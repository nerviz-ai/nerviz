# 0006 · `rest-api-architect` aligns with D15 and leaves stub — resolves P7 and P5

- **Date:** 2026-09-07
- **Scenario:** unblock `rest-api-architect` (CONTEXT.md § 7, stage 2, item 10). Two
  pending items blocked the procedure: P7 (does the skill write code or spec?) and P5
  (eight form decisions raised on 2026-09-06 when closing `api-rest.md`).
- **Decision:** P7 resolved by the recommended outcome — **align with D15**. P5 resolved
  by adopting the eight recorded recommendations, with two corrections that P7 forces
  (see § P5).
- **Status:** applied by default on 2026-09-07, under the clause of P5 itself ("default
  recommendation to follow if no one decides"). Supersedable by a new record.

## P7 — the skill emits spec, not code

`domain-modeling` (D15) and `persistence-architect` already emit a partial and stop.
`rest-api-architect` declared it wrote both `**/adapter/in/rest/**` **and**
`30-rest.md`.

| # | Outcome | Score | Verdict |
|---|---|---|---|
| 1 | Align with D15 — emits `30-rest.md`, the executor writes the code | 9 | **Approved** |
| 2 | Reopen D15 — each layer skill writes its own layer's code | 3 | Rejected |

**Motivator for 1:** the executor receives the four partials and is the only one to touch
`src/main/java`. A single owner per code path (invariant 2). And the inconsistency D15
declared closes without touching two skills already done.

**Why 2 fails:** would require amputating "domain and application code" from
`java-patterns`'s contract, rewriting `domain-modeling` and `persistence-architect`, and
turning the executor into a batch mode. Three pieces touched to align one. Option 1
touches one.

**Direct consequence:** `ApiExceptionHandler`, the DTOs and the controller are no longer
written by this skill. They stay in `templates/*.example` — they're form exemplars, which
is what invariant 3 mandates, and the executor reads them.

## P5 — the eight decisions

The recommendations recorded in `CONTEXT.md` § 6 adopted. Two change form because of P7;
flagged.

| # | Decision | Resolved |
|---|---|---|
| a | Endpoint contract test | **Corrected by P7.** The skill writes no test. It fixes the cases — status, `errorCode`, body shape — in block 5 of `30-rest.md`. The executor writes the test code. `testing` remains owner of `40-tests.md` (pyramid, slices, data); there aren't two owners because the artifacts differ: here the contract's **cases**, there the **strategy** |
| b | `Idempotency-Key` key | Table in the database. The rule requires the key and the effect in the same transaction, and Redis isn't transactional with Postgres. **Splits into two halves:** header presence and format are structural and live in an adapter filter (400); recording the key and the business effect live in the application layer, in the same transaction. The table belongs to `20-persistence.md` — `30-rest.md` declares that it's needed and names it, doesn't model it |
| c | Source of `traceId` | Micrometer Tracing. `Tracer` injected into `ApiExceptionHandler`; `tracer.currentSpan().context().traceId()`. Aligns with the planned `observability.md` and closes the exemplar's correction |
| d | Who installs springdoc | **Corrected by P7.** The skill doesn't edit `pom.xml`. It declares the dependency in block 4 of `30-rest.md`, with the version **resolved at runtime** (invariant 8) — springdoc isn't in the Spring Boot BOM, so the version is explicit and never written from memory |
| e | `openapi.json` diff job in CI | `project-bootstrap`, owner of `.github/workflows/*`, conditional on `feature: rest`. Two owners of the same file is the bad alternative. **Left undone** — see § Propagation |
| f | DTO↔domain mapper | Manual, in static methods. MapStruct brings an annotation processor and hides the mapping. **Superseded by `@.claude/decisions/0160-mapstruct-as-a-per-project-mapper-choice.md`** (2026-10-14): manual stays the default, MapStruct is a per-project choice recorded once |
| g | `/api/v1` prefix | Explicit in `@RequestMapping`. `server.servlet.context-path` hides the version and prevents `v1` and `v2` from coexisting |
| h | Default pagination | Offset. Cursor only when requested |

## `disable-model-invocation` stays — SUPERSEDED THE SAME DAY

> **Superseded by `@.claude/decisions/0007-pipeline-skills-invocation.md`**
> (2026-09-07). The field was removed from the four pipeline skills: it hid them from the
> model, and the `/new-feature` orchestrator planned in D14 wouldn't be able to call them.
> The text below stays as it was — it records what was decided on the date, not what
> holds today.

`CONTEXT.md` § 7 item 10 said to remove it when unblocking the skill. It doesn't get
removed, and the reason has changed: the item was written before `domain-modeling` and
`persistence-architect` existed. Both ended up with `disable-model-invocation: true`
because they write files into `docs/use-cases/**` — a side effect this repository doesn't
trigger on its own — and because the procedure interviews the user. Both reasons apply
equally here. The three pipeline skills are invoked by hand, by the same rule.

This record supersedes that line of item 10.

## References

| Statement | Source |
|---|---|
| Layer skills emit spec, not code | D15 · `@.claude/decisions/0003-skill-domain-modeling.md` |
| The inconsistency was declared and had to be resolved before the procedure | `CONTEXT.md` § 6, P7 |
| The eight decisions and the default recommendations | `CONTEXT.md` § 6, P5 |
| Idempotency key and business effect in the same transaction | `@.claude/rules/api-rest.md` § Idempotency |
| `traceId` required on every 5xx, same as the log's | `@.claude/rules/api-rest.md` § Errors — 500 family |
| OpenAPI generated from code, never hand-written | `@.claude/rules/api-rest.md` § OpenAPI |
| Boilerplate lives in `templates/*.example` of the skill that emits it | `@CLAUDE.md` invariant 3 |
| A theme with a single owner, cited by path | `@CLAUDE.md` invariant 2 |
| Versions never from memory; resolved at runtime | `@CLAUDE.md` invariant 8 |
| A new skill has to reach the generated project | `@CLAUDE.md` invariant 9 · `project-bootstrap/SKILL.md` steps 6.6 and 6.7 |
| Form precedent for the procedure and the partial | `.claude/skills/persistence-architect/SKILL.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/rest-api-architect/SKILL.md` | rewritten — stops being a stub, gains a procedure |
| `.claude/skills/rest-api-architect/templates/ApiExceptionHandler.java.example` | `traceId` and `violations` |
| `.claude/skills/rest-api-architect/templates/rest-spec.md.example` | NEW — shape of the partial |
| `.claude/skills/rest-api-architect/templates/Controller.java.example` | NEW |
| `.claude/skills/rest-api-architect/templates/Dtos.java.example` | NEW |
| `.claude/skills/rest-api-architect/templates/RestMapper.java.example` | NEW |
| `.claude/skills/rest-api-architect/templates/PageResponse.java.example` | NEW |
| `.claude/skills/rest-api-architect/templates/IdempotencyKeyFilter.java.example` | NEW |
| `.claude/skills/rest-api-architect/templates/ContractTest.java.example` | NEW |
| `.claude/skills/rest-api-architect/references/best-practices-links.md` | NEW |
| `CLAUDE.md` | routing table loses the "(stub)" |
| `.claude/skills/use-case-design/SKILL.md` | partials map loses the "(stub)" |
| `.claude/skills/project-bootstrap/SKILL.md` | step 6.7 and closing note |
| `CONTEXT.md` | P5 and P7 closed, inventory, stage 2 item 10 |

**Left undone, and declared:** the `openapi.json` diff job in CI (P5 e) belongs to
`project-bootstrap`, not this skill. It stays open until someone writes it there.

Goes to the generated project: **yes** — step 6.7, already there.
