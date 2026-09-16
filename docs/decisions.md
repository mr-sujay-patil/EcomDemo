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

**H2 and PostgreSQL race differently, and it changes what the test proves.** `ConcurrentOrderTest`
asserts the invariant (one order, zero stock, never oversold) rather than which error the loser got,
because that depends on the interleaving. Measured: on H2 the two transactions serialise and the
version conflict fired **zero times in 40 races** — the loser was always turned away by an already
empty cart. Against real PostgreSQL over HTTP it fired in **5 of 5 races**, and the audit trail shows
the full path each time: `PLACED`, then `CONCURRENT_MODIFICATION`, then `EMPTY_CART` on the retry,
returning 409. So the end-to-end test is necessary but not sufficient on H2, and `OptimisticLockTest`
exists beside it to pin the lock down deterministically by creating the stale write in a fixed order.
Phase 7 (Testcontainers) is what would let the concurrency test itself run on PostgreSQL.

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
