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
bb clj-test test-clj        # Run Clojure tests
bb cljs-node-test all       # Run ClojureScript tests (Node.js)
bb cljs-browser-test        # Run ClojureScript tests in browser (port 9651)

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
- `src/cljs/` - Frontend ClojureScript (re-frame/reagent SPAs)
- `src/cljc/` - Shared code (routes, pages, articles)

### Frontend Builds (shadow-cljs)

- `:app` - Main public frontend (`landing.fe/init`)
- `:app-admin` - Admin frontend (`landing.admin/init`)
- Output: `resources/public/js/compiled/`
- Dev server: port 9551, nREPL: 7151

### Backend

- Entry point: `landing.server/-main` (uses Mount for state management)
- HTTP handler: `landing.handler/handler` with Reitit router
- Routes: `/`, `/articles/*`, `/all-kind-of-checks`, `/api`, `/ping`, `/contact`

### Shared Code (cljc)

- `landing.routes` - URL definitions, links, and i18n route labels (French/English)
- `landing.pages.*` - Page components (home, article, admin, error)
- `landing.article.*` - Content modules (contacts, privacy, legal-notice, etc.)

**Note:** the public-facing site is now served as **static HTML** from `resources/public/{fr,en}/...`, generated and maintained outside the Clojure render pipeline. The `cljc` modules above are consumed only by:
- the admin SPA (`src/cljs/landing/admin.cljs`, route `/all-kind-of-checks`)
- `landing.endpoints.contact` (uses `landing.routes/links` for the success redirect target)

The backend's role is now: serve static resources, host the REST API (`/contact`, `/api`, `/check-url`, etc.), and host the admin SPA. Page-rendering Clojure has been removed.

### Static-site assets and fragments

- `resources/public/{fr,en}/index.html` and `articles/*.html` — actual pages.
- `resources/public/{fr,en}/404.html`, `articles/contact-validated.html` — error/confirmation pages.
- `resources/fragments/{header,footer,left-menu}.{fr,en}.html` — reusable fragments. `bb update-website` injects them between `<!-- BEGIN:NAME -->` / `<!-- END:NAME -->` markers. **Article pages have their own inline `<header>` (different from the index header) so HEADER markers live only in `index.html` files.**
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
