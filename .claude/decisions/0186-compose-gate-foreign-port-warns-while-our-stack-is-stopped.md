# 0186 · A foreign container on our port warns, and does not block, while every container of ours is stopped

- **Date:** 2026-10-24
- **Scenario:** Issue #175, triaged at `2f28ffa`: compose gate blocks every Stop on foreign-port
  collisions once any container of the project exists, even when 0 are running (0129's
  carve-out only covers `total == 0`).
- **Decision:** Options 1 and 4 together — Form 7c change to `composeReport()` in `.claude/hooks/ArchHook.java` (collisions warn while no container of ours is outside `exited`/`dead`; a `docker compose down` hint on the `exited` line), plus a project-owned switch for the `exited` line, `.claude/compose-gate.json`, named by `compose.project_overrides` in `.claude/schemas/extensions.json`, validated by a new `Compose overrides` line of `doctor` that `doctor.gate.labels` lists. The `Stop` registration is unchanged
- **State:** approved by Lucas Fernandes, on 2026-10-24

## Reproduced on disk before classifying

Checked against `2f28ffa` (`v0.42.4`) by `issue-verifier`, layer `static`. Only the confirmed
rows are listed here. The fix the issue proposed (a `block` / `warn` / `off` setting for the
whole gate) is not an input to this record.

| Claim | On disk |
|---|---|
| One stopped container of ours switches off 0129's carve-out, and foreign-port lines go back to `detail` | Confirmed, `ArchHook.java:2097` (`total++` for every `ps -a` entry, whatever its state) and `:2118` (`collisions = total == 0 ? warnings : detail`) |
| A service that is `exited` blocks | Confirmed, `ArchHook.java:2103-2104` and `:2151` (`ok = detail.isEmpty()`). By design under 0064 |
| The gate always exits 2 and has no non-blocking exit | Confirmed, `ArchHook.java:2045-2050` |
| The block text quoted in the issue is the gate's | Confirmed, `ArchHook.java:2046-2049`, `:2134-2141`, `:2157` |
| 0129 blocks once any container of ours exists, stopped or `created` included | Confirmed, `0129` axis 15; `docs/en/11-pitfalls.md:299-301` |
| The block repeats every later turn | Confirmed, `ArchHook.java:2043`: the only skip is `stop_hook_active`, which lasts one turn |
| `doctor gate` never gates `Compose` | Confirmed, `0128` ("runner state") |
| Exit 2 on `Stop` blocks and feeds stderr to the model; another non-zero exit does not block | Confirmed, `claude-help.md:906-911` |
| 0129 rejected an env-var switch and scoping by change set | Confirmed, `0129` options 2-4 |
| 0111's opt-out is a project-owned file `export` never names | Confirmed, `extensions.json:339-340` |
| Seen at `v0.42.4` | Confirmed, `HEAD` = `v0.42.4`; nothing already fixed |

Not confirmed, and not used: "removing the registration is reported as drift" (partly true —
`/arch-adopt` overwrites `settings.json`, `extensions.json:1025`, but no drift wording exists),
and the turn's own content (unproven, and irrelevant: `composeGate` reads no change set).

## What is actually open

The issue's state has two blocking lines, and they differ in who can clear them.

- `service otel-collector is exited, not running` — 0064's accepted cost ("a running stack that
  is legitimately half-down … blocks the stop until they say so"). The project clears it by
  itself: `docker compose up` or `docker compose down`.
- `host port 5432 is held by <foreign>, a container of ANOTHER project` — its only stated way
  out is `docker stop` on another project. No container of ours is binding that port: the one
  container this project has is `exited`, and `postgres` has none.

0129 rejected "create nothing" on this exact ground — "the block cannot be cleared without
touching another project" — but keyed the carve-out on `total == 0`. That reason holds for any
state where no container of ours is trying to bind, and `total == 0` is only the empty case of
it.

The criterion is **not** `running == 0`. 0129's own by-hand run had `postgres` stuck in
`created` (`Bind for 0.0.0.0:15432 failed`), `0/1 running`, and there the collision *is* the
cause of the failure: that line must keep blocking. A container is binding, or failed to bind,
in every state except `exited` and `dead`.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | The verified table above: a block whose foreign-port line only another project can clear, at `0/1 running` with the one container `exited` | Create nothing as the answer — 0129 already ruled that cost out |
| 7 — mandatoriness | The gate stays. One class of its lines is wrong in one state | 1 · 2 · 4 · 5 |
| 14 — lifecycle event | `Stop`, unchanged | Any new registration |
| 15 — reaction | Foreign-port lines warn while every container of ours is `exited` or `dead`. The `exited` line itself blocks by default (0064) and a project can set it to warn. `created` and a collision beside a running service always block. `doctor` and `compose` keep printing every line | A fully stopped stack going silent for everyone (option 3) |
| 8 — destination, switch | The project decides, in a versioned file `export` never names (0111's shape). Not per developer, not an environment variable | An env var or a gitignored local file (0129 option 4's objection: invisible in git) |
| 16 — existing mode | `composeReport()`, shared by `compose`, `doctor` and `compose gate`; 0124's `warnings` channel and 0129's routing already exist | A new mode. This 7c edits an existing method |
| 8 — destination | Both. `ArchHook.java` and the jar travel whole through `export`; the registration is unchanged | — |
| 17 — CI | By-hand verification with Docker, as in 0129. The maintainer's call | A Docker case in `ComposeGateTest` |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `ArchHook.java` `composeReport()`: collisions warn while no container of ours is outside `exited`/`dead`; the gate names `docker compose down` when nothing runs | 8 | Proposed, alone or with option 4 |
| 2 | `ArchHook.java` `composeGate()`: message only — name `docker compose down` when nothing of ours runs | 6 | — |
| 3 | `ArchHook.java` `composeReport()`: a stack with nothing running or `created` is treated as no stack at all — every daemon line warns | 4 | — |
| 4 | Option 1 plus a project-owned switch for the `exited` line (0111 shape), `.claude/compose-gate.json` | 8 | Proposed, with option 1 |
| 5 | A `block` / `warn` / `off` setting for the whole gate (the issue's proposal) | 2 | — |
| 6 | Create nothing | 3 | — |

### Option 1 — `composeReport()` routes collisions by binding state (score 8)

**Motivator:** axis 15. A foreign-port line belongs in `detail` only when a container of ours
is binding the port, or tried to and failed (`created`). While every container of ours is
`exited` or `dead` — `total == 0` is the empty case — the line is a heads-up for the next
`up`, exactly 0129's reasoning.

**Shape.** In the `ps -a` loop, count `binding` = entries whose state is neither `exited` nor
`dead`. Line 2118 becomes `collisions = binding == 0 ? warnings : detail`, and the clause on
the collision line (`once docker compose up runs …, so not blocking`) keys on the same count,
with its wording adjusted to "nothing of this project is running". The `exited` lines stay in
`detail`. `composeGate()` adds one line when `running == 0`: nothing of this project runs, and
`docker compose down` clears the stopped services — volumes are kept. No new data: `exited` and
`dead` are Docker's state names, the same kind of literal as the `running` and `created` the
method already tests (invariant 10 binds lists the project configures, not the daemon's
vocabulary).

**Pros:** closes the gap 0129's reason named, with the same mechanism and no new data. 0129's
`created` case still blocks with the collision line, which is the line that explains it. Every
line left in `detail` is one the project can clear on its own. The way out is stated (Phase 4
rule: a blocking hook states the way out). `compose` and `doctor` still print every collision,
as warnings.

**Cons:** the issue's turn still ends blocked: the `exited` line stays, by 0064, until the
developer runs `docker compose down` or `up`. A `paused` or `restarting` container counts as
binding, so its collision still blocks — correct, since a paused container still holds its
published ports.

**Points cut in the rubric:** § 8 criterion 9 is unchanged (the same daemon calls); one point
for leaving the 0064 line, which the issue reads as part of the same complaint.

**CI:** nothing testable in CI, by the maintainer's choice. The case needs a live daemon, a
container of ours in `exited` and a foreign container on a declared port.
`validate · hooks-cross-platform › compose gate blocks a published service no host client can
reach` (`ComposeGateTest`) keeps proving the file half this change must not move. Verified by
hand with Docker instead, red on the current jar and green on the new one.

### Option 2 — message only (score 6)

`docker compose down` already clears the issue's state today: it removes the stopped
container, `total` drops to 0, and 0129's carve-out turns the collision into a warning. The
block just never says so — the only way out it prints for the collision is `docker stop` on
another project. Adding the `docker compose down` line alone fixes the message. Rejected as the
whole answer because `detail` keeps a line the project cannot clear in that state, and the
report keeps disagreeing with 0129's own reason; option 1 carries this line anyway.

### Option 3 — a stopped stack is no stack (score 4)

Every daemon line warns while no container of ours is running or `created`, so a stack stopped
on purpose never blocks. It answers the whole complaint. Rejected: Docker gives no reliable
way to tell "stopped on purpose" from "crashed during the turn" (`docker stop` exits 0 or 143
depending on the image), so a single-service stack that crashed to `0/1` would go silent —
the failure 0064 exists to catch. 0064 weighed the half-down running stack; the stopped stack
is its accepted cost with a clean way out, which option 1 states.

### Option 4 — option 1 plus a project-owned switch for the `exited` line (score 8)

**Motivator:** axis 15. After option 1 the `exited` line is the only thing left that blocks a
stopped stack, and it blocks by design (0064). Reclassifying it for everyone loses the crash
the gate exists to catch (option 3), so only a choice the project makes can relax it. That is
the fact 0129 option 4 said was missing ("no failure has been observed that only an opt-out
would fix"): the verifier's table shows one.

**Shape** (0111's, step for step):

```json
{ "exited": "warn" }
```

- File `.claude/compose-gate.json`, named by `compose.project_overrides` in `extensions.json`
  (invariant 10), absent by default. `export` writes only the paths its manifest names and
  deletes only `export.retired`, so `/arch-adopt` never touches it (0111, "Found while
  designing").
- One key, `exited`, values `"block"` (the default, today's behaviour) and `"warn"`.
  Absent file, absent key or any other value → `block`.
- `warn` sends the lines for services in `exited` or `dead` to `warnings`. Always blocking,
  whatever the file says: `created`, any other non-`running` state, a collision beside a
  service that is running or `created`, and the whole file half. `compose` and `doctor` print
  the `warn` lines, so the arch-doctor still alerts.
- A new `doctor` line, `Compose overrides`, validates the file as strictly as `Audit
  overrides`: one key, two values, anything else fails by name, so a typo does not switch
  nothing in silence. It also says when the file is in force. The line goes into
  `doctor.gate.labels`: a malformed file is a defect of a versioned file, not runner state
  (0128 keeps `Compose` itself out, and that stays).

**Pros:** answers the request: `exited` configurable, default unchanged, `doctor` still
alerts. Versioned, so the relaxation is visible in git and reviewed with the project. Survives
`/arch-adopt` by construction. Every file-half guarantee stays at exit 2 (invariant 6), and so
does the `created` case.

**Cons:** strains invariant 2: two places answer "does an exited service block", the code's
default and the project's file. Bounded as in 0111 by one fixed precedence and by `doctor`
naming it. A project that sets `warn` loses 0064's catch of a crashed single-service stack
until someone reads the warning. It is the project's whole team, not one developer.

**Points cut in the rubric:** criterion 2 (invariant 2 strained, not violated); criterion 5
(a second file `composeReport()` reads, plus a validator).

**CI:** the file's rules need no daemon, so they are tested: new cases in
`.claude/.ci/DoctorGateTest.java` (`validate · hooks-cross-platform › doctor gate fails on
every gated line and only on those`): a malformed file fails the gate by the `Compose
overrides` label, a valid one passes, no file is silent. The effect on the daemon half needs a
live Docker and is verified by hand, red on the current jar and green on the new one, as in
0129.

### Option 5 — a setting for the whole gate (score 2)

`warn` or `off` also silences the file half — the advertised address, the placeholders, the
image pins — which is the defect 0064 exists for. A guarantee turned into a per-project
choice against an observed failure in one class of its lines: invariant 6, and 0116's
precedent of narrowing a `Stop` gate instead of switching it off.

### Option 6 — create nothing (score 3)

`docker compose down` is a way out today. But the block never names it and points at another
project instead, and 0129 already rejected the same cost for `total == 0`.

## References

| Claim | Source |
|---|---|
| `compose gate`, `compose` and `doctor` share one definition of healthy | `ArchHook.java` `composeReport()` Javadoc · `0064` |
| A stopped service blocking is the gate's accepted cost, for a running stack half-down | `0064` option 1, Cons |
| 0129's carve-out, and its reason: a block only another project can clear | `0129` § What is actually open, option 5 |
| A `created` service with a collision must keep blocking | `0129` § CI coverage, by-hand case 3 |
| `warnings` are printed by `compose`/`doctor` and never read by the gate | `0124` · `ArchHook.java:2073`, `:2158` |
| Exit 2 on `Stop` blocks and its stderr reaches the model | `@claude-help.md:906-911` |
| A blocking hook names the rule and the way out | `claude-code-architect-designer` Phase 4 step 6 |
| Narrow a `Stop` gate instead of switching it off | `0116` |
| A project opt-out is project-owned and versioned | `0111` |
| A rule that must always hold is a hook; it changes only against an observed failure | `@CLAUDE.md` invariant 6 |
| A triaged issue's verified rows are the record's evidence; its proposed fix is not an input | `0103` |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `composeReport()`: `binding` counts containers outside `exited`/`dead`; `collisions = binding == 0 ? warnings : detail`; `exited`/`dead` lines go to `warnings` when the project file says `warn`, and carry the `docker compose down` hint. `composeGate()` names both ways out. New `composeProjectFile()`, `composeExitedWarns()`, `composeOverrideProblems()`; `doctor` prints `Compose overrides` |
| `.claude/hooks/ArchHook.jar` | Rebuilt under JDK 21; `build --verify` passes |
| `.claude/schemas/extensions.json` | `compose.project_overrides` and its comment; `Compose overrides` in `doctor.gate.labels` |
| `.claude/.ci/DoctorGateTest.java` | The healthy fixture carries `{}`; a malformed value and a second key fail the gate by label; `{"exited":"warn"}` passes and is reported in force |
| `CLAUDE.md` § Commands | The `compose` row: the carve-out's new condition and the project switch |
| `docs/en/11-pitfalls.md`, `docs/pt-br/11-pitfalls.md` § Compose | The widened exception, the way out, the file |
| `docs/en/04-arch-doctor.md`, `docs/pt-br/04-arch-doctor.md` | `Compose overrides` in the table of lines that block CI |

Not touched: `.claude/settings.json` and `project-bootstrap/templates/settings.json.example`, since the `Stop`
registration is the same; `export`, which never names the new file. No restart is needed: every hook
launches the jar.

Goes to the generated project: **yes**. `export` copies `ArchHook.java`, the rebuilt jar and
`extensions.json` whole, so `/arch-adopt` carries it; the project's own `compose-gate.json` survives it.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › doctor gate fails on every gated line and only on those` (`DoctorGateTest`) | A malformed `.claude/compose-gate.json` — a value other than `block`/`warn`, or a second key — fails `doctor gate` naming `Compose overrides`; a valid `warn` passes and is reported in force; the healthy fixture prints the label | green on the tree · red with the previous jar (`gated label Compose overrides is never printed by doctor`) |
| `validate · hooks-cross-platform › compose gate blocks a published service no host client can reach` (`ComposeGateTest`) | The file half still exits 2 | not run again: nothing in it moved |

The effect on the daemon half needs a live Docker, which `hooks-cross-platform` has only on its
ubuntu leg, so it was verified by hand on 2026-10-24 with Docker 29.3.1, by the maintainer's choice.
The fixture was a compose file declaring `postgres:17` on `15432:5432`; a foreign `postgres:17`
named `foreign-186` was started outside the project on the same port.

| Case | Jar | Result |
|---|---|---|
| Our `postgres` `exited`, foreign container on 15432 | previous (`2f28ffa`) | exit 2, `2 problem(s) — 0/1 running`: the `exited` line and the collision. The issue's report, reproduced (the red run) |
| Same state | new | exit 2, `1 problem(s) — 0/1 running`: the `exited` line, with the `docker compose down` hint; the collision is a warning |
| Same state, `{"exited":"warn"}` | new | exit 0, silent |
| Our `postgres` `created` (bind failed), foreign container on 15432, `{"exited":"warn"}` | new | exit 2, `2 problem(s)`: the `created` line plus the collision. 0064's case, still blocking whatever the file says |
