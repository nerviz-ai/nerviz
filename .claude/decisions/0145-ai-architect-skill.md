# 0145 · A design skill owns outbound AI ports: the model suggests, the domain decides, Spring AI only in an `.ai` adapter

- **Date:** 2026-10-12
- **Scenario:** "Criar a skill de design ai-architect (roadmap H2, item 13, piloto #1) e a norma
  rules/ai.md: partial para portas de saída implementadas com Spring AI, passo condicional do
  /new-feature que dispara quando 10-dominio.md declara uma porta de kind `LLM` ou `vector store`."
- **Decision:** Option 1 — Form 1 `.claude/skills/ai-architect/SKILL.md` (class `design`, partial
  `29-ia.md`) + Form 4 `.claude/rules/ai.md` (territory `.ai` and `prompts/`); port kinds `LLM` and
  `vector store` in `domain-modeling`; an `.ai` package key in every blueprint and
  `org.springframework.ai..` forbidden in every `application` module; `/new-feature` runs it as
  step 3d, after outbound HTTP and before messaging.
- **State:** approved by Lucas Fernandes, on 2026-10-12 — option 1 with every planned decision of
  its table; implemented on a feature branch, released as a minor version

## What the context showed

Nothing in this repository owns an outbound call to a language model or a vector store today:

| Concern | Owner today |
|---|---|
| A port whose implementation calls a model | nobody — `domain-modeling` knows three kinds: `persistence` · `messaging` · `external HTTP` |
| Prompt, structured output, what the model's answer becomes in the domain | nobody — no norm, no template, no executor block |
| `org.springframework.ai..` reaching the application layer | nobody — the multi-module blueprints forbid `org.springframework..` in `domain` only; `application` forbids `jakarta.persistence..` and `org.springframework.web..` |
| Timeout, retry and failure of the model provider | `http-client.md` and `error-handling.md` § Integration families hold the norm, but no design step applies it to a vendor SDK |
| A pgvector service in the compose file | nobody — `docker-architect` has no vector store template |
| What personal data goes in a prompt | `personal-data.md` § In transit holds the norm; no design step asks it for a prompt |

No `/new-feature` run has shown an AI defect yet. What the model gets wrong without a piece is
taken from the official documentation, not from a run — the research below.

## Research — Spring AI (2026-10-12)

Read against the Spring AI reference documentation as of 2.0.1, and Spring Initializr that day:
Boot 4.1.1 resolves `spring-ai-bom` 2.0.1, every Spring AI dependency carries the range
`[4.0.0,4.3.0-M1)`, and no 3.x Boot version is offered. Versions as read that day, never written
into a template (invariant 8). Every item is re-verified against its page before
`references/pitfalls.md` is written; two are open and marked so.

| Group | Pitfall | Source |
|---|---|---|
| API break | 2.0 removed `internalToolExecutionEnabled`, `toolNames()`, `FunctionCallback`, `ChatOptions` setters and `copy()` (immutable builders, `mutate()`), the default temperature 0.7, and the `.options` segment of property keys — code written from 1.x memory does not compile or silently loses its options | *Upgrade notes* |
| Wiring | `ChatClient.Builder` is prototype-scoped; a client built with `ChatClient.create/builder(chatModel)` skips observations and customizers | *ChatClient* |
| Wiring | The default model differs between a provider's property and its options — a model left unset is whatever that source defaults to | provider chat pages |
| Output | `.entity(Class)` is best effort; `validateSchema()` revalidates and resends the error; native structured output is silently ignored when the options do not implement `StructuredOutputChatOptions`; OpenAI rejects a top-level array; OpenAI `strict` is off | *Structured Output* · *OpenAI Chat* |
| Prompt | `PromptTemplate` takes a `Resource`; ST's `{}` delimiters collide with JSON in the prompt | *Prompts* |
| Vector store | `SearchRequest` defaults to `topK` 4 and `similarityThreshold` 0.0 (accepts everything); no store creates its schema by default; pgvector needs `vector`, `hstore`, `uuid-ossp`, HNSW stops at 2000 dimensions, a new `dimensions` recreates the table | *Vector Databases* · *PGvector* |
| Ingestion | `TokenTextSplitter` defaults; the metadata enrichers call a model; a reader built from a user-supplied URL fetches whatever it names | *ETL Pipeline* |
| Failure | `spring.ai.retry.*` defaults to 10 attempts with exponential backoff up to 3 minutes; OpenAI and Anthropic run on the vendor SDK with their own timeout and `max-retries`. **Resolved while writing:** the 2.0 OpenAI and Anthropic models never read `spring.ai.retry` (no `RetryTemplate` in their source); the SDK's `max-retries` is the only layer. The Ollama timeout no longer matters — no exemplar uses it | *Retry* · *OpenAI Chat* · *Anthropic Chat* · source at `v2.0.1` |
| Cost | `ChatResponse.getMetadata().getUsage()`; `gen_ai.client.token.usage` and `gen_ai.client.operation.seconds` are recorded by the observations already in place | *Observability* |
| Data | Prompt, completion, tool content and vector query logging are off by default, each with a sensitive-data warning | *Observability* |
| Test | `spring-ai-spring-boot-testcontainers` has no pgvector service connection; the evaluators call a real model; the reference gives no guidance on stubbing `ChatModel` | *Testcontainers* · *Evaluation Testing* |

`spring-ai-community/spring-ai-agent-utils` was weighed and left out: pre-1.0, community, and
its tools (shell, filesystem, subagents) are a coding agent's, a risk inside a business service.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | None observed in a run. A planned roadmap item; the maintainer decides to anticipate on the documented pitfalls — anti-pattern 9 overridden on purpose, as in `0144` | Create nothing (as the whole answer) |
| 2 — trigger | Model decision inside `/new-feature` when `10-dominio.md` declares a port of kind `LLM` or `vector store`, and standalone by description | Form 2 |
| 5 — nature | Multi-step procedure: inherit the port and its domain types, decide what the answer becomes, what data goes out, failure, wiring, tests | Forms 4 and 5 as the whole answer |
| 6 — isolation | None — the interview is the task | Form 3 |
| 7 — mandatoriness | Persuasion, plus the guarantee that already exists: `forbidden_imports` read by `guard`/`check` and the ArchUnit test. No new hook without an observed failure (anti-pattern 16) | Forms 7 and 8 as new pieces |
| 8 — destination | Both — a development skill, travels in `export.skills.include` | — |
| — scope | Structured output and semantic search (embedding model + vector store). Question-answering over a corpus (retrieval-augmented generation), tool calling and chat memory are out of this version: the skill stops and records the case as out of scope | A RAG template; a `@Tool` template; a chat memory repository |
| — version line | Spring AI 2.x on Boot 4 only. The skill reads the project's Boot version; on Boot 3 it stops and says the 1.x line is not covered | 1.x variants of every exemplar |
| — tests | A stub `ChatModel` contract test; an HTTP-level IT against a recorded provider response (WireMock) for timeout, retry and failure mapping; a vector store IT on a real pgvector container with a stub `EmbeddingModel`. No model download, no evaluator | An Ollama IT, in CI or under a tag; evaluators |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `skills/ai-architect/` (Form 1, class `design`, partial `29-ia.md`) + `rules/ai.md` (Form 4, territory `.ai`) + kinds `LLM` and `vector store` in `domain-modeling` + an `.ai` package key in every blueprint + `org.springframework.ai..` forbidden in `application` | 7 | **Approved** |
| 2 | Same skill and rule, no package key: the model adapter in the `.http` package, the vector store adapter in `.persistence` | 5 | Rejected — the rule would load on every HTTP client and repository file |
| 3 | No new skill: `LLM` as a variant of `http-client-architect`, norms as a section of `http-client.md` | 4 | Rejected — that interview never reaches the prompt, the answer, the data sent or the store |
| 4 | Create nothing | 3 | Rejected — the maintainer chose to anticipate (axis 1) |

### Option 1 — skill + `rules/ai.md` + two port kinds + `.ai` package (score 7)

**Motivator:** axis 1 (the maintainer's decision to anticipate, on documented pitfalls) and the
scope answer — an AI port has a domain half nobody asks today (what an untrusted answer becomes)
and a code half no norm covers (prompt, output, store, provider failure).

**Pros:** one owner for "what the model is asked, with which data, and what its answer may
change"; the domain half asked where domain facts are asked, so the model never decides a state on
its own — its answer passes a value object and lands in a review state by default; the executor
gets the norms by `paths` on a territory of their own; the import boundary is data the existing
guarantee already reads; same shape as `0087`, `0121` and `0144`.

**Cons:** wide propagation, the size of `0144`'s — `domain-modeling` (two kinds),
`new-feature` (step 3d, consolidation, precedence, spec block), the executor (a new block in the
`adapters` group), `new-feature-implement`, `test-architect`, `docker-architect` (a pgvector
image variant and its decision row), eight blueprints, `extensions.json`, routing and docs.
Anticipation, not a symptom: if no run ever needs it, it is paid in maintenance for nothing.

**Planned decisions inside the option:**

| # | Question | Answer |
|---|---|---|
| 1 | Partial and position | `29-ia.md`, step 3d — after outbound HTTP (3c), before messaging (4). It generates requirements for the steps after it: the store's table for persistence, an ingestion or re-embedding job for jobs, a review queue for messaging. It reads `30-rest.md` for the trigger's deadline and `32-seguranca.md` for who is asking |
| 2 | Port kinds | `LLM` and `vector store`. Embedding is not a kind of its own: a similarity search needs the vectors stored |
| 3 | Data sent to the provider | The skill asks `personal-data.md` § In transit's question per field of the prompt — full, reduced or reference — and records the answer in the partial. No masking advisor in this version |
| 4 | Version line | 2.x / Boot 4 only, the Boot version read from the build file, the BOM from the Initializr at write time |
| 5 | Tool calling, chat memory, RAG | Out of this version; the skill stops and records |
| 6 | Provider failure | The three integration families, unchanged: refused, 429, 503 → `DependencyUnavailableException`; timeout → `OutcomeUnknownException` (retry-safe, a model call changes nothing); an unreadable body → `DependencyResponseException`. An answer outside the schema, or a refusal, is not an exception: it is the domain's review state. Exactly one layer retries: the provider SDK's `max-retries` — verified, the 2.0 OpenAI model never reads `spring.ai.retry.*`, so the two layers do not stack |
| 7 | Infrastructure | A `postgres` row whose image is `pgvector/pgvector` — on a project whose `postgres` service exists, the decision is the image, not a second service; the compose tag and the IT's `DockerImageName` must agree. A variant inside `postgres-service.yml.example`, not a template of its own (see *Found while writing*). Ingestion handed to `jobs-architect` |
| 8 | Tests | To `test-architect`: the three cases of the tests answer above |
| 9 | Territory | A `packages.map` key ending in `.ai` in every blueprint; `derived_paths["ai.md"]`; `org.springframework.ai..` added to `application`'s `forbidden_imports` where a blueprint has the module |
| 10 | The rule | A leaf citing `http-client.md`, `error-handling.md` § Integration families, `secrets.md`, `personal-data.md` and `observability.md` by path; it restates none of them |

**Points cut in the rubric:** 1 (form fit — § 3 of the decision matrix asks for a signal, and
there is none; overridden by the maintainer) · 5 (maintenance: many files touched).

**CI:** `validate` › `design` › `frontmatter schema` already proves the skill's class, sections,
`export` entry and the rule's `paths` and `derived_paths`; the leaf, single-owner and "every norm
paths matches some blueprint" steps cover the rule. New: (a) `.claude/.ci/AiTemplateTest.java` in
`validate` › `design` — fails by name when an AI exemplar builds a `ChatClient` bare, leaves the
model unset, writes a prompt as a string literal instead of a `classpath:/prompts/` resource,
searches without a `similarityThreshold`, turns on content logging, configures two retrying
layers, carries a 1.x API name, or holds a key that is not `${…}`; and when any exemplar outside
`ai-architect` imports `org.springframework.ai`; (b) `exemplar-imports` gains the Initializr
dependencies `spring-ai-openai` and `spring-ai-vectordb-pgvector`; (c) the pgvector compose
variant is covered by `design` › `compose service templates merge and parse`; (d) the blueprint
data — an `.ai` key in each, the import forbidden in each `application` module — held by the same
`AiTemplateTest` (the `check` mode reads whatever `forbidden_imports` holds, and `BoundaryTest`
already proves it blocks).

**Planned shape:**

```
domain-modeling ── port kind LLM | vector store, domain types, review state ──┐
                                                                               ▼
new-feature  3 REST ─► 3b security ─► 3c HTTP ─► 3d ai-architect ─► 4 messaging ─► 4b jobs ─► 5 persistence
                                                  │ reads 00, 10 (ports, review state), 30, 32
                                                  │ writes 29-ia.md: operations, prompt resource,
                                                  │   output and its value object, data sent,
                                                  │   failure table, retry layer, store and its
                                                  │   schema, configuration, declared dependencies,
                                                  │   test cases, requirements to other partials,
                                                  │   deferred
                                                  ▼
executor  group adapters ── new block loads rules/ai.md by paths on the blueprint's .ai package
test-architect ── stub contract · WireMock IT · pgvector IT
docker-architect ── pgvector image, decision row exists/external/materialize/pending
```

### Option 2 — no package key (score 5)

No blueprint package edits, but `ai.md` would load on every HTTP client and every repository file,
and `http-client.md` and `persistence.md` on every AI file — norms written for `RestClient` engines
and JPA mappings applied to a vendor SDK and a vector store. Cut: 3, 5, 6 (`0087`, `0121` and
`0144` gave each new adapter kind its own key).

### Option 3 — a variant of `http-client-architect` (score 4)

A model call is HTTP underneath, but that skill's interview — engine, pool, outbound
authentication, idempotency key — does not reach the prompt, the output's value object, the data
sent or the store; a vector store is a database, not an HTTP call. The norms would grow
`http-client.md` with a second subject, and the provider's SDK owns the engine the rule pins.
Cut: 1, 2 (one norm, two subjects), 5.

### Option 4 — create nothing (score 3)

What the decision matrix recommends with no symptom (anti-pattern 9). With nothing in place, a case
that declares a model call reaches the executor with no kind, no review state, no timeout decided
and Spring AI free to enter the application layer.

## References

| Claim | Source |
|---|---|
| A pipeline design piece is Form 1, not Form 2 | decision matrix § 4 · `decisions/0007-pipeline-skills-invocation.md` |
| No agent: the interview is the task | decision matrix § 5 |
| No signal → create nothing, unless overridden | decision matrix § 3 and anti-pattern 9 |
| Norm in one owner, cited by path; a rule never names a skill | `@CLAUDE.md` invariants 1 and 2 |
| Boilerplate only in `templates/*.example` | `@CLAUDE.md` invariant 3 |
| Package territory is blueprint data, with an `export.derived_paths` entry | `@CLAUDE.md` invariants 7 and 9 · `derived_paths["caching.md"]` |
| Versions and coordinates resolved at runtime | `@CLAUDE.md` invariant 8 · Initializr metadata read 2026-10-12 |
| No literal key in a versioned file | `@CLAUDE.md` invariant 11 · `rules/secrets.md` |
| Design skill + own rule + package key, the precedent | `decisions/0087-jobs-architect-skill.md` · `decisions/0121-http-client-architect-skill.md` · `decisions/0144-cache-architect-skill.md` |
| A step that generates requirements runs before the steps that read them | `new-feature/SKILL.md` § Integrates with |
| Exactly one layer retries | `rules/http-client.md` § Retry |
| Failure families | `rules/error-handling.md` § Integration families |
| The question asked before a payload leaves | `rules/personal-data.md` § In transit |
| A compose service decided at design, materialized at implement | `decisions/0137-pending-compose-service-decided-at-design-materialized-at-implement.md` |
| The `application` module does not forbid `org.springframework.ai` today | `blueprints/hexagonal/hexagonal.yaml` and siblings, `application.forbidden_imports` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/ai-architect/SKILL.md` | New — entry rule (a port of kind `LLM` or `vector store`), scope rule (no tools, chat memory, generation over passages, multimodal), Boot 4 check, inherit-first survey, interview, twelve-block partial, `postgres` image row |
| `.claude/skills/ai-architect/references/pitfalls.md` | New — 30 pitfalls in six groups plus the out-of-scope four, each with the rule section that prevents it and one source; the ones reproduced by a test marked **observed** |
| `.claude/skills/ai-architect/references/ai-reference-links.md` | New — the pages each decision is re-read against |
| `.claude/skills/ai-architect/templates/*.example` | New — `StructuredOutputAdapter`, `SemanticSearchAdapter`, `AiClientConfig` (+ `AiProperties`, `AiFailureTranslator`), `application-ai.yml`, two `.st` prompts, `vector-store-migration.sql`, `ai-spec.md` |
| `.claude/rules/ai.md` | New norm — scope, boundary, wiring, prompts, output, data sent, semantic search, failure and retry, observability, tests, How to verify |
| `.claude/rules/00-index.md` | `ai.md` row; listed with the package-territory rules |
| `.claude/schemas/extensions.json` | `skill_classes.design.skills` (and its count), `export.skills.include`, `derived_paths["ai.md"]` (optional, suffix `.ai`, extra `prompts/`), the prompts glob among the exempt ones |
| `.claude/blueprints/*/*.yaml` (8) | A `packages.map` key ending in `.ai`; `org.springframework.ai..` in the `application` module's `forbidden_imports` (hexagonal, clean multi-module); layered and vertical-slice also gain the `ai` glob and its dependency rules |
| `.claude/skills/domain-modeling/SKILL.md` + `templates/domain-spec.md.example` | Step 4e: kinds `LLM` and `vector store`, the "could not decide" value and the review state, ids from a search |
| `.claude/skills/new-feature/SKILL.md` + `templates/feature-spec.md.example` | Step 3d, its order and reason, short-path criterion, jobs and persistence reading § 9, tests reading § 8, precedence row, declared-dependency, compose and personal-data gates on `29-ia.md`, spec block `3.7 AI` |
| `.claude/agents/java-spring-boot-developer.md` | Block A (A1-A3) right after Block H; reads `ai.md`; writes `[ai]/**` and the prompts; AI dependencies from § 10; Block 4 AI tests |
| `.claude/skills/new-feature-implement/SKILL.md` | `adapters` group lists A |
| `.claude/skills/test-architect/SKILL.md` + `templates/AiAdapterContractTest`, `AiProviderIT`, `VectorStoreIT` | Reads `29-ia.md`; the three levels of AI tests, none calling a real model |
| `.claude/skills/docker-architect/SKILL.md` + `templates/postgres-service.yml.example` + `references/compose-service-decision.md` | The pgvector image variant of the `postgres` service and its catalog row; the decision reference names its new reader |
| `.claude/.ci/AiTemplateTest.java` + `.github/workflows/validate.yml` | New test in `design`; `exemplar-imports` gains `spring-ai-openai`, `spring-ai-vectordb-pgvector` and the `com.openai` and `tools.jackson` prefixes |
| `CLAUDE.md`, `project-bootstrap/templates/root.CLAUDE.md.example`, `README.md`, `claude-help.md`, `roadmap.md`, `docs/{en,pt-br}/*` | Routing rows, pipeline, design-skill list, norm and skill counts, CI rows, the ownership pitfall |

Goes to the generated project: **yes** — `ai-architect` via `export.skills.include`, `ai.md` via
`export.rules` with its glob derived from the blueprint's `.ai` entry plus the prompts folder.

Not changed, on purpose: no `migrations` entry. A project generated before this version has no AI
port this rule governs; one that called a model by hand is caught by the skill's survey (step 2)
when a case touches that port. No `BoundaryTest` case: `check` reads whatever `forbidden_imports`
holds, and the data itself is held by `AiTemplateTest`.

**Found while writing, recorded here:**

- **`spring.ai.openai.timeout` does not bound a chat call.** The 2.0.1 OpenAI model builds
  `RequestOptions` from the options' own timeout, which defaults to 60 s when the options name
  none, and that per-request value wins over the client's. Reproduced: a 3 s stub response passed
  a 500 ms `spring.ai.openai.timeout` and a 500 ms `spring.ai.openai.chat.timeout` alike. The
  exemplars put the timeout in each port's options (`app.ai.ports.<port>.timeout`), and
  `AiProviderIT`'s timeout case goes red without it.
- **`spring.ai.retry.*` is dead configuration for OpenAI and Anthropic on 2.0** — documented on
  their pages, read by no code. The exemplar yml leaves it out, and `AiTemplateTest` refuses it.
- **Two compose templates for one `postgres` service do not merge** — the `compose service
  templates merge and parse` step failed on a duplicate key. The pgvector image became a variant
  inside `postgres-service.yml.example`, which is also the honest shape: same service, other image.
- **The `{` `}` delimiters** would have read the JSON example of the system prompt as variables;
  the renderer is `<` `>`, and a `<company>` placeholder left in the prompt was a variable nobody
  bound — removed before the first run.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › AI exemplars decide every library default, and only they reach the library` (`.claude/.ci/AiTemplateTest.java`) | The AI exemplars carry every decision `ai.md` makes explicit, no other exemplar imports the AI library, every blueprint has an `.ai` key and forbids the library in its `application` module | green on the tree (3 Java exemplars, 1 port); red, by file and reason, on each of 24 injected defects: options without timeout; without model; a `ChatClient` bean without the injected builder; `ChatClient.create`; a literal prompt; `.entity` without `validateSchema`; a search without threshold; without `topK`; a 1.x name; the key with a default; `log-prompt: true`; `initialize-schema: true`; `spring.ai.retry` configured; no `max-retries`; a port without timeout; a port constant without an entry; a zero threshold; `dimensions` disagreeing with the migration; a `{ticket}` variable; the provider IT not pointed at WireMock; the store IT without its `@Primary` stub; an AI import in `RestClientAdapter`; a blueprint without `.ai`; `application` allowing the library. Two injections first landed in a header comment or did not match — re-injected on the YAML keys |
| `validate · exemplar-imports` | Every import of the new exemplars resolves | reproduced locally against the Initializr's Boot 4.1.1 project with the job's full dependency list plus `spring-ai-openai` and `spring-ai-vectordb-pgvector`: 338 checkable imports, 0 misses; red on a misspelt `OpenAiChatOption` |
| `validate · design › compose service templates merge and parse` | The pgvector variant leaves the compose merge valid | green; red with a separate `pgvector-service.yml.example` (duplicate `postgres` key) — the reason it became a variant |
| `validate · design › frontmatter schema`, `rules is a leaf`, `every norm paths matches some blueprint`, `no hardcoded Spring/Java version`, `exemplar bodies carry no comment but Javadoc` | Skill class, sections, export entry, rule `paths` and `derived_paths`, the leaf, the globs, dated versions, Javadoc-only bodies | green; `schema` red by name with `ai-architect` out of `export.skills.include`; the comment check first caught four `//` lines in `AiClientConfig` and one in `AiProviderIT`, made Javadoc |
| `validate · export-determinism` + `ArchHook.java export` by hand | `ai.md` travels with its glob derived from each blueprint | exported into seven blueprints: `**/adapter/out/ai/**`, `**/infrastructure/ai/**` ×2, `**/ai/**` ×2, `**/shared/internal/adapter/ai/**`, `**/app/ai/**`, each with `**/src/main/resources/prompts/**`; `ai-architect` present and `schema` green inside each |
| Outside CI, on the day | The exemplars compile and run | split into a Boot 4.1.1 / Spring AI 2.0.1 project and `./mvnw verify`: 7 contract cases, 5 WireMock cases, 3 pgvector cases green; the timeout case red with the per-port timeout removed |
