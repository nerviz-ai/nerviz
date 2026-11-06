# Block I: Search (steps I1-I3, conditional)

**Runs only when the spec carries the search block** — `grep -m1 -E '^## [0-9.]+ Search' <spec>`,
and the block is not `none`. No match, or `none` → skip to Block 3. Read the search block of
spec.md, then `23-search.md`, then `.claude/rules/search.md`. It runs after Block A because a search
adapter compiles against the `search index` port and the integration families, and nothing upstream
compiles against it. The exemplars are in `.claude/skills/search-architect/templates/`; the adapter
code is the same for Elasticsearch and OpenSearch — only the dependency and the properties differ.

- **I1 · Index** — the mapping file of `23-search.md` § 2 at its path under
  `src/main/resources/search/`, verbatim; once per project, in `[search]/shared/`,
  `SearchIndexDefinition` and the public `SearchIndexBootstrap`
  (`SearchIndexBootstrap.java.example`); per projection, the search document
  (`SearchDocument.java.example`: `createIndex = false`, no type hint, id and version kept out of
  `_source`, external version) and its index definition. The dependencies of § 9 in `pom.xml` —
  Elasticsearch's with no version, OpenSearch's with the versions § 9 recorded
- **I2 · Adapters** — per the kind of case: the query adapter implementing the `search index` port
  (`CriteriaQuery`, the tombstone filter, the sort ending in the unique field, the page ceiling, the
  cursor from the last item's fields); the indexer adapter (external version, a stale write returned
  as a value, the tombstone on delete, the `-next` alias written during a rebuild); the rebuild
  adapter (`SearchRebuild.java.example`: begin, bulk with `withVersion`, one alias action). Never an
  `ElasticsearchRepository`, never a `NativeQuery`
- **I3 · Configuration** — the properties of § 10 (`application-search.yml.example`): the engine's
  connection with the password as a placeholder with no default, explicit timeouts,
  `app.search.replicas`, the SSL bundle outside `local`, `docker` and `test`

Validation: no search client outside `[search]`, no `FieldType.Auto`, every mapping file
`"dynamic": "strict"`, no refresh on a write path.

Compilation: `./mvnw -q test-compile` (or `-pl <module that holds [search]>` in multi-module) ✅

**Line in the final report:**
```
✅ Block I: Search complete (steps I1-I3)
- Projection: orders-v1 behind alias orders (Elasticsearch), 8 fields, strict
- Adapters: OrderSearchAdapter (query, cursor), OrderIndexerAdapter (external version, tombstone)
- Files: 7 classes, 1 mapping file, 0 errors
```
