(ns landing.endpoints.cached-response-test
  (:require
   [clojure.test                      :refer [deftest is testing]]
   [landing.endpoints.cached-response :as sut])
  (:import (java.io ByteArrayInputStream)
           (java.util.zip GZIPInputStream)))

(def ^:private byte-array-class (Class/forName "[B"))

(defn- ungzip
  "Round-trip helper: gzipped bytes → original string."
  [^bytes data]
  (with-open [in (GZIPInputStream. (ByteArrayInputStream. data))] (slurp in)))

(defn- bytes->string [^bytes b] (String. b "UTF-8"))

;; ---------------------------------------------------------------------------
;; prepare
;; ---------------------------------------------------------------------------

(deftest prepare-test
  (testing "nil response returns nil" (is (nil? (sut/prepare nil))))
  (testing "nil body returns nil (no point caching an empty response)"
    (is (nil? (sut/prepare {:status 200
                            :headers {}
                            :body nil}))))
  (testing "string body is realized to a UTF-8 byte array"
    (let [resp (sut/prepare {:status 200
                             :headers {"Content-Type" "text/plain"}
                             :body "héllo"})]
      (is (instance? byte-array-class (:body resp)))
      (is (= "héllo" (bytes->string (:body resp))))))
  (testing "InputStream body is drained to bytes"
    (let [resp (sut/prepare {:status 200
                             :headers {"Content-Type" "text/plain"}
                             :body (ByteArrayInputStream. (.getBytes "stream" "UTF-8"))})]
      (is (instance? byte-array-class (:body resp)))
      (is (= "stream" (bytes->string (:body resp))))))
  (testing "byte-array body is passed through (no double copy)"
    (let [raw (.getBytes "raw" "UTF-8")
          resp (sut/prepare {:status 200
                             :headers {"Content-Type" "text/plain"}
                             :body raw})]
      (is (identical? raw (:body resp)))))
  (testing "gzippable content-type also gets a :gzipped variant"
    (let [body "<html><body>hi</body></html>"
          resp (sut/prepare {:status 200
                             :headers {"Content-Type" "text/html"}
                             :body body})]
      (is (instance? byte-array-class (:gzipped resp)))
      (is (= body (ungzip (:gzipped resp))))
      (is (< (count (:gzipped resp)) (* 2 (count body)))
          "gzipped bytes exist (size may be > raw for tiny inputs, but they exist)")))
  (testing "content-type with charset parameter is recognized as gzippable"
    (let [resp (sut/prepare {:status 200
                             :headers {"Content-Type" "text/html; charset=utf-8"}
                             :body "<p>x</p>"})]
      (is (some? (:gzipped resp)))))
  (testing "header lookup is case-insensitive"
    (let [resp (sut/prepare {:status 200
                             :headers {"content-type" "application/json"}
                             :body "{}"})]
      (is (some? (:gzipped resp)))))
  (testing "non-gzippable types (already-compressed binaries) get no :gzipped"
    (doseq [ct ["image/png"
                "image/jpeg"
                "image/webp"
                "application/pdf"
                "font/woff2"
                "application/zip"]]
      (let [resp (sut/prepare {:status 200
                               :headers {"Content-Type" ct}
                               :body (.getBytes "binary" "UTF-8")})]
        (is (nil? (:gzipped resp)) (str ct " must not be pre-gzipped")))))
  (testing "missing content-type yields no :gzipped"
    (let [resp (sut/prepare {:status 200
                             :headers {}
                             :body "x"})]
      (is (nil? (:gzipped resp))))))

;; ---------------------------------------------------------------------------
;; serve
;; ---------------------------------------------------------------------------

(defn- prep-html
  [body]
  (sut/prepare {:status 200
                :headers {"Content-Type" "text/html"}
                :body body}))

(deftest serve-test
  (testing "nil prepared response → nil" (is (nil? (sut/serve nil {}))))
  (testing "client without Accept-Encoding gets the raw body and Vary header"
    (let [prepared (prep-html "<p>x</p>")
          out (sut/serve prepared {:headers {}})]
      (is (= "<p>x</p>" (bytes->string (:body out))))
      (is (nil? (get-in out [:headers "Content-Encoding"])))
      (is (= "Accept-Encoding" (get-in out [:headers "Vary"])))
      (is (not (contains? out :gzipped)) ":gzipped is stripped from the served response")))
  (testing "client that accepts gzip gets the gzipped body + Content-Encoding"
    (let [body "<html><body>hello world</body></html>"
          out (sut/serve (prep-html body) {:headers {"accept-encoding" "gzip, deflate"}})]
      (is (= "gzip" (get-in out [:headers "Content-Encoding"])))
      (is (= "Accept-Encoding" (get-in out [:headers "Vary"])))
      (is (= body (ungzip (:body out))))))
  (testing "Accept-Encoding header is matched case-insensitively"
    (let [out (sut/serve (prep-html "<p>x</p>") {:headers {"Accept-Encoding" "GZIP"}})]
      (is (= "gzip" (get-in out [:headers "Content-Encoding"])))))
  (testing "no :gzipped variant → raw bytes even when client accepts gzip"
    (let [prepared (sut/prepare {:status 200
                                 :headers {"Content-Type" "image/png"}
                                 :body (.getBytes "binary" "UTF-8")})
          out (sut/serve prepared {:headers {"accept-encoding" "gzip"}})]
      (is (nil? (get-in out [:headers "Content-Encoding"])))
      (is (= "binary" (bytes->string (:body out))))))
  (testing "pre-existing response headers are preserved"
    (let [prepared (-> (prep-html "<p>x</p>")
                       (assoc-in [:headers "X-Custom"] "kept"))
          out (sut/serve prepared {:headers {}})]
      (is (= "kept" (get-in out [:headers "X-Custom"]))))))

;; ---------------------------------------------------------------------------
;; cache-fn
;; ---------------------------------------------------------------------------

(deftest cache-fn-dev-test
  (testing "In :dev the builder is called every time"
    (when-not sut/prod?
      (let [calls (atom 0)
            cached (sut/cache-fn (fn [_] (swap! calls inc) {:status 200}))]
        (dotimes [_ 3] (cached :anything))
        (is (= 3 @calls))))))

(deftest cache-fn-prod-test
  (when sut/prod?
    (testing "In :prod the builder is called once per cache key"
      (let [calls (atom 0)
            cached (sut/cache-fn (fn [x]
                                   (swap! calls inc)
                                   {:status 200
                                    :x x}))]
        (is (= {:status 200
                :x :a}
               (cached :a)))
        (is (= {:status 200
                :x :a}
               (cached :a)))
        (is (= 1 @calls) "second call for :a is served from cache")
        (cached :b)
        (is (= 2 @calls) "different arg → new call"))))
  (testing "key-fn lets callers cache by a subset of args"
    (when sut/prod?
      (let [calls (atom 0)
            cached (sut/cache-fn (fn [request] (swap! calls inc) {:uri (:uri request)}) :uri)]
        (cached {:uri "/a"
                 :headers {"cookie" "x=1"}})
        (cached {:uri "/a"
                 :headers {"cookie" "x=2"}})
        (is (= 1 @calls) "different cookies on same URI hit the same cache entry"))))
  (testing "Builder that returns nil is not cached (so it can retry later)"
    (when sut/prod?
      (let [calls (atom 0)
            cached (sut/cache-fn (fn [_] (swap! calls inc) nil))]
        (cached :x)
        (cached :x)
        (is (= 2 @calls))))))
