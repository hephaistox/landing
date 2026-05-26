(ns landing.endpoints.resource-test
  (:require
   [clojure.test               :refer [deftest is testing]]
   [landing.endpoints.resource :as sut]
   [reitit.ring                :as rring]
   [ring.mock.request          :as mock]))

(deftest cache-control-test
  (testing "Long-lived immutable cache for static assets"
    (doseq [uri ["/custom.css"
                 "/js/lang.js"
                 "/fontawesome/css/brands.css"
                 "/font/Roboto.woff2"
                 "/images/foo.png"
                 "/favicon.ico"]]
      (is (= "public, max-age=31536000, immutable" (#'sut/cache-control uri))
          (str "Static asset " uri " gets a long, immutable cache"))))
  (testing "Short cache for HTML so static updates ship quickly"
    (is (= "public, max-age=300" (#'sut/cache-control "/fr/index.html"))))
  (testing "Default cache for everything else"
    (is (= "public, max-age=600" (#'sut/cache-control "/robots.txt")))
    (is (= "public, max-age=600" (#'sut/cache-control nil)))))

(def ^:private byte-array-class (Class/forName "[B"))

(deftest resource-handler-test
  (let [h (rring/ring-handler (rring/router [] {}) sut/resource-handler)]
    (testing "A simple resource query"
      (let [resp (h {:request-method :get :uri "/custom.css"})]
        (is (= 200 (:status resp)))
        (is (= "text/css" (get-in resp [:headers "Content-Type"])))))
    (testing "Bodies are realized to byte arrays for in-memory caching"
      (let [resp (h {:request-method :get :uri "/print.css"})]
        (is (instance? byte-array-class (:body resp)))))
    (testing "Gzippable types are pre-compressed when the client asks for gzip"
      (let [resp (h (-> (mock/request :get "/custom.css")
                        (mock/header "accept-encoding" "gzip")))]
        (is (= "gzip" (get-in resp [:headers "Content-Encoding"])))
        (is (= "Accept-Encoding" (get-in resp [:headers "Vary"])))))
    (testing "Already-compressed binaries (PDF) are not gzipped"
      (let [resp (h (-> (mock/request :get "/cv_caumond.pdf")
                        (mock/header "accept-encoding" "gzip")))]
        (is (nil? (get-in resp [:headers "Content-Encoding"])))))))

(deftest response-headers-test
  (let [resp ((rring/ring-handler (rring/router [] {}) sut/resource-handler)
              {:request-method :get
               :uri "/custom.css"})]
    (is (= "public, max-age=31536000, immutable" (get-in resp [:headers "Cache-Control"]))
        "CSS files are served with a long, immutable Cache-Control")
    (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"]))
        "CORS is permissive on static assets")))


