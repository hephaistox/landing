(ns agora.entity.publication-test
  (:require
   [agora.entity.publication :as sut]
   [clojure.test             :refer [deftest is testing]]))

(defn- id-of [suffix] (parse-uuid (str "00000000-0000-0000-0000-0000000000" suffix)))

(def agora {:id #uuid "00000000-0000-4000-8000-000000000001"})
(def reader {:id #uuid "00000000-0000-4000-8000-000000000006"})

(defn- doc
  [m]
  (-> (merge {:owner agora
              :type :definition
              :lang :fr
              :major 1
              :content {:title "T"
                        :text "un t."
                        :inputs []
                        :published-at #inst "2026-01-01T00:00:00Z"}}
             (dissoc m :content))
      (update :content merge (:content m))))

(def published
  {:id #uuid "00000000-0000-4000-8000-000000000002"
   :type :publication
   :name "pose"
   :lang :fr
   :major 1
   :status :closed
   :owner agora
   :contributors []
   :documents [(doc {:id (id-of "a1")
                     :name "rapide"
                     :minor 0})
               (doc {:id (id-of "a2")
                     :name "chrono"
                     :minor 0})]
   :content {:title "Pose"
             :text "Pose rapide."
             :published-at #inst "2026-01-01T00:00:00Z"}})

(def open
  {:id #uuid "00000000-0000-4000-8000-000000000003"
   :type :publication
   :name "suite"
   :lang :fr
   :major 1
   :status :open
   :owner agora
   :contributors []
   :documents [(doc {:id (id-of "b1")
                     :name "rapide"
                     :content {:text "atteindre 100 km/h."}})
               (doc {:id (id-of "b2")
                     :name "universel"})
               (doc {:id (id-of "b3")
                     :name "singulier"
                     :content {:text (str "voir l'[[id:"
                                          (id-of "b2")
                                          "|universel]] et le [[definition:chrono:fr@1]]")
                               :inputs [{:id (id-of "b2")}
                                        {:type :definition
                                         :name "chrono"
                                         :lang :fr
                                         :major 1}]}})]
   :content {:title "Suite"
             :text "Précise rapide et ajoute deux énoncés."
             :published-at #inst "2026-02-01T00:00:00Z"}})

(defn- broken [publications] (set (map :rule (mapcat #((:check %) publications) sut/rules))))

(deftest status-test (is (sut/closed? published)) (is (sut/open? open)))

(deftest from-publication-test
  (is (= published (sut/from-publication [published open] {:id (id-of "a1")})))
  (is (= open (sut/from-publication [published open] {:id (id-of "b2")})))
  (is (nil? (sut/from-publication [published open]
                                  {:id #uuid "00000000-0000-4000-8000-000000000007"}))))

(deftest named-persons-test
  (is (= #{agora reader} (set (sut/named-persons [(assoc published :contributors [reader])])))))

(deftest publish-test
  (let [p (sut/publish open [published open] #inst "2026-03-01T00:00:00Z")
        [rapide universel singulier] (:documents p)]
    (is (sut/closed? p))
    (is (= #inst "2026-03-01T00:00:00Z"
           (-> p
               :content
               :published-at)
           (-> singulier
               :content
               :published-at)))
    (testing "each draft gets the next minor of its major"
      (is (= 1 (:minor rapide)) "after the published 1.0")
      (is (= 0 (:minor universel)) "nothing published before"))
    (testing "citations and links by :id become citations by tnlr"
      (is (= [{:type :definition
               :name "universel"
               :lang :fr
               :major 1}
              {:type :definition
               :name "chrono"
               :lang :fr
               :major 1}]
             (-> singulier
                 :content
                 :inputs)))
      (is (= "voir l'[[definition:universel:fr@1|universel]] et le [[definition:chrono:fr@1]]"
             (-> singulier
                 :content
                 :text))))))

(deftest rules-test
  (is (empty? (broken [published open])))
  (testing "a publication without a text"
    (is ((broken [(update published :content dissoc :text)]) :publication/shape)))
  (testing "a canceled publication keeps its drafts"
    (is (empty? (broken [published (assoc open :status :canceled)]))))
  (testing "a canceled publication holding a published version"
    (is ((broken [(assoc published :status :canceled)]) :publication/status)))
  (testing "a publication with a minor"
    (is ((broken [(assoc published :minor 0)]) :publication/shape)))
  (testing "a publication without its contributors"
    (is ((broken [(dissoc published :contributors)]) :publication/shape)))
  (testing "a contributor with a name"
    (is ((broken [(assoc published :contributors [(assoc reader :name "Lecteur")])])
         :publication/shape)))
  (testing "a document that breaks its grammar"
    (is ((broken [(assoc-in published [:documents 0 :content :title] "")]) :publication/shape)))
  (testing "a document in another language"
    (is ((broken [(assoc-in published [:documents 0 :lang] :en)]) :publication/content)))
  (testing "a draft in a closed publication"
    (is ((broken [(update-in published [:documents 0] dissoc :minor)]) :publication/status)))
  (testing "a new major while the previous one is open"
    (is ((broken
          [open
           (assoc open :id #uuid "00000000-0000-4000-8000-000000000004" :major 2 :documents [])])
         :publication/majors)))
  (testing "two versions of a document in one publication"
    (is ((broken [(update published
                          :documents
                          conj
                          (doc {:id #uuid "00000000-0000-4000-8000-000000000008"
                                :name "rapide"
                                :minor 1}))])
         :publication/one-version)))
  (testing "a draft of the same publication cited by tnlr"
    (is ((broken [(assoc-in open
                   [:documents 2 :content :inputs 0]
                   {:type :definition
                    :name "universel"
                    :lang :fr
                    :major 1})])
         :publication/citations)))
  (testing "a draft of another publication cited by :id"
    (is ((broken [published
                  open
                  (assoc open
                         :id #uuid "00000000-0000-4000-8000-000000000005"
                         :name "autre"
                         :documents [(doc {:id #uuid "00000000-0000-4000-8000-000000000009"
                                           :name #uuid "00000000-0000-4000-8000-000000000009"
                                           :content {:inputs [{:id (id-of "b2")}]}})])])
         :publication/citations))))

(def persons [(assoc agora :name "Agora") (assoc reader :name "Lecteur")])

(def users
  [{:id (:id agora)
    :provider :password
    :provider-id "agora"}
   {:id (:id reader)
    :provider :password
    :provider-id "lecteur"}])

(deftest person-rules-test
  (let [check (fn [publications persons users]
                (set (map :rule
                          (mapcat #((:check %) publications persons users) sut/person-rules))))]
    (is (empty? (check [published] persons users)))
    (testing "a publication owned by a person without an account"
      (is ((check [published] persons (rest users)) :publication/owner)))
    (testing "a document without an owner"
      (is ((check [(update-in published [:documents 0] dissoc :owner)] persons users)
           :publication/owner)))
    (testing "a document owned by a user not invited"
      (is ((check [(assoc-in published [:documents 0 :owner] reader)] persons users)
           :publication/owner)))
    (testing "an invited contributor's document"
      (is (empty? (check [(-> published
                              (assoc :contributors [reader])
                              (assoc-in [:documents 0 :owner] reader))]
                         persons
                         users))))
    (testing "a person unknown"
      (is ((check [published]
                  [{:id #uuid "00000000-0000-4000-8000-000000000009"
                    :name "Agora"}]
                  users)
           :publication/persons)))
    (testing "a contributor without an account"
      (is ((check [(assoc published :contributors [reader])] persons (take 1 users))
           :publication/contributors)))))
