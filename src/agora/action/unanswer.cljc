(ns agora.action.unanswer
  "Withdrawing an answer: the owner of an open publication declares it no longer answers an
  objection.

Until it is published, a publication may take back an answer declared by mistake. Once
published, the answer is made: the objection is closed.

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action          :as action]
   [agora.value.reference :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def parameters
  "The parameters of `unanswer`: the open publication, and the objection."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:objection reference/objection-schema]])

(defn unanswer
  "The owner of the open `publication` takes back its answer to `objection`. Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`, `:publication-canceled`: the publication is no longer open;
  - `:not-the-owner`: who acts does not own the publication;
  - `:not-answered`: the publication does not answer the objection;
  - `:rules-broken`: the publication breaks a rule."
  {:parameters parameters}
  [repository by {:keys [publication objection]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)]
    (when-not (= (:owner p) {:id (:id user)}) (action/refuse! :not-the-owner publication))
    (when-not (some #{objection} (:answers p)) (action/refuse! :not-answered objection))
    (action/check-rules! repository
                         {:changed {:publications
                                    [(update p :answers (partial filterv #(not= objection %)))]}})))
