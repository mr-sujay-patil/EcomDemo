## What changed

<!-- What does this PR do, and why? Written for a reviewer who has not seen the issue. -->

**Phase:** <!-- e.g. Phase 1 - Git & GitHub Workflow, or "n/a" for a fix outside a phase -->

**Technology introduced:** <!-- The ONE new technology this phase adds, or "none" -->

## How it was tested

<!--
Be specific. "Tests pass" is not enough - say what you actually ran and what you saw.
Paste the curl commands, the endpoints you hit, or the test names that cover the new behaviour.
-->

```
./mvnw clean verify
```

## Checklist

- [ ] `./mvnw clean verify` passes locally
- [ ] All existing tests still pass, and new behaviour has tests
- [ ] Only the technology named in this phase was introduced — no "while we're at it" additions
- [ ] Every dependency added is a stable GA version, compatible with the project's Spring Boot version
- [ ] No secrets, credentials or personal data in the diff
- [ ] Commits follow Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`)
- [ ] `README.md` updated with what this phase added and how to try it
- [ ] `docs/decisions.md` records the decisions worth remembering
- [ ] The Progress Tracker in `docs/ROADMAP.md` is updated

## Notes for the reviewer

<!--
Anything that needs explaining: a trade-off you made, a deviation from the plan and why,
or something you deliberately left out for a later phase.
-->
