(ns landing.agora.frontend.i18n
  "UI translations and language-fixed path builders for the Agora SPA.

  Two languages for now — `fr` / `en`, matching the landing site. Add a language
  by adding its map to `dict` and its code to `supported`; everything else adapts
  automatically.

  There are two language dimensions:
   - the INTERFACE language (`::lang`), a user preference driving the chrome, the
     discover feed and search. Cached in localStorage and, for logged-in users,
     persisted to the account (see core `:agora/set-lang`). NOT derived from the
     URL.
   - a KI permalink's `/agora/<lang>/…` segment, the CONTENT language to display
     for that page — independent of the preference (the permalink overrides it).

  Path builders take a language explicitly. `t`/`::lang` use the preference."
  (:require
   [clojure.string :as str]
   [re-frame.core  :as rf]))

(def supported "Content + UI language codes (ISO 639-1). First is the default." ["fr" "en"])

(def default-lang (first supported))

(defn normalize
  "Coerce a raw language string to a supported code, else the default."
  [lang]
  (let [l (some-> lang
                  str/lower-case)]
    (if (some #{l} supported) l default-lang)))

;; ---------------------------------------------------------------------------
;; Dictionary
;; ---------------------------------------------------------------------------

(def ^:private dict
  {"fr" {:nav/new-ki "Nouveau"
         :nav/preferences "Préférences"
         :nav/admin "Admin"
         :prefs/title "Préférences"
         :admin/title "Administration"
         :admin/major "Majeur"
         :admin/languages "Langues"
         :admin/versions "Versions"
         :admin/latest "Dernier"
         :admin/drop "Tout supprimer"
         :admin/compact "Garder le dernier"
         :admin/confirm "Confirmer ?"
         :admin/empty "Aucun élément de connaissance."
         :admin/login-required "Connectez-vous pour administrer."
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
         :type/derived "Dérivé"
         :type/verifiable-claim "Assertion vérifiable"
         :type/postulate "Postulat"
         :type/stance "Position"
         :type/belief "Croyance"
         :type/credo "Credo"
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
   "en" {:nav/new-ki "New"
         :nav/preferences "Preferences"
         :nav/admin "Admin"
         :prefs/title "Preferences"
         :admin/title "Administration"
         :admin/major "Major"
         :admin/languages "Languages"
         :admin/versions "Versions"
         :admin/latest "Latest"
         :admin/drop "Drop all"
         :admin/compact "Keep latest only"
         :admin/confirm "Confirm?"
         :admin/empty "No knowledge items."
         :admin/login-required "Log in to administer."
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
         :type/derived "Derived"
         :type/verifiable-claim "Verifiable claim"
         :type/postulate "Postulate"
         :type/stance "Stance"
         :type/belief "Belief"
         :type/credo "Credo"
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

(def language-name
  "Human label for a language code, shown in the switcher."
  {"fr" "Français"
   "en" "English"})

(defn t
  "Translate key `k` into language `lang`, falling back to the default language
  then to the key name."
  [lang k]
  (or (get-in dict [(normalize lang) k]) (get-in dict [default-lang k]) (name k)))

;; ---------------------------------------------------------------------------
;; Language-fixed path builders
;; ---------------------------------------------------------------------------

(defn- base [lang] (str "/agora/" (normalize lang)))
(defn discover [lang] (str (base lang) "/discover"))
(defn new-ki [lang] (str (base lang) "/lab/ki/new"))
(defn lab-ki-root [lang] (str (base lang) "/lab/ki"))
(defn lab-ki [lang id] (str (base lang) "/lab/ki/" id))
(defn lab-article [lang id] (str (base lang) "/lab/article/" id))
(defn preferences [lang] (str (base lang) "/preferences"))
(defn admin [lang] (str (base lang) "/admin"))
(defn ki
  "Public permalink of a KI (name + major) in `lang`."
  [lang {:keys [name major]}]
  (str (base lang) "/ki/" (js/encodeURIComponent name) "/" major))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

;; `::lang` is the INTERFACE language — a user preference, decoupled from the URL.
;; It drives the chrome, the discover feed and search. A KI permalink's own
;; `/agora/<lang>/…` segment is a separate dimension: the content language to
;; display for that page, which may differ from the preference.

(rf/reg-sub ::lang (fn [db _] (or (::lang db) default-lang)))

(defn set-lang
  "Store the normalized language in app-db (for use inside event handlers)."
  [db lang]
  (assoc db ::lang (normalize lang)))

(defn current
  "The current interface language from app-db (for use inside event handlers)."
  [db]
  (or (::lang db) default-lang))

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
  (normalize (or (read-stored) (cookie-lang) (browser-lang) default-lang)))
