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
bb uberjar           # Build target/landing.jar exactly as Clever Cloud does
bb prod-local        # Same build, then run the jar locally

# Maintenance
bb clean             # Remove build artifacts
bb deps              # Update Clojure and npm dependencies
bb copy              # Copy files from ext_src.edn sources
```

## Architecture

### Source Structure

- `src/` - **one source root holding `.clj`, `.cljc` and `.cljs` side by side**, laid out by namespace. A file's extension is what marks its side: `.clj` backend, `.cljs` frontend, `.cljc` the shared core that must give the same answer on both. Same for `test/unit/`.

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

**Agora (`/agora2`)** — static HTML/CSS only, under `resources/public/agora2/{fr,en}/`,
served by the static-file handler. It is self-contained: every link and asset stays inside
`agora2/` (or is fully external). It never points at the rest of the site.

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
- the admin SPA (`src/landing/admin.cljs`, mounted from the static page at `/all-kind-of-checks`)
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

CSS/JS are served `Cache-Control: max-age=31536000, immutable` (`landing.endpoints.resource`), so returning visitors would otherwise keep stale files for a year after a deploy. `build.clj` fingerprints them **in `target/classes`, never the source tree**: each CSS/JS referenced by a page's `href`/`src` (resolved against the page location and its `<base href>`) is renamed after an 8-char SHA-256 of its content (`custom.css` → `custom.34f0a84f.css`), and the pages are rewritten. Only changed files get a new hash, so unchanged assets stay cached. The rename map goes to `cache_bust_manifest.edn` (read by the w3c-validate handler) and into the admin page's `cache-bust-manifest` script. Fonts/images referenced from inside CSS keep working because only the basename changes.

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

Deployment is Clever Cloud's own build. The Clever app ("Java + JAR") is linked to the GitHub repo and
redeploys on each push of the tracked branch. `CC_PRE_BUILD_HOOK=bash clevercloud/build.sh` installs
the Clojure CLI (and Node.js when missing) in `target-tools/`, releases `app-admin` with shadow-cljs,
then runs `clojure -T:build uber`. `clevercloud/jar.json` names the jar to start
(`target/landing.jar`).
