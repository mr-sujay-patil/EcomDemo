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
