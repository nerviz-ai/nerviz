# 0164 · GENESIS Started also anchors on the Skill tool path

- **Date:** 2026-10-15
- **Scenario:** issue #146, triaged at `983c6c8`: `audit genesis` anchors Started only on a user
  entry holding the literal `<command-name>/init-project</command-name>`, so a session that
  enters `init-project` through the Skill tool or an untagged typed line exits 1 and leaves
  `GENESIS.md`'s placeholders unfilled.
- **Decision:** Form 7c, no new mode — `audit genesis` in `.claude/hooks/ArchHook.java` reads two anchor forms
  (option A). No registration, no template, no new data.
- **State:** approved by the maintainer (lucas.rj.fernandes), on 2026-10-15

Supersedes the Started definition of `0123` § C1 ("the last user entry carrying the
`/init-project` command"). The rest of § C1 stands. `0123` is not rewritten
(`.claude/decisions/README.md`).

## Reproduced on disk before classifying

Rows the `issue-verifier` confirmed at `983c6c8`. Refuted and unproven rows, and the issue's
proposed fix, are not inputs.

| # | Claim | Evidence |
|---|---|---|
| 1 | `audit genesis` sets Started only from a `type: user` entry whose content is a String holding the tag | `.claude/hooks/ArchHook.java:6569-6584`. The command name comes from `audit.genesis.command`, `.claude/schemas/extensions.json:236` |
| 2 | With no such entry it exits 1 before writing, and the three placeholders stay literal | `ArchHook.java:6582` exits before `Files.writeString` at `:6608` |
| 3 | `init-project` step 5 promises the fill "since the `/init-project` message" and says nothing of how the skill was entered | `.claude/skills/init-project/SKILL.md:96-98` |
| 4 | `0123` § C1 defined Started that way, and its test covers a typed command only | `.claude/decisions/0123-lessons-learned-020-init-project-run.md:133-134`, `.claude/.ci/GenesisTest.java` |
| 9 | Not fixed on the reported release | `HEAD` = `983c6c8` = v0.38.1 |

One more fact, from a transcript on the maintainer's machine (Claude Code 2.1.267, another
project), checked before the interview. A person typed a line that is no slash command. The
model then called `Skill(script-generator)`. Neither entry carries a `<command-name>` tag.
What the runtime wrote instead:

- the `tool_result` of that call is a `type: user` entry with list content and a top-level
  `"toolUseResult": {"success": true, "commandName": "script-generator"}`. The runtime writes
  that field. It quotes no file, so it cannot carry the echo the String-content guard exists for;
- that `tool_result` and the person's prompt that caused the call share one `promptId`. The
  prompt is a `type: user` entry with String content.

The audit trail already treats `Skill(name)` and `/name` as two origins of one skill run
(`0038`, `ArchHook.java:5376`). `audit genesis` is the only reader of the transcript that knows
one of them.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | Confirmed rows 1-4: exit 1, placeholders left, on any entry path without the tag | create nothing (anti-pattern 9 does not apply) |
| 7 — mandatoriness | The figures come from a deterministic reader, never the model (`0123`, invariants 6 and 8) | 1 · 2 · 4 · 5 as the fix |
| 16 — existing mode | `audit genesis` already owns the read; it needs a second anchor, not a new mode or a registration | 7a · 7b · a new 7c mode |
| 8 — destination | `ArchHook.java` and `extensions.json` travel whole through `export`; no template changes | — |
| Skill-path anchor (maintainer) | Started = the prompt that shares the `tool_result`'s `promptId`; the `tool_result`'s own timestamp when no `promptId` is found | anchoring on the `tool_result` alone |
| Untagged typed line (maintainer) | Not an anchor. The observed shape (an unparsed line, then `Skill`) already reaches the Skill path, and claim 5 of the issue is unproven | a text match on `^\s*/init-project` |
| Retry in one session (maintainer) | The last anchor of either form wins, as in `0123` | first match |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | `audit genesis` reads two anchor forms: the tag, and the Skill `tool_result` traced to its prompt | 9 | **Approved** |
| B | A plus `permissions.deny: Skill(init-project)` | 5 | Rejected — a guarantee against an unproven claim (anti-pattern 16) |
| C | `permissions.deny: Skill(init-project)` alone | 4 | Rejected — refuses the path instead of measuring it |
| D | No anchor → Started = the session's first entry | 3 | Rejected — writes figures nobody measured |
| E | Create nothing; tell the person, in `init-project`, to type `/init-project` as the prompt's first characters | 3 | Rejected — prose against a choice the model makes (anti-pattern 2) |

### A — two anchor forms in `audit genesis` (score 9)

**Motivator:** axis 16. The owner of the read is `audit genesis`. The fix belongs in it, not in
the caller and not in the model.

**Change:**
- Each transcript line can supply a Started candidate in two ways. The latest candidate across
  both wins, as before.
  - **Typed:** unchanged. A `type: user` entry whose `message.content` is a String containing
    `<command-name>/<command></command-name>`.
  - **Skill tool:** a `type: user` entry whose `toolUseResult.commandName` equals `<command>`.
    The candidate is the timestamp of the earliest `type: user` String-content entry with the
    same `promptId`. When the entry has no `promptId`, or no prompt carries it, the candidate is
    the `tool_result`'s own timestamp.
- `<command>` stays the single datum, `audit.genesis.command` (invariant 10). The transcript's
  field names (`toolUseResult.commandName`, `promptId`) stay in the Java beside the ones every
  transcript reader there already uses (`type`, `message.content`, `timestamp`). They describe
  the runtime's file format, not a list this repository chooses.
- The exit-1 message names both anchors and the way out: start `init-project` by typing
  `/init-project` as the prompt's first characters.
- The Javadoc of `auditGenesis` gains the three `Why` sentences.

**Pros:** fixes the confirmed symptom in its owner. The `tool_result` field is written by the
runtime and quotes no file, so the echo guard keeps its meaning. Started stays what the person
typed on both paths, so the turn where the model decided to call `Skill` is counted. Same
two-origin shape as the audit trail (`0038`). No new piece, no registration, no new data.

**Cons:** reads two runtime fields no documentation names. If a later CLI drops `promptId`,
Started falls back to the `tool_result`, seconds late. If it drops `commandName`, the Skill
path exits 1 again, by name, as today. Changes `0123` § C1, hence this record.

**Points cut in the rubric:** criterion 5 → two undocumented runtime fields to keep an eye on.

**CI:** `validate` · `hooks-cross-platform` › `audit genesis fills GENESIS from the
transcripts, once` (`GenesisTest.java`) gains these cases:
- Skill path with a `promptId`: Started is the prompt's timestamp;
- Skill path without one: Started is the `tool_result`'s timestamp;
- a `tool_result` whose `commandName` names another skill: no anchor;
- a typed run, then a later Skill run: the later one wins.
The existing cases stay. Proven green, then red with the Skill branch removed.

### B — A plus `permissions.deny: Skill(init-project)` (score 5)

`init-project` already declares `disable-model-invocation: true`. Whether the runtime still
lets `Skill` reach it is claim 8 of the issue, unproven. If the runtime honours the field, the
line is redundant. If it does not, the line refuses the path the person was on, and they
retype. That is a guarantee bought against an unobserved failure (anti-pattern 16), and a
Form 8 change in every session for a once-per-project ritual. Cut: criteria 4, 9.

### C — `permissions.deny: Skill(init-project)` alone (score 4)

Refuses the Skill path instead of measuring it. It leaves GENESIS unfilled whenever the
model follows the SKILL.md by reading it. It also rests on the same unproven claim 8. Cut:
criteria 1, 4, 9.

### D — fall back to the session's first entry (score 3)

It writes figures that are not the run's, with no error: turns from before `/init-project` would
be counted, which is the defect `0123` was written against. An exit 1 that names the cause
is the honest answer. Cut: criteria 1, 2 (invariant 8's spirit: a value written that nobody
measured), 4.

### E — create nothing, prose in `init-project` (score 3)

The model chooses the Skill path. A sentence asking the person to type the command never
reaches that choice. That is anti-pattern 2. Cut: criteria 1, 4.

## References

| Claim | Source |
|---|---|
| A skill can be denied to the `Skill` tool by name | `@claude-help.md` § skills troubleshooting (`Skill(commit)`) |
| `Skill(name)` and `/name` are two origins of one run | `.claude/decisions/0038-audit-trail-every-skill-and-agent.md`, `ArchHook.java:5376` |
| Started's current definition | `.claude/decisions/0123-lessons-learned-020-init-project-run.md` § C1 |
| Lists a mode reads live in `extensions.json` | `@CLAUDE.md` invariant 10 |
| A record is superseded, not rewritten | `.claude/decisions/README.md` |
| Transcript shape of the Skill path | maintainer's local transcript, CLI 2.1.267, quoted above |

## Propagation

| File | Change |
|---|---|
| `.claude/hooks/ArchHook.java` | `auditGenesis` reads the Skill tool anchor, new `skillPromptStart`, Javadoc with the three `Why` sentences, exit-1 message naming both anchors |
| `.claude/hooks/ArchHook.jar` | rebuilt, committed with the source |
| `.claude/schemas/extensions.json` | `$comment_genesis` names the second anchor and this record |
| `.claude/skills/init-project/SKILL.md` | step 5 says the message is typed or reached through the Skill tool |
| `.claude/.ci/GenesisTest.java` | seven cases, see below |
| `docs/en/07-ci-validate.md`, `docs/pt-br/07-ci-validate.md` | the `audit genesis` row lists the new cases |

Goes to the generated project: **yes, with no change of ours** — `export` copies `ArchHook.java`, the jar and `extensions.json` whole. No template, no `export` entry, no hook registration, so no restart is needed.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › audit genesis fills GENESIS from the transcripts, once` | A Skill tool run anchors Started on its prompt, or on the result without a `promptId`; another skill's result and an echoed field anchor nothing; the later of a typed and a Skill run wins, both ways | green on the tree · red with the Skill branch disabled (`if (false && viaSkill …`): four cases failed by name |

Step already existed; this PR adds cases to `GenesisTest.java`, not a step. `build --verify` proves the jar is the source.
