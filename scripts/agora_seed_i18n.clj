;; Agora dev seed — internationalise the existing (English) KIs.
;;
;; Translations are grouped by concept identity (T,N): a French version is just a
;; KI with the SAME name as the English one but lang='fr'. Because edges reference
;; (name, major) with no language, the French graph is inherited for free — no
;; French-specific edges are created. This script:
;;   1. marks each existing lineage lang='en' (they were English content),
;;   2. removes the earlier French-named rows (superseded model),
;;   3. creates a French translation sharing the English name.
;;
;; Idempotent: existing French rows (same name, lang='fr') are left alone.
;;
;;   clojure -M:env-dev scripts/agora_seed_i18n.clj
(require '[landing.agora.db :as db]
         '[landing.agora.blob :as blob]
         '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as rs])
(import '(java.util UUID))

(def concepts
  "Each: the existing English lineage + the French statement to add under the
  SAME name (so it is an automatic translation sibling)."
  [{:name "confidence-is-partial"
    :major 1
    :fr
    "La confiance collective humaine en toute affirmation est toujours partielle et évolutive ; la connaissance vit donc dans un espace flou et probabiliste plutôt que dans une vérité binaire. coucou agnès"}
   {:name "confidence-over-binary"
    :major 1
    :fr
    "Parce que notre confiance collective en toute affirmation est partielle et toujours évolutive, une plateforme de connaissance devrait enregistrer et exposer une confiance graduée plutôt que d'imposer des conclusions binaires vrai/faux."}
   {:name "Adam-eve"
    :major 1
    :fr "Est la racine de tout, mdr"}
   {:name "Here am I"
    :major 1
    :fr "Ce site déchire !"}])

;; Earlier model created French versions under distinct names — remove them so the
;; only French rows are the same-named siblings.
(def obsolete-fr-names
  ["confiance-partielle" "confiance-plutot-que-binaire" "adam-et-eve" "me-voici"])

;; Seeded KIs are authored by the platform owner's account.
(def owner-email "hephaistox.sc@gmail.com")
(def owner-id
  (:id (jdbc/execute-one! db/ds
                          ["SELECT id FROM AGORA_USER WHERE email = ?" owner-email]
                          {:builder-fn rs/as-unqualified-kebab-maps})))
(assert owner-id (str "No AGORA_USER for " owner-email))

(defn exists-fr?
  [nm major]
  (seq (jdbc/execute!
        db/ds
        ["SELECT id FROM AGORA_KI WHERE object_type='ki' AND name=? AND major=? AND lang='fr'"
         nm
         major])))

;; 0. drop the obsolete French-named lineages and any edges referencing them
(doseq [nm obsolete-fr-names]
  (jdbc/execute! db/ds ["DELETE FROM AGORA_KI_EDGE WHERE input_name=? OR output_name=?" nm nm])
  (jdbc/execute! db/ds ["DELETE FROM AGORA_KI WHERE object_type='ki' AND name=?" nm]))

(doseq [{:keys [name major fr]} concepts]
  ;; 1. ensure the English lineage is tagged 'en'
  (jdbc/execute!
   db/ds
   ["UPDATE AGORA_KI SET lang='en' WHERE object_type='ki' AND name=? AND major=? AND lang<>'fr'"
    name
    major])
  ;; 2. add the French sibling under the same name (idempotent)
  (if (exists-fr? name major)
    (println "skip — French sibling already present:" name)
    (let [{ki-type :type}
          (jdbc/execute-one!
           db/ds
           ["SELECT type FROM AGORA_KI WHERE object_type='ki' AND name=? AND major=? LIMIT 1"
            name
            major]
           {:builder-fn rs/as-unqualified-kebab-maps})]
      (jdbc/execute!
       db/ds
       ["INSERT INTO AGORA_KI
                       (id, object_type, name, type, lang, major, minor, output_statement_hash, owner_id, published_at)
                       VALUES (?, 'ki', ?, ?, 'fr', ?, 0, ?, ?, UTC_TIMESTAMP())"
        (str (UUID/randomUUID))
        name
        ki-type
        major
        (blob/write-blob fr)
        owner-id])
      (println "added French sibling:" name))))

;; 3. attribute every still-unowned KI to the platform owner
(jdbc/execute! db/ds
               ["UPDATE AGORA_KI SET owner_id = ? WHERE object_type = 'ki' AND owner_id IS NULL"
                owner-id])

(println "--- result (name, lang) ---")
(doseq [r
        (jdbc/execute!
         db/ds
         ["SELECT name, lang FROM AGORA_KI WHERE object_type='ki' AND minor=0 ORDER BY name, lang"]
         {:builder-fn rs/as-unqualified-kebab-maps})]
  (prn r))
(System/exit 0)
