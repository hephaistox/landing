(ns landing.handler-test
  (:require
   [clojure.test    :refer [deftest is testing]]
   [landing.handler :as sut]
   [reitit.core     :as r]))

(deftest routes-test
  (is (some? (r/match-by-path sut/router "/")) "home")
  (is (some? (r/match-by-path sut/router "")) "home")
  (is (= {:status 200
          :headers {"Content-Type" "text/plain"
                    "X-Frame-Options" "DENY"}
          :body "pong"}
         ((get-in (r/match-by-path sut/router "/ping") [:result :get :handler]) {}))
      "ping")
  (testing "articles"
    (is (= "privacy"
           (get-in (r/match-by-path sut/router "/articles/privacy") [:path-params :article-id]))
        "Path parameter")
    (is (= "privacy"
           (get-in (r/match-by-path sut/router "/articles/privacy") [:path-params :article-id]))
        "Path parameter"))
  (is (with-out-str ((get-in (r/match-by-path sut/router "/exception") [:result :get :handler])
                     {}))))
