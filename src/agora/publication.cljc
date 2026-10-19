(ns agora.publication
  "Publication.

## Définition

Une **publication** est un document de type `:publication` qui regroupe un ensemble de
changements. Elle a un titre, une langue, un propriétaire et un **statut** :

- `:open` : on y écrit encore. Ses documents sont des brouillons.
- `:closed` : elle est publiée. Ses documents sont publiés et figés.

Tout document qui n'est pas une publication appartient à **exactement une** publication :
celle qui l'a créé. C'est sa **provenance**, et elle ne change pas.

Dans une publication ouverte, les brouillons **peuvent se citer entre eux**. C'est la seule
exception à la règle qui interdit de citer un brouillon : ils sont publiés au même moment,
donc une fois la publication close, plus aucune citation ne vise un brouillon. Un brouillon
ne peut pas être cité depuis une autre publication.

À l'intérieur de la publication, un brouillon cite un autre brouillon par son **`:id`** :
il n'a pas encore de mineure, c'est son identifiant qui le relie aux autres. La citation
s'écrit `{:id \"…\"}` dans `:inputs`, et le lien `[[id:…|libellé]]` dans le texte.

**Publier** une publication, c'est la clore. À ce moment :

- chaque brouillon reçoit sa mineure : la suivante après la plus récente déjà publiée pour
  la même majeure, ou 0 s'il n'y en a pas. Si une autre publication a publié une mineure
  entre-temps, celle-ci passe après elle ;
- toutes les citations par `:id` deviennent des références `(type, nom, langue, majeure)`,
  comme toute citation publiée.

Une publication ne contient que des documents **de sa langue**. Une même publication existe
en plusieurs langues sous le même nom, comme tout document. Ses documents citent librement
des documents d'autres langues, qu'elle ne contient pas.

Une publication **n'affirme rien** et ne se conteste pas. Ce qui se tient ensemble est
porté par les documents qui se citent : une déduction affirme que sa conclusion suit de ses
entrées, un article que son raisonnement tient. On conteste ces documents, pas la
publication qui les a publiés. Comme tout type, `:publication` est décrit par le document
`type-publication`.

Un document publié est figé. Pour le **corriger**, son auteur en publie une nouvelle version
dans une nouvelle publication. L'ancienne publication reste lisible telle quelle.

Une publication n'a qu'une **majeure** : ni mineure, ni brouillon. Son statut tient lieu de
brouillon. Ouverte, elle se modifie sur place, titre compris. Close, elle est figée pour
toujours. On la corrige par une nouvelle publication, donc une mineure n'aurait pas de sens.

Le propriétaire d'une publication ouverte peut **inviter** d'autres utilisateurs à y
contribuer : ce sont ses **contributeurs** (`:contributors`). Sans invitation, personne
d'autre n'y écrit. Chaque document garde son propre propriétaire : celui qui l'a écrit.

Tant qu'une publication est ouverte, on continue de la modifier. On ne crée une **nouvelle
majeure** que lorsque la dernière est close : deux majeures d'une même publication ne sont
jamais ouvertes en même temps.

Une nouvelle majeure **reprend le même sujet**. Personne ne cite une publication, donc sa
majeure ne signale pas un changement cassant, comme pour les autres documents : elle
marque une reprise. Son identité est :

```
(:publication, nom, langue, majeure)
```

## Buts

- Préparer plusieurs documents ensemble, puis les publier d'un coup.
- Savoir pour toujours d'où vient chaque version.
- Raconter une évolution : une publication par étape.
- Publier chaque langue quand elle est prête, sans attendre les autres.
- Écrire à plusieurs, sur invitation.
- Garder la trace de ce qui a été publié, puis corrigé.

## Exemples

L'exemple de la page d'accueil tient en cinq publications closes, chacune en `:fr` et en
`:en` :

| publication             | ce qu'elle publie          |
|-------------------------|----------------------------|
| `exemples-pedagogiques` | les quatre documents cités |
| `exemple-pedagogique-1` | l'article 1.0              |
| `exemple-pedagogique-2` | l'article 1.1              |
| `exemple-pedagogique-3` | l'article 1.2              |
| `exemple-pedagogique-4` | l'article 1.3              |

Les deux langues de la première édition :

```clojure
{:type :publication :name \"exemple-pedagogique-1\" :lang :fr :title \"Exemple pédagogique 1\"
 :status :closed
 :documents [{:type :article :name \"ma-voiture-est-rapide\" :lang :fr :title \"Ma voiture est rapide\" …}]}
{:type :publication :name \"exemple-pedagogique-1\" :lang :en :title \"Teaching example 1\"
 :status :closed
 :documents [{:type :article :name \"ma-voiture-est-rapide\" :lang :en :title \"My car is fast\" …}]}
```

« La méthode scientifique » est une publication ouverte : ses huit documents sont des
brouillons.

Dans cette publication, la définition « Énoncé singulier » cite la définition « Énoncé
universel » par son identifiant :

```clojure
{:type :definition :name \"enonce-singulier\" :minor nil
 :text \"un énoncé portant sur un individu ou un événement déterminé … Là où l'[[id:a526a25d-c823-4295-a8b4-0e6514d3768a]] porte sur une classe non bornée, …\"
 :inputs [{:id \"a526a25d-c823-4295-a8b4-0e6514d3768a\"}]}
```

À la publication, ce lien deviendra `[[definition:enonce-universel:fr@1]]`.

```clojure
{:type :publication :name \"la-methode-scientifique\" :lang :fr :title \"La méthode scientifique\" :status :open
 :owner {:name \"Hephaistox\"}
 :documents [{:type :definition :title \"Énoncé universel\" :minor nil …} …]}
```

## Liens

- `agora.document`, `agora.type` : une publication est un document de type
  `:publication`.
- `agora.version` : ouverte, elle contient des brouillons ; close,
  des versions publiées. Elle-même n'a qu'une majeure.
- `agora.identity` : la sienne n'a pas de mineure.
- `agora.person` : son propriétaire.
- `agora.user` : ses contributeurs, invités par le propriétaire.
- `agora.lang` : une publication a une langue, celle de ses documents.
- `agora.citation` : ses documents citent au-delà de sa langue."
  (:require
   [agora.citation :as citation]
   [agora.document :as document]
   [agora.identity :as identity]
   [agora.lang     :as lang]
   [agora.person   :as person]
   [agora.type     :as type]
   [agora.user     :as user]
   [agora.version  :as version]))

(defn open? [publication] (= :open (:status publication)))

(defn closed? [publication] (= :closed (:status publication)))

(defn documents "Every document of `publications`." [publications] (mapcat :documents publications))

(defn named-persons
  "Every person named in `publications`: owners, contributors, credited authors."
  [publications]
  (concat (keep :owner publications)
          (mapcat :contributors publications)
          (keep :owner (documents publications))
          (keep :author (documents publications))))

(defn save
  "`publications` with each of `changed` created, or replacing the one of the same `:id`."
  [publications changed]
  (let [ids (set (map :id changed))] (into (vec (remove #(ids (:id %)) publications)) changed)))

(defn publish
  "`publication` closed at `at`: each draft gets the next minor of its major, among the
  documents already published in `publications`, and every citation and link by `:id`
  becomes a reference."
  [publication publications at]
  (let [published (filter version/published? (documents publications))
        by-id (into {} (map (fn [x] [(:id x) x])) (:documents publication))
        ->reference #(if (citation/by-id? %) (identity/reference (by-id (:id %))) %)]
    (assoc publication
           :status :closed
           :published-at at
           :documents (mapv (fn [d]
                              (assoc d
                                     :minor (identity/next-minor published d)
                                     :published-at at
                                     :text (citation/replace-links (:text d) ->reference)
                                     :inputs (mapv ->reference (:inputs d))))
                            (:documents publication)))))

(defn- with-publication [publications] (for [p publications d (:documents p)] [p d]))

(def rules
  "Rules on a sequence of publications."
  [{:id :publication/shape
    :description
    "une publication a le type :publication, une langue, une majeure, un statut, ni mineure ni brouillon"
    :check (fn [publications]
             (for [p publications
                   :when (or (not= :publication (:type p))
                             (not (lang/langs (:lang p)))
                             (not (pos-int? (:major p)))
                             (not (#{:open :closed} (:status p)))
                             (contains? p :minor)
                             (contains? p :draft))]
               {:rule :publication/shape
                :subject p}))}
   {:id :publication/identity
    :description
    "identité (nom, langue, majeure) unique ; :id unique parmi publications et documents"
    :check (fn [publications]
             (concat (for [[k ps] (group-by (juxt :name :lang :major) publications)
                           :when (> (count ps) 1)]
                       {:rule :publication/identity
                        :subject (first ps)
                        :detail k})
                     (for [[id xs] (group-by :id (concat publications (documents publications)))
                           :when (> (count xs) 1)]
                       {:rule :publication/identity
                        :subject (first xs)
                        :detail id})))}
   {:id :publication/content
    :description
    "une publication ne contient que des documents de sa langue, jamais une publication"
    :check (fn [publications]
             (for [[p d] (with-publication publications)
                   :when (or (not= (:lang p) (:lang d)) (= :publication (:type d)))]
               {:rule :publication/content
                :subject d
                :detail (:name p)}))}
   {:id :publication/status
    :description "ouverte ⇒ brouillons ; close ⇒ versions publiées"
    :check (fn [publications]
             (for [[p d] (with-publication publications)
                   :when (not= (version/draft? d) (open? p))]
               {:rule :publication/status
                :subject d
                :detail (:status p)}))}
   {:id :publication/majors
    :description "majeures 1..n ; une nouvelle majeure seulement après une majeure close"
    :check (fn [publications]
             (for [[k ps] (group-by (juxt :name :lang) publications)
                   :let [ps (sort-by :major ps)]
                   :when (or (not= (map :major ps) (range 1 (inc (count ps))))
                             (some open? (butlast ps)))]
               {:rule :publication/majors
                :subject (first ps)
                :detail [k (map (juxt :major :status) ps)]}))}
   {:id :publication/one-version
    :description "deux versions d'un même document ne sont jamais dans la même publication"
    :check (fn [publications]
             (for [p publications
                   [r ds] (group-by identity/reference (:documents p))
                   :when (> (count ds) 1)]
               {:rule :publication/one-version
                :subject p
                :detail r}))}
   {:id :publication/owner
    :description
    "un propriétaire ; :owner d'un document seulement s'il diffère, et alors contributeur invité"
    :check (fn [publications]
             (concat (for [p publications
                           :when (not (person/person? (:owner p)))]
                       {:rule :publication/owner
                        :subject p})
                     (for [[p d] (with-publication publications)
                           :when (and (:owner d)
                                      (or (= (:owner d) (:owner p))
                                          (not (some #{(:owner d)} (:contributors p)))))]
                       {:rule :publication/owner
                        :subject d
                        :detail (:owner d)})))}
   {:id :publication/citations
    :description "on cite du publié par référence, ou un brouillon de la même publication par :id"
    :check (fn [publications]
             (let [docs (documents publications)]
               (for [[p d] (with-publication publications)
                     c (:inputs d)
                     :let [problem (if (citation/by-id? c)
                                     (let [t (some #(when (= (:id c) (:id %)) %) (:documents p))]
                                       (when-not (and t (version/draft? d) (version/draft? t))
                                         :id-outside-drafts))
                                     (let [targets (filter #(= c (identity/reference %)) docs)]
                                       (cond
                                         (empty? targets) :unknown
                                         (some #(and (version/draft? %) (some #{%} (:documents p)))
                                               targets)
                                         :draft-by-reference
                                         (every? version/draft? targets) :draft-elsewhere)))]
                     :when problem]
                 {:rule :publication/citations
                  :subject d
                  :detail [problem c]})))}])

(def person-rules
  "Rules on `publications`, with the `persons` and the `users`."
  [{:id :publication/persons
    :description "toute personne nommée existe, sous le même nom, parmi les personnes"
    :check (fn [publications persons _users]
             (let [known (set persons)]
               (for [p (distinct (named-persons publications))
                     :when (not (known p))]
                 {:rule :publication/persons
                  :subject p})))}
   {:id :publication/contributors
    :description "un contributeur invité est un utilisateur"
    :check (fn [publications _persons users]
             (let [ids (set (map :id users))]
               (for [p publications
                     c (:contributors p)
                     :when (not (ids (:id c)))]
                 {:rule :publication/contributors
                  :subject p
                  :detail c})))}])

(defn violations
  "Every rule broken by `publications`, with the `persons` and the `users`."
  [publications persons users]
  (let [docs (documents publications)
        apply-rules (fn [rules input] (mapcat #((:check %) input) rules))]
    (concat
     (apply-rules person/rules persons)
     (mapcat #((:check %) persons users) user/rules)
     (mapcat #((:check %) publications persons users) person-rules)
     (apply-rules
      (concat lang/rules document/rules type/rules version/rules identity/rules citation/rules)
      docs)
     (apply-rules rules publications))))

(def transition-rules
  "Rules on `publications` before `changed` are saved."
  [{:id :publication/frozen
    :description "une publication close ne change plus"
    :check (fn [publications changed]
             (let [before (into {} (map (fn [x] [(:id x) x])) publications)]
               (for [p changed
                     :let [old (before (:id p))]
                     :when (and (closed? old) (not= old p))]
                 {:rule :publication/frozen
                  :subject p})))}
   {:id :publication/no-move
    :description "un document ne quitte jamais sa publication"
    :check (fn [publications changed]
             (let [after (save publications changed)
                   home (fn [ps] (into {} (for [p ps d (:documents p)] [(:id d) (:id p)])))
                   home-after (home after)]
               (for [[id pid] (home publications)
                     :when (not= pid (home-after id))]
                 {:rule :publication/no-move
                  :subject {:id id}})))}
   {:id :publication/publish
    :description
    "publier attribue la mineure suivante et convertit les citations par :id en références"
    :check (fn [publications changed]
             (let [before (into {} (map (fn [x] [(:id x) x])) publications)]
               (for [p changed
                     :let [old (before (:id p))]
                     :when (and (open? old)
                                (closed? p)
                                (not= p (publish old publications (:published-at p))))]
                 {:rule :publication/publish
                  :subject p})))}])

(defn transition-violations
  "Every rule broken when `changed` publications are saved into `publications`."
  [publications changed]
  (concat (mapcat #((:check %) (documents publications) (documents (save publications changed)))
           identity/transition-rules)
          (mapcat #((:check %) publications changed) transition-rules)))
