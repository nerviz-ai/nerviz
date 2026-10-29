# 0194 · The audit reports how much of the plan's 5-hour and weekly windows a run consumed, read from a snapshot a personal status line writes

- **Date:** 2026-10-29
- **Scenario:** "Quero saber, após a execução fim a fim de uma skill ou agent do Nerviz, qual porcentagem do meu plano foi consumida nas janelas de 5 horas e semanal. Isso reavalia a decisão 0133, que descartou a leitura real da conta."
- **Decision:** option 1 — Form 7c, a change to the existing `audit` mode of `.claude/hooks/ArchHook.java` (open, `flush`, `close`, render, `summary`, `doctor`), with `audit.plan_usage` and `audit.windows.*.rate_limits_key` in `.claude/schemas/extensions.json`, and the personal producer's snippet in `.claude/skills/audit-usage/templates/statusline-rate-limits.json.example`. No new piece
- **State:** approved by Lucas Fernandes, on 2026-10-29
- **Goes to the generated project:** yes — `ArchHook.java`, the jar, `extensions.json` and the `audit-usage` skill (with its new template) travel through `export`; the status line itself never does, it is the person's own `~/.claude/settings.json`
- **Partially supersedes:** `0133-audit-plan-window-projection.md` — its reason for dropping the account's real usage (fact 5: a project `statusLine` overrides the person's). Reading it from a **personal** status line removes that reason. 0133's projection (runs per window from a token budget) stands unchanged, next to the measured delta

## What the runtime publishes — re-checked 2026-10-29

Re-read against code.claude.com, not carried over from 0133:

| # | Fact | Source |
|---|---|---|
| 1 | No hook input carries plan usage. `StopFailure` names `rate_limit` as an error type and `Notification` names `quota_auto_resume_*`, neither with a number | docs/en/hooks § Common input fields, matcher tables |
| 2 | The status line's stdin carries `rate_limits.five_hour` / `seven_day`, each `used_percentage` (0–100) and `resets_at` (Unix epoch **seconds**) | docs/en/statusline § Available data |
| 3 | `rate_limits` appears only for claude.ai Pro/Max (or a gateway with a spend limit), **only after the session's first API response**; each window may be absent; **a window is dropped once its `resets_at` passes** | docs/en/statusline § Available data (notes) |
| 4 | The status line runs after each assistant message, debounced 300 ms; `refreshInterval` (≥ 1 s) re-runs it on a timer too | docs/en/statusline § How status lines work, § Manually configure |
| 5 | A `statusLine` in project settings overrides the user's | docs/en/statusline § Manually configure; settings precedence |
| 6 | No CLI command or flag prints plan usage non-interactively | docs/en/cli-reference |
| 7 | A session transcript (`.jsonl`) holds no rate-limit field | grep over a transcript of this repository, 2026-10-29 |
| 8 | A mod reads the same windows: `$.session.usage().rateLimits` (`kind`, `percentUsed`, `resetsAt` ISO) and the `session.measure` event, fired when a window moves a **whole** point | installed CLI 2.1.312 types, `SessionRateLimit`, `'session.measure'` |

Two consequences the design carries:

- **The "after" is not reliable at `Stop`.** The status line refreshes 300 ms after the last
  response (fact 4); the `Stop` hook runs before that. The final reading is taken at `close`
  (the next prompt or `SessionEnd`); at `flush` the delta is marked *provisional*.
- **A fresh session must not overwrite a good snapshot.** Before its first response its stdin
  has no `rate_limits` (fact 3) — after `/clear`, exactly when `/new-feature-implement`
  starts. The producer writes only when the input contains `"rate_limits"`.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | After a pipeline run, the person cannot tell what share of the plan it took; 0133's projection is an estimate from a self-reported budget | — |
| 2 — Trigger | Runtime events the audit already handles (`prompt`/`call` open, `flush`, `close`) | 1 · 2 · 4 · 5 |
| 7 — Mandatoriness | Information, never a block | axes 14–16 do not apply |
| 8 — Destination | Both: the reader travels in `ArchHook.java`; the producer is personal | — |
| 9 — Integration | `audit` render, `history.jsonl`, `summary`, `doctor`; `/audit-usage` renders only | a new mode (7c reuses `audit`) |
| Installation (user) | **A printed snippet.** The template lives in `audit-usage`; the docs show it; `/audit-usage` and `doctor` point at it when the snapshot is missing. Nothing writes `~/.claude` | an install mode writing `~/.claude/settings.json` · a `/statusline <instruction>` line (the agent's script varies per run) |
| Producers (user) | **Open contract.** The audit reads a documented shape — the status line stdin's `rate_limits` subset; any personal producer (e.g. a personal mod on `session.measure`) may write it. Nerviz documents only the status line | — |
| Calibration (user) | **Out for now.** No "tokens per pp" derived from runs; a later decision once valid deltas exist to measure the noise | — |
| Max age (user) | **15 minutes**, data in `extensions.json` | — |
| 17 — CI | `validate · hooks-cross-platform › AuditRenderTest` already renders a run in a throwaway project from the jar | — |
| 18–20 — Surface/State | A mod was weighed as the producer (fact 8): meta-repo only (0131), may not write a file (`mods.forbidden_calls`), terminal/Desktop only, whole-point events | Form 9 as the shipped producer |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Personal `statusLine` writes `~/.claude/rate-limits.json`; `audit` reads it at open and close and reports the delta (Form 7c change to `audit`; template in `audit-usage`) | 8 | **Approved** |
| 2 | A mod writes the snapshot on `session.measure` | 4 | Rejected — 0131 keeps mods out of the project; mods may not write files; 1-pp events |
| 3 | Manual capture: the person types the `/usage` numbers into an `audit mark` command before and after | 3 | Rejected — discipline, and the marks never line up with the run's boundaries |
| 4 | `statusLine` in `project-bootstrap/templates/settings.json.example` (0133's rejected path) | 2 | Rejected — overrides the status line of everyone who clones (fact 5) |
| 5 | Create nothing — keep 0133's projection | 3 | Rejected — the question asked has no measured answer |

### Option 1 — personal status line snapshot, delta in the audit (score 8)

**Motivator:** axis 1 and fact 2 — the only measured source is the status line's stdin, and a
personal one imposes nothing on a project.

**Mechanism.**

- **Producer (personal, never versioned in a project's settings).** A `statusLine` entry the
  person pastes into `~/.claude/settings.json`, from
  `.claude/skills/audit-usage/templates/statusline-rate-limits.json.example`:

  ```json
  {
    "statusLine": {
      "type": "command",
      "command": "f=\"$HOME/.claude/rate-limits.json\"; input=$(cat); case \"$input\" in *'\"rate_limits\"'*) printf '%s' \"$input\" > \"$f.tmp\" && mv \"$f.tmp\" \"$f\";; esac",
      "refreshInterval": 60
    }
  }
  ```

  POSIX `sh`, no `jq`. `tmp` + `mv` makes the write atomic on one filesystem. Writes only when
  the input carries `rate_limits` (consequence 2 above). `refreshInterval: 60` keeps the file's
  mtime fresh while any session is open. Someone with a status line of their own appends
  `; printf '%s' "$input" | <their command>` — the template says so. **Windows: not
  verified.** docs/en/statusline does not say which shell runs the command on native Windows
  (checked 2026-10-29); the snippet is POSIX `sh` and is documented as such — macOS, Linux,
  WSL. Where it does not run, the snapshot never appears and the report says
  `not captured — no snapshot at …`, never a wrong number.
- **Contract (open).** The file is the status line's stdin as received. The audit reads only
  `<root>.<window>.<used>` and `<root>.<window>.<resets_at>`; any producer writing that subset
  is supported.
- **`extensions.json` › `audit.plan_usage`** (invariant 10):

  ```json
  "plan_usage": {
    "snapshot": "~/.claude/rate-limits.json",
    "max_age_minutes": 15,
    "root": "rate_limits",
    "used": "used_percentage",
    "resets_at": "resets_at",
    "resolution_pp": 0.1,
    "not_available_env": ["CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY", "CLAUDE_CODE_USE_ANTHROPIC_AWS", "CLAUDE_CODE_USE_MANTLE", "ANTHROPIC_API_KEY"]
  }
  ```

  plus `rate_limits_key` on each existing `audit.windows` entry: `five_hour` → `"five_hour"`,
  `weekly` → `"seven_day"`. One window list, owned by `audit.windows`, read by both the 0133
  projection and this delta. The env names were verified on 2026-10-29 against
  docs/en/env-vars (the `CLAUDE_CODE_USE_*` provider rows and `ANTHROPIC_API_KEY`, which
  "is used instead of your Claude Pro, Max, Team, or Enterprise subscription even if you are
  logged in").
- **`audit` mode.** At run open (`auditPrompt` opening a run, `auditCall` opening one) append
  `plan_usage` `{"at": "before", ...}` to the run's `.state/<session>.ndjson`; at `flush`
  append `{"at": "provisional", ...}`; at `close` append `{"at": "after", ...}`. Each holds,
  per window, `used` and `resets_at`, plus the snapshot's mtime — **numbers only, never any
  other field of the file**. Root runs only; chained nodes overlap their parent.
- **Report** — `## 📉 Plan usage (account, measured)` beside the token aggregate: per window,
  before → after and the delta in pp. Cells:
  - `+3.5 pp` — both readings valid, same `resets_at`
  - `< 0.1 pp` — equal readings (resolution is `resolution_pp`)
  - `provisional` — rendered at `flush`, before `close`
  - `not valid — the window reset mid-run` — `resets_at` differs
  - `not captured — <reason>` — no file at `snapshot`; file older than `max_age_minutes`
    (names its age); unreadable JSON; window absent in the file
  - `not available — <var> is set` — no usable snapshot and a `not_available_env` variable is
    set in the hook's environment
  - one fixed line under the table: the delta is the account's — chat and other sessions in
    the same window count too; it is attributable to the run only if nothing else ran.
- **`history.jsonl`** — the closed run's row gains `plan_usage: {five_hour: <pp|null>, weekly:
  <pp|null>}` (null = not a valid delta). **`audit summary`** — per piece, the mean delta over
  runs with a valid one and `n`; `/audit-usage` renders it and never recomputes.
- **`doctor`** — a `Plan usage` line, only where `.claude/audit-usage/` exists: snapshot path,
  age, windows present; missing → points at the template. Never in `doctor.gate.labels`.
- **Redaction.** None needed: the file holds no credential (the status line's stdin carries
  model, paths, cost, rate limits — fact 2), and the audit copies two numbers per window out of
  it, never text. The file is never read into a report, a ledger or a prompt.

**Pros:** the first measured answer; no project imposes a status line; the producer is a few
lines in the person's own settings; the reader reuses `audit`'s events and 0133's window list.

**Cons:**
- Attribution: the delta includes chat, other sessions and other machines on the account.
- Resolution 0.1 pp: a short run on the weekly window reads `< 0.1 pp`.
- A fresh session left idle > 15 min before its first run reads `not captured` (its status line
  writes nothing until a first response; fact 3).
- "Zero shell" (`@CLAUDE.md` § Dependencies) holds for the repository; the snippet is `sh` in
  the person's settings, a documented exception.
- `CLAUDE_CONFIG_DIR` users must edit the path in both the snippet and nothing else — the
  audit reads `snapshot` literally; recorded in the template.
- Fact 3/4 are undocumented-as-stable runtime shapes; a renamed field blanks the section to
  `not captured — window absent`, never a wrong number.

**Points cut:** 5 (a data block, a report section, a `doctor` line, a template), 6 (no repo
precedent for a personal-settings producer) — 7.8 → 8.

**Visual.**

```
~/.claude/settings.json  statusLine (personal) ──writes──▶ ~/.claude/rate-limits.json
                                                                   │ reads (numbers only)
.claude/settings.json (project) ─ audit prompt|call ──▶ ArchHook.jar audit ─▶ .state/*.ndjson
                                 ─ audit flush|close ─┘          │
                                                                 ├─▶ report  ## 📉 Plan usage
                                                                 ├─▶ history.jsonl plan_usage
                                                                 └─▶ summary ─▶ /audit-usage (renders)
extensions.json  audit.plan_usage · audit.windows[].rate_limits_key
```

**CI:** `validate · hooks-cross-platform › AuditRenderTest`, new case `planUsage()`, spawning
the jar with `-Duser.home=<tmp>` so `~` resolves into the throwaway directory:

| Case | Expected | Defect that makes it fail |
|---|---|---|
| before 20.0 / 40.0, after 23.5 / 41.2, same `resets_at` | `+3.5 pp` (5 h), `+1.2 pp` (week); `history.jsonl` row holds both | `seven_day` read for both windows |
| `resets_at` differs on 5 h | 5 h `not valid — the window reset mid-run`, week still `+1.2 pp` | reset check removed |
| no snapshot file | `not captured — no snapshot at` | — (baseline) |
| snapshot mtime 20 min old | `not captured — snapshot 20 min old (max 15)` | age check removed |
| no file, `CLAUDE_CODE_USE_BEDROCK=1` in the env | `not available — CLAUDE_CODE_USE_BEDROCK is set` | env list ignored |
| equal readings | `< 0.1 pp` | equality printed as `+0.0 pp` |
| `flush` then `close` | `provisional` at flush, the delta at close | after read at `flush` |
| snapshot holds `"cwd": "/canary/path"` | the report never contains `/canary/path` | file text copied into the report |
| two runs of one piece, deltas 2.0 and 4.0 | `summary` shows mean `3.0 pp`, `n = 2` | mean over all runs, invalid ones as 0 |

`schema` covers `extensions.json` parsing and the `audit-usage` body sections; `build --verify`
the jar.

### Option 2 — a mod writes the snapshot (score 4)

Fact 8 gives a mod the same numbers, pushed. But nerviz mods stay in this repository (0131),
where the audit is off; `mods.forbidden_calls` forbids a file write, so the mod would have to
call `ArchHook.jar` to write it — a new write mode for a mod; mods load only in the terminal
and Desktop; `session.measure` fires on whole points. A **personal** mod outside nerviz can
still produce the file — option 1's open contract admits it without nerviz shipping one.
**Cut:** 2 (strains 0131 — capped), 5, 6, 9.

### Option 3 — manual capture (score 3)

`/usage` is interactive (fact 6): the person reads two numbers before and after and types them
into an `audit mark` command. Forgotten half the time, and the marks are not the run's open and
close. **Cut:** 1, 4, 5, 7.

### Option 4 — `statusLine` in the project template (score 2)

0133's rejected path: overrides the status line of whoever clones the project (fact 5) — a
project imposing personal configuration. **Cut:** 2 (invariant 9's spirit: the project ships
what the project needs, not the person's UI — capped), 3, 8.

### Option 5 — create nothing (score 3)

0133's projection stays an estimate from a self-reported budget; the measured question has no
answer. **Cut:** 1, 4, 5 (the question returns).

## References

| Claim | Source |
|---|---|
| Plan usage reaches a program only through the status line (or a mod) | docs/en/statusline § Available data; docs/en/hooks § Common input fields; CLI 2.1.312 `SessionRateLimit` |
| `rate_limits` absent before the first response, window dropped after reset | docs/en/statusline § Available data (notes) |
| Status line debounce 300 ms, `refreshInterval` | docs/en/statusline § How status lines work |
| Project `statusLine` overrides the person's | docs/en/statusline § Manually configure; `0133` fact 5 |
| Projection and window list already in `audit.windows` | `0133`, `0134` |
| Mods stay in this repository; may not write files | `0131`; `extensions.json` `mods.forbidden_calls` |
| `/audit-usage` renders, never recomputes | `.claude/skills/audit-usage/SKILL.md` |
| Boilerplate in the emitting skill's `templates/` | `@CLAUDE.md` invariant 3 |
| Lists and paths read by a mode live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| What travels is `export`; `audit-usage` is in `export.skills.include` | `@CLAUDE.md` invariant 9; `extensions.json` `export.skills.include` |

## Phase 4 handoff — verbatim texts

**`extensions.json`** — insert `"$comment_plan_usage"` and `"plan_usage"` (JSON above) into the
`audit` block right after `windows`/`plans`; add `"rate_limits_key": "five_hour"` to
`audit.windows.five_hour` and `"rate_limits_key": "seven_day"` to `audit.windows.weekly`.
Comment text:

> The account's real plan usage, measured around a run: the snapshot a personal status line
> (or any personal producer writing the same shape) leaves at `snapshot` — the status line's
> stdin as received. `audit` reads `<root>.<window rate_limits_key>.<used>` and `.<resets_at>`
> at run open, at `flush` (provisional) and at `close` (final: the status line refreshes 300 ms
> after the last response, after `Stop`), and copies those numbers only, never other text of
> the file — no redaction applies. Older than `max_age_minutes`, absent or unreadable →
> `not captured`; absent with a `not_available_env` variable set → `not available`; a changed
> `resets_at` → `not valid`. `~` is `user.home`. The producer never travels: its snippet is
> .claude/skills/audit-usage/templates/statusline-rate-limits.json.example, pasted by the
> person into ~/.claude/settings.json. Design: .claude/decisions/0194-audit-plan-usage-delta-from-a-personal-status-line-snapshot.md

**`ArchHook.java`** — every name, path, number above read from `audit.plan_usage` /
`audit.windows`, none hardcoded (invariant 10). Javadoc of the reading method, three sentences
(Form 7c's "why"):

> Reads the account's plan-usage windows from the snapshot a personal status line writes, at a
> run's open (`prompt`/`call`), `flush` and `close`. It lives in the `audit` mode because the
> run's boundaries are already its events and only the status line's stdin carries the numbers
> (0194; no hook input does). The rejected alternative — a `statusLine` in the project's
> settings — would override the status line of everyone who clones the project (0133 fact 5).

Event appended to the run log: `ev("plan_usage", "at", "before"|"provisional"|"after",
"mtime", <epoch ms|null>, "reason", <null|text>, "<window>_used", <n|null>,
"<window>_resets_at", <epoch s|null>)` per window id of `audit.windows`. The "before" is taken
only when the run opens (root), never for a nested node.

Report section, after the token aggregate and before 0133's `## 🪟 Plan windows`:

```
## 📉 Plan usage (account, measured)

| Window | Before | After | Δ |
|---|---|---|---|
| 5 h | 20.0 % | 23.5 % | +3.5 pp |
| week | 40.0 % | 41.2 % | +1.2 pp |

The delta is the account's: chat, other sessions and other machines in the same window count
too — it is this run's only if nothing else ran. Source: `~/.claude/rate-limits.json` (0194).
```

Cell strings exactly: `+X.Y pp` · `< 0.1 pp` (the number is `resolution_pp`) · `provisional` ·
`not valid — the window reset mid-run` · `not captured — no snapshot at <path>` ·
`not captured — snapshot <N> min old (max <M>)` · `not captured — unreadable snapshot` ·
`not captured — window absent in the snapshot` · `not available — <VAR> is set`. When a
reading is missing, Before/After show `—`.

`history.jsonl` row: `"plan_usage": {"five_hour": <pp|null>, "weekly": <pp|null>}` (keys = the
`audit.windows` ids). `audit summary`: a `### Plan usage (measured)` table before Health — Piece
· 5 h mean Δ · week mean Δ · n — over closed root runs with a non-null value; `—` and `n = 0`
otherwise.

`doctor`, only where the trail directory exists, never in `doctor.gate.labels`, label
`Plan usage`:
- `snapshot <path>, <N> min old, windows: five_hour, seven_day`
- `no snapshot at <path> — paste .claude/skills/audit-usage/templates/statusline-rate-limits.json.example into ~/.claude/settings.json`
- `snapshot <path> is <N> min old (max <M>) — is the status line installed?`

**`audit-usage/templates/statusline-rate-limits.json.example`** — the JSON above, with a
`"$comment"` key: what it does, that it goes into `~/.claude/settings.json` (never a project's),
how to chain an existing status line (`; printf '%s' "$input" | <your command>`), that the path
must equal `audit.plan_usage.snapshot` (edit both under `CLAUDE_CONFIG_DIR`), POSIX `sh` only,
and 0194.

**`audit-usage/SKILL.md`** — in § What the block already did, after **Plan windows**, a bullet
**Plan usage (measured)** — the account's 5-hour and weekly percentages before and after a run,
from the personal status line's snapshot; the block computed the delta and each cell's reason;
never recomputed. In § 2, after the plan-windows paragraph: if the plan usage says
`not captured — no snapshot`, say so and point at the template path and § Plan usage of
`docs/<lang>/08-audit-usage.md`; never write `~/.claude/settings.json`.

**Docs** `docs/pt-br/08-audit-usage.md` and `docs/en/08-audit-usage.md` — new subsection after
`### Por projeto: plan-limits.json` (`### Per project: …` in en): `### Uso do plano medido:
~/.claude/rate-limits.json` / `### Measured plan usage: ~/.claude/rate-limits.json` — the
snippet, the contract (any personal producer writing the subset), the cells, why the after is
taken at `close`, why a fresh session writes nothing, and 0194. Also the report anatomy list.
`docs/*/07-ci-validate.md`: the `AuditRenderTest` row names 0194's claims.

**`0133`** — under its 0134 note, a second note:

> **Real account usage — reopened by `0194-audit-plan-usage-delta-from-a-personal-status-line-snapshot.md`.**
> Fact 5 no longer excludes it: a status line in the person's own `~/.claude/settings.json`
> writes a snapshot the audit reads, and no project carries a `statusLine`. The projection
> above stands beside the measured delta.

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `audit`: `planUsageEvent` reads the snapshot at open (`openRun`), `flush` and `close` (`auditRender`); `planUsageReadings` / `planUsageSection` render `## 📉 Plan usage (account, measured)` after the token aggregate; `planUsageField` adds `plan_usage` to the `history.jsonl` row; `planUsageSummary` prints `### Plan usage (measured)` before Health; `planUsageDoctor` prints the `Plan usage` line — ⚪ no snapshot (optional), ⚠️ stale or unreadable, ✅ fresh, never `report(…, false, …)`, like `Platform`; the delta is compared to `resolution_pp` after rounding; `planWindows` also returns `rate_limits_key` |
| `.claude/hooks/ArchHook.jar` | Rebuilt under JDK 21 |
| `.claude/schemas/extensions.json` | `audit.plan_usage` and `$comment_plan_usage` (names the migration); `migrations.entries` › `audit-plan-usage-statusline` (amendment); `rate_limits_key` on `audit.windows.five_hour` (`five_hour`) and `weekly` (`seven_day`) |
| `.claude/skills/audit-usage/templates/statusline-rate-limits.json.example` | New — the producer snippet and its `$comment` |
| `.claude/skills/audit-usage/SKILL.md` | Vocabulary **Plan usage (measured)**; § 2 points at the template when `not captured — no snapshot` |
| `.claude/decisions/0133-audit-plan-window-projection.md` | Second note under the 0134 note: real account usage reopened by 0194 |
| `.claude/.ci/AuditRenderTest.java` | `planUsage()`: the nine cases, plus 6b (one resolution step, `23.3 → 23.4`, is `+0.1 pp`) and 6c (`doctor`: ⚪ without a snapshot, never ❌; windows listed with one); every spawn runs with `-Duser.home=<tmp>` and without the `not_available_env` variables |
| `docs/pt-br/08-audit-usage.md` · `docs/en/08-audit-usage.md` | New subsection `Uso do plano medido` / `Measured plan usage`; report anatomy lists the section; `doctor` paragraph names the `Plan usage` line |
| `docs/pt-br/07-ci-validate.md` · `docs/en/07-ci-validate.md` | `AuditRenderTest` row names 0194's claims |

## Amendment — a `migrations` entry (2026-10-29, approved by Lucas Fernandes)

A project generated before 0194 learns of the snippet only if someone runs `doctor` or
`/audit-usage`. `/arch-adopt` already prints each new `migrations` entry's `note` and `prompt` on
an update, so the entry `audit-plan-usage-statusline` (every blueprint the other entries list)
carries it: the prompt reads the template, merges it into `~/.claude/settings.json` — chaining an
existing `statusLine` — shows the block and writes only after the person confirms. Typed by the
person, behind Claude Code's own permission prompt: the "never write `~/.claude` without
confirmation" constraint holds. It strains the block's `$comment` ("code left behind"): nothing is
left behind here, the entry announces an optional personal setting. Accepted because no other
channel reaches a project on update; a project generated after the entry carries its id and is
pointed at the template by `doctor`'s `Plan usage ⚪` line and `/audit-usage` instead.

## Main-thread review

Two defects in the delegated diff, fixed before reporting: the doctor line used `report(…, false,
…)` — a ❌ on every machine without the personal status line, or on an API key, for an optional
file; and the `< 0.1 pp` test compared the unrounded difference, so one step of the resolution
(`23.3 → 23.4`) read as below it. Both now carry a test case.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › audit renders where the run spent, redacts tool errors, skips what class or project turns off` | Each window reads its own key (`+3.5 pp` 5 h, `+1.2 pp` week, both in the `history.jsonl` row); a changed `resets_at` is `not valid` for that window only; no file is `not captured — no snapshot at`; a 20-minute-old snapshot is `not captured — snapshot 20 min old (max 15)`; `CLAUDE_CODE_USE_BEDROCK` with no file is `not available`; equal readings are `< 0.1 pp`, never `+0.0 pp`; `provisional` at `flush` and the delta from the reading taken at `close`; no text of the snapshot (`/canary/path`) reaches the report or the ledger; `audit summary` shows mean `3.0 pp`, `n = 2` over two valid deltas and one run whose windows both reset | Green on the tree, 95 checks. Red with `seven_day` read for both windows: 6 checks fail by name, the first "plan usage: 5 h delta from five_hour". Red with the `resets_at` comparison removed: 3 fail (the reset cell, its history null, the summary mean). Red with the age check removed: 2 fail (the stale cell and "gives no delta"). Main-thread review added 6b and 6c: green, 99 checks; red with the comparison back on the unrounded difference — 6b fails by name (`23.4 − 23.3` is `0.0999…`). Source and jar restored; `build --verify` green |
| `validate › schema` (step `frontmatter schema`) | `extensions.json` parses with the new keys; the `audit-usage` body keeps its class sections | Green |
