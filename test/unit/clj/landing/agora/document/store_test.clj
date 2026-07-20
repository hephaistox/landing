(ns landing.agora.document.store-test
  "Pure change-model rules that live in the store (clj), not the shared domain: the publish
  lifecycle (`publish`) and its invariant (`pending?`)."
  (:require
   [clojure.test                 :refer [deftest is testing]]
   [landing.agora.document.store :as sut]))

(deftest publish
  (testing "publish closes the publication and publishes every draft it gathers, at once"
    (let [publication {:type :publication
                       :name "p"
                       :lang :en
                       :major 1
                       :minor 0
                       :status "open"
                       :draft true
                       :title "P"}
          members [{:type :ki
                    :name "a"
                    :lang :en
                    :major 1
                    :minor 3
                    :draft true}
                   {:type :ki
                    :name "b"
                    :lang :en
                    :major 1
                    :minor 0
                    :draft true}]
          [pub' & members'] (sut/publish publication members)]
      (is (= {:status "closed"
              :draft false}
             (select-keys pub' [:status :draft]))
          "the publication becomes closed + published")
      (is (= 2 (count members')))
      (is (every? (comp false? :draft) members') "every member draft becomes published")))
  (testing "a publication with no members still closes"
    (is (= {:status "closed"
            :draft false}
           (select-keys (first (sut/publish {:status "open"
                                             :draft true}
                                            []))
                        [:status :draft])))))

(deftest pending?
  (testing "the inputs with no published version, per the injected predicate"
    (let [published? #{{:type :ki
                        :name "ok"
                        :lang :en
                        :major 1}}
          inputs [{:type :ki
                   :name "ok"
                   :lang :en
                   :major 1}
                  {:type :ki
                   :name "draft-only"
                   :lang :en
                   :major 1}]]
      (is (= [{:type :ki
               :name "draft-only"
               :lang :en
               :major 1}]
             (sut/pending? inputs published?)))
      (is (= [] (sut/pending? [] published?))))))
