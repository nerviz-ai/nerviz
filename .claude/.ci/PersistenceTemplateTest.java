///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the document-family exemplars of persistence-architect (MongoDB) carry every
// decision `.claude/rules/persistence.md` § Document (MongoDB) makes explicit, that the MongoDB
// test and compose exemplars run a replica set, that no other exemplar reaches the MongoDB driver,
// and that every blueprint keeps it out of its inner layers.
//
// Why this test exists: every MongoDB default is a silent choice — `save` without `@Version` is a
// whole-document upsert, a class without `@Document("…")` names its own collection, `@Indexed`
// with index creation off is a unique key that does not exist, a standalone container refuses
// every transaction, a settings bean drops Spring Boot's URI and UUID representation. The executor
// copies an exemplar's shape, so one default left in a template ships into every project that
// stores a document (0147). Each of these compiles, starts, and passes a test against an empty
// collection.
//
// What it holds, each failure named by file:
//   - every MongoDB document exemplar names its collection, its type alias and a @Version;
//   - no exemplar uses @DBRef / @DocumentReference or @Indexed, a derived Containing/IgnoreCase
//     method, or a Page on a MongoDB repository; no MongoClientSettings or MongoCustomConversions
//     @Bean; a TTL index has one key;
//   - the MongoDB yml: `spring.mongodb.uri`, never `spring.data.mongodb.uri`; UUID `standard`;
//     BigDecimal `decimal128`; index creation off; every client timeout set; no default password;
//   - DocumentTransactions (application layer) imports nothing from MongoDB;
//   - the MongoDB IT starts `.withReplicaSet()` through `DockerImageName.parse`;
//   - the compose service runs `--replSet` with a keyfile, initiates the set, and requires the
//     password with no default;
//   - no exemplar outside persistence-architect's and the MongoDB IT imports the driver;
//   - every module named `application` that declares `forbidden_imports` forbids
//     `org.springframework.data.mongodb..`, `com.mongodb..` and `org.bson..`, and every `domain`
//     that declares them forbids `com.mongodb..` and `org.bson..`.
//
// Offline and in milliseconds. Whether the exemplars compile is `templates` › JavaTemplatesTest's
// job, and whether they run was checked against a real replica set when they were written
// (0147 § CI coverage).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class PersistenceTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path PERSISTENCE = SKILLS.resolve("persistence-architect/templates");
    static final Path MONGO_IT = SKILLS.resolve("test-architect/templates/MongoPersistenceIT.java.example");
    static final Path YML = PERSISTENCE.resolve("application-mongodb.yml.example");
    static final Path TRANSACTIONS = PERSISTENCE.resolve("DocumentTransactions.java.example");
    static final Path COMPOSE = SKILLS.resolve("docker-architect/templates/mongo-service.yml.example");
    static final Path BLUEPRINTS = Paths.get(".claude/blueprints");

    static final Pattern MONGO_IMPORT = Pattern.compile("^import (static )?(org\\.springframework\\.data\\.mongodb|com\\.mongodb|org\\.bson)\\.", Pattern.MULTILINE);
    static final Pattern BLOCK = Pattern.compile("(?m)^// --- (\\S+\\.java)");
    static final Pattern SETTINGS_BEAN = Pattern.compile("@Bean[^\\n]*\\n\\s*(MongoClientSettings|MongoCustomConversions)\\s+\\w+\\(");
    static final Pattern INDEX_CHAIN = Pattern.compile("new Index\\(\\)((?:\\s*\\.[a-zA-Z]+\\([^;]*?\\))+)");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".java.example"))
                    .sorted().toList();
        }
        List<Path> mongo = exemplars.stream()
                .filter(p -> p.startsWith(PERSISTENCE) && MONGO_IMPORT.matcher(text(p)).find()).toList();
        if (mongo.isEmpty()) {
            System.out.println("❌ No MongoDB exemplar under " + PERSISTENCE);
            System.exit(1);
        }

        for (Path file : exemplars) {
            boolean owned = file.startsWith(PERSISTENCE) || file.equals(MONGO_IT);
            if (!owned && MONGO_IMPORT.matcher(text(file)).find()) {
                fail(file, "imports the MongoDB driver or Spring Data MongoDB outside persistence-architect's exemplars (persistence.md § Boundary)");
            }
        }

        int documents = 0;
        for (Path file : mongo) {
            String all = text(file);
            for (Map.Entry<String, String> block : blocks(all).entrySet()) {
                String code = code(block.getValue());
                if (!code.contains("import org.springframework.data.mongodb.core.mapping.Document;")) continue;
                documents++;
                String where = rel(file) + " › " + block.getKey();
                if (!Pattern.compile("@Document\\(\"[a-z_]+\"\\)").matcher(code).find()) {
                    failures.add(where + " — @Document without its collection name: a rename moves the data out of reach");
                }
                if (!code.contains("@TypeAlias(\"")) {
                    failures.add(where + " — no @TypeAlias: _class stores the class name, and moving the class breaks every stored document");
                }
                if (!Pattern.compile("@Version\\s+Long\\s").matcher(code).find()) {
                    failures.add(where + " — no `@Version Long`: save is a whole-document upsert that never fails");
                }
            }
            String code = code(all);
            if (code.contains("@DBRef") || code.contains("@DocumentReference")) {
                fail(file, "references another aggregate by @DBRef/@DocumentReference — N+1, a lazy proxy, no cascade");
            }
            if (code.contains("@Indexed")) {
                fail(file, "declares an index with @Indexed — with auto-index-creation off it never exists; declare it in MongoCollectionSchema");
            }
            if (Pattern.compile("\\b\\w+(Containing|IgnoreCase)\\w*\\(").matcher(code).find()) {
                fail(file, "derives a Containing/IgnoreCase query — a regular expression no index serves");
            }
            if (code.contains("extends MongoRepository") && Pattern.compile("\\bPage<").matcher(code).find()) {
                fail(file, "pages a MongoDB listing with Page — it counts the whole collection on every page; use a keyset Window");
            }
            if (SETTINGS_BEAN.matcher(code).find()) {
                fail(file, "declares a MongoClientSettings or MongoCustomConversions @Bean — it drops Spring Boot's URI, UUID representation and SSL bundle");
            }
            Matcher chain = INDEX_CHAIN.matcher(code);
            while (chain.find()) {
                String calls = chain.group(1);
                if (calls.contains(".expire(") && countOf(calls, ".on(") != 1) {
                    fail(file, "puts a TTL on a compound index — expireAfterSeconds is ignored there");
                }
            }
        }
        if (documents == 0) failures.add(rel(PERSISTENCE) + " — no MongoDB document exemplar found");

        if (MONGO_IMPORT.matcher(text(TRANSACTIONS)).find()) {
            fail(TRANSACTIONS, "imports MongoDB in the application layer — the retry keys on TransientDataAccessException");
        }

        yml();
        mongoIt();
        compose();
        blueprints();

        if (!failures.isEmpty()) {
            System.out.println("❌ Document-family exemplars or blueprints leave a MongoDB default undecided — " + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/persistence.md § Document (MongoDB) · design: .claude/decisions/0147-document-storage-and-search-architect.md");
            System.exit(1);
        }
        System.out.println("✅ " + documents + " MongoDB document exemplar(s) name their collection, alias and version; the yml, the IT, the"
                + " compose service and every blueprint keep the document family's defaults decided.");
    }

    static void yml() throws Exception {
        String body = text(YML).replaceAll("(?m)^\\s*#.*\\n", "").replaceAll("\\s+#.*", "");
        if (!Pattern.compile("(?m)^  mongodb:\\n    uri: ").matcher(body).find()) {
            fail(YML, "sets no `spring.mongodb.uri` — Spring Boot reads the connection only from that prefix");
        }
        if (Pattern.compile("(?m)^  data:\\n    mongodb:\\n(      .*\\n)*?      uri:").matcher(body).find()) {
            fail(YML, "sets `spring.data.mongodb.uri` — ignored by Spring Boot, the application connects to localhost/test");
        }
        for (String[] want : new String[][] {
                {"uuid: standard", "the UUID representation is not `standard` — the driver refuses to encode a UUID"},
                {"big-decimal: decimal128", "BigDecimal is not stored as decimal128"},
                {"auto-index-creation: false", "index creation is not stated off — indexes are declared and created by code"},
                {"server-selection-timeout:", "no server selection timeout — the driver waits 30 s"},
                {"connect-timeout:", "no connect timeout"},
                {"read-timeout:", "no read timeout — the driver's default is never"},
                {"max-pool-size:", "no pool size"}}) {
            if (!body.contains(want[0])) fail(YML, want[1]);
        }
        if (Pattern.compile("\\$\\{MONGO_PASSWORD:[^}]").matcher(body).find()) {
            fail(YML, "gives MONGO_PASSWORD a default — a secret placeholder has none (secrets.md)");
        }
    }

    static void mongoIt() throws Exception {
        String code = code(text(MONGO_IT));
        if (!code.contains(".withReplicaSet()")) {
            fail(MONGO_IT, "starts MongoDB without .withReplicaSet() — a standalone server refuses every transaction");
        }
        if (!code.contains("new MongoDBContainer(DockerImageName.parse(\"")) {
            fail(MONGO_IT, "pins the image outside DockerImageName.parse — the compose × src/test tag comparison cannot read it");
        }
        if (!code.contains("MongoSchemaInitializer.class")) {
            fail(MONGO_IT, "does not import MongoSchemaInitializer — the slice runs with no index, and the duplicate test passes for the wrong reason");
        }
    }

    static void compose() throws Exception {
        String body = text(COMPOSE).lines().filter(l -> !l.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));
        if (!body.contains("--replSet")) fail(COMPOSE, "runs a standalone server — no --replSet, no transactions");
        if (!body.contains("--keyFile")) fail(COMPOSE, "runs a replica set with users and no keyfile — mongod refuses it");
        if (!body.contains("rs.initiate(")) fail(COMPOSE, "never initiates the replica set");
        if (body.contains("_FILE:")) fail(COMPOSE, "uses a MONGO_INITDB_*_FILE variable — Spring Boot's compose support rejects it");
        if (!Pattern.compile("MONGO_INITDB_ROOT_PASSWORD: \\$\\{MONGO_PASSWORD:\\?").matcher(body).find()) {
            fail(COMPOSE, "does not require MONGO_PASSWORD with `:?` — the service would start with an empty password");
        }
    }

    static void blueprints() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(BLUEPRINTS)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") && !p.toString().contains("/references/")).sorted().toList();
        }
        for (Path file : files) {
            String text = text(file);
            Matcher application = Pattern.compile("(?ms)^  - id: application\\n(.*?)(?=^  - id: |^[a-z])").matcher(text);
            if (application.find() && application.group(1).contains("forbidden_imports:")) {
                for (String prefix : List.of("org.springframework.data.mongodb..", "com.mongodb..", "org.bson..")) {
                    if (!application.group(1).contains("\"" + prefix + "\"")) {
                        fail(file, "lets the application module import " + prefix + " — the document store is reached through a port");
                    }
                }
            }
            Matcher domain = Pattern.compile("(?ms)^  - id: (domain|core)\\n(.*?)(?=^  - id: |^[a-z]|^  #)").matcher(text);
            if (domain.find() && domain.group(2).contains("forbidden_imports")) {
                for (String prefix : List.of("com.mongodb..", "org.bson..")) {
                    if (!domain.group(2).contains("\"" + prefix + "\"")) {
                        fail(file, "lets the " + domain.group(1) + " module import " + prefix);
                    }
                }
            }
        }
    }

    static Map<String, String> blocks(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(text);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start(), m.end()});
            names.add(m.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : text.length();
            out.put(names.get(i), text.substring(starts.get(i)[1], end));
        }
        return out;
    }

    static int countOf(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) count++;
        return count;
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
