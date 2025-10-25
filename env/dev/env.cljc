(ns env
  "Environment development"
  #_(:require
     [ring.middleware.reload :refer [wrap-reload]]))

(def env :dev)

(def cors-parameters
  [:access-control-allow-origin
   [#".*hephaistox.com$" #".*hephaistox.fr$" #".*hephaistox.pl$" #".*cleverapps.io$" #".*192.168.*"]
   :access-control-allow-methods
   [:get :put :post :delete]
   :access-control-allow-credentials
   "true"])

(def env-dep-middlewares
  "wrap-reload is removed from the list as my current workflow is to cider-ns-refresh."
  [#_[wrap-reload {:dirs "src"}]])


