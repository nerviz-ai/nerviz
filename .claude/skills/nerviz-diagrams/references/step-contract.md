# The step contract

Single owner of how a skill declares its steps so `scripts/Diagrams.java` can draw them. It binds
every skill that a `docs/diagrams/<view>/view.json` names in its `skill` field, and nothing else:
15 other skills use `### N · Name` headings for their own reasons and are never parsed. Design:
`@.claude/decisions/0192-nerviz-diagrams-draws-the-orchestrators.md`, extended by
`@.claude/decisions/0193-nerviz-diagrams-branches-phases-and-one-stepped-kind.md`.

The written order is the execution order: steps are ordered by their line in the file, and the
diagram draws, animates and routes in that order.

## The rules

1. **Scope.** The whole file, outside fenced code blocks. Inside `## Procedure` (a heading that
   starts with `## Procedure`) every `### ` heading containing ` · ` is a step and must carry a field
   block (exit 2 otherwise). Outside it, a `## ` or `### ` heading containing ` · ` is a step **only**
   when its next non-blank line starts with `**Runs:**`; otherwise it is prose.
2. **Step heading.** `#{2,3} <id> · <Name>`, the id being digits with one optional letter (`3b`) or
   lowercase words joined by hyphens (`approval`). Its label: a numeric id gives `<id> · <Name>`, a
   word id gives `<Name>`.
3. **Retired grammar.** `### Step <id>: …` in scope exits 2. Write `### <id> · <Name>` and move
   `depends on` into the `**Runs:**` line.
4. **Field block.** The lines right after the heading that start with a field, blank lines between
   them allowed, ending at the first non-blank line that starts with none. In this order:

   - `**Runs:** <phase>( · **Depends on:** <ids>)?( · **Reads:** <ids>)?( · **Conditional:** yes)?( · **Gate:** <gates>)?( · **Note:** <note>)?` — required, first.
   - `**Then runs:** <phase>( · **Note:** <note>)?` — zero or more; each is the next phase, run after
     the previous one ends.
   - `**Stops when:** <condition> · **Says:** <line>(; <line>)?` — a branch placed after the phase line
     it follows; yes ends (`stop`) or blocks (`gate`) the run.
   - `**Skips when:** <condition> · **To:** <id> · **Says:** <line>(; <line>)?` — a branch whose yes
     skips to a later step `<id>` of the same skill and rejoins the flow there.
   - `**Hands over:** `<cmd>`(, `<cmd>`)*` — last, at most one per file. Each item is a backticked
     literal kept verbatim; one that starts with `/` must name a skill of `.claude/skills/` or an entry
     of `types.skill.native_commands` in `extensions.json`, else exit 2. It is printed text: the diagram
     draws a dashed box, never a call.

   A field line anywhere else in the file exits 2: `<file>:<line> — **<Field>** must be in the field
   block right under a step heading`. A `**Runs:**` or `**Then runs:**` that does not match its grammar
   exits 2 too.
5. **Phase.** `<chain>(, <chain>)*`. Chains separated by `, ` run in either order (in parallel when the
   step has two phases or more). A chain is `<ref>(><ref>)*` with an optional trailing `?`: that chain
   is conditional. `self` as in `<ref>` below.
6. **Depends on / Reads.** Ids of steps of the same skill, each **written before** the step, else exit
   2: `step <id> depends on|reads '<x>', which is written after it — the written order is the execution
   order`. `Depends on` draws an edge; `Reads` is a soft dependency: it orders the layout and draws none.
   A step with no `Depends on` is drawn after the step written before it.
7. **Gate.** The words live in `view.schema.json` `$defs.gate.enum`, the ones that branch in
   `$defs.branchGate.enum`; `Diagrams.java` reads both from there. Repetition is meaningful:
   `confirm, confirm` is two gates. The count of branch words in `Gate` equals the count of
   `**Stops when:**` lines, and the i-th line takes the i-th branch word, else exit 2: `step <id> has <n>
   branch gate word(s) (<words>) and <m> **Stops when:** line(s)`. `**Skips when:**` needs no gate word.
8. **Text limits are data.** A condition is held to `$defs.branchCondition`, each part of `Says` to
   `$defs.branchLine`, a note to `$defs.phaseNote`: the extractor runs the schema walker on the string
   and prints the definition's `x-fix`, never a constant of its own. A `**Note:**` names a phase, so a
   step with one phase refuses it (`a note names a phase; step <id> has one`), and `**To:**` must name
   a step written after this one (`skips to '<x>', which is no later step`).
9. **Zero steps** in a named skill exits 2.

## `<ref>` — who runs the step

| Ref | Resolves to | Meaning |
|---|---|---|
| `self` | `skill:<the skill itself>` | the orchestrator, on its own thread |
| `skill:<name>` | a directory of `.claude/skills/` | a skill, through the Skill tool |
| `agent:<name>` | a file of `.claude/agents/` | an isolated agent |
| `cmd:<mode>` | a `case` of the dispatch in `.claude/hooks/ArchHook.java` | an `ArchHook` mode, through Bash |

Segments chain with `>` to say who the executor delegates to
(`skill:test-architect>agent:archunit-installer`). Every segment must resolve to a fact of
`Diagrams.java facts`, else exit 2 naming it. A step that runs `self` alone and has one branch is drawn
as a diamond, the others keep their box.

## `<gate>` — the closed vocabulary

| Word | The step |
|---|---|
| `ask` | asks a question that collects input |
| `confirm` | asks a question that holds a side effect |
| `stop` | refuses and ends the run: its `**Stops when:**` line says when |
| `gate` | runs a command whose exit 2 blocks the run: its `**Stops when:**` line says when |

## `cmd:<mode>`

The first line of `ArchHook.java` whose trimmed text is exactly `switch (mode) {` and whose previous
non-blank line is `try {`: every `case "<m>"` (each name of a comma list too) at the indentation of
that switch's first `case`, up to the line that closes the switch. None found exits 2.
