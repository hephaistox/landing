(ns landing.agora.translate
  "Best-effort machine translation for the authoring flow.

  Uses the free MyMemory API (no key, small anonymous quota) purely as a
  *suggestion* the author then edits and validates — never an automatic
  publication. Any failure (network, quota, timeout) returns nil so the caller
  falls back to the source text and the author translates by hand."
  (:require
   [clj-http.client :as http]
   [clojure.string  :as str]))

(def ^:private endpoint "https://api.mymemory.translated.net/get")

(defn suggest
  "A machine-translation suggestion for `text` from `source` to `target`
  (ISO 639-1 codes, e.g. \"en\" \"fr\"), or nil on any failure. Times out fast so
  the authoring UI never hangs on the external service."
  [text source target]
  (when (and (not (str/blank? text)) source target (not= source target))
    (try (let [resp (http/get endpoint
                              {:query-params {:q text
                                              :langpair (str source "|" target)}
                               :as :json
                               :throw-exceptions false
                               :socket-timeout 4000
                               :connection-timeout 4000})
               translated (get-in resp [:body :responseData :translatedText])]
           (when (and (= 200 (:status resp)) (not (str/blank? translated))) translated))
         (catch Exception _ nil))))
