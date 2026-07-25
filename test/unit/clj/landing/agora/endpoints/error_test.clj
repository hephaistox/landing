(ns landing.agora.endpoints.error-test
  (:require
   [clojure.test                  :refer [deftest is testing]]
   [landing.agora.endpoints.error :as sut]
   [reitit.ring                   :as rring]))

(defn- throwing-handler
  "A ring handler whose route throws `ex`, guarded by the DB-aware exception
  middleware under test."
  [ex]
  (rring/ring-handler (rring/router ["/x"
                                     {:get {:handler (fn [_] (throw ex))
                                            :middleware [sut/exception-middleware]}}])))

(defn- status-for
  [ex]
  (:status ((throwing-handler ex)
            {:request-method :get
             :uri "/x"})))

(deftest db-failures-become-503
  (testing "the store's ::db-unavailable ex-info is surfaced as 503"
    (is (= 503
           (status-for (ex-info "database unavailable"
                                {:type :landing.agora.store-old/db-unavailable})))))
  (testing "a raw java.sql.SQLException (e.g. connection refused) is surfaced as 503"
    (is (= 503 (status-for (java.sql.SQLException. "connection refused")))))
  (testing "an unrelated error keeps the default handling (not a 503)"
    (is (not= 503 (status-for (ex-info "boom" {:type :something-else}))))))
