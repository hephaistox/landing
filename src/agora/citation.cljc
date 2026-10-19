(ns agora.citation
  "Citation et lien.

## Définition

Une **citation** dit qu'un document s'appuie sur un autre. Un document liste ses citations
dans `:inputs`, sous forme de références.

Un **lien** est une citation placée dans le texte, à l'endroit où l'on s'en sert :

```
[[type:nom:langue@majeure|libellé]]
```

Le type, le nom, la langue et la majeure sont obligatoires : un lien porte toujours une
référence complète. Le libellé est facultatif. Sans lui, on affiche le titre du document
cité.

Tout lien est une citation. Une citation n'est pas forcément un lien : on peut s'appuyer
sur un document sans le nommer dans la phrase.

On ne cite que des versions **publiées** : un brouillon change encore, on ne peut pas s'appuyer
dessus.

Une citation peut viser un document d'une **autre langue**. La référence porte la langue du
document cité.

## Buts

- Rendre visible **sur quoi repose** un écrit.
- Permettre de remonter d'une affirmation à ses appuis, en un clic.
- Écrire une prose lisible qui garde ses appuis.
- Citer une pensée sans la figer à une mineure : les mineures ne cassent rien, donc une
  citation reste valable quand le document cité s'améliore.
- S'appuyer sur ce qui existe dans une autre langue, sans devoir le traduire d'abord.

## Exemples

L'édition 1.1 de l'article de la page d'accueil cite la définition de « rapide » et la
place dans sa phrase :

```clojure
{:type :article :name \"ma-voiture-est-rapide\" :lang :fr :major 1 :minor 1
 :text \"Ma voiture est rapide. Par « rapide », j'entends atteindre 100 km/h en moins de 5 secondes ([[definition:rapide:fr@1|définition]]).\"
 :inputs [{:type :definition :name \"rapide\" :lang :fr :major 1}]}
```

L'illustration « Énoncé universel » cite la définition qu'elle illustre, sans lien dans
son texte :

```clojure
{:type :illustration :title \"Énoncé universel\"
 :text \"Cet énoncé n'est borné ni dans le temps … ni dans l'espace. …\"
 :inputs [{:type :definition :name \"enonce-universel\" :lang :fr :major 1}]}
```

*Exemple fictif* : un concept bien plus répandu en anglais qu'en français. Un texte français
le cite tel quel, sans attendre qu'il existe en français :

```
… comme le montre le [[definition:nudge:en@1|nudge]] …
```

## Liens

- `agora.lang` : la langue du document cité fait partie de la citation.
- `agora.document` : une citation va d'un document à un autre.
- `agora.identity` : on cite une référence (sans mineure).
- `agora.type` : le type du document cité fait partie de la citation et du lien."
  (:require
   [agora.identity :as identity]
   [agora.version  :as version]
   [clojure.string :as str]))

(defn by-id?
  "Does `citation` point at one version by its `:id`?"
  [citation]
  (contains? citation :id))

(def ^:private link-re #"\[\[([^\]|]+?)(?:\|([^\]]*))?\]\]")

(defn parse-target
  "The citation a link target designates (`definition:rapide:fr@1` or `id:<uuid>`), or nil."
  [target]
  (if-let [[_ id] (re-matches #"id:([0-9a-f-]{36})" target)]
    {:id id}
    (when-let [[_ t n l m] (re-matches #"([a-z-]+):([^:@]+):([a-z]{2})@(\d+)" target)]
      {:type (keyword t)
       :name n
       :lang (keyword l)
       :major (parse-long m)})))

(defn links
  "Links of `text`: `{:target :citation :label}`, `:citation` nil when the target is incomplete."
  [text]
  (for [[_ target label] (re-seq link-re (or text ""))]
    {:target target
     :citation (parse-target target)
     :label label}))

(defn render-link
  "Link text for `citation`, with an optional `label`."
  [citation label]
  (str "[["
       (if (by-id? citation)
         (str "id:" (:id citation))
         (str (name (:type citation))
              ":" (:name citation)
              ":" (name (:lang citation))
              "@" (:major citation)))
       (when label (str "|" label))
       "]]"))

(defn replace-links
  "`text` with each link's citation replaced by `(f citation)`, labels kept."
  [text f]
  (when text
    (str/replace text
                 link-re
                 (fn [[whole target label]]
                   (if-let [c (parse-target target)]
                     (render-link (f c) label)
                     whole)))))

(def rules
  "Rules on a sequence of documents."
  [{:id :citation/shape
    :description "une citation est une référence (type, nom, langue, majeure) ou un {:id}"
    :check (fn [documents]
             (for [d documents
                   c (:inputs d)
                   :when (not (#{#{:id} #{:type :name :lang :major}} (set (keys c))))]
               {:rule :citation/shape
                :subject d
                :detail c}))}
   {:id :citation/links
    :description
    "un lien est complet (type:nom:langue@majeure ou id:…) et chaque lien est une citation"
    :check (fn [documents]
             (for [d documents
                   {:keys [target citation]} (links (:text d))
                   :when (or (nil? citation) (not (some #{citation} (:inputs d))))]
               {:rule :citation/links
                :subject d
                :detail target}))}
   {:id :citation/published-before
    :description "on ne cite par référence que ce qui était déjà publié"
    :check (fn [documents]
             (let [published (filter version/published? documents)]
               (for [d published
                     c (remove by-id? (:inputs d))
                     :let [targets (filter #(= c (identity/reference %)) published)]
                     :when (and (seq targets)
                                (every? #(pos? (compare (:published-at %) (:published-at d)))
                                        targets))]
                 {:rule :citation/published-before
                  :subject d
                  :detail c})))}
   {:id :citation/extract
    :description "un extrait cite exactement une œuvre"
    :check (fn [documents]
             (for [d documents
                   :when (and (= :extract (:type d))
                              (not (and (= 1 (count (:inputs d)))
                                        (= :work (:type (first (:inputs d)))))))]
               {:rule :citation/extract
                :subject d}))}])
