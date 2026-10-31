# Entry guardrail — shared by `/new-feature` and `/new-feature-implement`

Runs before any skill invocation, any write, and any question, in both skills. Every check is
a command; the model doesn't interpret the input. Each skill keeps **its own** closed input
table — what each row does is that skill's procedure — and reads everything else from here.
One owning file, cited by path from both bodies (`@CLAUDE.md` invariant 2;
`@.claude/decisions/0135-new-feature-split-spec-and-implement.md`).

## Contents

- 1 · Survey and classify
- 2 · Worktree — decided here, never later
- 3 · Project
- 4 · Disk

## 1 · Survey and classify

Survey the use cases once:

```bash
find docs/use-cases -mindepth 1 -maxdepth 1 -type d -name 'UC-*' 2>/dev/null | sort | while read -r d; do s=$(find "$d" -maxdepth 1 -name 'UC-*-spec.md' -exec grep -m1 '^status:' {} \;); echo "$d ${s:-status: (no spec)}"; done
```

Classify the argument with the argument in single quotes:

```bash
printf '%s' '<argument>' | grep -Eqx 'UC-[0-9]{3}-[a-z0-9]+(-[a-z0-9]+)*' && echo EXACT
printf '%s' '<argument>' | grep -Eiq 'uc-[0-9]' && echo UC_LIKE
test -d 'docs/use-cases/<argument>' && echo FOLDER

# The number the argument carries, and the folders it resolves to. RESOLVES_ONE when the
# argument names no folder of its own but its UC number has exactly one on disk.
N=$(printf '%s' '<argument>' | grep -Eio 'uc-[0-9]{3}' | head -1 | tr 'a-z' 'A-Z')
test -n "$N" && ls -d "docs/use-cases/$N"-*/ 2>/dev/null
```

Then evaluate the calling skill's input table in order and **stop at the first row that
matches**. Neither table has an "any other text" row.

**The model doesn't interpret the input.** It doesn't fix a slug, separate a slug from
context, or infer intent. A text that almost matches a row doesn't match it. A near-miss row
is not an exception: it reports what it found, it does not act on it.

**An error has a fixed shape and ends the run. After it: no side effect** — no skill call, no
write, no question. Reading is still allowed, and the near-miss rows are why: the survey is
`ls` and `grep`, it changes nothing, and it is the cheapest way for a near miss to correct
itself. The rule used to say "no output", which a run broke by printing the survey after the
error — the output the user actually needed. A rule violated because it is slightly wrong is
a rule to fix, not to repeat.

## 2 · Worktree — decided here, never later

```bash
git rev-parse --show-toplevel
git rev-parse --git-dir --git-common-dir
```

Both fail → not a git repository yet: no worktree, paths are relative to the project
root. Different `--git-dir` and `--git-common-dir` → the session is inside a worktree. Every
path this run writes is under `--show-toplevel`, and nothing is written in the main
checkout. If the work should be isolated in a worktree and isn't yet, that is decided
**now**, before any question or write. Never enter or leave a worktree mid-flow — edits
get refused outside it and orphan folders appear in the main checkout.

**Third case: the project root itself is gitignored by a parent repository.**

```bash
git check-ignore -q . && echo IGNORED
```

Happens when this project lives inside another repo's ignored path (e.g. a demo under
this meta-repo's own `examples/`, which is 100% gitignored). The pipeline runs normally
— nothing above depends on the root being tracked — but flag it here, once, so the
run's own state carries the fact forward instead of `git-publish` discovering a `git
status` that doesn't match the feature just implemented (lessons-learned-006 § 8:
`src/`, `docs/use-cases/` all ignored, only an unrelated dirty file showed up in
`status`). `IGNORED` → note it in the run's context and print it in the `/git-publish` line
at the end of the run (` — pre-existing: IGNORED`) so `git-publish`'s own state check
(`@.claude/skills/git-publish/SKILL.md` step 1) knows to warn instead of assuming the diff
matches the feature.

**Fourth case: the worktree already carries changes from before this run.**

```bash
git status --porcelain 2>/dev/null
```

Non-empty → work that is **not** this run's. Report it here, in full (path count and what
the paths are, staged and unstaged alike), and carry **the list itself** into the
`/git-publish` line the run prints, the same way `IGNORED` travels (` — pre-existing: <paths>`,
collapsed to directories past 20 paths) — not just the fact that something was dirty.

**`/new-feature-implement` leaves out the run's own input:** the approved case's folder
`docs/use-cases/UC-NNN-<slug>/` and `docs/use-cases/BACKLOG.md`. A spec nobody committed yet is
what the run implements and goes in its `feat(UC-NNN-<slug>)` commit; listing it as pre-existing
would split it into a `chore: commit changes from before this run` commit with the wrong
label (`@.claude/decisions/0182-git-publish-printed-not-chained-by-the-feature-flows.md`).

`git status --porcelain`, never `git diff --cached`: the index is half the picture, and the
half that was already known. An unstaged edit and an untracked file from a previous run are
invisible to the index and are swept into the commit by `git add -A` all the same — a modified
`.claude/audit-usage/history.jsonl` and one untracked report rode along exactly that way, while
the guardrail reported a clean start (lessons-learned-014 § 11). Harmless there, since
`git-publish` commits the audit trail with the run on purpose; the shape is not.

**`.claude/audit-usage/**` is never pre-existing work** — leave those paths out of the list.
The previous run's report and its `history.jsonl` line are written at `Stop` and on the next
prompt, both after that run's commit, so every run starts with them dirty by construction;
`git-publish` stages the directory with the run's commit anyway (lessons-learned-016 § 8) — and
since the commit is typed after `/clear`, that directory already holds the whole run.
Reporting them would make every start look dirty and teach the reader to ignore the report.

This is the cheap moment to decide: at the end of the flow the run's own deliverable is mixed
into the same worktree, and separating them costs a reset nobody planned. lessons-learned-012
§ 7: 1.503 staged deletions from before the run only surfaced at `git-publish`, and the
handling was improvised because no state in either skill described it.

## 3 · Project

Checks the root build file (exists + parseable) — `pom.xml` on Maven, `build.gradle.kts` or
`build.gradle` on Gradle; the wrapper beside it (`mvnw` / `gradlew`) says which —
`.claude/forbidden-imports.txt`, and that a domain package exists. **Discover it, don't
assume it.**

```bash
find src/main/java -type d -name domain
```

`src/domain/` and `adapter/` don't exist in any blueprint: they're Maven module paths,
and even in multi-module blueprints the layer lives at `<module>/src/main/java/…`. A
guardrail that checks a literal path either fails on a valid project or passes by
accident — neither case guards anything. The package name comes from the active
blueprint's `packages.map`; where the blueprint isn't at hand, it comes from the `find`
above.

## 4 · Disk

Available space > 100MB, write permission on `docs/use-cases/`.

Without ✅ validation, anti-pattern 9 (invoking against an invalid project).
