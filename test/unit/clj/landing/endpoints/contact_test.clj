(ns landing.endpoints.contact-test
  (:require
   [clojure.test              :refer [deftest is]]
   [landing.endpoints.contact :as sut]))

(deftest handle-contact-test (is (sut/contact-route {})))
