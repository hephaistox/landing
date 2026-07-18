(ns landing.agora.db.source
  "New Wire. Raw AGORA_SOURCE SQL. Resolves a document's `content.:source` ref to the shared work's
  display fields. No cache. No domain rules."
  (:require
   [clojure.string       :as str]
   [landing.agora.db     :as db]
   [next.jdbc            :as jdbc]
   [next.jdbc.result-set :as rs]))

(def ^:private kebab {:builder-fn rs/as-unqualified-kebab-maps})

(def ^:private select-source
  "SELECT source_id, author_id, author_name, title, year, editor, url, owner_id FROM AGORA_SOURCE ")

(defn- shape
  "DB row → resolved work `{:source-id :author-id :author-name :title :year :editor :url :owner-id}`.
  The columns match the domain keys, so no remapping — `source-id` is the work, `author-id` its
  author, `owner-id` the account that created the record."
  [row]
  (when row
    (select-keys row [:source-id :author-id :author-name :title :year :editor :url :owner-id])))

(defn resolve-ref
  "Resolve a document's `content.:source` ref `{:source-id :locator}` to the shared work's display
  fields + locator. nil for a blank/absent ref or an unknown source."
  [{:keys [source-id locator]}]
  (when-not (str/blank? source-id)
    (some-> (jdbc/execute-one! db/ds [(str select-source "WHERE source_id = ?") source-id] kebab)
            shape
            (assoc :locator locator))))

(comment
  (resolve-ref {:source-id "a04f0d47-1e1b-4102-8c8b-85dba470da52"
                :locator "foo"})
  ;; {:author-name "Creative commons",
  ;;  :locator "foo",
  ;;  :title "Wikipedia - groupe",
  ;;  :year 2026,
  ;;  :id "a04f0d47-1e1b-4102-8c8b-85dba470da52",
  ;;  :url "https://fr.wikipedia.org/wiki/Groupe",
  ;;  :editor nil,
  ;;  :author-id "676ec4a0-785a-427a-afec-5473f065cb28",
  ;;  :owner-id "61a3d5a4-a94a-4e02-9e1e-0db6f5c03503",
  ;;  :source-id "a04f0d47-1e1b-4102-8c8b-85dba470da52"}
  ;;
)
