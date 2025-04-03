(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [auto-web.middleware       :refer [wrap-add-language wrap-exception-handling]]
   [env                       :refer [cors-parameters middlewares]]
   [landing.language          :refer [default-language]]
   [landing.pages.admin-be    :refer [admin-response]]
   [landing.pages.article-be  :refer [article-response]]
   [landing.pages.error-be    :refer [exception-response page-not-found-response]]
   [landing.pages.home-be     :refer [home-response]]
   [muuntaja.core             :as m]
   [reitit.ring               :as rring]
   [ring.middleware.cookies   :as ring-cookies]
   [ring.middleware.cors      :as ring-cors]
   [ring.middleware.gzip      :as ring-gzip]
   [ring.middleware.x-headers :refer [wrap-frame-options]]
   [ring.util.response        :as rr]))

(def ^:private landing-middlewares "The middlewares for this application" middlewares)

(def router
  (rring/router [["/ping"
                  (constantly (-> {:status 200
                                   :headers {}
                                   :body "pong"}
                                  (rr/content-type "text/plain")))]
                 ["" home-response]
                 ["/exception" (fn [_] (throw (ex-info "Exception" {:for :test})))]
                 ["/" home-response]
                 ["/articles/:article-id" article-response]
                 ["/all-kind-of-checks" admin-response]]
                {:data {:muuntaja m/instance
                        :middleware (concat landing-middlewares
                                            [[wrap-frame-options :deny]
                                             ring-gzip/wrap-gzip
                                             [wrap-exception-handling exception-response]])}}))

(defn handler
  [request]
  ((-> (rring/ring-handler router
                           (rring/routes (rring/create-resource-handler {:path "/"})
                                         (-> (rring/create-default-handler
                                              {:not-found page-not-found-response
                                               :not-acceptable page-not-found-response
                                               :method-not-allowed page-not-found-response})
                                             (#(apply ring-cors/wrap-cors % cors-parameters))))
                           {})
       (wrap-add-language default-language)
       ring-cookies/wrap-cookies)
   request))
