# Spring AI pitfalls — what the library does by default, and what the model gets wrong

Reference for `ai-architect`. Loads only when the skill runs. Every row names the section of
`@.claude/rules/ai.md` that prevents it and one source. Read against Spring AI 2.0.1 on Boot 4.1.1
(the Initializr's pairing on 2026-10-12): the reference documentation, the source at tag `v2.0.1`,
and the exemplars of this skill compiled and run against that classpath. Versions as read that day,
never written into a template. A row marked **observed** was reproduced by a test, not only read.

D = `https://docs.spring.io/spring-ai/reference/` · S = `https://github.com/spring-projects/spring-ai/blob/v2.0.1/`

## Version line

| Pitfall | Prevented by | Source |
|---|---|---|
| 2.0 removed `FunctionCallback`, `internalToolExecutionEnabled`, `ChatOptions#copy()` and `fromOptions()`; options are immutable builders with `mutate()`. Code written from 1.x memory does not compile | the skill's version check (step 2) | D `upgrade-notes.html` § Upgrading to 2.0.0 |
| The chat property keys lost their `.options` segment (`spring.ai.openai.chat.model`, not `spring.ai.openai.chat.options.model`); the old keys are deprecated for removal | `application-ai.yml.example` | D `upgrade-notes.html` · S `.../OpenAiChatProperties.java` (`@DeprecatedConfigurationProperty`) |
| The Initializr offers Spring AI only for Boot `[4.0.0,4.3.0-M1)`: a Boot 3 project has no 2.x line to resolve | the skill's version check (step 2) | `https://start.spring.io` metadata, dependency `spring-ai-openai`, `versionRange` |

## Wiring

| Pitfall | Prevented by | Source |
|---|---|---|
| `ChatClient.create(model)` / `ChatClient.builder(model)` bypass the auto-configured builder — no observations, no `ChatClientBuilderCustomizer` | `ai.md` § Wiring | D `api/chatclient.html` |
| Spring AI 2.0 has no default temperature (it was 0.7); the OpenAI docs table still says 0.8, the code sets none | `ai.md` § Wiring | D `upgrade-notes.html` · S `.../OpenAiChatProperties.java` |
| A model left unset is whatever the starter names that release (`gpt-5-mini` in 2.0.1), and a reasoning model rejects `temperature` | `ai.md` § Wiring | S `models/spring-ai-openai/.../OpenAiChatOptions.java` (`DEFAULT_CHAT_MODEL`) · D `api/chat/openai-chat.html` |
| **observed** — `spring.ai.openai.timeout` (and `spring.ai.openai.chat.timeout`) does not bound a chat call: the model sends the options' timeout with each request, and options built without one carry a 60 s default that wins. A 3 s stub response passed a 500 ms setting | `ai.md` § Wiring, `AiClientConfig.java.example` (`.timeout(...)` per port) | S `models/spring-ai-openai/.../OpenAiChatModel.java` (`buildRequestOptions`) · `AiProviderIT`'s timeout case, red without the per-port timeout |
| `ChatClient.Builder` is a prototype: one builder per injection point, and a port that mutates a shared client changes another port's options | `AiClientConfig.java.example` (one bean per port) | S `.../ChatClientAutoConfiguration.java` (`@Scope("prototype")`) |

## Prompts

| Pitfall | Prevented by | Source |
|---|---|---|
| `StTemplateRenderer` reads `{` `}` as variables — a JSON example in the prompt fails to render | `ai.md` § Prompts | D `api/prompt.html` (custom delimiters) |
| User input concatenated into the instructions can carry instructions of its own (prompt injection) | `ai.md` § Prompts | OWASP *Top 10 for LLM Applications*, LLM01 Prompt Injection |
| A prompt built as a Java string is reviewed as code, not as a prompt, and drifts per call site | `ai.md` § Prompts | D `api/prompt.html` (`PromptTemplate` from a `Resource`) |

## Output

| Pitfall | Prevented by | Source |
|---|---|---|
| `.entity(Class)` is best effort: the format instructions are a request, not a guarantee | `ai.md` § Output | D `api/structured-output-converter.html` |
| `validateSchema()` re-asks the model with the validation error, up to 3 attempts by default — each one a billed call | `ai.md` § Output, `29-ia.md` § 6 | D `api/structured-output/validation.html` |
| Native structured output (`useProviderStructuredOutput()`, `AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT`) is ignored without error when the options do not implement `StructuredOutputChatOptions` | `ai.md` § Output (validation, not native, is the default) | D `api/structured-output/native.html` |
| OpenAI rejects a top-level array in a schema | `ai.md` § Output | D `api/structured-output/native.html` |
| An unparseable answer raises `tools.jackson.core.JacksonException` from the converter; an empty one returns `null` — neither is a provider failure | `ai.md` § Output, `StructuredOutputAdapter.java.example` | S `spring-ai-model/.../BeanOutputConverter.java` · `AiAdapterContractTest`, observed |

## Semantic search

| Pitfall | Prevented by | Source |
|---|---|---|
| `SearchRequest` defaults: `topK` 4 and `similarityThreshold` 0.0 — "accept all" | `ai.md` § Semantic search | S `spring-ai-vector-store/.../SearchRequest.java` (`DEFAULT_TOP_K`, `SIMILARITY_THRESHOLD_ACCEPT_ALL`) |
| No store creates its schema by default; pgvector needs `vector`, `hstore`, `uuid-ossp`; a plain `postgres` image has no `vector` | `vector-store-migration.sql.example`, `docker-architect/templates/postgres-service.yml.example` § VARIANT — a vector store | D `api/vectordbs/pgvector.html` |
| HNSW indexes at most 2000 dimensions; a new embedding model with other dimensions means a new table and every item re-embedded | `ai.md` § Semantic search | D `api/vectordbs/pgvector.html` |
| `SimpleVectorStore` is not for production, and not a test substitute for the real engine | `ai.md` § Tests | D `api/vectordbs.html` |
| The pgvector store upserts by document id — a random id per write duplicates the item instead of replacing it | `SemanticSearchAdapter.java.example` (id = record id) | S `vector-stores/spring-ai-pgvector-store/.../PgVectorStore.java` (`ON CONFLICT (id)`) |
| A filter written as text from input is an injection point | `ai.md` § Semantic search | D `api/vectordbs.html` § Metadata Filters (`FilterExpressionBuilder`) |
| Spring AI's Testcontainers module has no pgvector service connection | `VectorStoreIT.java.example` (Boot's `@ServiceConnection` on `PostgreSQLContainer`) | D `api/testcontainers.html` |

## Failure and retry

| Pitfall | Prevented by | Source |
|---|---|---|
| `spring.ai.retry.*` (10 attempts, 2 s × 5 up to 3 min) is documented for OpenAI but **not read** by the 2.0 OpenAI and Anthropic models — they retry only through their SDK (`max-retries`, 3 and 2) | `ai.md` § Failure and retry, `application-ai.yml.example` | S `models/spring-ai-openai/.../OpenAiChatModel.java` (no `RetryTemplate`) · `AiProviderIT`'s 503 case, observed: exactly 1 + `max-retries` requests |
| The OpenAI SDK retries 408, 409, 429, 5xx and I/O failures, honoring `Retry-After` — a Resilience4j retry on top multiplies the attempts | `ai.md` § Failure and retry | `openai/openai-java` `v4.49.0`, `RetryingHttpClient.kt` |
| Provider failures surface as the SDK's own types (`com.openai.errors.RateLimitException`, `InternalServerException`, `OpenAIIoException`, `BadRequestException`…), not Spring AI's `TransientAiException` | `AiClientConfig.java.example` (`AiFailureTranslator`) | `openai/openai-java` `v4.49.0`, `ErrorHandler.kt` · `AiProviderIT`, observed |
| With no base URL property the SDK reads `OPENAI_BASE_URL`; the code default is `https://api.openai.com/v1` and the SDK appends `chat/completions` — a stub server answers `/chat/completions`, not `/v1/chat/completions` | `AiProviderIT.java.example` | S `models/spring-ai-openai/.../setup/OpenAiSetup.java` |

## Data and observability

| Pitfall | Prevented by | Source |
|---|---|---|
| Prompt, completion and vector-query content logging exist, each with a sensitive-data warning; all off by default, and one `true` sends every prompt to the log backend | `ai.md` § Data sent | D `observability/index.html` |
| Token usage is cumulative across a tool-calling loop and is `gen_ai.client.token.usage` only when the client was built with observations | `ai.md` § Observability | D `observability/index.html` · D `api/chatclient.html` |
| A provider retains prompts under its own terms — personal data sent is personal data transferred | `ai.md` § Data sent | `@.claude/rules/personal-data.md` § In transit |

## Out of scope, recorded

| Topic | Why out | Source |
|---|---|---|
| Tool calling | State-changing actions need the caller's identity and a confirmation turn; 2.0 moved the loop into `ToolCallingAdvisor` | D `api/tools.html` · D `upgrade-notes.html` |
| Chat memory | Requires a conversation id on every call; JDBC, Cassandra and MongoDB repositories drop tool messages; the upgrade notes announce a replacement | D `api/chat-memory.html` · D `upgrade-notes.html` |
| Generation over retrieved passages | Answers must cite sources, refuse without context, and filter by the asker's access | D `api/retrieval-augmented-generation.html` |
| `spring-ai-community/spring-ai-agent-utils` | Pre-1.0 community tools for coding agents (shell, filesystem) — a risk inside a business service | `https://github.com/spring-ai-community/spring-ai-agent-utils` |
