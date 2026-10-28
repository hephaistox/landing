(ns agora.value.name-test
  (:require
   [agora.value.name :as sut]
   [clojure.test     :refer [deftest is]]))

(deftest from-title-test
  (is (= "groupes-et-individus" (sut/from-title "Groupes et individus")))
  (is (= "l-electrique-coutera-plus-cher-a-l-usage"
         (sut/from-title "L'électrique coûtera plus cher à l'usage")))
  (is (= "enonce-non-universel" (sut/from-title "Énoncé non universel ")))
  (is (= "oeuvre" (sut/from-title "Œuvre")))
  (is (= "le-0-a-100-mesure" (sut/from-title "Le 0 à 100 mesuré")))
  (is (= "que-veut-dire-rapide" (sut/from-title "Que veut dire « rapide » ?"))))
