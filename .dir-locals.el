;;; Directory Local Variables.  See (info "(emacs) Directory Variables").

;; A namespace refresh must stop the app before unloading and start it after reloading.
;; `tools.namespace/refresh` deletes the namespace holding the Agora connection pool without ever
;; running mount's `:stop`, so the pool is left open and the reload starts a second one. The MySQL
;; addon allows five connections across dev/la/prod; a few refreshes exhaust it, and the failures
;; that follow read on screen as being logged out.
;;
;; `user/stop` and `user/start` are defined in env/dev/user.clj.

((clojure-mode . ((cider-ns-refresh-before-fn . "user/stop")
                  (cider-ns-refresh-after-fn . "user/start"))))
