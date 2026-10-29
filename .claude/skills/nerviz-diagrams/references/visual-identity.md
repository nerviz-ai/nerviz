# Visual identity of the diagrams

Single owner of how every diagram under `docs/diagrams/` looks: palette, type, stroke,
corner radius, spacing. `scripts/Diagrams.java` reads the JSON block below and nothing else —
no colour, size or font is written in the Java, and no other file restates a value. Change a
value here, run `render`, commit the regenerated files.

A light background and six colours, each tied to the role a piece plays in the architecture
of `@CLAUDE.md`. A fact takes its role from `roles` by fact id first, then by fact `kind`;
a fact that matches neither is `neutral`.

| Role | Used for |
|---|---|
| `interface` | mods — the layer that draws what the hooks decide |
| `enforcement` | hooks and `settings.json` — deterministic |
| `procedure` | skills, skill classes and the steps of a skill |
| `isolation` | agents and agent classes |
| `norms` | rules, blueprints, `.mcp.json` — the leaf |
| `neutral` | history and anything unclassified |

An `orchestrator` view (`@.claude/decisions/0193-nerviz-diagrams-branches-phases-and-one-stepped-kind.md`,
which extends `0192`) draws one skill's steps from its own field lines, never from the view. It starts at
the orchestrator itself, a root box above the first step: its name, `orchestrator.self_detail` and the
territory it really has, collapsed to directories and files (`src/**` is `src/`), at most
`orchestrator.root.max_lines` lines. `derive` picks the layout. `sequence` is one column in the written
order: each step a box, a badge per gate word that stays on the step (`orchestrator.gates`), and to its
right the chain of who runs it, none when the orchestrator runs it itself (`self`) — `agent` chips in the
`isolation` palette, `skill` chips in `procedure`, `cmd` chips in `enforcement` and in
`orchestrator.mono`, each with the territory it really has under it (`["**"]` is
`orchestrator.territory_any`); the arrow into a chip is labelled by `orchestrator.tools`, and a skill that
declares `context: fork` carries `orchestrator.fork_marker`. A step that runs in several phases draws each
as a block under a caption (`orchestrator.phase`), joined by arrows; chains of one phase run in either
order and sit in a dotted box. A dashed chip is a conditional chain. `depends_on` is rows in the written
order, centred on one axis, with the dependencies drawn and no chips; an edge that skips a row runs in
the left gutter, one lane per edge (`orchestrator.routing`), and crosses no box. The handoff is a dashed
box captioned `orchestrator.handoff_caption`. What each gate word and each field line means is owned by
`step-contract.md`, not here.

The **decision** diamond (`orchestrator.decision`) is rose, a role no other piece plays: a step that runs
`self` alone and has one branch is the diamond, its name over its condition; any other step keeps its box
and a verdict diamond holds each of its conditions. The yes exit leaves the right vertex to a terminal box
(`orchestrator.terminal`): a stop or a gate takes the colours of its gate word and carries that badge, a
skip takes `terminal.skip` and a path through the right gutter back to the step it skips to; the exit
downward is the no. The legend names the badges in use, then `legend.decision` and `legend.conditional`
when the view draws one.

A dashed outline marks a conditional step. A heavier stroke (`emphasis_stroke`) marks what the view's
`emphasis` lists, in a `layers`, `classes` or `territory` view only; an orchestrator view takes none, its
badges and diamonds say what matters. Every length is a multiple of `grid`.

```json
{
  "background": "#FBFAF7",
  "ink": "#1F2933",
  "muted": "#52606D",
  "line": "#52606D",
  "palette": {
    "interface":   { "fill": "#DCEFF7", "stroke": "#2B7A9B" },
    "enforcement": { "fill": "#FBE3D6", "stroke": "#C4572A" },
    "procedure":   { "fill": "#E4F1DE", "stroke": "#4C8C3A" },
    "isolation":   { "fill": "#EBE1F5", "stroke": "#7A4FA8" },
    "norms":       { "fill": "#F7EBC4", "stroke": "#A6841A" },
    "neutral":     { "fill": "#ECECEC", "stroke": "#7A7A7A" }
  },
  "roles": {
    "layer:mods": "interface",
    "layer:hooks": "enforcement",
    "layer:settings": "enforcement",
    "layer:skills": "procedure",
    "layer:agents": "isolation",
    "layer:rules": "norms",
    "layer:blueprints": "norms",
    "layer:mcp": "norms",
    "layer:decisions": "neutral",
    "skill": "procedure",
    "skillclass": "procedure",
    "step": "procedure",
    "cmd": "enforcement",
    "handoff": "neutral",
    "agent": "isolation",
    "agentclass": "isolation"
  },
  "font": {
    "family": "Inter, system-ui, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif",
    "title": 22,
    "label": 13,
    "detail": 11,
    "caption": 12,
    "char_width": 0.6
  },
  "stroke": 1.5,
  "emphasis_stroke": 3,
  "radius": 8,
  "grid": 8,
  "margin": 32,
  "top": 96,
  "node": { "w": 208, "h": 56 },
  "gap": 24,
  "row_gap": 64,
  "lane": { "header_w": 240, "pad": 16, "cols": 4, "gap": 40 },
  "animation": { "step_seconds": 0.7, "reveal_seconds": 0.5, "branch_seconds": 0.35, "phase_seconds": 0.5, "delegate_seconds": 0.25 },
  "orchestrator": {
    "step": { "w": 320 },
    "chip": { "w": 248, "min_h": 40, "gap": 8 },
    "arrow": 88,
    "row_gap": 24,
    "badge": { "size": 24, "gap": 4 },
    "gates": {
      "ask":     { "glyph": "?", "fill": "#DCEFF7", "stroke": "#2B7A9B" },
      "confirm": { "glyph": "✓", "fill": "#F7EBC4", "stroke": "#A6841A" },
      "stop":    { "glyph": "■", "fill": "#FBE3D6", "stroke": "#C4572A" },
      "gate":    { "glyph": "!", "fill": "#EBE1F5", "stroke": "#7A4FA8" }
    },
    "fork_marker": "[fork]",
    "self_detail": "this orchestrator, on its own thread",
    "tools": { "agent": "Agent", "skill": "Skill", "skill_fork": "Skill · fork", "cmd": "Bash", "self": "" },
    "mono": "ui-monospace, 'SF Mono', Menlo, Consolas, monospace",
    "handoff_caption": "printed — never called",
    "decision": { "fill": "#F8DCE8", "stroke": "#B0366E", "stroke_width": 2, "h": 96, "h_tall": 112, "verdict_h": 80 },
    "terminal": { "w": 248, "h": 64, "gap": 64,
      "stop": { "title": "Ends the run" },
      "gate": { "title": "Blocks the run" },
      "skip": { "title": "Skips to {target}", "fill": "#E4F1DE", "stroke": "#4C8C3A" } },
    "branch_labels": { "yes": "yes", "no": "no" },
    "phase": { "gap": 24, "caption_h": 16, "parallel_caption": "in parallel, either order",
      "parallel": { "stroke": "#7A7A7A", "dash": "2 4", "pad": 8, "radius": 10 } },
    "root": { "max_lines": 2, "more": "+{n} more" },
    "territory_any": "writes anywhere in the repository",
    "legend": { "decision": "decision", "conditional": "conditional" },
    "routing": { "lane_gap": 16 }
  }
}
```
