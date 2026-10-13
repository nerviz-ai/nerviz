///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the search exemplars of search-architect (Elasticsearch / OpenSearch) carry every
// decision `.claude/rules/search.md` makes explicit, that the search test and compose exemplars run
// the engine as the norm says, that no other exemplar reaches a search client, and that every
// blueprint gives the search adapters a package and keeps the clients out of its inner layers.
//
// Why this test exists: every default of Spring Data Elasticsearch is a silent choice —
// `createIndex = true` creates the index at startup with a mapping derived from the class and takes
// the alias's name, `FieldType.Auto` hands the type to dynamic inference, the type hint and the id
// written into `_source` are refused by a strict mapping, a repository refreshes on every save, a
// bulk built without `withVersion` overwrites a newer copy. The executor copies an exemplar's
// shape, so one default left in a template ships into every project that keeps a search index
// (0147). Several of these were reproduced against real engines while the exemplars were written.
//
// What it holds, each failure named by file:
//   - every search document: createIndex = false, external versioning, no type hint, id and version
//     kept out of `_source`, a @Version, and every @Field typed — never FieldType.Auto;
//   - no search exemplar uses an ElasticsearchRepository, a NativeQuery (Elasticsearch's client
//     only), a refresh policy, a cast of getSortValues(), or an IndexQueryBuilder without withVersion;
//   - the mapping file is strict and leaves the replica count to the environment;
//   - the yml: no default password, both timeouts set;
//   - the IT pins the image through DockerImageName.parse, trusts the container's certificate with
//     @Ssl, and waits for an authenticated health on 9200;
//   - the compose services require the password with `:?`, never a `_FILE` variant, single node;
//   - no exemplar outside search-architect's and the search IT imports a search client;
//   - every blueprint has a packages.map key ending in `.search`, every `application` module with
//     `forbidden_imports` forbids the three search prefixes and every `domain` the client ones, and
//     every blueprint with dependency rules for `ai` has them for `search` too.
//
// Offline and in milliseconds. Whether the exemplars compile is `templates` › JavaTemplatesTest's
// job; whether they run, on both engines, was checked when they were written (0147 § CI coverage).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class SearchTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path SEARCH = SKILLS.resolve("search-architect/templates");
    static final Path SEARCH_IT = SKILLS.resolve("test-architect/templates/SearchIndexIT.java.example");
    static final Path MAPPING = SEARCH.resolve("search-index.json.example");
    static final Path YML = SEARCH.resolve("application-search.yml.example");
    static final Path DOCKER = SKILLS.resolve("docker-architect/templates");
    static final Path BLUEPRINTS = Paths.get(".claude/blueprints");

    static final Pattern SEARCH_IMPORT = Pattern.compile("^import (static )?(org\\.springframework\\.data\\.elasticsearch|co\\.elastic|org\\.opensearch)\\.", Pattern.MULTILINE);
    static final Pattern SEARCH_DOCUMENT = Pattern.compile("@Document\\(([^)]*)\\)");
    static final Pattern FIELD = Pattern.compile("@Field\\(([^)]*)\\)");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".java.example"))
                    .sorted().toList();
        }
        List<Path> search = exemplars.stream().filter(p -> p.startsWith(SEARCH)).toList();
        if (search.isEmpty()) {
            System.out.println("❌ No Java exemplar under " + SEARCH);
            System.exit(1);
        }

        for (Path file : exemplars) {
            boolean owned = file.startsWith(SEARCH) || file.equals(SEARCH_IT);
            if (!owned && SEARCH_IMPORT.matcher(text(file)).find()) {
                fail(file, "imports a search client outside search-architect's exemplars (search.md § Boundary)");
            }
        }

        int documents = 0;
        for (Path file : search) {
            String code = code(text(file));
            if (code.contains("import org.springframework.data.elasticsearch.annotations.Document;")) {
                Matcher doc = SEARCH_DOCUMENT.matcher(code);
                while (doc.find()) {
                    documents++;
                    String attributes = doc.group(1);
                    if (!attributes.contains("createIndex = false")) fail(file, "a search @Document without createIndex = false — the repository creates the index at startup and takes the alias's name");
                    if (!attributes.contains("versionType = Document.VersionType.EXTERNAL")) fail(file, "a search @Document without external versioning — a late event overwrites a newer copy");
                    if (!attributes.contains("writeTypeHint = WriteTypeHint.FALSE")) fail(file, "a search @Document writing a type hint — `_class` is refused by a strict mapping");
                    if (!attributes.contains("storeIdInSource = false")) fail(file, "a search @Document storing the id in _source — refused by a strict mapping (observed)");
                    if (!attributes.contains("storeVersionInSource = false")) fail(file, "a search @Document storing the version in _source — refused by a strict mapping");
                }
                if (!Pattern.compile("@Version\\s+Long\\s").matcher(code).find()) fail(file, "a search document with no `@Version Long` for the source's version");
                Matcher field = FIELD.matcher(code);
                while (field.find()) {
                    if (!field.group(1).contains("type =")) fail(file, "an untyped @Field — its default is FieldType.Auto");
                }
            }
            if (code.contains("FieldType.Auto")) fail(file, "uses FieldType.Auto — the type is left to dynamic inference");
            if (code.contains("ElasticsearchRepository")) fail(file, "uses an ElasticsearchRepository — its save refreshes on every call");
            if (code.contains("NativeQuery")) fail(file, "uses NativeQuery — Elasticsearch's client only; CriteriaQuery runs on both engines");
            if (code.contains("RefreshPolicy") || code.contains("withRefreshPolicy")) fail(file, "sets a refresh policy in production code — refresh belongs to a test fixture");
            if (code.contains("getSortValues()")) fail(file, "reads getSortValues() — the type differs per engine (observed on OpenSearch)");
            Matcher builder = Pattern.compile("new IndexQueryBuilder\\(\\)([^;]*?)\\.build\\(\\)", Pattern.DOTALL).matcher(code);
            while (builder.find()) {
                if (!builder.group(1).contains(".withVersion(")) fail(file, "builds an IndexQuery without withVersion — the bulk overwrites a newer copy (observed)");
            }
        }
        if (documents == 0) failures.add(rel(SEARCH) + " — no search document exemplar found");

        mapping();
        yml();
        searchIt();
        compose("elasticsearch-service.yml.example", "ELASTIC_PASSWORD");
        compose("opensearch-service.yml.example", "OPENSEARCH_INITIAL_ADMIN_PASSWORD");
        blueprints();

        if (!failures.isEmpty()) {
            System.out.println("❌ Search exemplars or blueprints leave a search default undecided — " + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/search.md · design: .claude/decisions/0147-document-storage-and-search-architect.md");
            System.exit(1);
        }
        System.out.println("✅ " + documents + " search document exemplar(s) and " + search.size() + " search exemplar files decide every"
                + " default; the mapping, the yml, the IT, both compose services and every blueprint agree with search.md.");
    }

    static void mapping() {
        String json = text(MAPPING);
        if (!Pattern.compile("\"dynamic\"\\s*:\\s*\"strict\"").matcher(json).find()) fail(MAPPING, "is not `\"dynamic\": \"strict\"`");
        if (json.contains("number_of_replicas")) fail(MAPPING, "fixes the replica count — it is per environment (`app.search.replicas`)");
    }

    static void yml() {
        String body = text(YML).replaceAll("(?m)^\\s*#.*\\n", "").replaceAll("\\s+#.*", "");
        if (Pattern.compile("\\$\\{SEARCH_PASSWORD:[^}]").matcher(body).find()) fail(YML, "gives SEARCH_PASSWORD a default (secrets.md)");
        if (!body.contains("connection-timeout:")) fail(YML, "no connection timeout");
        if (!body.contains("socket-timeout:")) fail(YML, "no socket timeout");
        if (!body.contains("replicas:")) fail(YML, "no `app.search.replicas`");
    }

    static void searchIt() {
        String code = code(text(SEARCH_IT));
        if (!code.contains("new ElasticsearchContainer(\n            DockerImageName.parse(\"") && !code.contains("new ElasticsearchContainer(DockerImageName.parse(\"")) {
            fail(SEARCH_IT, "pins the image outside DockerImageName.parse — the compose × src/test tag comparison cannot read it");
        }
        if (!code.contains("@Ssl")) fail(SEARCH_IT, "no @Ssl — Elasticsearch's container serves HTTPS, and the client would speak HTTP");
        if (!code.contains(".forPort(9200)") || !code.contains(".withBasicCredentials(")) {
            fail(SEARCH_IT, "no authenticated wait on 9200 — the bootstrap meets a 401 while the password is being set up (observed)");
        }
    }

    static void compose(String name, String passwordVar) {
        Path file = DOCKER.resolve(name);
        String body = text(file).lines().filter(l -> !l.stripLeading().startsWith("#")).collect(Collectors.joining("\n"));
        if (!Pattern.compile(passwordVar + ": \\$\\{SEARCH_PASSWORD:\\?").matcher(body).find()) {
            fail(file, "does not require SEARCH_PASSWORD with `:?`");
        }
        if (body.contains("_FILE:")) fail(file, "uses a `_FILE` password variable — Spring Boot's compose support refuses it");
        if (!body.contains("discovery.type: single-node")) fail(file, "is not a single-node cluster");
    }

    static void blueprints() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(BLUEPRINTS)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") && !p.toString().contains("/references/")).sorted().toList();
        }
        for (Path file : files) {
            String text = text(file);
            if (!Pattern.compile("(?m)^    [a-z.]*\\.search: \"[a-z.]+\"").matcher(text).find()) {
                fail(file, "has no packages.map key ending in .search — the search adapters would have no package, and search.md no territory");
            }
            Matcher application = Pattern.compile("(?ms)^  - id: application\\n(.*?)(?=^  - id: |^[a-z])").matcher(text);
            if (application.find() && application.group(1).contains("forbidden_imports:")) {
                for (String prefix : List.of("org.springframework.data.elasticsearch..", "co.elastic..", "org.opensearch..")) {
                    if (!application.group(1).contains("\"" + prefix + "\"")) fail(file, "lets the application module import " + prefix);
                }
            }
            Matcher domain = Pattern.compile("(?ms)^  - id: (domain|core)\\n(.*?)(?=^  - id: |^[a-z]|^  #)").matcher(text);
            if (domain.find() && domain.group(2).contains("forbidden_imports")) {
                for (String prefix : List.of("co.elastic..", "org.opensearch..")) {
                    if (!domain.group(2).contains("\"" + prefix + "\"")) fail(file, "lets the " + domain.group(1) + " module import " + prefix);
                }
            }
            if (text.contains("    - from: ai\n") && !text.contains("    - from: search\n")) {
                fail(file, "has dependency rules for `ai` and none for `search` — a search adapter could call back into a slice or a service");
            }
        }
    }

    static void fail(Path file, String message) {
        failures.add(rel(file) + " — " + message);
    }

    /** The text without its comments: a header that names a call does not make it. */
    static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception missing) {
            failures.add(rel(file) + " — missing");
            return "";
        }
    }

    static String rel(Path file) {
        return file.toString().replace('\\', '/');
    }
}
