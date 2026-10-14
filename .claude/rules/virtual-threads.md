---
paths:
  - "**/src/main/resources/application*.yml"
  - "**/src/main/resources/application*.yaml"
  - "**/src/main/resources/application*.properties"
status: active
---

# Virtual threads — on whenever the project targets JDK 21 or later

Spring Boot runs request handling, `@Async` work, `@Scheduled` triggers and the message listener
containers on platform threads unless one property says otherwise, and a project that targets
JDK 21 pays a pool per technology for nothing. This norm owns that property and the one that
keeps the JVM alive beside it. What the request pool used to bound — how many requests run at
once — is `@.claude/rules/inbound-admission.md`; a job's scheduler is
`@.claude/rules/scheduling.md`.

## The norm

- **A project whose build targets JDK 21 or later sets `spring.threads.virtual.enabled: true`**
  in the main application configuration file. The target is the build's
  (`<java.version>`, `sourceCompatibility`, the toolchain's language version), never the JDK
  of the machine that runs it: a build for 17 started on a 21 JVM is a 17 project
- **`spring.main.keep-alive: true` beside it.** Virtual threads are daemon threads: an
  application whose only work is a scheduler or a listener exits once the last platform thread
  ends, without an error
- **A profile does not turn either off** unless a comment on the line that sets `false` states
  why. A test profile inherits the main file's value
- **A project whose build targets a JDK below 21 sets neither.** The property is ignored there,
  and a line that does nothing reads as a decision someone took

## What changes when they are on

- The servlet container runs each request on its own virtual thread, so its thread pool no
  longer bounds concurrency. The scarce resource is what a request holds while it runs — a
  database connection, a client's connection pool, a permit
- The scheduler and the application task executor ignore their pool properties
  (`spring.task.scheduling.pool.*`, `spring.task.execution.pool.*`): a value written there is
  read by nothing

## How to verify

```bash
# The build's target JDK — 21 or later expects both properties below.
grep -m1 '<java.version>' pom.xml; grep -m1 -E 'sourceCompatibility|languageVersion' build.gradle

# Both properties, in the main file. Two hits is the expected result on JDK 21+.
grep -rnE 'virtual:|virtual\.enabled|keep-alive' src/main/resources/application.yml src/main/resources/application.properties

# No profile turns them off. Each hit carries a comment stating why.
grep -rnE 'enabled: *false|virtual\.enabled *= *false|keep-alive: *false|keep-alive *= *false' src/main/resources/application-*
```
