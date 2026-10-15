///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the AsyncAPI contract exemplars say what `.claude/rules/messaging.md` §
// Contract documentation decides, and what was observed running Springwolf against a real
// Initializr project (0162).
//
// Why this test exists: every trap here is silent, and each one produces a document that looks
// right. Springwolf's own `@KafkaListener` scanner left on documents a `__TypeId__` header the
// String wire never carries and lists the method beside `@AsyncListener` as a second message; an
// `@AsyncPublisher` on the relay's broker sender documents no payload, because the sender knows
// none; a publisher that is not a Spring bean is never scanned; a topic literal written three times
// drifts without a report; UI publishing left on is a way to inject a message into a topic from a
// browser; a `servers` block or an `info.version` tied to the build changes the snapshot in every
// environment or every release; a pinned generator version is a version from memory (invariant 8).
// The executor copies an exemplar's shape, so one of these left in a template ships into every
// project that has Kafka.
//
// What it holds, each failure named by file:
//   - the consumer exemplar annotates the `@KafkaListener` method with `@AsyncListener` and
//     `@KafkaAsyncOperationBinding`, reads one TOPIC constant in `topics` and `channelName`, and
//     declares its headers;
//   - each producer exemplar (Form A publisher, Form B outbox writer) carries exactly one
//     `@AsyncPublisher` with `payloadType`, `channelName = TOPIC`, a `message` and `headers`, on a
//     `@Component`; the relay's `KafkaOutboxEventSender` carries none;
//   - the yml exemplar turns Springwolf's `@KafkaListener` scanner and UI publishing off, sets
//     `base-package` and `use-fqn: false`, and declares no `servers` and no `info.version`; no yml
//     exemplar turns publishing on;
//   - a file that imports Springwolf imports no Jackson 2 databind or core;
//   - the contract IT reads `/springwolf/docs`, compares with `src/test/asyncapi/asyncapi.snapshot.json`
//     outside the test classpath, starts no listener container, and compares trees;
//   - the security exemplar's public API document list names `/springwolf/docs`;
//   - the partial template has the four contract rows and a Springwolf dependency row that is
//     not managed and carries no version; the ZenWave exemplar carries no version either;
//   - the norm states the spec-first criteria in both directions, and the migration and the
//     executor agree with the snapshot path.
//
// Offline and in milliseconds. Whether the imports resolve is `validate` › `exemplar-imports`'s job;
// whether the shapes run was proven against a real Initializr project (0162 § Verified).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class AsyncApiTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path MESSAGING = SKILLS.resolve("messaging-architect/templates");
    static final Path PRODUCER = MESSAGING.resolve("KafkaProducerAdapter.java.example");
    static final Path OUTBOX = MESSAGING.resolve("OutboxRelayPublisher.java.example");
    static final Path CONSUMER = MESSAGING.resolve("KafkaConsumerAdapter.java.example");
    static final Path YML = MESSAGING.resolve("application-kafka.yml.example");
    static final Path PARTIAL = MESSAGING.resolve("messaging-spec.md.example");
    static final Path ZENWAVE = MESSAGING.resolve("zenwave-sdk-maven.xml.example");
    static final Path CONTRACT_IT = SKILLS.resolve("test-architect/templates/AsyncApiContractIT.java.example");
    static final Path SECURITY = SKILLS.resolve("security-architect/templates/SecurityConfig.java.example");
    static final Path NORM = Paths.get(".claude/rules/messaging.md");
    static final Path AGENT = Paths.get(".claude/agents/java-spring-boot-developer.md");
    static final Path EXTENSIONS = Paths.get(".claude/schemas/extensions.json");

    static final String SNAPSHOT = "src/test/asyncapi/asyncapi.snapshot.json";
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (Path required : List.of(PRODUCER, OUTBOX, CONSUMER, YML, PARTIAL, ZENWAVE, CONTRACT_IT, SECURITY, NORM, AGENT, EXTENSIONS)) {
            if (!Files.isRegularFile(required)) {
                System.out.println("❌ Missing " + rel(required));
                System.exit(1);
            }
        }
        consumer();
        producer(PRODUCER, "OrderConfirmedPublisher");
        producer(OUTBOX, "OrderConfirmedOutboxWriter");
        relaySender();
        yml();
        noJackson2();
        contractIt();
        security();
        partial();
        zenwave();
        norm();
        agreement();
        if (!failures.isEmpty()) {
            System.out.println("❌ The AsyncAPI contract exemplars disagree with the norm or with what Springwolf does — "
                    + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/messaging.md § Contract documentation · design: .claude/decisions/0162-asyncapi-contract-via-springwolf.md");
            System.exit(1);
        }
        System.out.println("✅ AsyncAPI exemplars annotate both sides explicitly, keep Springwolf's listener scanner and UI publishing off,"
                + " pin no version, and gate the served document against a committed snapshot.");
    }

    static void consumer() throws Exception {
        String rel = rel(CONSUMER);
        String text = code(read(CONSUMER));
        require(rel, text, "@KafkaListener(topics = TOPIC", "the listener does not read the TOPIC constant");
        require(rel, text, "@AsyncListener(", "the listener method has no @AsyncListener — the document would lack the consumer, or carry the scanner's wrong __TypeId__ header");
        require(rel, text, "channelName = TOPIC", "channelName is not the TOPIC constant the listener reads — the topic would be written twice");
        require(rel, text, "@KafkaAsyncOperationBinding(groupId", "carries no @KafkaAsyncOperationBinding with the group id — the consumer group is lost from the document");
        require(rel, text, "headers = @AsyncOperation.Headers(", "does not state its headers");
        require(rel, text, "payloadType = ", "does not state payloadType");
    }

    static void producer(Path file, String className) throws Exception {
        String rel = rel(file);
        String text = code(read(file));
        int count = count(text, "@AsyncPublisher(");
        if (count != 1) {
            fail(rel, "carries " + count + " @AsyncPublisher, expected exactly one on the port's method");
            return;
        }
        String annotation = text.substring(text.indexOf("@AsyncPublisher("));
        annotation = annotation.substring(0, Math.min(annotation.length(), 900));
        for (String needed : List.of("payloadType = ", "channelName = TOPIC", "message = @AsyncMessage(", "headers = @AsyncOperation.Headers(")) {
            if (!annotation.contains(needed)) fail(rel, "@AsyncPublisher misses `" + needed + "`");
        }
        if (!Pattern.compile("(?s)@Component\\s+@RequiredArgsConstructor[^\\n]*\\s+@FieldDefaults\\([^)]*\\)\\s+public class " + className).matcher(text).find()) {
            fail(rel, className + " is not a @Component — Springwolf scans beans under springwolf.docket.base-package, and a class built by hand is never found");
        }
        if (!text.contains("static final String TOPIC")) {
            fail(rel, "has no TOPIC constant for channelName to share with the send");
        }
    }

    static void relaySender() throws Exception {
        String raw = read(OUTBOX);
        int at = raw.indexOf("// --- outbox/KafkaOutboxEventSender.java");
        if (at < 0) {
            fail(rel(OUTBOX), "lost the KafkaOutboxEventSender block this test reads");
            return;
        }
        if (raw.substring(at).contains("Async")) {
            fail(rel(OUTBOX), "KafkaOutboxEventSender carries an AsyncAPI annotation — it moves text already written and knows no payload type");
        }
    }

    static void yml() throws Exception {
        String rel = rel(YML);
        String text = read(YML).replaceAll("(?m)#.*$", "");
        Matcher found = Pattern.compile("(?m)^springwolf:\\n((?:[ \\t]+.*\\n|\\n)*)").matcher(text);
        if (!found.find()) {
            fail(rel, "has no springwolf block");
            return;
        }
        String block = found.group(1);
        boolean scannerOff = Pattern.compile("kafka-listener:\\s*\\n\\s*enabled:\\s*false").matcher(block).find();
        if (!scannerOff) fail(rel, "does not turn Springwolf's @KafkaListener scanner off — it documents a __TypeId__ header this wire never carries");
        boolean publishingOff = Pattern.compile("publishing:\\s*\\n\\s*enabled:\\s*false").matcher(block).find();
        if (!publishingOff) fail(rel, "does not write publishing.enabled: false — UI publishing must be decided, not inherited");
        require(rel, block, "base-package:", "sets no base-package — the scan finds nothing without it");
        require(rel, block, "use-fqn: false", "leaves use-fqn on — the application's package names enter every schema name");
        if (Pattern.compile("(?m)^\\s*servers:").matcher(block).find()) {
            fail(rel, "declares servers — the broker address is deployment, and the snapshot would differ per environment");
        }
        if (Pattern.compile("(?m)^\\s*version:").matcher(block).find()) {
            fail(rel, "sets info.version — tied to the build it changes the snapshot on every release");
        }
        try (Stream<Path> s = Files.walk(SKILLS)) {
            for (Path file : s.filter(p -> p.toString().matches(".*\\.(yml|yaml|properties)\\.example")).sorted().toList()) {
                String stripped = read(file).replaceAll("(?m)#.*$", "");
                if (Pattern.compile("publishing:\\s*\\n\\s*enabled:\\s*true").matcher(stripped).find()
                        || stripped.contains("springwolf.plugin.kafka.publishing.enabled=true")) {
                    fail(rel(file), "turns Springwolf's UI publishing on — only a local profile may, and no exemplar is one");
                }
            }
        }
    }

    static void noJackson2() throws Exception {
        try (Stream<Path> s = Files.walk(SKILLS)) {
            for (Path file : s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".java.example")).sorted().toList()) {
                String text = code(read(file));
                if (text.contains("io.github.springwolf") && Pattern.compile("com\\.fasterxml\\.jackson\\.(databind|core)").matcher(text).find()) {
                    fail(rel(file), "imports Springwolf and Jackson 2 databind or core — application code keeps the Jackson 3 JsonMapper Boot registers");
                }
            }
        }
    }

    static void contractIt() throws Exception {
        String rel = rel(CONTRACT_IT);
        String text = code(read(CONTRACT_IT));
        require(rel, text, "\"/springwolf/docs\"", "does not read the document at /springwolf/docs");
        require(rel, text, "Path.of(\"" + SNAPSHOT + "\")", "the snapshot is not at " + SNAPSHOT + " — outside src/test/resources, where a context would load it");
        require(rel, text, "spring.kafka.listener.auto-startup=false", "starts the listener containers — the test would need a broker");
        require(rel, text, "readTree(", "compares text, not parsed trees — key order and whitespace would fail it");
        require(rel, text, ".isEqualTo(snapshot)", "never compares the served document with the snapshot");
        if (text.contains("src/test/resources")) fail(rel, "keeps the snapshot on the test classpath");
        if (Pattern.compile("com\\.fasterxml\\.jackson\\.(databind|core)").matcher(text).find()) fail(rel, "uses Jackson 2 — the JsonMapper is the Jackson 3 one");
    }

    static void security() throws Exception {
        require(rel(SECURITY), read(SECURITY), "\"/springwolf/docs/**\"", "does not list /springwolf/docs among the API document paths the project makes public or protects as one");
    }

    static void partial() throws Exception {
        String rel = rel(PARTIAL);
        String text = read(PARTIAL);
        for (String row : List.of("| Channel and message name |", "| Headers |", "| Producer operation |", "| Consumer operation |")) {
            require(rel, text, row, "has no contract row");
        }
        Matcher dep = Pattern.compile("(?m)^\\| `io\\.github\\.springwolf:springwolf-kafka` \\| ([^|]*)\\|").matcher(text);
        if (!dep.find()) {
            fail(rel, "§ 7 has no springwolf-kafka row");
        } else {
            if (!dep.group(1).contains("not managed")) fail(rel, "§ 7's springwolf-kafka row does not say it is not managed by the Boot BOM");
            if (Pattern.compile("\\d+\\.\\d+\\.\\d+").matcher(dep.group(1)).find()) fail(rel, "§ 7's springwolf-kafka row writes a version — resolved at write time, never from memory");
        }
    }

    static void zenwave() throws Exception {
        String rel = rel(ZENWAVE);
        String text = read(ZENWAVE);
        if (Pattern.compile("<version>\\s*\\d").matcher(text).find() || Pattern.compile("<zenwave\\.version>\\s*\\d").matcher(text).find()) {
            fail(rel, "pins a version — resolved from maven-metadata.xml at write time, invariant 8");
        }
        require(rel, text, "RESOLVED-AT-WRITE-TIME", "lost the placeholder that says the version is resolved");
        require(rel, text, "spring-cloud-streams3", "does not name the generator whose output the alternative's criteria are about");
    }

    static void norm() throws Exception {
        String rel = rel(NORM);
        String text = read(NORM);
        require(rel, text, "## Contract documentation (AsyncAPI)", "lost the section");
        require(rel, text, "Use spec-first when", "records the rejected alternative without when-to-use criteria");
        require(rel, text, "Do not use it when", "records the rejected alternative without when-not criteria");
        require(rel, text, SNAPSHOT, "does not name the snapshot path");
    }

    static void agreement() throws Exception {
        require(rel(AGENT), read(AGENT), "AsyncApiContractIT", "the executor does not write the contract test");
        require(rel(AGENT), read(AGENT), SNAPSHOT, "the executor does not know the snapshot path");
        String json = read(EXTENSIONS);
        int at = json.indexOf("\"id\": \"asyncapi-springwolf\"");
        if (at < 0) {
            fail(rel(EXTENSIONS), "has no `asyncapi-springwolf` migration — projects generated before the contract existed are never told");
        } else {
            String entry = json.substring(at, Math.min(json.length(), at + 6000));
            if (!entry.contains(SNAPSHOT)) fail(rel(EXTENSIONS), "the migration prompt does not name the snapshot path " + SNAPSHOT);
            if (!entry.contains("maven-metadata.xml")) fail(rel(EXTENSIONS), "the migration prompt does not resolve the version from maven-metadata.xml");
        }
    }

    static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }
    static void require(String rel, String text, String needle, String why) {
        if (!text.contains(needle)) fail(rel, why + " (expected `" + needle.strip() + "`)");
    }
    static void fail(String rel, String message) {
        failures.add(rel + " — " + message);
    }
    static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }
    static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
    static String rel(Path file) {
        return file.toString().replace('\\', '/');
    }
}
