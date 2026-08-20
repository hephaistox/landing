(ns landing.endpoints.check-url-test
  (:require
   [clj-http.client             :as client]
   [clojure.test                :refer [deftest is testing]]
   [landing.endpoints.check-url :as sut]))

(defn- target [domain url] (#'sut/target-url domain url))

(defn- probe
  "Run the handler for one manifest entry, with the outbound HEAD stubbed. Returns
  `[response probed-url]`."
  [link-id origin domain]
  (let [probed (atom nil)]
    (with-redefs [client/head (fn [url _] (reset! probed url) {:status 200})]
      [(sut/check-url-handler {:parameters {:query {:link-id link-id
                                                    :origin origin
                                                    :domain domain}}})
       @probed])))

(deftest handler-still-checks-a-real-manifest-link-test
  (testing "a relative link from the manifest is probed against the caller's own site"
    (let [[resp probed] (probe :resume "landing.article.who-are-we" "http://localhost:8080/")]
      (is (= 200 (:status resp)))
      (is (= "http://localhost:8080/cv_caumond.pdf" probed))))
  (testing "the same link is refused when the caller points the base elsewhere, and nothing is sent"
    (let [[resp probed] (probe :resume "landing.article.who-are-we" "http://169.254.169.254/")]
      (is (= 400 (:status resp)))
      (is (nil? probed) "no outbound request is made at all"))))

(deftest absolute-manifest-links-are-probed-as-is-test
  (testing "an absolute link comes from our own manifest, so its host is one we chose to check"
    (is (= "https://www.linkedin.com/in/someone/"
           (target "http://localhost:8080/" "https://www.linkedin.com/in/someone/")))
    (is (= "https://agilemanifesto.org/iso/fr/manifesto.html"
           (target "http://localhost:8080/" "https://agilemanifesto.org/iso/fr/manifesto.html")))
    (testing "and the caller's domain cannot redirect it elsewhere"
      (is (= "https://agilemanifesto.org/x"
             (target "http://169.254.169.254/" "https://agilemanifesto.org/x"))))))

(deftest relative-links-resolve-only-against-our-own-sites-test
  (testing "our deployments and the dev server are accepted"
    (is (= "http://localhost:8080/fr/index.html"
           (target "http://localhost:8080/" "/fr/index.html")))
    (is (= "https://hephaistox.fr/fr/index.html"
           (target "https://hephaistox.fr/" "/fr/index.html")))
    (is (= "https://www.hephaistox.com/a" (target "https://www.hephaistox.com/" "/a")))
    (is (some? (target "https://landing.cleverapps.io/" "/a"))))
  (testing "anything else is refused — this is the parameter an attacker controls"
    (is (nil? (target "http://169.254.169.254/" "/latest/meta-data/")) "cloud metadata")
    (is (nil? (target "http://10.0.0.1/" "/admin")) "private network")
    (is (nil? (target "http://evil.example.com/" "/a")) "third-party host")
    (is (nil? (target "http://hephaistox.fr.evil.com/" "/a")) "suffix that only looks like ours")
    (is (nil? (target "file:///etc/passwd" "/a")) "non-http scheme")
    (is (nil? (target "not a url" "/a")))
    (is (nil? (target nil "/a")))))
