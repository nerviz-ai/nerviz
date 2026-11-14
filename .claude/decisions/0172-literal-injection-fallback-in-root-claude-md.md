# 0172 · A skill injection that reaches the model as text is run once, by one line in the generated root `CLAUDE.md`

- **Date:** 2026-10-17
- **Scenario:** "No Copilot CLI, o corpo de /arch-doctor chega com as injeções !`…` literais, e o modelo
  roda os comandos via bash. Isso custa tool calls, passa pelos hooks de Bash e, numa sessão, rodou cada
  comando duas vezes. Faça um inventário de toda injeção !`…` em .claude/skills/ que trava a skill quando
  não é expandida. Decida se cada skill ganha uma linha de fallback no corpo [...] ou se fica como está"
  (lessons-learned-022 § 5, B2).
- **Decision:** Form 5 of the generated project — one bullet in `## Under another agent` of
  `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example`. No skill changes.
- **State:** approved by Lucas Fernandes, on 2026-10-17. Unverified under Copilot until step B2′ of
  `lessons-learned-022/15-roteiro-teste-copilot.md` runs.

## Reproduced on disk

| Claim | Where it holds, at `59c46f3` |
|---|---|
| Under Copilot CLI 1.0.98 the `/arch-doctor` body arrived with both injections literal; the model ran `doctor` and `find` itself through `bash`, with its own descriptions, and the report it wrote was correct | `lessons-learned-022/evidence/B2-copilot-cli.txt` |
| In session `bfbb77e7` the `/arch-doctor` typed by the user also became a `skill` tool call, and both commands ran twice, 30 s apart. Whether the body was loaded twice is not confirmed | same file |
| 23 injections in 18 `SKILL.md`: 16 skills travel to the generated project (`export.skills.include`), 2 stay here (`init-project`, `claude-code-architect-designer`) | `grep -rn '^!`' .claude/skills`; `.claude/schemas/extensions.json` `export.skills` |
| `docs/pt-br/13-github-copilot.md` § 5 counts "14 skills, 2 a 4 por arquivo" | line 243 |
| No hook can run an injection in the runtime's place: the body reaches the model before any tool event | `claude-help.md` § Dynamic context injection; `docs/pt-br/13-github-copilot.md` § 2.3 |
| The generator stays Claude Code only | `roadmap.md` § Multi-agent; `docs/pt-br/13-github-copilot.md` § Escopo |

### Inventory — what each injection does when it reaches the model as text and is not run

| Group | Skills (injections) | Effect |
|---|---|---|
| A · the output **is** the skill | `arch-doctor` (2), `audit-usage` (1) | Nothing to report until the command runs. Observed: the model runs it, at a tool call each, and once twice |
| B · the output **decides** a step | `use-case-design` (1) — "`NNN` is the highest existing `UC-NNN` plus one, read from the injection at the top"; `docker-architect` (1) — the three-row table of § entry rule is keyed on the block's text | A guess numbers a duplicate `UC-NNN`; a command text matches no row of the entry-rule table |
| C · the output is a convenience | `domain-modeling`, `rest-api-architect`, `graphql-api-architect`, `security-architect`, `http-client-architect`, `ai-architect`, `messaging-architect`, `jobs-architect`, `persistence-architect`, `cache-architect`, `search-architect`, `test-architect` (1 each) | Low: the target comes from `$ARGUMENTS`, and each skill already stops without `00-use-case.md` in it. Only the "Empty above" hint goes unread |
| D · meta-repository only | `init-project` (2), `claude-code-architect-designer` (4) | Out of scope: these never run under Copilot |

None of them *blocks* outright: the observed model runs a command it sees. What the scenario costs is
the tool calls, the second run, and — in group B — a guess where a command was not run.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 2 — trigger | A skill body read under the Copilot CLI | — |
| 7 — mandatoriness | Cannot be a guarantee: the body reaches the model before any hook event, and no hook rewrites it | 7, 8 |
| 8 — destination | The generated project; group D never runs under Copilot | Any change to `init-project` and this skill |
| 9 — integration | `## Under another agent` of `root.CLAUDE.md.example` already holds the runtime-specific bullets (manual skills, `0171`; no user under the cloud agent). Command 8 of lessons-learned-022 rewrites that section last; the user approved writing the bullet now, for command 8 to keep | — |
| 17 — CI | The CI runs no Copilot. The user chose a manual retest, B2′, in `15-roteiro-teste-copilot.md`, with the decision unverified until it runs | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | one bullet in `## Under another agent` of `project-bootstrap/templates/root.CLAUDE.md.example` | 9 | **Approved** |
| 2 | option 1, plus a fourth row in `docker-architect`'s entry-rule table and a clause in `use-case-design`'s numbering step | 7 | Rejected — the same rule in three files; the bullet already names both skills |
| 3 | create nothing; record the fact in `13-github-copilot.md` only | 5 | Rejected — leaves the double run and the group-B guess unaddressed |
| 4 | a fallback line after every injection, in the 16 skills that travel | 5 | Rejected — one sentence in 16 files, read by every Claude Code run |

### Option 1 — bullet in the generated root `CLAUDE.md` (score 9)

**Motivator:** axis 9. Every surface reads the root `CLAUDE.md` (`13-github-copilot.md` § 7, row "Contexto
raiz"; decision `0139`), and the section already says how to read `.claude/` under another agent.

**Pros:** one owner for the rule, read before any skill opens; covers all 16 skills, the ones added later
too, with no edit to a skill; says "once" and "not again in the same invocation", which is what the
double run of B2 needs whatever its cause; names the two group-B skills so a guess is refused where it
costs.

**Cons:** three lines in a file every Claude Code session of the project loads, for a runtime most of
them will never use; persuasion, like the rest of the section.

**Points cut in the rubric:** 3 (context cost) → about half a point, three always-loaded lines for one
runtime. Rounded to 9.

**CI:** nothing testable, because the effect is a model's behaviour under the Copilot CLI, which no
workflow runs. The proof is manual: step B2′ of `15-roteiro-teste-copilot.md`.

### Option 2 — option 1 plus local text in the two group-B skills (score 7)

**Pros:** the instruction sits where the block is read; `docker-architect`'s table already lists the
states its block may show (`could not look`, `no docker-compose.yml`, `declares no service`), and "the
command itself" is a fourth state of the same kind.

**Cons:** the same rule in three files (invariant 2 strained): the bullet, the row, the clause. Every
Claude Code run of both skills reads a row that never applies there.

**Points cut:** 2 (invariant 2 strained), 3 (context in two skills), 5 (three places to keep in step).

**CI:** `validate` › `frontmatter schema` holds the skills' sections; nothing tests the behaviour.

### Option 3 — create nothing (score 5)

**Pros:** zero cost. B2's report came out right without any instruction.

**Cons:** leaves the double run and the group-B guess as they are; the generated project says nothing
about a body that arrives with commands in it.

**Points cut:** 1 (the scenario has a cheap persuasion answer), 4 (no guidance at all), 7, 8.

### Option 4 — a fallback line after every injection (score 5)

**Pros:** local to each block; does not depend on the root `CLAUDE.md` being read.

**Cons:** one sentence in 16 files (invariant 2 strained), read by every Claude Code run of 16 skills;
a new design skill must remember to copy it, or `schema` must grow a check for prose.

**Points cut:** 2, 3, 5, 6 (no precedent for a per-skill runtime note), 7.

**Rejected outright, not scored as an option:** replacing each injection by an explicit "step 0: run X".
It costs a tool call in every Claude Code run and turns the pre-model state of `0051` into persuasion in
the runtime where it works today.

## References

| Claim | Source |
|---|---|
| An injection runs before the content reaches the model, once, and a failure aborts the invocation | `claude-help.md` § Dynamic context injection |
| No `!` injection under Copilot; the body arrives literal | `docs/pt-br/13-github-copilot.md` § 2.3 |
| The root `CLAUDE.md` is read by every surface | `docs/pt-br/13-github-copilot.md` § 7; `0139` |
| Runtime-specific guidance lives in `## Under another agent` | `project-bootstrap/templates/root.CLAUDE.md.example`; `0171` |
| A norm in two places is a bug | `CLAUDE.md` invariant 2 |
| Injections read from `${CLAUDE_PROJECT_DIR` | `extensions.json` `injections`; `0051` |
| Under the original exec-form hooks the model's `bash` calls would have been denied | lessons-learned-022 `resultados.pt-br.md` § Achados 7; `0168` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | Bullet in `## Under another agent`: a body line starting with `` !` `` that arrives as text is run once with Bash, never again in the same invocation, and never guessed; names `use-case-design` and `docker-architect` |
| `docs/pt-br/13-github-copilot.md`, `docs/en/13-github-copilot.md` | § 2.3: the B2 observation. § 5: the count (16 skills that travel, 1 or 2 per file, plus 2 generator-only) and what a guess corrupts. § 7: the row becomes "guided", citing this record |
| `.claude/lessons-learned/lessons-learned-022/15-roteiro-teste-copilot.md` | Step B2′, the manual retest of this decision |

Goes to the generated project: **yes** — `project-bootstrap` renders the root `CLAUDE.md` from the
template at generation. A project generated earlier gets it only by regenerating that file by hand:
`arch-adopt` writes `.claude/` and the bounded-context line, not this section. Command 8 of
lessons-learned-022, which rewrites the section last, keeps the bullet.

## CI coverage

Nothing testable in CI: the effect is a model's behaviour under the Copilot CLI, which no workflow
runs, and no test reads `root.CLAUDE.md.example`. `validate` › `frontmatter schema` stays green on the
tree (`ArchHook.java schema` exit 0; `claude plugin validate .claude/skills` passed). The proof is
manual, step B2′ of `15-roteiro-teste-copilot.md`; until it runs, the State line above says unverified.
