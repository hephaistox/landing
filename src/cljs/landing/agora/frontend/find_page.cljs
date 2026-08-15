(ns landing.agora.frontend.find-page
  "Two public browse views: **by author** (`/agora/<lang>/authors`, search people →
  their profile) and **by work** (`/agora/<lang>/sources`, search `kind=work` documents).
  Both self-fetch from the search endpoints (`/agora/api/people`, `/agora/api/documents/ki`)
  via local state, like the citation picker — no re-frame plumbing."
  (:require
   [clojure.string                    :as str]
   [landing.agora.frontend.i18n       :as i18n]
   [landing.agora.frontend.ui-commons :as ui]
   [re-frame.core                     :as rf]
   [reagent.core                      :as r]))

(defn- GET*
  [url on-ok]
  (-> (js/fetch url #js {:headers #js {"Accept" "application/json"}})
      (.then #(.json %))
      (.then #(on-ok (js->clj % :keywordize-keys true)))
      (.catch (fn [_] (on-ok [])))))

(def ^:private page-style
  {:max-width "44em"
   :margin "1.5em auto"
   :padding "0 1em"
   :font-family "system-ui, sans-serif"})

(def ^:private field
  {:width "100%"
   :box-sizing "border-box"
   :padding "0.5em 0.6em"
   :font-size "0.95em"
   :border "1px solid #ccc"
   :border-radius "0.3em"})

(def ^:private link-style
  {:color "#b9770e"
   :text-decoration "none"
   :font-weight 600})

;; --- browse by author -------------------------------------------------------
(defn authors-page
  "Search people by name; each result links to their public author profile."
  []
  (r/with-let
   [q (r/atom "") results (r/atom [])]
   (let [lang @(rf/subscribe [::i18n/lang])]
     [:div {:style page-style}
      [:h1 {:style {:font-size "1.4em"
                    :margin "0 0 0.2em"}}
       (i18n/t lang :authors/title)]
      [:p {:style {:color "#777"
                   :margin "0 0 1em"}}
       (i18n/t lang :authors/lead)]
      [ui/composed-field {:type "text"
                          :autoFocus true
                          :placeholder (i18n/t lang :authors/search-ph)
                          :value @q
                          :style field
                          :on-text (fn [v]
                                     (reset! q v)
                                     (if (str/blank? v)
                                       (reset! results [])
                                       (GET* (str "/agora/api/people?q=" (js/encodeURIComponent v))
                                             #(reset! results %))))}]
      (cond
        (and (not (str/blank? @q)) (empty? @results)) [:p {:style {:color "#aaa"
                                                                   :margin-top "1em"}}
                                                       (i18n/t lang :authors/none)]
        (seq @results) (into [:ul {:style {:margin "1em 0 0"
                                           :padding-left "1.2em"
                                           :line-height "1.9"}}]
                             (for [p @results]
                               ^{:key (:id p)}
                               [:li
                                [:a {:href (i18n/author lang (:id p))
                                     :style link-style}
                                 (:display-name p)]])))])))

;; --- browse works (bibliographic sources) -----------------------------------
(defn sources-page
  "Search bibliographic works (`kind=work` documents) by title / author / year; each result links to
  the work and to its cited author (→ profile), with a link when it has a URL."
  []
  (r/with-let
   [q (r/atom "") results (r/atom [])]
   (let [lang @(rf/subscribe [::i18n/lang])
         run! (fn [v]
                (reset! q v)
                (if (str/blank? v)
                  (reset! results [])
                  (GET* (str "/agora/api/documents/ki?lang=" (name lang)
                             "&q=" (js/encodeURIComponent v))
                        (fn [cards] (reset! results (filterv #(= "work" (:kind %)) cards))))))]
     [:div {:style page-style}
      [:h1 {:style {:font-size "1.4em"
                    :margin "0 0 0.2em"}}
       (i18n/t lang :sources/browse-title)]
      [:p {:style {:color "#777"
                   :margin "0 0 1em"}}
       (i18n/t lang :sources/browse-lead)]
      [ui/composed-field {:type "text"
                          :autoFocus true
                          :placeholder (i18n/t lang :sources/search-ph)
                          :style field
                          :value @q
                          :on-text run!}]
      (cond
        (and (not (str/blank? @q)) (empty? @results)) [:p {:style {:color "#aaa"
                                                                   :margin-top "1em"}}
                                                       (i18n/t lang :sources/browse-none)]
        (seq @results) (into [:ul {:style {:margin "1em 0 0"
                                           :padding-left "1.2em"
                                           :line-height "1.7"}}]
                             (for [w @results]
                               ^{:key (:id w)}
                               [:li
                                (when-not (str/blank? (:url w))
                                  [:a {:href (:url w)
                                       :target "_blank"
                                       :rel "noopener noreferrer"
                                       :title (:url w)
                                       :style {:text-decoration "none"
                                               :margin-right "0.4em"}}
                                   "🌐"])
                                [:a {:href (i18n/ki lang
                                                    {:name (:name w)
                                                     :major (:major w)})
                                     :style link-style}
                                 (:title w)]
                                (when (:year w) (str " (" (:year w) ")"))
                                " — "
                                (if (:attributed-author-id w)
                                  [:a {:href (i18n/author lang (:attributed-author-id w))
                                       :style link-style}
                                   (:attributed-author w)]
                                  [:span (:attributed-author w)])
                                (when-not (str/blank? (:editor w)) (str " · " (:editor w)))])))])))
