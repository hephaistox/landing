(ns landing.agora.frontend.app-db
  "The app-db's one structural rule: what survives a change of context.

  Nearly everything the SPA holds is a copy of something the server owns, and what a copy *reads as*
  depends on who is asking and which publication they are working in — a publication-scoped read
  overlays that publication's drafts, and a cached entry is served without a refetch. So a change of
  identity (log in, log out, erase, rename) or of active publication invalidates the whole store.

  The rule is written the safe way round: **drop everything, name what survives**. A cache added
  later is dropped without anyone having to remember it here — the opposite list would leak by
  omission, silently, and only under someone else's session.

  `forget` is a plain function, not an event, so a caller applies it in the same db transition that
  installs the new context. That is what keeps the list to one entry: the user and the active
  publication are written *after* the forget, and the active publication is re-read from
  localStorage, which owns it."
  (:require
   [landing.agora.frontend.i18n :as i18n]))

(def keep-keys
  "The db entries that outlive a context change. Only the interface language: it is a preference the
  reader set, not something the server told us, and it must not blink back to the default on logout."
  [::i18n/lang])

(defn forget "`db` reduced to what survives a context change." [db] (select-keys db keep-keys))
