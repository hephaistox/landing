(ns landing.agora.frontend.article-page
  "Thin **article facade** over the generic document components (`view`/`edit`). It picks
  the article's feature values — its (rhetorical) kind selector, the article authoring labels, the create
  form's cancel target — and exposes the entry points `core` renders. All shared rendering
  lives in the generic layer; this ns only configures it for the article type, so the
  string `\"article\"` and its labels live here rather than in the type-agnostic engine."
  (:require
   [landing.agora.frontend.document-page :as dv]
   [landing.agora.frontend.edit          :as edit]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.view          :as view]))

(def ^:private cfg
  "The article's feature choices, handed to the generic view/edit components."
  {:type "article"
   :show-kind? true
   :cancel-route i18n/articles
   :labels {:new-title :article-form/new-title
            :title :article-form/title
            :title-ph :article-form/title-ph
            :text :article-form/body
            :text-ph :article-form/body-ph
            :login :article-form/login-to-create
            :creating :article-form/creating
            :create :article-form/create
            :cancel :article-form/cancel
            :save :article-form/save
            :saving :article-form/saving
            :save-failed :form/save-failed
            :edit :article-form/edit
            :login-to-edit :article-form/login-to-edit}})

(defn page "The editable article page." [doc] [view/document-page doc cfg])
(defn create-form "The standalone article authoring form." [] [edit/create-form cfg])
(defn discover
  "The article discover grid (`/agora/<lang>/articles`)."
  [articles]
  [dv/discover-grid {:type "article"
                     :heading-key :articles/heading
                     :items articles
                     :new-href-fn i18n/new-article
                     :new-label-key :nav/new-article}])
