(ns landing.endpoints.default-handler-test
  (:require
   [clojure.test                      :refer [deftest is testing]]
   [landing.endpoints.default-handler :as sut]))

(defn- body->str
  "Coerce a prepared/served response body (byte array, string, or stream) to text."
  [body]
  (cond
    (string? body) body
    (bytes? body) (String. ^bytes body "UTF-8")
    (instance? java.io.InputStream body) (slurp body)
    :else (str body)))

(deftest exception-response-test
  (let [e (ex-info "boom" {:secret "do-not-leak"})]
    (testing "returns a 500 with an HTML content-type"
      (let [resp (sut/exception-response {} e)]
        (is (= 500 (:status resp)))
        (is (re-find #"text/html" (str (get-in resp [:headers "Content-Type"]))))))
    (testing "never echoes the exception message or ex-data to the client"
      (let [body (body->str (:body (sut/exception-response {} e)))]
        (is (not (re-find #"do-not-leak" body)))
        (is (not (re-find #"boom" body)))))
    (testing "serves the language-resolved 500 page: fr by default, en via the lang cookie"
      (let [fr (body->str (:body (sut/exception-response {} e)))
            en (body->str (:body (sut/exception-response {:headers {"cookie" "lang=en"}} e)))]
        (is (re-find #"Erreur inattendue" fr) "default (no cookie) serves the French /fr/500.html")
        (is (re-find #"Unexpected error" en) "lang=en cookie serves the English /en/500.html")))))
