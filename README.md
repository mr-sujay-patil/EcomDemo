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

### H2 console — *superseded in Phase 4*

The application no longer runs on H2; see [Phase 4](#phase-4--postgresql) for connecting to
PostgreSQL. Kept here as a record of what Phase 0 shipped.

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

---

## Phase 2 — Automated Testing

**Added:** JUnit 5 (Jupiter), Mockito and Spring test slices. **No application code changed** — the
only non-test edit is two test-scoped dependencies in `pom.xml`.

**69 tests in about 6 seconds**, arranged as a pyramid:

| Level | What it loads | Tests | Speed |
|---|---|---|---|
| **Unit** — `@ExtendWith(MockitoExtension.class)` | Nothing. Plain objects, mocked collaborators | 35 | ~0.1s total |
| **Slice** — `@WebMvcTest` | Controller, Jackson, validation, the error advice. No DB | 26 | ~0.5s each |
| **Slice** — `@DataJpaTest` | Hibernate, repositories, embedded H2. No web layer | 7 | ~2s each |
| **Integration** — `@SpringBootTest` | Everything, on a real port | 1 | ~0.8s |

Broad at the base, narrow at the top: the many fast tests tell you *what* broke, the single slow one
tells you the pieces still fit together.

### Run them

```bash
./mvnw clean verify              # everything
./mvnw test -Dtest=CartServiceTest       # one class
./mvnw test -Dtest='*ServiceTest'        # just the unit layer
./mvnw test -Dtest='*ControllerTest'     # just the HTTP contract
./mvnw test -Dtest='*RepositoryTest'     # just the hand-written JPQL
```

### Conventions

- **Naming:** `methodName_condition_expectedResult`, so a failure report reads as a sentence —
  `placeOrder_whenOneLineExceedsStock_throwsConflictAndChangesNothingAtAll`.
- **Structure:** explicit `// GIVEN`, `// WHEN`, `// THEN` comments.
- **AssertJ everywhere**, including the controller tests via `MockMvcTester`.
- Money is compared with `isEqualByComparingTo`, never `isEqualTo` — `BigDecimal.equals` also
  compares scale, so `259.98` and `259.980` would not match.

### Two tests worth reading

The suite is designed to fail for a reason, not just to be green. Both of these were verified by
deliberately breaking the production code and watching them go red:

- `CartRepositoryTest` clears the persistence context, then asserts `Persistence.isLoaded(...)` on
  both association levels. Simply *reading* the values would pass either way, because a lazy load
  would quietly satisfy it. This is what stops the `left join fetch` — and with it the N+1 problem —
  regressing unnoticed.
- `OrderServiceTest.placeOrder_whenOneLineExceedsStock_throwsConflictAndChangesNothingAtAll` asserts
  that when line two is out of stock, line one's stock is **still 40** and the cart is untouched.
  Moving the stock check inside the mutation loop fails this test and nothing else — not even the
  full-stack integration test, which is exactly why the pyramid has a base.

---

## Phase 4 — PostgreSQL

**Added:** PostgreSQL, and Spring profiles to go with it. The application now talks to a real
database that keeps your data between runs; the test suite keeps an embedded one so the build still
needs nothing installed.

- **The driver swap** — `org.postgresql:postgresql` at runtime scope, H2 demoted to `test`. The
  `spring-boot-h2console` dependency is gone: it configures a console that only understands H2.
- **Two profiles** — `application-dev.yml` (PostgreSQL) and `application-test.yml` (H2). `dev` is the
  default, so `./mvnw spring-boot:run` needs no extra flag, and tests set `@ActiveProfiles("test")`.
- **Credentials from the environment** — `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, each
  with a local default. Nothing that grants real access is committed.
- **An explicit HikariCP pool** — sizes and lifetimes are written down in `application-dev.yml`
  rather than inherited silently from the library's defaults.
- **`ddl-auto: update`** — Hibernate still generates the schema, but now it *adds* to an existing one
  instead of dropping it. Phase 5 replaces this with Flyway.

### Prerequisites

Java 21, plus a PostgreSQL 16 or newer to talk to. Docker is the quickest route — Docker itself is
studied properly in Phase 10, so treat this as one command to copy:

```bash
docker run --name ecomdemo-postgres \
  -e POSTGRES_DB=ecomdemo \
  -e POSTGRES_USER=ecomdemo \
  -e POSTGRES_PASSWORD=ecomdemo \
  -p 5432:5432 \
  -v ecomdemo-pgdata:/var/lib/postgresql \
  -d postgres:18-alpine
```

The named volume `ecomdemo-pgdata` is what makes the data outlive the container itself.

> **PostgreSQL 18 changed the volume path.** Older recipes mount
> `-v …:/var/lib/postgresql/data`; on 18 the image declares `/var/lib/postgresql` and puts the
> cluster in `/var/lib/postgresql/18/docker`. Mounting the old path appears to work and then quietly
> fails to persist anything. Use the path above, or pin `postgres:17-alpine` and the old path.

Stopping and starting it later:

```bash
docker stop ecomdemo-postgres          # your data stays in the volume
docker start ecomdemo-postgres
```

Prefer a native install? Anything that gives you a database, user and password of `ecomdemo` on
`localhost:5432` works — or point the environment variables somewhere else.

### Run it

```bash
./mvnw spring-boot:run                        # uses the dev profile by default
```

Hibernate creates the five tables on first start. The catalogue is **not** seeded automatically any
more — against a database that survives restarts, re-running `data.sql` every time would add another
ten products on every boot. Seed it once:

```bash
docker exec -i ecomdemo-postgres psql -U ecomdemo -d ecomdemo < src/main/resources/data.sql
```

### Try it — prove the data survives

```bash
# 1. create something through the API
curl -s -XPOST localhost:8080/api/products -H 'Content-Type: application/json' \
     -d '{"name":"Phase 4 Survivor","description":"Created before a restart","price":42.50,"stockQuantity":7}' | jq

# 2. place an order, so stock moves and the cart empties
curl -s -XPOST localhost:8080/api/cart/items -H 'Content-Type: application/json' \
     -d '{"productId":1,"quantity":2}' | jq
curl -s -XPOST localhost:8080/api/orders | jq

# 3. stop the application (Ctrl-C) and start it again
./mvnw spring-boot:run

# 4. everything is still there - this is the whole point of the phase
curl -s localhost:8080/api/products/11 | jq     # the product you created
curl -s localhost:8080/api/orders/1 | jq        # the order you placed
curl -s localhost:8080/api/products/1 | jq      # stock is 38, not back to 40
```

Under Phase 0's H2 this sequence returned `404` after every restart.

### Pointing at a different database

The dev profile reads three environment variables, falling back to the local defaults above:

```bash
POSTGRES_URL=jdbc:postgresql://db.internal:5432/ecomdemo \
POSTGRES_USER=app \
POSTGRES_PASSWORD='...' \
./mvnw spring-boot:run
```

### Connecting with a DB client

[DBeaver](https://dbeaver.io) or [pgAdmin](https://www.pgadmin.org) — the replacement for Phase 0's
H2 console, and the way to read the schema Hibernate generated:

| Field | Value |
|---|---|
| Host / Port | `localhost` / `5432` |
| Database | `ecomdemo` |
| User / Password | `ecomdemo` / `ecomdemo` |

Worth looking at once connected, because it is where PostgreSQL differs visibly from H2:

```sql
\d products      -- id is 'bigint ... generated by default as identity', price is numeric(12,2)
\d orders        -- placed_at is 'timestamp with time zone'
```

Or without a client at all:

```bash
docker exec -it ecomdemo-postgres psql -U ecomdemo -d ecomdemo
```

### Tests

Unchanged in how you run them, and still needing no database:

```bash
./mvnw clean verify        # 74 tests
```

The suite runs under the `test` profile on H2 in `MODE=PostgreSQL`. That keeps the build hermetic —
important for CI in Phase 11 — at the cost of not exercising real PostgreSQL. **Phase 7
(Testcontainers) is what closes that gap**; until then, treat a green build as evidence about your
logic, not about PostgreSQL compatibility.

### Known limits of Phase 4

- `ddl-auto: update` never drops or renames anything and never reports what it did. Rename a field
  and you get a second column beside the old one. Phase 5 (Flyway) replaces it.
- Tests do not run against PostgreSQL (Phase 7).
- The local password is in `application-dev.yml`. That is deliberate — it opens a throwaway local
  database and nothing else — but a real deployment sets the environment variables instead.
- Two concurrent orders for the last unit in stock can still both succeed (Phase 6).
