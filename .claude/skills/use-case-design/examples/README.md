# Example use-case specs

Thirteen complete `00-use-case.md` — the artifact this skill emits (§ Procedure step
7, `templates/use-case-spec.md.example`) — written as fixtures to exercise the
`/new-feature` pipeline and the `java-spring-boot-developer` executor end to end.

**Meta-repo documentation, not project content.** These don't live at
`docs/use-cases/**` because this repository isn't a Spring project (`@CLAUDE.md`: "It
is not a Java application"). The `export` manifest keeps only `SKILL.md`, `templates/`
and `references/` out of each skill, so this `examples/` folder never reaches a
generated project.

## How to use

1. Pick a row below.
2. `cp -r .claude/skills/use-case-design/examples/UC-1NN-<slug> <project>/docs/use-cases/`
3. Renumber the folder and the `Identifier` field if the target project's own `UC-NNN`
   sequence collides (`ls -1d docs/use-cases/UC-* | tail -5`).
4. Run `/new-feature UC-<NNN>-<slug>` in that project. The folder has no consolidated
   spec yet, so it counts as a draft to resume: `use-case-design` is skipped and the
   pipeline continues from `domain-modeling`.
5. Once the executor (`java-spring-boot-developer`) implements it, check whether the
   pattern in the right-hand column actually appeared. If it didn't, either the
   business rule wasn't a strong enough symptom, or the executor had a better reason —
   both are useful signal.

**None of these specs name a pattern.** Per this skill's own out-of-scope table,
"which design pattern to apply" belongs to `java-patterns`, decided from a symptom in
the code — never fixed in the parent spec. The column below is this index's
annotation, read only by whoever picks a fixture, not part of the spec file itself.

| # | Folder | Business scenario | Symptom → pattern this tends to surface |
|---|---|---|---|
| 1 | `UC-101-apply-order-discount` | Recompute an order's discount from the customer's tier | Discount % keyed by a tier that grows a few times a year → `SKILL.md` § Catalog, Strategy |
| 2 | `UC-102-request-shipping-quote` | Quote shipping cost from many optional shipment attributes | ≥ 6 optional fields with invalid combinations → `SKILL.md` § Catalog, Builder |
| 3 | `UC-103-notify-order-event` | Send a notification across a channel with retry, audit, and rate limit | Cross-cutting behavior stacked over a base send, no matching `@Cacheable`/`@Retryable` → `SKILL.md` § Catalog, Decorator |
| 4 | `UC-104-search-orders` | Search orders by an arbitrary combination of optional filters | Business predicate reused between the REST search and a report query → `SKILL.md` § Catalog, Specification |
| 5 | `UC-105-authorize-payment` | Authorize a payment through an internal ledger and an external gateway | Two technologies behind one outbound port → `SKILL.md` § Catalog, "Adapter — already the blueprint" |
| 6 | `UC-106-confirm-order` | Confirm an order, triggering reservation and notification | One aggregate change, ≥ 2 independent consumers → `SKILL.md` § Catalog, Domain Events |
| 7 | `UC-107-cancel-subscription` | Cancel a subscription with a reversible grace window | Action needs queuing, audit, retry, and undo → `pattern-catalog.md`, Command |
| 8 | `UC-108-transition-order-status` | Move an order through its lifecycle statuses | Each status allows a different set of next transitions → `pattern-catalog.md`, State |
| 9 | `UC-109-evaluate-loan-application` | Evaluate a loan application against sequential checks | Request passes through handlers until one rejects or all pass → `pattern-catalog.md`, Chain of Responsibility |
| 10 | `UC-110-export-invoice` | Export an invoice as CSV, PDF, or XLSX | Same fetch/validate/write skeleton, format-specific step | `pattern-catalog.md`, Template Method |
| 11 | `UC-111-compute-product-charges` | Compute tax, shipping eligibility, and export payload for a product | Several unrelated operations over one fixed type hierarchy → `pattern-catalog.md`, Visitor |
| 12 | `UC-112-select-payment-gateway` | Instantiate the right payment gateway client for a payment method | Concrete type returned depends on input, grows with new methods → `pattern-catalog.md`, Factory Method |
| 13 | `UC-113-settle-merchant-payout` | Settle a merchant payout, crediting an external accounting service | Event whose loss nothing downstream would ever notice → `@.claude/rules/messaging.md` § Publication timing, transactional outbox |

Row 13 is the one whose right-hand column isn't a `java-patterns` entry: what it surfaces
is a **publication form**, decided by `messaging-architect` from the rule's criterion, and
it is the only fixture that reaches step 5 of the pipeline at all. Pair it with row 6 —
`UC-106` states in its flow why publishing straight after the commit is enough there, and
`UC-113` states why nothing downstream would ever notice the same event going missing. Same
question, opposite answers, and neither spec names the form: that is the reader's check that
the criterion in `@.claude/rules/messaging.md` § Publication timing is doing the work.

## Why examples, not `templates/`

`templates/use-case-spec.md.example` is the **shape** reference — one exemplar,
cited by `SKILL.md` § Procedure step 7, the same role every other pipeline skill's
single `.example` plays. These twelve are **content** fixtures for a different
purpose — testing the pipeline and surfacing patterns — so they get their own
directory instead of diluting the one canonical shape reference.
