(ns landing.endpoints.html.home-be-test
  (:require
   [clojure.test                   :refer [deftest is]]
   [landing.endpoints.html         :refer [html-middlewares]]
   [landing.endpoints.html.home-be :as sut]
   [reitit.ring                    :as rring]))

(def handler
  (rring/ring-handler (rring/router (sut/home-route "/") {:data {:middleware html-middlewares}})))

(deftest home-route-test
  (is (= 200
         (-> (handler {:request-method :get
                       :uri "/"})
             :status))
      "Is returning status 200")
  (is (= "!DOCTYPE"
         (-> (handler {:request-method :get
                       :uri "/"})
             :body
             (subs 1 9)))
      "Returns the html string")
  (is (= "gzip"
         (-> (handler {:request-method :get
                       :headers {"accept-encoding" "gzip"}
                       :uri "/"})
             :headers
             (get "Content-Encoding")))
      "Is response zipped if user agent request it?"))
