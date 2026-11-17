# Facts of a generated project

Single owner of which facts `Diagrams.java facts --source project` extracts and the file each one
comes from. A view cites a fact by id or by a selector (`type:*`); a fact that is not listed here
is not drawn. Every list the extractor reads — the globs, the package roles, the compose images and
their logos, the trigger words — is the `diagrams` block of `@.claude/schemas/extensions.json`
(invariant 10), never a constant of the Java. Design: `@.claude/decisions/0211-project-diagrams-skill-sharing-the-diagram-engine.md`.

| Prefix | Id | Comes from | Carries |
|---|---|---|---|
| `project:` | `project:info` | the active blueprint's YAML, `application*.yml` | blueprint id, base package, `spring.application.name` |
| `pkg:` | `pkg:<key>` | a key of the blueprint's `packages.map`, found under `src/main/java` | directory, the `type:` ids it holds |
| `type:` | `type:<SimpleName>` | one `.java` file under a mapped package | package key, `role` (first match of `diagrams.architecture.roles`), declaration kind, `uses` (other types its source names), `implements` (ports) |
| `impl:` | `impl:<Adapter>.<Port>` | an `implements` clause **read in the source** | the adapter and the port |
| `service:` | `service:<name>` | `docker-compose.yml` | image, container ports, logo slug, `is_app`, `dev_tool`, whether the app's block references it, detail lines (image tag, tables, topics) |
| `topic:` | `topic:<name>` | the `topics.<name>` keys of `application*.yml` | partitions |
| `table:` | `table:<name>` | `CREATE TABLE` in `db/migration/**` | — |
| `external:` | `external:<slug>` | the `External calls` row of a spec that is not `none`; `external:client` when the app publishes a port | name |
| `uc:` | `uc:UC-NNN` | `docs/use-cases/UC-NNN-<slug>/00-*.md`: the `# UC-NNN · Title` line and the `Trigger`, `Side effects`, `External calls` rows | trigger kind, the verbs of the effects, the aggregates the case's files name, `spec_only` (the spec cites Java files and none exists) |
| `bl:` | `bl:BL-NN` | the active table of `docs/use-cases/BACKLOG.md` | description (cut), dependencies |
| `mermaid:` | `mermaid:UC-NNN:sequence`, `…:flowchart` | the first `sequenceDiagram` and `flowchart` block of the case's `00-*.md` | source, file and line |
| `drift:` | `drift:UC-NNN.<Type>` | an aggregate a spec names (`| Aggregate | \`X\``) that no type of `src/` carries | the type, the case |

A Mermaid block of any spec file whose first line is not in `diagrams.mermaid_types` stops the
extraction, naming file and line: the spec is the defect (`@.claude/rules/spec-diagrams.md`).

Nothing here reads a method, a field, a DTO, a configuration value or a test; a variable's value
never reaches a diagram, only the name of a service, a topic or a table.
