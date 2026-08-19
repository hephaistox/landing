(ns landing.agora.publication-test
  (:require
   [clojure.test              :refer [deftest is testing]]
   [landing.agora.publication :as sut]))

(def ^:private mine
  {:owner-id "u1"
   :status :open})

(def ^:private mine-closed
  {:owner-id "u1"
   :status :closed})

(def ^:private theirs
  {:owner-id "u2"
   :status :open})

(def ^:private theirs-closed
  {:owner-id "u2"
   :status :closed})

(defn- in-scope? [viewer scope p] (#'sut/in-scope? viewer scope p))

(deftest visible-scope-hides-another-owners-staging
  (testing "another owner's open publication is their private staging — never visible"
    (is (not (in-scope? "u1" :visible theirs)))
    (is (not (in-scope? nil :visible theirs)))
    (testing "including to an anonymous viewer, who owns nothing"
      (is (not (in-scope? nil :visible mine)))))
  (testing "the viewer's own shows either status"
    (is (in-scope? "u1" :visible mine))
    (is (in-scope? "u1" :visible mine-closed)))
  (testing "a closed publication has published everything it gathers, so anyone may list it"
    (is (in-scope? nil :visible mine-closed))
    (is (in-scope? nil :visible theirs-closed))
    (is (in-scope? "u1" :visible theirs-closed))))

(deftest mine-scope-holds-the-viewers-own-either-status
  (is (in-scope? "u1" :mine mine))
  (is (in-scope? "u1" :mine mine-closed))
  (is (not (in-scope? "u1" :mine theirs)))
  (testing "an anonymous viewer owns nothing" (is (not (in-scope? nil :mine mine)))))

(deftest all-scope-holds-every-publication
  (is (in-scope? "u1" :all mine))
  (is (in-scope? "u1" :all theirs))
  (is (in-scope? "u1" :all theirs-closed)))
