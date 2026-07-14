(ns landing.agora.oauth
  "Google OAuth 2.0 (authorization-code flow) for Agora login.

  Config comes from env vars:
   - GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET — from the Google Cloud console
   - OAUTH_BASE_URL — the site's public base (e.g. http://localhost:8080 in dev,
     https://www.hephaistox.fr in prod); the redirect URI is
     `<OAUTH_BASE_URL>/agora/api/auth/google/callback` and must match the one
     registered in the console exactly."
  (:require
   [clj-http.client :as http]
   [clojure.string  :as str])
  (:import (java.net URLEncoder)))

(def ^:private auth-endpoint "https://accounts.google.com/o/oauth2/v2/auth")
(def ^:private token-endpoint "https://oauth2.googleapis.com/token")
(def ^:private userinfo-endpoint "https://www.googleapis.com/oauth2/v3/userinfo")

(defn- env [k] (System/getenv k))

(defn configured?
  "True when the Google client credentials are present."
  []
  (boolean (and (env "GOOGLE_CLIENT_ID") (env "GOOGLE_CLIENT_SECRET"))))

(defn redirect-uri
  "The Google callback URL: `<OAUTH_BASE_URL>/agora/api/auth/google/callback`. Must
  match a URI registered in the Google console exactly — so any trailing slash on
  OAUTH_BASE_URL is stripped to avoid a `//callback` double-slash mismatch."
  []
  (str (-> (or (env "OAUTH_BASE_URL") "http://localhost:8080")
           (str/replace #"/+$" ""))
       "/agora/api/auth/google/callback"))

(defn- enc [s] (URLEncoder/encode (str s) "UTF-8"))

(defn authorize-url
  "The Google consent URL to redirect the user to. `state` is a CSRF token echoed
  back on the callback."
  [state]
  (let [params {"client_id" (env "GOOGLE_CLIENT_ID")
                "redirect_uri" (redirect-uri)
                "response_type" "code"
                "scope" "openid email profile"
                "state" state
                "access_type" "online"
                "prompt" "select_account"}]
    (str auth-endpoint "?" (str/join "&" (for [[k v] params] (str k "=" (enc v)))))))

(defn exchange-code
  "Exchange an authorization `code` for tokens; returns the parsed token response
  (with :access_token)."
  [code]
  (:body (http/post token-endpoint
                    {:form-params {:code code
                                   :client_id (env "GOOGLE_CLIENT_ID")
                                   :client_secret (env "GOOGLE_CLIENT_SECRET")
                                   :redirect_uri (redirect-uri)
                                   :grant_type "authorization_code"}
                     :as :json
                     :throw-exceptions false})))

(defn fetch-userinfo
  "Fetch the Google profile for `access-token`: {:sub :email :name …}."
  [access-token]
  (:body (http/get userinfo-endpoint
                   {:headers {"Authorization" (str "Bearer " access-token)}
                    :as :json
                    :throw-exceptions false})))
