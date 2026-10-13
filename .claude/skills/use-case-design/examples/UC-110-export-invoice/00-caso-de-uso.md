# UC-110 · Export invoice

> Parent spec. Fixes the boundary and the names; **doesn't** detail any layer.
> Per-layer detail lives in this folder's partials, each with its own owner.
>
> **Example spec** — see `.claude/skills/use-case-design/examples/README.md`. Written
> to exercise `/new-feature` and `java-spring-boot-developer`; the business rule below
> is a candidate symptom for `java-patterns`, not a pattern chosen here.

| Field | Value |
|---|---|
| Identifier | `UC-110-export-invoice` |
| Date | 2026-09-09 |
| Trigger | invoice export request, over HTTP — inbound REST adapter |
| Side effects | **0** — read + format, nothing persisted |
| Storage | `Invoice` → relational (read) — chosen by: request (the aggregate already lives in a table) |
| Search index | none |
| Transaction | read-only |
| Idempotency | yes — same invoice and format always produce the same file |
| Concurrency | not applicable |
| Active blueprint | `clean-architecture-single-module` |

## Trigger, payload, and response

**In**

| Field | Type | Required | Rule |
|---|---|---|---|
| `invoiceId` | UUID (path) | ✅ | must reference an existing invoice |
| `format` | string | ✅ | one of `CSV`, `PDF`, `XLSX` |

**Out**

| Situation | Result |
|---|---|
| exported | binary file in the requested format |
| unsupported format | rejected without fetching the invoice |
| invoice not found | resource does not exist |

## Flow

1. The inbound adapter receives `invoiceId` and `format`.
2. Translates it into `ExportInvoiceCommand`.
3. The use case `ExportInvoiceUseCase` fetches the `Invoice` aggregate and its
   line items — this step is identical for every format.
4. It validates the fetched data is complete enough to export — also identical for
   every format.
5. Only the last step — turning validated invoice data into bytes — differs: CSV is a
   flat row-per-line-item dump, PDF lays the same data into a fixed template with
   totals, XLSX writes it into a workbook with a formulas sheet. Fetch and validate
   never change; only this final step varies by `format`.

## Components

State: **NEW** to be created · **CHANGE** already exists and changes · **REUSE**
already exists and serves.

| File | Layer | Role | State | Detailed by |
|---|---|---|---|---|
| `infrastructure/rest/InvoiceExportController.java` | infrastructure (in) | receives HTTP | NEW | `rest-api-architect` |
| `infrastructure/rest/dto/InvoiceExportResponse.java` | infrastructure (in) | output DTO — binary + content type | NEW | `rest-api-architect` |
| `application/usecase/invoice/ExportInvoiceCommand.java` | application | command | NEW | `domain-modeling` |
| `application/usecase/invoice/ExportInvoiceUseCase.java` | application | orchestrates: fetch, validate, format | NEW | `domain-modeling` |
| `application/port/InvoiceRepository.java` | application | outbound port | REUSE | `domain-modeling` |
| `domain/model/Invoice.java` | domain | aggregate | REUSE | `domain-modeling` |
| `domain/exception/InvoiceIncompleteException.java` | domain | exception | NEW | `domain-modeling` |
| `domain/exception/UnsupportedExportFormatException.java` | domain | exception | NEW | `domain-modeling` |

## Invariants

| Rule | Guaranteed by | Who violates it |
|---|---|---|
| Fetch and validation steps are identical regardless of `format` | `ExportInvoiceUseCase`'s fixed sequence | a format-specific shortcut that skips validation |
| An `Invoice` missing required line-item data is never exported, in any format | validation step, before formatting | exporting a draft invoice |
| Every supported `format` produces the same underlying data, only the encoding differs | shared fetch/validate result feeding all three formatters | a formatter recomputing totals independently |

## Errors

| Scenario | Exception | Family | Expected result |
|---|---|---|---|
| unsupported `format` | `UnsupportedExportFormatException` | Validation | 400 |
| invoice missing required data | `InvoiceIncompleteException` | Business | 422 |
| invoice not found | (adapter-level lookup) | — | 404 |

## Expected tests

| Level | Target | Detailed by |
|---|---|---|
| unit | `ExportInvoiceUseCase`: fetch/validate run once regardless of format; each format produces the expected byte shape from the same input | `test-architect` |
| unit | rejects an incomplete invoice before formatting | `test-architect` |
| slice | controller: 200 per format, 400, 404, 422 | `test-architect` |

## Out of scope for this use case

- Emailing the exported file → **UC-103**'s notification boundary, not this one.
- Bulk export of multiple invoices → a different trigger, its own pagination concerns.
- Editing invoice data → invoice creation/update use cases, not export.

## Implementation order

- [ ] 1. `domain/exception/InvoiceIncompleteException.java`
- [ ] 2. `domain/exception/UnsupportedExportFormatException.java`
- [ ] 3. `application/usecase/invoice/ExportInvoiceCommand.java`
- [ ] 4. `application/usecase/invoice/ExportInvoiceUseCase.java`
- [ ] 5. `infrastructure/rest/`
- [ ] 6. tests for the levels above
- [ ] 7. `./mvnw clean verify` green
