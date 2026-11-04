# 0198 · A spike runs in its own git worktree with only the audit hooks on, never in the main checkout

- **Date:** 2026-11-04
- **Scenario:** Issue #196, triaged at 598b5e8 — a generated project has no documented way to run throwaway spike code; `format`, `check`, `tests` and `compose gate` run unconditionally and `ArchHook.java` has no switch to relax them.
- **Decision:** Form 2 — `.claude/skills/spike/SKILL.md` (class `ops`, travels) + Form 7c — mode `spike` in `.claude/hooks/ArchHook.java`, reading `spike.keep_modes` from `.claude/schemas/extensions.json`
- **State:** approved by the maintainer, on 2026-11-04 — option 1, amended: keep the `audit` hooks on inside the spike, so its cost is traced

## Reproduced on disk

From the `issue-verifier` table of `/triage-issue 196` at 598b5e8 (confirmed rows only):

| Claim | Result |
|---|---|
| No skill, rule or CLAUDE.md section covers a spike or POC | true |
| `format` runs on every `Edit(**/*.java)` | true, never blocks (cost is time) |
| `check` blocks a forbidden import at edit time | true; PostToolUse, exit 2 |
| `tests` runs on `Stop`, 600 s timeout | true, only when a `.java` file differs from HEAD |
| An ad-hoc compose service fails the turn | true; `config set compose-gate exited warn` relieves stopped services only |
| `getenv` gates no enforcement mode | true |
| The only escape is editing versioned `settings.json` | false — `disableAllHooks` in untracked `.claude/settings.local.json` |
| A marker file switching modes off would be protected | false — `guard write`/`guard bash` on `.claude/poc.on` exit 0 with no phase open |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Observed by the maintainer in a real generated project | create nothing as a non-answer |
| 3 — frequency | Weekly | Form 5 alone (a weekly multi-step procedure is a skill, matrix § 3) |
| 7 — what stays on | Only `audit`, so the spike's token cost is traced; everything else off | `disableAllHooks` (turns `audit` off too); option 3's selective relaxation inside the main tree |
| 8 — destination | Generated project (the symptom lives there) | meta-repo-only forms |
| 9 — integration | `guard bash` drops paths outside `ROOT` (`addBashTarget`); hooks add up across settings layers, so `settings.local.json` cannot remove one entry; `project_config` `base: audit` already resolves to the main checkout's trail inside a worktree (0187) | a per-hook override in `settings.local.json` |
| 17 — CI | New `SpikeTest.java`, below | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `/spike` skill (Form 2, travels) — worktree, hooks off there | 8 | **Approved**, amended to keep `audit` (adds Form 7c `spike`) |
| 2 | Document worktree + `disableAllHooks` in `root.CLAUDE.md.example` and pitfalls (Form 5) | 7 | Rejected — a weekly three-step procedure left to prose, and `disableAllHooks` would turn `audit` off |
| 3 | `spike` entry in `project_config`, honored only in a linked worktree (Form 7c + data) | 6 | Rejected — early exit in four modes for a guarantee (`guard` in a throwaway tree) nobody asked for |
| 4 | Marker `.claude/poc.on` read by the hooks (issue's proposal) | 3 | Rejected — the switch is unprotected: `guard` admits the write with no phase open; invariant 6, anti-pattern 22 shape |

### Option 1 — `/spike` + mode `spike` (score 8)

**Motivator:** axis 3 — a weekly multi-step procedure; axis 7 — only `audit` stays on.

**Pros:** the main checkout never relaxes. The worktree's `settings.json` is marked `skip-worktree` in that worktree's index only (verified with git 2.50.1: `git add -A` + commit in the worktree left it out; `git ls-files -v` in the main checkout still shows `H`), so the relaxed registrations cannot be committed or merged. The audit trail of a worktree lands in the main checkout's `.claude/audit-usage/` (0187 `base: audit`), so `/audit-usage` there shows the spike's spend. CI (`doctor gate`, tests) still gates anything promoted back, which goes through `/new-feature` in the main checkout anyway.

**Cons:** inside the spike `guard`, `schema`, `check`, `format`, `tests`, `compose gate` are all off, by design. One more mode and one more skill. First piece whose effect is a sibling directory of the project.

**Points cut in the rubric:** 4 (enforcement) → no guarantee but the trail inside the spike, by design. 6 (precedent) → first skill whose effect is a sibling worktree.

**CI:** `validate` › `schema` covers the skill's frontmatter, class, sections and export entry. New `.claude/.ci/SpikeTest.java` in `validate` › `hooks-cross-platform`, cases below.

### Option 2 — documentation only (score 7)

Zero pieces, but the person repeats three steps weekly, a model following prose can write `disableAllHooks` into the main checkout, and `disableAllHooks` turns `audit` off — the amendment rules it out.

### Option 3 — `project_config` `spike` + linked-worktree condition (score 6)

`.claude/spike.json` `{"relax": [...]}` written only through `config set`, honored only when `ROOT` is a linked worktree. Keeps `guard` and `schema` on. Four modes gain an early exit and four test paths, for a guarantee inside a tree that is thrown away.

## Design — verbatim for Phase 4

### Data — `.claude/schemas/extensions.json`, new top-level block

```json
"spike": {
  "$comment": "What `ArchHook.java spike <worktree>` keeps in a spike worktree's `.claude/settings.json`: every hook entry whose ArchHook mode (the argument right after `ArchHook.jar`) is listed in `keep_modes`; every other entry is dropped, an emptied matcher group and an emptied event with it. Everything outside `hooks` is left as is. `audit` stays so a spike's token spend lands in the main checkout's trail. Design: .claude/decisions/0198-spike-worktree-with-hooks-off.md",
  "keep_modes": ["audit"]
}
```

If `schema` validates the top-level keys of `extensions.json` against a list, add `spike` there.

### Mode — `ArchHook.java spike <worktree>`

- Dispatch: `case "spike" -> spike(args)` in the mode switch; no stdin.
- Reads `keep_modes` from `extensions.json` (`get(sch, "spike", "keep_modes")`) — never a constant (invariant 10).
- Refuses with exit 2, writing nothing, when: no `<worktree>` argument; `<worktree>` is not a directory; `git -C <worktree> rev-parse --git-dir` equals `git -C <worktree> rev-parse --git-common-dir` once both are resolved to absolute paths (not a linked worktree — this is what protects the main checkout); `<worktree>` resolves to `ROOT`; `<worktree>/.claude/settings.json` is missing. Each stderr message names the rule and the way out, e.g. `spike: <path> is the main checkout, not a linked worktree. Create one with \`git worktree add ../<project>-spike-<slug> -b spike/<slug>\` and run \`spike\` on that path.`
- Filters `<worktree>/.claude/settings.json`: for each event in `hooks`, for each matcher group, keep the entries of `hooks` whose `args` contain an element ending in `ArchHook.jar` (or `ArchHook.java`) followed by an element in `keep_modes`; drop a group left with no entries, and an event left with no groups. Keys other than `hooks` are untouched. Written with the same JSON writer `config set` uses, two-space indent, trailing newline.
- Runs `git -C <worktree> update-index --skip-worktree .claude/settings.json`; a non-zero exit is exit 2 with git's first stderr line.
- Idempotent: a second run on the same worktree writes the same bytes and exits 0.
- Prints one line on stdout: `spike: <worktree> — kept <k> hook entries (<modes>), dropped <d>; .claude/settings.json marked skip-worktree.`
- Javadoc of `spike(...)`, the three sentences: *Form 7c, invoked by hand from the `spike` skill, not by any event: it writes a spike worktree's hook registrations down to `spike.keep_modes`, so a throwaway tree keeps its audit trail and loses every other check. Motivated by axis 7 of 0198 — only `audit` must stay on, which `disableAllHooks` cannot express since it turns every layer off. Option 3 of 0198, a relax flag read by four modes, was rejected: it adds an early exit to every enforcing mode to protect a tree that is thrown away.*

### Skill — `.claude/skills/spike/SKILL.md`

Frontmatter:

```yaml
name: spike
description: >
  Opens a throwaway spike of this project in its own git worktree, beside the project
  directory, with every hook off there except the audit trail — no format, check, tests,
  compose gate or guard — so exploratory code can be written fast while the main checkout
  stays fully enforced and the spike's token spend still shows in /audit-usage.
  Explicit invocation only.
argument-hint: "<slug> | end <slug>"
disable-model-invocation: true
allowed-tools: AskUserQuestion, Bash(git worktree:*), Bash(git rev-parse:*), Bash(git branch:*), Bash(java:*)
model: sonnet
effort: low
```

Body sections, in order: title + one-paragraph intro; `## Why this is a skill (Form 2) and not an agent`; `## Contract`; `## Procedure`; `## Failure modes`.

`## Why this is a skill (Form 2) and not an agent` — three sentences: *Form 2 because the trigger is the person deciding to explore (axis 2) and its effect — a worktree with enforcement off — must never start on the model's own decision. It is not an agent: none of the three reasons applies, the output is two lines and the work is three commands. Not `disableAllHooks` in a `settings.local.json`, which turns the audit trail off with everything else (0198, option 2).*

`## Contract`:
- **Class:** ops — `skill_classes.ops` in `@.claude/schemas/extensions.json`: writes no file of this project with a tool; its effect is a sibling worktree, made by `git worktree add` and `ArchHook.jar spike`.
- **Reads** the project root (`git rev-parse --show-toplevel`) and the argument.
- **Writes** nothing inside the project. Outside it: the worktree `../<project>-spike-<slug>` on branch `spike/<slug>`, whose `.claude/settings.json` `ArchHook.jar spike` reduces to the `spike.keep_modes` entries and marks `skip-worktree`.
- **Does not** merge, commit or push the spike, nor delete it — `end` prints the commands. Promoting spike code is a `/new-feature` run in the main checkout.
- Design: `@.claude/decisions/0198-spike-worktree-with-hooks-off.md`.

`## Procedure`:
1. **Preconditions.** Run from the main checkout: `git rev-parse --git-dir` equals `git rev-parse --git-common-dir`; otherwise stop (failure mode 1). The argument is a slug matching `[a-z0-9][a-z0-9-]*`; missing → ask once with `AskUserQuestion`, then stop.
2. **`end <slug>`** — print, do not run: `git worktree remove ../<project>-spike-<slug>` and `git branch -D spike/<slug>`, plus one line: the spike's spend is already in `/audit-usage`. Stop.
3. **Create.** `git worktree add ../<project>-spike-<slug> -b spike/<slug>`, where `<project>` is the basename of the root. Exists already → failure mode 2.
4. **Relax.** `java -jar .claude/hooks/ArchHook.jar spike ../<project>-spike-<slug>` and relay its stdout line verbatim. Non-zero → failure mode 3.
5. **Report**, then stop:
   ```
   ✅ Spike <slug> — ../<project>-spike-<slug> on spike/<slug>
   Hooks ....... audit only (format, check, tests, compose gate, guard off there)
   Next ........ open `claude` in that directory; this session stays enforced
   End ......... /spike end <slug>
   ```

`## Failure modes`:
1. Not the main checkout: `❌ /spike runs from the main checkout; this is a linked worktree. Open the project root and run it there.`
2. Worktree or branch exists: `❌ ../<project>-spike-<slug> or spike/<slug> already exists. Pick another slug, or /spike end <slug> first.`
3. `spike` mode refused: `❌ ArchHook spike refused: <its stderr first line>. The worktree exists with every hook on — remove it with git worktree remove ../<project>-spike-<slug>.`

## Test cases — `.claude/.ci/SpikeTest.java`

Single-file Java program like its neighbours (`java`, `git` only). Builds a throwaway git project in a temp dir with a copy of the real `.claude/schemas/extensions.json` and a `.claude/settings.json` holding three entries under `ArchHook.jar`: `audit prompt` (UserPromptSubmit), `guard write` (PreToolUse, matcher `Write|Edit`), `check` (PostToolUse, alone in its group), plus a `permissions` block. Commits it, `git worktree add ../w -b spike/t`, then spawns `java -jar <repo>/.claude/hooks/ArchHook.jar spike <w>` with `CLAUDE_PROJECT_DIR=<project>`.

| # | Case | Defect that makes it fail |
|---|---|---|
| 1 | Worktree `settings.json` keeps the `audit` entry, has no `guard`/`check` entry, no `PostToolUse` event, and the `permissions` block unchanged | Filter keeps every entry, or drops `permissions` |
| 2 | Main checkout `settings.json` byte-identical before and after | Mode writes under `ROOT` instead of `<worktree>` |
| 3 | In the worktree, `git ls-files -v .claude/settings.json` starts with `S`, `git status --porcelain` is empty, and `git add -A && git commit` leaves the file out | `update-index --skip-worktree` call removed |
| 4 | `spike <project>` (the main checkout) exits 2, stderr contains `not a linked worktree`, file unchanged | Linked-worktree check removed |
| 5 | With the copied `extensions.json` set to `keep_modes: ["audit","guard"]`, the `guard` entry survives | `"audit"` hardcoded in the Java |
| 6 | Second run exits 0 with the same bytes | Non-idempotent rewrite (e.g. drops everything once nothing matches the jar pattern twice) |

## References

| Claim | Source |
|---|---|
| `disableAllHooks: true` turns every hook off | `@claude-help.md` § hooks (line ~899) |
| Hooks from every settings layer add up | `@claude-help.md` § hooks |
| `settings.local.json` is per-checkout and gitignored | `@claude-help.md` settings table (line ~978) |
| `guard bash` drops targets outside `ROOT` | `.claude/hooks/ArchHook.java` `addBashTarget` |
| A worktree's audit trail is the main checkout's | `project_config.$comment` in `.claude/schemas/extensions.json`; `0187` |
| `skip-worktree` keeps the file out of commits, per worktree index | verified 2026-11-04, git 2.50.1, scratch repo |
| Rule that must always hold is a hook, not prose | `@CLAUDE.md` invariant 6 |
| Lists read from `extensions.json`, never constants | `@CLAUDE.md` invariant 10 |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/spike/SKILL.md` | new — the skill above |
| `.claude/schemas/extensions.json` | `spike` block; `spike` in `skill_classes.ops.skills`; `spike` in `export.skills.include`; ops `$comment` gains one sentence naming `spike` and 0198 |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | mode `spike`; jar rebuilt with `build` |
| `CLAUDE.md` | § Commands row for `spike <worktree>`; routing row for `/spike` |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | routing row: a throwaway spike → `/spike <slug>` |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | part 2 entry: in a spike worktree `.claude/settings.json` differs from `HEAD` yet `git status` is clean (`skip-worktree`), and only the audit hooks run there |
| `.claude/.ci/SpikeTest.java` | new — cases above |
| `.github/workflows/validate.yml` | step in `hooks-cross-platform` running `SpikeTest.java` |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | row for the new step |
| `README.md` | skill count / list, if it carries one |

Goes to the generated project: **yes** — the skill via `export.skills.include`, the mode and data via the whole `ArchHook.java`/`.jar`/`extensions.json` copy.

## CI coverage

| Workflow · job › step | What it proves | Runs |
|---|---|---|
| `validate` · `hooks-cross-platform` › `spike keeps only the hooks keep_modes lists, in a linked worktree, uncommittable` | `SpikeTest.java`, the six cases above, against the committed `ArchHook.jar` | Green locally 2026-11-04: cases 1-6 pass. Red, defect injected and jar rebuilt: no linked-worktree check (`linked = true`) fails case 4 only; `"audit"` hardcoded in place of `keep_modes` fails case 5 only. Source restored, jar rebuilt, `build --verify` and the test green again |

`schema` (the `validate` job that covers the skill's frontmatter, class, sections and export entry) green with the `spike` block and the skill in place.
