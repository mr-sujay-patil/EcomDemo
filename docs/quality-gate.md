# Quality gate: EcomDemo

The gate lives in the SonarQube instance, not in this repository — it is created through the API or
the UI, and a rebuilt `sonar-data` volume loses it. This file is the record needed to recreate it.

| Metric | Operator | Threshold | Why |
|---|---|---|---|
| `new_coverage` | `LT` | 70 | New code must be tested. 70 is the phase's figure; the project currently sits at 100% on new code and 92.8% overall, so 80 would also pass. |
| `new_violations` | `GT` | 0 | No new issues of any severity. Strict on purpose: it is far easier to keep a clean project clean than to clean a dirty one. |
| `new_blocker_violations` | `GT` | 0 | Explicit, so the intent survives if `new_violations` is ever relaxed. |
| `new_critical_violations` | `GT` | 0 | The phase's "no new critical issues". |
| `new_duplicated_lines_density` | `GT` | 3 | Inherited from Sonar way. |
| `new_security_hotspots_reviewed` | `LT` | 100 | Every new hotspot must be *looked at* — a hotspot is a question, not a defect. |

## Why every condition is about *new* code

A legacy project with 40% coverage cannot reach 80% in one sprint, and a gate demanding it is
switched off within a week. Gating new code instead means quality improves monotonically with every
change, without anyone being asked to stop and fix the past. The Sonar term for this is "clean as you
code".

The cost, seen in this phase: on a project's **first** analysis there is no baseline, so there is no
new code, so the gate passes having evaluated nothing.

## Recreating it

```bash
TOKEN=<an analysis token from the SonarQube UI>
SQ=http://localhost:9000

curl -s -u "$TOKEN:" -X POST "$SQ/api/qualitygates/create" -d "name=EcomDemo"
# A new gate is pre-populated from "Sonar way", so some conditions already exist and adding them
# again returns 400. Update those instead of creating them:
curl -s -u "$TOKEN:" "$SQ/api/qualitygates/show?name=EcomDemo"      # find the condition id
curl -s -u "$TOKEN:" -X POST "$SQ/api/qualitygates/update_condition" \
     -d "id=<id>&metric=new_coverage&op=LT&error=70"
curl -s -u "$TOKEN:" -X POST "$SQ/api/qualitygates/create_condition" \
     -d "gateName=EcomDemo&metric=new_critical_violations&op=GT&error=0"
curl -s -u "$TOKEN:" -X POST "$SQ/api/qualitygates/set_as_default" -d "name=EcomDemo"
```
