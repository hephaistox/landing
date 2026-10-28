(ns agora.use-case-test
  (:require
   [agora.entity.objection :as objection]
   [agora.use-case         :as use-case]
   [agora.value.lang       :as lang]
   [agora.value.name       :as name]
   [agora.value.type       :as type]
   [clojure.string         :as str]
   [clojure.test           :refer [deftest is testing]]))

(defn- report
  "Short form of `violations`, for failure messages."
  [violations]
  (map (fn [{:keys [rule subject detail]}] [rule
                                            (select-keys subject [:type :name :lang :major :minor])
                                            detail])
       violations))

(defn- violations
  "Rules broken by `publications`, with the objections, persons and users of the use cases."
  ([publications] (violations publications (use-case/initial-objections)))
  ([publications objections]
   (violations publications objections (use-case/persons) (use-case/users)))
  ([publications objections persons users]
   (objection/violations publications objections persons users)))

(deftest snapshots-follow-the-rules-test
  (is (empty? (report (violations (use-case/initial-publications))))))

;; Each rule must catch a broken use case.

(defn- broken
  "Rules broken once `f` is applied to the initial publications."
  [f]
  (set (map :rule (violations (f (use-case/initial-publications))))))

(defn- update-doc
  "`publications` with `f` applied to the document named `doc-name` in `lang`."
  [publications doc-name lang f]
  (mapv (fn [p]
          (update p
                  :documents
                  (fn [ds]
                    (mapv #(if (and (= doc-name (:name %)) (= lang (:lang %))) (f %) %) ds))))
        publications))

(deftest rules-catch-broken-data-test
  (testing "an unknown type"
    (is ((broken #(update-doc % "les-miserables" :fr (fn [d] (assoc d :type :oeuvre))))
         :type/known)))
  (testing "a link without its language"
    (is ((broken
          #(update-doc
            %
            "cosette-est-exploitee"
            :fr
            (fn [d]
              (update-in d [:content :text] str/replace "les-miserables:fr@1" "les-miserables@1"))))
         :citation/links)))
  (testing "a negative minor"
    (is ((broken #(update-doc % "rapide" :fr (fn [d] (if (:minor d) (assoc d :minor -1) d))))
         :version/minor)))
  (testing "a document in a publication of another language"
    (is ((broken #(update-doc % "rapide" :en (fn [d] (assoc d :lang :fr)))) :publication/content)))
  (testing "an article written by someone not invited"
    (is ((broken (fn [ps] (mapv #(assoc % :contributors []) ps))) :publication/owner)))
  (testing "a gap in the minors"
    (is ((broken #(update-doc %
                              "ma-voiture-est-rapide"
                              :fr
                              (fn [d] (if (= 3 (:minor d)) (assoc d :minor 4) d))))
         :identity/minors))))

(deftest persons-and-users-follow-the-rules-test
  (let [publications (use-case/initial-publications)
        persons (use-case/persons)
        users (use-case/users)
        rules #(set (map :rule (violations publications (use-case/initial-objections) %1 %2)))]
    (testing "a named person missing from the persons"
      (is ((rules (vec (rest persons)) users) :publication/persons)))
    (testing "two users whose names differ only by case and accents"
      (is ((rules (conj persons
                        {:id #uuid "00000000-0000-4000-8000-000000000001"
                         :name "HÉPHAISTOX"})
                  (conj users
                        {:id #uuid "00000000-0000-4000-8000-000000000001"
                         :provider :google
                         :provider-id "google-x"}))
           :user/unique-name)))
    (testing "a user who is not a person"
      (is ((rules persons
                  (conj users
                        {:id #uuid "00000000-0000-4000-8000-000000000002"
                         :provider :google
                         :provider-id "google-y"}))
           :user/is-a-person)))
    (testing "a contributor without an account"
      (is ((rules persons (vec (rest users))) :publication/contributors)))))

(deftest names-come-from-titles-test
  ;; a name differs from its title only when the name drawn from it was already taken
  (let [versions (distinct (concat (for [p (use-case/initial-publications)
                                         x (cons p (:documents p))]
                                     (dissoc x :documents))
                                   (use-case/initial-objections)))]
    (doseq [[[t n] xs] (group-by (juxt :type :name) versions)
            :when (not (type/description? {:name n}))
            :let [first-title (-> (sort-by (comp :published-at :content) xs)
                                  first
                                  :content
                                  :title)
                  drawn (name/from-title first-title)
                  taken? (some #(and (= t (:type %))
                                     (= drawn (:name %))
                                     (some #{(:lang %)} (map :lang xs)))
                               versions)]]
      (is (or (= drawn n) taken?) (str t " " n " : « " first-title " »")))))

(deftest objections-follow-the-rules-test
  (let [publications (use-case/initial-publications)
        objections (use-case/initial-objections)
        rules #(set (map :rule (violations %1 %2)))
        update-objection (fn [os n major f]
                           (mapv #(if (and (= n (:name %)) (= major (:major %))) (f %) %) os))]
    (is (empty? (rules publications objections)))
    (testing "an objection in another language than its target"
      (is ((rules publications
                  (update-objection objections "que-veut-dire-rapide" 1 #(assoc % :lang :en)))
           :objection/target)))
    (testing "an objection on a draft"
      (is ((rules publications
                  (update-objection
                   objections
                   "que-veut-dire-rapide"
                   1
                   (fn [o]
                     (assoc o
                            :documents
                            [{:id (:id (first (filter #(= "enonce-universel" (:name %))
                                                      (mapcat :documents publications))))}]))))
           :objection/target)))
    (testing "an objection on an unknown version"
      (is ((rules publications
                  (update-objection
                   objections
                   "que-veut-dire-rapide"
                   1
                   #(assoc % :documents [{:id #uuid "00000000-0000-4000-8000-000000000001"}])))
           :objection/target)))
    (testing "an objection on two documents"
      (is ((rules publications
                  (update-objection objections
                                    "que-veut-dire-rapide"
                                    1
                                    #(update % :documents conj (first (:documents %)))))
           :objection/shape)))
    (testing "an answered objection still a draft"
      (is ((rules publications
                  (update-objection objections "que-veut-dire-rapide" 1 #(assoc % :status :draft)))
           :objection/status)))
    (testing "a new major while the previous one is still a draft"
      (is ((rules
            publications
            (update-objection objections "et-le-modele-en-general" 1 #(assoc % :status :draft)))
           :objection/identity)))
    (testing "an objection made without an account"
      (is ((rules publications
                  (update-objection
                   objections
                   "que-veut-dire-rapide"
                   1
                   #(assoc % :owner {:id #uuid "6dee51b8-bd9b-4104-aefc-e082c0e1644c"})))
           :objection/owner)))
    (testing "an answer that does not change the document"
      (is ((rules (mapv #(if (= "exemple-pedagogique-2" (:name %)) (assoc % :documents []) %)
                        publications)
                  objections)
           :objection/answer)))))

(deftest vocabulary-describes-every-type-test
  (let [published (filter #(some? (:minor %)) (mapcat :documents (use-case/initial-publications)))]
    (doseq [t type/types
            l lang/langs]
      (is (some
           #(and (= (type/description-name t) (:name %)) (= l (:lang %)) (= :definition (:type %)))
           published)
          (str t " " l)))))
