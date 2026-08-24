(ns landing.agora.util-test
  (:require
   [clojure.test       :refer [deftest is]]
   [landing.agora.util :as sut])
  (:import (java.time LocalDateTime)))

(deftest ->utc-iso (is (string? (sut/->utc-iso (LocalDateTime/now)))))
