# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**EcomDemo** is a learning project: an e-commerce backend that grows from a single Spring Boot
monolith into a production-grade distributed system, adding **exactly one** technology per phase.

The purpose is understanding, not shipping a product. That constraint drives everything below — code
is written to be read and explained, and a phase never "sneaks in" a technology that belongs to a
later one.

- **Roadmap (all 31 phases) and the Progress Tracker that says which are done:**
  [`docs/ROADMAP.md`](docs/ROADMAP.md) — check the tracker before starting work; it is the source of
  truth for which phase is next.
- **Decision log (why things are the way they are):** [`docs/decisions.md`](docs/decisions.md) — read
  the entries for the current phase before changing anything they cover.

## Stack

| | |
|---|---|
| Language | Java 21 (the build targets `release 21` regardless of the local JDK) |
| Framework | Spring Boot 4.1.1 |
| Build | Maven, via the committed wrapper — always `./mvnw`, never a system `mvn` |
| Database | PostgreSQL 16+ when the app runs (`dev` profile); H2 in-memory when the tests run (`test` profile) |
| Schema | Owned by Flyway (`src/main/resources/db/migration`). Hibernate only validates |
| Security | Spring Security, HTTP Basic, stateless. Roles `CUSTOMER` and `ADMIN` |

## Build commands

```bash
./mvnw clean verify       # full build + tests - run this before every commit
./mvnw test               # tests only
./mvnw spring-boot:run    # start on http://localhost:8080
```

Running a subset:

```bash
./mvnw test -Dtest=CartServiceTest                  # one class, nested groups included
./mvnw test -Dtest='CartServiceTest$AddItem'        # one @Nested group - note the $
./mvnw test -Dtest='CartServiceTest*#addItem_*'     # one method or pattern
./mvnw test -Dtest='*ServiceTest'                   # the unit layer
./mvnw test -Dtest='*ControllerTest'                # the HTTP contract
./mvnw test -Dtest='*RepositoryTest'                # the hand-written JPQL
```

The `*` before `#` is not optional: service tests group their cases in `@Nested` classes, and
`-Dtest='CartServiceTest#addItem_*'` silently matches **zero** tests and still reports BUILD SUCCESS.

`spring-boot:run` needs a PostgreSQL on `localhost:5432` (database, user and password all
`ecomdemo`, or override `POSTGRES_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD`); the README has the
`docker run` line. **`./mvnw test` and `clean verify` need nothing installed** — they run on H2.

The catalogue is no longer seeded on start. Against a persistent database, seed it once by hand:

```bash
docker exec -i ecomdemo-postgres psql -U ecomdemo -d ecomdemo < src/main/resources/data.sql
```

There is no H2 console any more — `spring-boot-h2console` was removed in Phase 4. Read the schema
with `psql`, DBeaver or pgAdmin.

Do not stop a running `spring-boot:run` by deleting `target/` — `clean` pulls the classes out from
under the running JVM. Stop the process first.

## Architecture

Three feature packages — `product`, `cart`, `order` — plus `common`. A request goes
**Controller → Service → Repository** and never sideways or backwards.

**Cross-feature calls go service → service, never into another feature's repository.** There are
exactly two such seams, and both are deliberate:

- `ProductService.requireEntity(id)` — the catalogue lookup that throws `NotFoundException` rather
  than returning an `Optional`, so no caller handles the empty case.
- `CartService.requireCart()` — returns the `Cart` **entity**, the one documented exception to
  "entities never leave the service layer". `OrderService` needs the live managed entity so that
  clearing the cart participates in the checkout transaction.

A service test therefore mocks the collaborating *service* (`ProductService`, `CartService`), never
that service's repository.

**The cart is a single shared row.** There are no users until Phase 8, so `Cart.SHARED_CART_ID = 1L`
is the id every cart endpoint operates on. `data.sql` seeds it for the test profile; in `dev`
nothing seeds it and `requireCart()` simply recreates the row when it is missing.

**Cart totals are derived, order totals are stored.** `Cart.total()` recomputes from the live
`Product` prices on every read — a cart must show today's price. `Order` stores `totalAmount`, and
`OrderItem` snapshots `productName` and `unitPrice` at checkout, because an order is a historical
record: repricing or deleting a product must not rewrite what a customer already paid. The order's
`product` association is kept only for traceability and is never used to render a line.

**Checkout validates the whole cart before mutating anything.** `OrderService.placeOrder()` loops
twice — once to check stock for every line, once to reduce stock and build the order. With
`@Transactional`, a five-line order whose fourth line is short changes nothing at all. Tests pin this
by asserting the *first* line's stock is untouched after the failure.

**Updates rely on Hibernate dirty checking, not `save()`.** Inside a `@Transactional` service method
an entity loaded from a repository is managed, so mutating it is enough; `ProductService.update`,
the stock decrement and `cart.clear()` all work this way. `verify(repo, never()).save(any())` is how
tests keep that from silently regressing into an explicit save.

**Time comes from an injected `Clock` bean** (`common/ClockConfiguration`), never `Instant.now()`, so
a test can substitute `Clock.fixed(...)`.

**Configuration is split by profile.** `application.yml` holds only what every profile shares
(`open-in-view: false`, SQL logging, `hibernate.jdbc.time_zone: UTC`, error handling) and names
`dev` as the default, so `spring-boot:run` needs no flag. `application-dev.yml` holds the PostgreSQL
datasource — credentials from `POSTGRES_*` environment variables with throwaway local defaults — and
an explicitly written-out HikariCP pool. `application-test.yml` holds the H2 datasource. Anything
database-specific belongs in a profile file, never in `application.yml`.

**Schema and data lifecycle differ per profile.** Hibernate still generates the schema from the
`@Entity` classes, but `dev` uses `ddl-auto: update` with `spring.sql.init.mode: never` — the
database survives restarts, so re-running `data.sql` on every boot would add ten more products each
time. `test` uses `ddl-auto: create-drop` with `defer-datasource-initialization: true` and
`sql.init.mode: always`, giving every run a fresh schema and a freshly seeded catalogue. `update`
cannot rename or drop a column and never reports what it did; Flyway replaces it in Phase 5.

**Spring Boot 4 splits auto-configuration into per-technology modules.** Putting a library on the
classpath no longer configures it: the test slices need `spring-boot-webmvc-test` /
`spring-boot-data-jpa-test`, and the H2 console needed `spring-boot-h2console` before Phase 4
dropped it. When a technology seems not to auto-configure, look for its missing module before
assuming a config error. Adding such a module is not "a new technology" for the
one-technology-per-phase rule.

## Database migrations

**Flyway owns the schema; Hibernate only checks it.** `ddl-auto` is `validate` in every profile. A
mapping added without a matching migration fails at startup — loudly, and before the first query
rather than during it.

**Migrations live in `src/main/resources/db/migration`**, named `V<n>__snake_case_description.sql`.
The version number is the order of application, permanently.

**Three rules that are not negotiable:**

1. **Never edit an applied migration.** Flyway stores a checksum of every file it has run and
   compares it on each start; a changed file — a comment is enough — stops the application with
   `Migration checksum mismatch`. Fix forward with a new migration instead.
2. **Never renumber or reuse a version.**
3. **Prefer backward-compatible changes.** Add nullable columns rather than renaming in place. During
   a rolling deploy the old and new versions of the application share one database, so a migration
   must leave the code that has not been deployed yet working. Tighten later, in a separate migration
   (expand, then contract) — never on the same deploy.

**Name your constraints and index your foreign keys.** Both are things Hibernate's generated DDL
would not do: `fk_cart_items_product` is a name you can act on when it appears in an error, and
PostgreSQL never indexes the referencing side of a foreign key on its own.

**The test suite runs the same migrations** against H2 in `MODE=PostgreSQL`, so a broken migration
fails `./mvnw clean verify`. That also means migration SQL must be portable — anything
PostgreSQL-specific passes the build and fails on startup, until Testcontainers arrives in Phase 7.

## Code conventions

**Package by feature, not by layer.** `com.ecomdemo.<feature>` holds that feature's controller,
service, repository, entity and `dto/` package. There are no top-level `controller/` or `service/`
packages. `common` holds only what genuinely crosses features.

**Controller → Service → Repository, strictly one direction.**

- *Controller* — map URLs, trigger validation with `@Valid`, choose a status code. Nothing else. It
  never touches a repository.
- *Service* — all business rules, the `@Transactional` boundary, and entity → DTO mapping. It knows
  nothing about HTTP.
- *Repository* — a Spring Data interface. Add a method only when derived queries or `@Query` are
  genuinely needed.

**Entities never leave the service layer.** Every request and response body is a Java `record` in
the feature's `dto/` package, with a `static from(Entity)` factory on response records. This keeps
the JSON contract independent of the schema, stops clients setting fields they should not own (a
request record has no `id`), and — because `open-in-view` is disabled — prevents lazy loading during
serialisation.

**Money is always `BigDecimal`**, never `double`. Columns are `@Column(precision = 12, scale = 2)`;
values are normalised with `setScale(2, RoundingMode.HALF_UP)`. In tests compare with AssertJ's
`isEqualByComparingTo`, not `isEqualTo` — `BigDecimal.equals` also compares scale.

**Errors go through `common/GlobalExceptionHandler`.** Every failure returns
`{ "status": ..., "message": ... }`. Throw `NotFoundException` (→ 404) or `ConflictException` (→ 409)
from a service; validation and binding failures become 400 automatically. No controller should
contain a try/catch, and no service should mention HTTP. 400 means "fix your request"; 409 means
"your request is fine, but the server's state forbids it".

**Explicit fetching.** `@ManyToOne` is always marked `FetchType.LAZY` (its default is EAGER). When a
query needs an association, load it with `left join fetch` rather than relying on lazy loading —
`spring.jpa.open-in-view` is `false`, so a lazy access outside the transaction fails loudly.

**Entities keep both sides of an association in step** and own their own invariants —
`Cart.addOrIncrease`, `Order.addItem` (which recalculates the total rather than accumulating it),
`CartItem.detachFromCart`. Business rules that belong to one entity live on it, not in the service.

**Comments explain *why*, not *what*.** The code says what it does; a comment earns its place by
recording a trade-off or a non-obvious constraint.

## Security

**Every endpoint is denied by default.** `WebSecurityConfiguration` lists what is public; adding an
endpoint cannot accidentally publish it. Rules are matched in order, so the specific ones come first.

**Controllers take the principal, services take an id.** `@AuthenticationPrincipal SecurityUser` in
the controller, a `Long customerId` parameter into the service. Never read `SecurityContextHolder`
inside a service — it makes the service silently require a logged-in user and untestable without a
security context, the same way reading `HttpServletRequest` there would.

**Scope the query, don't filter afterwards.** A customer's data is fetched with the owner in the
WHERE clause (`findByIdAndCustomerWithItems`), so another customer's row is never loaded. Asking for
someone else's resource returns **404, not 403** — 403 confirms it exists, which turns sequential ids
into an enumeration tool. `@PreAuthorize("#customerId == authentication.principal.id")` goes on top
as a second line of defence.

**The security layer and the entity layer stay apart.** `SecurityUser` adapts `Customer` to
`UserDetails` and carries the id; the entity implements no framework interfaces, and the `ROLE_`
prefix is added in code rather than stored in the database.

**Passwords are hashed with the injected `PasswordEncoder`, never compared directly.** The algorithm
is chosen in one place. Test it with a real encoder, not a mock — a mock lets "it is hashed" pass
even if the hash is the password.

**Testing security:**

- `@WithMockUser(roles = "ADMIN")` when only the role matters; `@WithMockCustomer(id = …)` when the
  controller needs a customer id from the principal.
- A `@WebMvcTest` must `@Import({WebSecurityConfiguration.class, ApiErrorResponder.class,
  SecurityMockMvcCustomizer.class})` — a slice loads controllers, not `@Configuration`, so without it
  the real rules never load and `@AuthenticationPrincipal` is not even resolved.
- `SecurityMockMvcCustomizer` applies `springSecurity()` to the MockMvc builder. The chain is
  stateless, so without it the test's `SecurityContext` is discarded per request and every
  authenticated test returns 401.
- Tests calling services directly need `TestSecurity.actAs(...)` for `@PreAuthorize` to evaluate —
  including inside any worker thread, since `SecurityContextHolder` is thread-local.

## Transactions and concurrency

**The service layer owns the transaction boundary.** Class-level `@Transactional(readOnly = true)`,
overridden with `@Transactional` on the methods that write. A class-level annotation applies to
*every* method, so a method that must not be transactional has to opt out explicitly
(`Propagation.NEVER` on `OrderService.placeOrder`).

**Self-invocation does not work, and fails silently.** `@Transactional`, `@Retryable` and friends are
applied by a proxy. Calling `this.otherMethod()` bypasses it entirely: the annotation is ignored, the
code runs anyway, and nothing is logged. When two annotations must not share a boundary — a retry
around a transaction, an audit write that must outlive a rollback — put them on **separate beans**.
That is why `OrderService`, `OrderPlacement` and `OrderAuditService` are three classes.

**Rollback rules.** Spring rolls back on `RuntimeException` and `Error`, and *commits* on a checked
exception unless you ask otherwise. Every exception this codebase throws from a service
(`NotFoundException`, `ConflictException`) is unchecked, so the default is the one we want — but a
checked exception added later would need `@Transactional(rollbackFor = ...)`.

**Writes that race take `@Version`.** `Product` carries one. Optimistic locking suits data that is
read often and written rarely: it takes no locks and costs nothing until a collision actually
happens, at which point the loser is told to redo the work. Retry it a bounded number of times
outside the transaction, then answer 409. `@Lock(PESSIMISTIC_WRITE)` is the tool for the opposite
shape — contention as the norm rather than the exception — and nothing here needs it yet.

**A test that commits rows needs its own database.** The `test` profile's H2 lives for the whole JVM
and is shared by every test class, so a non-transactional `@SpringBootTest` that commits will leak
into other tests' assertions. Give it a distinct `spring.datasource.url` via `@TestPropertySource`.

## Testing conventions

**Naming:** `methodName_condition_expectedResult`. A failure report should read as a sentence.

**Structure:** one `@Nested` class per method under test — or per profile in
`DatasourceProfileTest` — then explicit `// GIVEN`, `// WHEN`, `// THEN` comments. Controller,
repository and flow tests stay flat.

**AssertJ** for every assertion, including controller tests. Compare money with
`isEqualByComparingTo`, never `isEqualTo` — `BigDecimal.equals` compares scale too.

**Pick the cheapest level that can catch the bug:**

| Level | Annotation | Loads | Use for |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | nothing | every service method — success path **and** at least one failure path |
| Web slice | `@WebMvcTest(XController.class)` | controller, Jackson, validation, error advice | status codes, JSON shape, headers, exception → status mapping |
| JPA slice | `@DataJpaTest` | Hibernate, repositories, embedded H2 | hand-written `@Query` only — never Spring Data's generated methods |
| Integration | `@SpringBootTest` | everything, real port | a flow that must cross layers, on H2 |
| **End-to-end** | `*IT.java` extending `AbstractPostgresIT` | everything + a real PostgreSQL container | anything whose correctness depends on the real database |
| Configuration | `ApplicationContextRunner` | one auto-configuration, no server | what the `application-*.yml` files actually bind to (`DatasourceProfileTest`) |

**Anything that builds a datasource must declare `@ActiveProfiles("test")`** — `@DataJpaTest`,
`@SpringBootTest`, and any new slice that touches the database. Without it the test inherits the
default `dev` profile and dials PostgreSQL, so the build fails on every machine with no database
running. `@WebMvcTest` creates no datasource and needs no profile.

**Configuration is testable too.** A typo in a `${PLACEHOLDER}` becomes a literal string and a pool
setting in the wrong profile only shows up under load. `DatasourceProfileTest` binds the real YAML
with `ConfigDataApplicationContextInitializer` and asserts what the container ends up with — no
database is contacted, because HikariCP opens no connection until one is asked for.

**Surefire runs `*Test.java`; Failsafe runs `*IT.java`.** The suffix is the whole routing rule — their
default include patterns already separate them, so a test named `FooIT` runs at `verify` against a
real PostgreSQL container and a test named `FooTest` runs at `test` with no Docker requirement at
all. Never name an integration test `*Test`: it would then run under Surefire, which aborts the build
on first failure and would leave containers behind.

**Integration tests extend `AbstractPostgresIT`.** It holds one container for the whole JVM (a static
field, the singleton pattern), exposes a `RestTestClient` bound to the real port, and lets
`@ServiceConnection` wire the random host port into the context. They run under the default `dev`
profile, so the Flyway migrations and `ddl-auto: validate` are exercised on the real engine.

**An IT may not assume an empty table.** Every IT shares one container and commits as it goes.
Create the rows a test needs, assert on those, and never on counts. If a test needs the shared cart
empty, it empties it in `@BeforeEach`.

**Spring Boot 4 specifics.** `@WebMvcTest` and `@DataJpaTest` come from the separate
`spring-boot-webmvc-test` and `spring-boot-data-jpa-test` modules. `@MockBean` is removed — use
`@MockitoBean`. Controller tests use `MockMvcTester`, not classic `perform(...).andExpect(...)`.
`TestRestTemplate` is no longer on the starter's classpath; the integration test uses
`RestTestClient`.

**Mock at the boundary you own.** A service test mocks the *collaborating service*, not that
service's repository — otherwise a refactor in one feature breaks another feature's tests.

**Prefer a stub to a mock for value-like collaborators.** `Clock.fixed(...)` over `mock(Clock.class)`:
it is a real implementation with known behaviour and needs no stubbing.

**Entities that need an id come from `support/TestFixtures`.** Ids are `@GeneratedValue` with no
setter, so the fixture sets the field reflectively — keeping that compromise in one place. Repository
tests deliberately do *not* use it: there, letting the database assign the id is part of what is
under test.

**Assert absence where absence is the behaviour.** `verify(repo, never()).save(any())` is how the
reliance on Hibernate dirty checking is pinned down. In repository tests, `entityManager.clear()`
then `Persistence.getPersistenceUtil().isLoaded(...)` is how a `join fetch` is pinned down — reading
the values would pass either way.

**A new test should fail before it passes.** If you cannot make it go red by breaking the code it
claims to cover, it is not testing that code.

## Git workflow

**Branches.** `main` is always working and is protected — no direct pushes. Each phase gets
`feature/phase-XX-<short-name>`, lowercase and hyphenated, cut from `main`.

**Conventional Commits.** `<type>(<scope>): <subject>`, subject in the imperative mood, no trailing
period.

| Type | Use for |
|---|---|
| `feat` | New user-facing behaviour |
| `fix` | A bug fix |
| `test` | Adding or correcting tests |
| `docs` | Documentation only |
| `chore` | Build, config, tooling, dependencies |
| `refactor` | Behaviour-preserving restructuring |

Scope is the feature package where there is one: `feat(cart): …`, `fix(order): …`.

**Pull Requests.** Every change to `main` goes through a PR, using
[`.github/pull_request_template.md`](.github/pull_request_template.md). Phase branches are
**squash-merged**, so `main` reads as one commit per phase while the granular commits stay visible in
the PR.

**Tags.** Each finished phase is tagged `phase-XX-complete` (annotated, on the merge commit on
`main`).

## Rules for a phase

1. **One new technology per phase.** No "while we're at it" additions. If something outside the
   current phase seems necessary, say so and leave it unimplemented.
2. `./mvnw clean verify` must pass before the phase is done, with all existing tests still green.
3. Only stable GA versions — never a milestone or snapshot. Check compatibility against the Spring
   Boot version in use; if there is a conflict, stop and explain rather than silently downgrading.
4. **No secrets in source control.** Configuration that would hold one belongs in an environment
   variable, and `.env` is ignored.
5. Every phase updates `README.md`, `docs/decisions.md` and the Progress Tracker in
   `docs/ROADMAP.md`.
