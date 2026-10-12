# Mermaid `classDiagram` and `stateDiagram-v2` — the subset a domain partial uses

Reference for `domain-modeling`, step 6. Loads only when block 1's diagrams are drawn.

**What** each diagram draws, and that it adds no name and no fact, is the norm
`@.claude/rules/spec-diagrams.md` — not restated here. This page owns **how** to write them:
the syntax subset, the mapping from the partial's tables to diagram elements, and the traps
of the renderer. Worked variants (a child entity with multiplicity, a sealed type, a
lifecycle with final states): `templates/domain-diagram-variants.md.example`; the base case
is block 1 of `templates/domain-spec.md.example`.

Source, verified on 2026-10-12: <https://mermaid.js.org/syntax/classDiagram.html>,
<https://mermaid.js.org/syntax/stateDiagram.html> and, for what GitHub renders,
<https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams>.
Where they disagree with this page, they win.

## `classDiagram` — the subset

| Element | Syntax | Use |
|---|---|---|
| Header | `classDiagram`, then `direction LR` | Left to right keeps the root on the left and its parts beside it |
| Class with fields | `class User {` · `UserId id` · `}` | Type first, then name — the Java order of the field table. One field per line, exactly the table's rows |
| Generic type | `List~OrderLine~ lines` | `~` instead of `<>`. A type with two parameters (`Map<K, V>`) does not parse: write it without its parameters (`Map`) |
| Enum | `<<enumeration>>` inside the body, then one value per line | A closed set the partial decided is an `enum` |
| Sealed type | `<<interface>>` inside the body; each permitted type `Assigned ..|> UserDepartment` | A sealed interface and the records it permits |
| Composition | `User *-- Email` | A value object or child entity the root owns |
| Multiplicity | `Order "1" *-- "1..*" OrderLine : lines` | Only when the text states it; quoted, on each side |
| Label | `User *-- DepartmentId : Department by reference` | The field it is, or the aggregate an id points to |
| Note | `note for Order "text"` | A sentence the text already holds, rarely needed |

## From the partial to the class diagram

| Partial | Diagram |
|---|---|
| **Aggregate root** and its field table | One class, with exactly those fields |
| **Value objects** table | One class per row, composed by the root; its fields only when the table names them |
| A field holding another aggregate's id | The id's class, composed and labelled with the other aggregate's name — never the other aggregate as a class |
| A child entity the text gives a multiplicity | Composition with that multiplicity |
| A sealed type the partial decided | `<<interface>>` plus a realization per permitted type |
| Methods, factories, invariants, exceptions | Nothing — they are text in blocks 1-3, and a method drawn on a class is a second list of the API |

## `stateDiagram-v2` — the subset

| Element | Syntax | Use |
|---|---|---|
| Header | `stateDiagram-v2` | First line, alone |
| Creation | `[*] --> PENDING : register` | The value the creation factory sets, labelled with the factory's method name |
| Transition | `PENDING --> ACTIVE : confirmEmail` | One per method of *Reached by*, labelled with the method name only — no parameters, no parentheses |
| Final state | `SHIPPED --> [*]` | Only when the text says no method leaves the value |
| Value nothing reaches yet | `DEACTIVATED` on its own line, then `note right of DEACTIVATED : UC-007-deactivate-user (backlog)` | Declares the state with no incoming transition, and the note names the case on the hook for it |

State ids are the enum's values as written (`UPPER_SNAKE`): no spaces, so no `state "…" as`
form is needed.

## From the partial to the state diagram

| *State reachability* | Diagram |
|---|---|
| A row reached by the creation factory | `[*] -->` that value |
| A row reached by a method, with the values it leaves from | One transition from each of those values |
| A row reached by nothing yet, with a named case | A bare state and a note naming the case |
| The rehydration path | Nothing — it is not a row, and it is not a transition |
| Table is `none` | No state diagram |

## Not used in a domain partial

| Construct | Why not |
|---|---|
| Visibility markers (`+ - # ~`), `$` static, `*` abstract | The field table states no visibility; every field is private by norm |
| Methods `name()` on a class | A second, divergent list of the aggregate's API |
| Inheritance `<|--` between aggregates, aggregation `o--`, dependency `..>` | Relations the partial does not decide; persistence and transport types never appear |
| `<<choice>>`, `<<fork>>`, `<<join>>`, composite states, concurrency `--` | Guards and parallel regions the table does not state |
| `classDef` styling, colors | Decoration |

## Renderer traps

- **The word `end`** breaks a diagram when it is an id or a label. Wrap it, or name the state
  differently in the text first
- **Commas inside a generic** do not parse — see *Generic type* above
- **`%%`** starts a comment to the end of the line
- **A class name with a space or a symbol** needs backticks; a canonical Java name never has one

## Checking that it renders

The PR's *Files changed* view renders the block, as does <https://mermaid.live>. A block
holding only `info` shows the Mermaid version GitHub runs.
