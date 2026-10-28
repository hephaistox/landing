(ns agora.action.publish-test
  (:require
   [agora.action.cancel-publication :as cancel]
   [agora.action.publish            :as sut]
   [clojure.test                    :refer [deftest is]]))

(def hephaistox {:id #uuid "61a3d5a4-a94a-4e02-9e1e-0db6f5c03503"})

(def intruder {:id #uuid "8e2ad0d5-c936-49ca-ada3-9e7dd84d13a9"})

(def repository
  "A corrupted repository: the draft of an intruder, absent from the contributors."
  {:persons [(assoc hephaistox :name "Hephaistox") (assoc intruder :name "Hephaistox2")]
   :users [(assoc hephaistox :provider :google :provider-id "google-h")
           (assoc intruder :provider :google :provider-id "google-h2")]
   :publications [{:id #uuid "86c19eb7-df81-4705-942a-7570667fcd25"
                   :type :publication
                   :name "la-methode-scientifique"
                   :lang :fr
                   :major 1
                   :status :open
                   :owner hephaistox
                   :contributors []
                   :documents [{:id #uuid "a526a25d-c823-4295-a8b4-0e6514d3768a"
                                :type :definition
                                :name "enonce-universel"
                                :lang :fr
                                :major 1
                                :owner intruder
                                :content {:title "Énoncé universel"
                                          :text "un énoncé sur toute une classe."
                                          :inputs []
                                          :published-at #inst "2026-10-26T09:00:00Z"}}]
                   :content {:title "La méthode scientifique"
                             :text "Pose le vocabulaire de la méthode de Popper."
                             :published-at #inst "2026-10-25T09:00:00Z"}}]
   :objections []})

(deftest foreign-draft-test
  (is (= {:reason :foreign-draft
          :ids [#uuid "a526a25d-c823-4295-a8b4-0e6514d3768a"]}
         (try (sut/publish repository
                           hephaistox
                           {:publication {:id #uuid "86c19eb7-df81-4705-942a-7570667fcd25"}
                            :published-at #inst "2026-10-27T09:00:00Z"})
              nil
              (catch Exception e (ex-data e))))))

(deftest cancel-foreign-draft-test
  (is (= {:reason :foreign-draft
          :ids [#uuid "a526a25d-c823-4295-a8b4-0e6514d3768a"]}
         (try (cancel/cancel-publication repository
                                         hephaistox
                                         {:publication
                                          {:id #uuid "86c19eb7-df81-4705-942a-7570667fcd25"}})
              nil
              (catch Exception e (ex-data e))))))
