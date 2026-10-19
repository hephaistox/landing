(ns agora.user
  "Utilisateur.

## Définition

Un **utilisateur** est une personne qui a un **compte** : elle se connecte à agora et y
écrit. Elle se connecte avec son compte Google ou avec un mot de passe.

Tout utilisateur est une personne. Toute personne n'est pas un utilisateur :

|                                 | personne | utilisateur                 |
|---------------------------------|----------|-----------------------------|
| a un nom d'affichage            | oui      | oui                         |
| peut se voir attribuer un écrit | oui      | oui                         |
| se connecte, écrit dans agora   | non      | oui                         |
| nom d'affichage unique          | non      | oui, parmi les utilisateurs |

Le nom d'affichage d'un utilisateur est unique. Celui d'une personne sans compte ne l'est
pas : deux auteurs cités peuvent porter le même nom.

## Buts

- Savoir **qui agit** dans agora, et le vérifier à la connexion.
- Séparer ceux qui écrivent dans agora de ceux qu'on y cite.
- Empêcher un utilisateur de se faire passer pour un autre : un nom d'affichage ne
  désigne qu'un seul utilisateur. On compare les noms sans tenir compte des majuscules ni
  des accents.

## Exemples

- « Hephaistox » est un utilisateur : il se connecte avec Google. Il a enregistré l'œuvre « Les misérables ».
- « Victor hugo » est une personne sans compte : on lui attribue cette œuvre, mais il ne se connecte pas.
- « Agora » est une personne sans compte : elle tient les contenus de la plateforme.

## Liens

- `agora.person` : un utilisateur est une personne qui a un compte.

Les utilisateurs que les cas d'usage nomment sont dans `agora/use_cases/utilisateurs.edn`.
Chacun n'y porte que ce que le compte ajoute à sa personne : `{:id :provider}`. Son nom est
celui de sa personne. Ni email ni mot de passe."
  (:require
   [clojure.string :as str]))

(def providers "How a user logs in." #{:google :password})

(defn user?
  "A user is `{:id :provider}`: the account of the person of that `:id`."
  [u]
  (and (map? u)
       (= #{:id :provider} (set (keys u)))
       (string? (:id u))
       (contains? providers (:provider u))))

(defn- strip-accents
  [s]
  #?(:clj (str/replace (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD) #"\p{M}" "")
     :cljs (str/replace (.normalize s "NFD") #"[\u0300-\u036f]" "")))

(defn name-key
  "`display-name` as compared for uniqueness: trimmed, lower case, without accents."
  [display-name]
  (-> display-name
      str/trim
      str/lower-case
      strip-accents))

(def rules
  "Rules on the `persons` and the `users`."
  [{:id :user/shape
    :description "un utilisateur est {:id :provider}, :provider :google ou :password"
    :check (fn [_persons users]
             (for [u users
                   :when (not (user? u))]
               {:rule :user/shape
                :subject u}))}
   {:id :user/is-a-person
    :description "un utilisateur est une personne : son :id est celui d'une personne"
    :check (fn [persons users]
             (let [ids (set (map :id persons))]
               (for [u users
                     :when (not (ids (:id u)))]
                 {:rule :user/is-a-person
                  :subject u})))}
   {:id :user/unique-name
    :description
    "le nom d'un utilisateur est unique parmi les utilisateurs, sans majuscules ni accents"
    :check (fn [persons users]
             (let [user-ids (set (map :id users))]
               (for [[k ps] (group-by (comp name-key :name) (filter #(user-ids (:id %)) persons))
                     :when (> (count ps) 1)]
                 {:rule :user/unique-name
                  :subject (first ps)
                  :detail k})))}])
