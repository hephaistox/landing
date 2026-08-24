(ns landing.endpoints.swagger-test
  (:require
   [clojure.test              :refer [deftest is]]
   [landing.endpoints.swagger :as sut]
   [muuntaja.core             :as m]
   [reitit.ring               :as rring]))

(defn test-handler
  [_http-request]
  {:status 200
   :headers {}
   :body "Anthony"})

(def handler
  (rring/ring-handler (rring/router (sut/api-swagger "/api") {:data {:muuntaja m/instance}})))

(deftest swagger-middleware-test
  (is (= {:status 200
          :body :body-place-holder
          :headers {"Content-Type" "application/json; charset=utf-8"}}
         (-> (handler {:request-method :get
                       :uri "/api/swagger.json"})
             (assoc :body :body-place-holder))))
  (is (= {:status 200
          :headers ["Content-Length" "Last-Modified" "Content-Type"]
          :body true}
         (-> (handler {:request-method :get
                       :uri "/api/api-docs/"})
             (update :headers keys)
             (update :body some?)))))
