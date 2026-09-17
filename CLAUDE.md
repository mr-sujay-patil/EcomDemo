# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**EcomDemo** is a learning project: an e-commerce backend that grows from a single Spring Boot
monolith into a production-grade distributed system, adding **exactly one** technology per phase.

**Since Phase 20 this is a Maven multi-module repository containing five deployable services.** Read
the Services section below before anything else — most of the guidance in this file still applies,
but it now applies *per service*.

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
| Database | PostgreSQL when a service runs (`dev` profile); H2 in-memory when the tests run (`test` profile). **One database per service** |
| Schema | Owned by Flyway (`src/main/resources/db/migration`). Hibernate only validates |
| Running it | `docker compose up -d --build` (five services + PostgreSQL, Redis, Kafka) |
| Security | Spring Security, JWT bearer tokens, stateless. Roles `CUSTOMER` and `ADMIN` |
| Cache | Redis, via Spring's cache abstraction. **catalog-service only**, product reads only |
| Messaging | Apache Kafka (KRaft). order-service publishes, notification-service consumes |

## Services

| Module | Port | Owns | Calls |
|---|---|---|---|
| `shared-kernel` | — | error shape, JWT validation, clock, metric tags. A library, never repackaged | — |
| `services/customer-service` | 8081 | `users`; issues tokens; publishes the JWK set | nothing |
| `services/catalog-service` | 8082 | `products`; the only Redis | nothing |
| `services/inventory-service` | 8083 | `stock_levels`; reservations | nothing |
| `services/order-service` | 8084 | `carts`, `orders`, `order_audit` | catalog + inventory (HTTP), Kafka (publish) |
| `services/notification-service` | 8085 | `notifications`, `processed_events` | Kafka (consume) |

Port 8080 is deliberately unused - it belonged to the monolith and belongs to the API gateway.

**Rules that are not negotiable across the split:**

1. **No service reads another's database.** Enforced by PostgreSQL, which has no cross-database
   joins; `MigrationLayoutTest` additionally checks that no migration references a table in another
   service's schema.
2. **No DTO or event record goes in `shared-kernel`.** Infrastructure is shared; contracts are not.
   Each consumer declares the shape it needs - order-service's `CatalogProduct` has three fields
   where catalog-service's `ProductResponse` has five. A shared contract class means five services
   that must be redeployed together, which is the monolith with extra network calls.
3. **Event and response contracts evolve additively.** Add fields, never rename or remove. A rename
   is two deploys: add the new name, wait for every reader, remove the old - the same
   expand-then-contract discipline the migrations follow, for the same reason.
4. **Every service validates JWTs itself**, from customer-service's published public key. No service
   asks another whether a token is good; that would put one process in front of every request to the
   other four.
5. **`@Transactional` stops at the process boundary.** A transaction belongs to one connection to one
   database. Anything spanning two services is a compensation - an attempt to undo, after the fact,
   over a network that can fail - and must be written and commented as one.
6. **Never open a transaction around a network call.** It holds a pooled connection for the duration
   of somebody else's latency. `OrderPlacement` is not transactional for exactly this reason, and
   `OrderWriter` exists as a separate bean so the transaction can start afterwards.
7. **Every outbound client has a timeout.** An in-process call returns or throws; a network call can
   also hang, and an unbounded read exhausts the thread pool.
8. **Forward the caller's token**, do not hold a service credential. Identity survives the hop and no
   service is more privileged than its callers.

## Build commands

```bash
./mvnw clean verify                          # all six modules - run this before every commit
./mvnw test                                  # tests only, no Docker needed
./mvnw -pl services/order-service verify     # one service
./mvnw -pl shared-kernel install             # after changing the kernel, before building a service alone
```

There is no `spring-boot:run` for "the application" any more - there are five. Run one with
`./mvnw -pl services/catalog-service spring-boot:run`, and note that it needs its own database
(`ecomdemo_catalog`) and, for anything authenticated, a customer-service to fetch the JWK set from.
`docker compose up -d --build` remains the way to run the system.

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

A request goes **Controller → Service → Repository** and never sideways or backwards. That is
unchanged; what changed in Phase 20 is what lies on the other side of a "cross-feature" call.

**Within a service**, cross-feature calls go service → service, never into another feature's
repository. `CartService.requireCart()` still returns the `Cart` **entity** - the one documented
exception to "entities never leave the service layer" - because `OrderWriter` needs the live managed
entity so that clearing the cart participates in the checkout transaction. Both are in order-service,
so both share a transaction.

**Across services**, there are no seams like that and there cannot be. `ProductService.requireEntity`
was deleted: what made it useful was that the caller shared the transaction, and a caller in another
process shares nothing. order-service reads prices over HTTP and gets an immutable snapshot, which is
all a boundary can hand over.

A service test mocks the collaborating *service* within a module, and the *client interface*
(`CatalogClient`, `InventoryClient`) across a boundary - never the transport, except in
`ServiceClientsTest`, which exists precisely to exercise the real proxies.

**Cart totals are derived, order totals are stored.** A cart must show today's price, so
`CartService` fetches it from catalog-service on every read - **once for the whole cart**, never once
per line, because that loop now crosses a network. `Order` stores `totalAmount`, and `OrderItem`
snapshots `productName` and `unitPrice` at checkout, because an order is a historical record:
repricing or deleting a product must not rewrite what a customer already paid. After the split that
snapshot is not merely correct but necessary - `product_id` resolves to nothing this database can
reach.

**Checkout validates everything before mutating anything**, and the reservation is what preserves it.
`POST /api/stock/reservations` takes the whole cart in one request so inventory-service can check
every line inside one transaction and apply none of them if any fails. One call per line would lose
the all-or-nothing property that Phase 0 built in, purely by moving stock behind HTTP - which is the
single easiest thing to get wrong when splitting a service.

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

## Containers

**`docker compose up -d --build` is the way to run the whole system.** It needs a `.env` — copy
`.env.example`. `compose.yaml` refuses to start without `POSTGRES_PASSWORD` and `JWT_SECRET` rather
than defaulting them.

**The Dockerfile is multi-stage and the order of instructions is load-bearing.** `pom.xml` is copied
and dependencies resolved *before* `src/`, so editing Java does not re-resolve dependencies. The
layered jar is extracted with **`-Djarmode=tools extract --layers`** (not the pre-3.3 `layertools`)
and copied one layer at a time, least-changing first. Keep both properties when editing it.

**The container runs as a non-root user, and `USER` comes after the `COPY`s.** Root in a container is
root on the host kernel. If you add a step that needs to write at runtime, give it a directory owned
by `ecomdemo` — do not move `USER` up.

**Anything the app talks to inside compose is addressed by service name**, never `localhost`: each
container has its own network namespace. The database is not published to the host at all.

**Database state lives in a named volume.** `docker compose down` keeps it; only `down -v` removes
it. The compose database and the standalone Phase 4 container are separate databases.

**`ContainerConfigurationTest` pins all of the above.** If you change the Dockerfile or
`compose.yaml`, that test is what tells you whether you broke a property that still boots fine —
non-root, layering, health-check ordering, no literal secrets.

## Database migrations

**Flyway owns the schema; Hibernate only checks it.** `ddl-auto` is `validate` in every profile. A
mapping added without a matching migration fails at startup — loudly, and before the first query
rather than during it.

**Migrations live in each service's `src/main/resources/db/migration`**, named
`V<n>__snake_case_description.sql`. The version number is the order of application, permanently.

**Each service has its own timeline, starting at V1, and its own `flyway_schema_history` in its own
database.** They were renumbered once, in Phase 20, which is the single legitimate exception to rule
2 below: the five databases were new and empty, so nothing held a checksum of the old files.
`MigrationLayoutTest` in shared-kernel enforces the result - contiguous from V1 per service, no table
created by two services, and no `REFERENCES` clause naming a table in another service's database.

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

**Authentication is a bearer token.** Credentials go to `POST /api/auth/login` on **customer-service**
once; every other request, to any service, carries `Authorization: Bearer <token>`. The token is
**RS256** since Phase 20 - customer-service holds the private key and publishes the public half at
`/.well-known/jwks.json`, and the other four verify locally with it. HS256 would have meant every
service that can check a token can also mint one. The token carries the customer id as `sub` and the
role as a claim, so authorizing a request needs no database read - which stopped being an
optimisation and became the design, since four of the five services have no users table at all.

**A JWT is signed, not encrypted:** anything in the payload is readable by whoever holds it.

**The token is kept on the Authentication and must not be erased.** `SecurityUserAuthentication`
overrides `eraseCredentials()` to do nothing, because order-service forwards the caller's own token
to its neighbours. `UsernamePasswordAuthenticationToken` nulls its credentials there, and
`ProviderManager` calls it on every successful authentication - which silently broke propagation
once already.

**Never put a secret, or anything you would not print on a postcard, in a claim.** And remember a
token cannot be revoked — a change of role takes effect only when the current token expires.

**Every endpoint is denied by default, in every service.** The filter chain is shared
(`ResourceServerAutoConfiguration` in shared-kernel); each service contributes a
`ServiceAuthorizationRules` bean naming only what it makes public. Service rules are applied first,
then the actuator rules, then `anyRequest().authenticated()` - so a service can only ever open
something up, never leave a gap. Rules are matched in order, so the specific ones come first.

Note the consequence of five chains: deny-by-default now has to hold five times, and a rule forgotten
in one service is a hole in the system even if the other four are right. **A `@WebMvcTest` must
import both `SecurityTestConfiguration` and its own rules class** - with only the former the chain
loads, nothing is public, and every anonymous test gets a 401 that looks like a broken rule.

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
- A `@WebMvcTest` must `@Import(SecurityTestConfiguration.class)` — a slice loads controllers, not
  `@Configuration`, so without it the real rules never load, the `JwtDecoder` is missing and
  `@AuthenticationPrincipal` is not even resolved. Add new security beans to that one class.
- `SecurityMockMvcCustomizer` applies `springSecurity()` to the MockMvc builder. The chain is
  stateless, so without it the test's `SecurityContext` is discarded per request and every
  authenticated test returns 401.
- Tests calling services directly need `TestSecurity.actAs(...)` for `@PreAuthorize` to evaluate —
  including inside any worker thread, since `SecurityContextHolder` is thread-local.

## Caching

**Caching lives in catalog-service and nowhere else.** That is the Phase 20 form of the Phase 13
rule: cache what is read to be displayed, never what is read to make a decision. Everything
catalog-service serves is display data, so all of it is cacheable without reservation; stock feeds a
decision and lives in inventory-service, which has no Redis dependency at all. The rule can no longer
be broken by annotating the wrong method.

(Phase 13 had to carve out `ProductService.requireEntity` as the one lookup that must not be cached,
because it carried `stockQuantity`. That method is gone - see Architecture.)

**Every cache declares its value type** in `CacheConfiguration`. Adding a cache means adding a
`RedisCacheConfiguration` for it with a `JacksonJsonRedisSerializer` for what it holds; the generic
polymorphic serializer is deliberately not used, so there are no type names in the stored JSON.

**Evict by key, not `allEntries = true`.** `RedisCache.clear()` was measured not to remove entries
here, and it fails by serving stale data rather than erroring. `findAll` caches under the explicit
key `product-list::all` for that reason.

**Every write path must evict**, including ones in other features — checkout evicts the products
whose stock it changed. A TTL is a backstop, not the mechanism.

**Watching it work:** `docker compose logs app | grep com.ecomdemo.cache` shows HIT / MISS / PUT /
EVICT, and `docker compose exec redis redis-cli GET 'products::3'` shows the stored JSON. In tests,
Failsafe writes that output to `target/failsafe-reports/<class>.txt`, not to Maven's stdout.

**The fast suite runs with `spring.cache.type=none`** so `./mvnw test` needs no Docker; integration
tests get a real Redis container from `AbstractPostgresIT`.

## Transactions and concurrency

**The service layer owns the transaction boundary.** Class-level `@Transactional(readOnly = true)`,
overridden with `@Transactional` on the methods that write. A class-level annotation applies to
*every* method, so a method that must not be transactional has to opt out explicitly
(`Propagation.NEVER` on `OrderService.placeOrder` and `StockService.reserve`).

**A transaction covers one database and stops at the process boundary.** `OrderPlacement` carries no
class-level annotation at all - it makes two HTTP calls, and a transaction held across them keeps a
pooled connection for the duration of somebody else's latency. Anything that must span two services
is a *compensation*, not a rollback: an HTTP call that can itself fail, leaving the system
inconsistent. Write it as one and comment it as one.

**Self-invocation does not work, and fails silently.** `@Transactional`, `@Retryable` and friends are
applied by a proxy. Calling `this.otherMethod()` bypasses it entirely: the annotation is ignored, the
code runs anyway, and nothing is logged. When two annotations must not share a boundary — a retry
around a transaction, an audit write that must outlive a rollback — put them on **separate beans**.
That is why `OrderService`, `OrderPlacement` and `OrderAuditService` are three classes.

**Rollback rules.** Spring rolls back on `RuntimeException` and `Error`, and *commits* on a checked
exception unless you ask otherwise. Every exception this codebase throws from a service
(`NotFoundException`, `ConflictException`) is unchecked, so the default is the one we want — but a
checked exception added later would need `@Transactional(rollbackFor = ...)`.

**Two resilience libraries, and the line between them.** Spring Framework 7's
`org.springframework.resilience` (`@Retryable`, `@ConcurrencyLimit`) and Resilience4j are both on the
classpath in order-service. The line is drawn by *what is being protected*, never by which is newer:

| Concern | Tool |
|---|---|
| Local optimistic-lock contention | Spring `@Retryable` (`OrderService`, `StockService`) |
| A remote HTTP call | Resilience4j (`ResilientCatalogClient`, `ResilientInventoryClient`) |

A database write has no remote party to protect and must never acquire a circuit breaker - opening a
circuit on lock contention would refuse writes because writes were contended. Never put both on the
same call.

**Resilience4j decorators are applied outermost-first** (`Retry → CircuitBreaker → ... → Bulkhead`),
and **`fallbackMethod` belongs on the outermost one**. A fallback runs inside the aspect that declares
it, so a fallback on `@CircuitBreaker` translates the exception before the outer `@Retry` can classify
it - which silently turns "fail fast when the circuit is open" into "retry the rejection three times".
See `docs/decisions.md` for the measured version of that mistake.

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

**Integration tests extend that service's own `Abstract*IT`.** There were one of these and there are
now five, each starting only the containers its service actually talks to - customer-service and
inventory-service start one, catalog-service two, order-service and notification-service two
including Kafka. Each holds its containers for the whole JVM (static fields, the singleton pattern),
exposes a `RestTestClient` bound to the real port, and lets `@ServiceConnection` wire the random host
port in. They run under the default `dev` profile, so the migrations and `ddl-auto: validate` are
exercised on the real engine.

**Tokens in tests come from `TestTokens`, not from logging in.** Four of the five services have no
login endpoint. `TestTokens` mints RS256 tokens with a throwaway keypair and each base class wires the
matching decoder in - which is exactly the production arrangement, and is what makes "this service
validates tokens independently" testable at all. Only customer-service's suite logs in for real.

**A neighbour is mocked at the client interface, never booted.** order-service's suite replaces
`CatalogClient` and `InventoryClient` with `@MockitoBean`. Booting two more Spring applications per
test class would be slow enough to discourage writing tests and would test the wrong thing - a mock
can return a 409, refuse a connection or hang, which is what this suite is about.

**Contracts across a boundary are pinned on both sides.** `OrderPlacedEventContractTest` asserts the
JSON field names the producer emits; notification-service publishes a hand-written golden sample with
those names. `ServiceClientsTest` builds the real HTTP proxies against `MockRestServiceServer` -
without it, nothing ever asks Spring to implement a client interface, and a missing `@PathVariable`
reaches production. **Treat "all six modules are green" as necessary and not sufficient**; the
end-to-end claim is verified through Compose.

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

## Code quality

**Coverage comes from JaCoCo across both test phases.** `./mvnw clean verify` writes a merged report
to `target/site/jacoco-merged/` — open `index.html`. Measuring only Surefire would ignore the
integration tests, which cover a great deal.

**Static analysis is SonarQube, run locally and on demand:**

```bash
docker compose -f compose.sonar.yaml up -d
./mvnw clean verify                      # Sonar reads JaCoCo's report; it does not measure coverage
./mvnw sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token>
```

`sonar.qualitygate.wait=true` fails the build on a red gate. It applies only to `sonar:sonar`, so
`verify` never needs a server.

**The gate is about *new* code**, and its conditions are recorded in `docs/quality-gate.md` — the gate
itself lives in the SonarQube instance and is lost if the volume is rebuilt.

**When an issue is not a defect, resolve it in SonarQube with the evidence** — false positive or
accepted, with a comment saying why and, for an acceptance, what would invalidate it. Do not add
`//NOSONAR` to the code: a suppression in source rots silently and hides the reasoning from the
person who next meets the issue.

## CI

**Every pull request runs `./mvnw -B clean verify` on GitHub Actions**, and `Build and test` is a
required status check on `main` — a red build cannot merge, for anyone, including the repository
owner. Test reports are uploaded as artifacts even when the build fails (`gh run download <id>`).

**A merge to `main` publishes an image** to `ghcr.io/mr-sujay-patil/ecomdemo`, tagged with the commit
SHA and `latest`. The publish job `needs: verify`, so an untested image cannot be published.

**When editing `.github/workflows/ci.yml`:**

- Keep permissions read-only at the workflow level and widen per job. Only `publish` gets
  `packages: write`.
- Keep both triggers. A squash-merge creates a commit no PR run tested.
- The GHCR image name must be lower-cased — `${{ github.repository }}` contains an uppercase letter
  and the registry rejects it.
- A workflow only guards branches that contain it. A PR branched from before the workflow existed
  runs no checks at all, which looks like a blocked PR for a completely different reason.

**Dependency updates arrive as Dependabot PRs** (weekly, Spring grouped into one) and go through the
same gate, so a breaking bump shows up as a red PR rather than a surprise.

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
6. **A phase is not done until it has been run.** Three of the six bugs in Phase 20 passed the entire
   test suite and failed on the first real request through `docker compose up` - a missing
   `@PathVariable`, a silently erased bearer token, and a serializer default that blocked a whole
   partition. Green modules are evidence about the modules.
