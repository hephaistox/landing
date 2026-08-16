(ns landing.agora.frontend.find-page
  "Browse **by author** (`/agora/<lang>/authors`): search people → their profile. Self-fetches from the
  people search endpoint via local state, like the citation picker — no re-frame plumbing. (Works are
  ordinary `kind=work` KIs, browsed in the discover grid via the Type filter, so they need no page of
  their own.)"
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
