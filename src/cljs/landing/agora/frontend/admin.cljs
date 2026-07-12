(ns landing.agora.frontend.admin
  "The admin/maintenance page (`/agora/<lang>/admin`, owner-only): the lineage table, the
  reference-consistency panel, and the prune/compact actions, with their `::admin-*` events."
  (:require
   [clojure.string                :as str]
   [landing.agora.document-domain :as domain]
   [landing.agora.frontend.auth   :as auth]
   [landing.agora.frontend.cite   :as cite]
   [landing.agora.frontend.i18n   :as i18n]
   [landing.language              :as language]
   [re-frame.core                 :as rf]
   [reagent.core                  :as r]))

(rf/reg-sub ::admin-tnrs (fn [db _] (:admin-tnrs db)))
(rf/reg-sub ::admin-issues (fn [db _] (:admin-issues db)))

(defn- consistency-panel
  "Admin: nodes with dangling references (a broken input / citation). Shown above the
  lineage table so data errors surface immediately."
  [lang issues]
  (let [bad? (seq issues)]
    [:div {:style {:margin "0 0 1.4em"
                   :border (str "1px solid " (if bad? "#e0a0a0" "#cfe0cf"))
                   :border-radius "0.5em"
                   :padding "0.8em 1em"
                   :background (if bad? "#fff4f4" "#f2f8f2")}}
     [:div {:style {:font-weight 700
                    :color (if bad? "#8a3a3a" "#3a7a3a")
                    :margin-bottom (if bad? "0.6em" "0")}}
      (str (if bad? "⚠ " "✓ ")
           (i18n/t lang :admin/issues-title)
           (when bad? (str " (" (count issues) ")")))]
     (if-not bad?
       [:div {:style {:color "#3a7a3a"
                      :font-size "0.9em"}}
        (i18n/t lang :admin/issues-none)]
       (into
        [:ul {:style {:margin 0
                      :padding-left "1.2em"
                      :font-size "0.9em"}}]
        (for [i issues]
          ^{:key (str (:type i) "/" (:name i) "/" (:lang i) "/" (:major i) "." (:minor i))}
          [:li {:style {:margin "0.25em 0"}}
           [cite/node-link
            i
            (str (:type i)
                 " · "
                 (or (:title i) (:name i))
                 " ("
                 (:lang i)
                 " v"
                 (:major i)
                 "."
                 (:minor i)
                 ")")]
           [:span {:style {:color "#666"}}
            (when (seq (:broken i))
              (str " → " (i18n/t lang :admin/issues-broken)
                   ": " (str/join ", " (map (fn [b] (str (:name b) "@" (:major b))) (:broken i)))))
            (when (seq (:self i))
              (str " → " (i18n/t lang :admin/issues-self)
                   ": " (str/join ", " (map (fn [b] (str (:name b) "@" (:major b))) (:self i)))))
            (when (seq (:dangling-successors i))
              (str " → " (i18n/t lang :admin/issues-dangling)
                   ": " (count (:dangling-successors i))))]])))]))

(def ^:private admin-btn
  {:font-size "0.8em"
   :padding "0.25em 0.7em"
   :border-radius "0.3em"
   :cursor "pointer"
   :border "1px solid #ccc"
   :background "#fff"})

(defn- admin-actions
  "The action cell for one TNR row: Compact / Drop, with a two-step inline confirm
  (no native dialog)."
  [lang confirm t]
  (let [c @confirm
        same? (and c
                   (= (:type c) (:type t))
                   (= (:name c) (:name t))
                   (= (:lang c) (:lang t))
                   (= (:major c) (:major t)))
        target {:type (:type t)
                :name (:name t)
                :lang (:lang t)
                :major (:major t)}]
    (if same?
      [:span {:style {:display "inline-flex"
                      :gap "0.3em"
                      :align-items "center"}}
       [:span {:style {:font-size "0.8em"
                       :color "#c92a2a"}}
        (i18n/t lang :admin/confirm)]
       [:button {:on-click (fn []
                             (rf/dispatch
                              [(if (= :drop (:action c)) :agora/admin-drop :agora/admin-compact)
                               (:type t)
                               (:name t)
                               (:lang t)
                               (:major t)])
                             (reset! confirm nil))
                 :style (assoc admin-btn :border "1px solid #c92a2a" :color "#c92a2a")}
        "✓"]
       [:button {:on-click #(reset! confirm nil)
                 :style admin-btn}
        "✕"]]
      [:span {:style {:display "inline-flex"
                      :gap "0.4em"}}
       [:button {:on-click #(reset! confirm (assoc target :action :compact))
                 :style admin-btn}
        (i18n/t lang :admin/compact)]
       [:button {:on-click #(reset! confirm (assoc target :action :drop))
                 :style (assoc admin-btn :border "1px solid #c92a2a" :color "#c92a2a")}
        (i18n/t lang :admin/drop)]])))

(def ^:private sitemap-limit 50000)
(def ^:private sitemap-warn 30000)

(defn- sitemap-gauge
  "How close the sitemap is to the 50,000-URL single-file limit. Every permalink is one
  `(type, name, lang, major)` lineage = one TNR row, so `n` (the TNR count) *is* the
  sitemap's URL count. Green well under, amber past ~30k, red at the cap — the point to
  split into a chunked sitemap index (issue #7)."
  [lang n]
  (let [pct (min 100 (/ (* 100.0 n) sitemap-limit))
        color (cond
                (>= n sitemap-limit) "#c92a2a"
                (>= n sitemap-warn) "#b9770e"
                :else "#2f9e44")]
    [:div {:style {:margin "0 0 1.2em"}}
     [:div {:style {:display "flex"
                    :justify-content "space-between"
                    :font-size "0.82em"
                    :color "#666"
                    :margin-bottom "0.25em"}}
      [:span (i18n/t lang :admin/sitemap-urls)]
      [:span {:style {:font-variant-numeric "tabular-nums"}}
       (str (.toLocaleString n) " / " (.toLocaleString sitemap-limit))]]
     [:div {:style {:height "0.55em"
                    :background "#eee"
                    :border-radius "0.3em"
                    :overflow "hidden"}}
      [:div {:style {:height "100%"
                     :width (str pct "%")
                     :background color
                     :transition "width 0.3s"}}]]
     (when (>= n sitemap-warn)
       [:div {:style {:font-size "0.75em"
                      :color color
                      :margin-top "0.25em"}}
        (i18n/t lang :admin/sitemap-near-limit)])]))

(defn- rebuild-button
  "Admin: recompute the derived caches (currently the successor index) **now**, instead of
  waiting for the daily scheduler — issue #70."
  [lang]
  (r/with-let [state (r/atom nil)] ; nil | :busy | :done
              [:div {:style {:margin "0 0 1.4em"}}
               [:button {:disabled (= @state :busy)
                         :on-click (fn []
                                     (reset! state :busy)
                                     (-> (js/fetch "/agora/api/admin/rebuild"
                                                   #js {:method "POST"
                                                        :headers #js {"Accept" "application/json"}})
                                         (.then (fn [_] (reset! state :done)))
                                         (.catch (fn [_] (reset! state nil)))))
                         :style {:padding "0.4em 0.9em"
                                 :border "1px solid #b9770e"
                                 :background "#fff"
                                 :color "#b9770e"
                                 :border-radius "0.3em"
                                 :cursor (if (= @state :busy) "default" "pointer")}}
                (i18n/t lang
                        (case @state
                          :busy :admin/rebuild-busy
                          :done :admin/rebuild-done
                          :admin/rebuild))]]))

(defn admin-page
  "Maintenance page: every KI lineage (TNR = name + major) with counts, and
  per-row actions to keep only the latest minor (per language) or drop it entirely.
  Logged-in only."
  []
  (r/with-let
   [confirm (r/atom nil) lang-filter (r/atom nil)]
   (let [lang @(rf/subscribe [::i18n/lang])
         user @(rf/subscribe [::auth/user])
         tnrs @(rf/subscribe [::admin-tnrs])
         issues @(rf/subscribe [::admin-issues])
         ;; the admin's local language: the chosen filter, or your interface language
         ;; until you pick one. `:all` shows every language (and a Language column).
         flt (if (nil? @lang-filter) lang @lang-filter)
         all? (= :all flt)
         shown (if all? tnrs (filter #(= flt (:lang %)) tnrs))
         th {:text-align "left"
             :padding "0.4em 0.6em"
             :border-bottom "2px solid #e2ddd2"
             :color "#888"
             :font-size "0.8em"}
         td {:padding "0.4em 0.6em"
             :border-bottom "1px solid #f0eee8"}]
     [:div {:style {:max-width "56em"
                    :margin "1.5em auto"
                    :padding "0 0.8em"
                    :font-family "system-ui, sans-serif"}}
      [:h1 {:style {:font-size "1.3em"
                    :margin "0 0 0.8em"}}
       (i18n/t lang :admin/title)]
      (when (:admin user) [consistency-panel lang issues])
      (when (:admin user) [rebuild-button lang])
      (when (:admin user) [sitemap-gauge lang (count tnrs)])
      ;; Language filter — defaults to your selected language (so the table matches it);
      ;; "All languages" shows every version and adds a Language column.
      (when (:admin user)
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "0.5em"
                       :margin "0 0 1em"}}
         [:span {:style {:font-size "0.85em"
                         :color "#888"}}
          (i18n/t lang :admin/language)]
         [:select {:value (if all? "__all__" flt)
                   :on-change #(let [v (.. % -target -value)]
                                 (reset! lang-filter (if (= v "__all__") :all v)))
                   :style {:font-size "0.9em"
                           :padding "0.25em 0.4em"
                           :border "1px solid #ccc"
                           :border-radius "0.3em"}}
          [:option {:value "__all__"}
           (i18n/t lang :admin/all-langs)]
          (for [l language/languages]
            ^{:key l}
            [:option {:value l}
             (get language/language-name l l)])]])
      (cond
        (not user) [:div {:style {:color "#888"
                                  :font-style "italic"}}
                    (i18n/t lang :admin/login-required)]
        (not (:admin user)) [:div {:style {:color "#888"
                                           :font-style "italic"}}
                             (i18n/t lang :admin/not-authorized)]
        (empty? shown) [:div {:style {:color "#aaa"
                                      :font-style "italic"}}
                        (i18n/t lang :admin/empty)]
        :else
        [:table {:style {:width "100%"
                         :border-collapse "collapse"}}
         [:thead
          [:tr
           [:th {:style th}
            (i18n/t lang :admin/type)]
           [:th {:style th}
            (i18n/t lang :admin/kind)]
           [:th {:style th}
            (i18n/t lang :form/name)]
           (when all?
             [:th {:style th}
              (i18n/t lang :admin/language)])
           [:th {:style th}
            (i18n/t lang :admin/major)]
           [:th {:style th}
            (i18n/t lang :admin/versions)]
           [:th {:style th}
            (i18n/t lang :admin/latest)]
           [:th {:style th}]]]
         (into
          [:tbody]
          (for [t shown]
            ^{:key (str (:type t) "/" (:name t) "/" (:lang t) "/" (:major t))}
            [:tr
             [:td {:style td}
              [:span {:style {:font-size "0.7em"
                              :font-weight 700
                              :letter-spacing "0.04em"
                              :text-transform "uppercase"
                              :color "#fff"
                              :background (if (= "article" (:type t)) "#8a8175" "#2c5aa0")
                              :padding "0.15em 0.5em"
                              :border-radius "0.25em"}}
               (:type t)]]
             [:td {:style td}
              ;; the epistemic kind badge (kind colour) — articles have none
              (when-let [k (:kind t)]
                [:span {:style {:font-size "0.7em"
                                :font-weight 700
                                :letter-spacing "0.04em"
                                :text-transform "uppercase"
                                :color "#fff"
                                :background (get domain/kind-color k "#666")
                                :padding "0.15em 0.5em"
                                :border-radius "0.25em"}}
                 (i18n/t lang (keyword "kind" k))])]
             [:td {:style (assoc td :font-weight 600)}
              ;; show the human title (the identity slug is internal); fall back
              ;; to the slug only if a row somehow has no title
              [cite/node-link t (or (:title t) (:name t))]]
             (when all?
               [:td {:style td}
                (str/upper-case (:lang t))])
             [:td {:style td}
              (:major t)]
             [:td {:style td}
              (:versions t)]
             [:td {:style td}
              (str "v" (:major t) "." (:latest t))]
             [:td {:style (assoc td :text-align "right")}
              [admin-actions lang confirm t]]]))])])))
