(ns landing.endpoints.default-handler-test
  (:require
   [clojure.test                      :refer [deftest is testing use-fixtures]]
   [landing.endpoints.default-handler :as sut])
  (:import (org.apache.logging.log4j LogManager Level)
           (org.apache.logging.log4j.core LoggerContext)
           (org.apache.logging.log4j.core.impl Log4jContextFactory)))

(defn- without-log
  "Runs `f` with the handler's log off: the exceptions below are thrown on purpose.
  Every log4j context is set, since a REPL may hold several."
  [f]
  (let [loggers (for [ctx (.getLoggerContexts (.getSelector ^Log4jContextFactory
                                                            (LogManager/getFactory)))]
                  (.getLogger ^LoggerContext ctx "landing.endpoints.default-handler"))
        levels (mapv #(.getLevel ^org.apache.logging.log4j.core.Logger %) loggers)]
    (doseq [l loggers] (.setLevel ^org.apache.logging.log4j.core.Logger l Level/OFF))
    (try (f)
         (finally (doseq [[l level] (map vector loggers levels)]
                    (.setLevel ^org.apache.logging.log4j.core.Logger l level))))))

(use-fixtures :each without-log)

(defn- body->str
  "Coerce a prepared/served response body (byte array, string, or stream) to text."
  [body]
  (cond
    (string? body) body
    (bytes? body) (String. ^bytes body "UTF-8")
    (instance? java.io.InputStream body) (slurp body)
    :else (str body)))

(deftest exception-response-test
  (let [e (ex-info "boom" {:secret "do-not-leak"})]
    (testing "returns a 500 with an HTML content-type"
      (let [resp (sut/exception-response {} e)]
        (is (= 500 (:status resp)))
        (is (re-find #"text/html" (str (get-in resp [:headers "Content-Type"]))))))
    (testing "never echoes the exception message or ex-data to the client"
      (let [body (body->str (:body (sut/exception-response {} e)))]
        (is (not (re-find #"do-not-leak" body)))
        (is (not (re-find #"boom" body)))))
    (testing "serves the language-resolved 500 page: fr by default, en via the lang cookie"
      (let [fr (body->str (:body (sut/exception-response {} e)))
            en (body->str (:body (sut/exception-response {:headers {"cookie" "lang=en"}} e)))]
        (is (re-find #"Erreur inattendue" fr) "default (no cookie) serves the French /fr/500.html")
        (is (re-find #"Unexpected error" en) "lang=en cookie serves the English /en/500.html")))))
