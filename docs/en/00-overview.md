# Overview — `.claude/` architecture

## What this repository generates

Nerviz turns an empty directory into a Spring Boot project with declared
architecture, executable boundaries (hooks), and an already-installed design→code
pipeline. It doesn't compile anything itself — no `pom.xml`, no Maven tests. The
product is instruction files.

That central idea shapes everything: this repository's own `.claude/` is organized as
a Clean Architecture, with dependencies pointing in a single direction. That's not a
metaphor — it's the real rule of who may cite whom (`CLAUDE.md` § Invariants 1 and 2).

## General diagram

```mermaid
flowchart TB
    subgraph L0["Enforcement — deterministic"]
        SETTINGS["settings.json"]:::hook
        HOOK["ArchHook.jar — built from ArchHook.java\n(check · format · tests · schema · guard\naudit · compose · context · doctor · export · build)"]:::hook
    end

    subgraph L1["Procedure — skills"]
        CLAUDEMD["CLAUDE.md\n(root — index and routing)"]:::claudemd
        SK_INIT["skill: init-project"]:::skill
        SK_BOOT["skill: project-bootstrap"]:::skill
        SK_DESIGNER["skill: claude-code-architect-designer\n(designs this .claude/ — stays here)"]:::skill
        SK_AUDIT["skill: audit-usage"]:::skill
        SK_PAT["skill: gof-design-patterns"]:::skill
        SK_NF["skill: new-feature"]:::skill
        SK_UC["skill: use-case-design"]:::skill
        SK_DOM["skill: domain-modeling"]:::skill
        SK_PERS["skill: persistence-architect"]:::skill
        SK_REST["skill: rest-api-architect"]:::skill
        SK_SEC["skill: security-architect"]:::skill
        SK_HTTP["skill: http-client-architect"]:::skill
        SK_TEST["skill: test-architect"]:::skill
        SK_DOCKER["skill: docker-architect"]:::skill
        SK_MSG["skill: messaging-architect"]:::skill
        SK_JOBS["skill: jobs-architect"]:::skill
        SK_DOCTOR["skill: arch-doctor"]:::skill
        SK_GIT["skill: git-publish"]:::skill
        SK_SONAR["skill: sonarqube-setup"]:::skill
        SK_TRANSPORT["skill: transport-security-setup"]:::skill
        SK_ADOPT["skill: arch-adopt\n(installs/updates this .claude/ in a project)"]:::skill
        SK_REPORT["skill: report-issue\n(files an issue here from a project)"]:::skill
        SK_TRIAGE["skill: triage-issue\n(checks an issue at HEAD — stays here)"]:::skill
    end

    subgraph L2["Isolated execution — agents"]
        AG_INITZR["agent: project-initializer\n(model: sonnet)"]:::agent
        AG_DEV["agent: java-spring-boot-developer\n(model: sonnet, effort: high)"]:::agent
        AG_ARCH["agent: archunit-installer\n(model: sonnet, effort: medium)"]:::agent
        AG_LOG["agent: commons-logging-installer\n(model: sonnet, effort: medium)"]:::agent
        AG_COMPOSE["agent: compose-service-installer\n(model: opus, effort: medium)"]:::agent
        AG_VER["agent: issue-verifier\n(model: opus, writes nothing)"]:::agent
    end

    subgraph L3["Norms and data — leaves"]
        RULES["rules/*.md\n(architecture-ddd, naming, error-handling,\ncode-quality, api-rest, http-client, lombok,\nvalue-objects, persistence, testing,\nobservability, logging, messaging, scheduling,\nauthorization, transport-security, secrets)"]:::rule
        BLUEPRINTS["blueprints/*/*.yaml\n(_schema.md defines the contract)"]:::blueprint
    end

    CLAUDEMD -->|routes via table| SK_INIT
    CLAUDEMD -->|routes via table| SK_NF
    CLAUDEMD -->|routes via table| SK_DOCTOR
    CLAUDEMD -->|routes via table| SK_DESIGNER
    CLAUDEMD -->|routes via table| SK_AUDIT
    CLAUDEMD -->|routes via table| SK_ADOPT
    CLAUDEMD -->|routes via table| SK_TRIAGE
    SK_TRIAGE -->|Agent tool: restricted tools + model opus| AG_VER
    SK_TRIAGE -.->|prints the command on a confirmed issue| SK_DESIGNER

    SK_INIT -->|Agent tool: context + restricted tools + model sonnet| AG_INITZR
    AG_INITZR -->|follows the procedure of| SK_BOOT
    SK_BOOT -->|reads and validates| BLUEPRINTS
    SK_BOOT -->|reads and copies into the generated project| RULES
    SK_BOOT -->|installs, with guard + audit wired| SETTINGS
    SK_BOOT -->|copies verbatim| HOOK
    SK_BOOT -->|copies| SK_UC & SK_DOM & SK_PERS & SK_REST & SK_SEC & SK_HTTP & SK_TEST & SK_DOCKER & SK_MSG & SK_JOBS & SK_DOCTOR & SK_NF & SK_GIT & SK_AUDIT & SK_PAT & SK_ADOPT & SK_SONAR & SK_TRANSPORT & SK_REPORT
    SK_BOOT -.->|Skill tool, step 7.5| SK_TRANSPORT
    SK_BOOT -.->|Skill tool, step 8.4| SK_SONAR
    SK_ADOPT -.->|Skill tool, when the build file has no scanner| SK_SONAR
    SK_ADOPT -.->|Skill tool, when root CLAUDE.md has no Transport paragraph| SK_TRANSPORT
    SK_SONAR -.->|Skill tool, no existing server| SK_DOCKER
    SK_TRANSPORT -.->|Skill tool, edge local or direct topology| SK_DOCKER
    SK_ADOPT -->|writes everything through the export mode| HOOK
    SK_BOOT -->|copies| AG_DEV & AG_ARCH & AG_LOG & AG_COMPOSE
    SK_DESIGNER -.->|proposes and writes, after approval| SK_UC & AG_DEV & RULES
    HOOK -.->|context subagent at SubagentStart: the catalog, generated project only| AG_DEV
    SK_DOM & SK_PERS & SK_REST & SK_SEC & SK_HTTP & SK_MSG & SK_JOBS -.->|read § Design-time use, decide the layer's patterns| SK_PAT
    SK_NF -->|Agent tool, pre-flight, if commons is empty| AG_LOG
    SK_NF -->|"Agent tool, after the tests group, for each materialize row"| AG_COMPOSE
    AG_COMPOSE -->|Skill tool, admitted because the caller is a classed agent| SK_DOCKER
    AG_LOG -->|writes| LOGOUT["commons.logging/** + AutoConfiguration.imports"]:::out
    SK_AUDIT -->|Bash, audit summary, no model| HOOK

    SK_NF -->|Skill tool, in sequence| SK_UC
    SK_UC --> SK_DOM
    SK_DOM --> SK_PERS
    SK_DOM --> SK_REST
    SK_REST -.->|if Access asks for something the filter chain doesn't already give| SK_SEC
    SK_DOM -.->|if the port's kind is external HTTP| SK_HTTP
    SK_DOM -.->|if the event needs external delivery| SK_MSG
    SK_UC -.->|if the case names a scheduled/job trigger| SK_JOBS
    SK_MSG -.->|"if Form B (outbox + relay), or a partial defers a job"| SK_JOBS
    SK_HTTP -.->|"if § 10 asks for a reconciliation pass"| SK_JOBS
    SK_PERS -.->|the partial records the decision; never chains| SK_DOCKER
    SK_MSG -.->|the partial records the decision; never chains| SK_DOCKER
    SK_TEST -.->|records the pending service; never chains| SK_DOCKER
    SK_JOBS --> SK_PERS
    SK_SEC --> SK_PERS
    SK_HTTP --> SK_PERS
    SK_PERS --> SK_TEST
    SK_MSG --> SK_TEST
    SK_JOBS --> SK_TEST
    SK_SEC --> SK_TEST
    SK_HTTP --> SK_TEST
    SK_REST --> SK_TEST
    SK_NF -->|Agent tool, optional, after consolidating| AG_DEV
    AG_DEV -->|writes| SRC["src/** of the generated project"]:::out
    AG_INITZR -.->|Skill tool, if build green| SK_GIT
    SK_NF -.->|Skill tool, if DEV reports success| SK_GIT
    SK_GIT -->|writes| GITOUT[".git/ + remote repo (gh)"]:::out

    SK_TEST -->|Agent tool, setup mode, no argument| AG_ARCH
    AG_ARCH -->|writes| ARCHTEST["ArchitectureTest.java + JaCoCo gate"]:::out

    SK_DOCTOR -->|Bash, no model| HOOK

    SETTINGS -->|UserPromptSubmit / PreToolUse / PostToolUse / Stop / SubagentStart / SubagentStop / SessionEnd …| HOOK
    HOOK -->|blocks or warns about| SRC
    HOOK -->|audit: writes, in the generated project| TRAIL[".claude/audit-usage/*.md + history.jsonl + nodes.jsonl"]:::out

    RULES -.->|cited by path, never copied| SK_UC & SK_DOM & SK_PERS & SK_REST & SK_SEC & SK_HTTP & SK_TEST & SK_MSG & SK_JOBS
    BLUEPRINTS -.->|cited by path| SK_BOOT

    classDef hook fill:#5c1a1a,stroke:#ff6b6b,color:#fff,stroke-width:2px
    classDef skill fill:#123a5c,stroke:#5aa9e6,color:#fff,stroke-width:2px
    classDef agent fill:#3a1a5c,stroke:#b98aff,color:#fff,stroke-width:2px
    classDef rule fill:#1a4d2e,stroke:#5ad17a,color:#fff,stroke-width:2px
    classDef blueprint fill:#5c4a1a,stroke:#e6b45a,color:#fff,stroke-width:2px
    classDef claudemd fill:#333,stroke:#ccc,color:#fff,stroke-width:2px
    classDef out fill:#222,stroke:#999,color:#eee,stroke-dasharray: 4 3
```

## Legend

| Symbol/color | Type | What it means here |
|---|---|---|
| 🟥 Red | **Hook** (`settings.json` + `ArchHook.java`) | Deterministic enforcement. Runs whenever the event fires, regardless of the model's decision. See [01-file-types.md](01-file-types.md#hook) |
| 🟦 Blue | **Skill** (`SKILL.md`) | Procedure. Becomes part of the model's repertoire; can be invoked by command or automatically. See [§ Skill](01-file-types.md#skill) |
| 🟪 Purple | **Agent** (`.claude/agents/*.md`) | Isolated execution — own context, restricted tools, different model. See [§ Agent](01-file-types.md#agent-subagent) |
| 🟩 Green | **Rule** (`.claude/rules/*.md`) | Norm. A leaf of the graph — never mentions a skill, agent, or command. See [§ Rule](01-file-types.md#rule) |
| 🟧 Orange | **Blueprint** (`.claude/blueprints/*/*.yaml`) | Declarative architecture data. A leaf like rules, but in YAML, not prose. See [05-blueprints.md](05-blueprints.md) |
| ⬛ Gray | **`CLAUDE.md`** | Index and routing table. Always loads at session start. See [§ CLAUDE.md](01-file-types.md#claudemd) |
| Solid line | Active call | One piece invokes another via Skill tool, Agent tool, or direct exec |
| Dashed line | Citation/read | One piece reads or cites another by path, without invoking it |

## Why the direction of the arrows matters

This repository's structural rule (`CLAUDE.md` § Invariants 1, 4, and 6) is: **rules
never mention skills, agents, or commands**. If a rule needed to invoke something, it
would stop being a norm and become a procedure — and a procedure is a skill. That's
why, in the diagram, arrows from `rules/` and `blueprints/` are always dashed and
always point outward from whoever reads them, never the other way.

Likewise, an agent only exists for one of three valid reasons (`CLAUDE.md` § Invariant
5): preserving context, restricting tools, or switching model. `project-initializer`
and `java-spring-boot-developer` meet all three reasons at once;
`archunit-installer` and `commons-logging-installer` meet only the first (preserving
context — neither has an interview, and isolating the `curl`/`./mvnw` they run keeps
that noise from becoming permanent in the main conversation). `compose-service-installer`
meets two: it preserves context (the body of `docker-architect` stays out of the implement
thread) and it keeps the `opus` pin of that skill from leaking into the turns that follow,
`git-publish` included. One reason is already
enough under Invariant 5. Each agent documents its own reason in the `## Why this is an
agent` (or `## Why this is Form 3`) section.

The hook is the only piece outside that citation graph: `settings.json` fires it on
lifecycle events, and what it reads (`forbidden-imports.txt`, `extensions.json`) is
data, not prose.

Three modes depend on where they are registered. `guard` runs **here and in the generated
project**: it keeps every skill inside the territory its class declares in
`skill_classes`, and every subagent inside its `agent_classes` territory (deny by
default), freezes approved specs, refuses a call to a `build`-class skill while a design
run is open, reads shell commands for the write shapes it can resolve (`guard bash`) and
refuses a force push, and on `Stop` sweeps what the turn changed on disk (`guard sweep`).
`audit` runs only in the generated project, because it switches itself on by the presence
of `.claude/audit-usage/` and this meta-repo doesn't create the directory — auditing the
*design* of the tool instead of its use is not the trail anyone wants. `context subagent`
is registered only in the generated project too, at `SubagentStart`: it hands the
`gof-design-patterns` catalog to every agent whose class declares `pattern_catalog: true`.
See [08-audit-usage.md](08-audit-usage.md).

That same `skill_classes` is what standardizes each skill's **body**: `ArchHook.java
schema` requires every `SKILL.md` on disk to sit in exactly one class, declare
`**Class:** <c>` in its body, and carry the sections that class asks for. Before it,
nine skills said `## Contract`, one said `## Skill contract`, and two had no contract
section at all — with `claude plugin validate` printing `✔ Validation passed` over all
of it.

## The commands, in one line each

| Command | What it does | Details |
|---|---|---|
| `/init-project` | Interview → picks a blueprint → generates the complete Spring Boot project structure, with no business code | [02-init-project.md](02-init-project.md) |
| `/new-feature <description>` · `/new-feature UC-NNN-slug` | **Create the spec:** designs one use case per run (use case → domain → REST → messaging and jobs when they apply → persistence → tests) into a single spec, asks approval and commits it; `UC-NNN-slug` resumes a draft. Ends by printing `/clear` and `/new-feature-implement UC-NNN-slug` | [03-new-feature.md](03-new-feature.md) |
| `/new-feature-implement UC-NNN-slug` | **Implement the spec:** over an approved spec, after `/clear`, runs the pre-flight and the executor in three chained groups (domain → adapters → tests), then commits the feature | [03-new-feature.md](03-new-feature.md) |
| `/arch-doctor` | Diagnoses active hooks, loaded boundaries, Maven wrapper, `java` on PATH, schema, audit trail, compose services | [04-arch-doctor.md](04-arch-doctor.md) |
| `/audit-usage` | Reads the generated project's audit trail: spend per skill and agent across runs, failure rate, which report to open. Here it reports the trail is off | [08-audit-usage.md](08-audit-usage.md) |
| `/claude-code-architect-designer` | Decides which of the nine forms (auto-invocable skill, manual skill, agent, rule, `CLAUDE.md` section, MCP server, hook, `permissions` rule, mod — or nothing) solves a scenario, and writes the file after approval. Meta-repo only | [06-claude-code-architect-designer.md](06-claude-code-architect-designer.md) |
| `/arch-adopt` | Installs this `.claude/` into a project never generated here, or updates one that is behind. Refuses a dirty worktree, writes through the `export` mode, and leaves the diff ready for review | [10-arch-adopt.md](10-arch-adopt.md) |

`git-publish` isn't a fourth top-level command — it's a Form 1 skill (no
`disable-model-invocation`) chained automatically by `project-initializer` (end of
`/init-project`, if the build passed) and by `/new-feature` and `/new-feature-implement` (every end of the flow with an approved spec —
docs-only when flow 1 approves a spec, and the feature commit when flow 2's executor succeeds), and also directly invocable by the user. Two `AskUserQuestion` gates
in the skill's body replace the flag as the guard — the same D17 pattern
(`@.claude/decisions/0007-pipeline-skills-invocation.md`), documented in
`@.claude/decisions/0034-git-publish-skill.md`.
