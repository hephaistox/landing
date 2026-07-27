(ns landing.agora.endpoints.auth
  "Account and session: registration, login and logout, current user, and the interface-language
  preference. The session is the outer `wrap-session` signed cookie — a handler logs a user in by
  returning `:session {:user-id id}`, out by returning `:session nil`, and reads the caller with
  `(get-in req [:session :user-id])`. Google OAuth logs in the same way after its callback, and
  registration is gated by an ALTCHA proof-of-work solution the server re-verifies."
  (:require
   [landing.agora.altcha              :as altcha]
   [landing.agora.auth                :as auth]
   [landing.agora.oauth               :as oauth]
   [muuntaja.core                     :as m]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   muuntaja/format-request-middleware])

(defn- uid [req] (get-in req [:session :user-id]))

(defn- me
  "The current user's public profile, or `null` when there is no session."
  [req]
  {:status 200
   :body (some-> (uid req)
                 auth/get-user)})

(defn- login
  "Verify email/password; on success establish the session and return the profile."
  [req]
  (let [{:keys [email password]} (:body-params req)
        [tag profile] (auth/authenticate email password)]
    (if (= tag :ok)
      {:status 200
       :body profile
       :session {:user-id (:id profile)}}
      {:status 401
       :body {:error (name profile)}})))

(defn- altcha-challenge
  "Issue a fresh ALTCHA proof-of-work challenge for the registration widget."
  [_req]
  {:status 200
   :body (altcha/challenge)})

(defn- register
  "Create a password account, gated by a valid ALTCHA solution; on success establish the session
  and return the profile."
  [req]
  (let [body (:body-params req)]
    (if (altcha/verify (:altcha body))
      (let [[tag profile] (auth/register body)]
        (if (= tag :ok)
          {:status 200
           :body profile
           :session {:user-id (:id profile)}}
          {:status 400
           :body {:error (name profile)}}))
      {:status 400
       :body {:error "captcha"}})))

(defn- logout
  "Clear the session."
  [_req]
  {:status 200
   :body {:ok true}
   :session nil})

(defn- set-lang
  "Persist the caller's preferred interface language (logged-in users only)."
  [req]
  (if-let [id (uid req)]
    (let [lang (get-in req [:body-params :lang])]
      (auth/set-lang! lang id)
      {:status 200
       :body {:lang lang}})
    {:status 401
     :body {:error "login required"}}))

(defn- google-start
  "Begin Google OAuth: stash a CSRF `state` in the session and redirect to Google's consent screen.
  Redirects home with a flag when the client credentials are not configured."
  [req]
  (if (oauth/configured?)
    (let [state (str (java.util.UUID/randomUUID))]
      {:status 302
       :headers {"Location" (oauth/authorize-url state)}
       :session (assoc (:session req) :oauth-state state)})
    {:status 302
     :headers {"Location" "/agora?login=unavailable"}}))

(defn- google-callback
  "Google redirects back with a `code` and the echoed `state`. Verify the state (CSRF), exchange the
  code for the profile, upsert the account, establish the session, and return to the app."
  [req]
  (let [state (get-in req [:query-params "state"])
        code (get-in req [:query-params "code"])
        expected (get-in req [:session :oauth-state])]
    (if (and code state expected (= state expected))
      (let [info (some-> (oauth/exchange-code code)
                         :access_token
                         oauth/fetch-userinfo)
            profile (when (:email info)
                      (auth/upsert-oauth-user {:provider "google"
                                               :provider-id (:sub info)
                                               :email (:email info)
                                               :display-name (:name info)
                                               :avatar-url (:picture info)}))]
        (if profile
          {:status 302
           :headers {"Location" "/agora"}
           :session {:user-id (:id profile)}}
          {:status 302
           :headers {"Location" "/agora?login=failed"}
           :session (dissoc (:session req) :oauth-state)}))
      {:status 400
       :body {:error "invalid oauth state"}})))

(defn auth-routes
  [prefix]
  [prefix {:muuntaja m/instance
           :middleware mw}
   ["/register"
    {:post {:handler register
            :operationId "agora-register"
            :summary "Register a password account"}}]
   ["/altcha-challenge"
    {:get {:handler altcha-challenge
           :operationId "agora-altcha-challenge"
           :summary "Issue an ALTCHA proof-of-work challenge"}}]
   ["/login"
    {:post {:handler login
            :operationId "agora-login"
            :summary "Log in with email/password"}}]
   ["/logout"
    {:post {:handler logout
            :operationId "agora-logout"
            :summary "Log out"}}]
   ["/me"
    {:get {:handler me
           :operationId "agora-me"
           :summary "Current user profile, or null"}}]
   ["/lang"
    {:post {:handler set-lang
            :operationId "agora-set-lang"
            :summary "Set the preferred interface language"}}]
   ["/google"
    {:get {:handler google-start
           :operationId "agora-google"
           :summary "Start Google OAuth"}}]
   ["/google/callback"
    {:get {:handler google-callback
           :operationId "agora-google-callback"
           :summary "Google OAuth callback"}}]])
