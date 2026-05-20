(ns landing.endpoints.contact-test
  (:require
   [clojure.test              :refer [deftest is testing]]
   [landing.db]
   [landing.endpoints.contact :as sut]
   [landing.handler           :as handler]
   [ring.mock.request         :as mock]))

(deftest handle-contact-test (is (sut/contact-route {})))

(def ^:private fake-ip-counter (atom 0))

(defn- fresh-ip
  "Each call returns a unique IP so the contact rate limiter (2 req/6 s)
  doesn't 429 our test sequence."
  []
  (let [n (swap! fake-ip-counter inc)] (str "10.0.0." n)))

(defn- post-form
  "POST a form to /contact through the full handler chain."
  [params]
  (let [h (handler/handler)]
    (h (-> (mock/request :post "/contact")
           (mock/header "content-type" "application/x-www-form-urlencoded")
           (assoc :remote-addr (fresh-ip))
           (mock/body params)))))

(def ^:private valid-form
  {:company "Acme"
   :firstname "Jane"
   :name "Doe"
   :mail "jane@example.com"
   :phone "0601020304"
   :adress ""})

(deftest contact-happy-path-test
  (testing "Valid submission with empty honeypot returns a 302 to /articles/contact-validated"
    (let [emails-sent (atom [])
          queries-executed (atom 0)]
      (with-redefs [sut/send-email (fn [body] (swap! emails-sent conj body))
                    landing.db/execute-query (fn [_ _] (swap! queries-executed inc))]
        (let [resp (post-form valid-form)]
          (is (= 302 (:status resp)))
          (is (= "/articles/contact-validated" (get-in resp [:headers "Location"])))
          ;; send-email is fired in a future — give it a moment to land
          (Thread/sleep 100)
          (is (= 1 (count @emails-sent)) "exactly one email is sent")
          (is (re-find #"Acme" (first @emails-sent)) "email mentions the company")
          (is (re-find #"jane@example.com" (first @emails-sent)) "email mentions the address"))))))

(deftest contact-server-side-validation-test
  (testing "Empty company field is rejected"
    (let [resp (post-form (assoc valid-form :company ""))] (is (= 400 (:status resp)))))
  (testing "Empty firstname field is rejected"
    (let [resp (post-form (assoc valid-form :firstname ""))] (is (= 400 (:status resp)))))
  (testing "Empty name field is rejected"
    (let [resp (post-form (assoc valid-form :name ""))] (is (= 400 (:status resp)))))
  (testing "Empty phone field is rejected"
    (let [resp (post-form (assoc valid-form :phone ""))] (is (= 400 (:status resp)))))
  (testing "Malformed mail field is rejected"
    (let [resp (post-form (assoc valid-form :mail "not-an-email"))] (is (= 400 (:status resp)))))
  (testing "Filled honeypot is rejected"
    (let [resp (post-form (assoc valid-form :adress "spam-bot-text"))]
      (is (= 400 (:status resp))))))
