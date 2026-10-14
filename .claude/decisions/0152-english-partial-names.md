# 0152 · The use case partials take English names

- **Date:** 2026-10-14
- **Scenario:** "hoje as skills de design produzem arquivos com nome em português:
  caso-de-uso.md, persistencia.md, dominio.md, testes.md, seguranca.md — vamos refatorar
  apenas esses nomes para padronizar para inglês."
- **Decision:** rename the nine Portuguese partial names everywhere a skill, agent, template,
  test, norm or document cites them, plus the `migrations` entry `english-partial-names` in
  `.claude/schemas/extensions.json`. No extension form changes: same skills, same owners, same
  numbers, same order.
- **State:** approved by Lucas Fernandes, on 2026-10-14 — all nine names (not only the five the
  scenario listed), history left untouched, with a migration

## The rename

| Before | After | Owner |
|---|---|---|
| `00-caso-de-uso.md` | `00-use-case.md` | `use-case-design` |
| `10-dominio.md` | `10-domain.md` | `domain-modeling` |
| `20-persistencia.md` | `20-persistence.md` | `persistence-architect` |
| `23-busca.md` | `23-search.md` | `search-architect` |
| `25-mensageria.md` | `25-messaging.md` | `messaging-architect` |
| `28-cliente-http.md` | `28-http-client.md` | `http-client-architect` |
| `29-ia.md` | `29-ai.md` | `ai-architect` |
| `32-seguranca.md` | `32-security.md` | `security-architect` |
| `40-testes.md` | `40-tests.md` | `test-architect` |

`22-cache.md`, `30-rest.md`, `31-graphql.md` and `35-jobs.md` were already English. The numeric
prefix is unchanged, so the order a folder lists in is unchanged too. `10-domain.md`,
`20-persistence.md`, `00-use-case.md` and `40-tests.md` are the names decisions 0002–0008 first
gave these partials; the Portuguese names came later.

## Interview

| Question | Answer |
|---|---|
| The five named, or all nine Portuguese partials | All nine |
| Rewrite `.claude/decisions/` and `.claude/lessons-learned/` | No — they record the names in force when written. This record is the bridge |
| A `migrations` entry for generated projects | Yes |

## What was left with the old names, on purpose

- Every file under `.claude/decisions/` and `.claude/lessons-learned/` — history (`README.md`
  § What this is not).
- The `migrations` entry `use-case-spec-partial-status-line` — an entry is never edited after
  it ships (`migrations.$comment`). Its prompt runs against a project's own files, which still
  carry `00-caso-de-uso.md` until `english-partial-names` is applied.
- The narrative of lessons-learned-012 § 14 in `doctor.uc_references.$comment` and in the
  Javadoc of the matching `ArchHook.java` method: it quotes the file that run found.

## Generated projects

Nothing in a project finds a partial by glob; every skill and agent names the file. After an
update, a use case folder written before it looks empty to `/new-feature`, and
`compose-service-installer` hands `docker-architect` a folder with no `20-persistence.md`,
`25-messaging.md` or `40-tests.md`. `/new-feature-implement` itself reads only the consolidated
`UC-NNN-spec.md`.

The `english-partial-names` prompt renames the open folders and prints commands for the frozen
ones, which the guard refuses on purpose — the shape of `use-case-spec-partial-status-line`
(`0115`). A frozen folder already implemented may stay as history; one still `approved` must be
renamed by hand before `/new-feature-implement`.

## CI coverage

`SweepTest` and `SkillTerritoryTest` use the new names in their fixtures. `ArchHook.java schema`
checks the new `migrations` entry's fields and blueprints (`MigrationsSchemaTest`).
