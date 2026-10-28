(ns agora.action.open-publication
  "Opening a publication: a logged-in user starts a new topic, to write documents in it.

The user becomes the owner of the publication, opened in its major 1, with no document. A
name is taken once per language: taking up a topic again is another action."
  (:require
   [agora.action          :as action]
   [agora.entity.document :as document]
   [agora.value.lang      :as lang]
   [agora.value.name      :as name]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `open-publication`: its id, name, language, title, the text describing
  its change, and when it is opened."
  [:map {:closed true}
   [:id :uuid]
   [:name name/schema]
   [:lang lang/schema]
   [:title [:string {:min 1}]]
   [:text [:string {:min 1}]]
   [:published-at document/published-at-schema]])

(defn open-publication
  "A logged-in user opens a publication. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:id-taken`: a publication, a document or an objection already has its `:id`;
  - `:name-taken`: a publication of that language already has that name;
  - `:rules-broken`: the publication breaks a rule."
  {:parameters parameters}
  [repository by {:keys [id name lang title text published-at]}]
  (let [user (action/logged-in-user repository by)]
    (action/new-id! repository id)
    (when (some #(and (= name (:name %)) (= lang (:lang %))) (:publications repository))
      (action/refuse! :name-taken
                      {:name name
                       :lang lang}))
    (action/check-rules! repository
                         {:created {:publications [{:id id
                                                    :type :publication
                                                    :name name
                                                    :lang lang
                                                    :major 1
                                                    :status :open
                                                    :owner {:id (:id user)}
                                                    :contributors []
                                                    :documents []
                                                    :content {:title title
                                                              :text text
                                                              :published-at published-at}}]}})))
