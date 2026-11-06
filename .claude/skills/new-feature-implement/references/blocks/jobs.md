# Block J: Jobs (steps J1-J3, conditional)

**Runs only when the spec carries the jobs block** — `grep -m1 -E '^## [0-9.]+ Jobs' <spec>`,
and the block is not `none`. No match → skip to Block C. Read the jobs block of spec.md, then
`35-jobs.md`. The technology in its § 2 decides which exemplar is the shape — never a second
scheduler because a class looked easier with it:

| `35-jobs.md` § 2 | Exemplar in `.claude/skills/jobs-architect/templates/` |
|---|---|
| `@Scheduled` | `ScheduledJob.java.example` — `SchedulingConfig`, `JobRunRecorder`, one trigger |
| `@Scheduled` + ShedLock | the above + `ShedLockJob.java.example` |
| Quartz | `QuartzJob.java.example` |
| Spring Batch | `SpringBatchJob.java.example` + the trigger it names |
| db-scheduler | `DbSchedulerTask.java.example` |
| JobRunr | `JobRunrJob.java.example` |
| Form B's relay and prune | `OutboxRelayJob.java.example`, `OutboxPruneJob.java.example` |

- **J1 · Wiring** — in the `[scheduling]` package: the project's single `@EnableScheduling`
  (with the observation registry set on the registrar), the lock provider or the scheduler's
  configuration § 2 names, and `JobRunRecorder` when the project has none yet. The dependencies
  of § 7 in `pom.xml` — exactly those rows, versions as the row says
- **J2 · Triggers** — one class per row of § 1: calls the inbound port the row names and
  nothing else; trigger, cadence placeholder, zone, on/off property and lock bounds exactly as
  the row says. Under Form B, `OutboxRelayJob` records the two delivery metrics from each
  pass's `RelayOutcome`, and `OutboxPruneJob` goes with `PruneOutboxEvents`, its service and
  `OutboxRetentionGateway` (the application side) — the gateway's implementation is Block 2's.
  `AuditHistoryPruneJob` follows the same shape with `PruneAuditHistory` and
  `AuditHistoryRetentionGateway`. A trigger whose inbound port writes an audited aggregate wraps
  the call in `SystemActor.run("<job-name>", …)`
- **J3 · Configuration** — the properties of § 8 in `application.yml`, and **every job's
  on/off property set to `false` in `src/test/resources/application-test.yml`**

**A relay in the pre-split shape** — `@Scheduled` on a class in the messaging adapter, a
`SchedulingConfig` there — is never left next to `OutboxRelayJob`. The spec's impact section says
whether this run migrates it (delete the old trigger and config, move the meters to the job) or
leaves it for a backlog row; with neither, it is a spec defect: stop and report.

Rules: `.claude/rules/scheduling.md`, `.claude/rules/architecture-ddd.md` § Adapters,
`.claude/rules/observability.md` § Metrics, `.claude/rules/logging.md`.

Validation, each item checkable:

- the `@Scheduled`/`@SchedulerLock`/`implements Job` grep of `.claude/rules/scheduling.md`
  § How to verify returns nothing outside the scheduling package, and there is exactly one
  `@EnableScheduling`;
- every trigger's only project dependency is an inbound port (plus `JobRunRecorder`);
- every cron names its zone; every job has its `enabled` property off in the test profile;
- a lock's bounds match § 3, and no lock wraps the relay when § 3 says the claim coordinates;
- a property § 8 attributes to `20-persistence.md` (`app.outbox.prune-after` among them)
  ships with the job that reads it — and a job whose property has no value to read fails the
  boot, it does not run on a number nobody chose.

Compilation: `./mvnw -q test-compile` (or `-pl <module that holds the scheduling package>` in
multi-module) ✅

**Line in the final report:**
```
✅ Block J: Jobs complete (steps J1-J3)
- Technology: @Scheduled + ShedLock (prune only)
- Jobs: OutboxRelayJob (fixedDelay PT1S, row claim) · OutboxPruneJob (cron 03:30 America/Sao_Paulo, lock)
- Test profile: app.outbox.enabled=false, app.outbox.prune.enabled=false
- Files: 6 classes, 0 errors
```
