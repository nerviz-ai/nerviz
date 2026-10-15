---
paths:
  - "**/adapter/in/rest/**"
  - "**/adapter/in/graphql/**"
  - "**/infrastructure/config/**"
# --- below: repo convention, ignored by the runtime ---
# Who verifies this rule: see `@.claude/rules/00-index.md`
status: active
---

# Authorization — authentication and access at the entry boundary

Who may call an endpoint, how the caller proves who it is, and what the API answers when the
proof or the permission is missing. Servlet stack only: a reactive application has a different
filter model, and nothing here applies to it.

The statuses themselves — 401, 403, and when 404 replaces 403 — are
`@.claude/rules/api-rest.md` § Errors — 400 family. Masking of credentials in logs is
`@.claude/rules/logging.md`. This file decides where each check lives and what makes it
correct.

## Boundary

- **Authentication and the coarse checks happen at the entry boundary.** A role, a group, a
  scope, "any authenticated caller": decided by the filter chain's request rules or by method
  security on the controller. Never inside the domain
- **The domain and the application layer never import the security framework.** No
  `Authentication`, no token type, no `SecurityContextHolder` read in a use case. Identity
  crosses into the application as a value the command carries — the actor's id, and the
  actor's roles only when a business rule branches on them — resolved by the controller from
  the authenticated principal. A use case that reads a thread-local to learn who called it is
  untestable without the framework and silently anonymous on any other trigger
- **"Only the owner" is a business rule, not a request rule.** The use case loads the
  aggregate and compares its owner with the actor from the command. An expression on the
  controller that calls a repository to answer the same question runs a second query outside
  the transaction and puts a business rule in a string nobody compiles
- **Wiring lives in the project's configuration package**: the filter chain, the token
  decoder or introspector, the claims-to-authorities converter, the entry point and access
  denied handler, the CORS source, method-security enablement. Where the architecture has no
  configuration package, in a `security` subpackage of the inbound REST adapter. Never next
  to a controller, never in a module the domain sees
- **Method-security annotations go on the controller implementation**, beside the mapping
  annotations — the REST contract interface carries documentation alone,
  `@.claude/rules/api-rest.md` § OpenAPI. An expression repeated on more than one endpoint
  becomes a named meta-annotation, so a role is spelled once
- **On a single-endpoint protocol the request rules cannot tell operations apart.** For GraphQL
  the chain only authenticates the endpoint, and every role, group or scope is method security on
  the resolver method — per operation, and per field when a field is narrower than its operation
  (`@.claude/rules/api-graphql.md` § Access)
- **Two inbound adapters over the same input port check the same rule with the same
  meta-annotation**, declared once where the wiring lives; neither adapter keeps its own copy of
  a role. The check runs in each adapter, and each adapter's tests prove it

## Default access

- **Deny by default.** The last request rule of the chain is "authenticated" (or "deny
  all"); every endpoint that answers without authentication is a `permitAll` with a recorded
  reason. A new endpoint whose use case says nothing about access is authenticated, not
  public
- **Public by default, and only these:** the OpenAPI document and the Swagger UI paths — and the
  AsyncAPI document (`/springwolf/docs`) of a project that publishes or consumes events — unless
  the project decided they are protected too, one decision for all of them; the health and info
  endpoints, without details
- **Every other actuator endpoint requires an operator role.** A blanket `permitAll` over the
  actuator exposes environment, configuration and heap to anyone who can reach the port
- **Rules go from specific to general**, matching method and path together. A broad pattern
  before a narrow one shadows it, and nothing reports the shadowed rule
- **A path rule names every API version of its route** — `/api/*/orders/*/cancellation`,
  never `/api/v1/…`. The rule is matched against the raw path, so a new version of a
  protected route otherwise falls to the last rule and keeps only "authenticated"

## Mechanism

- **One mechanism per kind of caller, per project.** People through one, machines through at
  most one more — and the second is a recorded decision. Two token formats for the same
  callers means two validation paths to keep in step
- **Bearer token from an external identity provider** (signed token, resource server) is the
  default for a REST API. The token is validated for signature against the provider's key
  set, expiry, issuer **and audience** — a token minted for another API of the same provider
  passes every other check
- **Opaque token with introspection** when tokens must be revocable before they expire, or
  the provider only issues opaque ones. Each request costs a call to the provider; that cost
  is written next to the choice
- **Users owned by the application** only when no identity provider exists or is planned.
  Passwords are stored with an adaptive one-way hash through the framework's delegating
  encoder, never plain, never reversible, never a fast digest. A compromised-password check
  runs on every password set or changed
- **API key** for a machine caller that cannot run an OAuth client-credentials flow. The key
  travels in a header, never in the query string (it ends up in access logs); it is stored
  hashed and compared in constant time; it has an owner, a creation date and a revocation
  path. A machine that can run client credentials gets a token instead, through the same
  resource-server path people use
- **Claims become authorities in one converter.** Roles carry the `ROLE_` prefix, groups the
  `GROUP_` prefix, scopes the `SCOPE_` prefix — one prefix per kind, so `hasRole`,
  `hasAuthority('GROUP_…')` and `hasAuthority('SCOPE_…')` never collide. Role and group
  names are `UPPER_SNAKE_CASE` constants, never string literals scattered across controllers

## Transport hardening

- **Stateless.** A bearer-token API creates no HTTP session. CSRF protection is off only
  because no cookie authenticates the request; the moment a cookie does (form login, a session),
  CSRF is on
- **CORS lists its origins explicitly**, from configuration. Never a wildcard origin with
  credentials allowed; no CORS configuration at all when no browser client calls the API
- **The framework's default response headers stay on** (HSTS, `nosniff`, frame denial).
  Disabling one is a recorded decision with its reason. Whether HSTS is actually written, and
  by which single component, is `@.claude/rules/transport-security.md` § HSTS

## Failure responses

- 401 and 403 carry the same Problem Details body as every other error
  (`@.claude/rules/api-rest.md` § Error body), and a 401 carries `WWW-Authenticate`. The body
  never says which role was missing or why a token was rejected — that goes to the log
- **Not the owner answers like an absent resource**: the same not-found exception the use
  case raises when the aggregate does not exist, so the response does not reveal that someone
  else's resource exists. A 403 for ownership needs a typed exception
  `@.claude/rules/error-handling.md` does not have, and is a recorded decision
- **A denial raised by method security inside the MVC layer never reaches the 500 fallback
  handler.** It is translated to 403 (or 401 for an anonymous caller) ahead of the catch-all —
  a forbidden request answered with 500 and a stack trace is an incident report, not a
  refusal

## Credentials and logs

- Credentials come from the environment, never from a versioned file —
  `@.claude/rules/secrets.md`
- Never logged: the `Authorization` header, a token, a password, an API key, an introspection
  response. A denial is logged at `WARN` with the principal's id, method and path; a failed
  authentication with the reason category and the remote address
  (`@.claude/rules/logging.md`)
- Denials and authentication failures are counted, with tags bounded per
  `@.claude/rules/observability.md` § Metrics — never the principal or the path as a tag

## Tests

- **Every protected endpoint is proven three ways**: no credential → 401; a credential without
  the authority → 403 (or the 404 the resource chose); the right authority → the success
  status. A public endpoint is proven public with no credential at all
- Ownership is proven at the use case: the same call with another actor gets the not-found
  outcome
- **Tests run with the real filter chain.** Turning the security filters off in a web test
  proves an endpoint nobody will ever call. Credentials come from the test support's request
  builders, never from a live identity provider

## How to verify

```bash
# The security framework stays out of the domain and the application layer. Zero lines expected.
grep -rln "org.springframework.security" --include=*.java src/main | grep -E "/(domain|application)/"

# Nothing public by accident. Zero lines expected.
grep -rnE "anyRequest\(\)\.permitAll|requestMatchers\(\"/\*\*\"\)\.permitAll" --include=*.java src/main

# No request rule bound to one API version. Zero lines expected.
grep -rnE "requestMatchers\(.*\"/api/v[0-9]" --include=*.java src/main

# Every permitAll is a recorded decision — review each hit against the design record.
grep -rn "permitAll" --include=*.java src/main

# CSRF is off only where the session is stateless — every file of the first grep is in the second.
grep -rln "csrf" --include=*.java src/main
grep -rln "SessionCreationPolicy.STATELESS" --include=*.java src/main

# Never a reversible or plain password encoder, never a wildcard origin. Zero lines expected.
grep -rnE "NoOpPasswordEncoder|allowedOrigins\(\"\*\"\)|setAllowedOrigins\(List.of\(\"\*\"\)\)" --include=*.java src/main

# Web tests keep the filter chain. Zero lines expected.
grep -rn "addFilters *= *false" --include=*.java src/test
```
