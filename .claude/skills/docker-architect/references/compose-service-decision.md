# Compose service decision

What a design partial records about a container its use case needs at runtime, and who acts
on each answer. Owned here because `docker-architect` is the single owner of every service
block. Read by `persistence-architect` and `messaging-architect` (step 9), by `cache-architect` (step 10), by `ai-architect` (step 9), by `search-architect` (step 13), by `/new-feature`
consolidation, by `/new-feature-implement` and by `compose-service-installer`.
Record: `@.claude/decisions/0137-pending-compose-service-decided-at-design-materialized-at-implement.md`.

## The section

Every partial whose layer needs a container carries `## Compose services`, one row per
container:

| Service | Image | Decision | Detail |
|---|---|---|---|
| `kafka` | `apache/kafka:<tag>` | `materialize` | added by `/new-feature-implement` |

`none` is valid when the layer needs no container: H2, or no broker. Absence is not `none`,
and consolidation stops on it. The image is the tag the spec assumes. `docker-architect`
verifies it before it writes anything.

## The four decisions

| Decision | When | Who acts | `Detail` carries |
|---|---|---|---|
| `exists` | The compose file already declares the service | Nobody | — |
| `external` | The service runs outside this compose file: a shared environment, another project's stack, a managed instance | Nobody. The partial's configuration block uses this address as the host default of the address property, in place of the compose port | `host:port` |
| `materialize` | The user asked for it to be added to the compose file | `compose-service-installer`, during `/new-feature-implement`, after the tests group ends green and before the final report | — |
| `pending` | The user will add it by hand, or the project has no `docker-compose.yml` | The user, from a prompt of its own | The reason and the `/docker-architect docs/use-cases/UC-NNN-<slug>/` command |

## The question

List the keys of the `services:` block (the command in each skill's step 9). The service is
there → `exists`, and nothing is asked.

Missing, and `docker-compose.yml` exists → `AskUserQuestion`, one question, three answers:

1. **Add it to docker-compose** → `materialize`. The implement run adds it, so the code and the
   container land in the same commit.
2. **It already runs outside this compose** → `external`. Ask for the address in the same
   call when the answer does not include it.
3. **I'll add it myself** → `pending`.

No `docker-compose.yml` at all → `pending` with no question, and `Detail` reads
`no docker-compose.yml — the base pair is project-bootstrap's`. Only `project-bootstrap`
writes the base pair, once, at generation. `docker-architect` stops without it, and so does
the installer.

The design skill never invokes `docker-architect` and never edits the compose file: the skill
is class `build`, and `ArchHook.java guard` refuses the call while a design run is open.
