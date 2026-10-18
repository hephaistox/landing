;;; Directory Local Variables.  See (info "(emacs) Directory Variables").

;; A namespace refresh must stop the app before unloading and start it after reloading.
;; `tools.namespace/refresh` deletes the namespace holding the web server without ever running
;; mount's `:stop`, so the port stays taken.
;;
;; `user/stop` and `user/start` are defined in env/dev/user.clj.

((clojure-mode . ((cider-ns-refresh-before-fn . "user/stop")
                  (cider-ns-refresh-after-fn . "user/start"))))
