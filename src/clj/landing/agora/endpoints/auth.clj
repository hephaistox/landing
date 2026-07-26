(ns landing.agora.endpoints.auth
  "Account and session: registration, login and logout, current user, interface-language preference,
  and Google OAuth.")

(defn- ok
  [_]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{}"})

(defn auth-routes
  [prefix]
  [prefix
   ["/register"
    {:post {:handler ok
            :operationId "agora-register"
            :summary "Register a password account"}}]
   ["/altcha-challenge"
    {:get {:handler ok
           :operationId "agora-altcha-challenge"
           :summary "Issue an ALTCHA proof-of-work challenge"}}]
   ["/login"
    {:post {:handler ok
            :operationId "agora-login"
            :summary "Log in with email/password"}}]
   ["/logout"
    {:post {:handler ok
            :operationId "agora-logout"
            :summary "Log out"}}]
   ["/me"
    {:get {:handler ok
           :operationId "agora-me"
           :summary "Current user profile, or null"}}]
   ["/lang"
    {:post {:handler ok
            :operationId "agora-set-lang"
            :summary "Set the preferred interface language"}}]
   ["/google"
    {:get {:handler ok
           :operationId "agora-google"
           :summary "Start Google OAuth"}}]
   ["/google/callback"
    {:get {:handler ok
           :operationId "agora-google-callback"
           :summary "Google OAuth callback"}}]])
