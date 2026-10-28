(ns agora.action.write-draft
  "Writing a draft: the owner of an open publication, or one of its contributors, adds a
  draft to it.

The draft takes the language of the publication. It has no minor yet: no `:minor` key. Its owner is who
writes it. It is a new document (major 1), a new version of a document (one of its majors,
for a minor), or its next major (a breaking change).

An action takes the repository, who acts (`by`) and its parameters, and returns its
changes. Its `:parameters` metadata is the grammar of its parameters."
  (:require
   [agora.action             :as action]
   [agora.entity.document    :as document]
   [agora.entity.publication :as publication]
   [agora.value.identity     :as identity]
   [agora.value.reference    :as reference]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(def draft-schema
  "The grammar of the draft written: a document without its language, minor, owner and
  timestamp. The publication gives the language, who writes it the owner."
  (into [:map {:closed true}]
        (remove (comp #{:lang :minor :owner :timestamp} first))
        (drop 2 document/schema)))

(def parameters
  "The parameters of `write-draft`: the publication written in, and the draft."
  [:map {:closed true}
   [:publication reference/publication-schema]
   [:draft draft-schema]])

(defn- by-id [xs id] (some #(when (= id (:id %)) %) xs))

(defn- majors
  "The majors `draft` may take: those of the same document, or the next one. 1 for a new
  document."
  [documents draft]
  (let [same (filter #(= ((juxt :type :name :lang) draft) ((juxt :type :name :lang) %)) documents)
        ms (set (map :major same))]
    (if (empty? ms) #{1} (conj ms (inc (apply max ms))))))

(defn write-draft
  "Who acts writes `draft` in the open `publication`, as its owner or a contributor.
  Refuses with:

  - `:not-logged-in`, `:unknown-user`: who acts is no user;
  - `:unknown-publication`: no publication has this `:id`;
  - `:publication-closed`: the publication is published;
  - `:not-a-contributor`: who acts is neither its owner nor a contributor;
  - `:id-taken`: a publication, a document or an objection already has the draft's `:id`;
  - `:already-in-publication`: the publication already holds a version of that document;
  - `:invalid-major`: the major is neither one of the document's nor the next;
  - `:rules-broken`: the draft breaks a rule, e.g. a link that is no citation."
  {:parameters parameters}
  [repository by {:keys [publication draft]}]
  (let [user (action/logged-in-user repository by)
        p (action/open-publication-of repository publication)
        writer (by-id (cons (:owner p) (:contributors p)) (:id user))
        documents (publication/documents (:publications repository))
        d (assoc draft :lang (:lang p) :owner writer)]
    (when-not writer (action/refuse! :not-a-contributor publication))
    (action/new-id! repository (:id d))
    (when (some #(= (identity/tnlr d) (identity/tnlr %)) (:documents p))
      (action/refuse! :already-in-publication (identity/tnlr d)))
    (when-not ((majors documents d) (:major d)) (action/refuse! :invalid-major (identity/tnlr d)))
    (action/check-rules! repository {:changed {:publications [(update p :documents conj d)]}})))
