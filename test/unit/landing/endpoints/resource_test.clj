(ns landing.endpoints.resource-test
  (:require
   [clojure.test               :refer [deftest is testing]]
   [env]
   [landing.endpoints.resource :as sut]
   [reitit.ring                :as rring]
   [ring.mock.request          :as mock]))

(def ^:private static-assets
  ["/css/custom.css"
   "/js/lang.js"
   "/fontawesome/css/brands.css"
   "/font/Roboto.woff2"
   "/images/foo.png"
   "/favicon.ico"])

(deftest cache-control-test
  (testing "In :prod, static assets get a long, immutable cache (fingerprinted at deploy)"
    (with-redefs [env/env :prod]
      (doseq [uri static-assets]
        (is (= "public, max-age=31536000, immutable" (#'sut/cache-control uri))
            (str "Static asset " uri " gets a long, immutable cache in prod")))))
  (testing "In :dev, static assets are no-cache (not fingerprinted; avoids stale bundles)"
    (with-redefs [env/env :dev]
      (doseq [uri static-assets]
        (is (= "no-cache" (#'sut/cache-control uri))
            (str "Static asset " uri " is no-cache in dev")))))
  (testing "HTML must revalidate every time, in any env"
    (doseq [e [:prod :dev]]
      (with-redefs [env/env e] (is (= "no-cache" (#'sut/cache-control "/fr/index.html"))))))
  (testing "Default cache for everything else"
    (is (= "public, max-age=600" (#'sut/cache-control "/robots.txt")))
    (is (= "public, max-age=600" (#'sut/cache-control nil)))))

(def ^:private byte-array-class (Class/forName "[B"))

(deftest resource-handler-test
  (let [h (rring/ring-handler (rring/router [] {}) sut/resource-handler)]
    (testing "A simple resource query"
      (let [resp (h {:request-method :get
                     :uri "/css/custom.css"})]
        (is (= 200 (:status resp)))
        (is (= "text/css" (get-in resp [:headers "Content-Type"])))))
    (testing "Bodies are realized to byte arrays for in-memory caching"
      (let [resp (h {:request-method :get
                     :uri "/css/print.css"})]
        (is (instance? byte-array-class (:body resp)))))
    (testing "Gzippable types are pre-compressed when the client asks for gzip"
      (let [resp (h (-> (mock/request :get "/css/custom.css")
                        (mock/header "accept-encoding" "gzip")))]
        (is (= "gzip" (get-in resp [:headers "Content-Encoding"])))
        (is (= "Accept-Encoding" (get-in resp [:headers "Vary"])))))
    (testing "Already-compressed binaries (PDF) are not gzipped"
      (let [resp (h (-> (mock/request :get "/cv_caumond.pdf")
                        (mock/header "accept-encoding" "gzip")))]
        (is (nil? (get-in resp [:headers "Content-Encoding"])))))))

(defn- css-response
  []
  ((rring/ring-handler (rring/router [] {}) sut/resource-handler)
   {:request-method :get
    :uri "/css/custom.css"}))

(deftest response-headers-test
  (testing "In :prod, CSS is served with a long, immutable Cache-Control"
    (with-redefs [env/env :prod]
      (is (= "public, max-age=31536000, immutable"
             (get-in (css-response) [:headers "Cache-Control"])))))
  (testing "In :dev, CSS is served no-cache (dev assets are not fingerprinted)"
    (with-redefs [env/env :dev]
      (is (= "no-cache" (get-in (css-response) [:headers "Cache-Control"])))))
  (testing "CORS is permissive on static assets"
    (is (= "*" (get-in (css-response) [:headers "Access-Control-Allow-Origin"])))))


