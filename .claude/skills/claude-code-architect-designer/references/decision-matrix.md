# Decision matrix — which extension for which scenario

Reference for the `claude-code-architect-designer` skill. Loads only when it is invoked.

Source: `@claude-help.md` (§ 2, 4, 5, 6, 7, 9, and 13) and the official documentation at
<https://code.claude.com/docs/en/overview>, <https://code.claude.com/docs/en/mcp>, and
<https://code.claude.com/docs/en/settings-reference>. Where they diverge, the official
docs win.

---

## 1. The dividing line

```
   ┌─ CLAUDE.md ──── fact, always in context             │
   ├─ rules/ ─────── fact, by territory (paths)          │  PERSUASION
   ├─ skills/ ────── procedure, on demand                 │  (the model can fail)
   ├─ agents/ ────── isolated execution                   │
   └─ .mcp.json ──── external tool, on demand             │
  ═════════════════════════════════════════════════════
   ┌─ permissions ── allow / ask / deny                   │  GUARANTEE
   └─ hooks ──────── lifecycle events                      │  (always executes)
  ─────────────────────────────────────────────────────
   └─ mods/ ──────── draws what the two above decide      │  INTERFACE (where it loads)
```

Everything above the line is read by the model: it can be ignored, misinterpreted, or
lost in a `/compact` — including whether it calls an available MCP tool at all. Everything
below executes regardless of what the model decides. `.mcp.json` sits on the persuasion
side for that reason: connecting a server doesn't guarantee the model uses it well,
which is exactly why MCP and a skill combine (`@claude-help.md` § 9) instead of MCP
replacing one.

**Consequence for classification:** if breaking the rule is a compliance, security, or
build bug, the answer is below the line — Form 7 (hook) or Form 8 (`permissions`), § 2.2.
This skill designs and writes both, after approval, like every other form.

**And mods sit under both, never between them.** A mod executes regardless of the model
too, but only where it loads — not in `claude -p`, VS Code, a cloud session or under
`--safe-mode` — so it is neither persuasion nor guarantee: it is the interface over the
guarantee. Form 9, § 2.3.

**And the consequence for the diagram of `@CLAUDE.md`:** a skill that writes a hook
appears to point back at the layer that verifies it. It doesn't. The arrow is
*design-time* — the same one `project-bootstrap` already has when it writes `src/` — and
at runtime nothing changes: the hook still verifies the skill, and the skill is still
read by a model that can ignore it. What the skill gains is the ability to close the
decision instead of ending it with "that's a hook, ask someone else."

---

## 2. Decision table

Read top to bottom. **The first row that matches decides** — the questions are ordered by
elimination power, not by frequency.

| Question | If yes |
|---|---|
| Must it happen always, without depending on the model's judgment? | **Form 7 or 8** — go to § 2.2, which decides which |
| Does it need to access an external system (Jira, database, S3) — go to § 2.1 first | **Form 6** — MCP server, unless § 2.1 eliminates it |
| Is it a declarative fact that holds in every session and across the whole repo? | **Form 5** — `CLAUDE.md` section |
| Is it a declarative fact that only holds for part of the files? | **Form 4** — rule in `rules/` with `paths` |
| Is it a multi-step procedure, or long, rarely-needed reference? | **Form 1 or 2** — skill |
| … and does the user want to trigger it by hand, or does it have a side effect? | **Form 2** — `disable-model-invocation: true` |
| … and should it fire on its own from the description, without the user asking? | **Form 1** |
| Does it need isolated context, restricted tools, or a different model? | **Form 3** — subagent, and even then see § 5 |
| Does the person need to **see** or **act on** something the hooks already decide — a band, a pane, a spinner, a dialog over a tool call, a `/command` that spends no turn? | **Form 9** — mod, § 2.3 |

No row matches → the answer is **create nothing**.

### 2.1. Sub-table — which MCP form, and whether it's MCP at all

Read top to bottom, same rule: **the first row that matches decides**. Sits inside the
"external system" row above because CLI-first has to run before Form 6 is even
considered, not after.

| Question | If yes |
|---|---|
| Does a CLI already solve it (`gh`, `psql`, `aws`, `kubectl`, `sentry-cli`)? | **Create nothing** — `Bash` + a line in `permissions.allow`. `@claude-help.md` § 9 names this the cheaper alternative; it is the most context-efficient way to talk to an external service and the model already knows how to use it |
| Must the tool never be callable at all? | `permissions.deny` on `mcp__<server>__<tool>` — out of scope, propose and stop |
| Does only one agent need it? | **Form 6b** — `mcpServers` in that agent's frontmatter |
| Does the whole team need it, with no secret literal in the file? | **Form 6a** — `.mcp.json`, credential via `${VAR}`, `oauth`, or `headersHelper` |
| Is it personal or experimental, not for the team? | `claude mcp add --scope local` — this skill doesn't write it; it isn't versioned |
| Does the server already exist and the model just uses it wrong? | **Form 1** — a skill documenting how to use its tools well, not a new server |

No row matches → **create nothing**. A server with no observed call is dead weight from
the first session: its name alone costs context at startup (§ 3, "cheap triage" row
doesn't apply here — see § 7 anti-pattern 13).

### 2.2. Sub-table — which guarantee, and whether it needs one

Reached only from the first row of § 2. Same rule: **the first row that matches decides.**

| Question | If yes |
|---|---|
| Is the answer a flat refusal of a tool call — this path, this command, never? | **Form 8** — `permissions.deny`. Read before the call, costs no process. A hook that spawns a JVM to print "no" is the same answer at a hundred times the price |
| Should a tool run without asking, every time? | **Form 8** — `permissions.allow`. Same file, opposite direction; the alternative is the user approving the same call forever |
| Does the check need to read the file, run the build, or compare two sources? | **Form 7** — a hook. `permissions` matches a pattern and stops there; it cannot open anything |
| Does the check already exist as a mode of `ArchHook.java` (`check`, `format`, `schema`, `tests`, `guard`, `audit`, `compose`)? | **Form 7a alone** — a registration, no Java. This is the common case |
| Does only one skill or agent need it, while it runs? | **Form 7b** — `hooks:` in that file's frontmatter. The rest of the session pays nothing |
| Does the whole session need it, on a lifecycle event? | **Form 7a** — the `hooks` block of `.claude/settings.json`, plus **7c** if no mode covers the check |
| Does the model already get it right, and the hook would only make it official? | **Create nothing.** A hook is a guarantee against a failure that was observed — axis 1. Without the observed failure it is a process spawned per event to confirm what was already true |

### 2.3. Sub-table — a mod, or something cheaper

Reached from the Form 9 row of § 2. Same rule: **the first row that matches decides.**

| Question | If yes |
|---|---|
| Must the rule hold even where no mod loads (`-p`, VS Code, cloud, `--safe-mode`)? | **Form 7 or 8** — § 2.2. A mod may be added on top later; it is never the first piece |
| Is what the person needs to read a line at the moment of a refusal? | **Form 7** alone — the blocking hook's stderr is that line. A dialog over it pays a second language for the same sentence |
| Is it text the **model** needs, not the person? | **Form 7a** with `additionalContext`, or a skill — a mod draws for the person, and `prompt.section`/`prompt.context` text that changes per request invalidates the prompt cache |
| Does it show state a mode of `ArchHook.java` already computes? | **Form 9** calling that mode through `$.process.run` — plus **7c** only when no mode prints it, as `guard status` was added for the cockpit (`0131`) |
| Does it hold a tool call until the person answers? | **Form 9** on `tool.call`, **only** over a guarantee that already refuses or asks — the dialog chooses what happens next, never whether the call runs |
| Does it need the network, the model, or to write a file? | **Create nothing** here — `mods.forbidden_calls` refuses it. The network is an MCP server (§ 2.1), the model is a skill or an agent, a write is Claude's tool behind `guard` |

Deciding **which event** is a separate question from which form, and it has one source:
`references/hook-events.md`. Two mistakes it exists to prevent — an event name that
exists nowhere (the hook simply never fires, with no error) and a `matcher` on an event
that never reads one (it looks like a filter and filters nothing).

---

---

## 3. Triggers — the signal that justifies creating

Not configured by anticipation. Each form has a recognizable signal; without the signal,
the piece is born speculative and dies from disuse.

| Observed signal | Form |
|---|---|
| The model gets the same convention wrong a second time | 5 (one line) or 4 (if it has territory) |
| `CLAUDE.md` went past ~200 lines | 4 — extract with `paths` |
| You pasted the same N-step procedure a third time | 1 or 2 |
| You write the same prompt to kick off a task | 2 |
| A task fills context with verbose output (search, logs, build) | 3 |
| A task shouldn't be able to write files | 3, with restricted `tools` |
| Cheap triage repeating many times | 3, with `model: haiku` |
| A rule written in prose was broken anyway, a second time | 7 — the prose was a request; make it enforcement |
| The same file keeps getting edited when it shouldn't be | 8 — `permissions.deny` on that path |
| The same permission prompt gets approved every session | 8 — `permissions.allow` |
| A check exists as a script somebody remembers to run | 7a on the event that check belongs to |

---

## 4. Form 1 vs Form 2 — auto-invocable or manual

Same file, one line of difference. Mistakes happen in both directions:

| Symptom | Cause | Fix |
|---|---|---|
| The skill doesn't fire when it should | `description` too vague or abstract | Concrete use case first, trigger phrases after |
| The skill fires too much | Territory too broad | Add `paths`, or switch to `disable-model-invocation: true` |
| The skill fires on its own and causes damage | It has a side effect | `disable-model-invocation: true`, always |

This repo's practical rule, and the exception that bounds it:

- **Writes files in the user's project and is triggered by their decision → Form 2.**
  `arch-doctor`, `init-project`, `project-bootstrap`, `gof-design-patterns`, and this skill.
- **Part of a pipeline another piece chains → Form 1**, even when writing files.
  `disable-model-invocation` hides the skill from the model, and what the model doesn't
  see the orchestrator doesn't call. The five pieces of `/new-feature` have been this way
  since D17 — `@.claude/decisions/0007-pipeline-skills-invocation.md`.

The guard for those five isn't the frontmatter: it's the entry rule in the body, which
makes them stop when the previous partial doesn't exist.

Only `description` stays permanently in context; the body loads on invocation. An
800-line guide costs ~30 tokens until it's needed — so a long body isn't an argument
against the skill. `description` + `when_to_use` are truncated to 1536 characters in the
listing.

---

## 5. Form 3 — the three reasons, and only those

An agent exists for one of these:

1. **Preserve context** — verbose exploration stays in the subagent, only the summary
   comes back.
2. **Restrict tools** — a reviewer with `tools: Read, Grep, Glob` can't write.
3. **Control cost or capability, scoped to that work** — `model: haiku` for triage, `opus`
   where failure is expensive. A skill declares `model` natively, but its override holds
   for the **rest of the turn**: a pin that lowers the model on a skill fired mid-turn
   degrades whatever follows it. The model alone justifies an agent only when it must not
   leak past the work — otherwise it is a skill's `model`, required per class in
   `skill_classes` (decision 0081).

Two of them are frontmatter, and `agent_classes.universal_fields` requires both on every
agent: `tools` (reason 2) and `model` (reason 3). An agent that declares neither has
documented none of its reasons and inherits whatever the caller had — which changes in
silence when the default changes. A class also fixes the sections its body carries and the
paths it may write; `permissionMode: bypassPermissions` is forbidden outright (criterion 8,
trust surface).

None applies → it's a skill. This is anti-pattern #1, and the most expensive one: one
more piece to maintain, no gain.

**Corollary — `mcpServers` in an agent's frontmatter is reason 2, never a fourth
reason.** Giving one agent its own MCP server is "restrict tools" applied to an external
connection instead of a built-in one: the rest of the session doesn't carry that
server's tool names in context, and no other agent can reach it. It doesn't unlock a new
category of agent — the same three-reason test above still gates whether the agent
should exist at all; `mcpServers` only answers *which* tools it gets once an agent is
already justified.

Counter-test before proposing Form 3 — if any answer is yes, it's a skill:

- Is the interview with the user the heart of the task? (the subagent doesn't see the
  conversation)
- Does the context it needs fit in a `references/` of the skill itself?
- Is the final output short anyway?

**What the subagent receives:** its own system prompt, the delegation message, all
`CLAUDE.md` files, a `git status` snapshot, and the skills preloaded in `skills:`.
**What it doesn't receive:** conversation history, skills already invoked, files already
read.

Sole precedent in this repo: `init-project` → `project-initializer`, and the agent's own
file opens with the section "Why this is an agent and not a skill". A new agent repeats
that section or isn't approved.

**Delegating the writing isn't a reason.** "The decision's already been made, now it's
just implementation" fails the counter-test above on all three points: the interview is
the heart of the task, the context fits in `templates/`, and the output is short. And the
premise is false — choosing the `description` decides whether the skill fires (§ 4), and
the `## Contract` decides ownership (invariant 2). That's design. What's left that's
mechanical is only propagation: table edits with file and line fixed in writing. Only
that part gets delegated, and only when the volume justifies it — axis 8 = "both". Never
without a saved decision record: the subagent doesn't see the conversation.

---

## 6. Form 4 vs Form 5 — rule or `CLAUDE.md`

| | Form 5 (`CLAUDE.md`) | Form 4 (`rules/`) |
|---|---|---|
| Cost | Every prompt, every session | Only when a `paths` matches |
| Scope | Whole repo | File territory |
| Survives `/compact` | Yes, re-injected | Reloads on touching the files |
| Size target | < 200 lines total | One theme per file |

Two loading paths for Form 4, and the first is preferable: **`paths`** (auto-loads,
doesn't depend on anyone remembering) and **explicit citation** by path, for
cross-cutting rules no glob captures. Declare `paths` whenever there's identifiable
territory.

Non-negotiable restrictions of this repo:

- **Invariant 1 — `rules/` is a leaf.** A rule never mentions a skill, an agent, or a
  command. If it needs to, it's a procedure: it belongs in a skill.
- **Invariant 2 — single owner.** The rule lives in one file; others cite it by path
  (`@.claude/rules/naming.md`). Written in two places, it diverges on the first update.
- A new rule enters `@.claude/rules/00-index.md` (leaves "planned", enters "written"). It
  travels to the generated project on its own — `export.rules` takes the whole directory —
  but a rule with a package territory also needs an `export.derived_paths` entry, or it
  ships naming this repo's package and never loads.

What does **not** go into a rule or `CLAUDE.md`: architecture readable from the code, a
dependency list, directory layout, a multi-step procedure. What does: build and test
commands, conventions that diverge from the tool's default, known pitfalls, decisions and
their reasoning. Specific always beats vague.

---

## 7. Anti-patterns — reject in Phase 2

| # | Anti-pattern | Signal | Redirect |
|---|---|---|---|
| 1 | Agent where a skill would do | None of the three reasons in § 5 | Skill |
| 2 | Prose where it had to be a hook | "Always run X before finishing" repeated | Form 7 (§ 2.2) |
| 3 | Obese `CLAUDE.md` | Past ~200 lines | Extract to `rules/` with `paths` |
| 4 | Rule that talks about skills | Breaks invariant 1 | Move the procedure to the skill |
| 5 | Duplicated rule | Same theme in two files | Single owner + citation |
| 6 | New `commands/` | Invariant 4 | Skill + `disable-model-invocation` |
| 7 | Invented frontmatter | Field the runtime ignores | `frontmatter-fields.md` |
| 8 | Code in the skill body | Boilerplate pasted into markdown | `templates/*.example` |
| 9 | Piece built on anticipation | No symptom in interview axis 1 | Create nothing |
| 10 | Creation skill copied into the generated project | Missing from `export.skills.exclude` | Exclude it, and say so |
| 11 | MCP where a CLI already solves it | `gh`/`psql`/`aws`/etc. already installed | Create nothing + `permissions.allow` (§ 2.1) |
| 12 | Literal secret in `.mcp.json` | A token, key, or password spelled out in `headers`/`env` | `${VAR}`, `${VAR:-default}`, `oauth`, or `headersHelper` — invariant 11 |
| 13 | MCP server with no observed use | No symptom in interview axis 1; its name still costs context at every startup | Create nothing |
| 14 | Same server duplicated across scopes with no destination reason | Same name in `.mcp.json` and an agent's `mcpServers`, or in both this repo's `.mcp.json` and the project template, with no axis-13 "both" answer behind it | Single destination, or record the "both" answer in the decision |
| 15 | Hook with no filter | A `PostToolUse` entry with no `if` and no `matcher`, for a check that concerns a handful of paths | Add the `if`; criterion 9 of the rubric. A JVM per edit to every file |
| 16 | Hook for something the model already gets right | No observed failure in axis 1; the hook only makes the rule official | Create nothing. A guarantee is bought against an observed failure, not against a worry |
| 17 | New Java mode where a registration would do | A Form 7c proposed while `check`, `format`, `schema`, `tests`, `guard`, `audit`, or `compose` already runs the check | Form 7a alone, reusing the mode |
| 18 | Hook that blocks without saying how to proceed | Exit `2` with a bare "not allowed" on stderr | Name the rule, the file, and the alternative. That text is all the blocked person sees |
| 19 | Exec form, or a chain in `command` | An `args` list, which the Copilot CLI ignores and then denies every call; or a pipe, `&&`, redirect or substitution inside `"command"` | One string, `java -jar "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar" <mode>`, no `args`. Rejected by `ArchHook.java schema` (0168) |
| 20 | Skill or agent with no class | Created without an entry in `skill_classes` / `agent_classes`, so nothing describes its body or its territory | Add it to a class. `ArchHook.java schema` fails by name; for an agent, `model` and `tools` are required too, since they are two of the three reasons in § 5 |
| 21 | CI answer is "later" | An option with no CI item, or one that defers the check to a future review — the shape of `0084` and `0099`, where CI arrived a review after the piece | Name the job that already covers it, the test Phase 4 step 9 writes, or why nothing is testable — `ci-coverage.md` |
| 22 | Mod as the guarantee | A Form 9 that refuses or allows on its own, with no Form 7 or 8 beneath — invisible in `-p`, CI and every surface that draws nothing | Form 7 or 8 first; the mod renders their decision (§ 2.3) |
| 23 | Rule restated in TypeScript | A list, path or pattern written into a hooks module, or a phase re-derived from events | Read it from `ArchHook.jar` (`guard status`, `doctor`); a new read-only mode is Form 7c |

---

## 8. Score rubric (0-10)

So the score isn't opinion. Nine criteria, equal weight, 1.11 points each. Round to the
nearest integer and show what cost points.

| # | Criterion | A point is lost when |
|---|---|---|
| 1 | **Form fit** | The § 2 table (or § 2.1 for MCP) points to another form |
| 2 | **Compliance with the invariants** | Strains one of the eleven; violating one caps the score at ≤ 4 |
| 3 | **Context cost** | Rarely-used knowledge stays always loaded — for Form 6, an MCP server's tool names load at every startup whether or not the session uses them |
| 4 | **Enforcement** | Relies on persuasion where a guarantee was available |
| 5 | **Maintenance cost** | Adds pieces or indirection without proportional gain |
| 6 | **Precedent in the repo** | No similar form already in use; novel design |
| 7 | **Complete propagation** | Doesn't close routing, `00-index`, the `export` manifest, the class block a new skill or agent needs (`skill_classes` / `agent_classes`), the CI answer of `ci-coverage.md` (anti-pattern 21), or the § 9 decision record |
| 8 | **Trust surface** | Grants a capability wider than the task needs — an MCP server that reads files and calls arbitrary APIs, an agent with `permissionMode: bypassPermissions`, a skill with unscoped `allowed-tools` where a narrower rule would do |
| 9 | **Cost of always running** | Frequency of the event × cost per firing, and whether `if`/`matcher` narrows before a process is spawned. A `PostToolUse` hook with no filter pays a JVM startup on every edit in the repo; a `SessionStart` hook pays once. Also loses a point when the hook blocks (exit `2`) on a judgment call that will sometimes be wrong — the cost there isn't milliseconds, it's a person stuck |

Criterion 9 only bites on Forms 7, 8 and 9. For the persuasion forms it is scored full:
they cost nothing until read. A mod pays no process per event — it runs inside Claude Code
— but each `$.process.run` it makes is one, so a mod that calls `ArchHook.jar` on every
render loses the point a hook with no `if` loses; call it on the events that change the
answer and keep the result. This is the same treatment criterion 3 gives an MCP server whose
tool names load at every startup — the two are the runtime bill, before and after the
line in § 1.

Violating an invariant caps the score at **≤ 4**, regardless of the rest. A score **≥ 8**
is a recommendation; **5 to 7** is viable with a written caveat; **≤ 4** appears only to
record why it was rejected.

---

## 9. Decision record

Phase 3 produces options, scores, rejected alternatives, and sources. That disappears
with the session, and without it the same twenty-axis interview repeats itself six
months from now. Two levels, and the first is mandatory:

| Level | Where | When | Content |
|---|---|---|---|
| 1 | `## Why this is <form>` section in the created file | Always — except Forms 5, 6a, 7a, and 8, which are JSON or have no body. Form 7c carries it in the new mode's Javadoc, Form 9 in the header comment of its hooks module | Form chosen, axis that motivated it, closest alternative and why. Three sentences |
| 2 | `.claude/decisions/NNNN-<slug>.md` | Only if there was a real choice — or always, for Forms 7, 8 and 9 | Interview, all options with score, rubric, references, propagation |

Level 2 is saved when at least one of these is true: two or more options scored ≥ 5; the
approved option strains an invariant; axis 8 = "both"; axis 13 (MCP destination) =
"both"; **the approved form is 7, 8 or 9**. None of these → level 1 is enough. A record for a
trivial decision is ceremony, not memory.

Forms 7 and 8 have no trivial case. A hook changes what every session enforces and has
almost nowhere to explain itself — a `permissions.deny` line has nowhere at all. Whoever
finds it later and can't reconstruct why it blocks will delete it the first time it gets
in the way, and the failure it was bought against comes back. Form 9 has none either: a
mod is a second language running unsandboxed in every session that loads it, and its
record is where the guarantee beneath it is named.

Level 1 travels with the file, including into the generated project. Level 2 stays in
this repository — it records decisions about this `.claude/`, invariant 9. It is not a
rule: no `paths`, no frontmatter, outside `00-index.md`. Full rules in
`@.claude/decisions/README.md`.

Restriction for Form 4: level 1 of a rule cannot name skills or agents — invariant 1,
`rules/` is a leaf. If the justification needs to name them, it lives only in level 2.
