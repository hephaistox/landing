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
  "A controlled text field that survives IME / dead-key composition — French `^`+`u` → `û`,
  which a plain controlled input mangles into `^û` (the re-render mid-composition commits the
  dead key as a literal). Use it like a normal input, but pass `:value` + **`:on-text`** (called
  with the new string) instead of `:on-change`; every other prop (`:type`, `:placeholder`,
  `:style`, `:ref`, …) passes through. State updates are held while composing and flushed on
  `compositionend`. `:element` selects the tag (`:input` default, `:textarea` for the body)."
  [_props]
  (let [composing? (atom false)]
    (fn [{:keys [element value on-text]
          :as props}]
      [(or element :input)
       (merge (dissoc props :element :on-text)
              {:value (or value "")
               :on-composition-start (fn [_] (reset! composing? true))
               :on-composition-end
               (fn [ev] (reset! composing? false) (on-text (.. ev -target -value)))
               :on-change (fn [ev] (when-not @composing? (on-text (.. ev -target -value))))})])))
