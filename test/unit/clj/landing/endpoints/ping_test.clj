(ns landing.endpoints.ping-test
  (:require
   [clojure.test           :refer [deftest is]]
   [landing.endpoints.ping :as sut]))

(deftest ping-handler-test
  (is (= {:status 200
          :headers {"Content-Type" "text/plain"}
          :body "pong"}
         (sut/ping-handler {}))
      "Ping is returning pong"))
