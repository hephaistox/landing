(ns landing.agora.frontend.article-view
  "Presentational component for a single article (#31): title, publication
  timestamp and body. The body is plain text; blank lines separate paragraphs.

  The body may embed **KI citations** — a `[[ki:<name>@<major>]]` token (optionally
  `[[ki:<name>@<major>|custom text]]`). Each renders as a special inline link (an
  `<a>` that knows it points at a Knowledge Item): its text defaults to the KI's
  title, it is visually marked as a KI, and hovering shows a card of the KI's
  metadata (kind, version, language, date, author). Citations are *living* — a
  `name + major` reference resolving to the latest minor via the by-major endpoint,
  so an article stays current as the KI is refined."
  (:require
   [clojure.string                    :as str]
   [landing.agora.frontend.auth       :as auth]
   [landing.agora.frontend.cite       :as cite]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.document-view    :as document-view]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]))

;; ---------------------------------------------------------------------------
;; Article
;; ---------------------------------------------------------------------------

(declare article-edit-form)

(defn- article-central
  "The article's central card — the SAME structure and controls as a KI card: a
  metadata row (a neutral 'Article' type chip, the interactive language switcher, the
  version picker), title, byline (author · date), the prose body (with living
  citations), and a pencil edit for a logged-in visitor (editing forks a new minor)."
  [{:keys [title major minor author author-id published-at versions]
    :as art}]
  (let [ui-lang @(rf/subscribe [::i18n/lang])
        user @(rf/subscribe [::auth/user])]
    [:article {:style document-view/card-style}
     (when user
       [:button {:on-click #(rf/dispatch [::edit-open art])
                 :title (i18n/t ui-lang :article-form/edit)
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
                         :line-height 1}}
        "✎"])
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "0.75em"
                    :margin-bottom "0.5em"}}
      [:span {:style {:font-size "0.62em"
                      :font-weight 700
                      :letter-spacing "0.05em"
                      :text-transform "uppercase"
                      :color "#fff"
                      :background "#8a8175"
                      :padding "0.2em 0.6em"
                      :border-radius "0.25em"}}
       (i18n/t ui-lang :type/article)]
      [document-view/languages-control ui-lang art]
      [document-view/version-picker {:major major
                               :minor minor
                               :versions versions}
       (fn [id] (i18n/article ui-lang id))]]
     [:h1 {:style {:font-size "1.3em"
                   :margin "0.2em 0 0.1em"}}
      title]
     [document-view/byline author published-at author-id]
     [:div {:style {:font-size "1.05em"
                    :line-height "1.5"
                    :color "#222"}}
      [cite/render-text (cite/node-text art)]]]))

(defn article-card
  "The article page — the SAME shared node layout as a KI (`document-view/node-frame`): the
  KIs cited in the body shown as input cards above, the article card in the middle,
  successors below. Editing swaps the central card for the edit form."
  [{:keys [id]
    :as art}]
  (let [{edit-id :id} @(rf/subscribe [::edit])
        lang @(rf/subscribe [::i18n/lang])]
    [document-view/node-frame
     art
     (if (= edit-id id) [article-edit-form] [article-central art])
     (fn [doc] (i18n/ki lang doc))
     (document-view/input-drop-fn "article" id)]))

;; ---------------------------------------------------------------------------
;; Article discover
;; ---------------------------------------------------------------------------

(defn articles-discover
  "Public article list — a responsive grid of preview cards linking to permalinks,
  ending with a `+` card to author a new article."
  [articles]
  (let [lang @(rf/subscribe [::i18n/lang])]
    [:div {:style {:max-width "72em"
                   :margin "1.5em auto"
                   :padding "0 0.8em"
                   :font-family "system-ui, sans-serif"}}
     [:p {:style {:color "#666"
                  :margin "0 0 1em"}}
      (i18n/t lang :articles/tagline)]
     (into [:div {:style {:display "grid"
                          :grid-template-columns "repeat(auto-fill, minmax(min(17em, 100%), 1fr))"
                          :gap "0.9em"}}]
           (conj (mapv (fn [a] ^{:key (:id a)} [document-view/discover-card lang a]) articles)
                 ^{:key "__add__"}
                 [document-view/add-card (i18n/new-article lang) (i18n/t lang :nav/new-article)]))
     [document-view/fab (i18n/new-article lang) (i18n/t lang :nav/new-article)]]))

;; ---------------------------------------------------------------------------
;; Article authoring (writing page)
;; ---------------------------------------------------------------------------

(rf/reg-sub ::form (fn [db _] (::form db)))
(rf/reg-event-db ::form-set (fn [db [_ k v]] (assoc-in db [::form k] v)))

(rf/reg-event-db ::op-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] article op failed:" (clj->js resp))
                   (update db ::form dissoc :submitting?)))

(rf/reg-event-fx ::submit
                 (fn [{:keys [db]} _]
                   (let [{:keys [title body]} (::form db)]
                     {:db (assoc-in db [::form :submitting?] true)
                      :fetch {:method :post
                              :url "/agora/api/article"
                              :headers {"Content-Type" "application/json"
                                        "Accept" "application/json"}
                              :body (js/JSON.stringify (clj->js {:title title
                                                                 :lang (i18n/current db)
                                                                 :text body}))
                              :response-content-types {#"application/json" :json}
                              :on-success [::created]
                              :on-failure [::op-failed]}})))

(rf/reg-event-fx ::created
                 (fn [{:keys [db]} [_ resp]]
                   {:db (dissoc db ::form)
                    :agora/navigate (i18n/article-permalink (i18n/current db) (:body resp))}))

;; ---- Edit an existing article → a new minor version ----

(rf/reg-sub ::edit (fn [db _] (::edit db)))
(rf/reg-event-db ::edit-open
                 (fn [db [_ art]]
                   (assoc db
                          ::edit
                          {:id (:id art)
                           :title (:title art)
                           :body (cite/node-text art)
                           :orig-cites (cite/citations (cite/node-text art))
                           :saving? false})))
(rf/reg-event-db ::edit-close (fn [db _] (dissoc db ::edit)))
(rf/reg-event-db ::edit-set (fn [db [_ k v]] (assoc-in db [::edit k] v)))

(rf/reg-event-db ::edit-failed
                 (fn [db [_ resp]]
                   (js/console.error "[agora] article edit failed:" (clj->js resp))
                   (update db ::edit dissoc :saving?)))

(rf/reg-event-fx ::edit-save
                 (fn [{:keys [db]} _]
                   (let [{:keys [id title body orig-cites]} (::edit db)
                         removed? (seq (remove (cite/citations body) orig-cites))]
                     (if (and removed?
                              (not (js/confirm (i18n/t (i18n/current db) :cite/removed-warning))))
                       {}
                       {:db (assoc-in db [::edit :saving?] true)
                        :fetch {:method :post
                                :url (str "/agora/api/article/" id "/edit")
                                :headers {"Content-Type" "application/json"
                                          "Accept" "application/json"}
                                :body (js/JSON.stringify (clj->js {:title title
                                                                   :text body}))
                                :response-content-types {#"application/json" :json}
                                :on-success [::edit-saved]
                                :on-failure [::edit-failed]}}))))

(rf/reg-event-fx ::edit-saved
                 (fn [{:keys [db]} [_ resp]]
                   (let [art (:body resp)]
                     (if (:id art)
                       {:db (dissoc db ::edit)
                        :dispatch [:agora/article-edited art]}
                       {:db (assoc-in db [::edit :saving?] false)}))))

(defn article-new-form
  "Standalone article authoring page: title + body, with the inline citation editor
  (search or create a KI → splices a `[[ki:…]]` citation, which becomes an input).
  On publish it POSTs /agora/api/article and navigates to the new permalink."
  []
  (let [{:keys [title body submitting?]} @(rf/subscribe [::form])
        user @(rf/subscribe [::auth/user])
        lang @(rf/subscribe [::i18n/lang])
        label {:font-size "0.8em"
               :color "#555"
               :margin-bottom "0.3em"}
        field {:width "100%"
               :box-sizing "border-box"
               :padding "0.5em"
               :font-family "inherit"
               :font-size "0.95em"
               :border "1px solid #ccc"
               :border-radius "0.3em"
               :margin-bottom "0.8em"}
        ;; the identity slug is derived from the title server-side, so it isn't asked
        blank? (or (str/blank? title) (str/blank? body))]
    [:div {:style {:width "44em"
                   :max-width "100%"
                   :box-sizing "border-box"
                   :margin "1.5em auto"
                   :padding "1.5em"
                   :background "#fff"
                   :border "1px solid #e2ddd2"
                   :border-radius "0.6em"
                   :font-family "system-ui, sans-serif"}}
     [ui/on-escape #(rf/dispatch [:agora/goto (i18n/articles lang)])]
     [:h1 {:style {:font-size "1.3em"
                   :margin "0 0 0.8em"}}
      (i18n/t lang :article-form/new-title)]
     [:div {:style label}
      (i18n/t lang :article-form/title)]
     [:input {:type "text"
              :placeholder (i18n/t lang :article-form/title-ph)
              :value (or title "")
              :on-change #(rf/dispatch [::form-set :title (.. % -target -value)])
              :style field}]
     [:div {:style label}
      (i18n/t lang :article-form/body)]
     [cite/citation-editor
      body
      #(rf/dispatch [::form-set :body %])
      (i18n/t lang :article-form/body-ph)]
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "1em"}}
      [:button {:on-click (cond
                            (not user) #(rf/dispatch [::auth/open :login])
                            (or blank? submitting?) nil
                            :else #(rf/dispatch [::submit]))
                :disabled (boolean (and user (or blank? submitting?)))
                :style {:padding "0.4em 0.9em"
                        :border "none"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.3em"
                        :opacity (if user 1 0.7)
                        :cursor (if (and user (or blank? submitting?)) "default" "pointer")}}
       (cond
         (not user) (i18n/t lang :article-form/login-to-create)
         submitting? (i18n/t lang :article-form/creating)
         :else (i18n/t lang :article-form/create))]
      [:a {:href (i18n/articles lang)
           :style {:padding "0.4em 0.9em"
                   :border "1px solid #ccc"
                   :background "#fff"
                   :border-radius "0.3em"
                   :text-decoration "none"
                   :color "#444"}}
       (i18n/t lang :article-form/cancel)]]]))

(defn article-edit-form
  "In-place editor on the article card (any logged-in visitor): title + body via the
  inline citation editor. Saving POSTs /article/:id/edit → a new minor (a fork), then
  navigates to the article's permalink."
  []
  (let [{:keys [title body saving?]} @(rf/subscribe [::edit])
        lang @(rf/subscribe [::i18n/lang])
        label {:font-size "0.8em"
               :color "#555"
               :margin-bottom "0.3em"}
        field {:width "100%"
               :box-sizing "border-box"
               :padding "0.5em"
               :font-family "inherit"
               :font-size "0.95em"
               :border "1px solid #ccc"
               :border-radius "0.3em"
               :margin-bottom "0.8em"}
        blank? (or (str/blank? title) (str/blank? body))]
    [:div {:style {:width "44em"
                   :max-width "100%"
                   :box-sizing "border-box"
                   :margin "1em auto"
                   :padding "1.5em"
                   :background "#fff"
                   :border "1px solid #e2ddd2"
                   :border-radius "0.6em"
                   :font-family "system-ui, sans-serif"}}
     [ui/on-escape #(rf/dispatch [::edit-close])]
     [:h1 {:style {:font-size "1.3em"
                   :margin "0 0 0.8em"}}
      (i18n/t lang :article-form/edit-title)]
     [:div {:style label}
      (i18n/t lang :article-form/title)]
     [:input {:type "text"
              :value (or title "")
              :on-change #(rf/dispatch [::edit-set :title (.. % -target -value)])
              :style field}]
     [:div {:style label}
      (i18n/t lang :article-form/body)]
     [cite/citation-editor
      body
      #(rf/dispatch [::edit-set :body %])
      (i18n/t lang :article-form/body-ph)]
     [:div {:style {:display "flex"
                    :gap "0.5em"
                    :margin-top "1em"}}
      [:button {:on-click (when-not (or blank? saving?) #(rf/dispatch [::edit-save]))
                :disabled (boolean (or blank? saving?))
                :style {:padding "0.4em 0.9em"
                        :border "none"
                        :background "#b9770e"
                        :color "#fff"
                        :border-radius "0.3em"
                        :cursor (if (or blank? saving?) "default" "pointer")}}
       (if saving? (i18n/t lang :article-form/saving) (i18n/t lang :article-form/save))]
      [:button {:on-click #(rf/dispatch [::edit-close])
                :style {:padding "0.4em 0.9em"
                        :border "1px solid #ccc"
                        :background "#fff"
                        :border-radius "0.3em"
                        :cursor "pointer"
                        :color "#444"}}
       (i18n/t lang :article-form/cancel)]]]))
