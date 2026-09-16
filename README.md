# EcomDemo

A learning project that grows an e-commerce backend from a single Spring Boot monolith into a
production-grade distributed system, adding **exactly one** technology per phase.

The full plan lives in [`docs/ROADMAP.md`](docs/ROADMAP.md). Decisions made along the way are
recorded in [`docs/decisions.md`](docs/decisions.md), and the conventions every change follows
are in [`CLAUDE.md`](CLAUDE.md).

**Stack:** Java 21 · Spring Boot 4.1.1 · Maven

---

## Phase 0 — Baseline Monolith

**Added:** Spring Boot (Web, Data JPA, Validation) and an in-memory H2 database.

This phase builds the thing every later phase will modify: a working e-commerce backend with a
product catalogue, a cart and orders, and nothing else in the way.

- **product** — full CRUD over a catalogue of 10 seeded products.
- **cart** — one shared cart (there are no users yet). Add, update and remove items; the total is
  always calculated by the server from the current product prices.
- **order** — place an order from the cart: stock is checked, stock is reduced, the order is saved
  with the product name and price snapshotted onto each line, and the cart is emptied. All in one
  transaction.
- **common** — a single `@RestControllerAdvice` so every failure returns the same
  `{ "status": ..., "message": ... }` body.
- **Layering** — Controller → Service → Repository, with every request and response body a Java
  `record`. Entities never leave the service layer.

### Prerequisites

Java 21 or newer. Maven is not required — the project ships the Maven wrapper.

### Run it

```bash
./mvnw spring-boot:run
```

The app starts on <http://localhost:8080>. The database is recreated and re-seeded on every start,
so nothing persists between runs.

### Try it

```bash
# 1. browse the seeded catalogue
curl -s localhost:8080/api/products | jq

# 2. put two keyboards in the cart
curl -s -XPOST localhost:8080/api/cart/items \
     -H 'Content-Type: application/json' \
     -d '{"productId":1,"quantity":2}' | jq

# 3. view the cart - note the server-calculated total
curl -s localhost:8080/api/cart | jq

# 4. place the order
curl -s -XPOST localhost:8080/api/orders | jq

# 5. read the order back
curl -s localhost:8080/api/orders/1 | jq

# 6. stock has fallen from 40 to 38...
curl -s localhost:8080/api/products/1 | jq

# 7. ...and the cart is empty again
curl -s localhost:8080/api/cart | jq
```

Error handling:

```bash
curl -s localhost:8080/api/products/9999                                   # 404
curl -s -XPOST localhost:8080/api/products -H 'Content-Type: application/json' \
     -d '{"name":"","price":-1}'                                          # 400 + which fields failed
curl -s -XPOST localhost:8080/api/cart/items -H 'Content-Type: application/json' \
     -d '{"productId":1,"quantity":99999}'                                # 409 insufficient stock
curl -s -XPOST localhost:8080/api/orders                                  # 409 on an empty cart
```

### Build and test

```bash
./mvnw clean verify
```

### H2 console

<http://localhost:8080/h2-console> (while the app is running)

| Field | Value |
|---|---|
| JDBC URL | `jdbc:h2:mem:ecomdemo` |
| User Name | `sa` |
| Password | *(blank)* |

### API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/products` | List every product |
| `GET` | `/api/products/{id}` | One product |
| `POST` | `/api/products` | Create a product → `201` |
| `PUT` | `/api/products/{id}` | Replace a product |
| `DELETE` | `/api/products/{id}` | Delete a product → `204` |
| `GET` | `/api/cart` | The shared cart with its total |
| `POST` | `/api/cart/items` | Add a product, or increase its quantity |
| `PUT` | `/api/cart/items/{productId}` | Set the quantity of a line |
| `DELETE` | `/api/cart/items/{productId}` | Remove a line |
| `POST` | `/api/orders` | Place an order from the cart → `201` |
| `GET` | `/api/orders` | List orders, newest first |
| `GET` | `/api/orders/{id}` | One order |

Every mutating cart endpoint returns the whole updated cart, so a client never needs to re-fetch it.

### Status codes

| Code | When |
|---|---|
| `400` | Bean Validation failed, the JSON was unreadable, or a path variable had the wrong type |
| `404` | The product, order or cart line does not exist |
| `409` | The request is valid but the state forbids it — not enough stock, or an empty cart |

### Known limits of Phase 0

Deliberately out of scope, each scheduled for a later phase: there is no security (Phase 8), the
database is in-memory and wiped on restart (Phase 4), and two concurrent orders for the last unit in
stock can both succeed (Phase 6).

---

## Phase 1 — Git & GitHub Workflow

**Added:** Git and GitHub. No application code changed — `pom.xml` and everything under `src/` are
untouched, which is exactly why `./mvnw clean verify` is still green.

This phase puts the project under the workflow every later phase will follow.

- **On GitHub** at [`mr-sujay-patil/EcomDemo`](https://github.com/mr-sujay-patil/EcomDemo), with
  `main` protected — no direct pushes, every change through a Pull Request.
- **Branching:** `main` is always working; each phase gets `feature/phase-XX-<short-name>`.
- **Conventional Commits** (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`), scoped to the
  feature package where there is one.
- **[`CLAUDE.md`](CLAUDE.md)** — the conventions, build commands and workflow in one place.
- **[PR template](.github/pull_request_template.md)** — what changed, how it was tested, and a
  checklist enforcing the roadmap's ground rules.
- **Tags:** each finished phase is tagged `phase-XX-complete`.

### Contributing

```bash
git checkout main && git pull
git checkout -b feature/phase-XX-short-name

# ...work, committing as you go...
git commit -m "feat(cart): add coupon support"

./mvnw clean verify            # must pass before you open the PR
git push -u origin feature/phase-XX-short-name
gh pr create                   # the template is filled in for you
```

Phase branches are **squash-merged**, so `main` reads as one commit per phase while the granular
commits stay visible in the PR. After merging:

```bash
git checkout main && git pull
git tag -a phase-XX-complete -m "Phase XX: ..." && git push origin phase-XX-complete
```

### Browsing history

```bash
git log --oneline --graph          # the phase-by-phase history
git tag -l -n1                     # every completed phase
git show phase-00-complete         # the baseline monolith
```
