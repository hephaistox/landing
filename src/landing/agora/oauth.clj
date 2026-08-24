(ns landing.agora.oauth
  "Google OAuth 2.0 (authorization-code flow) for Agora login.

  Config comes from env vars:
   - GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET — from the Google Cloud console
   - OAUTH_BASE_URL — the site's public base (e.g. http://localhost:8080 in dev,
     https://www.hephaistox.fr in prod); the redirect URI is
     `<OAUTH_BASE_URL>/agora/api/auth/google/callback` and must match the one
     registered in the console exactly.

  The token/userinfo calls go through the JDK `HttpClient` with an `SSLContext` built from the JDK's
  bundled cacerts, so TLS to Google is trusted regardless of the ambient `javax.net.ssl.trustStore`
  setting."
  (:require
   [cheshire.core  :as json]
   [clojure.string :as str])
  (:import
    (java.io FileInputStream)
    (java.net URI URLEncoder)
    (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
    (java.security KeyStore)
    (javax.net.ssl SSLContext TrustManagerFactory)))

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

(defn- jdk-trust-managers
  "Trust managers loaded from the JDK's bundled cacerts (`$JAVA_HOME/lib/security/cacerts`, default
  password `changeit`). Reading the store explicitly bypasses `javax.net.ssl.trustStore`, so a bad or
  empty value of that property can't leave the TLS trust anchors empty."
  []
  (let [ks (KeyStore/getInstance (KeyStore/getDefaultType))
        path (str (System/getProperty "java.home") "/lib/security/cacerts")]
    (with-open [in (FileInputStream. path)] (.load ks in (.toCharArray "changeit")))
    (let [tmf (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))]
      (.init tmf ks)
      (.getTrustManagers tmf))))

(def ^:private client
  "A shared `HttpClient` trusting the JDK cacerts. Built lazily so a missing cacerts fails at first
  use, not at namespace load."
  (delay (let [ctx (SSLContext/getInstance "TLS")]
           (.init ctx nil (jdk-trust-managers) nil)
           (-> (HttpClient/newBuilder)
               (.sslContext ctx)
               (.build)))))

(defn- send-json
  "Send `req` and parse its body as JSON (keyword keys), or nil on failure."
  [req]
  (try (json/parse-string (.body (.send @client req (HttpResponse$BodyHandlers/ofString))) true)
       (catch Exception _ nil)))

(defn exchange-code
  "Exchange an authorization `code` for tokens; returns the parsed token response (with
  `:access_token`), or nil on failure."
  [code]
  (let [form (str/join "&"
                       (for [[k v] {"code" code
                                    "client_id" (env "GOOGLE_CLIENT_ID")
                                    "client_secret" (env "GOOGLE_CLIENT_SECRET")
                                    "redirect_uri" (redirect-uri)
                                    "grant_type" "authorization_code"}]
                         (str k "=" (enc v))))]
    (send-json (-> (HttpRequest/newBuilder (URI/create token-endpoint))
                   (.header "Content-Type" "application/x-www-form-urlencoded")
                   (.POST (HttpRequest$BodyPublishers/ofString form))
                   (.build)))))

(defn fetch-userinfo
  "Fetch the Google profile for `access-token`: {:sub :email :name …}, or nil on failure."
  [access-token]
  (send-json (-> (HttpRequest/newBuilder (URI/create userinfo-endpoint))
                 (.header "Authorization" (str "Bearer " access-token))
                 (.GET)
                 (.build))))
