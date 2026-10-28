(ns agora.entity.objection-test
  (:require
   [agora.entity.objection :as sut]
   [clojure.test           :refer [deftest is testing]]))

(def agora {:id #uuid "00000000-0000-4000-8000-000000000001"})
(def reader {:id #uuid "00000000-0000-4000-8000-000000000007"})
(def users
  [{:id #uuid "00000000-0000-4000-8000-000000000007"
    :provider :google
    :provider-id "google-r"}])

(defn- article
  [id minor]
  {:id id
   :type :article
   :name "ma-voiture-est-rapide"
   :lang :fr
   :major 1
   :minor minor
   :owner agora
   :content {:title "Ma voiture est rapide"
             :text "Ma voiture est rapide."
             :inputs []
             :published-at #inst "2026-01-01T00:00:00Z"}})

(def objection
  {:id #uuid "00000000-0000-4000-8000-000000000002"
   :type :objection
   :name "que-veut-dire-rapide"
   :lang :fr
   :major 1
   :status :closed
   :owner reader
   :documents [{:id #uuid "00000000-0000-4000-8000-000000000008"}]
   :content {:title "Que veut dire « rapide » ?"
             :text "« Rapide », ça veut dire quoi, au juste ?"
             :reason :omission
             :published-at #inst "2026-01-02T00:00:00Z"}})

(def edition-1
  {:id #uuid "00000000-0000-4000-8000-000000000005"
   :type :publication
   :name "edition-1"
   :lang :fr
   :major 1
   :status :closed
   :owner agora
   :contributors []
   :documents [(article #uuid "00000000-0000-4000-8000-000000000008" 0)]
   :content {:title "Édition 1"
             :text "Affirme."
             :published-at #inst "2026-01-01T00:00:00Z"}})

(def edition-2
  {:id #uuid "00000000-0000-4000-8000-000000000006"
   :type :publication
   :name "edition-2"
   :lang :fr
   :major 1
   :status :closed
   :owner agora
   :contributors []
   :answers [{:id #uuid "00000000-0000-4000-8000-000000000002"}]
   :documents [(article #uuid "00000000-0000-4000-8000-000000000009" 1)]
   :content {:title "Édition 2"
             :text "Définit."
             :published-at #inst "2026-01-03T00:00:00Z"}})

(defn- broken
  [publications objections]
  (set (map :rule (mapcat #((:check %) publications objections users) sut/rules))))

(deftest target-test
  (is (= {:id #uuid "00000000-0000-4000-8000-000000000008"} (sut/target objection)))
  (is (sut/closed? objection))
  (is (sut/draft? (assoc objection :status :draft))))

(deftest rules-test
  (is (empty? (broken [edition-1 edition-2] [objection])))
  (testing "an objection on two documents"
    (is ((broken
          [edition-1 edition-2]
          [(update objection :documents conj {:id #uuid "00000000-0000-4000-8000-000000000009"})])
         :objection/shape)))
  (testing "an objection without a text"
    (is ((broken [edition-1 edition-2] [(update objection :content dissoc :text)])
         :objection/shape)))
  (testing "an objection with a minor"
    (is ((broken [edition-1 edition-2] [(assoc objection :minor 0)]) :objection/shape)))
  (testing "an owner with a name"
    (is ((broken [edition-1 edition-2] [(assoc objection :owner (assoc reader :name "Lecteur"))])
         :objection/shape)))
  (testing "a backing citation that is no citation"
    (is ((broken [edition-1 edition-2] [(assoc-in objection [:content :inputs] [{:name "x"}])])
         :objection/shape)))
  (testing "an objection made without an account"
    (is ((broken [edition-1 edition-2] [(assoc objection :owner agora)]) :objection/owner)))
  (testing "an objection on an unknown version"
    (is ((broken
          [edition-1 edition-2]
          [(assoc objection :documents [{:id #uuid "00000000-0000-4000-8000-000000000010"}])])
         :objection/target)))
  (testing "an objection in another language than its target"
    (is ((broken [edition-1 edition-2] [(assoc objection :lang :en)]) :objection/target)))
  (testing "an objection on a draft"
    (is ((broken [(assoc edition-1
                         :status :open
                         :documents [(article #uuid "00000000-0000-4000-8000-000000000008" nil)])
                  edition-2]
                 [objection])
         :objection/target)))
  (testing "a new major while the previous one is a draft"
    (is
     ((broken
       [edition-1]
       [(assoc objection :status :draft)
        (assoc objection :id #uuid "00000000-0000-4000-8000-000000000003" :major 2 :status :draft)])
      :objection/identity)))
  (testing "an answer by someone else than the owner"
    (is ((broken [edition-1 (assoc edition-2 :owner reader)] [objection]) :objection/answer)))
  (testing "an answer that does not change the document"
    (is ((broken [edition-1 (assoc edition-2 :documents [])] [objection]) :objection/answer)))
  (testing "an answer to an unknown objection"
    (is ((broken [edition-1 edition-2] []) :objection/answer)))
  (testing "closed without any answer" (is ((broken [edition-1] [objection]) :objection/status)))
  (testing "still a draft once answered"
    (is ((broken [edition-1 edition-2] [(assoc objection :status :draft)]) :objection/status)))
  (testing "a draft answered by a publication still open"
    (is (empty? (broken [edition-1
                         (assoc edition-2
                                :status :open
                                :documents [(article #uuid "00000000-0000-4000-8000-000000000009"
                                                     nil)])]
                        [(assoc objection :status :draft)])))))

(deftest reason-test
  (is (empty? (broken [edition-1 edition-2]
                      [(assoc-in objection
                        [:content :inputs]
                        [{:type :definition
                          :name "rapide"
                          :lang :fr
                          :major 1}])]))
      "an omission of an article may be backed by a definition")
  (testing "a reason the target's type does not predefine"
    (is ((broken [edition-1 edition-2] [(assoc-in objection [:content :reason] :incomplete)])
         :objection/reason)))
  (testing "no reason"
    (is ((broken [edition-1 edition-2] [(update objection :content dissoc :reason)])
         :objection/shape)))
  (testing "backed by a document of a type the reason does not admit"
    (is ((broken [edition-1 edition-2]
                 [(update objection
                          :content assoc
                          :reason :unclear
                          :inputs [{:type :definition
                                    :name "rapide"
                                    :lang :fr
                                    :major 1}])])
         :objection/reason))))

(deftest publish-test
  (let [open (assoc edition-2
                    :status :open
                    :documents [(article #uuid "00000000-0000-4000-8000-000000000009" nil)])
        draft (assoc objection :status :draft)
        other (assoc objection
                     :id #uuid "00000000-0000-4000-8000-000000000004"
                     :name "autre"
                     :status :draft)
        {:keys [publication objections]}
        (sut/publish open [edition-1 open] [draft other] #inst "2026-01-04T00:00:00Z")]
    (is (= :closed (:status publication)))
    (is (= 1 (get-in publication [:documents 0 :minor])) "its documents are published")
    (is (= [(assoc draft :status :closed)] objections) "only the objection it answers, closed")
    (is (empty? (broken [edition-1 publication] [(first objections)]))
        "the result follows the rules")))
