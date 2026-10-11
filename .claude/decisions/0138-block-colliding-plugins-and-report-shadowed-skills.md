# 0138 · Block colliding plugins by data, and report a personal skill that shadows a project one

- **Date:** 2026-10-11
- **Scenario:** Pacote de integrações do H1: enabledPlugins false para superpowers e
  mattpocock-skills (no meta-repo e no template do projeto gerado), Context7 como Forma 6a nos
  dois .mcp.json, e jdtls-lsp com uma linha no doctor quando o binário jdtls faltar. Narrowed
  during the interview to the block alone, then widened to the skills that collide without a
  plugin
- **Decision:** Form 8 (`enabledPlugins: false`) in `.claude/settings.json` and
  `project-bootstrap/templates/settings.json.example`, its list owned by
  `settings.blocked_plugins` in `.claude/schemas/extensions.json` and enforced by `schema`;
  plus a `Skill shadowing` line in `ArchHook.java doctor`, read from `doctor.skill_shadowing`
- **State:** approved by Lucas Fernandes, on 2026-10-11

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Concrete symptom | None observed. The input is a survey of marketplace plugins done outside this repository on 2026-10-05 | Weighs against every option; anti-pattern 9 kept "create nothing" in the table at 8 |
| 2 — Trigger | Session start: `enabledPlugins` is read once. A personal skill is resolved on every `/command` and every automatic invocation | Forms 1–5 — nothing here is text the model reads |
| 7 — Mandatoriness | A plugin block must hold whatever the model decides | Persuasion forms for the block |
| 8 — Destination | Both: the collision that matters is in the generated project, where `domain-modeling` and `/new-feature` run | A meta-repo-only block |
| 9 — Integration | `superpowers` competes with `use-case-design` → partials → executor; `mattpocock-skills` ships `domain-modeling` with our trigger, plus `to-spec`, `implement-spec`, `tdd`. `feature-dev` and `commit-commands` start from a typed command | Blocking command-triggered plugins |
| 10 — Cost of getting it wrong | Block: minutes. No block: a partial written outside rules/, found in review or after a whole `/new-feature` | Tie-break between the block and "create nothing" |
| 17 — CI coverage | `schema` already runs over both settings files; a `doctor` line is runner state | — |

Context7 (Form 6a) and `jdtls-lsp` left the scope once axis 1 came back empty: an MCP server
with no observed use is anti-pattern 13, and the LSP's cost (a binary outside `java`/`git`/`curl`,
files written outside any tool that `guard sweep` would see in this repository, whose
`.gitignore` lacks `.settings/` and `.factorypath`) buys a gain nobody has measured. Both stay
on the roadmap until a run shows the failure each one prevents.

## Reproduced on disk

| Claim | Evidence |
|---|---|
| A personal skill wins over a project skill of the same name | code.claude.com/docs/en/skills, "Resolve skills that share a name": "Enterprise over personal, and personal over project. With `deploy` in both `~/.claude/skills/` and the project's `.claude/skills/`, `/deploy` runs the personal one" |
| A plugin skill cannot shadow, it is namespaced | same table: "Both load, because plugin skills are namespaced as `/plugin-name:skill-name`" |
| Both plugins are in the default marketplace | `claude-plugins-official` `marketplace.json`, read 2026-10-11: `superpowers` and `mattpocock-skills`, each pinned to a sha |
| The generated project had no `enabledPlugins` at all | `project-bootstrap/templates/settings.json.example` at `eaa5f7c` |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| A | Block by data: `settings.blocked_plugins`, `false` in both settings files, `schema` check, `PluginBlockTest` | 8 | Absorbed into B |
| B | A + `doctor` line naming a personal skill that shadows a project skill | 7 | **Approved** |
| C | The `false` lines alone, no list, no check | 7 | Rejected — the same list in two files with no owner (invariant 2), and nothing testable |
| D | Create nothing | 8 | Rejected — tied with A; axis 10 breaks the tie: the block costs minutes, the miss costs a run |

### Option B — block by data, and report shadowing (score 7)

**Motivator:** axes 7 and 8 for the block; the documented skill precedence for the line. B is the
only option that reaches the collision that *replaces* a skill instead of competing with it.

**Pros:**
- `settings.blocked_plugins` is the single owner of the list; `schema` fails by name on a
  missing or `true` entry in either settings file, here and inside every generated project,
  where `doctor gate` turns that into a red `Schema` and `Hooks` line.
- The personal directory is outside the project, so no guarantee reaches it (§ 2.2); `doctor`
  is where a person already reads the setup. The line follows `CLAUDE_CONFIG_DIR`.
- Runner state, never in `doctor.gate.labels`: a CI runner has no personal skills, and a
  developer's home is not the project.

**Cons:**
- Built on anticipation (axis 1).
- A block by id misses the same plugin from another marketplace; a project still re-enables one
  in its untracked `settings.local.json` — a default, not a prohibition.
- Java in two places of `ArchHook.java`, a jar rebuild and two tests.
- The line reads only `skills/`: a personal `commands/<name>.md` and an enterprise-managed skill
  are not compared.

**Points cut in the rubric:** 1 form fit (no observed failure) · 5 maintenance (two Java changes,
two tests) · 6 precedent (no `doctor` line read the personal directory before).

**CI:** two new steps in `validate · hooks-cross-platform`, see § CI coverage.

### Option A — block by data only (score 8)

B without the `doctor` line. Loses 1 (anticipation) and half of 5. Leaves the personal-skill
shadowing — the collision with no warning at all — unreported.

### Option C — `false` lines only (score 7)

Loses 1, 2 (the list lives in two files with nothing comparing them) and 7 (Form 8 has no offline
matcher, so nothing is testable).

### Option D — create nothing (score 8)

The matrix answer with no observed symptom. Loses 1 (axis 7 points at Form 8) and 4 (persuasion
where a guarantee is available).

## References

| Claim | Source |
|---|---|
| A project-level `false` keeps a user-level plugin off in the project | precedent `"skill-creator@claude-plugins-official": false` in `.claude/settings.json` |
| Skill name precedence, plugin namespacing | code.claude.com/docs/en/skills, read 2026-10-11 |
| A list written in two files is a bug | `@CLAUDE.md` invariant 2 |
| A runner-state `doctor` line never gates | `@.claude/decisions/0128-doctor-gate-fails-the-generated-boundaries-job.md` |
| The mode reads its lists from `extensions.json` | `@CLAUDE.md` invariant 10 |
| Form 8 has no offline matcher in CI | `references/ci-coverage.md`, row 8 |

## Propagation

| File | Change |
|---|---|
| `.claude/schemas/extensions.json` | `settings.blocked_plugins`; `doctor.skill_shadowing` (`config_dir_env`, `config_dir_default`, `skills_subdir`) |
| `.claude/settings.json` | `superpowers` and `mattpocock-skills` set to `false` |
| `.claude/skills/project-bootstrap/templates/settings.json.example` | `enabledPlugins` block with both `false`; `_comment` sentence on why and where to re-enable |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `checkSettings` checks the list; `doctorShadowedSkills()` prints `Skill shadowing` |
| `.claude/.ci/PluginBlockTest.java` | new |
| `.claude/.ci/SkillShadowTest.java` | new |
| `.claude/.ci/DoctorGateTest.java` | the healthy fixture's `settings.json` carries the list, read from `extensions.json` |
| `.github/workflows/validate.yml` | two steps in `hooks-cross-platform` |
| `CLAUDE.md` | routing row |
| `README.md` | two silent-failure rows; `schema` and `doctor` rows of the mode table |
| `docs/{pt-br,en}/04-arch-doctor.md` | `Hooks` row; new `Skill shadowing` row |
| `docs/{pt-br,en}/07-ci-validate.md` | diagram nodes, two table rows, local commands |
| `docs/{pt-br,en}/11-pitfalls.md` | part 1 § Skills: shadowing, and a plugin blocked on purpose |

Goes to the generated project: **yes** — `export` copies `settings.json.example` as
`.claude/settings.json`, and `ArchHook.java`, the jar and `extensions.json` whole. A project
already generated receives the block on its next `/arch-adopt` update, which overwrites
`.claude/settings.json`.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · hooks-cross-platform › schema blocks a settings file that re-enables a colliding plugin` | `PluginBlockTest`: shipped files pass; a missing or `true` entry in either file exits 2 naming file and id; an id added to the list is required in both; an empty list requires nothing | green on the tree · red with the check in `checkSettings` disabled: 4 of 6 cases failed by name |
| `validate · hooks-cross-platform › doctor names a personal skill that shadows a project skill, and never gates` | `SkillShadowTest`: a colliding personal skill is named, a distinct one and a folder with no `SKILL.md` are not; ✅ with no collision; ⚪ with no directory; `doctor gate` never names the line | green on the tree · red with the name filter disabled: 2 of 4 cases failed by name |
| `validate · hooks-cross-platform › doctor gate fails on every gated line and only on those` | `DoctorGateTest`, fixture updated so its healthy `settings.json` satisfies the new check | green |
| `validate · design › frontmatter schema` | `schema` over the real settings files | green |
