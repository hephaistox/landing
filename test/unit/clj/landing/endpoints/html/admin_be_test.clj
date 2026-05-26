(ns landing.endpoints.html.admin-be-test
  (:require
   [clojure.test                    :refer [deftest is]]
   [landing.endpoints.html          :refer [html-middlewares]]
   [landing.endpoints.html.admin-be :as sut]
   [reitit.ring                     :as rring]))

(def router
  (rring/ring-handler (rring/router (sut/admin-route "/") {:data {:middleware html-middlewares}})))

(defn- body->string [body]
  (cond
    (string? body) body
    (instance? (Class/forName "[B") body) (String. ^bytes body "UTF-8")
    :else (str body)))

(deftest router-test
  (is (= 200
         (-> (router {:request-method :get
                      :uri "/"})
             :status))
      "Is returning status 200")
  (is (= "!DOCTYPE"
         (-> (router {:request-method :get
                      :uri "/"})
             :body
             body->string
             (subs 1 9)))
      "Returns the html string")
  (is (= "gzip"
         (-> (router {:request-method :get
                      :headers {"accept-encoding" "gzip"}
                      :uri "/"})
             :headers
             (get "Content-Encoding")))
      "Is response zipped if user agent request it?"))
