---
paths:
  - "**/adapter/out/ai/**"
  - "**/infrastructure/ai/**"
  - "**/src/main/resources/prompts/**"
status: active
---

# AI — outbound calls to a language model and a vector store

Code that asks a language model for an answer or a vector store for similar items: a
classification, an extraction, a semantic search over the project's own records. The answer is
text produced by a system nobody here controls — plausible when wrong, different on every call,
and shaped by whatever the prompt carried. Every default of the client library makes a silent
choice about that text: a model nobody pinned, a retry nobody counted, a similarity threshold
that accepts everything, a log line that holds the prompt. This norm makes each of those choices
explicit.

Scope: a model asked for a structured answer, and an embedding model with a vector store asked for
the nearest items. A model that calls back into the application (tools), a conversation kept
across calls (chat memory), and an answer generated over retrieved passages are outside this norm,
and code that needs one of them is a design decision recorded before it is written.

## Boundary

- **The AI library is an adapter detail.** `org.springframework.ai` and the provider's SDK types
  appear only in the architecture's AI package. The domain and the application see an outbound
  port in business words (`TicketClassifier`, `ProductSemanticSearch`) that takes and returns
  domain types — `@.claude/rules/architecture-ddd.md` § Adapters. A blueprint forbids the import in
  every layer above the adapters
- **The model suggests; the domain decides.** What comes back is untrusted input, the same as a
  request body: it passes through the value objects and the validation of the domain before it
  reaches an aggregate, and an aggregate never takes a state on the model's word alone. A state
  that needs a person, or a rule of the domain, to confirm the model's answer
  (`PENDING_REVIEW`, `UNCLASSIFIED`) is the default outcome, not the exception
- **A vector store returns identifiers, not records.** The fields a caller acts on — a price, a
  stock level, a permission — are read from their own repository after the search, never from
  the metadata stored next to the vector, which is a copy as old as its last embedding
- **One adapter per port.** Two ports may share a model client; a port never reaches the model
  through another port's adapter

## Wiring

- **A chat client is built from the auto-configured `ChatClient.Builder`**, which carries the
  observations and customizers. `ChatClient.create(model)` and `ChatClient.builder(model)` skip
  both, and the call disappears from metrics and traces
- **The model is named in configuration**, per port when two ports use different models. A model
  left to the library's default changes when the library does
- **Every generation option a port depends on is written** — temperature for a classification,
  maximum output tokens for any call. No option is inherited from a default the library may
  change or drop
- The provider's starter, BOM and version come from the build's dependency management, never
  from memory

## Prompts

- **A prompt is a versioned resource**, `src/main/resources/prompts/<port>-<purpose>.st`, read
  through a `Resource`. A prompt built by string concatenation in Java is not reviewable as a
  prompt and is not allowed
- **Variables are named, and nothing else is interpolated.** Input from a user or another system
  enters the prompt only through a named variable, inside a delimited block the instructions
  refer to — never appended to the instructions themselves. Text in that block is data the model
  is told to classify, extract from, or compare, and an instruction inside it is not followed
- **A prompt that holds a JSON example uses delimiters that JSON does not**, set once in the
  template renderer. The default `{` `}` delimiters read the example as variables
- A change to a prompt is a change to behavior: it goes through review with the test cases that
  pin its outcomes

## Output

- **A structured answer is requested as a type**, a record declared in the adapter, and its
  closed fields are enums: the schema the model receives lists the values it may return
- **Schema validation is on**, so an answer that does not match is sent back to the model once with
  the error, before the adapter sees it
- **A list is never the top-level type** — it is a field of a record. Providers that enforce a
  schema reject a top-level array
- **An answer that still cannot be read — malformed, outside the enum, refused, empty — is an
  outcome, not an exception.** The adapter returns the port's "could not decide" value, and the
  domain routes it to the review state. It never throws an integration failure for an answer the
  provider did deliver, and never retries it as one
- A confidence or score the model reports is a hint the domain may use to route to review; it is
  never the only reason a state changes

## Data sent

- **Each field a prompt carries is decided before the prompt is written**: the full value, a reduced
  form, or nothing — the question and its recorded answer of `@.claude/rules/personal-data.md`
  § In transit. A model provider is a receiver outside the project, and a prompt is retained by it
  under its own terms
- A field the classification does not need is not sent because it was at hand in the record
- **The content of prompts, completions and vector queries stays out of logs and traces.** Every
  content-logging flag the library offers stays off — `@.claude/rules/logging.md` § Masking candidates

## Semantic search

- **Every search names its similarity threshold and its result count.** The library's default
  threshold accepts every item, so a search with no match still returns the nearest unrelated ones
- **"Nothing similar enough" is an answer the port returns**, distinct from an error
- **The embedding model and its dimensions are fixed per store**: changing either changes every
  stored vector, and is a migration of the store, re-embedding every item
- **The store's schema is created by a versioned migration** — `@.claude/rules/persistence.md` — and
  the library's schema initialization stays off. A filter on metadata is written with the
  library's portable filter builder, never by concatenating text from input
- Each stored item carries the identifier of the record it was made from, and is replaced when that
  record changes or removed when it is deleted, by whoever writes the record

## Failure and retry

- **The provider is a remote system**: timeouts, the retry allowlist, the single retrying layer and
  the failure families are those of `@.claude/rules/http-client.md` § Timeouts, § Retry and
  § Failures, and `@.claude/rules/error-handling.md` § Integration families
- **Exactly one layer retries, and it is named.** A provider SDK retries on its own by default;
  that retry counts as the layer. When the trigger already retries — a message listener, a job's
  next run — the SDK's own retry is set to zero
- **A model call changes nothing at the provider**, so a read timeout on it is safe to retry, within
  the request's own deadline
- **429 is not a reason to wait inside a request.** The wait the provider announces is surfaced, as
  any dependency-unavailable failure
- Credentials for the provider are secrets — `@.claude/rules/secrets.md`

## Observability

- **Token usage and call latency are measured** by the library's observations
  (`gen_ai.client.token.usage`, `gen_ai.client.operation`), exported like every other metric —
  `@.claude/rules/observability.md` § Metrics. A client built outside the auto-configured builder is not measured
- The answers the domain sent to review, per port, are counted: a rising share is the first sign
  that a prompt, a model or the input changed

## Tests

- **The adapter is tested without a model**: a stub chat model returns fixed answers — a valid one,
  one outside the enum, a malformed one, a refusal — and each test asserts the port's result. These
  are the cases that decide whether the domain ever sees an unchecked answer
- **The wiring is tested against a stub server speaking the provider's protocol**, with the
  starter's base URL pointed at it: the configured timeout fires, the configured retries are the
  only ones, and 429, 5xx and a timeout become the failure families of § Failure and retry
- **A vector store is tested against the real database engine** with a stub embedding model that
  returns fixed vectors — `@.claude/rules/testing.md` § Database in integration tests. The threshold and the
  "nothing similar" path are asserted, not only the nearest hit
- No test of the build calls a real model: its answer is not repeatable, and the test spends money
  and sends data out on every run

## How to verify

```bash
# The AI library only in the AI package. Zero lines is the expected result.
grep -rln "import org.springframework.ai\.\|import com.openai\.\|import com.anthropic\." --include='*.java' src/main \
  | grep -v "/ai/"

# No chat client built around the auto-configured builder. Zero lines is the expected result.
grep -rnE "ChatClient\.(create|builder)\(" --include='*.java' src/main

# No prompt text in Java. Review every hit: a long string next to .user( or .system( is a prompt.
grep -rnE "\.(user|system)\(\"" --include='*.java' src/main

# Content logging off. Zero lines is the expected result.
grep -rnE "(log-prompt|log-completion|include-content|log-query-response)\s*:\s*true" src/main/resources

# Every search names its threshold. Review any SearchRequest without similarityThreshold.
grep -rn "SearchRequest.builder()" --include='*.java' src/main

# No schema created by the library. Zero lines is the expected result.
grep -rnE "initialize-schema\s*:\s*true" src/main/resources
```
