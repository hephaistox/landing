(ns landing.agora.frontend.ui
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
