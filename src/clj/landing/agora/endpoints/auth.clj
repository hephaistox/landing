(ns landing.agora.endpoints.auth
  "Auth HTTP routes: register / login / logout / me. Session is a signed
  cookie (see landing.handler); responses set/clear :session to log in/out."
  (:require
   [auto-core.log                     :as core-log]
   [auto-web.middleware.rate-limit    :refer [make-rate-limiter stop-rate-limit]]
   [landing.agora.altcha              :as altcha]
   [landing.agora.auth                :as auth]
   [landing.agora.oauth               :as oauth]
   [landing.language                  :as language]
   [mount.core                        :refer [defstate]]
   [muuntaja.core                     :as m]
   [reitit.coercion.malli             :refer [coercion]]
   [reitit.ring.coercion              :as rcoercion]
   [reitit.ring.middleware.exception  :as exception]
   [reitit.ring.middleware.muuntaja   :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters])
  (:import (java.util UUID)))

(def ^:private mw
  [parameters/parameters-middleware
   muuntaja/format-negotiate-middleware
   muuntaja/format-response-middleware
   exception/exception-middleware
   muuntaja/format-request-middleware
   rcoercion/coerce-request-middleware])

(defstate
 credential-rate-limiter
 "Per-IP throttle on the credential endpoints (login / register) to blunt
          brute-force and credential-stuffing — 429 once exceeded. Reuses the shared
          landing rate limiter, same as /ping and /contact."
 :start (make-rate-limiter {:limit 10
                            :window-ms 60000
                            :name "landing.agora.auth"
                            :cleanup-interval-ms 60000})
 :stop (stop-rate-limit credential-rate-limiter))

(def altcha-challenge-handler
  "Issue a fresh ALTCHA proof-of-work challenge for the registration widget."
  (fn [_]
    {:status 200
     :body (altcha/challenge)}))

(defn- register-account
  [email password display-name]
  (let [[status v] (auth/register {:email email
                                   :password password
                                   :display-name display-name})]
    (if (= status :ok)
      {:status 201
       :body v
       :session {:user-id (:id v)}}
      (case v
        :email-taken {:status 409
                      :body {:error "email already registered"}}
        :weak-password
        {:status 400
         :body {:error (str "password must be at least " auth/min-password-length " characters")}}
        :db-error {:status 503
                   :body {:error "service temporarily unavailable, please retry"}}
        {:status 400
         :body {:error "email and password are required"}}))))

(def register-handler
  (fn [req]
    (let [{:keys [email password display-name altcha]} (get-in req [:parameters :body])]
      (if-not (altcha/verify altcha)
        {:status 400
         :body {:error "human verification failed, please try again"}}
        (register-account email password display-name)))))

(def login-handler
  (fn [req]
    (let [{:keys [email password]} (get-in req [:parameters :body])
          [status v] (auth/authenticate email password)]
      (cond
        (= status :ok) {:status 200
                        :body v
                        :session {:user-id (:id v)}}
        (= v :db-error) {:status 503
                         :body {:error "service temporarily unavailable, please retry"}}
        :else {:status 401
               :body {:error "invalid email or password"}}))))

(def logout-handler
  (fn [_]
    {:status 200
     :body {:ok true}
     :session nil}))

(def me-handler
  "Return the current user's profile, or null when logged out."
  (fn [req]
    (if-let [uid (get-in req [:session :user-id])]
      {:status 200
       :body (auth/get-user uid)}
      {:status 200
       :body nil})))

(def set-lang-handler
  "Persist the logged-in user's preferred interface language. 200 with the updated
  profile, 401 when logged out (anonymous users keep the preference client-side)."
  (fn [req]
    (if-let [uid (get-in req [:session :user-id])]
      {:status 200
       ;; normalize to a supported code — never persist an arbitrary string
       :body (auth/set-lang! uid (language/normalize (get-in req [:parameters :body :lang])))}
      {:status 401
       :body {:error "login required"}})))

;; ---- Google OAuth ----

(def google-start-handler
  "Redirect to Google's consent screen, stashing a CSRF state in the session."
  (fn [req]
    (if (oauth/configured?)
      (let [state (str (UUID/randomUUID))]
        {:status 302
         :headers {"Location" (oauth/authorize-url state)}
         :session (assoc (:session req) :oauth-state state)})
      {:status 503
       :body {:error "Google login is not configured"}})))

(def google-callback-handler
  "Google redirects here with ?code&state. Verify state, exchange the code, fetch
  the profile, upsert the user, set the session, and land on the app."
  (fn [req]
    (let [{:strs [code state]} (:query-params req)
          expected (get-in req [:session :oauth-state])
          ;; land back inside the Agora app, in the caller's language
          discover (str "/agora/" (language/pick-lang req) "/discover")
          ;; log *why* a callback failed (the flow is otherwise silent — exchange-code
          ;; swallows Google's error), then send the user back with ?login=failed. Only
          ;; :error/:error_description are logged — never the client secret or a token.
          fail (fn [reason]
                 (core-log/warn (str "Google OAuth callback failed — " reason))
                 {:status 302
                  :headers {"Location" (str discover "?login=failed")}})]
      (cond
        (not (and code state expected (= state expected))) (fail (str "state/CSRF check "
                                                                      {:code? (boolean code)
                                                                       :state? (boolean state)
                                                                       :session-state? (boolean
                                                                                        expected)
                                                                       :match? (= state expected)}))
        :else
        (let [tokens (oauth/exchange-code code)]
          (cond
            (not (:access_token tokens))
            (fail (str "token exchange rejected: "
                       (pr-str (select-keys tokens [:error :error_description]))))
            :else
            (let [info (oauth/fetch-userinfo (:access_token tokens))]
              (cond
                (not (:sub info)) (fail (str "userinfo without :sub: "
                                             (pr-str (select-keys info
                                                                  [:error :error_description]))))
                :else
                (if-let [profile (auth/upsert-oauth-user {:provider "google"
                                                          :provider-id (:sub info)
                                                          :email (:email info)
                                                          :display-name (:name info)
                                                          :avatar-url (:picture info)})]
                  {:status 302
                   :headers {"Location" discover}
                   :session {:user-id (:id profile)}}
                  (fail
                   "upsert-oauth-user returned nil (DB error, or AGORA_USER schema issue)"))))))))))

(defn auth-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:auth}}
           :middleware mw}
   ["/register"
    {:middleware [(:middleware-fn credential-rate-limiter)]
     :post {:handler register-handler
            :operationId "agora-register"
            :parameters {:body [:map
                                [:email
                                 [:string {:min 3
                                           :max 254}]]
                                [:password
                                 [:string {:min 1
                                           :max 200}]]
                                [:display-name {:optional true}
                                 [:string {:max 100}]]
                                [:altcha [:string {:max 4000}]]]}
            :summary "Register a password account"}}]
   ["/altcha-challenge"
    {:get {:handler altcha-challenge-handler
           :operationId "agora-altcha-challenge"
           :no-doc true
           :summary "Issue an ALTCHA proof-of-work challenge"}}]
   ["/login"
    {:middleware [(:middleware-fn credential-rate-limiter)]
     :post {:handler login-handler
            :operationId "agora-login"
            :parameters {:body
                         [:map [:email [:string {:max 254}]] [:password [:string {:max 200}]]]}
            :summary "Log in with email/password"}}]
   ["/logout"
    {:post {:handler logout-handler
            :operationId "agora-logout"
            :summary "Log out"}}]
   ["/me"
    {:get {:handler me-handler
           :operationId "agora-me"
           :summary "Current user profile, or null"}}]
   ["/lang"
    {:post {:handler set-lang-handler
            :operationId "agora-set-lang"
            :parameters {:body [:map [:lang [:string {:max 8}]]]}
            :summary "Set the preferred interface language"}}]
   ["/google"
    {:get {:handler google-start-handler
           :no-doc true
           :summary "Start Google OAuth"}}]
   ["/google/callback"
    {:get {:handler google-callback-handler
           :no-doc true
           :summary "Google OAuth callback"}}]])
