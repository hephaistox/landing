# Marre de rater — design document (WIP)

> Status: **elaboration in progress.** This document is being written iteratively.
> Nothing here is built yet. The [Open questions](#open-questions) list at the
> bottom tracks what still needs a decision; work through it one item at a time.

## Pitch

**"Marre de rater"** — *"I'm fed up with missing out."*

A watchlist app. A user records **a subject they don't want to miss**, and the
platform keeps an eye on the Internet (and on the user's connected services) for
when that subject becomes available or an event happens — then **notifies** them.

The value is *not* discovery (finding new things) but **not-missing** things the
user already cares about: the app watches on their behalf so a release, airing, or
announcement never slips by.

### Motivating use cases

1. **Movie.** A user hears about a movie and stores it. The app watches for it and
   notifies the user when:
   - it airs on TV (TV listings), and/or
   - it appears on a VOD provider the user has connected (Netflix, Amazon Prime, …)
     — the platform searches that provider's catalogue on the user's behalf.
2. **Artist / band.** The user follows an artist; the app watches for **concerts**
   (ideally near the user) and notifies when one is announced.

The subjects are open-ended (movies and artists are the first two); the design
should not hard-code only these.

## Design principles

- **P1 — Scarce, high-value notifications only.** The whole product value is *not
  missing the things that matter* — so it must ping **rarely** and only when it is
  genuinely worth it. Noise destroys trust; a notification the user shrugs at is a
  failure. **Every layer is biased toward silence.**
- **P2 — Probes are source-specific and format-aware.** There is **no generic
  matcher**. Each probe is written for **one** source and **exploits that source's
  data shape** to extract only high-signal results. P2 follows from P1: deciding
  whether a hit is high-value requires *understanding* the data, which a generic /
  declarative matcher cannot do. (The naive substring TV match catching "Batman, la
  série animée" alongside "Batman" is exactly the low-value noise P2 exists to
  prevent — see Sandbox #1 learnings.)
- **Wide ≠ frequent.** A notification may be *wide* (reference content beyond what the
  user can access today — e.g. a paid VOD offer) yet notifications stay *scarce*.
  Breadth-of-content and frequency are separate dials; both are set in the
  interpretation layer.

## Technical placement

Lives **inside the `landing` project, as a sibling of `agora`** — same repo, same
stack, same deployment. No new service.

- Backend: Clojure / Ring / Reitit (mounted into `landing.handler/router`, own route
  prefix — TBD, see Q1).
- Frontend: a **single-page app** (Reagent / Re-frame / Pushy), its own shadow-cljs
  build, mirroring `agora`'s frontend structure.
- **Design & ergonomics: defer to `agora`.** When a UI/UX decision comes up and this
  doc doesn't specify it, copy what agora does.

## Architecture (intended — reuse agora's proven pieces)

| Concern   | Reuse from agora                        | Notes                                                                                                                                                           |
|-----------|-----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Storage   | `agora/db.clj` + `document.clj` pattern | One MySQL table storing an **edn `content` blob** + a few **indexed columns for search only**. Very few SQL queries; search is `LIKE` over the indexed columns. |
| Scheduler | `agora/scheduler.clj` pattern           | In-process Mount `defstate` + `ScheduledExecutorService`. Runs the stored searches **regularly**; no external cron.                                             |
| Auth      | `agora/oauth.clj` + `auth.clj`          | **Google OAuth2**, per-user watchlists.                                                                                                                         |
| SPA shell | `agora/endpoints/shell.clj` pattern     | HTML shell(s) + JSON API under the route prefix.                                                                                                                |

### The central problem: modelling "a search"

The hardest and first thing to get right is **how a search is modelled**. Plan:

1. Write **a bunch of small, concrete searches** covering the different use cases
   (movie-on-TV, movie-on-VOD, artist-concert, …).
2. **Sandbox** each one — run it in isolation to see what it takes to express and
   execute it — before committing to an abstraction.
3. Only once the search model has proven itself do we build the real app (the SPA,
   auth, persistence, scheduler wiring).

So: **search model & sandbox first, product second.**

#### Anatomy of a watch (working model)

A **Watch** = a **Subject** the user cares about + one or more **Probes**. Each
**Probe** = a **Source** + a **Match condition**; running it yields zero or more
**Hits**; a *new* Hit (not seen before) drives a **notification**. The scheduler
re-runs every active Probe on a cadence.

```
Watch ── Subject (movie "Dune" / band "Phoenix")
      └─ Probe(s) ── Source (TV / VOD provider / concert feed)
                  └─ Match (what counts as a hit)
                       → Hit(s) → notification (if new)
```

#### Decision (Q2): **hybrid — code now, data later**

- **Phase 1 (sandbox):** each Probe is a small Clojure **function**, one per use
  case (`tv-movie-probe`, `vod-movie-probe`, `concert-probe`, …). Run them directly
  to learn what expressing and executing a search really costs.
- **Phase 2 (once the shape is clear):** the **source-specific probe code stays** (it
  is the substance — see P2; a probe is bespoke to its source and exploits that
  source's format). What becomes data is only the thin **subject + which sources to
  watch** config (the edn `content` blob, Q7), resolved to the right probe fns via a
  small **source→probe registry**. There is deliberately **no generic matching
  engine** — the "data" layer picks and parameterises probes, it does not replace
  them.

The abstraction is *deferred until the sandboxed searches provide evidence* — no
up-front vocabulary design. The shared parts to factor out are the **engine**
(dedupe, scheduling, interpretation), **not** the matching (which is per-source).

#### Search scope: content kind (subject `:kinds`)

A search declares **what kind of content it targets** — any of `#{:movie :tv-show
:anime}`, or `:all`. This is part of the subject/search spec, and it does two jobs:

1. **Disambiguates an ambiguous title** — "Batman" the *film* vs the *series* vs the
   *anime* are different desires behind the same string.
2. **Scopes what a probe emits** — a movie-watcher and a series-watcher want opposite
   halves of the same title's airings.

**Kind classification is source-bounded (P2).** Each source can only honour `:kinds`
as far as its format allows:

- **epg.pw TV feed** has no `<category>`/genre → it can only infer **movie vs
  episodic by duration**. It **cannot** identify anime. When `:anime` is requested it
  falls back to episodic and flags `:kind-confidence :low`. (Proven in Sandbox #1:
  `batman` → movie 2 / tv-show 40 / all 42.)
- **TMDB** carries `media_type` (movie vs tv) and genres (incl. *Animation*) and anime
  keywords → it *can* classify kind properly.

→ **Finding:** kind is best **resolved at subject-identity time from a metadata source
(TMDB)**, then carried on the subject; probes filter to it using whatever their own
format supports. Reinforces the earlier "canonical subject identity" learning: TMDB
resolves *which work and what kind*; source probes confirm availability. (See Q10.)

#### Use cases the model must cover (Q2 exit criterion)

The distinction that matters is the **user's intent**, not the media type. There are
**two** fundamental use cases. **Q2 is not done until each is validated by working
sandboxed probes.**

##### UC-A — "I'm waiting to be able to find/watch this"

A **specific, known work** whose availability is *gated* — you can genuinely **miss**
it (a broadcast slot, a streaming window). Notify me when I can finally get to it.

- Subject: a concrete title (a **movie**).
- Trigger: it **becomes available** to watch.
- "Movie on TV" and "movie on VOD" are the **same** use case to the user — TV vs VOD
  is just plumbing (two probes, one desire).
- Match = "available now" (a *state*).

| Probe             | State                                                                    | Source                                                                        | Source status                                                                                |
|-------------------|--------------------------------------------------------------------------|-------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `tv-movie-probe`  | ✅ **sandbox built & validated** — `marre_de_rater/scripts/tv_movie.clj` | **epg.pw** hosted France XMLTV (`epg_FR.xml`, ~42k programmes, daily, no key) | format-aware: duration floor + contiguous-token match + upcoming-only; batman 42→2 noise-filtered |
| `vod-movie-probe` | 🔴 no probe yet                                                          | TMDB `watch/providers` (by region)                                            | decided, reachable                                                                           |

##### UC-B — "Keep me posted on this creator"

You love a **person / band / author**; notify when they **do something new** — a
concert, a new album, a new book, a disc, a theater role, *anything*. Media-agnostic,
**activity-driven**, open-ended. The "artist → concert" case and the "I love Stephen
King, tell me when he does something new" case are the **same** use case.

- Subject: a **creator/entity** (ongoing identity, not a single work).
- Trigger: **any new output/activity** attributed to them.
- Match = "new item since last seen" (an *event stream*).
- Harder to model: one subject likely fans out into **several probes** (concerts,
  releases, news, …) or a general activity feed.

| Probe                | State           | Source                              | Source status             |
|----------------------|-----------------|-------------------------------------|---------------------------|
| `concert-probe`      | 🔴 no probe yet | Bandsintown artist-events (likely)  | likely-reachable API (Q4) |
| release/other probes | 🔴 not scoped   | TBD (MusicBrainz / Discogs / news?) | open (Q4)                 |

##### Why these two are the validation set

- **UC-A** proves *one subject, many probes* (TV + VOD over the same movie) and the
  *state / availability* flavour.
- **UC-B** proves a *different subject kind* (creator, not a work), the *event-stream*
  flavour, **geographic** match ("concert near me"), and *multi-probe fan-out*.
- **Books are not a separate case**: a book is *concrete* (always obtainable → you
  never "miss" it), so UC-A doesn't apply; the book-shaped desire ("notify when the
  author publishes") is UC-B.
- If probes for UC-A **and** UC-B share one contract without contortion, the model is
  proven. More use cases may be appended, but these two are the seed.

#### Probe contract (Q2 decision)

**A probe just answers "does this search have a positive result right now?"** It is
**pure** and **has no memory**; the engine is stateful and owns *all* de-dup.

```clojure
;; A probe returns the matches it currently sees — every run, all of them.
(defn a-probe
  [subject ctx]        ; subject: {:kind :movie :title "Dune" …}
  ;; ctx: run context — {:region :fr :now <ts> :accounts {…}} (NO seen-set)
  [{;; --- a positive result (hit) ---
    :hit-id  "…"       ; stable id of this result → the ENGINE de-dups on it
    :title   "Dune"
    :when    <ts>      ; airing time / availability date / concert date
    :where   "TF1"     ; channel / "Netflix FR" / venue
    :url     "…"
    :watch   <ref>}])  ; back-reference to the owning watch/subject
```

The **engine** (scheduler side) runs each probe, diffs the returned `:hit-id`s
against the per-watch stored seen-set, notifies only on newly-appeared ids, then
records them. Because probes take no prior state, each is **runnable in isolation**
in the sandbox with just a subject + a context map.

> Parked (engine/notification detail, *not* a probe concern): whether a result that
> stays positive for a while (e.g. "on Netflix") should re-notify, and whether
> discrete occurrences (each TV airing) should each notify. The probe doesn't care;
> this is purely how the engine chooses `:hit-id` granularity and re-notify policy.
> Revisit once probes exist. (was Q2b)

#### The engine pipeline: probes → consolidation → interpretation → notification

Notifications are **wide** — a notification is *not* 1:1 with "something the user can
already access." So between the pure probes and the user there are **two engine
layers**:

```
probes (pure) ─▶ raw positive results ─▶ ┌───────────────┐ ─▶ ┌────────────────┐ ─▶ notification
  return ALL matches, no user state       │ consolidation │    │ interpretation │
  (every VOD provider, every airing,      │ layer         │    │ layer          │
   TV / cinema / VOD in parallel)         └───────────────┘    └────────────────┘
                                          NEW + MERGED:         turn events into
                                          one EVENT, many        user-facing messages
                                          SOURCES (1→many)
```

1. **Probes** stay pure and return **everything** they find — e.g. *all* providers a
   movie streams on, not just the ones the user subscribes to. They know nothing
   about the user. (Reinforces the pure-probe contract above.)
2. **Consolidation layer** (engine) does two things:
   - **Dedupe over time** — diff `:hit-id`s against the per-watch seen-set → the
     genuinely new results.
   - **Merge across probes** — the *same real-world event* can be surfaced by **more
     than one probe at once** (e.g. a movie becomes watchable **on TV tonight** *and*
     is **screening at a cinema** *and* just **landed on a VOD provider**). These are
     one **Event**, not three notifications. Consolidation collapses them into a
     single Event that holds a **1→many link to its sources**:

     ```clojure
     {:event    {:subject <Dune> :what :now-watchable}
      :sources  [{:probe :tv     :where "Ciné+" :when …}    ; ← 1 event
                 {:probe :cinema :where "UGC Lyon" :when …} ;   → many sources
                 {:probe :vod    :where "Netflix FR"}]}
     ```

     Merging needs a stable notion of "same event", which is why a **canonical subject
     identity** (TMDB id) matters: it lets TV / cinema / VOD hits for the same film
     collapse instead of triple-notifying. (See Q11.)
3. **Interpretation layer** (engine): turn consolidated events into notifications,
   applying business rules — user preferences/subscriptions, phrasing, grouping across
   the event's sources, **and deliberately *widening*** beyond what the user can
   access today.

##### Wide notifications & monetization

Because notifications are wide, the interpretation layer can surface results the user
can't currently reach — and that is a **monetization** surface:

- Example: user watches *Dune*. It's on Disney's VOD, which the user does **not**
  subscribe to. Instead of hiding that, the notification can say *"Dune is on Disney+
  — here's an offer"*, and Disney could **pay** to place that offer.
- So: **probes surface all availability; interpretation decides what to say and how to
  monetize it.** Keeping probes provider-agnostic and user-agnostic is what makes this
  possible. (New open question Q9.)

### Sandboxes (disposable scripts in `marre_de_rater/scripts/`)

Disposable, REPL-run, **not wired into the app** — throwaway code to feel out what a
real probe costs (Q3). Each validates a slice of the search model (Q2).

#### ✅ Sandbox #1 — movie on TV (`scripts/tv_movie.clj`) — BUILT & VALIDATED

- **UC:** UC-A (await availability), TV source.
- **Source:** [epg.pw](https://epg.pw/) hosted **France XMLTV** (`epg_FR.xml`) — one
  ~16 MB file, ~42k programmes, daily refresh, **no API key**. Programmes have
  title/desc/start/stop + a channel id resolved to a `display-name`. **No
  `<category>`** — irrelevant for UC-A: we match the *specific* movie title the user
  gave against programme titles.
- **Probe (format-aware, P1/P2):** pure `(tv-probe subject ctx)` where
  `subject = {:title … :kinds <#{:movie :tv-show :anime} | :all>}` (`:kinds` default
  `#{:movie}`). Emits a hit per **upcoming** airing whose title contains the subject
  title as a **contiguous run of words** and whose **inferred kind** matches. Filters:
  1. **Duration floor** (`start`/`stop` → minutes; default 60) — splits **movie**
     (≥ floor) from **episodic** (< floor); the strongest high-value signal.
  2. **Contiguous-token match** — keeps sequels/subtitles ("…: les deux tours") while
     rejecting incidental substrings.
  3. **Kind filter** — `:kinds` scopes to movie / episodic / all. `:anime` can't be
     confirmed from this feed → returned as episodic with `:kind-confidence :low`.
  4. **Upcoming-only** (`start ≥ :now`).
  Hits carry `:hit-id :title :kind :kind-confidence :when :starts-at :duration-min
  :where :desc`.
- **Validated live** (2026-07-08):
  - `"batman"` kinds `#{:movie}` → **2** (90-min *Scooby-Doo & Batman*);
    `#{:tv-show}` → **40** (20–25-min episodes); `:all` → **42** (2 + 40, clean
    partition). The duration floor is doing the classification.
  - `"seigneur des anneaux"` `#{:movie}` → 2 real LOTR films (172 & 173 min).
  - `"zzz-nonexistent-movie"` → `[]`.
- **Deps:** only `clj-http` (already on classpath) + core `clojure.xml` + `java.time`
  — no new dep.
- **Learnings feeding the model:**
  - Duration is a *free, decisive* high-value signal in this format — concrete
    evidence for **P2** (a source-specific probe beats a generic matcher).
  - **Residual precision gap:** "Scooby-Doo & Batman" still matches `"batman"` — free
    text can't fully disambiguate. The real fix is a **canonical subject identity**
    (a TMDB id + official title / AKAs) instead of a raw string. UC-A's *other* source
    (TMDB, Sandbox #2) provides exactly that → the two UC-A sources reinforce each
    other: TMDB resolves the work, TV matches its official titles.
  - No `<category>` means TV can only answer "is *this* movie on", not "any movie" —
    fine for UC-A.

#### 🔴 Sandbox #2 — movie on VOD (TMDB) — planned

- **UC:** UC-A, VOD source. **Source:** [TMDB](https://www.themoviedb.org/)
  `GET /movie/{id}/watch/providers` (JustWatch data) → per-region provider
  availability, e.g. `{:results {:FR {:flatrate ["Netflix" …]}}}`. Free API key.
  Resolve title→id via `GET /search/movie` first.
- **Probe:** `(vod-movie-probe subject ctx)` → a hit per provider streaming it in
  `(:region ctx)` — returns **all** providers (interpretation layer narrows/widens,
  see monetization).
- **Consequence:** availability-by-region comes from TMDB/JustWatch, so we do **not**
  log into the user's Netflix/Amazon — Q8 resolved.

#### ✅ Engine sandbox (`scripts/engine.clj` + `scripts/intents.edn`) — BUILT & VALIDATED

The stateful counterpart to the pure probes. **Input is a user INTENT, not
probe-shaped data** — the engine does the routing. Realises the pipeline as **five
explicit layers**.

- **`intents.edn`** — user intents only: `{:id :owner :query :kind :lang}`. No
  sources, no probe details — *what* the user doesn't want to miss, of what kind, in
  what language.
- **`engine.clj`** — a **probe registry** where each probe declares `:finds?` (can it
  serve this intent?) and `:translate` (intent → its own input); the user never names
  a source. The tick runs five named layers:

  | # | Layer | Does |
  |---|-------|------|
  | 1 | **assign** | route each intent to the probes that could find it, **translating** it into each probe's input |
  | 2 | **execute** | run the assigned probes → events |
  | 3 | **dedupe** | drop events already in **`memory`** (persists across ticks) → new events |
  | 4 | **gather** | group the new events **per probe** |
  | 5 | **notify!** | one notification per new event (`println` for now) |

- **Validated live** (2026-07-08) over 3 intents:
  - **assign routes correctly:** `i-lotr` + `i-batman` (fr/movie) → `:tv`; `i-dune-en`
    (lang `:en`) is **not** assigned — the FR-only TV feed can't serve it. Routing is
    the engine's job, not the user's.
  - **TICK 1 → 4 notifications** (2 LOTR films + Scooby-Doo & Batman on 2 channels),
    gathered under "via tv". **TICK 2 → nothing new** (memory = 4).
- **Deferred (Q11):** cross-probe **1→many consolidation** (same event found by TV +
  cinema + VOD → one event) is *not* in this engine yet — it needs a stable
  same-event key (canonical subject identity). Today each probe hit is its own event.
- **Run:** `(load-file "…/tv_movie.clj")` then `(load-file "…/engine.clj")`, then
  `(tick! "marre_de_rater/scripts/intents.edn")`.

#### 🔴 Sandbox #3 — concert for an artist (Bandsintown) — planned (UC-B)

## Open questions

Legend: 🔴 TODO · 🟡 IN PROGRESS · ⏸️ PAUSE · ✅ DONE

| #   | State          | Question                                                                                                                                                                                                                                                                                                                                                                                       |
|-----|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Q1  | 🔴 TODO        | **Name & namespace.** Final app name? Code namespace (`landing.mdr`? `landing.watch`?) and URL prefix (`/mdr`? `/watch`?)?                                                                                                                                                                                                                                                                     |
| Q2  | 🟡 IN PROGRESS | **The search model.** Contract decided (probe = pure fn `(subject ctx → hits)`; engine owns de-dup). Two user use cases: **UC-A** "await availability of a known work" (movie via TV+VOD; *state* flavour) and **UC-B** "follow a creator for new activity" (artist/author; *event-stream* flavour, multi-probe). **Done only when both are proven by sandboxed probes sharing one contract.** |
| Q2b | ⏸️ PAUSE        | **Re-notify policy / `:hit-id` granularity** (engine concern). Parked until probes exist.                                                                                                                                                                                                                                                                                                      |
| Q3  | 🟡 IN PROGRESS | **The sandbox.** Location decided: `marre_de_rater/scripts/*.clj`, disposable, REPL-run via `load-file`. **Sandbox #1 (TV) built & validated live.** Next: #2 VOD (TMDB), #3 concert (Bandsintown). Open: fixture capture for repeatable tests.                                                                                                                                                |
| Q4  | 🟡 IN PROGRESS | **Sources / providers.** VOD = **TMDB `watch/providers`** (decided, search #1). Concerts = Bandsintown (likely). TV listings = still open (regional/scraped). ToS review per source pending.                                                                                                                                                                                                   |
| Q5  | 🔴 TODO        | **Notification channel.** How is the user notified — email, web push, in-app inbox? Reuse anything from landing (contact email infra)?                                                                                                                                                                                                                                                         |
| Q6  | 🔴 TODO        | **Scheduling cadence & de-dup.** How often does each search run? Global sweep vs per-watch cadence? How do we avoid notifying twice for the same hit?                                                                                                                                                                                                                                          |
| Q7  | 🔴 TODO        | **Storage schema.** Table name; which fields are promoted to indexed columns vs left in the edn blob; how a user's connected-provider credentials (if any) are stored.                                                                                                                                                                                                                         |
| Q8  | ✅ DONE        | **Connected accounts.** Resolved for VOD: availability comes from TMDB/JustWatch **by region**, so we do **not** log into the user's Netflix/Amazon. (Revisit only if a future source genuinely needs account access.)                                                                                                                                                                         |
| Q9  | 🔴 TODO        | **Interpretation layer & monetization.** Notifications are *wide* (surface availability the user can't currently access). How does interpretation turn raw results into messages, apply user prefs, and place **paid offers** (e.g. a VOD provider paying to be featured)? Business model detail.                                                                                              |
| Q10 | 🔴 TODO        | **Content-kind taxonomy.** Subject carries `:kinds` (`#{:movie :tv-show :anime}` \| `:all`). Is *anime* a form or a genre (anime films exist)? One facet or two (form × genre)? Resolved where — TMDB `media_type` + Animation genre — and carried on the subject.                                                                                                                          |
| Q11 | 🔴 TODO        | **Consolidation: same-event across probes.** One real-world event (movie now watchable) may be found by several probes (TV / cinema / VOD) → one **Event** with a **1→many** link to sources. What key defines "same event" (canonical subject id + event-type + time window)? How are sources ranked/merged for the notification?                                                          |

---
*Maintainer note: keep this list current. When a question is settled, mark it ✅ and
fold the decision into the sections above; when a new one surfaces, append it.*
