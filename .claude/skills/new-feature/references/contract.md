# Contract of `/new-feature`

Moved out of the body of `SKILL.md` by `@.claude/decisions/0196-new-feature-body-cut-quoted-globs-and-audit-effort.md`:
the body keeps a summary and the `**Class:**` line `schema` cross-checks (`@.claude/decisions/0058-skill-classes-territory-schema.md`).
The text below is the former § Contract, unchanged.

## Contents

- Class and territory
- Ownership
- Reads
- Writes, indirectly via layer skills
- Writes outside `docs/`: none
- Writes, directly
- Never writes under `src/`; never runs git
- Integrates with
- Why REST and messaging both run before persistence — and the order of every other step
- Design order isn't implementation order
- `gof-design-patterns` is not a pipeline step

**Class:** orchestrator — the territory is `skill_classes.orchestrator` in
`@.claude/schemas/extensions.json` and `ArchHook.java guard` enforces it. A run writes under
`docs/` only, and `ArchHook.java guard` also refuses a `Skill` call to a `build`-class skill
while this run is open: a design run does not materialize files, it records what is missing.

**Ownership:** Feature zero→spec orchestrator. Owns the sequence, the input table, the
spec lifecycle (`status:`), and the final output (`UC-NNN-spec.md`). Does **not** own the
use case number or slug — `use-case-design` does.

**Reads:**
- `pom.xml`, `.claude/forbidden-imports.txt`, and this project's domain package,
  discovered and never assumed — validates the generated project
- `docs/use-cases/UC-*/` — folder existence and each spec's `status:` line, by command
- `docs/use-cases/UC-NNN-<slug>/*.md` — the partials of the case being run

**Writes (indirectly via layer skills):**
- `docs/use-cases/UC-NNN-<slug>/00-use-case.md` and `docs/use-cases/BACKLOG.md` — via
  `use-case-design`
- `docs/use-cases/UC-NNN-<slug>/10-domain.md` — via `domain-modeling`
- `docs/use-cases/UC-NNN-<slug>/20-persistence.md` — via `persistence-architect`,
  migration SQL included as a code block, never as a file under `src/`
- `docs/use-cases/UC-NNN-<slug>/25-messaging.md` — via `messaging-architect`, only if
  `10-domain.md`'s Events block names external (Kafka) delivery
- `docs/use-cases/UC-NNN-<slug>/35-jobs.md` — via `jobs-architect`, only if
  `25-messaging.md` chose Form B or `00-use-case.md`'s trigger is a schedule
- `docs/use-cases/UC-NNN-<slug>/30-rest.md` — via `rest-api-architect`, unless `00-use-case.md`'s
  `Trigger` names `inbound GraphQL adapter` alone (step 3)
- `docs/use-cases/UC-NNN-<slug>/31-graphql.md` — via `graphql-api-architect`, only if the `Trigger`
  names `inbound GraphQL adapter` or `inbound REST and GraphQL adapters` (step 3g)
- `docs/use-cases/UC-NNN-<slug>/32-security.md` — via `security-architect`, only if
  `00-use-case.md`'s `Access` asks for something the project's filter chain does not
  already give (step 3b)
- `docs/use-cases/UC-NNN-<slug>/28-http-client.md` — via `http-client-architect`, only if
  `10-domain.md` § Ports declares a port of kind `external HTTP` (step 3c)
- `docs/use-cases/UC-NNN-<slug>/29-ai.md` — via `ai-architect`, only if `10-domain.md` § Ports
  declares a port of kind `LLM` or `vector store` (step 3d)
- `docs/use-cases/UC-NNN-<slug>/23-search.md` — via `search-architect`, only if
  `00-use-case.md`'s `Search index` row names a projection (step 3e)
- `docs/use-cases/UC-NNN-<slug>/22-cache.md` — via `cache-architect`, only if
  `00-use-case.md`'s `Freshness` row states a tolerance (step 5b)
- `docs/use-cases/UC-NNN-<slug>/40-tests.md` — via `test-architect`

**Writes outside `docs/`: none.** A run is docs-only, and that is enforced, not promised:
`ArchHook.java guard` refuses a `Skill` call to a `build`-class skill — `docker-architect`
included — while this orchestrator's phase is open, and refuses a write outside
`skill_classes.orchestrator`'s territory. A partial that needs a service the compose file
doesn't declare **asks** whether to add it, and records the answer in its `## Compose services`
(`persistence-architect` step 9, `messaging-architect` step 9); the consolidated spec carries
the list in `## Infrastructure`, and `/new-feature-implement` adds every `materialize` row
after the code is green — never this run. Why: a design run once hand-wrote a `schema-registry` block
into `docker-compose.yml`, with no template, no tag verification and no healthcheck
(`lessons-learned-012.md` §§ 4, 12, 13), and the review was of a spec nobody read that file
through. Record: `@.claude/decisions/0058-skill-classes-territory-schema.md`

**Writes (directly):**
- `docs/use-cases/UC-NNN-<slug>/UC-NNN-spec.md` — implementation plan (executor-ready),
  including its `status:` line (`draft` → `approved`)

**Never writes under `src/`.** Neither does any skill it chains. Every file under `src/`
— migrations included — belongs to `java-spring-boot-developer`, delegated by
`/new-feature-implement`, which also owns the one-time setup pre-flight
(`@.claude/skills/new-feature-implement/SKILL.md`).

**Never runs `git add`, `git commit`, or `git push`.** Neither does any skill it chains.
Git happens only through `git-publish`, which the person types — the final report prints the
command, this flow never invokes it (`@.claude/decisions/0182-git-publish-printed-not-chained-by-the-feature-flows.md`).
No end of this flow is left without an explicit git instruction.

**Integrates with** the skill each step of § Procedure names on its `**Runs:**` line, in the written order of the headings; when a step runs, is skipped, or what it reads is that step's own body and field lines. The heading ids are the only step numbers.

**Why REST and messaging both run before persistence.** The order follows who generates
requirements for whom. REST generates schema requirements — `Idempotency-Key` on a
creation `POST` needs the shared key table — and persistence generates none for REST.
With persistence first, the table was discovered after the partial was written, and
persistence ran twice.

Messaging is the same shape and was learned the same way: a transactional outbox needs the
shared `outbox_events` table, a consumer needs the shared dedupe table, and neither is
visible from the domain partial alone. With messaging after persistence, `20-persistence.md` was
written without the relay's retry columns and its partial index, and persistence ran twice
again. Messaging depends only on step 2, so running it before persistence costs nothing —
and persistence then reads both requirement lists, `30-rest.md` block 4 and
`25-messaging.md` § 6, in its first pass.

Jobs sits between them for the same reason. The scheduling technology it picks brings tables
(`shedlock`, `QRTZ_*`, `BATCH_*`), the relay's claim depends on how many instances run the
pass, and the prune job needs a bounded delete — three schema facts invisible to persistence
until `35-jobs.md` § 3 and § 6 exist. Jobs itself needs only the use case and messaging's
publication form, so it costs nothing to run first
(`@.claude/decisions/0087-jobs-architect-skill.md`).

GraphQL runs right after REST and before security, the REST step's pair. Both shape the same input
ports, and when the trigger names both adapters the GraphQL partial reads the REST one, so each
operation names its counterpart. It generates requirements for the steps after it: a batch port the
domain did not declare, the batch statement and an index for persistence, and an access row per
root field and per sensitive field for security — none visible before `31-graphql.md` § 9 exists
(`@.claude/decisions/0146-graphql-api-architect-skill.md`).

Security runs right after REST and GraphQL, for the same two reasons in opposite directions. It needs the
endpoints `30-rest.md` fixed — access rules written before them protect a guess — and it can
generate schema requirements: users owned by the application need a credentials store, an
API key needs a key store, both invisible to persistence until `32-security.md` § 7 exists.
Ownership is not one of them: it is an invariant `domain-modeling` already modeled from the
`Access` row (`@.claude/decisions/0112-security-architect-skill.md`).

The HTTP client runs right after security and before messaging, for the same reason in one
direction: it generates requirements for the steps after it. A non-idempotent write to another
system needs its idempotency key persisted with the operation, and an unknown outcome needs a
reconciliation pass — a column persistence cannot see and a job scheduling cannot see until
`28-http-client.md` § 10 exists. It reads the trigger's deadline from `30-rest.md` and whether a
filter chain exists from `32-security.md`, so it waits for both
(`@.claude/decisions/0121-http-client-architect-skill.md`).

The AI ports run right after the HTTP client and before messaging, for the same reason: a vector
store needs its table and extensions created by a migration, and the records it mirrors need a
re-index on every change and a first fill — a schema fact for persistence and a trigger for
messaging or jobs, invisible to all three until `29-ai.md` § 9 exists. It reads the trigger's
deadline from `30-rest.md`, so it waits for REST, and it needs nothing messaging, jobs or
persistence decide (`@.claude/decisions/0145-ai-architect-skill.md`).

The search projection runs right after the AI ports, before messaging, for the same reason: an
index is fed by the source's change events, rebuilt by a job and keyed on the source's version —
a consumer for messaging, a rebuild for jobs, a version field and an outbox event for persistence,
invisible to all three until `23-search.md` § 8 exists. Whether the case has a search index, and on
which engine, is `00-use-case.md`'s row; this orchestrator never decides it
(`@.claude/decisions/0147-document-storage-and-search-architect.md`).

The cache runs after persistence, the one step here that generates no requirement for anyone
before it: a cache adds no table and no column. It wraps an outbound port, so it waits for the
adapters behind it — `20-persistence.md`'s query and `28-http-client.md`'s call — and it
lists every writer of the data it holds, which only the finished partials name. Tests run
after it, because a cached read owes three tests no other layer asks for
(`@.claude/decisions/0144-cache-architect-skill.md`).

**Design order isn't implementation order.** GraphQL is block 4.1, implemented by the conditional
Block G right after Block 3 and before Block S — its resolvers call the same ports as the REST
controllers, and Block S's annotations go on both. Messaging is designed fourth and implemented
sixth: `UC-NNN-spec.md` keeps messaging as block 6, and the executor's conditional Block M
still runs between REST and Tests. Jobs is block 7, implemented by the conditional Block J
right after Block M — a trigger compiles against the inbound port it calls, so it comes last
among the production blocks. Outbound HTTP is block 3.3, implemented by the conditional Block H
right after Block 2 — an adapter compiles against the outbound port and the domain types, and
nothing upstream compiles against it. AI is block 3.7, implemented by the conditional Block A
right after Block H, for the same reason. Security is block 4.5, implemented by the conditional Block S
right after Block 3 — its annotations and the actor resolution go on the controllers Block 3
just wrote. Cache is block 3.4, implemented by the conditional Block C last among the production
blocks, after Block J — a decorator compiles against the port and needs its delegate, and every
writer that must go through it already exists. The pipeline orders by who needs whose requirements;
the spec orders by what compiles against what.

`git-publish` is not a step: § Approval prints its command for the person to type.

The executor, `java-spring-boot-developer`, is not a step of this skill: `/new-feature-implement`
delegates to it over an `approved` spec, in a clean session.

`gof-design-patterns` is not a pipeline step, and this orchestrator never invokes it — a
design run cannot reach a `build`-class skill. **Its catalog is still applied at design
time:** each design skill above runs its § Design-time use for its own layer and writes a
`## Design patterns` section into its partial, so the classes a pattern creates are in the
spec the user approves, not chosen by the executor afterwards. The executor implements
those rows, and applies the catalog on its own only for a symptom already on disk
(`@.claude/decisions/0089-design-patterns-decided-at-design-time.md`, superseding the
executor-side trigger of 0077).
