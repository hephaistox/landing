(ns agora.action-case
  "Runs the action use cases of `agora/use_cases/actions`, one EDN file each, a vector of
  steps. A step says who acts (`:by`) and which action (`:action`, the function's symbol),
  and three parts, each with a `:description`:

  - `:input`: the repository the step starts from. Each step starts from its own input. It
    is `:repository` alone, missing kinds empty; or, with `:snapshots`, the persons and users
    of the use cases, then the publications and objections of those snapshots, then
    `:repository`, whose entities are added or replace those of the same `:id`;
  - `:parameter`: the parameters of the action (`:data`);
  - `:output`: what the step creates or changes in the repository (`:created`, `:changed`),
    or the refusal it expects (`:error {:reason …}`).

  A step:

  ```clojure
  {:description \"Anthony signs up with his Google account.\"
   :by :anonymous
   :action agora.action.sign-up/sign-up
   :input {:description \"The empty repository.\"
           :repository {}}
   :parameter {:description \"His display name, and Google to log in.\"
               :data {:id #uuid \"05ca77b3-1355-4f39-b59c-6ea1dbb5a13d\"
                      :name \"Anthony\"
                      :provider :google :provider-id \"google-05ca77b3\"}}
   :output {:description \"A person, and the user that is his account.\"
            :created {:persons [{:id #uuid \"05ca77b3-1355-4f39-b59c-6ea1dbb5a13d\" :name \"Anthony\"}]
                      :users [{:id #uuid \"05ca77b3-1355-4f39-b59c-6ea1dbb5a13d\" :provider :google :provider-id \"google-05ca77b3\"}]}}}
  ```"
  (:require
   [agora.repository :as repository]
   [agora.use-case   :as use-case]
   [auto-core.schema :as schema]
   [clojure.edn      :as edn]
   [clojure.java.io  :as io]
   [clojure.string   :as str]))

(def dir "agora/use_cases/actions")

(def step-schema
  "The grammar of a step. The `:data` of `:parameter` follows the grammar its action
  declares; the `:repository` of `:input` follows the rules of a repository."
  [:map {:closed true}
   [:description [:string {:min 1}]]
   [:by
    [:or
     [:= :anonymous]
     [:map {:closed true}
      [:id :uuid]]]]
   [:action :qualified-symbol]
   [:input
    [:map {:closed true}
     [:description [:string {:min 1}]]
     [:snapshots {:optional true}
      [:vector [:string {:min 1}]]]
     [:repository {:optional true}
      :map]]]
   [:parameter
    [:map {:closed true}
     [:description [:string {:min 1}]]
     [:data :map]]]
   [:output
    [:map {:closed true}
     [:description [:string {:min 1}]]
     [:created {:optional true}
      repository/kinds-schema]
     [:changed {:optional true}
      repository/kinds-schema]
     [:error {:optional true}
      [:map {:closed true}
       [:reason :keyword]]]]]])

(defn all-steps
  "Every step of every action use case, each with the `:file` it comes from."
  []
  (for [f (sort-by str (.listFiles (io/file dir)))
        :when (str/ends-with? (.getName f) ".edn")
        step (edn/read-string (slurp f))]
    (assoc step :file (.getName f))))

(defn- resolve-action
  "The var `action` names, or nil when there is none."
  [action]
  (when (qualified-symbol? action) (try (requiring-resolve action) (catch Exception _ nil))))

(defn- upsert
  "`xs` with each of `ys` added, or replacing the one of the same `:id`."
  [xs ys]
  (let [by-id (into {} (map (juxt :id identity)) ys)
        old (set (map :id xs))]
    (into (mapv #(get by-id (:id %) %) xs) (remove (comp old :id) ys))))

(defn input
  "The repository `step` starts from."
  [step]
  (let [{:keys [snapshots repository]} (:input step)]
    (if snapshots
      (let [content (mapcat use-case/snapshot snapshots)
            base {:persons (use-case/persons)
                  :users (use-case/users)
                  :publications (use-case/publications content)
                  :objections (use-case/objections content)}]
        (merge-with upsert base (merge repository/empty-repository repository)))
      (merge repository/empty-repository repository))))

(defn- step-error
  "What makes `step` impossible to run, or nil."
  [step]
  (if-let [invalid (schema/validate-data-humanize step-schema (dissoc step :file))]
    {:kind :invalid-step
     :error (:error invalid)}
    (let [v (resolve-action (:action step))
          parameters (:parameters (meta v))
          missing (remove use-case/snapshot (get-in step [:input :snapshots]))]
      (cond
        (seq missing) {:kind :unknown-snapshot
                       :snapshots (vec missing)}
        (nil? v) {:kind :unknown-action
                  :action (:action step)}
        (nil? parameters) {:kind :undeclared-parameters
                           :action (:action step)}
        :else (if-let [invalid (schema/validate-data-humanize parameters
                                                              (get-in step [:parameter :data]))]
                {:kind :invalid-parameters
                 :action (:action step)
                 :error (:error invalid)}
                (when-let [violations (seq (repository/violations (input step)))]
                  {:kind :invalid-input
                   :violations (mapv #(select-keys % [:rule :subject :detail]) violations)}))))))

(defn split-steps
  "`steps` split into `{:valid [...] :errors [...]}`. A valid step follows `step-schema`, its
  `:action` names a function, kept under `::action`, whose `:parameters` metadata its
  `:data` follows, and its input repository breaks no rule. An error is
  `{:kind :file :description …}`, its `:kind` one of:

  - `:invalid-step`: the step does not follow `step-schema`, with the humanized `:error`;
  - `:unknown-snapshot`: `:snapshots` names a file that is no snapshot;
  - `:unknown-action`: `:action` names no function;
  - `:undeclared-parameters`: the action declares no `:parameters` grammar;
  - `:invalid-parameters`: `:data` does not follow it, with the humanized `:error`;
  - `:invalid-input`: the input repository breaks rules, with its `:violations`."
  [steps]
  (reduce (fn [acc step]
            (if-let [error (step-error step)]
              (update acc :errors conj (merge (select-keys step [:file :description]) error))
              (update acc :valid conj (assoc step ::action @(resolve-action (:action step))))))
          {:valid []
           :errors []}
          steps))

(defn run
  "Runs a valid `step`, as `split-steps` returns it. Returns `{:input :after :expected
  :actual}`, `:actual` being the changes the action returned, or `{:error {:reason …}}`
  when it refused, to compare with `:expected`; `:after` is the input with these changes
  applied. When the action throws without a reason, or its changes do not apply, returns
  `{:input :error}`, `:error` being
  `{:kind :action-failed :file :description :action :message :data}`."
  [{:keys [by action parameter output]
    :as step}]
  (let [before (input step)]
    (try (let [changes ((::action step) before by (:data parameter))]
           {:input before
            :after (repository/apply-changes before changes)
            :expected (dissoc output :description)
            :actual changes})
         (catch Exception e
           (if-let [reason (:reason (ex-data e))]
             {:input before
              :after before
              :expected (dissoc output :description)
              :actual {:error {:reason reason}}}
             {:input before
              :error (assoc (select-keys step [:file :description])
                            :kind :action-failed
                            :action action
                            :message (ex-message e)
                            :data (ex-data e))})))))
