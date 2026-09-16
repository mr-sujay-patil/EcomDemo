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

> ***Superseded in Phase 5.*** Flyway now creates the tables and seeds the catalogue itself, exactly
> once per database. There is no `data.sql` any more and no manual seeding step — just start the app.

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

---

## Phase 5 — Database Migrations

**Added:** Flyway. The schema stops being a side effect of the Java code and becomes a set of
versioned files that are reviewed, committed and applied in order — like the rest of the codebase.

- **`V1__init_schema.sql`** — the five tables, taken from a `pg_dump` of the Phase 4 database so it
  reproduces what Hibernate had been generating, plus two things Hibernate would not do: **named
  constraints** (`fk_cart_items_product`, not `fk1re40cjegsfvw58xrkdp6bac6`) and **indexes on the
  foreign key columns**. PostgreSQL indexes the referenced side of a foreign key automatically but
  never the referencing side, so the `join fetch` queries were scanning whole tables.
- **`V2__seed_products.sql`** — replaces `data.sql`, which is deleted. As a migration the catalogue
  is inserted exactly once per database, recorded, and never again.
- **`V3__add_product_category.sql`** — a realistic later change: a nullable `category` column and an
  index on it, mapped onto `Product` and exposed through the product API.
- **`ddl-auto: validate`** — Hibernate no longer touches the schema. It compares the entities against
  what Flyway built and refuses to start if they disagree.

### How it fits together

```
start
  └─ Flyway runs first ──> applies any migration not yet in flyway_schema_history
       └─ Hibernate ─────> validate: do the @Entity classes match these tables?
            └─ app ──────> serves traffic, or fails to start. Never a half-migrated database.
```

### Run it

```bash
./mvnw spring-boot:run
```

That is the whole setup now. Against an empty database Flyway creates the schema and seeds it; there
is no manual seeding step any more.

Starting from scratch — drop the schema and let Flyway rebuild it:

```bash
docker exec ecomdemo-postgres psql -U ecomdemo -d ecomdemo \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
./mvnw spring-boot:run
```

```
Migrating schema "public" to version "1 - init schema"
Migrating schema "public" to version "2 - seed products"
Migrating schema "public" to version "3 - add product category"
Successfully applied 3 migrations to schema "public", now at version v3
```

Start it a second time and Flyway says `Schema "public" is up to date. No migration necessary.`

### Try it

**See what Flyway recorded:**

```bash
docker exec ecomdemo-postgres psql -U ecomdemo -d ecomdemo \
  -c "select installed_rank, version, description, checksum, success from flyway_schema_history order by installed_rank;"
```

```
 installed_rank | version |     description      |  checksum  | success
----------------+---------+----------------------+------------+---------
              1 | 1       | init schema          | 1757385095 | t
              2 | 2       | seed products        |  856564194 | t
              3 | 3       | add product category | 1468050282 | t
```

**Use the column V3 added** — and note that a client that has never heard of `category` still works
unchanged, which is what "backward compatible" means in practice:

```bash
curl -s -XPOST localhost:8080/api/products -H 'Content-Type: application/json' \
     -d '{"name":"Standing Desk","description":"Electric, dual motor","price":549.00,"stockQuantity":12,"category":"Furniture"}' | jq
# {"id":11, ..., "category":"Furniture"}

curl -s -XPOST localhost:8080/api/products -H 'Content-Type: application/json' \
     -d '{"name":"Mouse Pad","description":"Cloth, XL","price":19.99,"stockQuantity":100}' | jq
# {"id":12, ..., "category":null}      <- the old payload, still accepted
```

**Watch a checksum catch you** — the reason you never edit an applied migration:

```bash
echo "-- an innocent-looking comment" >> src/main/resources/db/migration/V1__init_schema.sql
./mvnw spring-boot:run
```

```
Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 1
-> Applied to database : 1757385095
-> Resolved locally    : 919311040
Either revert the changes to the migration, or run repair to update the schema history.
```

The application refuses to start. Undo the edit and it starts again. A comment was enough — Flyway
compares the file's checksum, not its meaning, precisely because it cannot know whether your edit was
cosmetic or changed a column type.

### Adding a migration

```bash
# 1. next number, double underscore, snake_case description
vim src/main/resources/db/migration/V4__add_product_sku.sql

# 2. it applies on the next start
./mvnw spring-boot:run
```

Rules that are not negotiable:

1. **Never edit an applied migration.** Write a new one. The checksum above is why.
2. **Never renumber.** Version order is the order of application, permanently.
3. **Prefer backward-compatible changes** — add nullable columns, do not rename in place. During a
   deploy the old and new versions of the application both run against this one database.

### Tests

```bash
./mvnw clean verify        # 82 tests
```

The suite now runs the **same migrations** against H2, so a broken migration fails the build rather
than a deployment. `FlywayMigrationTest` asserts the history table, the tables and indexes V1
creates, V3's column being nullable, and V2's seed data.

### Known limits of Phase 5

- The migrations are written in SQL that both PostgreSQL and H2 accept, and the tests exercise them
  on H2. Anything PostgreSQL-specific would pass the build and fail on startup. Phase 7
  (Testcontainers) is the fix.
- Nothing rolls a migration back. Flyway Community has no `undo`; the recovery path is a new
  migration that reverses the change.
- `V2__seed_products.sql` means reference data is deployed with the schema. Fine for a demo
  catalogue; real reference data usually wants repeatable migrations (`R__*.sql`) instead.
- Two concurrent orders for the last unit in stock can still both succeed (Phase 6).

---

## Phase 6 — Transactions & Concurrency

**Added:** JPA optimistic locking and Spring's transaction propagation, used deliberately. No new
dependency — `@Retryable` comes from Spring Framework 7 itself.

Phase 0 already made checkout atomic by validating the whole cart before touching anything. This
phase makes it correct when two people do it *at the same time*, which atomicity alone does not.

- **`@Version` on `Product`** — every product `UPDATE` now carries `AND version = ?`. A write built
  on a stale read matches no rows and fails, instead of silently overwriting the other transaction.
- **A bounded retry, outside the transaction** — `@Retryable` on `OrderService.placeOrder`, three
  attempts, then a `409`.
- **`order_audit`, written with `REQUIRES_NEW`** — every attempt is recorded in its own transaction,
  so the record of a failure survives the rollback of the failure.
- **A concurrency test** — two threads, one unit, exactly one order.

### The shape of it

The three annotations have to sit on three different beans, and that is the lesson:

```
OrderController
  └─ OrderService.placeOrder()          @Retryable + @Transactional(NEVER)
       └─ OrderPlacement.placeOnce()    @Transactional          ← one attempt, all-or-nothing
            └─ OrderAuditService.record()  @Transactional(REQUIRES_NEW)  ← survives the rollback
```

Put the retry in the same method as the transaction and there is nothing left to retry — the
transaction is already rollback-only. Put it in a neighbouring method of the *same class* and it
does nothing at all, because Spring's transactions are applied by a proxy that an internal
`this.method()` call never passes through. Separate beans make both mistakes impossible rather than
merely documented.

### Try it — race two checkouts for the last unit

```bash
# a product with exactly one in stock, in the cart
ID=$(curl -s -XPOST localhost:8080/api/products -H 'Content-Type: application/json' \
      -d '{"name":"Last Unit","description":"one only","price":10.00,"stockQuantity":1}' | jq -r .id)
curl -s -XPOST localhost:8080/api/cart/items -H 'Content-Type: application/json' \
      -d "{\"productId\":$ID,\"quantity\":1}" -o /dev/null

# two requests at once
curl -s -XPOST localhost:8080/api/orders -o /dev/null -w '%{http_code}\n' &
curl -s -XPOST localhost:8080/api/orders -o /dev/null -w '%{http_code}\n' &
wait

curl -s localhost:8080/api/products/$ID | jq .stockQuantity     # 0, never -1
```

```
201
409
```

Every time: one order, one conflict, stock `0`. Without `@Version` both would return `201` and the
shop would have sold two of something it had one of.

### Read the audit trail

```bash
docker exec ecomdemo-postgres psql -U ecomdemo -d ecomdemo \
  -c "select id, outcome, left(detail,50) as detail, order_id from order_audit order by id desc limit 6;"
```

```
 id |         outcome         |                      detail                        | order_id
----+-------------------------+----------------------------------------------------+----------
 15 | EMPTY_CART              | Cannot place an order: the cart is empty           |
 14 | CONCURRENT_MODIFICATION | Another transaction changed a product first: Objec |
 13 | PLACED                  | Order placed with 1 line(s), total 10.00          |       10
```

Read bottom-up, that is one race in full: one thread placed the order; the other had its write
rejected by the version check; its retry then found the cart already consumed and returned `409`.

**Two of those three rows are in transactions that rolled back.** The orders and stock changes they
describe do not exist. That is what `REQUIRES_NEW` buys — and deleting it from
`OrderAuditService.record` makes `OrderAuditRollbackTest` fail while the rest of the suite stays
green.

### Tests

```bash
./mvnw clean verify        # 96 tests
```

| Test | Proves |
|---|---|
| `ConcurrentOrderTest` | two threads, one unit, one order — the invariant |
| `OptimisticLockTest` | `@Version` rejects a stale write, deterministically |
| `OrderAuditRollbackTest` | a failed order leaves no data and still leaves an audit row |
| `OrderRetryTest` | the retry, through the real proxy |
| `OrderPlacementTest` | the checkout rules (moved here from `OrderServiceTest`) |

**An honest caveat about the concurrency test.** It asserts the invariant — one order, zero stock —
not *how* the loser lost, because that depends on the interleaving. Both endings are correct and both
pass, which is why `OptimisticLockTest` sits alongside it to pin the lock down deterministically.

> **Correction, made in Phase 7.** This section originally claimed the version conflict "never fired
> once" on H2 across 40 races, and fired every time on PostgreSQL. That was a measurement error, not
> a difference between the engines. The count came from the exception the calling thread finally saw
> — but a thread whose write is rejected by the version check is retried by `@Retryable`, and the
> retry finds the cart already consumed, so a `ConflictException` is what surfaces and the lock is
> invisible from outside. Counted properly, from the `CONCURRENT_MODIFICATION` audit rows, **the lock
> fires on both engines — five times in five rounds on each.**

### Known limits of Phase 6

- Optimistic locking is the right default here — a catalogue is read constantly and written rarely,
  and taking a lock on every read would cost more than the occasional retry. It is the wrong choice
  under heavy contention for the same row, where every attempt fails and retries add load. That case
  wants `@Lock(PESSIMISTIC_WRITE)` (`SELECT ... FOR UPDATE`), which is explained in
  `docs/decisions.md` but deliberately not implemented — nothing here needs it yet.
- Isolation is left at the database default (`READ COMMITTED`). `@Version` is what closes the lost
  update it permits; raising the isolation level instead would be a bigger hammer with a bigger cost.
- The retry delay is fixed with jitter, not exponential backoff. Fine for three attempts.
- The audit table has no retention policy. It grows forever.

---

## Phase 7 — Integration Testing with Real Infrastructure

**Added:** Testcontainers. The integration tests now run against a real PostgreSQL that the build
starts and throws away, instead of H2 imitating one.

- **`AbstractPostgresIT`** — one PostgreSQL 18 container, shared by every integration test in the run.
- **Three feature ITs** — `ProductCrudIT`, `CartFlowIT`, `PlaceOrderIT`, all over HTTP.
- **`ConcurrentOrderIT`** — Phase 6's race, moved onto the real engine.
- **Surefire / Failsafe split** — `*Test.java` are unit and slice tests; `*IT.java` are integration
  tests. `./mvnw test` needs no Docker at all.

### Running them

```bash
./mvnw test        # 94 unit and slice tests, ~7s, no Docker needed
./mvnw verify      # the above, plus 16 integration tests against real PostgreSQL
```

Docker must be running for `verify`. Nothing else is required — no database to install, no port to
free, no cleanup:

```
Creating container for image: postgres:18-alpine
Container postgres:18-alpine started in PT0.988696S
Container is started (JDBC URL: jdbc:postgresql://localhost:52188/test)
Database: jdbc:postgresql://localhost:52188/test (PostgreSQL 18.6)
```

That last line is Flyway. **The migrations are now executed against real PostgreSQL on every build** —
something no test could check before this phase.

### How the container is wired

```java
@ServiceConnection
protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

static { POSTGRES.start(); }
```

Three deliberate details:

- **`static` + a static initialiser** is the *singleton container* pattern. A static field is
  initialised once per JVM, so all four ITs share one container and pay the ~1s startup once. The
  more familiar `@Testcontainers` + `@Container` does the opposite — it hands the lifecycle to JUnit,
  which starts and stops a container per test class.
- **Nothing stops it.** Testcontainers runs a sidecar (Ryuk) that removes the containers when the JVM
  exits, including when it exits badly. A shutdown hook cannot promise that.
- **`@ServiceConnection`** takes the container's random host port and contributes the JDBC URL, user
  and password to the environment before the context refreshes — replacing a hand-written
  `@DynamicPropertySource` block.

The ITs run under the default **`dev` profile** — the same configuration the application uses, with
only the connection details swapped. So `ddl-auto: validate` applies and Flyway builds the schema.

### Testcontainers 2.x has different coordinates

Worth knowing, because every tutorial predates it:

```xml
<!-- Testcontainers 2.x -->
<artifactId>testcontainers-postgresql</artifactId>   <!-- org.testcontainers.postgresql.PostgreSQLContainer -->

<!-- 1.x, what you will find written everywhere -->
<artifactId>postgresql</artifactId>                  <!-- org.testcontainers.containers.PostgreSQLContainer -->
```

`org.testcontainers:postgresql` has no 2.x release at all — its last is 1.21.4. Because Spring Boot
4.1.1 manages Testcontainers 2.0.5, using the familiar coordinate means silently mixing major
versions.

### Tests

| Level | Plugin | Pattern | Database | Docker |
|---|---|---|---|---|
| Unit, web slice, JPA slice | Surefire | `*Test.java` | H2 (or none) | no |
| Integration | Failsafe | `*IT.java` | real PostgreSQL | yes |

H2 stays for the fast tests deliberately: the inner loop stays a few seconds and needs nothing
installed. The ITs are what cover the fidelity gap.

### Known limits of Phase 7

- The slice tests (`@DataJpaTest`) still run on H2, so a PostgreSQL-specific problem in a
  hand-written query would be caught by the ITs, not by them.
- Container reuse between builds (`withReuse(true)`) is not enabled. It would save ~1s per run but
  has to be opted into per developer, and state surviving between runs makes tests order-dependent.
- The ITs share one container and commit as they go, so none of them may assume an empty table.
- No CI runs any of this yet — Phase 11.

---

## Phase 8 — Spring Security (Users & Roles)

**Added:** Spring Security. The shop gets real users, and the shared cart that stood in for them
since Phase 0 is gone.

- **`customer` feature** — register with a BCrypt-hashed password, read your own profile, a `users`
  table (`V6`).
- **Roles** — `CUSTOMER` and `ADMIN`, one per user. The administrator is seeded by the migration.
- **HTTP Basic** authentication, stateless, CSRF disabled.
- **Cart per user** (`V7`) — orders belong to whoever placed them.
- **`@PreAuthorize`** so a customer reaches only their own orders.
- **401 and 403** in the same `{status, message}` shape as every other error.

### Who may do what

| Endpoint | Anonymous | CUSTOMER | ADMIN |
|---|---|---|---|
| `GET /api/products/**` | ✅ | ✅ | ✅ |
| `POST/PUT/DELETE /api/products/**` | 401 | 403 | ✅ |
| `/api/cart/**` | 401 | ✅ | 403 |
| `/api/orders/**` | 401 | ✅ (own only) | 403 |
| `POST /api/customers/register` | ✅ | ✅ | ✅ |
| `GET /api/customers/me` | 401 | ✅ | ✅ |

An administrator gets **403 on the cart**, which is deliberate: a role is a job, not a rank. ADMIN
does not contain CUSTOMER, and an administrator has no cart to look at.

### The seeded administrator

```
email:    admin@ecomdemo.local
password: admin123
```

The BCrypt hash of that password is committed in `V6__create_users.sql`. That is safe and deliberate,
for the same reason the local PostgreSQL password in `application-dev.yml` is: BCrypt is one-way, so
the hash reveals nothing, and the account unlocks a throwaway local database. **A real deployment
changes it.**

### Try it

```bash
# reads are public - no credentials at all
curl -s localhost:8080/api/products | jq

# writes are not
curl -s -XPOST localhost:8080/api/products -H 'Content-Type: application/json' \
     -d '{"name":"X","description":"d","price":1.00,"stockQuantity":1}'
# {"status":401,"message":"Authentication required"}

# ...unless you are the administrator
curl -s -u admin@ecomdemo.local:admin123 -XPOST localhost:8080/api/products \
     -H 'Content-Type: application/json' \
     -d '{"name":"Admin Widget","description":"made by admin","price":25.00,"stockQuantity":10}' | jq

# register a shopper (note: the email is normalised to lower case)
curl -s -XPOST localhost:8080/api/customers/register -H 'Content-Type: application/json' \
     -d '{"email":"Sam@Example.COM","password":"password123","displayName":"Sam"}' | jq
# {"id":2,"email":"sam@example.com","displayName":"Sam","role":"CUSTOMER",...}

# a shopper may not write to the catalogue - 403, not 401: we know exactly who this is
curl -s -u sam@example.com:password123 -XDELETE localhost:8080/api/products/18
# {"status":403,"message":"You do not have permission to perform this action"}

# their cart starts empty and, importantly, uncreated
curl -s -u sam@example.com:password123 localhost:8080/api/cart | jq
# {"cartId":null,"items":[],"totalItems":0,"total":0.00}

# shop and check out as usual, with credentials on every request
curl -s -u sam@example.com:password123 -XPOST localhost:8080/api/cart/items \
     -H 'Content-Type: application/json' -d '{"productId":18,"quantity":2}' | jq
curl -s -u sam@example.com:password123 -XPOST localhost:8080/api/orders | jq
```

**Orders are private.** Register a second shopper and try to read the first one's order:

```bash
curl -s -u riley@example.com:password123 localhost:8080/api/orders/11
# {"status":404,"message":"Order 11 not found"}
```

**404, not 403.** 403 would confirm that order 11 exists, which turns sequential ids into a way to
count the shop's orders and probe which are real. As far as Riley is concerned it does not exist —
and the query is scoped, so the row is never even loaded.

### Tests

```bash
./mvnw clean verify        # 118 unit + 16 integration
./mvnw test -Dtest='SecurityRulesTest*'
```

`SecurityRulesTest` is the authorization matrix: for every rule, who is allowed and who is refused,
using `@WithMockUser` and a `@WithMockCustomer` variant that carries a customer id. It is separate
from the controller tests on purpose — those ask whether an endpoint *behaves* correctly for someone
entitled to call it; this asks *who is entitled*. A broken rule does not make an endpoint wrong, it
makes it available to the wrong people, and every other test in the suite would still pass.

### Known limits of Phase 8

- **HTTP Basic sends credentials on every request**, base64-encoded — which is encoding, not
  encryption. Fine over localhost; over the open internet it demands TLS without exception. Phase 9
  replaces it with JWT.
- **One role per user.** A second role would mean a `user_roles` join table and a migration.
- **Orders placed before this phase have no owner** and are invisible to every customer. They were
  kept rather than deleted; `customer_id` is nullable precisely so that history survived.
- There is no password reset, no email verification, no account lockout and no rate limiting on
  login — each is a real requirement for a real shop, and none belongs to this phase.
- The administrator cannot see other people's orders either. An admin view would need its own
  endpoint and its own rule.

---

## Phase 9 — Stateless Authentication with JWT

**Added:** JWT via Spring Security's OAuth2 Resource Server. HTTP Basic is gone: credentials are sent
**once**, to `POST /api/auth/login`, and everything afterwards carries a signed token.

- **`POST /api/auth/login`** returns an HS256 token with the role as a claim and a 15-minute expiry.
- **Resource server** validates the signature and the expiry on every request — no session, no
  database read.
- **Signing key from `JWT_SECRET`**; nothing committed.
- The principal is still a `SecurityUser`, so every controller and `@PreAuthorize` from Phase 8 is
  untouched.

### Log in and use the token

```bash
TOKEN=$(curl -s -XPOST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@ecomdemo.local","password":"admin123"}' | jq -r .accessToken)

curl -s -H "Authorization: Bearer $TOKEN" -XPOST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Token Widget","description":"created with a bearer token","price":12.00,"stockQuantity":3}' | jq
```

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJlY29tZGVtbyIsInN1YiI6IjEiLC...",
  "tokenType": "Bearer",
  "expiresInSeconds": 900 }
```

### Look inside the token

A JWT is three base64url segments. **Decode the middle one with no key at all:**

```bash
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq
```

```json
{
  "iss": "ecomdemo",
  "sub": "1",
  "role": "ADMIN",
  "exp": 1789560584,
  "iat": 1789559684,
  "email": "admin@ecomdemo.local"
}
```

That is the whole point to internalise: **a JWT is signed, not encrypted.** Anyone holding it can
read every claim. The signature does not hide the payload — it makes altering it detectable. Put
nothing in a token you would not print on a postcard.

### Watch a bad token get refused

```bash
curl -s localhost:8080/api/cart                                     # no token
# {"status":401,"message":"Authentication required"}

# flip one character of the payload and the signature no longer covers it
TAMPERED=$(python3 -c "
t='$TOKEN'.split('.'); s=t[1]; t[1]=s[:-1]+('B' if s[-1]=='A' else 'A'); print('.'.join(t))")
curl -s -H "Authorization: Bearer $TAMPERED" localhost:8080/api/cart
# {"status":401,"message":"Authentication required"}

curl -s -XPOST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"email":"admin@ecomdemo.local","password":"wrong"}'
# {"status":401,"message":"Invalid email or password"}
```

The last message says neither whether the account exists nor which half was wrong — otherwise the
endpoint becomes a way to discover registered email addresses.

### The signing key

```bash
export JWT_SECRET=$(openssl rand -base64 48)
./mvnw spring-boot:run
```

**Nothing is committed.** With `JWT_SECRET` unset the application generates a random key at startup
and warns:

```
JWT_SECRET is not set - generated an ephemeral signing key. Tokens will stop working when this
application restarts, and other instances will reject them. Set JWT_SECRET for anything beyond a
single local process.
```

A fresh clone therefore works with no setup, and restarting invalidates every token — which is worth
seeing once, because it makes concrete that **the key is the trust anchor**. A key shorter than 256
bits fails at startup with a message telling you how to generate one, rather than at the first login.

### Tests

```bash
./mvnw clean verify        # 124 unit + 28 integration
./mvnw test -Dtest='TokenServiceTest'
```

`JwtAuthenticationIT` covers the lifecycle end to end; its valuable half is the refusals — no token,
tampered payload, a token signed with someone else's key, an expired one, and gibberish.

### Known limits of Phase 9

- **A token cannot be revoked.** There is no server-side record to delete — that absence *is* the
  statelessness. Demote a user and their existing token keeps its old role until it expires. The
  15-minute expiry is what bounds that window; the phase's `docs/decisions.md` explains why a refresh
  token was not added.
- **HMAC means anyone who can verify can also forge.** Fine while one application does both; it stops
  working the moment a second service needs to check tokens (Phase 20), which is what RS256 and an
  external issuer solve.
- **Swagger UI is not configured for bearer tokens** — there is no Swagger UI yet. Phase 3 (OpenAPI)
  is still open, and should add the `bearerAuth` security scheme when it lands.
- Tokens still travel unprotected over plain HTTP locally. Over the open internet this demands TLS
  without exception: a bearer token is exactly as sensitive as the password it replaced.

---

## Phase 10 — Containerization

**Added:** Docker and Docker Compose. The whole system — application and database — starts with one
command, and the image is built the same way on every machine.

- **Multi-stage `Dockerfile`** — build with the project's own `./mvnw` on a JDK, explode the layered
  jar, ship only the layers on a slim Alpine JRE.
- **Runs as a non-root user**, with `-XX:MaxRAMPercentage` so the JVM uses its container's memory.
- **`compose.yaml`** — app + PostgreSQL, health checks, a named volume, and environment variables.
- **`.env.example`**; `.env` stays gitignored.

### Start everything

```bash
cp .env.example .env
# edit .env: set POSTGRES_PASSWORD, and JWT_SECRET to `openssl rand -base64 48`

docker compose up -d --build
docker compose ps
```

```
SERVICE    STATUS                    PORTS
app        Up 17 seconds (healthy)   0.0.0.0:8080->8080/tcp
postgres   Up 22 seconds (healthy)   5432/tcp
```

Note `postgres` has **no host binding**. The application reaches it over the compose network; your
laptop cannot. Add `ports: ["5432:5432"]` to the postgres service if you want DBeaver.

### The curl flow, against the container

```bash
curl -s localhost:8080/api/products | jq length                       # 10 - Flyway ran inside the container

TOKEN=$(curl -s -XPOST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@ecomdemo.local","password":"admin123"}' | jq -r .accessToken)

curl -s -H "Authorization: Bearer $TOKEN" -XPOST localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Container Widget","description":"made inside a container","price":33.00,"stockQuantity":9}' | jq

curl -s -XPOST localhost:8080/api/customers/register -H 'Content-Type: application/json' \
  -d '{"email":"sam@example.com","password":"password123","displayName":"Sam"}' | jq
```

Everything Phases 4–9 added works unchanged: migrations, JWT, roles, per-user carts.

### Data survives `down`

```bash
docker compose down        # containers and network destroyed; the volume is not
docker compose up -d
curl -s localhost:8080/api/products/11 | jq     # still there, with its reduced stock
```

`docker compose down -v` is the one that removes the volume, and that is the only way to lose the
data. The database lives in a **named volume**, not in the container's writable layer — which would
be deleted along with the container every time.

### Image size

Measured on this project (arm64, uncompressed as Docker reports it):

| | Size |
|---|---|
| Build stage (JDK + Maven cache + sources) | **1.19 GB** |
| Final runtime image | **399 MB** |

The build stage is thrown away. That is what "multi-stage" buys: the compiler, the Maven repository
and the 59 MB fat jar never reach the thing you deploy.

Inside that image, the layers are split by how often they change:

```
59 MB    COPY /extracted/dependencies/        ← changes when pom.xml changes
696 kB   COPY /extracted/spring-boot-loader/
4 kB     COPY /extracted/snapshot-dependencies/
426 kB   COPY /extracted/application/         ← changes on every commit
```

Editing one Java file rebuilds and pushes the **426 kB** layer. Without layering it would be a new
59 MB layer every time.

### Tests

```bash
./mvnw clean verify        # 139 unit + 28 integration
./mvnw test -Dtest='ContainerConfigurationTest*'
```

`ContainerConfigurationTest` pins what `docker compose up` cannot tell you: that the container is
non-root, that the jar is still layered, that the app waits for the database to be *healthy*, and
that no secret is a literal. Every one of those regressions produces a stack that starts and works.

### Known limits of Phase 10

- **The image is built from the host's source tree, not from a tagged commit.** Reproducibility stops
  at "same Dockerfile, same base image digest"; the base images are pinned by tag, not digest, so
  `21-jre-alpine` can change under you. Digest pinning is the next step.
- **No registry.** The image exists only on the machine that built it. Pushing it somewhere is part
  of CI in Phase 11.
- **The health check probes `/api/products`**, a business endpoint doing an ops job, because
  Actuator is Phase 15.
- `docker compose up --build` rebuilds from scratch when `pom.xml` changes, which takes a couple of
  minutes. That is the dependency layer doing its job, not a fault.
- The compose stack and the standalone `ecomdemo-postgres` container from Phase 4 are **separate
  databases** with separate volumes. Data does not move between them.

---

## Phase 11 — Continuous Integration

**Added:** GitHub Actions. Every pull request is built and tested automatically, and every merge to
`main` publishes a container image.

- **`.github/workflows/ci.yml`** — a `verify` job on PRs and on `main`, and a `publish` job that
  depends on it and runs only on `main`.
- **Branch protection** — `Build and test` is a required status check, so a red build cannot merge.
- **Test reports** uploaded as artifacts, including when the build fails.
- **`.github/dependabot.yml`** — weekly Maven and GitHub Actions updates.

### What runs, and when

| Trigger | `verify` | `publish` |
|---|---|---|
| Pull request → `main` | ✅ | skipped |
| Push to `main` (a merge) | ✅ | ✅ → GHCR |

Both triggers are deliberate. A squash-merge produces a commit that **no PR run ever tested**,
because `main` can hold a combination neither branch had.

### Seeing it work

```bash
gh run list --limit 5
gh run view --log-failed          # just the failing step
gh run download <run-id>          # the test reports
```

The published image:

```bash
docker pull ghcr.io/mr-sujay-patil/ecomdemo:latest
docker pull ghcr.io/mr-sujay-patil/ecomdemo:<commit-sha>
```

Two tags, answering different questions. **The SHA tag is immutable** and identifies exactly one
commit — it is what a deployment should reference and what a rollback needs. **`latest` is mutable**
and always moves: convenient for a human, useless for anything repeatable, because two machines
pulling it an hour apart can run different code.

### The gate, demonstrated

A throwaway PR containing one failing assertion produced:

```
Build and test .......... FAILURE
Publish image to GHCR ... SKIPPED
mergeStateStatus ........ BLOCKED

$ gh pr merge 11 --squash
X Pull request #11 is not mergeable: the base branch policy prohibits the merge.
```

That is the whole point of the phase: the check is not advisory.

### Two traps worth knowing

**A workflow runs from the PR's head branch, not from `main`.** The first attempt at the demo above
branched from `main`, which did not yet have `ci.yml` — so *no checks ran at all*, and the PR was
blocked for a different reason (a required check that never reported). A workflow only starts
guarding pull requests once it is on the branch they come from.

**GHCR rejects uppercase image names.** `ghcr.io/${{ github.repository }}` yields
`ghcr.io/mr-sujay-patil/EcomDemo` and fails on every push; the workflow lower-cases it in its own
step.

### Dependabot

Weekly PRs for Maven and for the actions themselves, with the Spring family grouped into one — they
are released together and only make sense together. Each one goes through the same CI, so a bump that
breaks a test arrives as a red pull request rather than as a surprise months later.

### Known limits of Phase 11

- **CI, not CD.** The image is published, not deployed anywhere. Deployment is Phases 25–26.
- **No coverage or static analysis gate** — SonarQube is Phase 12; CI currently only asks "do the
  tests pass".
- **Actions are pinned to major tags** (`@v4`), which are mutable. Pinning to a commit SHA is
  stricter; Dependabot is what keeps the tags honest in the meantime.
- **The runner is `ubuntu-latest`**, also a moving target by design.
- No caching of the Testcontainers PostgreSQL image, so each run pulls it.

---

## Phase 12 — Code Quality

**Added:** JaCoCo for coverage and SonarQube Community Edition for static analysis, with a quality
gate that fails the build.

### Results

| Metric | Value | Rating |
|---|---|---|
| Lines of code | 1,758 | |
| Coverage | **92.8%** (line 94.5%, branch 63.3%) | |
| Duplication | 0.0% | |
| Bugs | **0** | Reliability **A** |
| Vulnerabilities | **0** | Security **A** |
| Security hotspots | 0 | |
| Code smells | **0** | Maintainability **A** |
| Technical debt | **0 min** | |
| Complexity | cyclomatic 205, cognitive 17 | |

**Quality gate: PASSED** — all five conditions evaluated:

```
[ ok ] new_coverage                 actual=100.0   LT 70
[ ok ] new_duplicated_lines_density actual=  0.0   GT 3
[ ok ] new_blocker_violations       actual=    0   GT 0
[ ok ] new_critical_violations      actual=    0   GT 0
[ ok ] new_violations               actual=    0   GT 0
```

It started at 14 bugs, 1 vulnerability and 27 code smells with 130 minutes of debt.

### Running it

```bash
docker compose -f compose.sonar.yaml up -d      # SonarQube + its own PostgreSQL, ~60s to start
./mvnw clean verify                             # produces the merged JaCoCo report
./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token>

open http://localhost:9000                      # admin / the password you set on first login
docker compose -f compose.sonar.yaml down       # add -v to discard the analysis history
```

`verify` must run first: **Sonar does not measure coverage**, it reads a report JaCoCo produced. Run
`sonar:sonar` on its own and it reports 0% rather than an error.

Coverage locally, without SonarQube at all:

```bash
./mvnw clean verify && open target/site/jacoco-merged/index.html
```

### The quality gate

`sonar.qualitygate.wait=true` means the scanner polls after uploading and **fails the Maven build**
if the gate is red — it is not a dashboard nobody opens. It only applies to `sonar:sonar`, so
`./mvnw verify` needs no server.

That gate genuinely failed mid-phase (`new_violations: 1`) and failed the build with it, which is the
only evidence that it enforces anything.

### What SonarQube found, and what I did about it

| Finding | Count | Action |
|---|---|---|
| `S2119` `SecureRandom` constructed per call | 1 bug | **Fixed** — hoisted to a static field |
| `S8491` dangling Javadoc | 2 smells | **Fixed** — and both were real documentation bugs (below) |
| `S1130`/`S112` `throws Exception` that cannot be thrown | 2 smells | **Fixed** — verified by removing it and compiling |
| `S6213` method named `record` | 1 smell | **Fixed** — renamed to `recordAttempt()` |
| `S5778` ambiguous `assertThatThrownBy` lambdas | 7 smells | **Fixed** — arguments hoisted out of the lambda |
| `S1128` unused imports, `S5838`, `S5853` chains | 15 smells | **Fixed** |
| `S5845` "dissimilar types" in assertions | 13 bugs | **False positive** — proven, marked in SonarQube |
| `S4502` CSRF disabled | 1 vulnerability | **Accepted** — with the Phase 8 justification recorded on the issue |

**The two documentation bugs are the ones worth noticing.** Sonar flagged two Javadoc blocks stacked
on one method — leftovers from my own script-driven edits in earlier phases. In
`SecurityConfiguration` the effect was worse than duplication: a Phase 9 edit had inserted
`authenticationManager` *between* the `passwordEncoder` Javadoc and its method, so one method carried
documentation describing the other and `passwordEncoder` had none. No test could ever have caught
that.

**The 13 "critical bugs" were not bugs.** `S5845` flags `assertThat(x).isEqualTo(2)` inside
`MockMvcTester`'s `hasPathSatisfying`, where Sonar sees `Object` compared to `int`. AssertJ's
`AssertProvider` overload actually resolves to a JSON-value assert. Rather than trust either the tool
or myself, I changed an expected value and ran the test:

```
expected: 999
 but was: 2
```

The assertion is live. They are marked false positive in SonarQube **with that evidence in the
comment**, not silently suppressed in code.

### Known limits of Phase 12

- **The first analysis passed the gate vacuously.** With no previous analysis there is no "new code",
  so every new-code condition had nothing to measure and the gate reported OK having evaluated
  *nothing*. Only the second analysis was a real verdict. Worth knowing before trusting a green gate
  on a brand-new project.
- **Branch coverage is 63.3%** against 94.5% line coverage. Lines are easy; branches are where
  untested paths hide. There is no gate condition on it.
- **SonarQube is not in CI.** Analysis is a local command someone must remember to run. SonarCloud
  with PR decoration is the fix, and needs an account plus a `SONAR_TOKEN` secret.
- **The analysis token is not committed** and cannot be — regenerate it from the UI if you rebuild
  the SonarQube volume.
- The `sonarqube:lts-community` tag is the 9.9 line, whose schema migrations **fail on PostgreSQL 18**.
  `compose.sonar.yaml` uses current CE with PostgreSQL 16 for that reason.
