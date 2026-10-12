# Mermaid `sequenceDiagram` — the subset a parent spec uses

Reference for `use-case-design`, step 8. Loads only when the `## Flow` diagram is drawn.

**What** the diagram draws, and that it adds no name and no fact, is the norm
`@.claude/rules/spec-diagrams.md` — not restated here. This page owns **how** to write it:
the syntax subset, the mapping from the spec's sections to diagram elements, and the traps of
the renderer. Worked variants (event trigger, schedule, external HTTP port):
`templates/sequence-diagram-variants.md.example`; the base case is the `## Flow` of
`templates/use-case-spec.md.example`.

Source, verified on 2026-10-12: <https://mermaid.js.org/syntax/sequenceDiagram.html> and, for
what GitHub renders, <https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams>.
Where they disagree with this page, they win.

## The subset

| Element | Syntax | Use |
|---|---|---|
| Header | `sequenceDiagram` | First line of the block, alone |
| Numbering | `autonumber` | Second line, always — the numbers are what tie each message to its step of the list |
| Caller of the trigger | `actor caller` · `actor scheduler` | The one actor, first. Its id is a word the trigger row uses |
| Participant | `participant CreateUserUseCase` | One per canonical name, declared before the first message — the declaration order is the drawing order |
| Participant with a label | `participant adapter as inbound adapter` | When the text names the role, not a class: the alias is the phrase the text uses |
| Synchronous call | `A->>B: text` | A step where `A` calls `B` and waits |
| Return | `B-->>A: text` | Only the step that hands the result back (the response) |
| Asynchronous send | `A-)B: text` | A step that publishes and does not wait — an event through a `messaging` port |
| Note | `Note right of B: text` · `Note over A,B: text` | Each side effect of the header's `Side effects` row, beside the port that causes it. A note is not a message and takes no number |

Count before closing the block: **messages = numbered steps**. A note never counts.

## From the spec to the diagram

| Spec | Diagram |
|---|---|
| `Trigger` row | The actor, and the first message: actor → inbound adapter |
| Flow step naming the command | adapter → inbound port, the command's name as the text |
| Flow step building or loading the aggregate | inbound port → aggregate |
| Flow step through an outbound port | inbound port → that port. The remote system of an `External calls` row appears only through its port, never as a participant of its own |
| `Side effects` row | A note per effect, on the port that causes it |
| Flow step answering the caller | adapter → actor, as a return |
| `Transaction`, `Repetition`, `Concurrency` rows | Nothing — they are not messages |

## Not used in a spec

| Construct | Why not |
|---|---|
| `alt` / `opt` / `loop` / `par` / `critical` / `break` | A branch the numbered list does not have, and messages that break the one-per-step count. Error situations live in `## Errors` |
| `activate` / `deactivate`, `+` / `-` on arrows | Lifeline bars carry no fact the list states |
| `box`, `rect`, colors | Decoration; a hex color does not parse (`#` starts an entity) |
| `create` / `destroy`, half-arrows, central connections | Recent Mermaid versions; GitHub may render an older one |
| `<<->>` bidirectional arrows | Same — and a call has a direction |

## Renderer traps

- **The word `end`** closes a block and breaks the diagram when it appears in a name or text.
  Wrap it: `(end)`, `"end"`, `[end]`
- **A semicolon** separates statements. Inside text it is `#59;`; `#35;` is `#`
- **`%%`** starts a comment to the end of the line — never inside a message
- **A line break inside a participant's name** needs an alias; keep aliases to one line
- **Paths, verbs and status codes** stay out (the norm), and so does `/` text that reads as one

## Checking that it renders

The PR's *Files changed* view renders the block, as does <https://mermaid.live>. A block
holding only `info` shows the Mermaid version GitHub runs — check it before using a construct
the page above marks with a version.
