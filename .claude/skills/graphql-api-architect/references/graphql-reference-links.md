# Spring for GraphQL — reference links

Reference for `graphql-api-architect`. The pages each decision is re-read against before a partial
cites it. A version, a starter coordinate or a property default is read from these pages, from the
library's source at its tag, or from the Initializr on the day the partial is written — never from
this file, which names pages, not values.

| Topic | Page |
|---|---|
| Server transports — HTTP (POST only on the 2.0 line), SSE, WebSocket; the status rules | `https://docs.spring.io/spring-graphql/reference/transports.html` |
| Request execution — schema resources, inspection, `ConnectionTypeDefinitionConfigurer`, exception resolvers, batch loading, context propagation | `https://docs.spring.io/spring-graphql/reference/request-execution.html` |
| Annotated controllers — `@QueryMapping`, `@MutationMapping`, `@SchemaMapping`, `@BatchMapping`, `@Argument`, `ArgumentValue`, `ScrollSubrange`, blocking methods and the executor | `https://docs.spring.io/spring-graphql/reference/controllers.html` |
| Data integration — pagination, `Window`, cursor strategies, the repository resolvers this skill does not use | `https://docs.spring.io/spring-graphql/reference/data.html` |
| Security — method security, `SecurityDataFetcherExceptionResolver`, context propagation | `https://docs.spring.io/spring-graphql/reference/security.html` |
| Observability — observation names, low and high cardinality keys | `https://docs.spring.io/spring-graphql/reference/observability.html` |
| Testing — `GraphQlTester`, `HttpGraphQlTester`, error filtering | `https://docs.spring.io/spring-graphql/reference/testing.html` |
| GraphiQL | `https://docs.spring.io/spring-graphql/reference/graphiql.html` |
| Spring Boot — "Spring for GraphQL": schema locations, inspection, introspection, CORS, GraphiQL, security auto-configuration | `https://docs.spring.io/spring-boot/reference/web/spring-graphql.html` |
| Spring Boot — `spring.graphql.*` properties and their defaults | `https://docs.spring.io/spring-boot/appendix/application-properties/index.html` |
| Spring Boot — testing: `@GraphQlTest`, `@AutoConfigureHttpGraphQlTester` | `https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html` |
| graphql-java — instrumentation: depth, complexity | `https://www.graphql-java.com/documentation/instrumentation` |
| graphql-java — parser and execution limits | `https://www.graphql-java.com/documentation/limits` |
| graphql-java — field visibility, introspection | `https://www.graphql-java.com/documentation/field-visibility` |
| graphql-java — application concerns: context, authorization in the domain | `https://www.graphql-java.com/documentation/concerns` |
| Source, by tag — when the page and the code disagree, the code wins | `https://github.com/spring-projects/spring-graphql/tree/v<version>` · `https://github.com/graphql-java/graphql-java/tree/v<version>` · `https://github.com/spring-projects/spring-boot/tree/v<version>` |
| GraphQL over HTTP (draft) — media types, status codes | `https://graphql.github.io/graphql-over-http/draft/` |
| Relay cursor connections | `https://relay.dev/graphql/connections.htm` |
| graphql.org Learn — pagination, authorization, security, performance, response and errors, schema design, file uploads, thinking in graphs | `https://graphql.org/learn/pagination/` · `/learn/authorization/` · `/learn/security/` · `/learn/performance/` · `/learn/response/` · `/learn/schema-design/` · `/learn/file-uploads/` · `/learn/thinking-in-graphs/` |
| OWASP GraphQL Cheat Sheet | `https://cheatsheetseries.owasp.org/cheatsheets/GraphQL_Cheat_Sheet.html` |
| Apollo — errors as data | `https://www.apollographql.com/docs/graphos/schema-design/guides/errors-as-data-explained` |
| Shopify GraphQL Design Tutorial — payloads, `userErrors`, naming | `https://github.com/Shopify/graphql-design-tutorial/blob/master/TUTORIAL.md` |
| Choosing REST or GraphQL | `https://aws.amazon.com/compare/the-difference-between-graphql-and-rest/` · `https://learn.microsoft.com/en-us/azure/architecture/best-practices/api-design` |
| Initializr metadata — the `graphql` dependency id and the Boot it resolves | `https://start.spring.io` (`Accept: application/vnd.initializr.v2.2+json`) |
