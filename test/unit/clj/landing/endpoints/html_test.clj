(ns landing.endpoints.html-test
  (:require
   [clojure.test           :refer [deftest is]]
   [landing.endpoints.html :as sut]
   [landing.language       :refer [default-language]]
   [reitit.ring            :as rring]))

;; ********************************************************************************
;; A handler stub

(defn- test-handler
  [_http-request]
  {:status 200
   :headers {"content-type" "text-html"
             "Access-Control-Allow-Origin" "*"}
   :body "<html><body>[:p test]</body></html>"})

(deftest test-handler-test
  (is (= {:status 200
          :headers {"content-type" "text-html"
                    "Access-Control-Allow-Origin" "*"}
          :body "<html><body>[:p test]</body></html>"}
         (test-handler {}))))

;; ********************************************************************************
;; Ring handler

(def router
  (rring/ring-handler (rring/router ["/" test-handler] {:data {:middleware sut/html-middlewares}})))

(deftest router-test
  (is
   (= [(str "lang=%3A" (name default-language) "; Path=/")]
      (-> (router {:request-method :get
                   :uri "/"})
          :headers
          (get "Set-Cookie")))
   "Is the default strategy language applied by add-language and set to cookie with wrap-cookies?")
  (is (= "DENY"
         (-> (router {:request-method :get
                      :uri "/"})
             :headers
             (get "X-Frame-Options")))
      "Is deny options added to the header? wrapper wrap-frame-options"))


