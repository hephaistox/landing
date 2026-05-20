(ns landing.handler-test
  (:require
   [clojure.string    :as str]
   [clojure.test      :refer [deftest is testing]]
   [landing.handler   :as sut]
   [ring.mock.request :as mock]))

(defn- get-route
  "Invoke the route's :get handler with the given request."
  [route req]
  (let [h (get-in (second route) [:get :handler])] (h req)))

(deftest root-redirect-test
  (testing "defaults to French when no cookie nor Accept-Language"
    (is (= "/fr/index.html"
           (-> (get-route (sut/root-redirect-route "/") {})
               (get-in [:headers "Location"])))))
  (testing "honors lang cookie"
    (is (= "/en/index.html"
           (-> (get-route (sut/root-redirect-route "/")
                          {:headers {"cookie" "foo=bar; lang=:en; baz=qux"}})
               (get-in [:headers "Location"]))))
    (is (= "/fr/index.html"
           (-> (get-route (sut/root-redirect-route "/") {:headers {"cookie" "lang=:fr"}})
               (get-in [:headers "Location"])))))
  (testing "falls back to Accept-Language when no cookie"
    (is (= "/en/index.html"
           (-> (get-route (sut/root-redirect-route "/")
                          {:headers {"accept-language" "en-US,en;q=0.9,fr;q=0.8"}})
               (get-in [:headers "Location"])))))
  (testing "cookie wins over Accept-Language"
    (is (= "/fr/index.html"
           (-> (get-route (sut/root-redirect-route "/")
                          {:headers {"cookie" "lang=:fr"
                                     "accept-language" "en"}})
               (get-in [:headers "Location"])))))
  (testing "accepts the colon-less cookie form (current writer)"
    (is (= "/en/index.html"
           (-> (get-route (sut/root-redirect-route "/") {:headers {"cookie" "lang=en"}})
               (get-in [:headers "Location"])))))
  (testing "accepts the URL-encoded legacy form (%3A)"
    (is (= "/en/index.html"
           (-> (get-route (sut/root-redirect-route "/") {:headers {"cookie" "lang=%3Aen"}})
               (get-in [:headers "Location"])))))
  (testing "tolerates capitalized Cookie header"
    (is (= "/en/index.html"
           (-> (get-route (sut/root-redirect-route "/") {:headers {"Cookie" "lang=en"}})
               (get-in [:headers "Location"]))))))

(deftest legacy-articles-redirect-test
  (testing "rewrites legacy /articles/<slug> to /<lang>/articles/<slug>.html"
    (is (= "/fr/articles/rivalis.html"
           (-> (get-route (sut/legacy-articles-route "/articles") {:path-params {:slug "rivalis"}})
               (get-in [:headers "Location"])))))
  (testing "preserves an existing .html suffix"
    (is (= "/fr/articles/projets.html"
           (-> (get-route (sut/legacy-articles-route "/articles")
                          {:path-params {:slug "projets.html"}})
               (get-in [:headers "Location"])))))
  (testing "uses cookie language"
    (is (= "/en/articles/rivalis.html"
           (-> (get-route (sut/legacy-articles-route "/articles")
                          {:headers {"cookie" "lang=:en"}
                           :path-params {:slug "rivalis"}})
               (get-in [:headers "Location"])))))
  (testing "accepts the colon-less cookie form on legacy article redirect"
    (is (= "/en/articles/rivalis.html"
           (-> (get-route (sut/legacy-articles-route "/articles")
                          {:headers {"cookie" "lang=en"}
                           :path-params {:slug "rivalis"}})
               (get-in [:headers "Location"]))))))

(deftest lang-fallback-handler-test
  (testing "redirects missing /fr/... to /fr/index.html"
    (is (= {:status 302
            :headers {"Location" "/fr/index.html"}}
           (sut/lang-fallback-handler {:uri "/fr/articles/does-not-exist.html"}))))
  (testing "redirects missing /en/... to /en/index.html"
    (is (= {:status 302
            :headers {"Location" "/en/index.html"}}
           (sut/lang-fallback-handler {:uri "/en/missing"}))))
  (testing "ignores paths outside /fr/ or /en/"
    (is (nil? (sut/lang-fallback-handler {:uri "/something"})))))

;; ----------------------------------------------------------------------------
;; Integration tests — hit the wired-up handler chain against real resources.
;; ----------------------------------------------------------------------------

(def article-slugs
  ["rivalis"
   "projets"
   "hephaistox"
   "who-are-we"
   "contacts"
   "about-site"
   "legal-notice"
   "privacy"
   "disclaimer"])

(deftest all-static-pages-served-test
  (let [h (sut/handler)]
    (testing "Both index pages serve 200"
      (doseq [path ["/fr/index.html" "/en/index.html"]]
        (is (= 200 (:status (h (mock/request :get path)))) (str path " returns 200"))))
    (testing "Every article page serves 200 in both languages"
      (doseq [lang ["fr" "en"]
              slug article-slugs]
        (let [path (str "/" lang "/articles/" slug ".html")]
          (is (= 200 (:status (h (mock/request :get path)))) (str path " returns 200")))))
    (testing "Confirmation page is reachable in both languages"
      (doseq [path ["/fr/articles/contact-validated.html" "/en/articles/contact-validated.html"]]
        (is (= 200 (:status (h (mock/request :get path)))))))
    (testing "Static 404 pages exist for both languages"
      (doseq [path ["/fr/404.html" "/en/404.html"]]
        (is (= 200 (:status (h (mock/request :get path)))))))))

(deftest routing-integration-test
  (let [h (sut/handler)]
    (testing "GET / 302s to /fr/index.html by default"
      (let [resp (h (mock/request :get "/"))]
        (is (= 302 (:status resp)))
        (is (= "/fr/index.html" (get-in resp [:headers "Location"])))))
    (testing "GET / honors lang=:en cookie"
      (let [resp (h (-> (mock/request :get "/")
                        (mock/header "cookie" "lang=:en")))]
        (is (= "/en/index.html" (get-in resp [:headers "Location"])))))
    (testing "GET / honors Accept-Language"
      (let [resp (h (-> (mock/request :get "/")
                        (mock/header "accept-language" "en-US,en;q=0.9")))]
        (is (= "/en/index.html" (get-in resp [:headers "Location"])))))
    (testing "Legacy /articles/<slug> 301s with the right .html suffix"
      (let [resp (h (mock/request :get "/articles/rivalis"))]
        (is (= 301 (:status resp)))
        (is (= "/fr/articles/rivalis.html" (get-in resp [:headers "Location"])))))
    (testing "Legacy /articles/<slug>.html doesn't double the suffix"
      (let [resp (h (mock/request :get "/articles/rivalis.html"))]
        (is (= "/fr/articles/rivalis.html" (get-in resp [:headers "Location"])))))
    (testing "Missing /fr/... falls back to /fr/index.html"
      (let [resp (h (mock/request :get "/fr/articles/missing.html"))]
        (is (= 302 (:status resp)))
        (is (= "/fr/index.html" (get-in resp [:headers "Location"])))))
    (testing "Legacy article route only matches a single segment"
      (let [resp (h (mock/request :get "/articles/foo/bar"))]
        ;; nested path is not rewritten — falls through to default 404
        (is (= 404 (:status resp)))))
    (testing "GET /ping returns pong"
      (let [resp (h (mock/request :get "/ping"))]
        (is (= 200 (:status resp)))
        (is (= "pong" (:body resp)))))))

(deftest cache-and-cors-test
  (let [h (sut/handler)]
    (testing "Static asset gets a long immutable Cache-Control"
      (let [resp (h (mock/request :get "/custom.css"))]
        (is (= "public, max-age=31536000, immutable" (get-in resp [:headers "Cache-Control"])))
        (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"])))))
    (testing "JS asset gets a long immutable Cache-Control"
      (let [resp (h (mock/request :get "/js/lang.js"))]
        (is (= "public, max-age=31536000, immutable" (get-in resp [:headers "Cache-Control"])))))
    (testing "HTML gets a short Cache-Control"
      (let [resp (h (mock/request :get "/fr/index.html"))]
        (is (= "public, max-age=300" (get-in resp [:headers "Cache-Control"])))))
    (testing "Other files get the default Cache-Control"
      (let [resp (h (mock/request :get "/robots.txt"))]
        (is (= "public, max-age=600" (get-in resp [:headers "Cache-Control"])))))))

(defn- body-string
  "Coerce a ring response body to a string regardless of the underlying type."
  [body]
  (cond
    (string? body) body
    (instance? java.io.File body) (slurp body)
    (instance? java.io.InputStream body) (slurp body)
    :else (str body)))

(deftest seo-artifacts-test
  (let [h (sut/handler)]
    (testing "sitemap.xml references /fr/ and /en/ URLs with hreflang alternates"
      (let [body (body-string (:body (h (mock/request :get "/sitemap.xml"))))]
        (is (str/includes? body "https://hephaistox.fr/fr/index.html"))
        (is (str/includes? body "https://hephaistox.fr/en/index.html"))
        (is (str/includes? body "hreflang=\"fr\""))
        (is (str/includes? body "hreflang=\"en\""))
        (is (str/includes? body "hreflang=\"x-default\""))))
    (testing "robots.txt has the sitemap line and disallows API endpoints"
      (let [body (body-string (:body (h (mock/request :get "/robots.txt"))))]
        (is (str/includes? body "Sitemap:"))
        (is (str/includes? body "Disallow: /api"))
        (is (str/includes? body "Disallow: /check-url"))
        (is (str/includes? body "Disallow: /w3c-validate"))
        (is (not (str/includes? body "/all-kind-of-checks"))
            "admin path is intentionally not advertised in robots.txt")))
    (testing "sitemap.xml declares the xhtml namespace used by hreflang alternates"
      (let [body (body-string (:body (h (mock/request :get "/sitemap.xml"))))]
        (is (str/includes? body "xmlns:xhtml=\"http://www.w3.org/1999/xhtml\""))
        (is (str/includes? body "<xhtml:link"))))
    (testing "Every public page has hreflang link tags"
      (doseq [path (concat ["/fr/index.html" "/en/index.html"]
                           (for [lang ["fr" "en"]
                                 slug article-slugs]
                             (str "/" lang "/articles/" slug ".html")))]
        (let [body (body-string (:body (h (mock/request :get path))))]
          (is (str/includes? body "hreflang=\"fr\"") (str path " has fr hreflang"))
          (is (str/includes? body "hreflang=\"en\"") (str path " has en hreflang")))))))

(deftest no-cookie-leak-on-404-test
  (testing "Default handler must not overwrite the client's `lang` cookie"
    (let [h (sut/handler)
          resp (h (mock/request :get "/this-route-does-not-exist"))
          set-cookie (or (get-in resp [:headers "Set-Cookie"])
                         (get-in resp [:headers "set-cookie"]))]
      (is (or (nil? set-cookie) (not (re-find #"(?i)lang=" (str set-cookie))))
          "404 response must not include a Set-Cookie: lang=..."))))

(deftest contacts-page-wires-contact-form-js-test
  (testing "Contacts page loads the contact-form.js helper"
    (let [h (sut/handler)]
      (doseq [path ["/fr/articles/contacts.html" "/en/articles/contacts.html"]]
        (let [body (body-string (:body (h (mock/request :get path))))]
          (is (str/includes? body "/js/contact-form.js") (str path " references contact-form.js"))
          (is (str/includes? body "id=\"contact-form\"")
              (str path " has the form element the JS hooks into"))
          (is (str/includes? body "name=\"adress\"")
              (str path " keeps the honeypot field the backend checks")))))))

(deftest content-type-test
  (let [h (sut/handler)]
    (testing "Static HTML carries text/html content type"
      (let [resp (h (mock/request :get "/fr/index.html"))]
        (is (re-find #"text/html" (get-in resp [:headers "Content-Type"])))))
    (testing "CSS carries text/css"
      (let [resp (h (mock/request :get "/custom.css"))]
        (is (re-find #"text/css" (get-in resp [:headers "Content-Type"])))))
    (testing "JS carries application/javascript or text/javascript"
      (let [resp (h (mock/request :get "/js/lang.js"))]
        (is (re-find #"(application|text)/javascript" (get-in resp [:headers "Content-Type"])))))))
