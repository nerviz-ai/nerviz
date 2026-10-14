# Client decision matrix — which client, which engine, which authentication

Reference for `http-client-architect`. Loads only when the skill runs. Each table is read top to
bottom and **the first row that matches decides** — the rows are ordered by elimination power.
Tested to compile against Spring Boot 4.1.1 and Spring Framework 7.0.9 on 2026-10-05 — a dated note
of what was verified, not a version to use: every version a partial writes still comes from the
Boot parent or `maven-metadata.xml`, never from this page.

---

## 1. Client — per dependency

| # | Question | If yes | Exemplar |
|---|---|---|---|
| 1 | Does the project already call this provider through Spring Cloud OpenFeign? | **OpenFeign, legacy** — keep it under the norm, or migrate when this case rewrites the client anyway (the partial's § 2 says which) | `FeignClientAdapter.java.example` |
| 2 | Is the response a stream the provider pushes (SSE, NDJSON) and consumed item by item? | **`WebClient`**, servlet project, bounded stream | `WebClientStreamAdapter.java.example` + `ReactorNettyConfig.java.example` |
| 3 | Does the provider publish an OpenAPI document of the operations this case needs, versioned and usable? | **OpenAPI Generator** → HTTP interfaces | `OpenApiGeneratedAdapter.java.example` + `openapi-generator-maven.xml.example` / `-gradle.kts.example` |
| 4 | Does the URL come from data (a customer's endpoint), or does the call page by `Link: rel="next"`, or stream a large body into a sink? | **`RestClient` direct** | `RestClientAdapter.java.example` |
| 5 | Anything else — a provider with a stable contract | **HTTP interface (`@HttpExchange`)** — the default | `HttpExchangeAdapter.java.example` |

Never chosen for new code, and why:

| Option | Status | Instead |
|---|---|---|
| `RestTemplate` | Framework 7.0 announced its deprecation, 7.1 marks it for removal, 8.0 removes it; Boot 4.2 deprecates `RestTemplateBuilder` and `TestRestTemplate` | `RestClient` — `RestClient.create(restTemplate)` bridges a legacy component while it migrates |
| Spring Cloud OpenFeign | feature-complete, bug fixes only; its README points to HTTP Service Clients | HTTP interface (row 5) — migration recipe in `FeignClientAdapter.java.example` |
| OkHttp as the engine | `OkHttp3ClientHttpRequestFactory` does not exist in Framework 7 | Apache HttpClient 5 |
| `WebClient` + `block()` for ordinary calls | a reactive stack carried for a blocking call; virtual threads with `RestClient` cover fan-out | `RestClient` |
| A WebFlux application | out of scope: no reactive norm exists in this project | — stop and say so |

## 2. Engine — once per project

| # | Question | If yes | Exemplar |
|---|---|---|---|
| 1 | Does the project already pin an engine (`spring.http.clients.imperative.factory`, an engine customizer on disk, an earlier `28-http-client.md` § 3)? | **Inherit it** | — |
| 2 | Is there a recorded reason not to add a dependency (image size policy, a platform that forbids it)? | **JDK `HttpClient`** — and its keep-alive set by `jdk.httpclient.keepalive.timeout` at JVM start | `JdkHttpClientConfig.java.example` |
| 3 | Does a provider require HTTP/2 — it refuses HTTP/1.1, or the case multiplexes many concurrent calls to one host over one connection? | **JDK `HttpClient`** — Apache HttpClient 5's classic API, the one `RestClient` drives, speaks HTTP/1.1 only | `JdkHttpClientConfig.java.example` |
| 4 | Anything else | **Apache HttpClient 5** — pool per route, lease timeout, eviction, engine retry off | `ApacheHttpClient5Config.java.example` |

Reactor Netty is not a choice on this table: it comes only with `WebClient` (§ 1 row 2), and
then `ReactorNettyConfig.java.example` bounds its pool. Jetty is supported by Boot and not
templated here — no case asked for it.

The engine is a project fact. A second engine for one provider is a divergence to report, never
added because one adapter looked easier with it.

## 3. Outbound authentication — per dependency

| # | Question | If yes | Exemplar |
|---|---|---|---|
| 1 | Does the provider authenticate this service by its client certificate? | **Mutual TLS** through an SSL bundle — the bundle itself is transport security's design | `MutualTlsClientConfig.java.example` |
| 2 | Is the call made on behalf of the user of the current request, to a provider in the same trust domain that accepts the same token (issuer and audience)? | **Token relay** | `TokenRelayInterceptor.java.example` |
| 3 | Same as 2, but the provider expects another audience? | **Token exchange** (RFC 8693) behind the authorized-client manager — no exemplar yet: record it as Deferred with its own backlog row | — |
| 4 | Does an authorization server issue tokens to this service as a machine? | **OAuth2 client credentials** | `OAuth2ClientCredentialsConfig.java.example` |
| 5 | Does the provider require each request to be signed with a shared secret? | **HMAC request signing** — or the provider's SDK signer when one exists (AWS SigV4) | `HmacSigningInterceptor.java.example` |
| 6 | Does the provider issue a static key? | **API key** — header through the group property; query parameter or rotated key through the interceptor | `ApiKeyAuthConfig.java.example` |
| 7 | Does the provider accept a username and password per request? | **HTTP Basic**, over TLS only | `BasicAuthConfig.java.example` |
| 8 | None — the provider is public | **none**, recorded with the reason | — |

Rows 1 and 4–7 may combine (mTLS **and** client credentials is common in open banking): record
each, in this order of the table.

## 4. Defaults that cause incidents — what each exemplar answers

| Library | Default | Consequence | Answered in |
|---|---|---|---|
| JDK `HttpClient` | no connect timeout, no request timeout | a call blocks forever | `spring.http.clients.*`, `JdkHttpClientConfig.java.example` |
| Apache HttpClient 5 | 25 connections in total, 5 per route | pool starvation in a service that talks to one host | `ApacheHttpClient5Config.java.example` |
| Apache HttpClient 5 | response timeout unset, idle eviction off, automatic retry on | `NoHttpResponseException` after idle periods; a second, hidden retry layer | same |
| Reactor Netty | `maxIdleTime` unset | `PrematureCloseException` when the load balancer closes an idle connection | `ReactorNettyConfig.java.example` |
| Boot engine detection | Apache → Jetty → Reactor → JDK → Simple, first on the classpath | a transitive jar changes the engine, and an engine customizer typed to the old one stops applying, in silence | `spring.http.clients.imperative.factory` pinned |
| `@Retryable` | any exception, 3 retries, 1 s apart | retries a 400 and a deserialization error | `DependencyRetryPredicate` |
| `@Retryable` | inert unless `@EnableResilientMethods` is present | no retry at all, no error saying so | `OutboundHttpConfig` |
| Feign | `HttpURLConnection` client; its own timeouts | no pool sizing; timeouts nobody chose | `FeignClientAdapter.java.example` |
| OpenAPI Generator | `spring` generator = server; `java` generator = `okhttp-gson`; tests, docs and nullable wrappers generated | a second HTTP stack; code nobody reviews | `openapi-generator-maven.xml.example` |

## 5. Observed on Boot 4.1.1 while the exemplars were verified

Facts a reader would not guess, each found by running the exemplars, not by reading docs:

| Fact | Consequence |
|---|---|
| The pool lease timeout of Apache HttpClient 5 is `org.apache.hc.core5.http.ConnectionRequestTimeoutException`, an `InterruptedIOException` — not a `SocketTimeoutException` | classified as "not sent" explicitly in `HttpFailureTranslator`; generic timeout checks miss it |
| A host refused by `InetAddressFilter` throws `org.springframework.boot.http.client.FilteredHostException`, a plain `RuntimeException` — not a `RestClientException` | the translator never sees it; the adapter that takes a URL from data catches it (`RestClientAdapter.java.example`) |
| Adding `spring-boot-starter-security-oauth2-client` to a project with no `SecurityFilterChain` turns on Boot's default chain: every endpoint but `/actuator/health` answers 401 | the partial's § 10 records it as an impact whenever no chain exists |
| `resilience4j-spring-boot4` resolves its core modules to the Spring Cloud BOM's older Resilience4j when OpenFeign (or any Spring Cloud starter) is in the build | import `io.github.resilience4j:resilience4j-bom` ahead of `spring-cloud-dependencies` in `dependencyManagement`; the partial's § 8 says so |
| `@ImportHttpServices` proxies a package-private HTTP interface, and the group properties (`base-url`, `connect-timeout`, `read-timeout`, `default-header`, `apiversion`, `ssl.bundle`) apply to it | the HTTP interface, its DTOs and its configuration stay package-private in the provider's subpackage |
| `HttpClientErrorException.UnprocessableContent` is the 422 type in Framework 7 (`UnprocessableEntity` is deprecated) | the 422 catch in `HttpExchangeAdapter.java.example` |
| The OpenAPI Generator's `spring-http-interface` output still imports `org.springframework.lang.Nullable`, deprecated in Framework 7 | a build with `-Werror` excludes the generated sources; nothing to fix in the spec |
