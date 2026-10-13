# Spring for GraphQL pitfalls — what the library does by default, and what the model gets wrong

Reference for `graphql-api-architect`. Loads only when the skill runs. Every row names the section
of `@.claude/rules/api-graphql.md` that prevents it and one source. Read against Spring for GraphQL
2.0.5, graphql-java 25.0 and java-dataloader 6.0.0 on Boot 4.1.1 (the Initializr's pairing on
2026-10-13): the reference documentation, the source at those tags, and the exemplars of this skill
and `test-architect`'s GraphQL tests compiled and run against that classpath. Versions as read that
day, never written into a template. A row marked **observed** was reproduced by running them, not
only read.

D = `https://docs.spring.io/spring-graphql/reference/` · B = `https://docs.spring.io/spring-boot/` ·
S = `https://github.com/spring-projects/spring-graphql/blob/v2.0.5/spring-graphql/src/main/java/org/springframework/graphql/` ·
J = `https://github.com/graphql-java/graphql-java/blob/v25.0/src/main/java/graphql/`

## Transport

| Pitfall | Prevented by | Source |
|---|---|---|
| The HTTP endpoint answers `POST` only on the 2.0 line: no `GET`, so no HTTP or CDN cache. Choosing GraphQL for a cacheable public read loses that | `api-graphql.md` § Choosing the inbound protocol | D `transports.html` · S `server/webmvc/GraphQlRequestPredicates.java` (`HttpMethod.POST`) |
| An execution failure is HTTP 200 with `errors`; 400 only when there is no `data` and the client accepted `application/graphql-response+json`. A dashboard counting 5xx sees nothing | § Errors, § Observability | S `server/webmvc/GraphQlHttpHandler.java` (`selectResponseStatus`) · GraphQL over HTTP draft |
| CORS for `/graphql` is off until `spring.graphql.cors.*` is set, and `allowed-methods` must name `POST` | § Demand limits | B `appendix/application-properties` (`spring.graphql.cors.*`) |

## Schema

| Pitfall | Prevented by | Source |
|---|---|---|
| Spring Boot runs the schema inspection and only LOGS the report at INFO; the application starts, and a field nothing answers returns `null`. **observed** — renaming the `customer` batch mapping failed the startup only with the customizer of `GraphQlConfig.java.example` | § Schema | B `spring-graphql.html` · Boot source `GraphQlAutoConfiguration` (`inspectSchemaMappings(logger::info)`) |
| A customizer's `inspectSchemaMappings` REPLACES Boot's logger consumer (one consumer per builder) — the failing check is the only report | `GraphQlConfig.java.example` | S `execution/DefaultSchemaResourceGraphQlSourceBuilder.java` |
| The default property data fetcher reads any accessor whose name matches a field: an entity or aggregate returned to the schema exposes whatever a later schema field names, and a lazy association loads outside the transaction | § Boundary | D `request-execution.html` · graphql-java `PropertyDataFetcher` |
| An interface or union member resolves its type by the Java class's simple name; a class named differently from the type is not found | § Schema | D `request-execution.html` (`ClassNameTypeResolver`) |
| An `@Argument` record cannot tell an omitted field from an explicit `null`; a partial update built on it clears what the client did not send. `ArgumentValue` per field can | § Schema | D `controllers.html` (`ArgumentValue`) |
| Jackson annotations on a returned record do not rename schema fields — the field fetcher reads accessors | § Schema | D `request-execution.html` |
| A connection type (`OrderConnection`) is generated only when the `Connection`-suffixed type is referenced and not declared; declaring it by hand drifts from the cursor strategy | § Pagination | D `data.html` (Pagination) · S `execution/ConnectionTypeDefinitionConfigurer.java` |

## Batching

| Pitfall | Prevented by | Source |
|---|---|---|
| A `@SchemaMapping` on a child field runs once per parent — N+1 calls. **observed** — turning `Order.lines` into a per-parent mapping failed `childFieldsAreBatched` | § Batching | D `controllers.html` (`@BatchMapping`) |
| A batch method's parents key the returned map; a parent without value equality matches nothing, and a parent missing from the map answers `null` on a non-null field | § Batching | D `controllers.html` (`@BatchMapping` return values) |
| A `@BatchMapping` method takes no `@Argument`; the loader caches per key for the whole request | § Batching | D `controllers.html` · D `request-execution.html` (Batch Loading) |
| `DataLoaderOptions.maxBatchSize` defaults to `-1`, no limit — every key of a large page goes into one `IN` list. A `DefaultBatchLoaderRegistry` built with an options supplier bounds every loader, annotated ones included | § Batching | java-dataloader `DataLoaderOptions` (`maxBatchSize = -1`) · S `execution/DefaultBatchLoaderRegistry.java` |

## Pagination

| Pitfall | Prevented by | Source |
|---|---|---|
| `first` reaches the controller with no ceiling; nothing in the library bounds it | § Pagination | D `controllers.html` (`ScrollSubrange`) |
| A cursor encoding an offset drifts when rows are inserted; the keyset cursor needs an order ending in a unique key | § Pagination | Relay connections spec · D `data.html` |
| **observed** — a keyset position whose values are a `String` instant and a `String` id round-trips through the library's cursor encoding; the adapter parses them back into the port's own cursor type | `GraphQlController.java.example` (`OrderCursors`) | `cursorCarriesTheOrderingKey` in `GraphQlControllerTest.java.example` |

## Errors

| Pitfall | Prevented by | Source |
|---|---|---|
| A domain exception no resolver handles becomes `INTERNAL_ERROR for <executionId>` — the client loses the `errorCode` | § Errors | S `execution/ExceptionResolversExceptionHandler.java` (`createInternalError`) |
| A catch-all resolver swallows `AccessDeniedException` before `SecurityDataFetcherExceptionResolver` sees it. **observed** — with one, the denied `taxId` read came back `INTERNAL_ERROR` and `sensitiveFieldDeniedAlone` failed | § Errors | S `execution/SecurityDataFetcherExceptionResolver.java` |
| A business rule failure as a top-level error on a mutation leaves the client parsing messages; errors as data in a payload are typed | § Errors | Apollo "Errors as data" · Shopify tutorial (`userErrors`) |
| Parse and validation errors — an unknown field, a limit exceeded — never reach a resolver | § Errors | D `request-execution.html` (Exceptions) |

## Access

| Pitfall | Prevented by | Source |
|---|---|---|
| The filter chain sees one `POST /graphql`: a request rule cannot tell operations or fields apart | § Access | D `security.html` |
| A check on the root query does not protect the same object reached through another field (edge versus node) | § Access | graphql.org `learn/authorization/` · OWASP GraphQL Cheat Sheet |
| **observed** — `@PreAuthorize` through a meta-annotation on a package-private `@SchemaMapping` of a package-private `@Controller` is intercepted: the denied field answered `null` with `FORBIDDEN` and the order stood | § Access | `sensitiveFieldDeniedAlone` in `GraphQlHttpIT.java.example` |
| Spring Boot registers `SecurityDataFetcherExceptionResolver` only with Spring Security on the classpath and a servlet application; denials become `FORBIDDEN` / `UNAUTHORIZED` | § Access | Boot source `GraphQlWebMvcSecurityAutoConfiguration` |
| A check on a `@BatchMapping` runs once for the batch, not per parent | § Access | D `security.html` |
| **observed** — in a `@GraphQlTest` slice there is no authentication, so a controller's `Authentication` parameter fails with `AuthenticationCredentialsNotFoundException` and every case answers `INTERNAL_ERROR`; `@WithMockUser` supplies one | `GraphQlControllerTest.java.example` | first run of the exemplars, 2026-10-13 |
| **observed** — Spring Security 7 adds `resource_metadata` to the `WWW-Authenticate: Bearer` challenge over a real server; an exact-match assertion on `Bearer` fails | `GraphQlHttpIT.java.example` | first run of the exemplars, 2026-10-13 |

## Demand limits

| Pitfall | Prevented by | Source |
|---|---|---|
| No depth, complexity or time limit is on by default; only the parser's own (15 000 tokens, 1 MB, rule depth 500) | § Demand limits | J `parser/ParserOptions.java` · graphql-java `documentation/limits` |
| **observed** — a complexity calculator multiplying EVERY field by the default page priced a two-order page with lines and customer at 344421; only a field declaring `first` multiplies | § Demand limits | `GraphQlConfig.java.example` · `childFieldsAreBatched`, first run |
| An alias repeats a field at no parse cost; only complexity counts it again | § Demand limits | OWASP GraphQL Cheat Sheet (alias and batching attacks) |
| Introspection is ON by default; turning it off calls `Introspection.enabledJvmWide(false)`, static for the whole JVM — a later test context in the same JVM inherits it | § Demand limits | Boot source `GraphQlAutoConfiguration` · J `introspection/Introspection.java` |
| GraphiQL is one property away from production; it belongs in the development profile only | § Demand limits | D `graphiql.html` · B `appendix/application-properties` (`spring.graphql.graphiql.enabled`) |
| A request has no timeout: a slow port holds a servlet thread. `TimeoutWebGraphQlInterceptor` bounds it | § Demand limits | S `server/TimeoutWebGraphQlInterceptor.java` |

## Transactions and threads

| Pitfall | Prevented by | Source |
|---|---|---|
| `@Transactional` on a `@QueryMapping` closes before nested fields resolve; a lazy association then fails | § Boundary | D `request-execution.html` · `@.claude/rules/persistence.md` |
| On a JDK with virtual threads, a blocking controller method runs on Boot's application task executor when that executor does not prefer short-lived tasks — with virtual threads on, every method leaves the request thread | § Boundary | S `data/method/annotation/support/AnnotatedControllerDetectionSupport.java` (`shouldInvokeAsync`) · Boot source `GraphQlAutoConfiguration` (`setExecutor`) |
| Spring Security's context follows a controller method to another thread through context propagation (`SecurityContextThreadLocalAccessor`, `io.micrometer:context-propagation` on the starter's runtime classpath); a transaction does not | § Boundary | S `execution/SecurityContextThreadLocalAccessor.java` · D `request-execution.html` (Context Propagation) |

## Evolution and tests

| Pitfall | Prevented by | Source |
|---|---|---|
| A removed or stricter field breaks clients with no version to fall back on | § Evolution | graphql.org `learn/schema-design/` · Shopify tutorial |
| graphql-java's `SchemaDiff` reports a removed deprecated field as `DEPRECATION_REMOVED` and every other removal as `MISSING`, both breaking — the test admits only the first | § Evolution | J `schema/diff/SchemaDiff.java` |
| **observed** — a snapshot under `src/test/resources/graphql/` is on the test classpath: the compatibility test read it as the schema, and Boot would load it in every `@SpringBootTest`, defining each type twice | § Evolution | `GraphQlSchemaCompatibilityTest.java.example` |
| **observed** — printing `#` comments into the snapshot made two runs over the same files differ; the printer drops comments and directive definitions | `GraphQlSchemaCompatibilityTest.java.example` | first run of the exemplars, 2026-10-13 |
| `@GraphQlTest` loads no service, no filter chain and no transport — a passing access case there proves nothing | § Tests | B `testing/spring-boot-applications.html` (`@GraphQlTest`) |
| `graphql.operation.name` is high cardinality and chosen by the client; as a metric tag it explodes the series and can be spoofed | § Observability | D `observability.html` · S `observation/DefaultExecutionRequestObservationConvention.java` |

## Out of scope — the skill stops on these

| Case | Why it is out | What the user decides |
|---|---|---|
| Subscriptions (SSE, WebSocket) | A stream has its own backpressure, timeout and authentication-per-connection decisions this version does not design | Narrow the case, or defer it with an owner |
| File upload | GraphQL has no file transport in the spec; Spring for GraphQL has none on HTTP | A mutation returning a signed upload URL, or a REST endpoint |
| Federation | A gateway and an entity-resolution contract across services | A separate decision |
| `@GraphQlRepository`, Querydsl and query-by-example resolvers | They skip the input port and its rules | — never in this architecture |
| Generated Java types (codegen) | The adapter's records are written by hand from the schema | A separate decision |
| WebFlux, RSocket | Servlet stack only, as `authorization.md` | A separate decision |
