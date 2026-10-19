# Cas d'usage d'agora

Un fichier EDN par cas d'usage. Les concepts sont définis dans les docstrings des namespaces `src/agora/*.cljc`.

Il y a deux formes de fichiers :

- **un état** (`:publications`) : une photo des publications et de leurs documents, tirée de la base de prod ;
- **les personnes** (`personnes.edn`) et **les utilisateurs** (`utilisateurs.edn`) : toutes
  les personnes nommées par les cas d'usage, et le compte de celles qui en ont un ;
- **un scénario** (`:steps`) : une suite d'étapes. Chaque étape crée ou remplace des publications, identifiées par leur `:id`. Un scénario part des états, sans les modifier.

## Vérification

Le domaine est dans `src/agora/`, un namespace par concept. Chaque concept porte ses règles. `agora.publication/violations` les applique toutes à un ensemble de publications, et `agora.publication/transition-violations` contrôle le passage d'un état au suivant.

Le test `test/unit/agora/use_case_test.clj` lit les cas (`agora.use-case`), vérifie les états, puis rejoue chaque scénario. Après chaque étape, il contrôle l'état obtenu et le passage depuis l'étape précédente.

| règle                                | concept     | ce qui est vérifié                                                                     |
|--------------------------------------|-------------|----------------------------------------------------------------------------------------|
| `:person/shape`, `:person/unique-id` | personne    | une personne est `{:id :name}` ; un `:id` ne désigne qu'une personne                   |
| `:user/shape`                        | utilisateur | un utilisateur est `{:id :provider}`, `:provider` `:google` ou `:password`             |
| `:user/is-a-person`                  | utilisateur | un utilisateur est une personne : son `:id` est dans `personnes.edn`                   |
| `:user/unique-name`                  | utilisateur | le nom d'un utilisateur est unique parmi les utilisateurs, sans majuscules ni accents  |
| `:lang/known`                        | langue      | langue `:fr` ou `:en`, pour les documents et les citations                             |
| `:document/title-and-date`           | document    | chaque document a un titre et une date                                                 |
| `:type/known`                        | type        | le type est pris dans la liste fermée ; plus de `:kind`                                |
| `:type/described`                    | type        | chaque type est décrit par `type-<type>`, en fr et en en                               |
| `:type/text`                         | type        | une œuvre a un auteur crédité ; tout autre document a un texte                         |
| `:version/minor`                     | version     | une mineure est absente (brouillon) ou entière ≥ 0                                     |
| `:version/described-types-published` | version     | les descriptions de types sont publiées                                                |
| `:identity/unique`                   | identité    | identité unique parmi les versions publiées ; `:id` unique                             |
| `:identity/minors`                   | identité    | les mineures publiées d'une majeure vont de 0 à n, sans trou                           |
| `:identity/frozen`                   | identité    | un document publié ne change plus (transition)                                         |
| `:citation/shape`                    | citation    | une citation est une référence ou un `{:id}`                                           |
| `:citation/links`                    | citation    | liens complets (`type:nom:langue@majeure` ou `id:…`), et chaque lien est une citation  |
| `:citation/published-before`         | citation    | on ne cite par référence que ce qui était déjà publié                                  |
| `:citation/extract`                  | citation    | un extrait cite une seule œuvre                                                        |
| `:publication/shape`                 | publication | type `:publication`, une langue, une majeure, un statut, ni mineure ni brouillon       |
| `:publication/identity`              | publication | identité (nom, langue, majeure) unique ; `:id` unique parmi publications et documents  |
| `:publication/content`               | publication | que des documents de sa langue, jamais une publication                                 |
| `:publication/status`                | publication | ouverte ⇒ brouillons ; close ⇒ versions publiées                                       |
| `:publication/majors`                | publication | majeures 1..n ; nouvelle majeure seulement après une majeure close                     |
| `:publication/one-version`           | publication | deux versions d'un même document ne sont jamais dans la même publication               |
| `:publication/owner`                 | publication | `:owner` d'un document seulement s'il diffère, et alors contributeur invité            |
| `:publication/persons`               | publication | toute personne nommée existe dans `personnes.edn`, sous le même nom                    |
| `:publication/contributors`          | publication | un contributeur invité est un utilisateur                                              |
| `:publication/citations`             | publication | on cite du publié par référence, ou un brouillon de la même publication par `:id`      |
| `:publication/frozen`                | publication | une publication close ne change plus (transition)                                      |
| `:publication/no-move`               | publication | un document ne quitte jamais sa publication (transition)                               |
| `:publication/publish`               | publication | publier attribue la mineure suivante et convertit les citations par `:id` (transition) |

## Les cas

### États tirés de la prod

| cas                         | ce qu'il vérifie                                                                                                                |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `voiture-rapide.edn`        | versions mineures d'un article, une publication par étape, citations et liens, fr et en                                         |
| `vocabulaire-des-types.edn` | chaque type décrit par `type-<type>` ; une publication close ne reçoit plus de document (`type-publication` est dans une autre) |
| `methode-scientifique.edn`  | publication ouverte, brouillons qui se citent par `:id`                                                                         |
| `groupes-et-individus.edn`  | contributeur invité ; un document dont le propriétaire n'est pas celui de la publication                                        |
| `les-miserables.edn`        | œuvre créditée à un auteur sans compte, extraits et leur emplacement                                                            |

### Scénarios

| cas                                   | ce qu'il vérifie                                                                                                           | règles                                                                    |
|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `publier-la-methode-scientifique.edn` | publier une publication : mineures attribuées, citations et liens par `:id` convertis en références                        | `:publication/publish`, `:publication/status`, `:publication/citations`   |
| `corriger-une-definition.edn`         | corriger un document publié par une nouvelle publication : précision en mineure (1.1), changement cassant en majeure (2.0) | `:identity/frozen`, `:publication/publish`, `:identity/minors`            |
| `mineures-concurrentes.edn`           | deux brouillons d'un même document dans deux publications : la mineure suit l'ordre de publication, pas d'ouverture        | `:publication/publish`, `:identity/unique`, `:identity/minors`            |
| `reprendre-une-publication.edn`       | nouvelle majeure d'une publication close                                                                                   | `:publication/majors`, `:publication/frozen`                              |
| `traduction-differee.edn`             | une langue publiée deux mois après l'autre, sous le même nom ; les extraits ne se traduisent pas                           | `:publication/content`, `:publication/frozen`                             |
| `citer-une-autre-langue.edn`          | un article fr cite une définition en ; le brouillon en doit être publié avant d'être cité                                  | `:publication/citations`, `:citation/published-before`, `:citation/links` |
| `changer-de-type.edn`                 | une croyance ne devient pas un fait mesurable : c'est un nouveau document, du même nom                                     | `:identity/unique`, `:type/known`                                         |
| `fondements-et-prediction.edn`        | supposition, croyance, prédiction, puis une mesure et une déduction qui la réfutent                                        | `:type/known`, `:publication/citations`, `:publication/publish`           |
| `changer-un-titre.edn`                | une nouvelle mineure change le titre, le nom reste                                                                         | `:publication/publish`, `:identity/minors`                                |
| `contester.edn`                       | une définition concurrente de « Rapide », une mesure qui contredit le chrono                                               | `:type/known`, `:identity/unique`                                         |

### Ce qui n'est pas encore couvert

| sujet                                       | pourquoi                                                                |
|---------------------------------------------|-------------------------------------------------------------------------|
| lier une contestation à ce qu'elle conteste | question ouverte : citer veut dire « s'appuyer sur », pas « contester » |
