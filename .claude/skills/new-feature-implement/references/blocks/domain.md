# Block 1: Domain (steps 1-7)

Read § 2 of spec.md, then the partial it cites for the step at hand. Generates:
- `[Aggregate].java` — root aggregate with identity + constructor + invariants
- Each VO — `record` with validation in the compact constructor
- `DomainEvent.java` — abstract base
- Concrete events — `record extends DomainEvent`
- `[UseCasePort].java` — input interface
- Output port interfaces (`[Resource]Repository`, `EventPublisher`)

Rules: `.claude/rules/naming.md`, `.claude/rules/code-quality.md`, `.claude/rules/lombok.md`,
`.claude/rules/value-objects.md` (when a field earns a VO vs. stays primitive),
`.claude/rules/error-handling.md` (exception taxonomy for invariant violations),
`.claude/rules/architecture-ddd.md` § Domain (zero framework, zero I/O, `Clock` injected,
past-tense events). `.claude/rules/logging.md` applies too, in the negative: this layer logs
nothing — that silence is the rule, not an oversight.

Validation: immutable, no null, invariants in the aggregate.

Compilation: `./mvnw -q -pl <domain-module> test-compile` in multi-module, `./mvnw -q test-compile` in single-module ✅

**Line in the final report:**
```
✅ Block 1: Domain complete (steps 1-7)
- Aggregate: OrderAggregate with 3 invariants
- VOs: OrderId, Email, Quantity — constructor validation
- Events: 2 (OrderCreated, OrderModified)
- Ports: 1 input, 2 output
- Files: 6 classes, 0 errors
```
