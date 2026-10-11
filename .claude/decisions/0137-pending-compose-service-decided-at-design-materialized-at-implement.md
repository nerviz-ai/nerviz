# 0137 · A compose service a spec needs is decided at design time and materialized by the implement run

- **Date:** 2026-10-11
- **Scenario:** Issue #119, triaged at `eed3e00`: `new-feature-implement` never relays the pending compose services recorded by the design partials, and its final report has no item for them, so after the commit nothing names the missing broker.
- **Decision:** Form 3 — `.claude/agents/compose-service-installer.md`, class `installer`, launched by `/new-feature-implement`; the decision vocabulary in `.claude/skills/docker-architect/references/compose-service-decision.md`
- **State:** approved by the maintainer, on 2026-10-11

## Reproduced on disk

The confirmed rows of the `issue-verifier` table at `eed3e00` (no commit since touched a cited file):

| # | Fact | Evidence |
|---|---|---|
| 1 | `messaging-architect` and `persistence-architect` step 9 record a missing service in the partial's § 6 and never write compose | `messaging-architect/SKILL.md:358-361`, `persistence-architect/SKILL.md:389-391` |
| 2 | `/new-feature` names the pending service in its final report, which ends with `/clear` | `new-feature/SKILL.md:645-656` |
| 3–5 | Nothing in `new-feature-implement/SKILL.md` or `java-spring-boot-developer.md` reads, relays or materializes it | `new-feature-implement/SKILL.md:65-69`, `:194-205` |
| 6–7 | The ITs start the broker through Testcontainers, and `compose` treats a test-only pin as healthy, by design | `ArchHook.java:955-957`, `:1255-1257`, decision `0052` |
| 8 | The runtime default points at `localhost:${KAFKA_EXTERNAL_PORT:29092}` | `messaging-architect/templates/application-kafka.yml.example:32` |
| 10 | Unchanged since `5e2a79c` (#115) | `git log` |

Checked in this session, not in the table: `new-feature/SKILL.md:65` says "the consolidated
spec carries the list", but `new-feature/templates/feature-spec.md.example` has no section for
it. The list lives only in the partial's § 6, which the implement thread never reads.

Refuted, so not an input: the guard does **not** let `new-feature-implement` call a
`build`-class skill (row 9). It refuses the call, and decisions `0126` and `0135` intend that.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Observed in a real run: `/new-feature` → `/clear` → `/new-feature-implement`, and the broker never existed after the commit | create nothing |
| 7 — mandatoriness | Can fail. The report is enough as the guarantee: a miss costs minutes, and the app fails on the first local start | 7, 8 |
| 5 — nature | A question during design, then a step of the implement run | 4, 5 |
| 8 — destination | Both: every piece touched travels in `export` | — |
| 9 — integration | `docker-architect` is the single owner of every service block (`0029`). The guard refuses it from the implement thread (`0126`), and skips the `Skill` call of a classed agent (`ArchHook.java:6459-6470`) | Option C |
| — user's direction | The spec must say whether the service already exists or runs elsewhere, or should be added to compose. Design asks when messaging (or a database) comes up, and `new-feature-implement` materializes it | report-only as the whole answer |

## Common part — every option below carries it

1. **Design asks.** `persistence-architect` and `messaging-architect` step 9: when the service
   is missing from `docker-compose.yml`, `AskUserQuestion` with three answers. *Already running
   outside compose*: record host and port, materialize nothing. *Add to compose*: record it as
   `materialize`. *Leave it to me*: record it as `pending`, with the `/docker-architect` command.
2. **The spec carries it.** A `## Infrastructure` section in `feature-spec.md.example`, one row
   per service: service, decision, image tag, command. Consolidation copies it from the
   partials' § 6. `none` is valid, and absence is not (same gate as the declared-dependency
   lists). This makes `new-feature/SKILL.md:65` true.
3. **Implement reports it.** `new-feature-implement` § 4 gets one more item: every
   `## Infrastructure` row, with what this run did to it, or "none".

The options differ only in who writes `docker-compose.yml` for a `materialize` row.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | New agent `compose-service-installer`, class `installer`, which invokes `docker-architect` | 8 | **Approved** |
| 2 | `java-spring-boot-developer` gains the compose territory | 5 | Rejected — widens the executor to infrastructure and loads a 420-line skill into a group (`0130`) |
| 3 | Report only: implement relays the rows and prints `/docker-architect` | 6 | Rejected as the whole answer — kept as option 1's fallback when the agent fails |
| 4 | Give `new-feature-implement` a class without `design_phase`, and let it call `docker-architect` itself | 4 | Rejected — reverses `0126`, leaks the `opus` pin into `git-publish` |

### Option 1 — `.claude/agents/compose-service-installer.md` (score 8)

**Motivator:** axis 9. The guard skips a classed agent's `Skill` call, so an agent can carry
`docker-architect`'s procedure into the implement run without changing the guard.

**Shape:** implement launches it via `Agent` after the `tests` group ends green and before
`git-publish`, only when the spec has a `materialize` row. This mirrors how the pre-flight
launches `commons-logging-installer`. The agent invokes `docker-architect` with the UC folder
and `project:` as context, runs `java .claude/hooks/ArchHook.java compose`, and returns a
fixed summary. `git-publish` then stages the compose change in the same commit as the code.

**Pros:**
- `docker-architect` stays the single owner of the procedure and the service block. The agent
  carries the procedure and holds no compose knowledge of its own.
- The skill's ~420-line body and its `opus` pin stay out of implement's `sonnet` thread and out
  of every executor group.
- The territory is narrow and fixed: the `write_allow` of `skill_classes.build` for
  `docker-architect`.
- Precedent: `project-initializer` chains skills under the same guard rule (issue #99), and
  implement already launches an installer.
- Invariant 5: reason 1 (preserve context) and reason 3 (the `opus` pin must not leak past this
  work, `0080`).

**Cons:**
- One more piece: an agent file, an `agent_classes.installer` override, `export.agents.include`,
  and an `AgentTerritoryTest` case.
- A background agent has no `AskUserQuestion` (`0123`). It holds because `docker-architect`
  asks nothing when it is given a UC folder (step 2), and step 2.5 runs only on a manual call.
  A future question in that path would break this silently.

**Points cut in the rubric:** criterion 5 (maintenance) — one more agent.

**CI:** `validate` › `schema` covers the class, sections, fields and `export` entry.
`AgentTerritoryTest` gets a case: the agent writes `docker-compose.yml` and is refused on
`src/**`. A case in `SkillTerritoryTest`: a `Skill(docker-architect)` call carrying this
`agent_type` passes while `new-feature-implement`'s phase is open.

### Option 2 — `java-spring-boot-developer` writes compose (score 5)

**Pros:** no new piece. The executor already reads the spec, and implement already chains it.

**Cons:**
- The executor's territory grows to `docker-compose.yml`, `docker/**`, `.env` and `.gitignore`,
  which is infrastructure an implementer of Java never needed (criterion 8).
- If it writes the block itself, `docker-architect` is no longer the single owner (invariant 2).
  That is the `schema-registry` failure of lessons-learned-012 §§ 12–13: no template, no tag
  check, no healthcheck.
- If it invokes `docker-architect`, the 420-line body enters a group's context, and every later
  turn of that group pays for it. `0130` split the executor into groups to remove exactly that
  cost. The executor runs on `sonnet`, and `docker-architect` is pinned to `opus` because of an
  observed design failure (a broker advertised only on the compose network). It is not verified
  whether a skill's `model` overrides inside a subagent.
- It mixes a fourth concern into the agent with the largest body in the repository.

**Points cut:** criteria 1, 3, 5, 8, and criterion 2 when it writes the block itself.

**CI:** `AgentTerritoryTest` — the executor's case grows to compose paths.

### Option 3 — report only (score 6)

Common part only: the run names every `materialize` and `pending` row, and prints
`/docker-architect UC-NNN-<slug>` to type after `git-publish`. It does not do what the user
asked (implement in charge), and the service lands in a commit separate from the code that
needs it. It is what options 1 and 2 fall back to when the agent fails.

**CI:** `schema` alone — a body change.

### Option 4 — implement calls `docker-architect` itself (score 4)

Needs `new-feature-implement` out of `orchestrator` (a new class, or a per-skill `design_phase`
override, Form 7c). `0126` rejected dropping `design_phase` from `orchestrator`. The `opus` pin
would also hold for the rest of implement's turn (`0080`), so `git-publish` runs on `opus`,
which `0135` moved off on purpose. Rejected: it changes the guard to get what option 1 gets
with none.

## Open point — no `docker-compose.yml` at all

The user asked for "create docker-compose if it does not exist". `docker-architect` step 1
stops when the base pair is missing, because the base pair is `project-bootstrap`'s (once, at
generation). A generated project always has it, and an adopted one may not. Accepted: the
design question offers *Add to compose* only when the file exists. Otherwise it records the row
as `pending`, with the reason and the `project-bootstrap` pointer. Changing who writes the base
pair is a separate decision.

**Where the vocabulary lives.** The question and the four decisions (`exists`, `external`,
`materialize`, `pending`) are needed by two design skills, consolidation, the implement run and
the agent. Writing them in each would be a norm in five places (invariant 2), so they live once
in `docker-architect/references/compose-service-decision.md`: that skill owns every service
block, and every other piece cites the reference.

## References

| Claim | Source |
|---|---|
| A classed agent's `Skill` call is skipped by `guard call` | `ArchHook.java:6459-6470`, `@.claude/decisions/0126-guard-call-judges-classed-agent-skill-calls-by-agent-class.md` |
| Implement keeps `design_phase` on purpose | `@.claude/decisions/0135-new-feature-split-spec-and-implement.md`, `new-feature-implement/SKILL.md:34-39` |
| `docker-architect` is the single writer of service blocks | `docker-architect/SKILL.md` § Contract, `@.claude/decisions/0029-docker-architect-skill.md` |
| Why a design run no longer writes compose | `@.claude/decisions/0058-skill-classes-territory-schema.md`, lessons-learned-012 §§ 12–13 |
| A background agent has no `AskUserQuestion` | decision `0123`, `agent_classes.driver.$comment` |
| The executor was split into groups to cut per-turn context | `@.claude/decisions/0130-executor-split-by-block-group-implement-in-clean-session.md` |
| An installer launched from implement, by `Agent` | `new-feature-implement/SKILL.md` § 2 step 1, `commons-logging-installer` |
| The pin of a procedural skill holds for the rest of the turn | `@.claude/decisions/0080-procedural-skills-on-sonnet.md` |

## Propagation

| File | Change |
|---|---|
| `.claude/agents/compose-service-installer.md` | New agent |
| `.claude/skills/docker-architect/references/compose-service-decision.md` | New — the question and the four decisions, single owner |
| `.claude/schemas/extensions.json` | `agent_classes.installer`: the agent, its override (= `docker-architect`'s `write_allow`), the `$comment`; `export.agents.include` |
| `.claude/skills/persistence-architect/SKILL.md`, `messaging-architect/SKILL.md` | Step 9 asks and writes `## Compose services`; report and contract |
| `.claude/skills/persistence-architect/templates/persistence-spec.md.example`, `messaging-architect/templates/messaging-spec.md.example` | `## Compose services` section |
| `.claude/skills/new-feature/SKILL.md` | Consolidation gate on `## Compose services`, the final report item, the docs-only paragraph |
| `.claude/skills/new-feature/templates/feature-spec.md.example`, `feature-spec-short.md.example` | `## Infrastructure` section |
| `.claude/skills/new-feature-implement/SKILL.md` | § 2 item 3 launches the agent; § 4 reports every row; contract and references |
| `.claude/skills/docker-architect/SKILL.md` | Fifth invocation path; boundary rows |
| `CLAUDE.md`, `README.md`, `docs/en/**`, `docs/pt-br/**` | Routing, agent lists, pipeline diagrams, CI case counts |

Goes to the generated project: **yes** — the agent through `export.agents.include`, the
reference with the `docker-architect` folder, the rest with the skills that already travel.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › guard keeps each agent inside its class's territory` | The agent writes every path `docker-architect`'s territory names, and is refused application config, the POM and the spec | green on the tree · red with `.env` dropped from the override (`compose-service-installer writes .env — … expected exit 0, got 2`) |
| `validate · hooks-cross-platform › guard keeps each skill inside its class's territory` | Under `/new-feature-implement`'s phase, the agent's `Skill(docker-architect)` passes and its compose write lands, while the main thread is still refused both | green on the tree |
| `validate · design › schema` | The agent's class, sections, fields, `**Executor:**` and catalog markers, and its `export` entry | green on the tree · red with the agent dropped from `export.agents.include` (`is in neither export.agents.include nor .exclude`) |

The agent's procedure itself — `docker-architect` run inside an implement session — needs a
model run and a Docker daemon, so nothing in CI exercises it end to end. What CI holds is the
territory that lets it work.
