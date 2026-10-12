---
paths:
  - "docs/use-cases/**"
status: active
---

# Diagrams in specs — Mermaid, drawn from the text

A use case spec describes its flow, aggregate and lifecycle in text and tables, and a diagram
is a view of that text for the person reviewing it. It is never a second source: a diagram
that names something the text does not, or draws a call the text never makes, is two answers
to one question, and whoever implements from the spec has to guess which is right.

## Why this is a rule and not a `CLAUDE.md` section

It's a declarative fact with a territory: it holds for the files under `docs/use-cases/`, both
when one is written and when one is read to implement it, and nowhere else. Motivated by axis 4
of the interview (identifiable territory, touched by the writer and the reader of a spec); the
nearest rejected form was the same sentence in each spec template, which would put one norm in
two files and never reach whoever reads the finished spec. Full record in
`@.claude/decisions/0143-mermaid-diagrams-in-use-case-and-domain-specs.md`.

## Format

- **A diagram is a fenced `mermaid` block inside the spec's markdown.** Its first line is one of
  `sequenceDiagram`, `classDiagram` or `stateDiagram-v2` — nothing else is drawn in a spec
- **No diagram file next to the spec** — no `.drawio`, `.excalidraw`, `.svg` or `.png`. Each
  tool that draws one imports Mermaid, so the block is already the source of any such view, and
  a second file is a copy nobody updates
- **A diagram lives in the file that owns its facts.** A file that consolidates others by
  reference does not copy their diagrams

## Names

- **Every name in a diagram is one the same file's text already defines**, spelled the same:
  participants, classes, fields, value objects, states, and the methods on messages and
  transitions. A participant's display alias is a phrase the text uses (`inbound adapter`)
- **No new name and no synonym.** A name the diagram needs and the text lacks is missing from
  the text: it goes into the text first, or it stays out of the diagram

## Facts

- **A diagram adds no fact.** No step, call, field, multiplicity, state or transition the text
  does not state
- **Where the text and the diagram disagree, the text wins.** The diagram is the defect and is
  corrected to match; nothing is implemented from a diagram alone

## What each diagram draws

| Diagram | Draws | Leaves out |
|---|---|---|
| `sequenceDiagram` | One message per numbered step of the flow, with `autonumber` so the numbers match: the caller of the trigger, the inbound adapter, the inbound port, the aggregate, each outbound port; each side effect of the header as a note | Path, verb and status — the transport spec's facts; framework types |
| `classDiagram` | The aggregate root with the fields of its field table, each value object, and another aggregate by its id only | Composition with another aggregate; persistence and transport types |
| `stateDiagram-v2` | One state per value of a state field, one transition per method that reaches it, labelled with that method; a value nothing reaches yet carries a note naming the case that will | The rehydration path — rebuilding persisted state is not a transition |

The state diagram exists exactly when the spec records more than one value for a state field.
With no state field, there is no state diagram.

## How to verify

```bash
# Every mermaid block opens with an allowed diagram type. Zero lines is the expected result.
grep -rn -A1 '^```mermaid' docs/use-cases/ | grep -vE '```mermaid|^--$|(sequenceDiagram|classDiagram|stateDiagram-v2)$'

# No diagram file beside a spec. Zero lines is the expected result.
find docs/use-cases -type f \( -name '*.drawio' -o -name '*.excalidraw' -o -name '*.svg' -o -name '*.png' \)
```

Names against the text: review — every identifier inside a block appears in the same file
outside it.
