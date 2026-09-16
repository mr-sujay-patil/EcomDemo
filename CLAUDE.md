# CLAUDE.md

Conventions for this repository. Read this before making changes.

## What this project is

**EcomDemo** is a learning project: an e-commerce backend that grows from a single Spring Boot
monolith into a production-grade distributed system, adding **exactly one** technology per phase.

The purpose is understanding, not shipping a product. That constraint drives everything below — code
is written to be read and explained, and a phase never "sneaks in" a technology that belongs to a
later one.

- **Roadmap (all 31 phases):** [`docs/ROADMAP.md`](docs/ROADMAP.md)
- **Decision log (why things are the way they are):** [`docs/decisions.md`](docs/decisions.md)

## Stack

| | |
|---|---|
| Language | Java 21 (the build targets `release 21` regardless of the local JDK) |
| Framework | Spring Boot 4.1.1 |
| Build | Maven, via the committed wrapper — always `./mvnw`, never a system `mvn` |
| Database | H2, in-memory (PostgreSQL arrives in Phase 4) |

## Build commands

```bash
./mvnw clean verify       # full build + tests - run this before every commit
./mvnw test               # tests only
./mvnw spring-boot:run    # start on http://localhost:8080
```

Do not stop a running `spring-boot:run` by deleting `target/` — `clean` pulls the classes out from
under the running JVM. Stop the process first.

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
contain a try/catch, and no service should mention HTTP.

**Explicit fetching.** `@ManyToOne` is always marked `FetchType.LAZY` (its default is EAGER). When a
query needs an association, load it with `left join fetch` rather than relying on lazy loading —
`spring.jpa.open-in-view` is `false`, so a lazy access outside the transaction fails loudly.

**Comments explain *why*, not *what*.** The code says what it does; a comment earns its place by
recording a trade-off or a non-obvious constraint.

## Testing conventions

**Naming:** `methodName_condition_expectedResult`. A failure report should read as a sentence.

**Structure:** explicit `// GIVEN`, `// WHEN`, `// THEN` comments. **AssertJ** for every assertion,
including controller tests. Compare money with `isEqualByComparingTo`, never `isEqualTo` —
`BigDecimal.equals` compares scale too.

**Pick the cheapest level that can catch the bug:**

| Level | Annotation | Loads | Use for |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | nothing | every service method — success path **and** at least one failure path |
| Web slice | `@WebMvcTest(XController.class)` | controller, Jackson, validation, error advice | status codes, JSON shape, headers, exception → status mapping |
| JPA slice | `@DataJpaTest` | Hibernate, repositories, embedded H2 | hand-written `@Query` only — never Spring Data's generated methods |
| Integration | `@SpringBootTest` | everything, real port | keep it to one flow test; the pyramid's apex stays small |

**Spring Boot 4 specifics.** `@WebMvcTest` and `@DataJpaTest` come from the separate
`spring-boot-webmvc-test` and `spring-boot-data-jpa-test` modules. `@MockBean` is removed — use
`@MockitoBean`. Controller tests use `MockMvcTester`, not classic `perform(...).andExpect(...)`.

**Mock at the boundary you own.** A service test mocks the *collaborating service*, not that
service's repository — otherwise a refactor in one feature breaks another feature's tests.

**Prefer a stub to a mock for value-like collaborators.** `Clock.fixed(...)` over `mock(Clock.class)`:
it is a real implementation with known behaviour and needs no stubbing.

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
