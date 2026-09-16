# Decision Log

One short entry per decision worth remembering. Newest phase last.

---

## Phase 0 — Baseline Monolith

**Spring Boot 4.1.1 on Java 21.** 4.1.1 is the newest GA release; 4.2.0-M1 exists but the roadmap
forbids milestones. The roadmap's base stack is Java 21, so the build targets `release 21` even
though the local JDK is 25 — Spring Boot 4's own baseline is Java 17, so there is no conflict.

**Packages by feature, not by layer.** `product`, `cart`, `order` and `common`, each holding its own
controller, service, repository, entity and DTOs — rather than top-level `controller/`, `service/`
and `repository/` packages. Related code changes together, and it makes the Phase 19 modular-monolith
split a matter of drawing boundaries that already exist.

**Entities never leave the service layer.** Every endpoint returns a `record` DTO. This keeps the
JSON contract independent of the schema (renaming a column cannot silently break clients), stops a
client from setting fields it should not own (a `ProductRequest` has no `id`), and — with
`open-in-view` disabled — makes it impossible to trigger a lazy load during serialisation.

**`open-in-view: false`.** Spring Boot's default keeps the Hibernate session open for the whole
request, so a lazy association touched during JSON rendering quietly issues another query. Turning it
off means such a mistake fails loudly in the service layer instead, which is why `CartRepository` and
`OrderRepository` use explicit `left join fetch` queries.

**`BigDecimal` for all money, `NUMERIC(12,2)` in the schema.** `double` cannot represent 0.1
exactly, so summing prices accumulates error. Prices are normalised to two decimal places on write
and totals rounded `HALF_UP`, and `precision`/`scale` put the same rule in the database rather than
trusting every future caller to remember it.

**Order lines snapshot the product name and price; cart lines do not.** A cart should show today's
price, so `CartItem` reads through to the live `Product`. An order is a historical record of what was
actually paid, so `OrderItem` copies the name and price at checkout. Without the snapshot, repricing
a product would silently rewrite every past order.

**Stock is validated for the entire cart before anything is written.** `OrderService.placeOrder()`
loops twice: once to check, once to mutate. Combined with `@Transactional`, a five-line order whose
fourth line is out of stock changes nothing at all, rather than half-completing.

**A single `@RestControllerAdvice`, and the 409 vs 400 split.** Centralising the mapping means no
controller needs a try/catch and no service needs to know about HTTP. `400` means "fix your request";
`409` means "your request is fine, but the server's state forbids it" — insufficient stock, or
checking out an empty cart. `NoResourceFoundException` is mapped explicitly to `404`, because
otherwise the catch-all `Exception` handler would report every mistyped URL as a `500`.

**`spring-boot-h2console` added as an explicit dependency.** Spring Boot 4 split the
auto-configurations out of `spring-boot-autoconfigure` into per-technology modules, so having `h2` on
the classpath no longer configures the console on its own. This is a Spring Boot module, not a new
technology.

**`RestTestClient` instead of `TestRestTemplate` in the integration test.** `TestRestTemplate` is no
longer on the `spring-boot-starter-test` classpath in Spring Boot 4; it moved to the separate
`spring-boot-restclient-test` module. `RestTestClient` is Spring Framework 7's synchronous test
client, ships with `spring-test`, and needed no extra dependency.

**Git initialised during Phase 0, one phase early.** The roadmap puts Git in Phase 1, but committing
the baseline incrementally is worth more than a strict reading of "one technology per phase". Only
local Git is used — no remote, no PR template, no branch protection, no tag. Those remain Phase 1.

### Known gaps, deferred on purpose

- ~~`docs/Roadmap.md` is spelled with a capital R on disk while the roadmap text refers to
  `docs/ROADMAP.md`.~~ Resolved in Phase 1.
- `Product.stockQuantity` has no optimistic lock, so two concurrent checkouts for the last unit can
  both succeed. That is exactly the problem Phase 6 exists to solve.
- ~~There is no `CLAUDE.md` yet — it is a Phase 1 deliverable.~~ Added in Phase 1.

---

## Phase 1 — Git & GitHub Workflow

**`main` was repointed rather than merged.** `main` sat on `fa76668`, an orphan left behind when a
`git commit --amend --reset-author` on the Phase 0 branch rewrote the same commit as `d56ed25`. Both
had the identical tree `d0c3f01`, so `git branch -f main feature/phase-00-baseline-monolith` lost
nothing. A real merge would have grafted a duplicate root commit into the history to no benefit.
Phase 0 therefore landed on `main` directly; the roadmap starts the PR workflow at Phase 1, and
Phase 1 itself was merged through one.

**Squash merge for phase branches.** `main` gets one commit per phase, so `git log --oneline` on
`main` reads as the roadmap itself. The granular commits are not lost — they stay visible in the PR,
which is where the step-by-step reasoning is actually useful. The cost is that `main`'s commits no
longer correspond to anything on a branch, which is why each phase is also tagged.

**Annotated tags, not lightweight ones.** `git tag -a phase-XX-complete` creates a real object
carrying a tagger, a date and a message. A lightweight tag is only a pointer, with no record of who
made it or why — fine for a scratch bookmark, wrong for something that marks a milestone.

**Branch protection: PR required, zero approvals, admins not enforced.** `main` refuses direct
pushes and force-pushes, and every change must arrive through a PR. Requiring an approving review
would be more realistic, but with a single maintainer GitHub will not let you approve your own PR —
the rule would only ever be satisfied by bypassing it. Zero approvals keeps the gate genuine rather
than theatrical. `enforce_admins` is off for the same reason: it would leave nobody able to merge.
Turn both up the moment a second contributor appears.

**No required status checks.** There is no CI yet — GitHub Actions is Phase 11. Adding a required
check with nothing to run it would block every merge permanently.

**Roadmap renamed to `docs/ROADMAP.md`.** Flagged as a known gap in Phase 0 and fixed here, since
this phase owns the file's location. macOS's case-insensitive filesystem makes a direct
`git mv Roadmap.md ROADMAP.md` a silent no-op, so it was done in two steps via a temporary name to
force Git to record the rename.

**Branch name normalised.** The phase prompt asked for `feature/phase-01-Phase 1: Git & GitHub
Workflow`. Git rejects that — `check-ref-format` forbids `:` in a ref name — so the roadmap's own
convention was used: `feature/phase-01-git-github-workflow`.

### Known gaps, deferred on purpose

- No CI, no `.github/workflows/`, and no required status checks — Phase 11.
- No `CODEOWNERS`, issue templates or Dependabot config; none is named by this phase.
- `enforce_admins` is off, so an admin can still push to `main` in an emergency. Acceptable for a
  solo repository, worth revisiting with collaborators.

---

## Phase 2 — Automated Testing

**Three Spring Boot 4 surprises, all verified against the classpath before writing a line.**
`@WebMvcTest` and `@DataJpaTest` are no longer in `spring-boot-test-autoconfigure` — that module is
down to 22 classes because Boot 4 split the test auto-configurations into per-technology modules,
exactly as it did for the H2 console in Phase 0. They now need `spring-boot-webmvc-test` and
`spring-boot-data-jpa-test`. Separately, `@MockBean` is gone; the replacement is `@MockitoBean` from
`spring-test`. And the starter ships JUnit Jupiter **6.0.3**, not 5 — same annotations and
programming model, newer major version, no compatibility conflict.

**`MockMvcTester` rather than classic `MockMvc`.** The phase names both MockMvc and AssertJ, and
`MockMvcTester` is MockMvc behind an AssertJ-fluent API, so the whole suite speaks one assertion
dialect instead of mixing AssertJ with Hamcrest matchers. The cost is that most tutorials and
StackOverflow answers still show `perform(...).andExpect(status().isOk())`, so the classic form has
to be learned separately when reading other codebases.

**Mock the collaborator at the service boundary, not the repository underneath it.** `CartServiceTest`
mocks `ProductService`, not `ProductRepository`. Whether product lookup works is `ProductServiceTest`'s
question; mocking one layer down would couple the cart suite to the product service's internals and
make a refactor there break tests over here.

**`Clock.fixed` instead of a mocked `Clock`.** A fixed clock is a real implementation with known
behaviour — a *stub*. Mocking it would require stubbing and would additionally record interactions
nobody asks about. This is why `Clock` was injected as a bean in Phase 0 rather than calling
`Instant.now()` inline: it made `placedAt` exactly assertable.

**Assert `isLoaded`, not the values, in repository tests.** `CartRepositoryTest` flushes, calls
`entityManager.clear()` to detach everything, then checks
`Persistence.getPersistenceUtil().isLoaded(cart, "items")`. Reading the items instead would trigger a
lazy load and pass whether or not the `join fetch` survived. Confirmed by deleting the fetch clause
and watching the test fail with the message it was given.

**Assert the absence of calls where the absence is the behaviour.**
`ProductServiceTest` uses `verify(productRepository, never()).save(any())` on the update path, because
relying on Hibernate's dirty checking rather than calling `save()` is a deliberate design choice —
and an invisible one until a test pins it down.

**The suite was tested by breaking the code.** Moving the stock check in `OrderService.placeOrder`
from before the mutation loop to inside it failed exactly one test — the unit test that owns that
rule — while the `@SpringBootTest` stayed green. That is the pyramid justifying itself: the
integration test proves the pieces fit, but only the unit test can see a half-completed mutation.

**No `@DataJpaTest` for `ProductRepository`.** It has no hand-written query, so a test there would
assert that Spring Data works, which is not ours to verify.

### Known gaps, deferred on purpose

- No coverage measurement — JaCoCo is Phase 12. "Every service method has a test" was checked by
  reading, not by a tool.
- `@DataJpaTest` runs on H2, so it verifies the JPQL but not PostgreSQL-specific behaviour.
  Testcontainers is Phase 7.
- Everything runs under Surefire; there is no Failsafe split between unit and integration tests. Worth
  adding when the suite is slow enough to notice — at 6 seconds it is not.
- `TestFixtures` sets `Product.id` by reflection because the field is `@GeneratedValue` with no
  setter. Confined to one file, but still a compromise the production design forces.

---

## Phase 4 — PostgreSQL

**PostgreSQL 18 in Docker, driver 42.7.13 from the Spring Boot parent.** No version is pinned in
`pom.xml`: the parent curates a driver known to work with Boot 4.1.1. The README's `docker run` pins
`postgres:18-alpine` so everyone gets the same engine, and notes the trap that PostgreSQL 18 moved
the image's volume from `/var/lib/postgresql/data` to `/var/lib/postgresql` — the old path still
mounts, it just stops persisting anything, which is the worst kind of failure.

**H2 stays, at `test` scope.** The application runs on PostgreSQL; the test suite does not. The
alternative — pointing the tests at a real PostgreSQL — buys fidelity at the price of a build that
fails on any machine without a running database, including the CI runner arriving in Phase 11, and
of test data landing in the developer's own database. Keeping the build hermetic is worth more right
now, and the resulting blind spot is exactly what Phase 7 (Testcontainers) exists to remove. Until
then a green build is evidence about our logic, not about PostgreSQL compatibility. The test URL sets
`MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` so H2 at least parses PostgreSQL syntax and folds unquoted
identifiers the same way; that narrows the gap without closing it.

**`spring-boot-h2console` removed rather than kept for tests.** It configures a web console for a
database the application no longer runs on. Reading the schema is now a job for `psql`, DBeaver or
pgAdmin — which is one of this phase's stated concepts, not a loss.

**Profiles hold only what differs.** `application.yml` keeps the settings every profile shares
(`open-in-view`, SQL logging, error handling); `application-dev.yml` and `application-test.yml` hold
the datasource and the schema strategy. `dev` is the default so `./mvnw spring-boot:run` works with
no flag, and tests opt out explicitly with `@ActiveProfiles("test")`. Without that annotation
`@SpringBootTest` inherits `dev` and tries to dial PostgreSQL, so the build breaks wherever no
database is running — a one-line omission with a confusing failure, which is why there is now a test
asserting each profile's datasource.

**Credentials come from `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.** The defaults after
each `:` in the dev profile are committed deliberately. They are not secrets: they open a throwaway
local container and nothing else, and having them in the file is what lets a fresh clone start with
one command. A real deployment overrides all three from the environment, which
`DatasourceProfileTest` pins down by asserting the environment wins.

**HikariCP is configured explicitly even though its defaults would do.** Pool size 10, two idle
connections kept warm, a 30 s wait for a free connection and a 30 min maximum connection lifetime.
The values are close to the library's own defaults; writing them down makes them reviewable, and
`max-lifetime` in particular is the setting that matters once a real network sits between the
application and the database — it must stay below whatever idle timeout the database or a proxy
enforces, or the pool cheerfully hands out sockets the far end has already closed.

**`spring.sql.init.mode: never` in dev — the seeding trap this phase creates.** Phase 0 ran
`data.sql` on every start, which is free when the database is thrown away at shutdown and actively
wrong when it is not: ten more products on every boot. The catalogue is now seeded once by hand
(the README gives the command) and the file is left in place for the test profile, which still wants
a fresh catalogue per run. The shared cart needs no seeding at all — `CartService.requireCart()`
already recreates row 1 when it is missing.

**`hibernate.jdbc.time_zone: UTC`.** Checked against the generated schema rather than assumed:
Hibernate maps `Order.placedAt` (an `Instant`) to `TIMESTAMP_UTC`, which PostgreSQL realises as
`timestamp with time zone` — so on PostgreSQL alone the setting would be redundant. It earns its
place because the test profile runs on H2, where that mapping is not guaranteed to match; pinning
the zone removes both the machine's timezone and the engine from the set of things that can change a
stored timestamp.

**`ddl-auto: update`, knowingly on borrowed time.** It is what the roadmap asks for in this phase and
it is fine for a single developer adding fields. It also cannot rename a column, cannot drop one, and
never reports what it changed — rename a field and you silently get a second column beside the first,
with the old data stranded in it. Phase 5 replaces it with Flyway.

### Known gaps, deferred on purpose

- Tests do not touch PostgreSQL (Phase 7, Testcontainers).
- Schema changes are unversioned and unreviewable (Phase 5, Flyway).
- The `docker run` command is a copy-paste convenience, not an understanding of Docker (Phase 10).
- Nothing checks the database is reachable before the application accepts traffic; a health endpoint
  arrives with Actuator in Phase 15.

---

## Phase 5 — Database Migrations

**Flyway 12.4.0, version curated by the Spring Boot parent.** Three dependencies rather than one:
`flyway-core`, `flyway-database-postgresql` (Flyway 10 split the heavyweight database
implementations out of core) and `spring-boot-flyway`. That last one is Spring Boot's
auto-configuration module, and leaving it out is a genuinely nasty failure: Flyway sits on the
classpath and simply never runs — no error, no log line — and Hibernate then fails with
`Schema validation: missing table [cart_items]`, which points nowhere near the cause. It is the same
per-technology module split as `spring-boot-h2console` in Phase 0 and the test slices in Phase 2.

**No `flyway-database-h2`.** The obvious inference from the PostgreSQL split is that every database
needs a module; the artifact does not exist. Checked by looking inside the jar: `flyway-core` still
carries `org.flywaydb.core.internal.database.h2`, so the test suite needs no third dependency.

**V1 reproduces the Hibernate-generated schema, from a dump rather than from memory.** `ddl-auto`
moved straight from `update` to `validate`, so V1 had to match what was already there closely enough
for Hibernate to agree — a `pg_dump --schema-only` of the Phase 4 database was the source. Two
deliberate improvements went in at the same time, both things Hibernate will not do for you: named
constraints, and indexes on the four foreign key columns. PostgreSQL indexes the referenced side of a
foreign key automatically but never the referencing side, so every `left join fetch` in
`CartRepository` and `OrderRepository` had been scanning the whole child table.

**The existing database was dropped rather than baselined.** It had been built by Hibernate and had
no `flyway_schema_history`, so Flyway refuses to touch it. `baseline-on-migrate` would have stamped
whatever Hibernate happened to create as "version 1" without ever running V1, leaving the schema
unversioned in practice and the V2 seed duplicating ten products that were already there. Dropping
cost nothing but demo data and is what the phase's "Done when" actually asks for. On a database with
real data in it the answer would be the opposite, and `baseline-on-migrate` is the tool for it.

**The test suite runs the real migrations.** The alternative — leaving tests on `create-drop` and
`data.sql` — would mean the migrations are never executed until someone starts the application, which
defeats the point of putting the schema under version control. Two adjustments made it work:
`spring.test.database.replace: none`, so `@DataJpaTest` keeps the configured `MODE=PostgreSQL`
datasource instead of swapping in a plain embedded one whose dialect rules the migrations are not
written for; and `ddl-auto: validate` in the test profile too, so the entities are checked against
the migrated schema on every build. The honest limit: this proves the migrations run on **H2**.
Anything PostgreSQL-specific would pass `verify` and fail on startup. Phase 7 closes that.

**`FlywayMigrationTest` queries `information_schema` directly.** Unusual for this codebase, and
deliberate: the subject under test is the migration mechanism, not any Java code — every assertion
would still hold if the entities were deleted. Seed data is asserted by name rather than by counting
rows, because the test profile's H2 lives for the whole JVM and a count would make the result depend
on which tests happened to run first. That shared-database-per-run behaviour is a mild regression in
isolation compared with `create-drop`, accepted because `@DataJpaTest` still rolls back and the
alternative is not running the migrations at all.

**V3 is nullable on purpose, and that is the whole lesson.** A nullable column with no default
rewrites no rows, takes no long lock, and leaves code that has never heard of `category` working —
which is exactly the situation during a rolling deploy, where two versions of the application talk to
one database for a few minutes. `NOT NULL` would have needed a value for every existing row and would
have broken the running old version instantly. That tightening belongs in a later migration once
every row has a value, and never on the same deploy: expand first, contract later.

**`category` is a `String`, not an enum, and is set through a setter rather than the constructor.**
An enum would turn every new category into a code change and a redeploy, and there is no fixed set to
enforce yet. Keeping it out of the constructor means no existing call site had to be changed to pass
`null`.

### Known gaps, deferred on purpose

- Migrations are verified on H2, not PostgreSQL (Phase 7, Testcontainers).
- Flyway Community has no `undo`; reversing a change means writing another migration.
- `V2` deploys reference data as a versioned migration. Repeatable migrations (`R__*.sql`) are the
  better home for data that should track the file rather than be applied once — not needed yet.
- Nothing runs migrations separately from application startup. Real deployments usually migrate as a
  distinct step, so a schema change cannot be half-applied by three instances booting at once. That
  needs somewhere to run it from, which arrives with CI in Phase 11.

---

## Phase 6 — Transactions & Concurrency

**`@Retryable` from Spring Framework 7, not the Spring Retry project.** The plan was approved for
declarative retry via Spring Retry; checking the classpath first showed Framework 7.0.9 ships
`org.springframework.resilience.annotation.Retryable`, already present through `spring-context`. Same
annotation shape, same declarative style, no new dependency and no second proxy library whose
ordering against `@Transactional` has to be reasoned about. It does need `@EnableResilientMethods` —
without it the annotation compiles, runs and silently never retries, which is why `OrderRetryTest`
uses a real context: a Mockito-only retry test would pass just as happily in that broken state.

**Three annotations, three beans.** `@Retryable` on `OrderService.placeOrder`, `@Transactional` on
`OrderPlacement.placeOnce`, `REQUIRES_NEW` on `OrderAuditService.record`. They cannot share a class.
A retry inside the transaction it retries has nothing left to retry — the transaction is already
rollback-only — and a retry in a neighbouring method of the same bean does nothing at all, because
Spring applies both annotations with a proxy that an internal `this.method()` call never reaches.
That is the self-invocation pitfall, and it fails silently: the code runs, the annotation is ignored,
and nothing in the logs says so. Splitting the beans makes it structurally impossible rather than a
comment asking the next person to remember.

**`Propagation.NEVER` on `placeOrder`.** The class-level `@Transactional(readOnly = true)` applies to
every method, including the retry wrapper — so without an explicit override the retry would run
inside a read-only transaction that `OrderPlacement` would then join, and the whole split would be
undone invisibly. `NEVER` says "there must be no transaction here" and throws if there ever is,
turning the invariant the design depends on into an assertion rather than a hope.

**`placeOnce` flushes explicitly before returning.** The optimistic lock fires on the `products`
UPDATE, which Hibernate would otherwise defer to commit — after the method returns, while the proxy
is committing. That is too late to record an audit row for it and too late for `@Retryable` to see
anything more useful than a failed commit. `orderRepository.flush()` brings the version check inside
the method, where the failure can be audited and re-thrown deliberately.

**The audit uses `REQUIRES_NEW`, and that has a real cost.** It suspends the caller's transaction,
runs and commits its own, then resumes — which is why the audit row outlives the rollback it
describes. The cost is that a suspended transaction still holds its locks and its connection while
the new one runs, so two connections are in play for every audited attempt. A pool sized 1 would
deadlock instantly. With the default `REQUIRED` the audit would simply join the caller and roll back
with it, producing a trail that is complete only for the cases that already worked — worse than no
audit, because it looks trustworthy.

**The concurrency test asserts the invariant, not how the loser lost.** `ConcurrentOrderTest` checks
one order, zero stock, never oversold — never which exception the losing thread received, because
that depends on the interleaving and pinning it down would make it a test of the scheduler.
`OptimisticLockTest` sits beside it and creates the stale write in a fixed order, so there is one
place where the failure can only be the version check.

> **Corrected in Phase 7.** This entry originally recorded that the version conflict fired **zero
> times in 40 races on H2** and 5 of 5 against PostgreSQL, and concluded the engines race
> differently. That was wrong: it was a flaw in how conflicts were counted, not a property of H2.
> The count came from the exception the calling thread finally saw — but `@Retryable` retries the
> rejected thread, the retry finds the cart already consumed, and a `ConflictException` is what
> surfaces, hiding the lock entirely from outside. Counted from the `CONCURRENT_MODIFICATION` audit
> rows instead, **the lock fires on both engines, five times in five rounds on each.** The audit
> trail was the reliable evidence all along, which is a small argument for the audit table itself.
> The case for running this against PostgreSQL in Phase 7 still stands — it is what production runs —
> but not on the grounds originally given.

**Optimistic, not pessimistic.** Optimistic locking takes no locks and blocks nothing; it detects the
collision at write time and makes the loser redo the work. That suits a catalogue — read constantly,
written rarely, contention unusual — and costs literally nothing when nothing collides.
`@Lock(PESSIMISTIC_WRITE)` (a `SELECT ... FOR UPDATE`) is the opposite trade: the reader blocks
everyone else until it commits, which is right when contention is the norm rather than the exception
(a seat map, a ledger balance, a counter every request touches), because there every optimistic
attempt would fail and the retries would add load to a system already under pressure. It is
deliberately not implemented here: nothing in this application needs it, and adding an unused
annotation to demonstrate it would be exactly the kind of speculative code this project avoids.

**Isolation stays at the database default.** `READ COMMITTED` prevents dirty reads and nothing else;
the anomaly that matters here is the lost update, which `@Version` closes precisely. Raising the
level to `REPEATABLE READ` or `SERIALIZABLE` would also work, at the cost of more locking or more
serialisation failures across every query in the application, to fix one write path.

**Committing tests get their own database.** The four `@SpringBootTest` classes that commit rows now
set a distinct `spring.datasource.url`. They previously shared the one H2 instance that lives for the
whole JVM, so committed orders leaked into `OrderRepositoryTest`, whose assertions are about an empty
table. That was a latent ordering dependency `PlaceOrderFlowTest` had been getting away with by luck;
the new tests made it fail. Isolating per class costs one extra context and migration run each and
removes execution order from the set of things that can break the build.

### Known gaps, deferred on purpose

- The concurrency test runs on H2, where the interesting path does not occur (Phase 7).
- No exponential backoff; the retry delay is fixed with jitter, which is enough for three attempts.
- `order_audit` grows without bound. Retention is an operational concern with nowhere to live yet.
- The audit records what the application attempted, not who attempted it. There are no users
  until Phase 8.

---

## Phase 7 — Integration Testing with Real Infrastructure

**Testcontainers 2.0.5, and the coordinates are not the ones you remember.** The Spring Boot parent
imports `testcontainers-bom:2.0.5`, and 2.x renamed every module with a `testcontainers-` prefix:
`org.testcontainers:testcontainers-postgresql`, not `org.testcontainers:postgresql`. The old
coordinate is not deprecated, it simply has no 2.x release — its last is 1.21.4 — so following any
existing tutorial would pair a 1.x module with a 2.x core. The class moved too, from
`org.testcontainers.containers.PostgreSQLContainer` to `org.testcontainers.postgresql.PostgreSQLContainer`
(the old package survives as a shim). Checked against the BOM and the jar rather than assumed, which
is the only reason it was caught before it became a confusing runtime failure.

**A singleton container, not `@Container`.** `AbstractPostgresIT` holds the container in a `static`
field started from a `static` initialiser, so it is created once per JVM and shared by every
integration test. `@Testcontainers` with `@Container` is the better-known form and does the opposite:
JUnit starts a container before each test class and stops it after, which for four IT classes means
four start/stop cycles for no benefit. Nothing stops the container explicitly either — Testcontainers'
Ryuk sidecar removes it when the JVM exits, including when the JVM is killed, which a shutdown hook
cannot promise.

**The ITs run under `dev`, not a test profile.** They deliberately use the configuration the
application actually runs with — `ddl-auto: validate`, Flyway enabled, the real Hikari settings — and
`@ServiceConnection` swaps only the connection details. The consequence is the valuable part: **the
Flyway migrations are executed against real PostgreSQL on every build**, closing a gap open since
Phase 5, and `validate` proves the entity mappings match what those migrations produce on the real
engine rather than on H2.

**H2 stays for the fast tests.** Moving `@DataJpaTest` onto Testcontainers too was considered and
rejected: the inner loop would then need Docker and would slow down, and a slice test's whole point is
being cheap. The division is now explicit — Surefire runs `*Test.java` with no Docker requirement,
Failsafe runs `*IT.java` against PostgreSQL. The residual risk is a PostgreSQL-specific problem in a
hand-written query that only the ITs would catch, which is a known and accepted trade rather than an
oversight.

**Surefire and Failsafe are not interchangeable.** Surefire fails the build the moment a test fails.
For an integration test that would abandon the run before `post-integration-test`, leaving containers
behind. Failsafe separates the two: `integration-test` records failures and `verify` is what fails the
build, so teardown always happens. It also keeps `./mvnw test` genuinely Docker-free, verified by
running it with `DOCKER_HOST` pointed at a socket that does not exist — 94 tests, green, 7 seconds.

**Container reuse between builds was rejected.** `withReuse(true)` would save the ~1s startup by
leaving the container running for the next build, but it cannot be enabled from the repository (each
developer must opt in via `~/.testcontainers.properties`), and state surviving between runs makes a
test that assumes an empty table pass alone and fail in sequence. One container per run, torn down at
the end, is worth the second.

**A Phase 6 measurement was wrong, and is corrected in place.** Phase 6 reported that the optimistic
lock never fired on H2 (0 in 40 races) and fired every time on PostgreSQL, and concluded the engines
race differently. Moving the test here exposed the flaw: conflicts were counted from the exception the
calling thread finally saw, but `@Retryable` retries the rejected thread and the retry finds the cart
already consumed, so a `ConflictException` surfaces and the lock is invisible from outside. Counted
from the `CONCURRENT_MODIFICATION` audit rows instead, the lock fires on **both** engines — five in
five rounds on each. The Phase 6 entries in this file and in the README now carry that correction
rather than being silently edited. Two lessons worth keeping: a measurement taken from the wrong
vantage point is worse than no measurement, because it gets written down as a finding; and the audit
table introduced in Phase 6 was the reliable evidence all along.

### Known gaps, deferred on purpose

- Slice tests still run on H2.
- Nothing runs the ITs automatically — CI is Phase 11, and it will need a Docker-capable runner.
- The ITs share one container and commit, so they cannot assert on row counts.
- No container reuse between builds.

---

## Phase 8 — Spring Security (Users & Roles)

**Spring Security 7.1.1, from the parent.** No version pinned. Adding the starter secures every
endpoint by default, which is the right default and the reason `WebSecurityConfiguration` has to say
explicitly which endpoints are public rather than which are protected.

**The table is `users`, the class is `Customer`.** USER is reserved in PostgreSQL, so
`CREATE TABLE user` would need quoting in every statement that mentions it. `@Table(name = "users")`
resolves the disagreement once.

**One role per user, stored as a string.** Two roles exist and there is no plan for a third, so a
single `role` column beats a `user_roles` join table: one migration, no join, and building the
authority list is a one-element `List.of`. Stored as the name rather than the ordinal, because
reordering the enum must never silently turn every CUSTOMER into an ADMIN. If a user ever needs two
roles this becomes a join table and a migration — a known, bounded cost.

**The `ROLE_` prefix lives in code, not in the database.** `hasRole("ADMIN")` is Spring Security
shorthand for the authority `ROLE_ADMIN`; `SecurityUser` adds the prefix when it builds the
authorities. The column therefore holds the word a human would use, and the framework's convention
stays in the framework's layer.

**Registration cannot produce an administrator, structurally.** `RegisterRequest` has no `role`
field at all, so there is no payload a client could send to promote themselves — it is not a check
that a future refactor could drop. Administrators are seeded by migration.

**The seeded admin's BCrypt hash is committed.** The password is documented in the README. BCrypt is
one-way so the hash reveals nothing, and the account unlocks a throwaway local database — the same
reasoning as the local PostgreSQL password committed in Phase 4. The alternative (seed a locked
account, set the password out of band) costs an extra manual step on every fresh clone and every
rebuilt database, for a demo credential that grants nothing.

**Password hashing, not encryption.** Encryption is reversible by design; that is the entire point of
it. A password store needs the opposite property — nobody, us included, should be able to recover
what the user typed. BCrypt is one-way, salts every hash (so two people with the same password get
different rows, and one cracked hash unlocks one account), and is deliberately slow, which is what
makes guessing expensive per attempt. `CustomerServiceTest` uses a **real** encoder rather than a
mock for exactly this: a mocked encoder would let "the password is hashed" be asserted as "some
method was called", which would pass just as happily if the hash were the password itself.

**CSRF is disabled because the API is stateless, and that reasoning is only as strong as
"stateless".** The attack needs a credential the browser attaches automatically — a session cookie.
Nothing here is attached automatically: credentials arrive in an `Authorization` header a client sets
deliberately, and no session is ever created. Keeping CSRF on would break every non-browser client to
defend against something that cannot happen. Store a token in a cookie in some later phase and CSRF
comes straight back.

**401 and 403 need their own handler, not the `@RestControllerAdvice`.** `GlobalExceptionHandler`
only sees exceptions thrown from a controller, and authentication and authorization failures happen
in the servlet filter chain, before any controller is reached. Left alone Spring Security answers
with an empty body, so a client parsing `{status, message}` would get a surprise on precisely the two
responses it is most likely to hit. `ApiErrorResponder` implements both hooks; the messages are
deliberately vague, because distinguishing "no such user" from "wrong password" tells an attacker
which email addresses are registered.

**Security configuration is split in two.** `HttpSecurity` and `@EnableWebSecurity` only exist in a
servlet web application, so leaving them in one class broke every
`@SpringBootTest(webEnvironment = NONE)` — a test wanting the services and the database but no
server — with a missing-bean error that had nothing to do with what it was testing.
`WebSecurityConfiguration` carries the rules and `@ConditionalOnWebApplication`; `SecurityConfiguration`
keeps the password encoder and method security, which apply everywhere.

**Someone else's order is 404, not 403.** 403 confirms the resource exists, which turns sequential
ids into a way to count the shop's orders and probe which are real. The ownership check lives in the
WHERE clause (`findByIdAndCustomerWithItems`), so the row is never loaded — a query that *cannot*
return another customer's order is a stronger guarantee than one that returns it and relies on the
next line of code to notice. `@PreAuthorize("#customerId == authentication.principal.id")` sits on
top as belt and braces: the belt that survives a future controller passing the wrong id.

**Controllers take the principal; services take an id.** `@AuthenticationPrincipal` in the controller,
a `Long customerId` parameter into the service. Reading `SecurityContextHolder` inside services would
have been less typing and would have made every one of them silently require a logged-in user, and
untestable without a security context. The service layer stays as free of security as it is of HTTP.

**Reading a cart must not create one.** The integration tests caught this: `getCart` called
`requireCart`, which creates a cart if missing. Harmless while the single shared cart was seeded by a
migration and always existed; a 500 once carts became per-customer, and only for brand new accounts —
the kind of bug that reaches production because it never fires for anyone who already has data.
`getCart` now returns an empty view with a null `cartId`, and the row appears on the first write.

**Orders keep a nullable owner.** Orders placed before this phase have no user and cannot be given
one. `NOT NULL` would have meant deleting them; nullable kept them, at the cost of rows belonging to
nobody that no customer query returns. Measured on the dev database afterwards: 5 orphaned, 2 owned.
The expand step from Phase 5, applied to a column that will simply never be tightened.

### Known gaps, deferred on purpose

- HTTP Basic sends credentials on every request; over the open internet that demands TLS (Phase 9
  replaces it with JWT).
- No password reset, email verification, account lockout or login rate limiting.
- No administrative view of other people's orders; that needs its own endpoint and rule.
- One role per user.

---

## Phase 9 — Stateless Authentication with JWT

**One dependency, not two.** `spring-boot-starter-oauth2-resource-server` pulls in
`spring-security-oauth2-jose`, which carries `NimbusJwtDecoder` *and* `NimbusJwtEncoder` — so the same
dependency both issues and verifies. Adding jjwt or java-jwt alongside it, as most tutorials do, would
put two implementations of the same specification in one application with no benefit.

**The principal stays a `SecurityUser`, and that decision kept the phase small.** A resource server
puts a raw `Jwt` in the security context by default, which would have meant rewriting eight
controllers and three `@PreAuthorize` expressions to read claims — and coupling every one of them to
the token format. `JwtSecurityUserConverter` rebuilds a `SecurityUser` from the claims instead, with
no database read, because the signature already proved the claims are ours and unaltered. Phase 8's
code is untouched, and would survive a third change of authentication mechanism. How a caller proved
who they are is not the business layer's concern.

**HMAC (HS256), not RSA.** One application issues and verifies here, so one shared secret is the
right fit and the simpler thing. The asymmetry matters as soon as there is a second party: with
RS256 every service can verify using a public key while only the issuer can mint, whereas with HMAC
**anyone who can verify can also forge** — every verifying service must hold the key that creates
tokens. That is exactly why this choice stops working at the microservices split (Phase 20) or the
moment an external identity provider appears, and it is worth having felt the constraint before
meeting the solution.

**The key comes from `JWT_SECRET`, and when it is absent one is generated at startup.** Committing a
signing key is categorically different from committing the local database password or the admin's
BCrypt hash, which are the precedents that might have justified it: a signing key lets anyone holding
it mint a token for any user, including the administrator. Generating an ephemeral key keeps a fresh
clone working with no setup and makes the trade visible — tokens stop working at every restart,
because the thing that vouched for them is gone. A key under 256 bits fails at startup with a
sentence saying how to make one, rather than at the first login with a Nimbus error about key lengths.

**No refresh token, deliberately.** The roadmap lists one as optional, and the honest version is not
small. A refresh token is only worth more than a long-lived access token if it can be *revoked*, and
revocation needs server-side storage — a table, a migration, a rotation policy, and a decision about
what happens when a refresh token is replayed. Without that it is a long-lived access token with extra
steps and a wider blast radius. The trade being taught here is the one that matters: **a JWT cannot be
withdrawn**, so the expiry is the only bound on how long a stolen or stale token stays useful, and
choosing 15 minutes is choosing how much damage a leak can do against how often users log in again.

**A failed login is mapped in `GlobalExceptionHandler`, unlike the other 401.** The distinction is
where the failure happens. Missing or invalid *tokens* are rejected in the filter chain, before any
controller, which is why `ApiErrorResponder` exists. A wrong *password* fails inside `AuthController`,
which calls the `AuthenticationManager` itself — so the exception reaches the advice normally, and
without a mapping the catch-all would have reported a wrong password as a 500.

**Test slices import one `SecurityTestConfiguration`.** A `@WebMvcTest` loads controllers, not
configuration, so each security bean has to be asked for explicitly. That list has now grown twice —
once for the Phase 8 rules, once for the JWT decoder — and the failure when a piece is missing is a
context-load error naming a missing bean rather than the test that needed it. Collecting it in one
place means the next addition is a one-line change.

**What OAuth2 and OIDC would add.** This application is currently both halves of OAuth2: the
*authorization server* that issues tokens (`AuthController`) and the *resource server* that accepts
them (everything else). OAuth2 standardises the protocol between those halves, so the issuer can be
somebody else — Keycloak, Entra ID — with standard grant types for cases this login endpoint does not
cover, such as a third-party application acting on a user's behalf without ever seeing their password.
OIDC then adds identity on top of OAuth2's authorization: an `id_token` with standard claims about
*who* the user is, plus a discovery document so a resource server can find the issuer's public keys by
itself. Moving to either would change `AuthController` and `JwtConfiguration` and nothing else, which
is the payoff for validating a signed token rather than a session.

### Known gaps, deferred on purpose

- No revocation, and therefore no logout that means anything server-side.
- No refresh token; sessions end after 15 minutes and the user logs in again.
- Swagger UI's bearer configuration is impossible until Phase 3 adds OpenAPI.
- Tokens travel over plain HTTP locally; anywhere else that demands TLS.
- HMAC does not survive the service split in Phase 20 — that will want RS256 or an external issuer.

---

## Phase 10 — Containerization

**A three-stage build, and the middle one is the interesting one.** Stage 1 compiles with the
project's own `./mvnw` on a JDK; stage 2 explodes the layered jar; stage 3 copies those layers onto a
JRE. Measured here: the build stage is **1.19 GB**, the final image **399 MB**. Everything that makes
the difference — the compiler, the Maven repository, the sources, the 59 MB fat jar — is discarded.
A single-stage build would ship all of it, and the parts that help you build are exactly the parts an
attacker would like to find at runtime.

**`./mvnw`, not a `maven:` image.** `CLAUDE.md` says the wrapper is the only build entry point, and
that has to be true inside the image too, or the container builds with a different Maven than every
developer and CI runner uses.

**The jarmode changed, and every older tutorial is wrong.** Spring Boot 3.3 replaced
`-Djarmode=layertools` with `-Djarmode=tools extract`, and the old form is gone in Boot 4. Verified
against the real jar before writing the Dockerfile. One further trap: `extract` refuses to write into
a non-empty directory, so the jar has to live somewhere other than its own destination — copying it
into the extraction directory fails the build with a message about the directory, not the jar.

**Layers are ordered by rate of change, which is the whole point.** Measured:
dependencies 59 MB, spring-boot-loader 696 kB, snapshot-dependencies 4 kB, application 426 kB. One
`COPY` per layer, least-changing first, so a code change rebuilds 426 kB and reuses the 59 MB. The
same reasoning drives copying `pom.xml` before `src/`: dependency resolution is then cached
independently of source edits, and editing a Java file does not re-download the internet.

**Alpine, with the trade named.** `eclipse-temurin:21-jre-alpine` against `:21-jre` is roughly 399 MB
versus what would be ~80 MB more. Alpine uses musl rather than glibc, which is fine for a pure-JVM
application and would not be if a glibc-only native library ever appeared. Worth choosing
deliberately rather than copying.

**Non-root, and not as a checkbox.** Root in a container is root on the host kernel — the isolation
is namespaces, not a virtual machine — so a container escape from uid 0 starts from a much better
position than one from uid 100. `USER` comes *after* the `COPY`s so the files are owned by root and
the application cannot modify its own installation; `--chown` on each COPY avoids a second layer that
exists only to fix permissions.

**`-XX:MaxRAMPercentage=75.0`, paired with a compose memory limit.** A modern JVM reads the
container's limit rather than the host's, but caps the heap at 25% of it by default — a 768 MB
container would run with a ~192 MB heap and GC constantly while three quarters of its allowance sat
idle. 75% leaves room for metaspace, thread stacks and direct buffers, which live outside the heap
and are counted by the OOM killer; that headroom is why the number is not 90%.

**`depends_on: condition: service_healthy`, not the default.** The default `service_started` means
"the container exists", which is why so many compose files grow a sleep in an entrypoint. With a
`pg_isready` health check the app simply does not start until the database answers, and the startup
log shows it: postgres Started → Waiting → Healthy → app Started.

**PostgreSQL is not published to the host.** The app reaches it as `postgres` on the compose network,
which is the container-networking lesson made structural rather than described: each container has
its own network namespace, so `localhost` inside the app container is the app container. Not
publishing also avoids colliding with the standalone Phase 4 container. Those two databases are
entirely separate, with separate volumes — a point worth remembering when data appears to vanish.

**Secrets via `${VAR:?message}`.** Compose refuses to start with a message naming the variable,
rather than falling back to a default password nobody chose. `.env` was already gitignored from Phase
0; `.env.example` documents what is required and contains nothing usable.

### Cloud Native Buildpacks, compared by building both

`./mvnw spring-boot:build-image` needs no Dockerfile at all. Both images were built and measured:

| | Dockerfile | Buildpacks |
|---|---|---|
| Image size | **399 MB** | **755 MB** |
| Layers | 11 | 20 |
| Runs as | `ecomdemo` (uid 100) | uid 1002 |
| Build time (cold) | ~2 min | ~2.5 min |
| Dockerfile to maintain | yes | none |

**What Buildpacks get right for free:** a non-root user, a sensible JVM memory calculator, layered
output, reproducible builds, and — the real argument — *someone else patches the base image*. When a
CVE lands in the JRE, a rebuilt buildpack image picks up the fix without anyone editing anything. A
Dockerfile pins a base image that somebody has to remember to bump.

**Why this project uses a Dockerfile anyway:** the phase is about understanding containers, and a
Dockerfile is where the concepts are visible — stages, layer ordering, `USER`, the JVM flag. A
buildpack does all of that correctly and invisibly, which is exactly wrong for learning and exactly
right for production. The 356 MB difference is the honest cost of the transparency; Buildpacks are
what I would reach for on a team that would rather not own this file.

### Known gaps, deferred on purpose

- Base images are pinned by tag, not digest, so `21-jre-alpine` can change beneath the build.
- The image is never pushed anywhere - a registry is part of CI in Phase 11.
- The health check probes a business endpoint because Actuator is Phase 15.
- No resource limits on the postgres service, and no non-root user for it either - the official
  image handles that itself, but it is not something this project controls.

---

## Phase 11 — Continuous Integration

**One workflow, two jobs, rather than two workflows.** `publish` declares `needs: verify`, which is
what makes an untested image impossible to publish: the job is skipped entirely if the tests failed.
Two separate workflows would have to coordinate through `workflow_run`, which is more machinery for a
weaker guarantee.

**Both `pull_request` and `push: [main]`.** Testing only pull requests would leave a gap that squash
merges make real: the commit that lands on `main` is a new commit, and `main` can contain a
combination neither branch had. Running again after the merge is what catches that, and it is also
the trigger that publishes.

**Permissions are read-only at the top and widened per job.** `packages: write` is granted to
`publish` alone, so the job that compiles and runs tests - the one that executes the most untrusted
code, including anything a dependency does at build time - cannot write to the registry. The
repository default is read-only as well; the workflow states its own permissions anyway, so it keeps
its posture if that default is ever loosened.

**`GITHUB_TOKEN`, not a personal access token.** It is minted for the run, scoped by those
permissions, and expires when the job ends - so there is no secret to create, store or rotate, and
nothing tied to a person who might leave. This is the one case where "no secrets in source control"
is satisfied by there being no secret at all.

**Two tags per image, because they answer different questions.** The commit SHA is immutable and
identifies exactly one commit: it is what a deployment references, what a rollback needs, and what
makes "which code is running?" answerable. `latest` is mutable and always moves - useful for a human
trying the current build, and actively wrong for anything that must be repeatable, since two machines
pulling it an hour apart can get different code. Semantic version tags would be the third kind, and
need releases to exist first.

**Caching, in two places.** `setup-java`'s Maven cache is keyed on the hash of `pom.xml`, so a
dependency change correctly invalidates it and everything else reuses it. The Docker layer cache
(`type=gha`) means the 59 MB dependency layer built in Phase 10 survives *between* runs - that
layering paid off within a build before; now it pays off across builds.

**A workflow guards only the branches that carry it.** Discovered while demonstrating the gate: the
first throwaway PR was branched from `main`, which did not yet have `ci.yml`, so **no checks ran at
all**. The PR was still blocked - by a required check that never reported - which looks identical
from the outside and is a completely different failure. Worth knowing before concluding that CI is
"not working": for `pull_request` events GitHub uses the workflow from the PR's head.

**GHCR rejects uppercase.** `ghcr.io/${{ github.repository }}` expands to
`ghcr.io/mr-sujay-patil/EcomDemo` and fails on every push. The name is lower-cased in its own step.
The registry is case-sensitive in a way the repository name is not.

**Branch protection keeps `enforce_admins: true`.** The required check applies to the repository
owner as well, which is the only version of the rule that means anything - a gate you can walk around
is a suggestion. The Phase 11 pull request had to satisfy its own check before it could merge. The
existing settings (linear history, conversation resolution, zero required approvals) were preserved:
the protection API replaces the whole object, so a PUT that omits them silently turns them off.

**Dependabot groups the Spring family.** Fifteen separate pull requests for artefacts that are
released together and only work together is noise, not diligence; one grouped PR is a change somebody
will actually review. The cap of five open PRs is the same reasoning - an unread pile of thirty is
worse than five that get looked at.

### Known gaps, deferred on purpose

- No quality gate beyond "the tests pass" - coverage and static analysis are Phase 12.
- Actions are pinned to mutable major tags (`@v4`) rather than commit SHAs.
- The image is published but never deployed; that is Phases 25-26, and the difference between
  continuous delivery and continuous deployment.
- No cache for the Testcontainers PostgreSQL image, so every run pulls it.
- No release/tag-triggered workflow, so there are no semantic version tags on images yet.
