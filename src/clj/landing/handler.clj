(ns landing.handler
  "Handler turns an http http-request into a response"
  (:require
   [auto-web.middleware       :refer [wrap-add-language wrap-exception-handling]]
   [hiccup2.core              :refer [html]]
   [landing.article           :refer [article-map]]
   [landing.language          :refer [default-language]]
   [landing.pages.article-be  :refer [article-page]]
   [landing.pages.error-be    :refer [exception-page page-not-found-page]]
   [landing.pages.home-be     :refer [home-page]]
   [muuntaja.core             :as m]
   [reitit.ring               :as rring]
   [ring.middleware.cookies   :as ring-cookies]
   [ring.middleware.cors      :as ring-cors]
   [ring.middleware.gzip      :as ring-gzip]
   [ring.middleware.reload    :refer [wrap-reload]]
   [ring.middleware.x-headers :refer [wrap-frame-options]]
   [ring.util.response        :as rr]))

(defn render [el] (str (html el)))

(def ^:private landing-middlewares
  "The middlewares for this application"
  [ring-cookies/wrap-cookies
   [wrap-add-language default-language]
   [wrap-frame-options :deny]
   [ring-cors/wrap-cors
    :access-control-allow-origin
    [#".*hephaistox.com$"
     #".*hephaistox.fr$"
     #".*hephaistox.pl$"
     #".*cleverapps.io$"
     #".*192.168.*"]
    :access-control-allow-methods
    [:get :put :post :delete]
    :access-control-allow-credentials
    "true"]
   ring-gzip/wrap-gzip
   wrap-reload])

(def router
  (rring/router
   [["/ping"
     (constantly (-> {:status 200
                      :headers {}
                      :body "pong"}
                     (rr/content-type "text/plain")))]
    [""
     {:get (fn [http-request]
             {:status 500
              :headers {}
              :body (render (home-page http-request))})
      :middleware landing-middlewares}]
    ["/exception" {:get (fn [_] (throw (ex-info "Exception has been raised" {:for :test})))}]
    ["/"
     {:get (fn [http-request]
             {:status 200
              :headers {}
              :body (render (home-page http-request))})
      :middleware landing-middlewares}]
    ["/articles/:article-id"
     {:get (fn [http-request]
             (let [{:keys [handler title description]}
                   (some-> (get-in http-request [:reitit.core/match :path-params :article-id])
                           article-map)]
               (if article-map
                 {:status 200
                  :headers {}
                  :body (render (article-page http-request title description handler))}
                 {:status 200
                  :headers {}
                  :body (render (home-page http-request))})))
      :middleware landing-middlewares}]]
   {:data {:muuntaja m/instance
           :middleware [[wrap-exception-handling
                         (fn [http-request e]
                           {:status 500
                            :headers {}
                            :body (render (exception-page http-request e))})]]}}))

(def handler
  (rring/ring-handler router
                      (rring/routes (rring/create-resource-handler {:path "/"})
                                    (rring/create-default-handler
                                     {:not-found (fn [http-request]
                                                   {:status 404
                                                    :headers {}
                                                    :body (render (page-not-found-page
                                                                   http-request))})}))
                      {}))
