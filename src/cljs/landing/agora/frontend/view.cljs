(ns landing.agora.frontend.view
  "Generic document page — read card + node layout — for **any** document type, with no
  type knowledge. It renders from the document's own fields (permalink/version URLs from
  its `:type`, badge from its `:kind`) and takes the per-type authoring bits as a `cfg` map
  from the facade (see `edit`): only the read card's edit pencil and the in-place edit form
  consult `cfg`; everything else is derived. The per-type facades (`ki-page`/`article-page`)
  supply `cfg` and are what `core` calls. A nil `cfg` means read-only (the public page)."
  (:require
   [clojure.string                       :as str]
   [landing.agora.document-domain        :as domain]
   [landing.agora.frontend.cite          :as cite]
   [landing.agora.frontend.document-page :as    dv
                                         :refer [byline
                                                 card-style
                                                 doc-badge
                                                 gated
                                                 input-drop-fn
                                                 languages-control
                                                 node-frame
                                                 permalink
                                                 version-picker]]
   [landing.agora.frontend.edit          :as edit]
   [landing.agora.frontend.i18n          :as i18n]
   [landing.agora.frontend.source        :as source]
   [re-frame.core                        :as rf]))

(defn- prefix-pill
  "The kind-guided statement opening, shown as an inline rounded box — flagging it as
  hard-coded scaffold (not authored prose) while still flowing on the same line as the body."
  [prefix]
  [:span {:style {:display "inline-block"
                  :background "#f4efe4"
                  :border "1px solid #d9c9a8"
                  :border-radius "0.4em"
                  :padding "0 0.4em"
                  :margin-right "0.35em"
                  :color "#8a7a55"
                  :font-weight 500
                  :white-space "nowrap"
                  :vertical-align "baseline"}}
   (str/trim prefix)])

(defn- read-card
  "The central card in read mode: type badge, language switcher, version picker, title,
  byline, the prose (with living citations) and the cited source. When `cfg` is given (an
  editable page) a login-gated pencil opens the in-place editor; the public page passes
  nil (read-only)."
  [{doc-type :type
    :keys [title major minor published-at versions author author-id]
    :as doc}
   cfg]
  (let [lang @(rf/subscribe [::i18n/lang])
        labels (:labels cfg)]
    [:article {:style card-style}
     (when cfg
       (let [[on-click dim?] (gated #(rf/dispatch [::edit/edit-open doc]))]
         [:button {:on-click on-click
                   :title (i18n/t lang (get labels (if dim? :login-to-edit :edit)))
                   :style {:position "absolute"
                           :top "0.7em"
                           :right "0.8em"
                           :border "1px solid #ddd"
                           :background "#fff"
                           :color "#b9770e"
                           :border-radius "0.3em"
                           :width "2em"
                           :height "2em"
                           :cursor "pointer"
                           :font-size "0.95em"
                           :line-height 1
                           :opacity (if dim? 0.4 1)}}
          "✎"]))
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.5em"}}
      [doc-badge doc {:link? true}]
      [languages-control lang doc]
      [version-picker {:major major
                       :minor minor
                       :versions versions}
       (fn [vid] (i18n/doc-url lang doc-type vid))]]
     [:h1 {:style {:font-size "1.3em"
                   :margin "0.2em 0 0.1em"}}
      title]
     [byline author published-at author-id]
     [:div {:style {:font-size "1.05em"
                    :line-height "1.5"
                    :color "#222"}}
      ;; the kind-guided opening is part of the statement, but hard-coded (not authored) —
      ;; so it flows INLINE at the start of the first line, marked as a rounded box (pill)
      [cite/render-text (cite/node-text doc)
       (when-let [prefix (domain/statement-prefix-of doc lang)]
         [prefix-pill prefix])]]
     [source/source-view (:source doc)]]))

(defn- central
  "The central card: the edit form when this doc's editor is open (only possible on an
  editable page — `cfg` given), else the read card."
  [doc cfg]
  (let [e @(rf/subscribe [::edit/edit])]
    (if (and cfg (:open? e) (= (:id e) (:id doc))) [edit/edit-form doc cfg] [read-card doc cfg])))

(defn document-page
  "The editable document page: the shared node layout with an editable central card.
  Inputs carry a ✕ (for logged-in viewers) that removes the input link. Neighbour cards
  link to the concrete-version app URL, built from each neighbour's own `:type`. `cfg` is
  the facade's per-type config."
  [doc cfg]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [node-frame
     doc
     [central doc cfg]
     (fn [d] (i18n/doc-url lang (:type d) (:id d)))
     (input-drop-fn (:type doc) (:id doc))]))

(defn public-page
  "Read-only public document page: the same layout, a static central card, neighbour
  links pointing at public permalinks."
  [doc]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [node-frame doc [read-card doc nil] (fn [d] (permalink lang d))]))
