# Mermaid in a parent spec — `sequenceDiagram` and `flowchart TD`

Reference for `use-case-design`, step 8. Loads only when the spec's diagrams are drawn: the
`sequenceDiagram` under `## Flow` and the `flowchart TD` under the **Out** table.

**What** each diagram draws, and that it adds no name and no fact, is the norm
`@.claude/rules/spec-diagrams.md` — not restated here. This page owns **how** to write them:
the syntax subset, the mapping from the spec's sections to diagram elements, and the traps of
the renderer. Worked variants (external HTTP port with its outcomes, event trigger, schedule):
`templates/use-case-diagram-variants.md.example`; the base case is
`templates/use-case-spec.md.example`.

Source, verified on 2026-10-12: <https://mermaid.js.org/syntax/sequenceDiagram.html>,
<https://mermaid.js.org/syntax/flowchart.html> and, for what GitHub renders, <https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams>.
Where they disagree with this page, they win.

## `sequenceDiagram` — the subset

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

## From the spec to the sequence diagram

| Spec | Diagram |
|---|---|
| `Trigger` row | The actor, and the first message: actor → inbound adapter |
| Flow step naming the command | adapter → inbound port, the command's name as the text |
| Flow step building or loading the aggregate | inbound port → aggregate |
| Flow step through an outbound port | inbound port → that port. The remote system of an `External calls` row appears only through its port, never as a participant of its own |
| `Side effects` row | A note per effect, on the port that causes it |
| Flow step answering the caller | adapter → actor, as a return |
| `Transaction`, `Repetition`, `Concurrency` rows | Nothing — they are not messages |

## Not used in the sequence diagram

| Construct | Why not |
|---|---|
| `alt` / `opt` / `loop` / `par` / `critical` / `break` | A branch the numbered list does not have, and messages that break the one-per-step count. The branches are the flowchart's |
| `activate` / `deactivate`, `+` / `-` on arrows | Lifeline bars carry no fact the list states |
| `box`, `rect`, colors | Decoration; a hex color does not parse (`#` starts an entity) |
| `create` / `destroy`, half-arrows, central connections | Recent Mermaid versions; GitHub may render an older one |
| `<<->>` bidirectional arrows | Same — and a call has a direction |

## `flowchart TD` — the subset

| Element | Syntax | Use |
|---|---|---|
| Header | `flowchart TD` | First line, alone — top to bottom, the order the checks run |
| Start | `trigger((user creation))` | One circle, labelled with the trigger as the `Trigger` row words it |
| Decision | `shape{"payload shape valid?"}` | One per check the text makes. Text in double quotes — a `?` or a parenthesis outside quotes can break the parse |
| End | `created(["created"])` | One stadium per situation of the **Out** table, its text the situation as written there |
| Edge | `shape -->\|no\| malformed` | A decision's two answers, `yes` and `no`; an unlabelled `-->` only from the start |

Count before closing the block: **end nodes = rows of the Out table** — not one more, not one
fewer. A stadium `([…])` is used for nothing else.

## From the spec to the flowchart

| Spec | Flowchart |
|---|---|
| `Trigger` row | The start node |
| A check in `## Flow` — the shape in the adapter, an invariant when the aggregate is built, a lookup at an outbound port, a call that can fail | A decision, in the order the flow makes it |
| A row of `## Invariants` checked outside the aggregate (uniqueness at the outbound port) | A decision where the flow reaches that port |
| An `External calls` row's tolerance when the system is down, or its unknown result | A decision after the call — only when the **Out** table lists the situation it leads to |
| A situation of the **Out** table | An end node |
| `## Errors` kinds, statuses, exception names | Nothing — the end node is the situation, not its kind or its code |

## Not used in the flowchart

| Construct | Why not |
|---|---|
| `subgraph` | Groups by layer are the components table's, not a decision |
| A loop back to an earlier node | A retry the text does not state; retries are the transport and client partials' |
| A node naming a class, port or participant | Who calls whom is the sequence diagram's |
| Shapes beyond circle, rhombus and stadium; `@{ shape: … }`; `classDef`, `click` | Decoration, or recent Mermaid versions GitHub may not render |

## Renderer traps

- **The word `end`** closes a block and breaks the diagram when it appears in a name or text.
  In a sequence, wrap it: `(end)`, `"end"`, `[end]`. In a flowchart, a lowercase `end` as a node
  breaks it — capitalize it or keep it inside quoted text
- **A flowchart node id starting with `o` or `x`** right after `--` becomes a circle or cross
  edge. Pick another id
- **A semicolon** separates statements. Inside text it is `#59;`; `#35;` is `#`
- **`%%`** starts a comment to the end of the line — never inside a message
- **A line break inside a participant's name** needs an alias; keep aliases to one line
- **Paths, verbs and status codes** stay out (the norm), and so does `/` text that reads as one

## Checking that it renders

The PR's *Files changed* view renders the block, as does <https://mermaid.live>. A block
holding only `info` shows the Mermaid version GitHub runs — check it before using a construct
the page above marks with a version.
