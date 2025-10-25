(ns landing.endpoints.resource-test
  (:require
   [clojure.test               :refer [deftest is]]
   [landing.endpoints.resource :as sut]
   [reitit.ring                :as rring]
   [ring.mock.request          :as mock]))

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


