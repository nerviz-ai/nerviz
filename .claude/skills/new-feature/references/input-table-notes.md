# Input table notes — `/new-feature`

Prose and error shapes of the input table of `SKILL.md` § guardrail, moved out of the body by
`@.claude/decisions/0196-new-feature-body-cut-quoted-globs-and-audit-effort.md`, unchanged. Read
it whole when the argument lands on row 3 or row 5, or on any ❌ row, before printing the error.

Row 3 used to implement on the same argument; it is an error row since the implement flow
became its own skill, and it points there instead of guessing
(`@.claude/decisions/0135-new-feature-split-spec-and-implement.md`). A user typing the old
command loses one line, not a run.

Row 5 is the near miss, and it is an **error row, not a success one**: it reports and stops.
`/new-feature UC-003-spec` once answered `use case not found` while
`docs/use-cases/UC-003-initiate-kyc-verification/` sat on disk — literally true, since no folder
carries that name, and practically wrong, since the argument was the basename of the spec file
inside that folder (lessons-learned-014 § 12). The answer names the real slug and the status, so
the next attempt is one line away. It does **not** resolve the argument and carry on: the
argument was wrong, and guessing which case was meant is exactly what the guardrail forbids.

```text
❌ /new-feature: <reason>.
Usage: /new-feature <feature description>   → new use case
       /new-feature UC-NNN-slug              → resume a draft case
       /new-feature                          → list
       /new-feature-implement UC-NNN-slug    → implement an approved case
```

Row 5 keeps that shape and adds the one line that makes it actionable:

```text
❌ /new-feature: UC-003 is UC-003-initiate-kyc-verification, status approved.
Did you mean: /new-feature-implement UC-003-initiate-kyc-verification
Usage: …
```
