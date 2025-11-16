(ns landing.endpoints.exception
  "Exception handler triggers an exception to check what will be the displayed page, and header response"
  (:require
   [landing.endpoints.html :refer [html-middlewares]]))


(defn exception-handler [_] (throw (ex-info "Exception" {:for :test})))

(defn exception-route
  [prefix]
  [prefix {:get {:handler exception-handler
                 :swagger {:tags #{:html}}
                 :middleware html-middlewares
                 :summary "Trigger an exception to see what's displayed in that case"}
           :head {:handler exception-handler
                  :swagger {:tags #{:html}}
                  :middleware html-middlewares
                  :summary "Trigger an exception to see what's displayed in that case"}}])


