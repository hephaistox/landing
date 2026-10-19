(ns agora.use-case-test
  (:require
   [agora.name        :as name]
   [agora.publication :as publication]
   [agora.type        :as type]
   [agora.use-case    :as use-case]
   [clojure.string    :as str]
   [clojure.test      :refer [deftest is testing]]))

(defn- report
  "Short form of `violations`, for failure messages."
  [violations]
  (map (fn [{:keys [rule subject detail]}] [rule
                                            (select-keys subject [:type :name :lang :major :minor])
                                            detail])
       violations))

(defn- violations
  "Rules broken by `publications`, with the persons and users of the use cases."
  ([publications] (violations publications (use-case/persons) (use-case/users)))
  ([publications persons users] (publication/violations publications persons users)))

(deftest snapshots-follow-the-rules-test
  (is (empty? (report (violations (use-case/initial-publications))))))

(deftest scenarios-follow-the-rules-test
  (doseq [{:keys [file steps]} (use-case/scenarios)]
    (reduce (fn [before {:keys [step publications]}]
              (testing (str file " step " step)
                (is (empty? (report (publication/transition-violations before publications))))
                (let [after (publication/save before publications)]
                  (is (empty? (report (violations after))))
                  after)))
            (use-case/initial-publications)
            steps)))

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
    (is ((broken #(update-doc
                   %
                   "cosette-est-exploitee"
                   :fr
                   (fn [d] (update d :text str/replace "les-miserables:fr@1" "les-miserables@1"))))
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

(deftest transitions-catch-broken-publishing-test
  (let [before (use-case/initial-publications)
        open (first (filter #(= "la-methode-scientifique" (:name %)) before))
        published (publication/publish open before "2026-10-20T09:00:00Z")
        rules #(set (map :rule (publication/transition-violations before [%])))]
    (is (empty? (rules published)))
    (testing "a wrong minor"
      (is ((rules (update-in published [:documents 0 :minor] inc)) :publication/publish)))
    (testing "a closed publication changed"
      (let [closed (first (filter publication/closed? before))]
        (is ((rules (assoc closed :title "autre")) :publication/frozen))))))

(deftest persons-and-users-follow-the-rules-test
  (let [publications (use-case/initial-publications)
        persons (use-case/persons)
        users (use-case/users)
        rules #(set (map :rule (violations publications %1 %2)))]
    (testing "a person known under another name"
      (is ((rules (mapv #(update % :name str "!") persons) users) :publication/persons)))
    (testing "two users whose names differ only by case and accents"
      (is ((rules (conj persons
                        {:id "x"
                         :name "HÉPHAISTOX"})
                  (conj users
                        {:id "x"
                         :provider :google}))
           :user/unique-name)))
    (testing "a user who is not a person"
      (is ((rules persons
                  (conj users
                        {:id "y"
                         :provider :google}))
           :user/is-a-person)))
    (testing "a contributor without an account"
      (is ((rules persons (vec (rest users))) :publication/contributors)))))

(defn- all-states
  "The initial publications, then the publications after each step of each scenario."
  []
  (let [initial (use-case/initial-publications)]
    (cons initial
          (for [{:keys [steps]} (use-case/scenarios)
                state (reductions (fn [before {:keys [publications]}]
                                    (publication/save before publications))
                                  initial
                                  steps)]
            state))))

(deftest names-come-from-titles-test
  ;; a name differs from its title only when the name drawn from it was already taken
  (let [versions
        (distinct
         (for [state (all-states) p state x (cons p (:documents p))] (dissoc x :documents)))]
    (doseq [[[t n] xs] (group-by (juxt :type :name) versions)
            :when (not (type/description? {:name n}))
            :let [first-title (:title (first (sort-by :published-at xs)))
                  drawn (name/from-title first-title)
                  taken? (some #(and (= t (:type %))
                                     (= drawn (:name %))
                                     (some #{(:lang %)} (map :lang xs)))
                               versions)]]
      (is (or (= drawn n) taken?) (str t " " n " : « " first-title " »")))))
