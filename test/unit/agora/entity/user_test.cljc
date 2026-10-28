(ns agora.entity.user-test
  (:require
   [agora.entity.user :as sut]
   [clojure.test      :refer [deftest is testing]]))

(def persons
  [{:id #uuid "00000000-0000-4000-8000-000000000001"
    :name "Hephaistox"}
   {:id #uuid "00000000-0000-4000-8000-000000000002"
    :name "Victor hugo"}])

(defn- broken [persons users] (set (map :rule (mapcat #((:check %) persons users) sut/rules))))

(deftest user?-test
  (is (sut/user? {:id #uuid "00000000-0000-4000-8000-000000000001"
                  :provider :google
                  :provider-id "google-1"}))
  (is (sut/user? {:id #uuid "00000000-0000-4000-8000-000000000001"
                  :provider :password
                  :provider-id "hephaistox"}))
  (is (not (sut/user? {:id #uuid "00000000-0000-4000-8000-000000000001"
                       :provider :github})))
  (is (not (sut/user? {:id #uuid "00000000-0000-4000-8000-000000000001"
                       :name "Hephaistox"
                       :provider :google
                       :provider-id "google-1"}))))

(deftest name-key-test
  (is (= "hephaistox" (sut/name-key "  HÉPHAISTOX ")))
  (is (= "eleve" (sut/name-key "Élève"))))

(deftest rules-test
  (is (empty? (broken persons
                      [{:id #uuid "00000000-0000-4000-8000-000000000001"
                        :provider :google
                        :provider-id "google-1"}])))
  (testing "a user that is not a person"
    (is ((broken persons
                 [{:id #uuid "00000000-0000-4000-8000-000000000004"
                   :provider :google
                   :provider-id "google-9"}])
         :user/is-a-person)))
  (testing "a user without a provider"
    (is ((broken persons [{:id #uuid "00000000-0000-4000-8000-000000000001"}]) :user/shape)))
  (testing "two users whose names differ only by case and accents"
    (is ((broken (conj persons
                       {:id #uuid "00000000-0000-4000-8000-000000000003"
                        :name "HÉPHAISTOX"})
                 [{:id #uuid "00000000-0000-4000-8000-000000000001"
                   :provider :google
                   :provider-id "google-1"}
                  {:id #uuid "00000000-0000-4000-8000-000000000003"
                   :provider :password
                   :provider-id "heph"}])
         :user/unique-name)))
  (testing "a person without an account may share a user's name"
    (is (empty? (broken (conj persons
                              {:id #uuid "00000000-0000-4000-8000-000000000003"
                               :name "Hephaistox"})
                        [{:id #uuid "00000000-0000-4000-8000-000000000001"
                          :provider :google
                          :provider-id "google-1"}])))))

(deftest validate-test
  (is (nil? (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                           :provider :google
                           :provider-id "google-1"})))
  (testing "an unknown provider"
    (is (contains? (:error (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                                          :provider :github}))
                   :provider)))
  (testing "the name belongs to the person, not the user"
    (is (contains? (:error (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                                          :provider :google
                                          :provider-id "google-1"
                                          :name "Hephaistox"}))
                   :name))))

(deftest provider-id-test
  (testing "a Google account has its provider id"
    (is (contains? (:error (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                                          :provider :google}))
                   :provider-id)))
  (testing "a password account has its login"
    (is (contains? (:error (sut/validate {:id #uuid "00000000-0000-4000-8000-000000000001"
                                          :provider :password}))
                   :provider-id)))
  (testing "a Google account opens one user only"
    (is ((broken persons
                 [{:id #uuid "00000000-0000-4000-8000-000000000001"
                   :provider :google
                   :provider-id "g"}
                  {:id #uuid "00000000-0000-4000-8000-000000000002"
                   :provider :google
                   :provider-id "g"}])
         :user/unique-provider-id)))
  (testing "a login opens one user only"
    (is ((broken persons
                 [{:id #uuid "00000000-0000-4000-8000-000000000001"
                   :provider :password
                   :provider-id "heph"}
                  {:id #uuid "00000000-0000-4000-8000-000000000002"
                   :provider :password
                   :provider-id "heph"}])
         :user/unique-provider-id)))
  (testing "the same id at two providers is two accounts"
    (is (empty? (broken persons
                        [{:id #uuid "00000000-0000-4000-8000-000000000001"
                          :provider :password
                          :provider-id "heph"}
                         {:id #uuid "00000000-0000-4000-8000-000000000002"
                          :provider :google
                          :provider-id "heph"}])))))

