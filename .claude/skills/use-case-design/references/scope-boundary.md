# Use case boundary — test, vocabulary, and exemplars

Reference for the `use-case-design` skill. Loads only when it's invoked.

---

## 1. The boundary test

Count **observable side effects** in the request. An effect is anything the outside
world notices after execution:

| Counts as an effect | Doesn't count |
|---|---|
| `INSERT`, `UPDATE`, `DELETE` on a table | Read `SELECT` for validation |
| Publishing to a queue or topic | Reading from a queue (that's the trigger, not the effect) |
| `POST`/`PUT` call to an external service | `GET` call to an external service to enrich data |
| Sending email, SMS, push | Writing a log or a metric |
| Writing a file, an object in S3 | Reading a configuration file |

**Rule: one effect = one use case.**

Two or more effects only stay together if **both** conditions hold:

1. They share the same transaction — failing the second has to undo the first.
2. Failing the second makes the first invalid from the domain's point of view.

Email, push, queue publication, and external service calls **never** meet condition 1:
they aren't transactional with the database. So they always split off into another use
case, connected by a domain event.

### The one exception: a broker publication the case cannot afford to lose

`@.claude/rules/messaging.md` § Publication timing has two forms, and the sentence above
is true of only one of them.

- **Form A — publish after commit.** The send is outside the transaction. The rule above
  applies unchanged: publication is a second effect and splits.
- **Form B — transactional outbox.** What the use case does is **insert a row**, in the
  same transaction as the state change. That meets condition 1 literally, and condition 2
  as well — a state change whose intent-to-publish was lost is invalid from the domain's
  point of view, which is the whole reason Form B exists. So the append does **not** split
  the use case: it is part of it, and the relay that later sends the row is
  infrastructure, not a use case.

**Which form, decided here and not later.** The technical choice belongs to
`messaging-architect`, but the boundary needs the answer now, so apply Form B's
**business** criteria — they are knowable at boundary time and none of them is technical:

| Ask | Form B when |
|---|---|
| Does losing the announcement corrupt state a human then has to reconcile? | money moved, an external ledger, a regulatory record |
| Is the consumer external, with an effect that is irreversible once it runs? | a payment, a shipment, a notification to a third party |
| Would nobody notice the loss until the reconciliation? | there is no periodic sweep that would catch it |

Any yes → the case includes the append, and `00-use-case.md` records "publication:
Form B (outbox), append inside the transaction" as a boundary fact. All no → Form A, and
the publication splits like any other external effect.

**What never merges, in either form:** the **consumer**. Reading the event is a trigger,
not an effect (see the table above), so the reactive case is always its own `UC-NNN`.

### Applying it to the original request's examples

| Request | Effects | Verdict |
|---|---|---|
| REST endpoint creates a user and saves it to the database | 1 — `INSERT users` | One use case |
| REST endpoint creates a user, saves it to the database, **and sends an email** | 2 — `INSERT users` + sending an email | Two. The email isn't transactional with the `INSERT`; if SMTP fails, the user is still created and valid |

Proposed split for the second one:

```
UC-001  POST /v1/users → INSERT users → publishes UserCreated   (synchronous, returns 201)
UC-002  consumes UserCreated → sends welcome email                (reactive, no response)
```

If the user insists on keeping them together, the spec ends up with a single use case
**and an explicit line** stating that sending the email is inside the HTTP
transaction and that an SMTP failure returns 5xx with the user already saved. Written
down, not hidden.

### Cases that look like two and are one

| Request | Effects | Why it's one |
|---|---|---|
| saves the order and its items | 1 | Same transaction, and an order with no items is invalid in the domain — conditions 1 and 2 are met |
| debits the source account and credits the destination | 1 | Transfer. A half-debit isn't a valid state |
| saves and returns the created resource | 1 | Returning isn't a side effect |
| reads stock, validates, and saves the order | 1 | The `SELECT` doesn't count |

### Cases that look like one and are two

| Request | Effects | Split |
|---|---|---|
| create a user and sync with the external CRM | 2 | `INSERT` synchronous; `POST` to the CRM reacts to an event, with its own retry |
| approve a request and generate a PDF in S3 | 2 | Approval synchronous; document generation reactive |
| import a CSV and notify each invalid line | 2 | Import is one use case; notification is another, triggered by an event |

---

## 2. Vocabulary — what the skill accepts

The skill needs a **concrete trigger** and a **concrete destination**. Both or neither.

| Element | Accepted forms |
|---|---|
| Trigger | HTTP endpoint with verb and path · topic/queue consumer with a name · scheduled job with a period · CLI command |
| Payload | named fields, or the already-existing DTO/message |
| Destination | table with a name · topic with a name · external service with an endpoint |
| Response | HTTP status and body · ack · nothing (fire and forget) |

### Rejected — and what to say

| Request | What's missing | Skill's response |
|---|---|---|
| "when a new user is created in the marketing area, notify the manager" | Technical trigger (event? polling? trigger?), destination (email? push? table?), and "marketing area" isn't a technical concept | Ask: what's the trigger — endpoint, domain event, or job? Where does "area" live? What's the notification channel? |
| "improve the customer registry" | Everything | Ask for a trigger and an effect |
| "create the onboarding flow" | Boundary — "flow" is N use cases | Ask for the list of steps, and design them one at a time |

The refusal is short and actionable: name what's missing and give a rewritten
example. No lecturing about technical language.

---

## 3. Exemplar prompts

All three meet the vocabulary and pass the boundary test with one effect.

### Example A — REST trigger

> REST endpoint `POST /v1/orders` that receives a list of items with `productId` and
> `quantity`. Validates against the `products` table that there's stock for all of
> them, and saves the order to `orders` and `order_items`. Returns 201 with the
> order's id and the `Location` header. Insufficient stock returns 422.

The request may carry statuses and a path — accept them as the user's input, pass them on
for `rest-api-architect`, and don't ask about them. The spec still records situations
(`created`, `insufficient stock`), not the numbers.

One effect (transactional `INSERT` into two tables of the same aggregate). The
`SELECT` on `products` doesn't count.

### Example B — messaging trigger

> Consumer of the Kafka topic `payment.confirmed`. The message carries `orderId` and
> `paidAt`. Marks the order as `PAID` in the `orders` table. No synchronous response.
> Reprocessing the same message has to be safe — the consumer may receive duplicates.

One effect (`UPDATE orders`). The idempotency question has an obvious answer: yes, and
the spec fixes how (state comparison, not a counter).

### Example C — scheduled trigger

> Daily job at 03:00 that reads `PENDING` orders older than 48h from the `orders`
> table and sets them to `EXPIRED`. No external call, no notification. Processes in
> batches of 500.

One effect (batch `UPDATE`). "No notification" is the boundary declared by the
request itself — exactly what the skill wants to hear.

### Counter-example — rejected

> When a new user is created in the marketing area, notify the responsible manager
> so they can approve access.

Zero technical triggers, zero destinations, and it mixes two things ("notify" and
"approve access"). The skill stops and asks for a rewrite.

---

## 4. Canonical names the parent spec fixes

Come from `@.claude/rules/naming.md` and the active blueprint's vocabulary. Here only the
way to derive them from the trigger, so the partial specs don't reinvent them:

| Element | Rule | Example (UC-001, create user) |
|---|---|---|
| Identifier | `UC-NNN-<slug-kebab>` | `UC-001-create-user` |
| Use case class(es) | Active blueprint's vocabulary — `naming.md` § Architecture vocabulary | hexagonal: port `CreateUserUseCase` + `CreateUserService` · clean architecture: concrete `CreateUserUseCase`, no interface |
| Input command | `<Verb><Noun>Command` | `CreateUserCommand` |
| Aggregate | Noun | `User` |
| Outbound port | `<Resource>Repository` | `UserRepository` |
| Emitted event | Past participle | `UserCreated` |
| Error situation | Described, with its kind — never an exception name (`domain-modeling` names it) | `email already in use` (conflict) |

The use case's verb is the trigger's, never a generic one: `confirm`, `expire`,
`import` — never `process`, `handle`, or `execute`.

**The case that creates no use-case class.** When every component of the case is `CHANGE`
on already-approved cases — new fields, a new port, a new event, a gate added to an
existing rule — the row "Use case class(es)" reads `none — extension of UC-XXX, UC-YYY`
and the slug names the **capability added**, not the trigger:
`@.claude/rules/naming.md` § Use case identifier owns both halves of that. It is still one
`UC-NNN` with its own folder and its own spec; what changes is where its content lands —
`## Impact on approved use cases` carries almost all of it, and each altered case's own
`CHANGELOG.md` gets a line. Say so explicitly in the parent spec, in one line, so the
layer skills don't look for a class that was never meant to exist.
