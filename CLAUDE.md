# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Stack

Clojure/ClojureScript full-stack landing/portfolio website for Hephaistox.

- **Backend**: Clojure + http-kit + Reitit + next.jdbc (MySQL)
- **Frontend**: ClojureScript + Re-frame (Reagent/React) + Reitit
- **Shared code**: `.cljc` files (run on both JVM and browser)
- **Build orchestration**: Babashka (`bb`)
- **ClojureScript compiler**: shadow-cljs
- **Formatter**: zprint (`.zprintrc`)
- **Linter**: clj-kondo

## Common Commands

```bash
bb repl           # Start dev REPL with hot-reload
bb clj-test       # Run Clojure tests
bb cljs-node-test # Run ClojureScript tests (Node)
bb cljs-browser-test # Run ClojureScript tests (browser)
bb lint           # Lint with clj-kondo
bb format         # Format with zprint
bb gha            # CI check: lint + clj-test + cljs-node-test
bb prod-local     # Build production JAR and run locally
bb uberjar        # Build JAR only
bb clean          # Remove build artifacts
```

**CI enforces formatting** — run `bb format` before committing or the workflow will fail.

## Architecture

### Source layout

```
src/clj/landing/    — Backend (JVM only)
src/cljs/landing/   — Frontend (browser only)
src/cljc/landing/   — Shared logic (both JVM and browser)
test/unit/          — Tests mirroring src/ structure
resources/public/   — Static assets served directly
```

### Backend (`src/clj/landing/`)

- **server.clj** — entry point, uses Mount for component lifecycle
- **web_server.clj** — http-kit server setup
- **handler.clj** — Reitit HTTP routing
- **db.clj** — database access
- **endpoints/** — one namespace per route handler (ping, contact, HTML rendering, Swagger, etc.)

### Frontend (`src/cljs/landing/`)

- **fe.cljs** — main app entry point, Re-frame initialization
- **admin.cljs** — admin panel entry point
- **pages/** — page-level components (home, article, admin, error)
- **article/** — content modules for each page section

### Shared (`src/cljc/landing/`)

- **routes.cljc** — route definitions shared between backend and frontend (avoids duplication)
- **language.cljc** — i18n support

### shadow-cljs build targets

Defined in `shadow-cljs.edn`:
- `:app` — main frontend bundle
- `:app-admin` — admin panel bundle
- `:browser-test` — browser test runner
- `:ltest` — Node.js test runner

### Deployment

- `bb la` — deploy to local acceptance (Clever Cloud)
- `bb prod` — deploy to production (Clever Cloud)
