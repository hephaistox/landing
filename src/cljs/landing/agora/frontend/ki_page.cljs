(ns landing.agora.frontend.ki-page
  "Thin **KI facade** over the generic document components (`view`/`edit`). It picks KI's
  feature values — the epistemic-kind selector is shown, the authoring labels, the create
  form's cancel target — and exposes ready entry points that `core` renders. All shared
  rendering lives in the generic layer; this ns only configures it for the KI type, so the
  string `\"ki\"` and KI's labels live here rather than in the type-agnostic engine."
  (:require
   [landing.agora.frontend.document-page :as dv]
   [landing.agora.frontend.edit          :as edit]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.view          :as view]))

(def ^:private cfg
  "KI's feature choices, handed to the generic view/edit components."
  {:type "ki"
   :show-kind? true
   :cancel-route i18n/discover
   :labels {:new-title :form/new-title
            :title :form/title
            :title-ph :form/title-ph
            :text :form/statement
            :text-ph :form/statement-ph
            :login :form/login-to-create
            :creating :form/creating
            :create :form/create
            :cancel :form/cancel
            :save :form/save
            :saving :form/saving
            :save-failed :form/save-failed
            :edit :ki/edit
            :login-to-edit :ki/login-to-edit}})

(defn page "The editable KI page." [doc] [view/document-page doc cfg])
(defn public-page "The read-only public KI page." [doc] [view/public-page doc])
(defn create-form "The standalone KI creation form." [] [edit/create-form cfg])
(defn discover
  "The KI discover grid (`/agora/<lang>/discover`)."
  [kis]
  [dv/discover-grid {:type "ki"
                     :heading-key :discover/heading
                     :items kis
                     :new-href-fn i18n/new-ki
                     :new-label-key :nav/new-ki}])
