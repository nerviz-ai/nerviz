# Approval and final report — `/new-feature`

Read whole before asking for approval. The two steps — headings and field blocks — stay in
`SKILL.md`; this is their prose, moved out by
`@.claude/decisions/0196-new-feature-body-cut-quoted-globs-and-audit-effort.md`, unchanged.

## Contents

- approval · Approval — what to read once more, the question, Keep as draft, Approve and the three lines to type
- report · Final report — what the last message carries

## approval · Approval

Asked on every run that consolidated a spec. **Read the consolidated spec once more before asking.** Approval freezes the folder — after it,
the guard admits only the status moves and checklist toggles `guard.status_transitions` names,
and reopening is by hand (`@.claude/decisions/0066-spec-state-machine.md`) — so whatever is
wrong in the spec at this moment ships to the executor. Three defects real runs approved
(issues #167 and #168), fixed here, never after:

- a `§` reference to a section, step or row the spec does not have — every one resolves inside
  `UC-NNN-spec.md` or names the partial that holds it
- text addressed to the approver or to a later run instead of the executor — "confirm before
  approving", "TBD", "to be decided" — which either becomes a decision now or a stop-and-ask
- a fact about another system — an identifier it assigns, a path or header it expects, a limit or
  period it enforces, a code it answers with — that carries no source, flagged or not
  (`@.claude/rules/spec-external-facts.md`): the source is found now, or the person who owns the
  integration is asked, and a spec that cannot be sourced in this run stays a draft

Fix each in the spec while it is still `draft`, then ask.

`AskUserQuestion`: **Approve `UC-NNN-spec.md`** / **Keep as draft**.

- **Keep as draft** → report the spec path and the resume command
  (`/new-feature UC-NNN-<slug>`), and stop. No git: a draft isn't a deliverable.
- **Approve** → change the spec's line to `status: approved`. Nothing else: a design-only run
  writes nothing outside `docs/`, and the guard is what makes that true rather than intended.
  No `Skill` call to `git-publish` — the commit is the person's, at any moment, and a chained
  one ran at the design phase's context and committed before the run's audit trail existed
  (`@.claude/decisions/0182-git-publish-printed-not-chained-by-the-feature-flows.md`). The
  final report ends with the three lines to type next:

  ```
  /clear
  /git-publish docs(UC-NNN-<slug>): approved spec — paths: docs/use-cases/UC-NNN-<slug>/ docs/use-cases/BACKLOG.md
  /new-feature-implement UC-NNN-<slug>
  ```

  When the guardrail's worktree step listed pre-existing changes (`IGNORED` or paths), the
  `/git-publish` line ends with ` — pre-existing: <paths>` — the list itself, collapsed to
  directories past 20 paths — so the typed command still names what it must not mix into the
  spec's commit. `/git-publish` is optional: the person may skip it and run
  `/new-feature-implement` first, in which case the spec rides in the `feat(UC-NNN-<slug>)`
  commit.

  What used to be staged here — `docker-compose.yml` and `docker/init/**`, when the pipeline
  chained `docker-architect` — is no longer written during the run at all. Every
  `## Infrastructure` row goes into the final report: a `materialize` row is added by
  `/new-feature-implement`, and a `pending` row carries the `/docker-architect` invocation the
  user runs. An unmentioned service is how a `kafka` service went orphan once
  (`lessons-learned-010.md` § 7), and the fix is naming it, not writing the file mid-design.

## report · Final report

Last message of the run, and the only report: the spec path and status, the backlog
entries left for later (if any), **every `## Infrastructure` row of the spec** — each
compose service with its decision: `materialize` (the implement run adds it), `pending` (with
the `/docker-architect` command that adds it), `external` (with its address), or "none" —
**every approved use case this run left unreachable end to end**, with the backlog row that
restores it named by its `BL-NN` (or "none") — never a `UC` number, which a backlog row does not
have — **every item a partial deferred, with the owner it left it to** (a checklist item, or a
`BL-NN`, or "none"), **every guarantee delegated to a consumer outside this project whose
idempotency is assumed or unknown** (or "none"), and **every personal-data field that crosses
a boundary in clear, with its receiver** (or "none") — now a repeat of what § 8 and § 6 of the
partials already recorded, not the first time anyone asks — three findings a reader must not have to
reconstruct from a fixture comment or a Javadoc sentence — and the next commands. After
§ Approval those are `/clear`, the `/git-publish` line above and
`/new-feature-implement UC-NNN-<slug>`; after **Keep as draft**, `/new-feature UC-NNN-<slug>`.
