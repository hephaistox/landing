(ns landing.endpoints.html.article-be-test
  (:require
   [clojure.test                      :refer [deftest is]]
   [landing.endpoints.html            :refer [html-middlewares]]
   [landing.endpoints.html.article-be :as sut]
   [reitit.ring                       :as rring]))

(def handler
  (rring/ring-handler (rring/router (sut/article-route "/art")
                                    {:data {:middleware html-middlewares}})))

(handler {:request-method :get
          :uri "/art/arg"})

(deftest article-test
  (is (= 200
         (-> (handler {:request-method :get
                       :uri "/art/privacy"})
             :status))
      "An article defined in article-map returns 200")
  (is (= 404
         (-> (handler {:request-method :get
                       :uri "/art/non-existing-article"})
             :status))
      "An article which is not defined in article-map returns 404")
  (is (= "!DOCTYPE"
         (-> (handler {:request-method :get
                       :uri "/art/privacy"})
             :body
             (subs 1 9)))
      "Returns the html string"))
