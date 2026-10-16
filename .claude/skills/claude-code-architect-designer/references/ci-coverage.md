# CI coverage — what already proves each form, and where a new check goes

Reference for the `claude-code-architect-designer` skill: interview axis 17, the CI item of
every Phase 3 option, and Phase 4 step 9. Loads only when it is invoked.

Source: `.github/workflows/validate.yml`, `.github/workflows/templates.yml`,
`.github/workflows/mods.yml`, `.github/workflows/blueprints.yml`,
`.claude/.ci/*Test.java`, `.claude/skills/project-bootstrap/templates/ci.yml.example`.
**The workflows are the owner.** Every job and step this page names is quoted from them; when
they disagree, they win and this page gets corrected. Prose tour of the same jobs, with the
decision behind each: `docs/pt-br/07-ci-validate.md`.

## Why this page exists

Two pieces shipped and CI caught up in a later review: the guard modes of `0063`–`0077` ran in
every session with zero CI until `0084`, and two template defects were caught by hand until
`0099`. Nothing in the designer's procedure asked about CI, so the question was never asked
while the person who knew what the piece claimed was still in the room.

The answer is mandatory; a new test is not. Most pieces are already covered by `schema`, and
the right CI item then is one line naming that step.

## The pipelines

| Pipeline | File | Runs | Use it for |
|---|---|---|---|
| `validate` · job `hooks-cross-platform` | `.github/workflows/validate.yml` | every push and PR, on ubuntu, macos and windows | Behavior of a hook mode. Every step spawns `java -jar .claude/hooks/ArchHook.jar`, after `build --verify` proved the jar is the source (`0084`) |
| `validate` · job `design` | same | every push and PR, ubuntu, `shell: bash` | A claim about the files of this repo. First choice: `schema` (step `frontmatter schema`), because it also runs inside every generated project; a `grep` step here only when the claim cannot be data in `extensions.json` |
| `validate` · job `export-determinism` | same | every push and PR | What the `export` mode writes into a target: reproducible tree, the jar carried, `export.retired` deleted, the exported jar's own `schema` and `doctor` |
| `validate` · job `exemplar-imports` | same | every push and PR, network | Every `import` of a `.java.example` exists in a JAR of a real Initializr project |
| `templates` | `.github/workflows/templates.yml` | path-filtered, network | Anything that needs network or a Maven build: Checkstyle configs against fixtures, templates copied as files compiled and their tests run (`0099`). Not a required check — it stays pending on PRs it skips |
| `blueprints` | `.github/workflows/blueprints.yml` | path-filtered (a blueprint, `project-bootstrap`, the hook, `extensions.json`), then narrowed by its `plan` job to the blueprints the change impacts; network, ubuntu | A claim only a generated project can prove: a blueprint's reactor builds on Maven and Gradle, its boundary map blocks what it says. One job per blueprint × build tool (`BlueprintSkeletonTest.java run`); `selftest` holds the selection rule in `validate`. Not a required check (`0166`) |
| `mods` | `.github/workflows/mods.yml` | path-filtered (`.claude/mods/**`, `extensions.json`), ubuntu | What only the Claude CLI can prove about a mod: `claude plugin validate` reads it as the engine will (and the job fails on `gating hook without .catch`), `claude plugin test` runs its `*.test.ts`. The CLI comes from npm at `mods.ci_version` — the only install outside java/git/curl in this repository's CI, and nothing a user runs (`0131`). Not a required check |
| Generated project · `build` | `project-bootstrap/templates/ci.yml.example` (+ `ci-gradle.yml.example`) | every push and PR **of the generated project** | Only what exists inside the project: `./mvnw verify`, the IT-ran check, `ArchHook.java doctor gate` (fails on the lines `doctor.gate.labels` lists — `0128`). Nothing under `.claude/.ci/` travels (`export` does not copy it) |

## Form → what already covers it → when a new check is needed

| Form | Already covered by | New check needed when | Where it goes |
|---|---|---|---|
| 1, 2 — skill | `design` › `frontmatter schema`: native fields, class in `skill_classes`, the class's required sections, model allowed by the class, injections from `${CLAUDE_PROJECT_DIR}`, no native-command folder name, `export` include/exclude. `design` › `exemplars have the .example suffix at the end`; `issue forms cited by skills exist`. `exemplar-imports` for its `.java.example` imports | A new class, or a `write_allow` / `blocked_during_design` change → a case in `SkillTerritoryTest`. A template copied **as a file** into a project → its path in `JavaTemplatesTest` (or a sibling) and in both path lists of `templates.yml`. A compose service template → already merged by `design` › `compose service templates merge and parse`. A config the project runs (Checkstyle-like) → a fixture test in `templates.yml` | `.claude/.ci/SkillTerritoryTest.java` · `templates.yml` |
| 3 — agent | `frontmatter schema`: class in `agent_classes`, required `model` and `tools`, `**Executor:**` and `pattern_catalog` markers both ways, `export` entry | A new agent, a new class, or a territory change → a case in `AgentTerritoryTest` (inside and outside its `write_allow`). A `pattern_catalog` change → a case in `SubagentContextTest` | `.claude/.ci/AgentTerritoryTest.java` · `SubagentContextTest.java` |
| 4 — rule | `frontmatter schema`: `paths` required, `export.derived_paths` for a package territory. `design` › `rules is a leaf of the graph`, `norm contains no code boilerplate`, `every norm paths matches some blueprint`, `no hardcoded Spring/Java version outside decisions/` | The rule owns a phrase another file must never restate → add that phrase to the term list of `design` › `each norm has a single owner`. Otherwise nothing | `validate.yml` › `design` |
| 5 — `CLAUDE.md` section | `no hardcoded Spring/Java version outside decisions/` | Rarely. A routing row pointing at a path is not checked by anything — say so in the record rather than inventing a step for one line | — |
| 6a — `.mcp.json` | `frontmatter schema`: server fields from `extensions.json`, the secret scan over `headers`/`env` (invariant 11). When axis 13 = "both", `export-determinism` carries the project template | Nothing, unless the server is reached by a hook mode — then that mode's test | — |
| 6b — `mcpServers` of an agent | `frontmatter schema` on the agent file | Nothing beyond Form 3's answer | — |
| 7a, 7b — hook registration | `frontmatter schema`: event names, entry fields, shell form with no `args` and the ArchHook command shape, `matcher` only where the event reads one — in `.claude/settings.json` **and** in `project-bootstrap/templates/settings.json.example`. `hooks-cross-platform` › `doctor`, and `every hook registration runs the same under Claude Code and the Copilot CLI` (`HookShellFormTest`: each ArchHook registration as exec, Claude shell and Copilot shell form, exit 0 or 2) | The registration changes **what is blocked or allowed** by an existing mode (a new `if`, a new data entry the mode reads) → a case in that mode's test. A write mode must read another runtime's payload shape → a case in `ApplyPatchTest` (`0169`), from a real capture | The mode's `.claude/.ci/<Mode>Test.java` |
| 7c — new `ArchHook.java` mode | `hooks-cross-platform` › `committed ArchHook.jar is what the source compiles to` (the jar), nothing else | **Always.** A mode that throws exits 0 through the top-level catch and looks like it passed — no test, no proof. One new `<Mode>Test.java`, both directions: the input it must block or report, and the input it must let through | New `.claude/.ci/<Mode>Test.java` + a step in `hooks-cross-platform` |
| 8 — `permissions` | Nothing. The runtime evaluates a permission rule; there is no offline matcher to run in CI | Not testable in CI — record that, and name the pitfall entry in `docs/*/11-pitfalls.md` that tells a reader the tool refuses on purpose | — |
| 9 — mod | `hooks-cross-platform` › `schema blocks a mod that would load half-way` (`ModsSchemaTest`): marketplace and `enabledPlugins` entries, manifest, one hooks module, tests present, known events, `.catch` on every `gating_events` hook, no `forbidden_calls`, only `process_allow` programs, `deny_markers` still in the source. `mods.yml` › validate and test, every mod on disk | Always the mod's own `tests/*.test.ts` — one per claim, stubbing what `ArchHook.jar` prints, green then red once. A read-only mode the mod calls is Form 7c: its own `<Mode>Test.java` (precedent: `GuardStatusTest`), since the mod's tests stub it | `.claude/mods/<name>/tests/` · `.claude/.ci/<Mode>Test.java` + a step in `hooks-cross-platform` |
| Create nothing | — | — | — |

**Axis 8 = "both".** Whatever the generated project receives runs there through its own
`ci.yml.example`: `doctor gate` already fails on a broken hook registration and a broken `schema`. A new
step in that template is warranted only for a claim about the **project's** code that `verify`
does not already fail on (precedent: `integration tests actually ran`). Write it in
`ci.yml.example` and `ci-gradle.yml.example` identically.

## Writing a new test

- **Shape.** A single-file Java program in `.claude/.ci/`, `public static void main`, exit
  non-zero when any case fails, naming each failed case. `java`, `git` and `curl` only —
  `@CLAUDE.md` § Dependencies. Copy the structure of the closest neighbour: `BashGuardTest`
  (table of inputs and expected exits), `SweepTest` (throwaway git repo), `AuditRenderTest`
  (throwaway project with a copy of the real `extensions.json`).
- **What it runs.** A hook mode through `java -jar .claude/hooks/ArchHook.jar`, never
  `ArchHook.java` — the jar is what the registrations launch (`0084`).
- **Where it is registered.** One step per test, in the job of the table above, with a comment
  naming the failure it guards against and the decision that bought it — the convention every
  step of `hooks-cross-platform` follows. In `templates.yml`, the test file and every path it
  reads go in **both** `paths` lists (`push` and `pull_request`); there is no anchor.
- **Proof.** Green on the current tree, then red once with the defect injected, failing by
  name. Record both runs in the decision's `## CI coverage` (`0099` § Verified before
  committing). A test that never failed proves nothing.
- **Docs.** The new step gets a row in `docs/pt-br/07-ci-validate.md` and
  `docs/en/07-ci-validate.md` (§ What each check covers, and the job diagram).
