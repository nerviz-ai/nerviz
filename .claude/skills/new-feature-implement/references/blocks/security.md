# Block S: Security (steps S1-S3, conditional)

**Runs only when the spec carries the security block** — `grep -m1 -E '^## [0-9.]+ Security' <spec>`,
and the block is not `none`. No match, or `none` → skip to Block M. Read the security block of
spec.md, then `32-security.md`. Servlet only: a project whose web starter is the reactive one
is a spec defect — stop and report. § 2's mechanism decides the exemplars in
`.claude/skills/security-architect/templates/` — never a second mechanism because a class looked
easier with it:

| `32-security.md` § 2 | Exemplars |
|---|---|
| Resource server, signed token | `SecurityConfig.java.example` + `JwtResourceServerConfig.java.example` |
| Resource server, opaque token | `SecurityConfig.java.example` + `OpaqueTokenResourceServerConfig.java.example` |
| Users owned by the application | `SecurityConfig.java.example` + `LocalUsersSecurityConfig.java.example` |
| API key | `SecurityConfig.java.example` + `ApiKeyAuthenticationConfig.java.example` |

- **S1 · Wiring** — every component § 4 marks NEW or CHANGE, in the `[config]` package (or
  `[rest]/security` where the blueprint has none): the filter chain with § 1's public rows in
  specific-to-general order and deny by default last, the mechanism wiring, the role catalog,
  `ProblemDetailSecurityHandlers.java.example`, `SecurityExceptionHandler.java.example` ordered
  ahead of `ApiExceptionHandler`, `MethodSecurityAnnotations.java.example`,
  `SecurityAuditListener.java.example`, `OpenApiSecurityConfig.java.example`. The dependencies
  of § 8 in `pom.xml` — exactly those rows, no version where the Boot parent manages them. A
  REUSE component is not rewritten; a CHANGE adds the one matcher, constant or origin the row
  names
- **S2 · Endpoints** — per row of § 1: the request rule in the chain, or the method-security
  annotation (or the meta-annotation § 1 names) on the **controller implementation**, never on
  the `Api` contract interface. Per ownership row of § 5: the controller resolves the actor from
  the principal attribute § 5 names into the value object `10-domain.md` declared, and passes
  it in the command — `CurrentActorResolution.java.example`. The ownership check itself is
  Block 1's, already in the use case
- **S3 · Configuration** — the properties of § 9 in `application.yml` (shape in
  `application-security.yml.example`), every secret as an environment placeholder, never a
  literal

Rules: `.claude/rules/authorization.md`, `.claude/rules/api-rest.md` § Errors,
`.claude/rules/logging.md` (no token, header or password in a log line),
`.claude/rules/observability.md` § Metrics.

Validation, each item checkable:

- the greps of `.claude/rules/authorization.md` § How to verify return what that section says;
- every `permitAll` in the chain matches a public row of § 1, and the last request rule is
  authenticated or deny-all;
- no class under the domain or application packages imports `org.springframework.security`;
- a method-security denial in a controller reaches `SecurityExceptionHandler`, not the 500
  fallback (the contract tests of Block 4 prove it).

Compilation: `./mvnw -q test-compile` (or `-pl <module that holds the configuration package>`
in multi-module) ✅

**Line in the final report:**
```
✅ Block S: Security complete (steps S1-S3)
- Mechanism: resource server, signed token (NEW)
- Public: /v3/api-docs/**, /swagger-ui/**, /actuator/health, /actuator/info
- Endpoints: cancelOrder (authenticated in chain; owner or SUPPORT in the use case)
- Files: 7 classes, 0 errors
```
