---
paths:
  - "**/adapter/in/scheduling/**"
  - "**/infrastructure/scheduling/**"
status: active
---

# Scheduling — jobs, triggers, and coordination across instances

Code that runs on its own clock instead of on a request or a message: a recurring pass, a
calendar-anchored job, a bulk run, a background task enqueued for later. The event that starts
it is time, and nothing upstream waits for its answer — which is why its failures are the
quietest in the system. A job that stopped running raises no error; it only stops producing
effects.

## Boundary

- **A trigger is a driving adapter**, exactly like a controller or a listener: it translates
  "the clock fired" into a call on one inbound port of the application layer. No business
  logic, no repository, no broker type, no JPA type in it. It lives in the scheduling package
  of the architecture, never next to whatever the job happens to touch
- The transaction opens in the use case the trigger calls, never in the trigger —
  `@.claude/rules/architecture-ddd.md` § Application
- A job that sweeps persisted state (a relay, a reconciler, a retention pass) reaches it through
  an abstraction the application layer owns — `@.claude/rules/architecture-ddd.md` § Adapters
- **Scheduling wiring lives with its triggers.** `@EnableScheduling`, a lock provider, a
  scheduler's job and trigger beans, a batch job definition: adapter-local wiring, in the
  scheduling package, not in a project-wide configuration package. Same exception, and same
  reason, as broker wiring in `@.claude/rules/messaging.md` § Boundary
- **One scheduling technology per project**, plus at most one coordination mechanism for it. A
  second scheduler is a recorded decision with its reason, never a side effect of one job's
  preference: two schedulers mean two thread pools, two sets of tables, two places to look
  when a job did not run. The framework's own scheduler is not a second technology when it
  runs a high-frequency polling pass whose coordination is a row claim: a persistent scheduler
  would write its store on every fire and coordinate nothing the claim does not already

## Triggers

- **Fixed delay by default** for a polling pass: the next run starts a set interval after the
  previous one finished, so a slow pass never overlaps the next. Fixed rate only when the body
  is short, idempotent, and an overlap is either impossible or measured and harmless
- **Cron for calendar-anchored work, always with an explicit zone.** A cron without one runs in
  the JVM's default zone, which is whatever the container image happened to set
- Interval and cron come from a property with a default in the placeholder, never a literal in
  the annotation. A property read by an annotation placeholder is resolved before any bean
  exists, so the same value bound into a properties record is read by nothing — name it in one
  of the two, not both
- **Every job has an on/off property, defaulted on, and the test profile turns it off.** A
  `@SpringBootTest` boots every trigger in the context; an ungated one runs against the test
  database, on its own clock, under tests that assert something else. A test that exercises a
  job calls its inbound port directly
- **Pool size is a decision.** A platform-thread scheduler runs on a single thread unless
  configured otherwise, so a second job waits for the first. On JDK 21 or later virtual threads
  are on (`@.claude/rules/virtual-threads.md`) and the pool keys are read by nothing; below 21,
  more than one job means a pool sized to the job count, stated in configuration

## Execution guarantees

- **Assume at-least-once.** A crash between the effect and its bookkeeping reruns the pass, so
  the body is idempotent: a repeated run must not repeat the effect
- **More than one instance makes coordination explicit, and it is stated, not hoped for.** Two
  shapes are legitimate: work partitioned by a claim that locks or leases rows
  (`@.claude/rules/persistence.md` owns that query), or a lock around the trigger so only one
  instance runs the pass. An unlocked pass on N instances runs N times, every time. A single
  instance is a legitimate answer only as a written deployment constraint
- **A lock carries two bounds.** The upper bound on how long it is held exceeds the worst run
  observed, with margin — shorter, and a second instance starts while the first still works.
  The lower bound, for very short jobs, exceeds the clock difference between instances. The
  lock's time comes from the database clock, not each instance's
- **A missed run is either skipped or caught up, and the choice is written.** Catch-up needs a
  scheduler that persists its triggers and applies a misfire policy, or a job that computes its
  window from persisted state — the last successful watermark — never from "now minus the
  interval"
- **Bounded work per pass.** Every pass reads a capped batch; a delete or update over a hot
  table runs in batches with its own limit. One unbounded statement locks the table the
  application writes to
- **One poisoned item never stalls the pass.** The failure is recorded against that item,
  counted, and the pass continues. A trigger that lets an exception escape stops nothing but
  its own log line; one that catches and discards makes the failure invisible
- **Volume that needs restart from the point of failure needs persisted progress.** A loop
  inside a scheduled method restarts from zero; chunked processing with a job repository
  restarts from the last committed chunk. Pick by volume and by what a rerun costs

## Observability

- **Every job exposes when it last succeeded.** A timer per execution, tagged by outcome, and a
  gauge of the last successful completion: a job that silently stopped is visible only as
  staleness, and staleness is only visible if something measures it. Alarm thresholds and
  cardinality follow `@.claude/rules/observability.md` § Metrics
- Scheduled executions are observed through the framework's observation hook, so a run carries
  a trace like any request
- Logs: the outcome of a pass at `INFO` with counts (claimed, done, failed), never payloads;
  a pass that found nothing to do is not logged — `@.claude/rules/logging.md`

## Retention

- **A retention window exists only while a job enforces it.** The property that holds the
  window ships in the same change as the job that reads it; a window written in configuration
  with no reader is a promise nobody keeps — `@.claude/rules/personal-data.md` § At rest

## How to verify

```bash
# Triggers live in the scheduling package (`scheduling`, or `scheduler` in a layered
# architecture). Zero lines is the expected result.
grep -rn "@Scheduled\|@SchedulerLock\|implements Job\b\|@Recurring" --include='*.java' src/main | grep -vE "/(scheduling|scheduler)/"

# Exactly one @EnableScheduling in the project.
grep -rln "@EnableScheduling" --include='*.java' src/main | wc -l

# Every cron names its zone. Zero lines is the expected result.
grep -rn "cron *= *\"" --include='*.java' src/main | grep -v "zone"

# Every fixedRate is a decision someone can point at — review each hit.
grep -rn "fixedRate" --include='*.java' src/main

# Every job's on/off property is off in the test profile — compare against the triggers above.
grep -rn "enabled: *false" src/test/resources/
```
