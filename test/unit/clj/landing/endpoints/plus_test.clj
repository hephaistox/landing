(ns landing.endpoints.plus-test
  (:require
   [clojure.test           :refer [deftest is]]
   [landing.endpoints.plus :as sut]
   [reitit.core            :as r]
   [reitit.ring            :as rring]))

;; ********************************************************************************
;; Handler

(deftest plus-handler-test
  (is (= {:status 200
          :headers {"Content-Type" "text/plain"}
          :body {:total 25}}
         (sut/plus-handler {:parameters {:query {:x 12
                                                 :y 13}}}))
      "A simple successful addition")
  (is (= {:status 200
          :headers {"Content-Type" "text/plain"}
          :body {:total 0}}
         (sut/plus-handler {}))
      "Check default values are 0"))

;; ********************************************************************************
;; Router

(def router (rring/router (sut/plus "/foo")))

(deftest router-test
  (is (= "/foo"
         (-> (r/match-by-path router "/foo")
             :template))
      "Is the foo template found?")
  (is (= [:get :head :post :put :delete :connect :options :trace :patch]
         (-> (r/match-by-path router "/foo")
             :result
             keys))
      "Is the result key uptodate?"))

;; ********************************************************************************
;; Ring handler

(def test-handler (rring/ring-handler router))

(deftest plus-test
  (is (= 400
         (-> (test-handler {:protocol "HTTP/1.1"
                            :remote-addr "127.0.0.1"
                            :headers {"host" "localhost"}
                            :server-port 80
                            :uri "/foo"
                            :server-name "localhost"
                            :query-string "x=abc&y=10"
                            :scheme :http
                            :request-method :get})
             :status))
      "An exception raised as coercion is failing due to wrong type")
  (is (= 400
         (-> (test-handler {:protocol "HTTP/1.1"
                            :remote-addr "127.0.0.1"
                            :headers {"host" "localhost"}
                            :server-port 80
                            :uri "/foo"
                            :server-name "localhost"
                            :query-string "x=4&y=aa"
                            :scheme :http
                            :request-method :get})
             :status))
      "A request for which coercion is not working"))


(comment
  (require '[ring.mock.request :as mock])
  (-> (mock/request :get "/foo?x=abc&y=10")
      (mock/header "accept-encoding" "gzip"))
  ;;
)




