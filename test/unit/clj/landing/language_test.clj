(ns landing.language-test
  (:require
   [clojure.test     :refer [deftest is testing]]
   [landing.language :as sut]))

(defn- req [headers] {:headers headers})

(deftest cookie-lang-test
  (testing "Recognized cookie values are normalized to lowercase strings"
    (is (= "fr" (sut/cookie-lang (req {"cookie" "lang=fr"}))))
    (is (= "en" (sut/cookie-lang (req {"cookie" "lang=EN"})))))
  (testing "Legacy `:fr` / URL-encoded `%3Afr` forms are still accepted"
    (is (= "fr" (sut/cookie-lang (req {"cookie" "lang=:fr"}))))
    (is (= "fr" (sut/cookie-lang (req {"cookie" "lang=%3Afr"})))))
  (testing "Cookie header lookup is case-insensitive"
    (is (= "en" (sut/cookie-lang (req {"Cookie" "lang=en"})))))
  (testing "Other cookies don't get picked up as `lang`"
    (is (nil? (sut/cookie-lang (req {"cookie" "session=abc; theme=dark"}))))
    (is (nil? (sut/cookie-lang (req {"cookie" "lang=de"}))))
    (is (nil? (sut/cookie-lang (req {}))))))

(deftest accept-lang-test
  (testing "First supported language in Accept-Language wins"
    (is (= "en" (sut/accept-lang (req {"accept-language" "en-US,en;q=0.9,fr;q=0.8"}))))
    (is (= "fr" (sut/accept-lang (req {"accept-language" "fr-FR,fr;q=0.9"})))))
  (testing "Unsupported languages fall through to nil"
    (is (nil? (sut/accept-lang (req {"accept-language" "de-DE,de"}))))
    (is (nil? (sut/accept-lang (req {}))))))

(deftest pick-lang-precedence-test
  (testing "Cookie wins over Accept-Language"
    (is (= "fr" (sut/pick-lang (req {"cookie" "lang=fr"
                                     "accept-language" "en"})))))
  (testing "Accept-Language is used when no cookie"
    (is (= "en" (sut/pick-lang (req {"accept-language" "en-US,en;q=0.9"})))))
  (testing "Default falls back to `fr` when neither header is informative"
    (is (= "fr" (sut/pick-lang (req {}))))
    (is (= "fr" (sut/pick-lang (req {"cookie" "lang=de"
                                     "accept-language" "de-DE"}))))))
