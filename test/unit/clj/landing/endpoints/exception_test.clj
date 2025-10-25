(ns landing.endpoints.exception-test
  (:require
   [clojure.test                :refer [deftest is]]
   [landing.endpoints.exception :as sut]))

(deftest exception-handler-test (is (thrown? Exception (sut/exception-handler {}))))
