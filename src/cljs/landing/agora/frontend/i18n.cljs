(ns landing.agora.frontend.i18n
  "UI translations and language-fixed path builders for the Agora SPA.

  Two languages for now — `fr` / `en`, matching the landing site. Language identity
  (codes, order, default, labels) is canonical in `landing.language` (cljc, shared
  with the backend); this namespace only adds the translated UI strings. Add a
  language there and its map to `dict` here.

  There are two language dimensions:
   - the INTERFACE language (`::lang`), a user preference driving the chrome, the
     discover feed and search. Cached in localStorage and, for logged-in users,
     persisted to the account (see core `:agora/set-lang`). NOT derived from the
     URL.
   - a KI permalink's `/agora/<lang>/…` segment, the CONTENT language to display
     for that page — independent of the preference (the permalink overrides it).

  Path builders take a language explicitly. `t`/`::lang` use the preference."
  (:require
   [landing.language :as language]
   [re-frame.core    :as rf]))

;; ---------------------------------------------------------------------------
;; Dictionary
;; ---------------------------------------------------------------------------

(def ^:private dict
  {"fr"
   {:nav/discover-ki "Connaissances"
    :nav/new-ki "Nouvelle connaissance"
    :nav/discover-articles "Articles"
    :nav/new-article "Nouvel article"
    :nav/preferences "Préférences"
    :nav/admin "Admin"
    :articles/tagline "Articles — le raisonnement rendu lisible, chaque terme relié à sa source."
    :articles/empty "Aucun article pour l'instant."
    :type/article "Article"
    :landing/headline "Stockez le raisonnement, pas seulement la conclusion."
    :landing/subtitle
    "Agora est un graphe public d'étapes de raisonnement contestables — chaque affirmation traçable jusqu'aux étapes qui la fondent, chaque terme jusqu'à sa définition."
    :landing/cta-ki "＋ Nouvel élément"
    :landing/cta-article "Écrire un article"
    :landing/example-label "Une étape de raisonnement, en direct"
    :landing/explore "Explorer cet élément →"
    :landing/recent "Éléments récents"
    :landing/browse "Parcourir toutes les connaissances →"
    ;; --- Landing marketing sections (home / :agora/<lang>) ---
    :home/eyebrow "La place publique du raisonnement"
    :home/subtitle
    "Agora est un graphe public d'étapes de raisonnement contestables. Chaque affirmation remonte aux étapes qui la fondent — et chaque étape peut être contestée pour elle-même."
    :home/cta-explore "Explorer le graphe"
    :home/cta-publish "＋ Publier une étape"
    :home/trust "Lecture libre · Sans compte pour explorer · Un raisonnement traçable"
    :home/anatomy-title "Anatomie d'une affirmation"
    :home/anatomy-lead
    "Un élément de connaissance, c'est une seule étape de raisonnement : à partir d'entrées tenues pour vraies, une conclusion suit. En voici une."
    :home/tag-definition "Définition"
    :home/tag-observation "Observation"
    :home/tag-conclusion "Conclusion"
    :home/ex-definition "Ici, « rapide » signifie atteindre 100 km/h en moins de 5 secondes."
    :home/ex-observation "Cette voiture atteint 100 km/h en 4,2 secondes."
    :home/ex-conclusion "Donc, cette voiture est rapide."
    :home/ex-objection
    "Objection : « rapide » ne devrait-il pas aussi exiger une vitesse soutenue ?"
    :home/problem-title "Déjà eu raison, sans être cru ?"
    :home/problem-body
    "Le philosophe qu'on écarte, l'ingénieur qu'on ignore, le penseur dont le raisonnement est solide mais ne convainc pas. Avoir raison ne suffit pas quand personne ne peut suivre votre raisonnement. Agora rend chaque étape de votre pensée lisible — et contestable — pour que l'argument parle de lui-même."
    :home/how-title "Comment ça marche"
    :home/how-1-title "Décomposez"
    :home/how-1-body
    "Découpez votre argument en étapes de raisonnement uniques, chacune défendable séparément."
    :home/how-2-title "Reliez"
    :home/how-2-body
    "Chaque étape déclare les entrées dont elle dépend et relie ses termes à leur définition."
    :home/how-3-title "Faites contester"
    :home/how-3-body
    "N'importe qui peut objecter à une étape. Les étapes solides survivent ; les faibles se scindent ou bifurquent."
    :home/features-title "Pensé pour ceux qui raisonnent par étapes"
    :home/feat-terms-title "Chaque terme défini"
    :home/feat-terms-body "Les termes clés pointent vers leur définition. Fini l'ambiguïté cachée."
    :home/feat-objection-title "La contestation par objection"
    :home/feat-objection-body
    "Les objections restent attachées pour toujours, avec la réponse de l'auteur."
    :home/feat-versions-title "Des versions immuables"
    :home/feat-versions-body
    "Rien n'est modifié en place : chaque changement est une nouvelle version traçable."
    :home/feat-time-title "Preuve d'antériorité"
    :home/feat-time-body "Un horodatage public prouve que vous l'avez raisonné en premier."
    :home/feat-confidence-title "Une confiance traçable"
    :home/feat-confidence-body "Une affirmation ne vaut que par son entrée la plus faible."
    :home/feat-lang-title "Dans toutes les langues"
    :home/feat-lang-body "Un concept vit dans chaque langue, relié par son identité."
    :home/predict-title "Prédisez, publiquement"
    :home/predict-lead
    "Annoncez ce qui va arriver — et quand cela se tranchera. Votre prédiction est horodatée et publique : la preuve que vous l'aviez dit, avant que ce soit évident."
    :home/predict-date-claim
    "« D'ici 2027, la voiture électrique se vendra plus que le thermique en Europe. »"
    :home/predict-date-resolve
    "Se résout à une date. L'échéance arrivée, la prédiction est évaluée — le temps la déclenche."
    :home/predict-event-claim
    "« La prochaine tempête qui touchera Brest provoquera une coupure de courant. »"
    :home/predict-event-resolve
    "Se résout sur un événement. Quand il survient, la réalité tranche — c'est le monde qui la déclenche."
    :home/predict-footer
    "À l'échéance ou à l'événement, la prédiction est confirmée ou réfutée — quoi qu'en pense la communauté. (bientôt)"
    :home/live-title "Un exemple réel, tiré du graphe"
    :home/cta-title "Rendez votre raisonnement impossible à ignorer."
    :home/cta-body
    "Publiez votre première étape. La lecture est libre ; contribuer prend une minute."
    :discover/heading "Découvrir les connaissances"
    :article-form/new-title "Nouvel article"
    :article-form/name "Nom (identifiant)"
    :article-form/name-ph "un court identifiant d'URL"
    :article-form/title "Titre"
    :article-form/title-ph "le titre affiché"
    :article-form/body "Contenu"
    :article-form/body-ph "Écrivez votre article. Insérez un KI avec la recherche ci-dessous."
    :article-form/insert-ki "Insérer un KI"
    :article-form/ki-search-ph "Rechercher un KI à insérer…"
    :article-form/create "Publier"
    :article-form/creating "Publication…"
    :article-form/login-to-create "Connectez-vous pour publier"
    :article-form/cancel "Annuler"
    :article-form/edit "Modifier"
    :article-form/edit-title "Modifier l'article"
    :article-form/save "Enregistrer"
    :article-form/saving "Enregistrement…"
    :article-form/login-to-edit "Connectez-vous pour modifier"
    :cite/search-ph "Citer un élément — rechercher…"
    :cite/create-new "Créer"
    :cite/removed-warning "Vous retirez une référence (une entrée). Continuer ?"
    :prefs/title "Préférences"
    :admin/title "Administration"
    :admin/major "Majeur"
    :admin/type "Type"
    :admin/language "Langue"
    :admin/all-langs "Toutes les langues"
    :admin/languages "Langues"
    :admin/versions "Versions"
    :admin/latest "Dernier"
    :admin/drop "Tout supprimer"
    :admin/compact "Garder le dernier"
    :admin/confirm "Confirmer ?"
    :admin/empty "Aucun élément de connaissance."
    :admin/login-required "Connectez-vous pour administrer."
    :admin/not-authorized "Accès réservé à l'administrateur."
    :admin/issues-title "Cohérence des références"
    :admin/issues-none "Aucune référence cassée détectée."
    :admin/issues-broken "cassées"
    :admin/issues-self "auto-référence"
    :author/member-since "Membre depuis"
    :author/last-activity "dernière modification"
    :author/search-ph "Rechercher parmi ses savoirs…"
    :author/kis "savoirs"
    :author/no-kis "Aucun savoir ne correspond."
    :author/unknown "Auteur inconnu"
    :source/heading "Sources"
    :source/find "Trouver une source"
    :source/find-title "Trouver une source"
    :source/create-title "Nouvelle source"
    :source/edit-title "Modifier la source"
    :source/save "Enregistrer"
    :source/create-new "Créer"
    :source/find-existing "Rechercher"
    :source/author "Auteur"
    :source/author-ph "Rechercher ou créer un auteur…"
    :source/title "Titre"
    :source/year "Année"
    :source/editor "Éditeur"
    :source/locator-ph "page / entrée…"
    :source/recent "Récentes :"
    :source/new-person "Nouvel auteur"
    :source/add "Ajouter la source"
    :source/no-results "Aucune source trouvée."
    :ref/remove "Retirer la référence"
    :card/quotes "Cite une source"
    :card/original "auteur original"
    :prefs/account "Compte"
    :prefs/connection "Méthode de connexion"
    :prefs/via-password "E-mail et mot de passe"
    :prefs/not-signed-in "Vous n'êtes pas connecté."
    :search/placeholder "Rechercher un élément…"
    :search/no-matches "Aucun résultat."
    :discover/tagline "Éléments de connaissance — le raisonnement rendu lisible."
    :discover/empty "Aucun élément de connaissance pour l'instant."
    :discover/view "vue"
    :discover/views "vues"
    :ki/edit "Modifier — créer une nouvelle version"
    :ki/login-to-edit "Connectez-vous pour modifier"
    :ki/login-to-add "Connectez-vous pour ajouter des entrées"
    :ki/add-input "Ajouter comme entrée"
    :ki/remove-input "Retirer cette entrée"
    :ki/search-input "Rechercher un élément par nom…"
    :ki/create-new "+ créer un nouvel élément"
    :ki/new-input "Nouvel élément d'entrée"
    :ki/create-and-add "Créer et ajouter"
    :ki/versions "Voir toutes les versions"
    :kind/inference "Inférence"
    :kind/prediction "Prédiction"
    :kind/postulate "Postulat"
    :kind/definition "Définition"
    :kind/position "Position"
    :kind/belief "Croyance"
    :kind/credo "Credo"
    :kind/definition-link "Voir la définition de ce type"
    :ki/other-languages "Aussi en"
    :ki/create-translation "Créer cette version linguistique"
    :ki/lang-notice-shown "Vous consultez cet élément en"
    :ki/lang-notice-switch "Voir la version dans votre langue"
    :translate/to "Traduire en"
    :translate/source "Source"
    :translate/your "Votre traduction"
    :translate/suggesting "Suggestion en cours…"
    :translate/create "Créer la traduction"
    :translate/creating "Création…"
    :form/new-title "Nouvel élément de connaissance"
    :form/name "Nom"
    :form/name-ph "un court identifiant"
    :form/title "Titre"
    :form/title-ph "un titre lisible (facultatif)"
    :form/type "Type"
    :form/language "Langue"
    :form/statement "Énoncé"
    :form/statement-ph "l'affirmation portée par cet élément"
    :form/create "Créer"
    :form/creating "Création…"
    :form/login-to-create "Connectez-vous pour créer"
    :form/save "Enregistrer la nouvelle version"
    :form/saving "Enregistrement…"
    :form/save-failed "Échec — voir la console."
    :form/cancel "Annuler"
    :form/next "→ suivante"
    :auth/login "Se connecter"
    :auth/register "S'inscrire"
    :auth/logout "Se déconnecter"
    :auth/google "Continuer avec Google"
    :auth/or "— ou —"
    :auth/email "E-mail"
    :auth/password "Mot de passe"
    :auth/alias "Pseudonyme"
    :auth/create-account "Créer un compte"
    :auth/have-account "Déjà un compte ? "
    :auth/new-here "Nouveau ici ? "
    :auth/login-to-contribute "Connectez-vous pour contribuer"
    :auth/error "Une erreur est survenue"
    :footer/home "Accueil"
    :footer/legal-notice "Mentions légales"
    :footer/privacy "Confidentialité"
    :footer/disclaimer "Avertissement"
    :footer/who-are-we "Qui sommes-nous ?"}
   "en"
   {:nav/discover-ki "Knowledges"
    :nav/new-ki "New Knowledge"
    :nav/discover-articles "Articles"
    :nav/new-article "New article"
    :nav/preferences "Preferences"
    :nav/admin "Admin"
    :articles/tagline "Articles — reasoning made legible, every term linked to its source."
    :articles/empty "No articles yet."
    :type/article "Article"
    :landing/headline "Store the reasoning, not just the conclusion."
    :landing/subtitle
    "Agora is a public graph of challengeable reasoning steps — every claim traceable to the steps it stands on, every term to its definition."
    :landing/cta-ki "＋ New Knowledge Item"
    :landing/cta-article "Write an article"
    :landing/example-label "A live reasoning step from the graph"
    :landing/explore "Explore this item →"
    :landing/recent "Recent items"
    :landing/browse "Browse all knowledge →"
    ;; --- Landing marketing sections (home / :agora/<lang>) ---
    :home/eyebrow "The public square for reasoning"
    :home/subtitle
    "Agora is a public graph of challengeable reasoning steps. Every claim traces back to the steps that support it — and every step can be challenged on its own."
    :home/cta-explore "Explore the graph"
    :home/cta-publish "＋ Publish a step"
    :home/trust "Free to read · No account to explore · Reasoning you can trace"
    :home/anatomy-title "Anatomy of a claim"
    :home/anatomy-lead
    "A Knowledge Item is a single reasoning step: given inputs held true, a conclusion follows. Here's one."
    :home/tag-definition "Definition"
    :home/tag-observation "Observation"
    :home/tag-conclusion "Conclusion"
    :home/ex-definition "In this graph, \"quick\" means reaching 100 km/h in under 5 seconds."
    :home/ex-observation "This car reaches 100 km/h in 4.2 seconds."
    :home/ex-conclusion "Therefore, this car is quick."
    :home/ex-objection "Objection: shouldn't \"quick\" also require sustained speed?"
    :home/problem-title "Ever been right, but not believed?"
    :home/problem-body
    "The philosopher who gets waved off, the engineer who gets ignored, the thinker whose reasoning is solid but never lands. Being right isn't enough when no one can follow your reasoning. Agora makes every step of your thinking legible — and challengeable — so the argument speaks for itself."
    :home/how-title "How it works"
    :home/how-1-title "Break it down"
    :home/how-1-body "Split your argument into single reasoning steps, each defensible on its own."
    :home/how-2-title "Link the steps"
    :home/how-2-body
    "Each step declares the inputs it relies on and links its terms to their definition."
    :home/how-3-title "Invite challenge"
    :home/how-3-body
    "Anyone can object to a step. Strong steps survive; weak ones get split or forked."
    :home/features-title "Built for people who think in steps"
    :home/feat-terms-title "Every term defined"
    :home/feat-terms-body "Key terms link to their definition. No hidden ambiguity."
    :home/feat-objection-title "Challenge by objection"
    :home/feat-objection-body "Objections stay attached forever, alongside the author's answer."
    :home/feat-versions-title "Immutable versions"
    :home/feat-versions-body "Nothing is edited in place; every change is a new, traceable version."
    :home/feat-time-title "Proof of antecedence"
    :home/feat-time-body "A public timestamp proves you reasoned it first."
    :home/feat-confidence-title "Confidence you can trace"
    :home/feat-confidence-body "A claim is only as strong as its weakest input."
    :home/feat-lang-title "Any language"
    :home/feat-lang-body "A concept lives in every language, linked by identity."
    :home/predict-title "Predict, on the record"
    :home/predict-lead
    "State what will happen — and when it settles. Your prediction is timestamped and public: proof you called it, before it became obvious."
    :home/predict-date-claim "\"By 2027, EVs will outsell combustion cars in Europe.\""
    :home/predict-date-resolve
    "Resolves on a date. When the deadline arrives, the prediction is evaluated — time triggers it."
    :home/predict-event-claim "\"The next storm to hit Brest will cause a power outage.\""
    :home/predict-event-resolve
    "Resolves on an event. When it happens, reality decides — the world triggers it."
    :home/predict-footer
    "At the deadline or the event, the prediction is confirmed or refuted — whatever the crowd believed. (coming soon)"
    :home/live-title "See a real one, from the graph"
    :home/cta-title "Make your reasoning impossible to ignore."
    :home/cta-body "Publish your first step. Reading is free; contributing takes a minute."
    :discover/heading "Discover knowledge"
    :article-form/new-title "New article"
    :article-form/name "Name (slug)"
    :article-form/name-ph "a short URL slug"
    :article-form/title "Title"
    :article-form/title-ph "the displayed title"
    :article-form/body "Body"
    :article-form/body-ph "Write your article. Insert a KI with the search box below."
    :article-form/insert-ki "Insert a KI"
    :article-form/ki-search-ph "Search a KI to insert…"
    :article-form/create "Publish"
    :article-form/creating "Publishing…"
    :article-form/login-to-create "Log in to publish"
    :article-form/cancel "Cancel"
    :article-form/edit "Edit"
    :article-form/edit-title "Edit article"
    :article-form/save "Save"
    :article-form/saving "Saving…"
    :article-form/login-to-edit "Log in to edit"
    :cite/search-ph "Cite a KI — search…"
    :cite/create-new "Create"
    :cite/removed-warning "You're removing a reference (an input). Continue?"
    :prefs/title "Preferences"
    :admin/title "Administration"
    :admin/major "Major"
    :admin/type "Type"
    :admin/languages "Languages"
    :admin/versions "Versions"
    :admin/language "Language"
    :admin/all-langs "All languages"
    :admin/latest "Latest"
    :admin/drop "Drop all"
    :admin/compact "Keep latest only"
    :admin/confirm "Confirm?"
    :admin/empty "No knowledge items."
    :admin/login-required "Log in to administer."
    :admin/not-authorized "Administrator access only."
    :admin/issues-title "Reference consistency"
    :admin/issues-none "No broken references."
    :admin/issues-broken "broken"
    :admin/issues-self "self-reference"
    :author/member-since "Member since"
    :author/last-activity "last edit"
    :author/search-ph "Search their knowledge…"
    :author/kis "items"
    :author/no-kis "No knowledge item matches."
    :author/unknown "Unknown author"
    :source/heading "Sources"
    :source/find "Find a source"
    :source/find-title "Find a source"
    :source/create-title "New source"
    :source/edit-title "Edit source"
    :source/save "Save"
    :source/create-new "Create"
    :source/find-existing "Search"
    :source/author "Author"
    :source/author-ph "Search or create an author…"
    :source/title "Title"
    :source/year "Year"
    :source/editor "Editor"
    :source/locator-ph "page / entry…"
    :source/recent "Recent:"
    :source/new-person "New author"
    :source/add "Add source"
    :source/no-results "No source found."
    :ref/remove "Remove reference"
    :card/quotes "Quotes a source"
    :card/original "original author"
    :prefs/account "Account"
    :prefs/connection "Sign-in method"
    :prefs/via-password "Email & password"
    :prefs/not-signed-in "You are not signed in."
    :search/placeholder "Search knowledge items…"
    :search/no-matches "No matches."
    :discover/tagline "Knowledge Items — reasoning made legible."
    :discover/empty "No knowledge items yet."
    :discover/view "view"
    :discover/views "views"
    :ki/edit "Edit — create a new version"
    :ki/login-to-edit "Log in to edit"
    :ki/login-to-add "Log in to add inputs"
    :ki/add-input "Add as input"
    :ki/remove-input "Remove this input link"
    :ki/search-input "Search a KI by name…"
    :ki/create-new "+ create a new KI"
    :ki/new-input "New input KI"
    :ki/create-and-add "Create & add"
    :ki/versions "Show all versions"
    :kind/inference "Inference"
    :kind/prediction "Prediction"
    :kind/postulate "Postulate"
    :kind/definition "Definition"
    :kind/position "Position"
    :kind/belief "Belief"
    :kind/credo "Credo"
    :kind/definition-link "See this type's definition"
    :ki/other-languages "Also in"
    :ki/create-translation "Create this language version"
    :ki/lang-notice-shown "You're viewing this item in"
    :ki/lang-notice-switch "See the version in your language"
    :translate/to "Translate to"
    :translate/source "Source"
    :translate/your "Your translation"
    :translate/suggesting "Suggesting…"
    :translate/create "Create translation"
    :translate/creating "Creating…"
    :form/new-title "New Knowledge Item"
    :form/name "Name"
    :form/name-ph "a short identity slug"
    :form/title "Title"
    :form/title-ph "a readable title (optional)"
    :form/type "Type"
    :form/language "Language"
    :form/statement "Output statement"
    :form/statement-ph "the claim this KI asserts"
    :form/create "Create"
    :form/creating "Creating…"
    :form/login-to-create "Log in to create"
    :form/save "Save new version"
    :form/saving "Saving…"
    :form/save-failed "Save failed — see console."
    :form/cancel "Cancel"
    :form/next "→ next"
    :auth/login "Log in"
    :auth/register "Register"
    :auth/logout "Log out"
    :auth/google "Continue with Google"
    :auth/or "— or —"
    :auth/email "Email"
    :auth/password "Password"
    :auth/alias "Alias"
    :auth/create-account "Create an account"
    :auth/have-account "Already have an account? "
    :auth/new-here "New here? "
    :auth/login-to-contribute "Log in to contribute"
    :auth/error "Something went wrong"
    :footer/home "Home"
    :footer/legal-notice "Legal notice"
    :footer/privacy "Privacy"
    :footer/disclaimer "Disclaimer"
    :footer/who-are-we "Who are we?"}})

(defn t
  "Translate key `k` into language `lang`, falling back to the default language
  then to the key name."
  [lang k]
  (or (get-in dict [(language/normalize lang) k]) (get-in dict [language/default-lang k]) (name k)))

;; ---------------------------------------------------------------------------
;; Language-fixed path builders
;; ---------------------------------------------------------------------------

(defn- base [lang] (str "/agora/" (language/normalize lang)))
(defn home "The Agora landing/home page (marketing hero + live example)." [lang] (base lang))
(defn discover [lang] (str (base lang) "/discover"))
(defn new-ki [lang] (str (base lang) "/new"))
(defn ki-id
  "The app URL of a KI by its concrete id (a specific version): /agora/<lang>/ki/<id>.
  Distinct from the public permalink `ki` (name + major), which resolves to the
  latest minor."
  [lang id]
  (str (base lang) "/ki/" id))
(defn article [lang id] (str (base lang) "/article/" id))
(defn articles "The article discover page." [lang] (str (base lang) "/articles"))
(defn new-article "The article authoring page." [lang] (str (base lang) "/article/new"))
(defn article-permalink
  "Public permalink of an article (name + major) in `lang`."
  [lang {:keys [name major]}]
  (str (base lang) "/article/" (js/encodeURIComponent name) "/" major))
(defn preferences [lang] (str (base lang) "/preferences"))
(defn admin [lang] (str (base lang) "/admin"))
(defn author "The author profile page for account `id`." [lang id] (str (base lang) "/author/" id))
(defn ki
  "Public permalink of a KI (name + major) in `lang`."
  [lang {:keys [name major]}]
  (str (base lang) "/ki/" (js/encodeURIComponent name) "/" major))

;; --- Generic, type-driven document URLs ------------------------------------
;; Every document type shares one URL shape, with its `:type` as the path segment:
;;   permalink  /agora/<lang>/<type>/<name>/<major>   (latest minor of an identity)
;;   by-id      /agora/<lang>/<type>/<id>             (one concrete version)
;; so the generic engine builds URLs from a document's own `:type` — it never needs to
;; know which types exist.
(defn doc-permalink
  "Public permalink of any document — `/agora/<lang>/<type>/<name>/<major>`."
  [lang type name major]
  (str (base lang) "/" type "/" (js/encodeURIComponent name) "/" major))
(defn doc-url
  "App URL of one concrete document version — `/agora/<lang>/<type>/<id>`."
  [lang type id]
  (str (base lang) "/" type "/" id))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

;; `::lang` is the INTERFACE language — a user preference, decoupled from the URL.
;; It drives the chrome, the discover feed and search. A KI permalink's own
;; `/agora/<lang>/…` segment is a separate dimension: the content language to
;; display for that page, which may differ from the preference.

(rf/reg-sub ::lang (fn [db _] (or (::lang db) language/default-lang)))

(defn set-lang
  "Store the normalized language in app-db (for use inside event handlers)."
  [db lang]
  (assoc db ::lang (language/normalize lang)))

(defn current
  "The current interface language from app-db (for use inside event handlers)."
  [db]
  (or (::lang db) language/default-lang))

;; ---- Preference persistence (localStorage; DB sync lives in core) ----

(def ^:private ls-key "agora-lang")

(defn read-stored
  "The preferred language cached in localStorage, or nil."
  []
  (try (some-> js/localStorage
               (.getItem ls-key))
       (catch :default _ nil)))

(defn write-stored!
  "Cache the preferred language in localStorage (fast path for next page loads)."
  [lang]
  (try (some-> js/localStorage
               (.setItem ls-key lang))
       (catch :default _ nil)))

(defn- cookie-lang
  []
  (some->> (.-cookie js/document)
           (re-find #"(?:^|;\s*)lang=([a-z]{2})")
           second))

(defn- browser-lang
  []
  (some-> js/navigator
          .-language
          (subs 0 2)))

(defn initial-pref
  "The preference to start with: localStorage → shared `lang` cookie → browser →
  default. Normalized to a supported code."
  []
  (language/normalize (or (read-stored) (cookie-lang) (browser-lang) language/default-lang)))
