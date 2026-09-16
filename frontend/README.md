# EcomDemo API Console

A small React app for exercising the EcomDemo backend by hand — a browser-based alternative to a
long list of `curl` commands.

It is **completely separate from the backend**. Nothing outside this folder was changed to make it
work: no CORS configuration, no new Maven dependency, no note in the backend's docs. It is not a
phase of the roadmap and it deliberately does not appear in one.

## Run it

Two terminals, in either order:

```bash
# terminal 1 - the backend, from the project root (needs PostgreSQL running)
./mvnw spring-boot:run

# terminal 2 - this app
cd frontend
npm install        # first time only
npm run dev
```

Then open <http://localhost:5173>. The header shows whether the backend is reachable.

## How it talks to the backend

The browser only ever calls `/api/*` on `localhost:5173`, and the Vite dev server proxies those
calls to `localhost:8080` (see `vite.config.js`). Because every request is same-origin from the
browser's point of view, **no CORS setup is needed on the Spring Boot side** — which is what keeps
the two codebases independent.

Pointing at a backend somewhere else:

```bash
VITE_API_TARGET=http://localhost:9090 npm run dev
```

## What it does

| Tab | Covers |
|---|---|
| **Products** | `GET/POST/PUT/DELETE /api/products` — full CRUD, plus an "add to cart" on each row |
| **Cart** | `GET /api/cart`, add / set quantity / remove a line, and checkout |
| **Orders** | `GET /api/orders`, expand an order's snapshotted lines, fetch one by id |
| **Error paths** | One button per documented failure: 400, 404 and 409, each asserting the status it expects |

Two things worth knowing about the UI:

- **The request log on the right records every call** — method, path, status, duration, and both
  bodies. It is the reason this app exists; the tables are just a convenient way to trigger calls.
- **The forms do not validate.** Submitting a blank name or a negative price is allowed on purpose,
  so you can see the backend's own 400 and exactly which fields it names.

## Things to try

- Change a product's price, then look at the cart: the total moves, because the server recomputes it
  from live prices. Then place an order and change the price again — the order does not move, because
  its lines snapshot the name and price at checkout.
- Put five lines in the cart, make one of them exceed stock, and check out. The 409 comes back and
  **nothing** changes: not the cart, not the stock on the other four lines.
- Stop the backend and press "Reload all" to see what the app does when nothing answers.

## Scripts

```bash
npm run dev       # dev server on 5173, with the /api proxy
npm run build     # production bundle into dist/
npm run preview   # serve that bundle - note: no proxy, so the API calls will 404
```

`npm run preview` serves static files only. To use a built bundle against a real backend you would
need to serve it from something that proxies `/api` — out of scope for a local testing tool.

## Stack

React 19 and Vite 8. No router, no state library, no UI framework, no HTTP client — one `fetch`
wrapper in `src/api/client.js` and plain CSS in `src/styles.css`.
