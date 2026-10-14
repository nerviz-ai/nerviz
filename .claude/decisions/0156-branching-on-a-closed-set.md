# 0156 · Where a branch over a closed set lives — the type, a switch expression, or a Strategy

- **Date:** 2026-10-14
- **Scenario:** "olhando o codigo do bank-app notei um uso excessivo do bloco switch (analisar
  possíveis melhorias enriquecendo enums para implementar interfaces ou criar métodos não estáticos
  para determinado valor do enum ou uso de design patterns)"
- **Decision:** Form 4 — `.claude/rules/code-quality.md` § Branching on a closed set (the sealed-switch
  bullet moved into it); `domain-modeling/templates/Enum.java.example`; the `closed-set-branching`
  migration
- **State:** approved by Lucas Fernandes, on 2026-10-14 ("aprovo 1"); implemented on the branch
  `code-quality-improvement`

## What the code showed

`bank-app` at `45fc2bb`, `src/main` only (no `switch` in `src/test`; the other 11 are in the copied
`.claude/hooks/ArchHook.java`):

| Site | Shape | Verdict |
|---|---|---|
| `RelayOutboxEventsUseCase.java:59` | exhaustive `switch` expression over sealed `OutboxSendResult` | Right place: each arm is the use case's own reaction, with its ports. Moving it into the result type puts application behavior in a value |
| `IdempotencyAspect.java:66` | exhaustive `switch` expression with record patterns over sealed `IdempotentOutcome` | Same |
| `CustomerMapper.java:40`, `CustomerPersistenceMapper.java:23` | the same **statement** `switch` over sealed `CustomerIdentity`, assigning locals declared `null` before it | The smell: a computed value written as mutation, twice. Two adapters mapping to their own shapes is not duplication — the statement form is |
| `KycVerificationCompletedListener.java:81` | `switch` over the wire `String` into `KycVerdict`, `default` throws | Right: an anti-corruption parse at the boundary, open type keeps its `default` |
| `Customer.java:87,109` | `status == CustomerStatus.KYC_IN_PROGRESS`, twice | A question about the state asked from outside it; the enum's own Javadoc states "ACTIVE and REJECTED_BY_KYC are final" in prose, and no method says it |
| `KycVerdict`, `MaskedType` | enums carrying data per constant | Already the shape the scenario asks for |

What the repository already says: `rules/code-quality.md` § SOLID (OCP: a varying case enters as a
new implementation, once the axis has varied) and § Clean Code (a `switch` over a sealed type is
exhaustive, no `default`); `rules/value-objects.md` § The criterion 3 and 4 (closed set → `enum`;
an operation that belongs to the value lives on it); `gof-design-patterns` § Catalog's Strategy
row (`switch`/`if-else` over a type or enum, repeated in ≥ 2 places). Nothing says **which of the
three** a branch over a closed set becomes, nor forbids the statement form that computes a value.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | The `CustomerIdentity` mappers and the `==` comparisons on `CustomerStatus`, plus a general impression — a criterion to prevent it | create nothing |
| 5 — Nature / scope | The choice criterion (type · switch expression · Strategy); an enum answers its own questions; a switch that computes a value is an expression; an exemplar in `domain-modeling` | — |
| 7 — Mandatoriness | Norm only — the choice is a judgment no Checkstyle or ArchUnit check makes well | Forms 7, 8; a Checkstyle module |
| 8 — Destination | New projects, and existing ones through a `migrations` entry | — (both) |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `rules/code-quality.md` § Branching on a closed set + `domain-modeling/templates/Enum.java.example` + citations in `gof-design-patterns` and `domain-modeling` + `migrations` entry | 8 | **Approved** |
| 2 | A new `rules/closed-sets.md`, the sealed-switch bullet moved into it | 6 | Rejected — a second rule on the same files, and OCP would have to cite it |
| 3 | `gof-design-patterns` catalog and exemplar only | 4 | Rejected — a norm inside a skill (invariants 1, 2); the executor never reads it |
| 4 | create nothing | 2 | Rejected — the gap is shown in `bank-app` |

### Option 1 — `rules/code-quality.md` § Branching on a closed set (score 8)

**Motivator:** axes 1 and 5 — the gap is a choice criterion over a norm `code-quality.md` already
half-owns (OCP, the sealed `switch` bullet), on the files it already loads for.

**Pros:** one owner (invariant 2): the sealed-switch bullet moves into the new section instead of
being restated, and it extends to an enum you own (a `switch` expression over it is exhaustive in
Java 21 too). The criterion: behavior that needs nothing outside the value → on the type (a field
per constant for data, a method — a constant-specific body or an interface the enum implements —
for behavior; callers ask `status.isFinal()`, never compare constants); behavior that needs a
collaborator or belongs to a layer the type must not see (an adapter's mapping, a use case's
reaction) → an exhaustive `switch` **expression** there; variants that grow, each with its own
dependencies, selected at runtime → Strategy (`gof-design-patterns`). A `switch` that computes a
value is an expression — no locals declared before it and assigned in its arms; the statement
form stays only for arms that each perform a side effect. A question about an enum asked in two
places, or a comparison against more than one constant, becomes a method of the enum. The same
`switch` in two adapters mapping to different shapes is not duplication. The exemplar gives
`domain-modeling` step 4b a shape to copy for a status enum. The migration lists the sites before
touching any.

**Cons:** `code-quality.md` grows from 83 lines by ~20; it stays the one rule for these files. The
Strategy row's "when not" changes in a skill — a citation, not a restated norm. Nothing mechanical
checks the norm (axis 7, by choice).

**Points cut in the rubric:** criterion 8 (verification) — human review, like OCP today.

**CI:** `validate` › `schema` (the rule's frontmatter and export, the skill bodies);
`TemplateCommentsTest` (the exemplar's comments are Javadoc only); `MigrationsSchemaTest` (the
migration's shape). The exemplar is a shape reference, like `ValueObject.java.example`, outside
`JavaTemplatesTest`'s scope (verbatim templates only) — it compiles with `javac` alone, proved by
hand at write time.

### Option 2 — a new `rules/closed-sets.md` (score 6)

Same content in its own file, `paths` `**/src/main/**/*.java`, the sealed-switch bullet moved out
of `code-quality.md`. Easier to find in the index; but a second rule loading on the same files,
and `code-quality.md`'s OCP line would have to cite it to say where its "new implementation"
ends. **CI:** same as option 1, plus the new `00-index.md` row through `schema`.

### Option 3 — `gof-design-patterns` only (score 4)

An enum-behavior row in the catalog and an exemplar there. A skill is read when invoked — at design
time, step 5b — so the executor writing a mapper never sees "a switch that computes a value is an
expression", which is a norm, not a pattern (invariant 1 the other way: a norm belongs in
`rules/`). **CI:** `schema` only.

### Option 4 — create nothing (score 2)

OCP and the Strategy row exist. `bank-app` shows they leave the gap: the statement form passed
review twice, and the `==` predicates match no row. **CI:** none.

## References

| Claim | Source |
|---|---|
| A sealed `switch` with no `default` is exhaustive; so is a `switch` expression over an enum | JLS 21 § 14.11.1.1, § 15.28.1 |
| The existing sealed-switch norm and OCP | `.claude/rules/code-quality.md` § SOLID, § Clean Code |
| Closed set → `enum`; own behavior lives on the value | `.claude/rules/value-objects.md` § The criterion |
| Strategy threshold: repeated in ≥ 2 places | `.claude/skills/gof-design-patterns/SKILL.md` § Catalog |
| Patterns are decided at design time, step 5b | `@.claude/decisions/0089-design-patterns-decided-at-design-time.md` |
| A norm never lives in a skill | `@CLAUDE.md` invariants 1, 2 |
| Shape references stay out of `JavaTemplatesTest` | `.claude/.ci/JavaTemplatesTest.java` header |
| A convention change reaches existing projects through `migrations` | `@.claude/decisions/0104-use-case-subpackage-per-aggregate.md` |

| `MissingSwitchDefault` skips switch expressions and pattern labels, and flags a statement over an enum with constant labels | Checkstyle `MissingSwitchDefault` documentation; the module is on in `project-bootstrap/templates/checkstyle.xml.example` |

## Found while writing

`messaging-architect/templates/OutboxRelayPublisher.java.example` relayed with a statement `switch`
over the `Status` enum, counters mutated in its arms, and no `default` — the shape the new section
forbids, and one `MissingSwitchDefault` rejects in a generated project. It now computes one
`RelayOutcome` per row with a `switch` expression and sums them (`RelayOutcome.plus`), the shape
`bank-app`'s `RelayOutboxEventsUseCase` had already reached. Every other `switch` in a skill
template already matched the section.

## Propagation

| File | Change |
|---|---|
| `.claude/rules/code-quality.md` | § Branching on a closed set: the three places, the expression form, the no-`default` bullet moved in from § Clean Code and extended to an enum you own, the enum statement that keeps a throwing `default` |
| `.claude/rules/00-index.md` | `code-quality.md`'s row |
| `.claude/skills/domain-modeling/templates/Enum.java.example` | new: a status enum answering its own questions (data per constant, a predicate), and an enum implementing an interface with a body per constant |
| `.claude/skills/domain-modeling/SKILL.md` | step 4b cites the section and the exemplar; the exemplar table row; "Thirteen exemplars" |
| `.claude/skills/gof-design-patterns/SKILL.md`, `references/pattern-catalog.md` | the Strategy row's "When not" and the State row point to the section |
| `.claude/skills/messaging-architect/templates/OutboxRelayPublisher.java.example` | § Found while writing |
| `.claude/schemas/extensions.json` | migration `closed-set-branching` |
| `docs/pt-br/14-rules.md`, `docs/en/14-rules.md` | `code-quality.md`'s role and verification |

Goes to the generated project: **yes** — the rule and the two skills through `export`; an existing
project through the migration on its next `/arch-adopt` update.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate` › `schema` | the rule's frontmatter and export; the skills' bodies; the migration's shape | green · red with the migration id `Closed_Set` (`id does not match`) |
| `validate` › `TemplateCommentsTest` | the new exemplar carries Javadoc only | green (154 exemplars) · red with a `//` line in `allowsCancellation` (`Enum.java.example:43`) |
| `validate` › `MigrationsSchemaTest`, `SubagentContextTest` | the migrations block; the pattern catalog still injected within `max_chars` | green |

The exemplar and the edited outbox template are shape references, outside `JavaTemplatesTest`.
Both were compiled by hand with `javac` (the outbox blocks against a `@Service` stub) and the enum
exemplar run once. The norm itself is held by review, by choice (axis 7).
