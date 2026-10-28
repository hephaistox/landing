(ns agora.action.take-up
  "Taking up a publication: its owner opens a new major, on the same topic.

Any major may be taken up: the new one comes after the latest major of the publication,
not after the one taken up. Taking up the major 2 when a major 3 exists opens the major 4.
It has the same name and language, opens empty, and has no contributor yet: an invitation
holds for one major. It is opened only when no major of the publication is open: two
majors are never open at the same time. The former majors stay as they were.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.publication :as publication]
   [agora.value.reference    :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `take-up`: the id of the new major, the publication taken up, and
  what the new major says."
  [:map {:closed true}
   [:id :uuid]
   [:publication reference/publication-schema]
   [:content publication/content-schema]])

(defn take-up
  "The owner of `publication` opens a new major, after its latest one. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:id-taken`: something already has the new major's `:id`;
  - `:unknown-publication`: no publication has this `:id`;
  - `:not-the-owner`: who acts does not own the publication;
  - `:publication-open`: a major of the publication is still open;
  - `:rules-broken`: the new major breaks a rule."
  {:parameters parameters}
  [repository
   by
   {:keys [id content]
    :as parameters}]
  (let [user (action/logged-in-user repository by)
        _ (action/new-id! repository id)
        publications (:publications repository)
        ref (:publication parameters)
        p (some #(when (= (:id ref) (:id %)) %) publications)]
    (when-not p (action/refuse! :unknown-publication ref))
    (when-not (= (:owner p) {:id (:id user)}) (action/refuse! :not-the-owner ref))
    (let [majors (filter #(= ((juxt :name :lang) p) ((juxt :name :lang) %)) publications)]
      (when (some publication/open? majors) (action/refuse! :publication-open ref))
      (action/check-rules! repository
                           {:created {:publications [{:id id
                                                      :type :publication
                                                      :name (:name p)
                                                      :lang (:lang p)
                                                      :major (inc (apply max (map :major majors)))
                                                      :status :open
                                                      :owner (:owner p)
                                                      :contributors []
                                                      :documents []
                                                      :content content}]}}))))
