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
  "Search people by name; each result links to their public author profile, and says whether they are
  a member or only a cited author. Opens on the people most recently attributed a document rather than
  on nothing — a blank search box in front of an unknown corpus asks the reader to guess a name they
  have no reason to know."
  []
  (r/with-let
   [q
    (r/atom "")
    results
    (r/atom [])
    default?
    (r/atom true)
    load-default!
    (fn [] (reset! default? true) (GET* "/agora/api/people" #(reset! results %)))
    _
    (load-default!)]
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
                                       (load-default!)
                                       (do (reset! default? false)
                                           (GET* (str "/agora/api/people?q="
                                                      (js/encodeURIComponent v))
                                                 #(reset! results %)))))}]
      (when (and @default? (seq @results))
        [:p {:style {:color "#888"
                     :font-size "0.85em"
                     :margin "1em 0 0"}}
         (i18n/t lang :authors/recent)])
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
                                 (:display-name p)]
                                ;; members and cited people share this list and both sign documents,
                                ;; so say which is which: one has an account here and can answer for
                                ;; what they wrote, the other is only ever credited by someone else
                                (when-not (:member? p)
                                  [:span {:title (i18n/t lang :authors/cited-hint)
                                          :style {:margin-left "0.5em"
                                                  :font-size "0.75em"
                                                  :color "#8a8177"
                                                  :border "1px solid #d8d2c8"
                                                  :border-radius "0.8em"
                                                  :padding "0.05em 0.5em"
                                                  :vertical-align "0.1em"}}
                                   (i18n/t lang :authors/cited)])])))])))
