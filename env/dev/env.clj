(ns env
  "Environment development"
  (:require
   [ring.middleware.reload :refer [wrap-reload]]))

(def cors-parameters
  [:access-control-allow-origin
   [#".*hephaistox.com$" #".*hephaistox.fr$" #".*hephaistox.pl$" #".*cleverapps.io$" #".*192.168.*"]
   :access-control-allow-methods
   [:get :put :post :delete]
   :access-control-allow-credentials
   "true"])

(def middlewares [wrap-reload])
