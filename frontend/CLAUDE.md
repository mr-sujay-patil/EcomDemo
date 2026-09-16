# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Scope — read this before touching anything

**This file governs `frontend/` only, and work started here stays here.** Every file you create,
edit or delete must be under `frontend/`.

The repository root is a Spring Boot backend that this app merely calls over HTTP. It has its own
`CLAUDE.md`, `README.md`, `docs/ROADMAP.md` and `docs/decisions.md`. **None of them mention this app,
and none of them should** — not a section, not a line in the progress tracker, not a decision entry.
The root `.gitignore` is likewise off limits; this folder ignores its own build output.

The backend's rules do not apply here. There is no "one new technology per phase", no phase branch,
no decision log to update, no `./mvnw clean verify` to satisfy. Those govern `src/main/java`, not
this folder.

If a change here appears to require a backend edit — a `@CrossOrigin` annotation, a new endpoint, an
extra field on a DTO, a tweak to `application-dev.yml` — **stop and say so in your reply. Do not make
it.** Nine times out of ten the frontend can be adjusted instead; see the proxy note below.

## What this is

A throwaway React console for exercising the backend API by hand — a browser-based alternative to a
long list of `curl` commands. It is a testing tool, not a product and not a roadmap phase. Its value
is the **request log**: every call's method, path, status, duration and both bodies. The tables and
forms exist to trigger requests; the log is what you actually came to look at. Any change that makes
the UI prettier while making the traffic harder to see is the wrong change.

## Commands

```bash
npm install       # first time only
npm run dev       # dev server on http://localhost:5173, with the /api proxy
npm run build     # production bundle into dist/ - the only gate this project has
npm run preview   # serves that bundle WITHOUT the proxy, so every /api call 404s
```

`npm run dev` is useful only with the backend up: `./mvnw spring-boot:run` from the repository root,
which in turn needs PostgreSQL on 5432. The header shows a red dot and a banner when nothing answers.

Point at a backend elsewhere with `VITE_API_TARGET=http://localhost:9090 npm run dev`.

There is no test runner and no linter. **`npm run build` is the only automated check** — it catches
syntax and unresolved imports, nothing else. Verify behaviour by driving the UI, or with
`curl http://localhost:5173/api/...`, which goes through the same proxy the browser uses.

## How it reaches the backend, and why there is no CORS anywhere

The browser only ever calls `/api/*` on its own origin, `localhost:5173`. The Vite dev server proxies
those to `localhost:8080` (`vite.config.js`). Every request is therefore same-origin, and **that is
the entire reason the backend needs no CORS configuration**.

This is the decision that keeps the two codebases independent, so when an API call fails from the
browser, the fix is in `vite.config.js` — never a `@CrossOrigin` on a Spring controller.

## Architecture

State lives in `App.jsx`; the panels below it are presentational and receive data, `reload*`
callbacks and `onError`. No router (tabs are `useState`), no state library, no HTTP client.

| File | Responsibility |
|---|---|
| `src/api/client.js` | The **only** place `fetch` is called. Every request is recorded and every non-2xx becomes an `ApiError` |
| `src/App.jsx` | Owns products / cart / orders state, the tab, the error banner and backend reachability |
| `src/components/RequestLog.jsx` | Subscribes to the log; the panel the app exists for |
| `src/components/Scenarios.jsx` | The declarative table of failure cases, each with the status it expects |
| `src/components/{Products,Cart,Orders}.jsx` | One panel per feature, mirroring the backend's three packages |
| `src/components/{Json,StatusBadge}.jsx` | Shared leaves |
| `src/format.js` | Display formatting only — never arithmetic |

**Never call `fetch` from a component.** Add a method to the `api` object in `client.js` instead, or
the call is invisible in the log and the app has lost its point. `api.raw(method, path, body)` is the
escape hatch for deliberately malformed requests.

**`ApiError` carries `status` and `body`**, where `body` is the backend's `{ status, message }`.
**`status === 0` is special**: the request never left the browser, which in practice means the proxy
could not reach Spring Boot. That is what drives the "backend unreachable" banner, and it is why a
caught error must be checked for status 0 before being shown as an HTTP failure.

The log is a tiny pub/sub in `client.js` — `subscribe()` returns an unsubscribe function, entries are
newest-first and capped at 100 so a long session cannot grow the page without bound.

**After a mutation, refresh everything the transaction touched.** Checkout is the case that catches
people out: `POST /api/orders` reduces stock, writes an order and clears the cart in one transaction,
so the cart, the product list *and* the order list are all stale afterwards.

## Deliberate choices — do not "fix" these

- **The forms do not validate client-side.** Submitting a blank name or a negative price is the
  point: it shows the backend's own 400 and which fields it named. Adding `required` or a
  client-side check would hide exactly what this tool exists to reveal.
- **`<StrictMode>` is off** (`src/main.jsx`, with the reason in a comment). It double-invokes effects
  in development, which would duplicate every startup request in the log.
- **Money is never computed in the browser.** Prices arrive as JSON numbers and are formatted for
  display only. Every total — line, cart, order — comes from the server, which is the behaviour being
  tested. Summing line totals in JS would mask a server-side bug rather than expose it.
- **New failure cases go in the `SCENARIOS` array**, with an `expect` status, not in an ad-hoc
  button. The array is the checklist of the API's documented error contract.
- **Dependencies stay at `react`, `react-dom`, `vite`, `@vitejs/plugin-react`.** Reach for a router,
  a state library or an HTTP client only if the app genuinely outgrows a few hundred lines, and say
  why first.

## Backend contract this UI is built around

Facts about the API that the components assume. Confirm against the running backend rather than
editing Java if one of them seems wrong.

- **One shared cart.** There are no users yet, so the backend keeps a single cart row and every cart
  endpoint operates on it. There is no cart id to pass.
- **Every mutating cart endpoint returns the whole updated cart**, so the response body alone is
  enough to render the new state.
- **Order lines are snapshots.** `productName` and `unitPrice` are frozen at checkout, while
  `Cart.total()` is recomputed from live prices on every read. Changing a price moves the cart and
  leaves placed orders alone — a good thing to demonstrate, and never something to "correct" in the UI.
- **400 means "fix your request"; 409 means "the request is fine, the server's state forbids it"**
  (insufficient stock, empty cart). Both arrive as `{ status, message }` from one handler.
- `DELETE` returns **204 with an empty body** — `client.js` handles that; don't assume JSON.
- `BigDecimal` serialises as a JSON **number**, `Instant` as an **ISO-8601 string**.

## Commits

Conventional Commits, as the repository already uses, with `frontend` as the scope:
`feat(frontend): add an orders filter`. **Never mix backend and frontend changes in one commit** —
the whole point of this folder is that its history can be read, or dropped, on its own.
