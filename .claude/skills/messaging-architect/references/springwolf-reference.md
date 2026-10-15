# Springwolf — what was verified, and where the version comes from

Read by `messaging-architect` when it writes the contract rows of § 1 and the declared dependency
of § 7. Nothing here is a norm: the norm is `@.claude/rules/messaging.md` § Contract documentation.
These are facts about a tool, observed on **2026-10-15** against a Boot 4.1.1 project from the
Initializr with `springwolf-kafka` 2.8.0, so that the skill does not rediscover them. A newer
Springwolf release makes each row worth a re-check before it is relied on.

## Resolving the version

Not in the Spring Boot BOM, so the version is explicit and never written from memory:

```bash
curl -sS 'https://repo1.maven.org/maven2/io/github/springwolf/springwolf-kafka/maven-metadata.xml' \
  | grep -oE '<version>2\.[0-9.]+</version>' | tail -1
```

The oracle is `maven-metadata.xml` from `repo1.maven.org`, and the pattern keeps only a final `2.x`
number: `<release>` is the last deploy, not the last stable one. The `2.x` line targets Spring Boot 4 — its
POM imports the Boot 4 dependencies — and the `1.x` line targets Spring Boot 3, which this `.claude/`
does not install into (`ArchHook.java export` refuses a build below `doctor.platform.min_line`). Take
the latest stable `2.x` release; a major above `2` is read against the project's Boot line before it is used.
No network → ask, don't guess.

## Observed behavior

| Observation | Consequence for the design |
|---|---|
| The core module brings Jackson 2 (`jackson-databind`, `spring-boot-jackson2`) for its own document; the application's `JsonMapper` bean stayed `tools.jackson.databind.json.JsonMapper` | No `com.fasterxml.jackson.databind` or `.core` import in application code (the `annotation` package is still Jackson 3's); nothing to configure |
| The generated document declares AsyncAPI **3.1.0**, JSON, at `/springwolf/docs`; the same path with `.yaml` answers 404 | The snapshot is JSON; the version number is read from the document, never written into a norm |
| Two runs over unchanged code produce byte-identical documents | A snapshot comparison is stable |
| A package-private `@Component` carrying `@AsyncPublisher` or `@AsyncListener` is found, once `springwolf.docket.base-package` names its package | The exemplars' package-private visibility holds |
| `@AsyncOperation` has no operation-id attribute: the id is `<channel>_<send\|receive>_<method name>` | The port and listener method names are part of the contract |
| Springwolf's own `@KafkaListener` scan documents a `__TypeId__` header with a constant value and a message named after the payload class; beside `@AsyncListener` the method appears with two messages and no consumer group | `springwolf.plugin.kafka.scanner.kafka-listener.enabled: false`, both sides annotated |
| With that scanner off, `@AsyncListener` + `@KafkaAsyncOperationBinding(groupId = …)` renders one message and the group id binding | The consumer exemplar |
| `@KafkaAsyncOperationBinding(messageBinding = @KafkaAsyncMessageBinding(key = …))` on the producer was accepted and **not rendered** | The partition key is written in the operation's `description` |
| `headers = @AsyncOperation.Headers(notUsed = true)` renders a `HeadersNotUsed` schema; with no `headers` the document says headers exist but are undocumented | Headers are always stated |
| `springwolf.plugin.kafka.publishing.enabled` defaults to `false`; `springwolf-ui` is a separate artifact, not pulled by `springwolf-kafka` | Written explicitly; the UI is not a declared dependency |
| The context starts with no broker reachable when `spring.kafka.listener.auto-startup` is `false` | The snapshot test needs no Testcontainers broker |
| ZenWave SDK `spring-cloud-streams3` generates Spring Cloud Stream code, not `KafkaTemplate` / `@KafkaListener` adapters | The recorded alternative, `templates/zenwave-sdk-maven.xml.example` |
