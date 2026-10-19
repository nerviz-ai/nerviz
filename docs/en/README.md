# Nerviz workflow

Versão em português: [`docs/pt-br/`](../pt-br/README.md).

This folder documents how the pieces of this repository's `.claude/` collaborate to
run the main commands — `/init-project`, `/new-feature`, `/arch-doctor`,
`/audit-usage`, `/arch-adopt`, `/platform-upgrade`, `/report-issue` and `/triage-issue` — and what this project does that similar
repositories don't. A
first-time reader should start at [09-differentiators.md](09-differentiators.md).

Nerviz is not an application — it's a meta-repository. What it produces are
**instruction files** (skills, rules, agents, hooks) that, together, generate Spring
Boot projects already prepared for AI-assisted development. Understanding how these
files call each other is the prerequisite for editing any one of them without breaking
the others.

## Recommended reading order

| Document | Content |
|---|---|
| [00-overview.md](00-overview.md) | General diagram of the `.claude/` architecture, with a legend by piece type |
| [01-file-types.md](01-file-types.md) | How each type works — skill, rule, agent, `CLAUDE.md`, hook — according to the Claude Code runtime |
| [02-init-project.md](02-init-project.md) | Full `/init-project` workflow: who calls whom, invocation example, final report |
| [03-new-feature.md](03-new-feature.md) | the two flows, `/new-feature` and `/new-feature-implement`: creating the spec (design-skill pipeline, 5 fixed + messaging and jobs when they apply) and implementing it (executor in three chained groups), example, final report |
| [04-arch-doctor.md](04-arch-doctor.md) | `/arch-doctor` workflow: what it diagnoses and how to read the output |
| [05-blueprints.md](05-blueprints.md) | `_schema.md` contract, what each blueprint declares, how to create a custom blueprint |
| [06-claude-code-architect-designer.md](06-claude-code-architect-designer.md) | `/claude-code-architect-designer` workflow: the 5 phases, decision matrix, frontmatter fields, full example |
| [07-ci-validate.md](07-ci-validate.md) | `validate.yml`: why it exists, what each job/step verifies, what's still missing |
| [08-audit-usage.md](08-audit-usage.md) | Audit trail: the `audit` hook (one report per run, billable tokens and the model per piece), the `guard` hook (every skill writes only its class's territory, approved specs frozen), the `/audit-usage` skill |
| [09-differentiators.md](09-differentiators.md) | What category this is (a stack-specialized agent harness), the neighbors mapped on GitHub and in commercial tools, what is unique here and what is not — comparison table and sources |
| [10-arch-adopt.md](10-arch-adopt.md) | `/arch-adopt` workflow: installing this `.claude/` into a project never generated here, or updating one that is behind — the `export` mode, the copy manifest, the provenance stamp |
| [11-pitfalls.md](11-pitfalls.md) | Every silent trap, in two parts. The runtime's: a skill named after a native command, `$ARGUMENTS` interpolated in prose, pipe segments in `allowed-tools`, an injection's cwd, unknown frontmatter, a hook's four mute failures, `AskUserQuestion`, and the four `.mcp.json` ones. This repository's: write territories and the guards, frozen spec folders, compose reachability, who owns `pom.xml` and the outbox |
| [12-issues.md](12-issues.md) | Issues, both sides: `/report-issue` files a verifiable, sanitized issue from a generated project; `/triage-issue` has the read-only `issue-verifier` check every claim at `HEAD` — diagnosis and proposed fix included — before a comment, a label, or a design run |
| [13-github-copilot.md](13-github-copilot.md) | What a generated project gives a GitHub Copilot user, surface by surface (CLI, cloud agent, VS Code, code review): what works the same, what needs a file or frontmatter translation, what only Claude Code has, what only Copilot has, and which Nerviz guarantee still holds there. Basis of `roadmap.md` phase 8 and of the `AGENTS.md` design |
| [14-rules.md](14-rules.md) | The role of each rule in `.claude/rules/`: what only it decides, where it hands over to another, what verifies it — and a table of boundary questions (who decides the status, the log level, masking, inbound and outbound TLS). It restates no norm; it points at them |
| [15-platform-upgrade.md](15-platform-upgrade.md) | `/platform-upgrade` workflow: taking a project that builds on a Spring Boot line below the floor up to it — the `upgrade-recipe` mode and its two passes, the `test-diff` baseline, the data it reads (`doctor.platform.upgrade`), the guided residue and the context-load run, what it does not do, what CI proves |

## How this repository is organized (quick reference)

```
.claude/
├── hooks/ + settings.json   enforcement infra — deterministic (ArchHook.java → ArchHook.jar)
├── schemas/                 what the hook reads: valid fields, skill and agent classes,
│                            write territory, the export manifest
├── skills/                  procedure + exemplars
├── agents/                  isolated execution
├── rules/ + blueprints/     norms and data (leaves — call nobody)
├── .ci/                     CI tests that drive the hook (ArchHook.jar) end to end
└── decisions/ + lessons-learned/   history, versioned — outside the runtime graph
```

This is the same dependency graph described in `CLAUDE.md` § Architecture of the AI
files, applied to the `.claude/` folder itself as Clean Architecture. The documents in
this folder detail how that graph behaves across the commands above.

## What this documentation is not

It is not a copy of the rules — every `SKILL.md`, `rule`, and `agent` remains the
single source (`CLAUDE.md`, invariant 2). What's here is the **reading across
files**: the call sequence, why each piece exists in the shape it's in, and concrete
execution examples. Wherever this document cites a Claude Code runtime behavior
(frontmatter, hooks, subagents), the source is `claude-help.md`, at the root of this
repository.
