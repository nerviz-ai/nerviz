# External sources — search engine index

Reference for the `search-architect` skill. Each row has the source and what to fetch there.
Official documentation first; where it diverges from any summary, the official one wins.

**Freshness warning.** Assembled on 2026-10-13. URLs change and versions ship: resolve the
project's actual versions (`./mvnw dependency:tree`, and the compatibility matrix below) before
applying what a page says — never a version from memory (`@CLAUDE.md` invariant 8).

| Source | Go fetch |
|---|---|
| <https://docs.spring.io/spring-data/elasticsearch/reference/> | *Versions* (the release train ↔ client ↔ server matrix), *Object Mapping*, *Template*, *Repository queries*, *Misc* (aliases, point in time, `search_after`) |
| <https://github.com/opensearch-project/spring-data-opensearch> | The compatibility matrix (Spring Boot ↔ Spring Data OpenSearch ↔ server), the starter, `opensearch.*` properties, `@DataOpenSearchTest`, the Testcontainers service connection |
| <https://repo1.maven.org/maven2/org/opensearch/client/spring-data-opensearch-starter/maven-metadata.xml> | The releases of each Spring Data OpenSearch series |
| <https://docs.spring.io/spring-boot/reference/data/nosql.html> | Elasticsearch connection properties, the auto-configured client |
| <https://docs.spring.io/spring-boot/reference/testing/testcontainers.html> | Service connections, `@Ssl` |
| <https://www.elastic.co/docs/reference/elasticsearch/rest-apis/compatibility> | Which client major speaks to which server |
| <https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/dynamic> · <https://www.elastic.co/docs/reference/elasticsearch/index-settings/mapping-limit> | Strict mapping, field limits |
| <https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/nested> · <https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/text> | `nested`, `text` vs `keyword` |
| <https://www.elastic.co/docs/manage-data/data-store/mapping/update-mappings-examples> · <https://www.elastic.co/docs/manage-data/data-store/aliases> · <https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reindex-indices> | Changing a mapping: new index, reindex, atomic alias swap |
| <https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-index> | `version_type=external`, tombstones |
| <https://www.elastic.co/docs/reference/elasticsearch/rest-apis/refresh-parameter> · <https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-bulk> | Refresh on write, bulk item results |
| <https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results> | `search_after`, point in time, the 10 000-hit window |
| <https://www.elastic.co/docs/deploy-manage/production-guidance/optimize-performance/size-shards> | Shard sizing |
| <https://docs.opensearch.org/latest/clients/> · <https://docs.opensearch.org/latest/install-and-configure/install-opensearch/docker/> | OpenSearch clients, the Docker image and its admin password |
| <https://java.testcontainers.org/modules/elasticsearch/> · <https://github.com/opensearch-project/opensearch-testcontainers> | Test containers for each engine |
| <https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html> | Feeding a projection from an outbox |
