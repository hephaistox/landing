(ns landing.endpoints.w3c-validation-test
  (:require
   [clojure.test                     :refer [deftest is testing]]
   [landing.endpoints.w3c-validation :as sut]))

(deftest only-our-own-sites-are-validated-test
  (testing "a foreign domain is refused before any request leaves"
    (doseq [domain ["https://example.com" "http://169.254.169.254" "file:///etc"]]
      (is (= 400
             (:status (sut/w3c-validate-handler {:parameters {:query {:html-id "about"
                                                                      :domain domain}}})))
          domain))))
