# 0148 · Sonar's java:S4502 on SecurityConfig is a finding accepted by design, exempted by rule and file

- **Date:** 2026-10-13
- **Scenario:** Issue #132, triaged at 18586b2: security-architect's SecurityConfig.java.example disables CSRF by design, but neither Sonar exception list in sonarqube-setup (pom-sonar.xml.example, build-gradle-sonar.example) mutes java:S4502 for it, so every project with security-architect inherits the finding
- **Decision:** no new piece — a `sonar.issue.ignore.multicriteria` entry in both `sonarqube-setup` build templates, a pointer back in `SecurityConfig.java.example`, and a `migrations` entry for projects already configured
- **State:** approved by the maintainer, on 2026-10-13

## Reproduced on disk

Confirmed rows of the `issue-verifier` table for #132 at `18586b2`, layer `static`. No commit
since touches a cited file.

| # | Claim | Evidence |
|---|---|---|
| 1 | The template disables CSRF | `skills/security-architect/templates/SecurityConfig.java.example:84`, right after `STATELESS` |
| 2 | Its Javadoc conditions that on no cookie authenticating a request | `SecurityConfig.java.example:16-17` |
| 3 | The norm states the same condition | `rules/authorization.md:95-97` § Transport hardening |
| 4 | The condition is checked by a manual grep only | `rules/authorization.md:152-154` |
| 5 | Both Sonar templates exempt `s2326,s112` and nothing for S4502 | `pom-sonar.xml.example:37`, `build-gradle-sonar.example:35` |
| 6 | S4502 is a vulnerability, `SECURITY: HIGH` | Unproven by the verifier (server output only). Settled here from SonarJava's own rule metadata on `master`, `sonar-java-plugin/.../rules/java/S4502.json`: `"type": "VULNERABILITY"`, tag `former-hotspot`. An older server that still raises it as a hotspot is not muted by multicriteria; the entry then matches nothing, harmlessly |
| 7 | Every project whose `Access` row brings in `security-architect` receives the class | `skills/security-architect/templates/security-spec.md.example:58` |
| 9 | The idempotency templates already point back at their entries | `IdempotencyAspect.java.example:50-52`, `IdempotentExecution.java.example:36` |
| 10 | `// NOSONAR` is out | `rules/code-quality.md:56-57`; `0096` option 23.3 |

The issue's proposed fix was not an input; the options below come from these rows.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 5 — Nature | A declarative entry in templates that already exist | 1, 2, 3 — no procedure |
| 7 — Mandatoriness | Not a guarantee: a finding accepted by design, muted where it is produced | 7, 8 |
| 8 — Destination | Both — `security-architect` and `sonarqube-setup` are in `export.skills.include` | — |
| 9 — Integration | The exemption list's owner is `sonarqube-setup`'s templates (`0098` F) | 4, 5 — a rule cannot name a template (invariant 1) |
| Propagation to configured projects | `sonarqube-setup` runs once; a `migrations` entry carries it through `/arch-adopt` | — |
| Build-time check of the CSRF condition | None — no exposure was observed | 7c |
| 17 — CI | Nothing testable (below) | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | multicriteria entry + template pointer + `migrations` entry | 8 | **Approved** |
| 2 | multicriteria entry + template pointer only | 6 | Rejected — leaves already configured projects red; the cut `0098` F recorded |
| 3 | Option 1 + an ArchUnit rule "CSRF off ⇒ STATELESS, no `formLogin()`" | 3 | Rejected — unsound as specified, and a guarantee with no observed failure |
| 4 | `// NOSONAR` on the line | 1 | Rejected — mutes every rule on the line; `code-quality.md:56-57`, `0096` 23.3 |

### Option 1 — exemption, pointer, migration (score 8)

**Motivator:** axis 9 — `0098` F already fixed where a template's accepted finding is muted,
and its scope line ("Findings accepted by design in code the framework's own templates ship")
covers `SecurityConfig` exactly.

**Pros:** same shape as S2326 and S112; scoped by rule and file, so a second CSRF disable
elsewhere still reports; the entry's comment cites `authorization.md` instead of restating the
condition (invariant 2); configured projects get a note and a prompt that check the condition
before adding the entry.

**Cons:** one more migration entry to carry. The multicriteria mutes the file whether or not
the condition still holds — the day a cookie authenticates and CSRF goes back on, the entry
must go too. The template's Javadoc and the entry's comment both say so.

**Points cut in the rubric:** maintenance (−1, a twin list in two build templates);
verification (−1, nothing in CI ties the entry to the condition).

**CI:** nothing testable — below.

### Option 3 — ArchUnit rule (score 3)

ArchUnit sees calls per class, not per filter chain; the authentication mechanism is applied
from another class (`SecurityConfig.java.example:102`, `mechanism.applyTo(http, …)`); and "no
`formLogin()`" admits HTTP Basic, which `LocalUsersSecurityConfig.java.example:18-20` names as
a browser-ambient credential that needs CSRF on. And no CSRF exposure was observed: the mirror
of invariant 6.

## References

| Claim | Source |
|---|---|
| Template findings accepted by design are muted by rule and file in both Sonar templates | `0098` § F, `0096` § 23.1 |
| CSRF off for a stateless bearer API | `0112:47`; `rules/authorization.md` § Transport hardening |
| A finding in project code is fixed, not muted — templates are not project code | `skills/sonarqube-setup/SKILL.md` step 3 |
| A convention change that leaves configured projects behind is a `migrations` entry | `migrations.$comment` in `schemas/extensions.json`; precedent `sonar-coverage-exclusions` |
| S4502's type | SonarJava `S4502.json` on `master` |

## Propagation

| File | Change |
|---|---|
| `skills/sonarqube-setup/templates/pom-sonar.xml.example` | `s4502` in the list, its two keys, and its reason line citing `authorization.md` |
| `skills/sonarqube-setup/templates/build-gradle-sonar.example` | The same list and keys |
| `skills/sonarqube-setup/SKILL.md` | Step 3 names `SecurityConfig` among the templates that point back |
| `skills/security-architect/templates/SecurityConfig.java.example` | Javadoc pointer to the exemption, and its removal with CSRF |
| `schemas/extensions.json` | `migrations` entry `sonar-s4502-securityconfig` |

Goes to the generated project: **yes** — both skills through `export`, the entry through
`sonarqube-setup` on its next run, and a configured project through the migration
`/arch-adopt` prints.

## CI coverage

Nothing new is testable. Whether Sonar flags a construct depends on the server and its rule
set, so a template that ships a new flagged construct without an entry — the defect behind
#132 — cannot be caught from the files. Twin divergence between the Maven and Gradle lists,
or an entry outliving its template, was never observed. The existing `validate` job's
`schema` step covers the `migrations` entry's required fields and `id_pattern`, and its
blueprint names.
