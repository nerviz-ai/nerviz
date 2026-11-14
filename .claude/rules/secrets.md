---
paths:
  - "**/src/**"
  - "**/*.yml"
  - "**/*.yaml"
  - "**/*.properties"
  - "**/Dockerfile*"
status: active
---

# Secrets — credentials, keys and keystores

A secret is any value that grants access: a password, a client secret, an API key, a token, a
signing key, a private key, a keystore or its password, a connection string that embeds a
password. Once a secret is in a commit it is in every clone and every fork, and removing it from
history does not revoke it. This norm owns where secrets may live; what may be logged is
`@.claude/rules/logging.md`, and personal data is `@.claude/rules/personal-data.md`.

## Where a secret may live

- **Never in a versioned file.** Not in `application*.yml` or `.properties`, not in Java, not in
  a `Dockerfile`, a compose file, a CI workflow, a test resource or a README — not even
  "temporarily", not even a value that "only works locally"
- **A secret reaches the application through configuration resolved from its environment**: an
  environment variable, a mounted secret file, or a secret store integrated with the
  configuration. The versioned file holds only the placeholder (`${DB_PASSWORD}`)
- **A placeholder for a secret has no default value**, or an empty one. A default is a value in a
  versioned file. A missing secret fails the startup with the placeholder's name; it never falls
  back to something that happens to work
- A secret is never baked into an image: not copied, not passed as a build argument, not left in
  a layer. It is provided to the running container
- CI reads secrets from the CI platform's secret store, by name. A workflow file names the secret,
  never holds it
- A local developer keeps secrets in an untracked file (`.env`, an IDE run configuration) that the
  repository's ignore list covers, or in the shell

## Key material

- **No private key, keystore or truststore holding a private key is committed**, whatever its
  format (`.key`, `.pem` with a private key, `.p12`, `.pfx`, `.jks`) and whatever it protects
- Key material is mounted at run time, from the platform or the renewal client, and its path
  comes from the environment
- **A test generates the key material it needs when it runs**, in a temporary directory, for
  `localhost` only and with a lifetime no longer than the test run needs. A committed
  "test-only" key ends up trusted somewhere it should not be
- A certificate alone, without its private key, is public and may be committed — a CA a client
  must trust, for example

## Configuration types

- **A configuration type that holds a secret never prints it**: its `toString` masks the value,
  and it is never returned by an endpoint, logged, or written to an error body. The Actuator
  `env` and `configprops` endpoints stay unexposed, or sanitize every key
- A secret is not compared with `equals` on a string read from a request; a constant-time
  comparison is used

## When a secret leaks

- **A secret that reached a commit, a log or a ticket is rotated**, at its issuer. Rewriting
  history is not a fix: every clone made before the rewrite still holds it
- The rotation is done before the history is cleaned, never after

## How to verify

```bash
# No private key or keystore in the tree. Zero lines is the expected result.
git ls-files | grep -iE "\.(key|p12|pfx|jks)$"
git grep -lE "BEGIN ([A-Z]+ )?PRIVATE KEY"

# No literal value next to a secret-looking key. Review every hit.
grep -rniE "(password|secret|token|api[-_]?key|private[-_]?key)\s*[:=]\s*[^$ {][^ ]*" \
  --include='*.yml' --include='*.yaml' --include='*.properties' src/main/resources/

# No default on a secret placeholder. Review every hit.
grep -rnE "\\$\{[A-Z_]*(PASSWORD|SECRET|TOKEN|KEY)[A-Z_]*:[^}]+\}" src/main/resources/
```
