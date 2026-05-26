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
- HTTP handler: `landing.handler/handler` with Reitit router
- Routes: `/`, `/articles/*`, `/all-kind-of-checks`, `/api`, `/ping`, `/contact`

### Shared Code (cljc)

All page-rendering Clojure has been removed. Only pure-data modules remain:

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
