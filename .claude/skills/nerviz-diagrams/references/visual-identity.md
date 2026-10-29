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
| `procedure` | skills, skill classes and the pipeline steps |
| `isolation` | agents and agent classes |
| `norms` | rules, blueprints, `.mcp.json` — the leaf |
| `neutral` | history and anything unclassified |

An `orchestrator` view (`@.claude/decisions/0192-nerviz-diagrams-draws-the-orchestrators.md`) draws
one row per step: the step, a badge per gate word (`orchestrator.gates`), and to its right the chain of
who runs it — `agent` chips in the `isolation` palette, `skill` chips in `procedure`, `cmd` chips in
`enforcement` and in `orchestrator.mono`, each with the territory it really has under it; the arrow
into a chip is labelled by `orchestrator.tools`, and a skill that declares `context: fork` carries
`orchestrator.fork_marker`. The handoff is a dashed box captioned `orchestrator.handoff_caption`.
What each gate word means is owned by `step-contract.md`, not here.

A dashed outline marks a conditional step. A heavier stroke marks what the view's `emphasis`
lists. Every length is a multiple of `grid`.

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
  "animation": { "step_seconds": 0.7, "reveal_seconds": 0.5 },
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
    "handoff_caption": "printed — never called"
  }
}
```
