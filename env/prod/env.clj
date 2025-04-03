(ns env)

(def cors-parameters
  [:access-control-allow-origin
   [#".*hephaistox.com$" #".*hephaistox.fr$" #".*hephaistox.pl$" #".*cleverapps.io$"]
   :access-control-allow-methods
   [:get :put :post :delete]
   :access-control-allow-credentials
   "true"])

(def middlewares [])
