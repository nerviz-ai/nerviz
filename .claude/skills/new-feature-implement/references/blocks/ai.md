# Block A: AI (steps A1-A3, conditional)

**Runs only when the spec carries the AI block** — `grep -m1 -E '^## [0-9.]+ AI' <spec>`, and the
block is not `none`. No match, or `none` → skip to Block 3. Read the AI block of spec.md, then
`29-ai.md`, then `.claude/rules/ai.md`. It runs right after Block H because an AI adapter compiles
against the outbound port, the domain types and the integration families, and nothing upstream
compiles against it. The exemplars are in `.claude/skills/ai-architect/templates/`:

| Port kind (`29-ai.md` § 1) | Exemplar |
|---|---|
| `LLM` | `StructuredOutputAdapter.java.example` + the two `.st.example` prompts |
| `vector store` | `SemanticSearchAdapter.java.example` |
| both — the project-wide pieces | `AiClientConfig.java.example` + `application-ai.yml.example` |

- **A1 · Shared setup** — every component § 6 marks NEW: the integration families in `[domain]`
  (`http-client-architect/templates/IntegrationExceptions.java.example`) when absent, and in `[ai]`
  `AiProperties`, `AiClientConfig` (one `ChatClient` bean per `LLM` port, built from the injected
  `ChatClient.Builder`, its options carrying the port's model, temperature, output budget and
  timeout) and `AiFailureTranslator`. The dependencies of § 10 in `pom.xml` — the BOM import with
  the version § 10 recorded, the starters with none. A REUSE component gets only its new bean or
  `app.ai` entry
- **A2 · Adapters and prompts** — per port, in `[ai]`: the answer type with the closed fields § 3
  names, the adapter implementing the port `10-domain.md` declared, every unusable answer § 3
  lists mapped to the port's "could not decide" value, every provider exception through the
  translator. The prompt resources of § 3 in `src/main/resources/prompts/`, the input only in the
  named variables. Per `vector store` port: the search with § 3's threshold and ceiling, returning
  ids; the write side § 5 names. Only the fields § 4 marks as sent reach a prompt or an embedding
- **A3 · Configuration** — the properties of § 11 in `application.yml` (shape in
  `application-ai.yml.example`): the key as a placeholder with no default, the SDK's `max-retries`
  as § 6 says, every content-logging flag `false`, the store's `initialize-schema: false`. The
  store's migration is Block 2's, from `20-persistence.md`

Rules: `.claude/rules/ai.md`, `.claude/rules/error-handling.md` § Integration families,
`.claude/rules/architecture-ddd.md` § Adapters, `.claude/rules/personal-data.md` § In transit,
`.claude/rules/secrets.md`, `.claude/rules/logging.md` (outcome and latency, never a prompt, an
answer or a key).

Validation, each item checkable:

- the greps of `.claude/rules/ai.md` § How to verify return what that section says;
- no class outside `[ai]` imports `org.springframework.ai`, `com.openai` or `com.anthropic`, and no
  port signature carries one of their types;
- every `ChatClient` bean receives `ChatClient.Builder`, and its options set a timeout;
- no prompt text appears in Java: every `.system(...)`/`.user(...)` reads a `Resource`.

Compilation: `./mvnw -q test-compile` (or `-pl <module that holds [ai]>` in multi-module) ✅

**Line in the final report:**
```
✅ Block A: AI complete (steps A1-A3)
- ticket-classifier: LLM, gpt-4.1-mini, timeout 4s, SDK retries 1, Undecided → AWAITING_TRIAGE
- Sent to the provider: Ticket.text only
- Shared: AiProperties, AiClientConfig, AiFailureTranslator (NEW); integration families (REUSE)
- Files: 6 classes, 2 prompts, 0 errors
```
