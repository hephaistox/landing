(ns landing.agora.frontend.ui-commons
  "Small shared UI helpers for the Agora SPA."
  (:require
   [reagent.core :as r]))

(defn on-escape
  "An invisible component: while it is mounted, pressing Escape calls `close!`. Drop
  it inside a modal/overlay so the modal closes on Esc — the document keydown
  listener is added on mount and removed on unmount, so only currently-open modals
  react. Works with modals held in either app-db or a local ratom.

  `close!` is re-read on every render, so passing a fresh closure each time is fine."
  [_close!]
  (let [close (atom nil)
        handler (fn [e] (when (= "Escape" (.-key e)) (when-let [c @close] (c))))]
    (r/create-class {:display-name "on-escape"
                     :component-did-mount (fn [_] (.addEventListener js/document "keydown" handler))
                     :component-will-unmount (fn [_]
                                               (.removeEventListener js/document "keydown" handler))
                     :reagent-render (fn [close!] (reset! close close!) nil)})))

(defn composed-field
  "A text field that survives IME / dead-key composition — French `^`+`e` → `ê`. Use it like a
  normal input, but pass `:value` + **`:on-text`** (called with the new string) instead of
  `:on-change`; every other prop (`:type`, `:placeholder`, `:style`, `:ref`, `:on-key-down`, …)
  passes through. `:element` selects the tag (`:input` default, `:textarea` for the body).

  It is **uncontrolled** at the React layer, on purpose. A *controlled* input enforces
  `value === props.value`: while composing a dead key we deliberately withhold the state update,
  so React would revert the DOM to the old value and wipe the in-progress `^`. Instead we seed
  `:default-value` and push `:value` into the DOM imperatively (in `did-update`) only when it
  actually changes **and** we are not composing — so React never fights the composition, and an
  external change (form reset, an inline citation spliced into the body) still reflects."
  [_props]
  (let [composing? (atom false)
        node (atom nil)]
    (r/create-class
     {:display-name "composed-field"
      :component-did-update (fn [this _]
                              (let [value (:value (second (r/argv this)))
                                    el @node]
                                (when (and el (not @composing?) (not= (.-value el) (or value "")))
                                  (set! (.-value el) (or value "")))))
      :reagent-render
      (fn [{:keys [element value on-text ref]
            :as props}]
        [(or element :input)
         (merge
          (dissoc props :element :on-text :value :ref)
          {:default-value (or value "")
           :ref (fn [el] (reset! node el) (when (fn? ref) (ref el)))
           :on-composition-start (fn [_] (reset! composing? true))
           :on-composition-end (fn [ev] (reset! composing? false) (on-text (.. ev -target -value)))
           :on-change (fn [ev] (when-not @composing? (on-text (.. ev -target -value))))})])})))
