# 0178 · `messaging-sqs` leaves the blueprints until an AWS skill can carry it

- **Date:** 2026-10-20
- **Scenario:** "retire dos blueprints a feature SQS, hoje não temos skills e nem templates para
  tratá-la, voltará futuramente quando tiver a skill aws-architect"
- **Decision:** Form 2 — no new piece. `messaging-sqs` leaves the blueprints, and
  `project-bootstrap` § 2 and `blueprints/_schema.md` gain rule 7. Option 1.
- **State:** approved by Lucas Fernandes, on 2026-10-20

## Reproduced on disk

| # | Fact | Evidence |
|---|---|---|
| 1 | Every blueprint declares `messaging-sqs` | `features:` of the nine blueprints and `custom.template.yaml`, always `false` |
| 2 | Turning it on adds a starter and nothing else | `project-bootstrap/references/dependency-catalog.md:11` (`spring-cloud-aws-starter-sqs` by hand). `messaging-architect` is Kafka-only (`0032`, `rules/messaging.md`), and no template under `.claude/skills/**/templates/` names SQS |
| 3 | `/init-project` offers it as a choice | `init-project/SKILL.md:52`, and the Kafka-and-SQS question in `docs/{pt-br,en}/02-init-project.md` |
| 4 | Without SQS, every built-in blueprint leaves **one** feature `false` (Kafka) | the `features:` blocks. A multi-select with one option is refused with the whole batch (`docs/pt-br/11-pitfalls.md` § `AskUserQuestion`, `too_small`) |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | A feature the interview offers, and that no skill or template delivers past the starter | create nothing |
| 7 — mandatoriness | A user blueprint with `messaging-sqs: true` must not generate silently. The refusal names SQS only, not every feature without a catalog row | the generic rule (option 3) and ignoring the key |
| 8 — destination | Both. `use-case-design` and `docker-architect` travel, and so does the active blueprint (`export.blueprint_copy`) | — |
| 9 — integration | `docker-architect`'s "SQS via LocalStack" example goes too. A compose service with no adapter skill behind it is infrastructure nothing uses | — |
| — return path | An `aws-architect` item in `roadmap.md` § Future ideas, plus this record | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Retire the key, refuse it by name at validation | 8 | **Approved** |
| 2 | Keep the key at `false`, with a comment saying it is unsupported | 4 | Rejected: the dead option stays in the interview |
| 3 | Retire the key and add a generic rule: refuse any `true` feature with no catalog row | 6 | Rejected by the user in the interview: a SQS-only refusal |
| 4 | A `schema` check that every blueprint feature has a catalog row (Form 7c) | 3 | Rejected: this failure was never observed, and SQS *had* a row (mirror of invariant 6) |
| 5 | Create nothing | 2 | Rejected: the interview keeps offering a feature that delivers a starter and no adapter |

### Option 1 — retire `messaging-sqs` (score 8)

**Motivator:** axis 1. `/init-project` asks a question whose "yes" produces a dependency and no
procedure.

**Change:**
- Remove the `messaging-sqs: false` line from the nine blueprints and `custom.template.yaml`.
- `project-bootstrap/references/dependency-catalog.md`: remove the `messaging-sqs` row. The module
  table's `kafka`, `sqs` row becomes `kafka`.
- `project-bootstrap/SKILL.md` § 2 rule 7, and `blueprints/_schema.md` § Validation rules rule 7:
  `features.messaging-sqs` is not `true`. Otherwise stop. The message names the retirement
  (`0178`) and says to set the key `false` or remove it. `_schema.md` is a leaf, so it cites this
  record and names no skill.
- `init-project/SKILL.md` step 2, *Features*: the list loses SQS. When the blueprint leaves exactly
  one feature `false`, the question becomes a single-select with two options, on and off.
- `use-case-design/SKILL.md:77`: "Kafka or SQS" becomes "Which broker".
- `docker-architect/SKILL.md:383`: the example list loses "SQS via LocalStack".
- `docs/{pt-br,en}/02-init-project.md`: the worked example offers Kafka alone, as a yes/no
  question. The diagram's rule count changes from 6 to 7.
- `docs/{pt-br,en}/05-blueprints.md`: the validation rules list gains rule 7, and the count changes
  from 6 to 7.
- `roadmap.md` § Future ideas: `aws-architect`, which brings SQS (and SNS) back as a feature with
  its adapter skill.

**Pros:** the interview offers only what the pipeline delivers. A user blueprint that asks for SQS
stops at step 2 with a reason. It does not get a starter with no adapter.

**Cons:** the SQS-only rule is dead text once `aws-architect` exists, and it has to come out then.
The Kafka question changes shape from multi-select to yes/no.

**Points cut in the rubric:** Enforcement. Rule 7 is prose the agent runs at step 2, not a
`schema` check.

**CI:** `validate › schema` covers the frontmatter, class and sections of the four edited skills.
`blueprints.yml` runs because a blueprint and `project-bootstrap` change. It regenerates all nine
skeletons and proves they still build and gate. It does not read `features`, by its own header. The
refusal itself is model behaviour, so nothing in CI can test it.

### Option 3 — generic rule (score 6)

Same removal. Rule 7 would refuse any feature set `true` that has no row in
`dependency-catalog.md`. That would cover the next retirement too. The user chose the SQS-specific
refusal.

### Option 4 — `schema` check (score 3)

A Form 7c addition that would cross-check the blueprint `features` against the catalog. Nobody saw
a feature with no catalog row. SQS had one, so this check would not have caught the case at hand.

## References

| Claim | Source |
|---|---|
| A rejected `AskUserQuestion` with fewer than two options sinks the whole batch | `docs/pt-br/11-pitfalls.md` § `AskUserQuestion` |
| Every feature stays a choice at generation time (no messaging-only shortcut) | `0125` § A2, rejected by the user |
| `messaging-architect` covers Kafka only, and other brokers join it later | `0032`; `0162` § Scope |
| `blueprints/` is a leaf and names no skill | `@CLAUDE.md` § Architecture of the AI files |
| A feature is a dependency and its configuration, never code | `blueprints/_schema.md` § What `features` decides |
| The active blueprint travels | `@CLAUDE.md` invariant 9, `export.blueprint_copy` |

## Propagation

| File | Change |
|---|---|
| `.claude/blueprints/*/*.yaml` (nine) and `custom-template/custom.template.yaml` | `messaging-sqs: false` removed |
| `.claude/blueprints/_schema.md` | validation rule 7 |
| `.claude/skills/project-bootstrap/SKILL.md` | § 2 rule 7 |
| `.claude/skills/project-bootstrap/references/dependency-catalog.md` | `messaging-sqs` row removed; module table `kafka` only |
| `.claude/skills/init-project/SKILL.md` | step 2 *Features*: SQS out, single-select for one `false` feature |
| `.claude/skills/use-case-design/SKILL.md` | "Kafka or SQS" becomes "Which broker" |
| `.claude/skills/docker-architect/SKILL.md` | "SQS via LocalStack" out of the example list |
| `docs/{pt-br,en}/02-init-project.md`, `05-blueprints.md` | Kafka asked as yes/no; 7 validation rules |
| `roadmap.md` | `aws-architect` under Future ideas |

Goes to the generated project: **yes, via step 6.6** — `init-project` stays out, but `project-bootstrap`
travels with the active blueprint (`export.blueprint_copy`), which no longer carries the key. No
`migrations` entry: no SQS code was ever generated, so no project is left behind.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate › frontmatter schema` | the four edited skills keep their frontmatter, class and sections | green on the tree (`schema` exit 0) |
| `validate › boundary-map` (`BoundaryMapTest`) | the nine blueprints still derive a boundary map without the key | green on the tree |
| `blueprints.yml` | the nine skeletons still generate, build and gate; path-filtered, runs on this PR | runs in CI |

Rule 7 is prose the agent runs at step 2 — model behaviour, nothing testable in CI. No red run was
injected: no new check was written.
