(ns agora.action-test
  (:require
   [agora.action-case :as action-case]
   [agora.repository  :as repository]
   [clojure.test      :refer [deftest is testing]]))

(deftest actions-test
  (let [{:keys [valid errors]} (action-case/split-steps (action-case/all-steps))]
    (is (empty? errors))
    (doseq [step valid]
      (testing (str (:file step) " : " (:description step))
        (let [{:keys [after expected actual error]} (action-case/run step)]
          (is (nil? error))
          (when-not error
            (is (= expected actual))
            (is (empty? (map (juxt :rule :subject :detail) (repository/violations after))))))))))

(defn failing-action
  "An action that always throws."
  {:parameters [:map]}
  [_ _ _]
  (throw (ex-info "plante" {:cause :test})))

(defn refusing-action
  "An action that always refuses."
  {:parameters [:map]}
  [_ _ _]
  (throw (ex-info "refusé" {:reason :test})))

(defn undeclared-action "An action without the grammar of its parameters." [_ _ _] {})

(defn clashing-action
  "An action creating a person whose id is already there."
  {:parameters [:map]}
  [repository _ _]
  {:created {:persons [(first (:persons repository))]}})

(def step
  {:file "test.edn"
   :description "Anthony signs up."
   :by :anonymous
   :action 'agora.action.sign-up/sign-up
   :input {:description "Empty."
           :repository {}}
   :parameter {:description "Anthony."
               :data {:id #uuid "00000000-0000-4000-8000-000000000001"
                      :name "Anthony"
                      :provider :google
                      :provider-id "google-a"}}
   :output {:description "Anthony."}})

(deftest split-steps-test
  (let [split #(action-case/split-steps [%])
        error #(-> %
                   split
                   :errors
                   first)
        kind #(:kind (error %))]
    (is (= {:errors []} (dissoc (split step) :valid)))
    (is (= 1 (count (:valid (split step)))))
    (testing "no action" (is (= :invalid-step (kind (dissoc step :action)))))
    (testing "no parameter" (is (= :invalid-step (kind (dissoc step :parameter)))))
    (testing "an unexpected key" (is (= :invalid-step (kind (assoc step :foo :bar)))))
    (testing "the error says what is wrong"
      (is (contains? (:error (error (dissoc step :parameter))) :parameter)))
    (testing "an output on an unknown kind"
      (is (= :invalid-step (kind (assoc-in step [:output :created] {:foo []})))))
    (testing "an action in no namespace"
      (is (= :unknown-action (kind (assoc step :action 'agora.nowhere/sign-up)))))
    (testing "an action missing from its namespace"
      (is (= :unknown-action (kind (assoc step :action 'agora.action.sign-up/nothing)))))
    (testing "an action without the grammar of its parameters"
      (is (= :undeclared-parameters (kind (assoc step :action `undeclared-action)))))
    (testing "parameters that do not follow the action's grammar"
      (let [e (error (assoc-in step [:parameter :data :provider] :github))]
        (is (= :invalid-parameters (:kind e)))
        (is (contains? (:error e) :provider))))
    (testing "a Google account without its provider id"
      (is (= :invalid-parameters (kind (update-in step [:parameter :data] dissoc :provider-id)))))
    (testing "a snapshot that does not exist"
      (is (= :unknown-snapshot (kind (assoc-in step [:input :snapshots] ["nowhere.edn"])))))
    (testing "an input that breaks rules"
      (is (= :invalid-input
             (kind (assoc-in step
                    [:input :repository]
                    {:users [{:id #uuid "00000000-0000-4000-8000-000000000002"
                              :provider :google
                              :provider-id "google-x"}]})))))))

(deftest run-test
  (let [run #(action-case/run (first (:valid (action-case/split-steps [%]))))]
    (is (= {:created {:persons [{:id #uuid "00000000-0000-4000-8000-000000000001"
                                 :name "Anthony"}]
                      :users [{:id #uuid "00000000-0000-4000-8000-000000000001"
                               :provider :google
                               :provider-id "google-a"}]}}
           (:actual (run step))))
    (testing "an action that throws without a reason"
      (is (= {:kind :action-failed
              :file "test.edn"
              :description "Anthony signs up."
              :action `failing-action
              :message "plante"
              :data {:cause :test}}
             (:error (run (assoc step :action `failing-action))))))
    (testing "an action whose changes do not apply"
      (is (= :action-failed
             (-> step
                 (assoc :action `clashing-action)
                 (assoc-in [:input :repository]
                           {:persons [{:id #uuid "00000000-0000-4000-8000-000000000003"
                                       :name "Han"}]})
                 run
                 :error
                 :kind))))
    (testing "an action that refuses, with its reason"
      (is (= {:error {:reason :test}} (:actual (run (assoc step :action `refusing-action))))))))

(deftest input-test
  (testing "a literal repository"
    (is (= repository/empty-repository (action-case/input {:input {:repository {}}}))))
  (testing "snapshots, then the repository on top"
    (let [repo (action-case/input
                {:input {:snapshots ["type-vocabulary.edn"]
                         :repository {:persons [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                                 :name "Agora!"}]}}})]
      (is (= 2 (count (:publications repo))))
      (is (seq (:users repo)))
      (is (= "Agora!"
             (some #(when (= #uuid "00000000-0000-0000-0000-000000000001" (:id %)) (:name %))
                   (:persons repo))))
      (is (= (count (distinct (map :id (:persons repo)))) (count (:persons repo)))))))
