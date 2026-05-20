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

(deftest resource-handler-test
  (is (= [[:status :headers :body] 200 "text/css"]
         ((juxt keys :status #(get-in % [:headers "Content-Type"]))
          ((rring/ring-handler (rring/router [] {}) sut/resource-handler)
           {:request-method :get
            :uri "/custom.css"})))
      "A simple resource query")
  (is (->> ((rring/ring-handler (rring/router [] {}) sut/resource-handler)
            {:request-method :get
             :uri "/print.css"})
           :body
           (instance? java.io.File))
      "Is the returned body a simple file")
  (is (= "gzip"
         (-> ((rring/ring-handler (rring/router [] {}) sut/resource-handler)
              (-> (mock/request :get "/cv_caumond.pdf")
                  (mock/header "accept-encoding" "gzip")))
             (get-in [:headers "Content-Encoding"])))
      "When the file is big enough, it should be compressed"))

(deftest response-headers-test
  (let [resp ((rring/ring-handler (rring/router [] {}) sut/resource-handler)
              {:request-method :get
               :uri "/custom.css"})]
    (is (= "public, max-age=31536000, immutable" (get-in resp [:headers "Cache-Control"]))
        "CSS files are served with a long, immutable Cache-Control")
    (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"]))
        "CORS is permissive on static assets")))


