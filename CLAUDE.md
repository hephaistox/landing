# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hephaistox landing page - a ClojureScript/Clojure full-stack web application using shadow-cljs for frontend compilation and Ring/Reitit for the backend server.

## Common Commands

All commands use Babashka (`bb`):

```bash
# Development
bb repl              # Start REPL with dev environment (set LANDING_PORT=8080 first)
bb repl-fe           # Start frontend REPL (shadow-cljs for :app and :app-admin builds)

# Testing
bb clj-test          # Run Clojure tests
bb cljs-node-test all # Run ClojureScript tests (Node.js)
bb cljs-browser-test  # Run ClojureScript tests in browser (port 9651)

# Code Quality
bb format            # Format source code with zprint
bb lint              # Lint with clj-kondo
bb bp                # Before-push check: format, lint, and all tests

# Building
bb prod-local        # Build production and run locally
bb uberjar           # Build uberjar to target/uberjar/landing.jar
bb la                # Build and push to local acceptance
bb prod              # Build and push to production

# Maintenance
bb clean             # Remove build artifacts
bb deps              # Update Clojure and npm dependencies
bb copy              # Copy files from ext_src.edn sources
```

## Architecture

### Source Structure

- `src/clj/` - Backend Clojure (server, handlers, endpoints)
- `src/cljs/` - Frontend ClojureScript (admin SPA)
- `src/cljc/` - Shared code (routes + admin/article URL manifests). No page-rendering code lives here anymore.

### Frontend Builds (shadow-cljs)

- `:app-admin` - Admin frontend (`landing.admin/init`)
- `:browser-test` - Browser test
- `:ltest` - for local tests
- Output: `resources/public/js/compiled/`
- Dev server: port 9551, nREPL: 7151

### Backend

- Entry point: `landing.server/-main` (uses Mount for state management)
- HTTP handler: `landing.handler/handler` with Reitit router (`landing.handler/router`)

### HTTP endpoints (reference)

The complete route table lives in `landing.handler/router`. Every route below is
in active use; keep this list in sync when adding/removing routes. Audited
2026-07-06 — no redundant routes; removed `plus` (a toy adder) and `/exception`
(a throw-only endpoint that only re-verified the lib's `wrap-exception-handling`,
not our own code).

**Landing (public site + ops)**

| Route                          | Handler ns                                                    | Why it exists                                                                       |
|--------------------------------|---------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `GET /`                        | `handler/root-redirect-route`                                 | Redirect `/` → `/<lang>/index.html` (cookie → Accept-Language → `fr`).              |
| `GET /index.html`, `/404.html` | `handler/lang-page-redirect-route`                            | Redirect bare top-level pages to their language-prefixed location.                  |
| `GET /articles/:slug`          | `handler/legacy-articles-route`                               | 301 legacy no-lang article URLs → `/<lang>/articles/<slug>.html` (SEO back-compat). |
| `POST /contact`                | `endpoints.contact`                                           | Store contact-form submissions (MySQL). Used by `js/contact-form.js`.               |
| `GET /ping`                    | `endpoints.ping`                                              | Liveness probe (returns `pong`) for uptime/Clever Cloud monitoring. Rate-limited.   |
| `GET /all-kind-of-checks`      | `endpoints.html.admin-be`                                     | Serves the admin/diagnostics SPA shell (`app-admin.js`).                            |
| `* /check-url`                 | `endpoints.check-url`                                         | Reachability check for a URL; consumed by the admin SPA.                            |
| `* /w3c-validate`              | `endpoints.w3c-validation`                                    | W3C HTML/CSS validation; consumed by the admin SPA.                                 |
| `/api`                         | `endpoints.swagger`                                           | Swagger UI + OpenAPI docs for the REST surface (linked from the admin SPA).         |
| static files                   | `endpoints.resource/resource-handler`                         | Serves `resources/public/**` with env-aware `Cache-Control` (see Cache-busting).    |
| `<fallback>`                   | `endpoints.default-handler` + `handler/lang-fallback-handler` | 404 / 500 / not-acceptable / method-not-allowed, language-resolved.                 |

**Agora (`/agora`)** — HTML shells are served by `agora.endpoints.shell`; the JSON
API under `/agora/api` is the SPA's backend.

| Route                                                                    | Handler                             | Why it exists                                                                    |
|--------------------------------------------------------------------------|-------------------------------------|----------------------------------------------------------------------------------|
| `GET /agora`, `/agora/:lang`                                             | `handler/agora-lang-redirect-route` | Redirect to `/agora/<lang>/discover`.                                            |
| `GET /agora/sitemap.xml`                                                 | `shell/sitemap-route`               | Dynamic sitemap of every KI permalink (SEO).                                     |
| `GET /agora/:lang/ki/:name/:major`                                       | `shell/ki-page-route`               | Public KI **permalink** shell with server-rendered SEO (OpenGraph + schema.org). |
| `GET /agora/:lang/discover`, `/preferences`                              | `shell/public-shell-route`          | Public shells with generic SEO head.                                             |
| `GET /agora/:lang/{new,ki/:id,article/:id,admin}`                        | `shell/app-shell-route`             | Authoring/app shells (`noindex`; not canonical content).                         |
| `/agora/api/auth/{register,login,logout,me,lang,google,google/callback}` | `agora.endpoints.auth`              | Accounts + session (email/password + Google OAuth) and the language preference.  |
| `/agora/api/admin/{tnrs,drop-tnr,compact-tnr}`                           | `agora.endpoints.admin`             | Maintenance (list/prune KI lineages); **owner-only**.                            |
| `GET\|POST /agora/api/ki`                                                | `ki/ki-collection-route`            | Search KIs (`GET ?q`) / create a KI (`POST`).                                    |
| `GET /agora/api/ki/by/:name/:major`                                      | `ki/by-major-route`                 | Latest-minor KI behind a permalink (drives the permalink page).                  |
| `GET /agora/api/ki/:id`                                                  | `ki/ki-route`                       | A KI by concrete version id.                                                     |
| `POST /agora/api/ki/:id/edit`                                            | `ki/edit-ki-route`                  | Produce a new minor version (immutable edit).                                    |
| `POST /agora/api/ki/:id/translate`                                       | `ki/translate-ki-route`             | Create a language sibling of a KI + its inputs.                                  |
| `POST /agora/api/translate`                                              | `ki/translate-suggest-route`        | Best-effort machine-translation suggestion (authoring aid).                      |
| `POST\|DELETE /agora/api/ki/:id/inputs`                                  | `ki/inputs-route`                   | Add / drop an input edge.                                                        |
| `GET /agora/api/article/:id`                                             | `agora.endpoints.article`           | Fetch a seeded article.                                                          |

**Why the look-alike routes are actually distinct.** Several routes touch the same
concept but answer different questions — the distinction is the reason they exist:

- **The three HTML shells serve the *same* SPA bundle but inject a different
  `<head>`**, which is the whole point — the head decides how crawlers/unfurlers
  treat the URL:
  - `ki-page` → per-KI rich metadata (title/description/OpenGraph/schema.org
    `Article` with `isBasedOn` edges) so each permalink is independently indexable
    and shareable.
  - `public-shell` → generic site metadata for the always-public, indexable pages
    (discover, preferences) that have no single KI to describe.
  - `app-shell` → `robots noindex`: authoring/app screens (new, edit-by-id, admin)
    must never compete with a canonical permalink in search.
- **HTML shell vs JSON API for the same identity.** `/agora/:lang/ki/:name/:major`
  (`ki-page`) returns a crawlable *page*; `/agora/api/ki/by/:name/:major`
  (`by-major`) returns the *data* the SPA fetches to render it. Browser/crawler vs
  app.
- **Three ways to read a KI**, because each answers a different question:
  - `by-major` (`/ki/by/:name/:major`) — "the current (latest-minor) version of
    this permanent identity" → what a shared permalink resolves to.
  - `ki-route` (`/ki/:id`) — "this *exact* version" → following version/lineage
    links.
  - `ki-collection` (`GET /ki?q=`) — "which KIs match this text" → search / picking
    an input.
- **`/translate` vs `/ki/:id/translate`.** The first is a stateless
  machine-translation *suggestion* (writes nothing, used while typing); the second
  actually *creates* the language-sibling KI. Suggest vs commit.

Notes:
- The branded **500 page** is produced by `wrap-exception-handling` (auto-web lib)
  → our `default-handler/exception-response` → `500.html`, and by the same
  `exception-response` as an outer net in `landing.web-server`. There is no
  throw-only endpoint for it; our part — a 500 status, the language-resolved
  `500.html`, and never leaking exception details — is covered by
  `default-handler-test` (that tests our code, not the lib's try/catch).
- `endpoints.cached-response` and `endpoints.html` are **helpers** (in-memory/gzip
  response prep, html middleware), not routes.

### Shared Code (cljc)

Only pure-data modules :

- `landing.routes` — URL definitions, link maps, and i18n route labels (French/English).
- `landing.pages.admin` — manifests aggregated for the admin SPA: every URL the site should expose (reachability checks) and every page/CSS file to W3C-validate. **Despite the namespace name, no hiccup/page-rendering code lives here.**
- `landing.article.{rivalis,who-are-we}` — external-link manifests for those articles, aggregated by `landing.pages.admin`.

The cljc modules are consumed only by:
- the admin SPA (`src/cljs/landing/admin.cljs`, mounted from the static page at `/all-kind-of-checks`)
- `landing.endpoints.{contact,check-url,w3c-validation}` (use the manifests + `landing.routes/links`)

The backend's role is: serve static resources, host the REST API (`/contact`, `/api`, `/check-url`, `/w3c-validate`, etc.), serve the admin SPA shell, and serve language-resolved 404/500 pages via `landing.endpoints.default-handler`.

### Static-site assets and fragments

- `resources/public/{fr,en}/index.html` and `articles/*.html` — actual public pages.
- `resources/public/{fr,en}/404.html`, `500.html`, `articles/contact-validated.html` — error/confirmation pages. `404.html` and `500.html` are served by `landing.endpoints.default-handler` (`static-404-response` / `exception-response`) with the right HTTP status and language picked from the `lang` cookie.
- `resources/public/all-kind-of-checks.html` — static shell for the admin SPA; `app-admin.js` mounts on `#admin-panel`. Served by `landing.endpoints.html.admin-be/admin-route` with CORS headers.
- `resources/fragments/{header,footer,left-menu}.{fr,en}.html` — reusable fragments. `bb update-website` injects them between `<!-- BEGIN:NAME -->` / `<!-- END:NAME -->` markers in every page under `resources/public/{fr,en}/**.html`. **Article pages have their own inline `<header>` (different from the index header) so HEADER markers live only in `index.html`, `404.html`, and `500.html`.**
- `resources/public/js/lang.js` — language-switch helper, included on every page.
- `resources/public/js/contact-form.js` — contact form submit handler with retries / timeout. Loaded only by `articles/contacts.html`.

### Cache-busting (asset fingerprinting)

CSS/JS are served `Cache-Control: max-age=31536000, immutable` (`landing.endpoints.resource`), so returning visitors would otherwise keep stale files for a year after a deploy. `scripts/cache_bust.clj` solves this **on the built jar, never the source tree**. `bb la` / `bb prod` call `cache-bust/deploy!`, which: (1) runs the auto-build `build` step (shadow release + uberjar, no push), (2) opens the resulting `target/{la,prod}/landing.jar` via Java's zip filesystem and renames each referenced asset to embed an 8-char SHA-256 of its contents (`custom.css` → `custom.34f0a84f.css`), rewriting every matching link in the bundled `public/**.html`, then (3) amends the build's commit and `git push --force clever master`. Only changed files get a new hash, so unchanged assets stay cached. Idempotent (already-fingerprinted files are skipped) and the working tree is never modified.

**Scope:** referenced CSS/JS only — `public/css/*.css`, `public/fontawesome/css/*.css`, `public/js/*.js`, and `public/js/compiled/app-admin.js` (fingerprinted too, since shadow has already built it into the jar by then). Unreferenced files (e.g. the unused fontawesome `*.min.css` variants) are left alone. Fonts/images referenced from inside CSS via relative `url(...)` are unaffected because fingerprinting keeps each file's directory and only changes the basename.

### Per-widget JS convention

Interactive bits on the static site are **plain JS files under `resources/public/js/`**, named after their purpose (`lang.js`, `contact-form.js`, ...), each included only on the pages that need them. ClojureScript is reserved for the admin SPA and for future complex widgets (simulation/optimization demos), each as its own shadow-cljs build.

### Dependencies

- Backend: Ring, Reitit, Mount, next.jdbc, MySQL
- Frontend: Reagent, Re-frame, Pushy (routing)
- Shared: auto-web libraries from hephaistox/auto-web

## Configuration

- Environment: `LANDING_PORT` for server port
- Dev config: `env/dev/`
- Prod config: `env/prod/`
- External sources: `ext_src.edn`

## Testing

Tests are in `test/unit/` mirroring the src structure. Run specific tests:
```bash
bb clj-test test-clj -v    # Verbose output
bb cljs-node-test all -v   # Verbose ClojureScript tests
```

## CI/CD

GitHub Actions runs on every push: lint, format check, and `bb gha` (clj + cljs tests).
