(ns landing.agora.endpoints.auth
  "Auth HTTP routes (#38): register / login / logout / me. Session is a signed
  cookie (see landing.handler); responses set/clear :session to log in/out."
  (:require
   [landing.agora.auth                :as auth]
   [landing.agora.oauth               :as oauth]
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

(def register-handler
  (fn [req]
    (let [{:keys [email password display-name]} (get-in req [:parameters :body])
          [status v] (auth/register {:email email
                                     :password password
                                     :display-name display-name})]
      (if (= status :ok)
        {:status 201
         :body v
         :session {:user-id (:id v)}}
        (case v
          :email-taken {:status 409
                        :body {:error "email already registered"}}
          :db-error {:status 503
                     :body {:error "service temporarily unavailable, please retry"}}
          {:status 400
           :body {:error "email and password are required"}})))))

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
       :body (auth/set-lang! uid (get-in req [:parameters :body :lang]))}
      {:status 401
       :body {:error "login required"}})))

;; ---- Google OAuth (#38) ----

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
          expected (get-in req [:session :oauth-state])]
      (if (and code state expected (= state expected))
        (let [tokens (oauth/exchange-code code)
              info (oauth/fetch-userinfo (:access_token tokens))]
          (if-let [profile (and (:sub info)
                                (auth/upsert-oauth-user {:provider "google"
                                                         :provider-id (:sub info)
                                                         :email (:email info)
                                                         :display-name (:name info)
                                                         :avatar-url (:picture info)}))]
            {:status 302
             :headers {"Location" "/discover"}
             :session {:user-id (:id profile)}}
            ;; no :sub, or upsert failed (e.g. DB error, logged in auth/upsert-oauth-user)
            {:status 302
             :headers {"Location" "/discover?login=failed"}}))
        {:status 302
         :headers {"Location" "/discover?login=failed"}}))))

(defn auth-routes
  [prefix]
  [prefix {:coercion coercion
           :muuntaja m/instance
           :swagger {:tags #{:auth}}
           :middleware mw}
   ["/register"
    {:post {:handler register-handler
            :operationId "agora-register"
            :parameters {:body [:map
                                [:email :string]
                                [:password :string]
                                [:display-name {:optional true}
                                 :string]]}
            :summary "Register a password account"}}]
   ["/login"
    {:post {:handler login-handler
            :operationId "agora-login"
            :parameters {:body [:map [:email :string] [:password :string]]}
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
            :parameters {:body [:map [:lang :string]]}
            :summary "Set the preferred interface language"}}]
   ["/google"
    {:get {:handler google-start-handler
           :no-doc true
           :summary "Start Google OAuth"}}]
   ["/google/callback"
    {:get {:handler google-callback-handler
           :no-doc true
           :summary "Google OAuth callback"}}]])
