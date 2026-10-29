# The step contract

Single owner of how a skill declares its steps so `scripts/Diagrams.java` can draw them. It binds
every skill that a `docs/diagrams/<view>/view.json` names in its `skill` field, and nothing else:
15 other skills use `### N · Name` headings for their own reasons and are never parsed. Design:
`@.claude/decisions/0192-nerviz-diagrams-draws-the-orchestrators.md`.

## The rules

1. **Scope.** Lines from the heading that starts with `## Procedure` to the next `## ` heading,
   outside fenced code blocks.
2. **Step heading.** A `### ` heading in scope is a step if and only if it contains ` · `. Grammar:
   `### <id> · <Name>`, the id being digits with one optional letter (`3b`) or lowercase words
   joined by hyphens (`consolidation`). A `### ` heading without ` · ` is prose, never a step.
3. **Retired grammar.** `### Step <id>: …` in scope exits 2. Write `### <id> · <Name>` and move
   `depends on` into the `**Runs:**` line.
4. **Runs line.** The first non-blank line after a step heading, in this fixed order:

   `**Runs:** <ref>(, <ref>)*( · **Depends on:** <id>(, <id>)*)?( · **Conditional:** yes)?( · **Gate:** <gate>(, <gate>)*)?`

   Missing or malformed exits 2.
5. **Hands over.** At most one per Procedure: `**Hands over:** `<cmd>`(, `<cmd>`)*`, on the next
   non-blank line after a step's `**Runs:**` line. Each item is a backticked literal kept verbatim.
   An item that starts with `/` must name a skill of `.claude/skills/` or an entry of
   `types.skill.native_commands` in `extensions.json`, else exit 2. It is printed text: the diagram
   draws a dashed box, never a call.
6. **Zero steps** in a named skill exits 2.

## `<ref>` — who runs the step

| Ref | Resolves to | Meaning |
|---|---|---|
| `self` | `skill:<the skill itself>` | the orchestrator, on its own thread |
| `skill:<name>` | a directory of `.claude/skills/` | a skill, through the Skill tool |
| `agent:<name>` | a file of `.claude/agents/` | an isolated agent |
| `cmd:<mode>` | a `case` of the dispatch in `.claude/hooks/ArchHook.java` | an `ArchHook` mode, through Bash |

Segments chain with `>` to say who the executor delegates to
(`skill:test-architect>agent:archunit-installer`). Several refs are separated by `, `. Every segment
must resolve to a fact of `Diagrams.java facts`, else exit 2 naming it. A `**Depends on:**` id must
be another step of the same skill.

## `<gate>` — the closed vocabulary

The words live in `view.schema.json` `$defs.gate.enum`; `Diagrams.java` reads them from there.
Repetition is meaningful: `confirm, confirm` is two gates.

| Word | The step |
|---|---|
| `ask` | asks a question that collects input |
| `confirm` | asks a question that holds a side effect |
| `stop` | refuses and ends the run |
| `gate` | runs a command whose exit 2 blocks the run |

## `cmd:<mode>`

The first line of `ArchHook.java` whose trimmed text is exactly `switch (mode) {` and whose previous
non-blank line is `try {`: every `case "<m>"` (each name of a comma list too) at the indentation of
that switch's first `case`, up to the line that closes the switch. None found exits 2.
