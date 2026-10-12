///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the AI exemplars carry every decision `.claude/rules/ai.md` makes explicit, that
// no other exemplar reaches the AI library, and that every blueprint gives the AI adapters a
// package and keeps the library out of its application layer.
//
// Why this test exists: every default of Spring AI is a silent choice — a model nobody pinned, a
// 60 s per-request timeout that overrides the configured one, a similarity threshold that accepts
// everything, a prompt-logging flag one `true` away, a retry block the OpenAI model never reads.
// The executor copies an exemplar's shape, so one default left in a template ships into every
// project that asks a model (0145). Nothing at runtime would say so: each of these compiles,
// starts and passes a test with a stubbed model.
//
// What it holds, each failure named by file:
//   - no AI production exemplar builds a client around the auto-configured builder
//     (`ChatClient.create(` / `ChatClient.builder(`), every `ChatClient` @Bean takes the injected
//     `ChatClient.Builder`, and every options block names a model and a timeout;
//   - no prompt is a string literal (`.system("` / `.user("`), every `.entity(` validates the schema,
//     every search names its threshold and its result count, and nothing uses a 1.x API name;
//   - the yml: the key placeholder has no default, every content-logging flag is written false,
//     the store's schema initialization is off, the SDK's `max-retries` is set and `spring.ai.retry`
//     (not read by the OpenAI model) is absent, every `app.ai.ports` entry has a model, an output
//     budget and a timeout, every search a threshold above zero, every port constant of
//     `AiClientConfig` an entry — and the migration's `vector(N)` equals `dimensions`;
//   - the prompts use the `< >` delimiters the renderer is configured with, never `{ }` variables;
//   - no exemplar outside ai-architect's and test-architect's AI templates imports the AI library;
//   - the AI test exemplars point the starter's base URL at WireMock and replace the embedding model,
//     so none of them can reach a real model;
//   - every blueprint's `packages.map` has a key ending in `.ai`, and every module named
//     `application` that declares `forbidden_imports` forbids `org.springframework.ai..`.
//
// Offline and in milliseconds. The exemplars are shape references, not compiled here: whether
// their imports resolve is `validate` › `exemplar-imports`'s job, and whether they run was checked
// against a real Initializr project when they were written (0145 § CI coverage).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class AiTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path AI = SKILLS.resolve("ai-architect/templates");
    static final Path TESTS = SKILLS.resolve("test-architect/templates");
    static final Path CONFIG = AI.resolve("AiClientConfig.java.example");
    static final Path YML = AI.resolve("application-ai.yml.example");
    static final Path MIGRATION = AI.resolve("vector-store-migration.sql.example");
    static final Path BLUEPRINTS = Paths.get(".claude/blueprints");
    static final Set<String> AI_TESTS = Set.of(
            "AiAdapterContractTest.java.example", "AiProviderIT.java.example", "VectorStoreIT.java.example");

    static final Pattern AI_IMPORT = Pattern.compile("^import (static )?(org\\.springframework\\.ai|com\\.openai|com\\.anthropic)\\.", Pattern.MULTILINE);
    static final Pattern BEAN_CHAT_CLIENT = Pattern.compile("@Bean[^\\n]*\\n\\s*ChatClient \\w+\\(([^)]*)\\)");
    static final Pattern OPTIONS_BLOCK = Pattern.compile("\\.defaultOptions\\((.*?)\\)\\s*\\n\\s*\\.build\\(\\)", Pattern.DOTALL);
    static final Pattern SEARCH = Pattern.compile("SearchRequest\\.builder\\(\\)(.*?)\\.build\\(\\)", Pattern.DOTALL);
    static final Pattern ENTITY = Pattern.compile("\\.entity\\(([^;]*)\\);");
    static final Pattern PORT_CONSTANT = Pattern.compile("static final String [A-Z_]+ = \"([a-z0-9-]+)\";");
    static final Pattern LEGACY = Pattern.compile("\\b(FunctionCallback|internalToolExecutionEnabled|SpringBeanToolCallbackResolver)\\b|\\.copy\\(\\)|spring\\.ai\\.[a-z]+\\.chat\\.options\\.");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".example"))
                    .sorted().toList();
        }
        List<Path> aiJava = exemplars.stream()
                .filter(p -> p.startsWith(AI) && p.toString().endsWith(".java.example")).toList();
        if (aiJava.isEmpty()) {
            System.out.println("❌ No Java exemplar under " + AI);
            System.exit(1);
        }

        for (Path file : exemplars) {
            if (!file.toString().endsWith(".java.example")) continue;
            boolean owned = file.startsWith(AI) || file.startsWith(TESTS) && AI_TESTS.contains(file.getFileName().toString());
            if (!owned && AI_IMPORT.matcher(read(file)).find()) {
                fail(rel(file), "imports the AI library outside ai-architect's and test-architect's AI exemplars (ai.md § Boundary)");
            }
        }

        for (Path file : aiJava) {
            String text = code(read(file));
            String rel = rel(file);
            if (text.contains("ChatClient.create(") || text.contains("ChatClient.builder(")) {
                fail(rel, "builds a ChatClient around the auto-configured builder — no observations, no customizers (ai.md § Wiring)");
            }
            if (text.matches("(?s).*\\.(system|user)\\(\\s*\".*")) {
                fail(rel, "writes a prompt as a string literal — prompts are resources (ai.md § Prompts)");
            }
            Matcher entity = ENTITY.matcher(text);
            while (entity.find()) {
                if (!entity.group(1).contains("validateSchema()")) {
                    fail(rel, "calls .entity(...) without validateSchema() — best effort is not a contract (ai.md § Output)");
                }
            }
            Matcher search = SEARCH.matcher(text);
            while (search.find()) {
                if (!search.group(1).contains(".similarityThreshold(")) fail(rel, "builds a SearchRequest without similarityThreshold — the default 0.0 accepts every item");
                if (!search.group(1).contains(".topK(")) fail(rel, "builds a SearchRequest without topK — the default is 4, whatever the port asked");
            }
            if (LEGACY.matcher(text).find()) {
                fail(rel, "uses a Spring AI 1.x name 2.0 removed or renamed");
            }
        }

        String config = code(read(CONFIG));
        Matcher bean = BEAN_CHAT_CLIENT.matcher(config);
        int beans = 0;
        while (bean.find()) {
            beans++;
            if (!bean.group(1).contains("ChatClient.Builder")) {
                fail(rel(CONFIG), "has a ChatClient @Bean that does not take the injected ChatClient.Builder");
            }
        }
        if (beans == 0) fail(rel(CONFIG), "declares no ChatClient @Bean — one per port is the shape");
        Matcher options = OPTIONS_BLOCK.matcher(config);
        int blocks = 0;
        while (options.find()) {
            blocks++;
            if (!options.group(1).contains(".model(")) fail(rel(CONFIG), "sets options with no model — the starter's default changes with the release");
            if (!options.group(1).contains(".timeout(")) fail(rel(CONFIG), "sets options with no timeout — the OpenAI model then sends a 60 s default that overrides spring.ai.openai.timeout");
        }
        if (blocks == 0) fail(rel(CONFIG), "sets no default options on the client");
        Set<String> ports = new TreeSet<>();
        Matcher constant = PORT_CONSTANT.matcher(config);
        while (constant.find()) ports.add(constant.group(1));

        yml(read(YML), ports);
        prompts(exemplars);
        tests();
        blueprints();

        if (!failures.isEmpty()) {
            System.out.println("❌ AI exemplars or blueprints leave a library default undecided — " + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/ai.md · design: .claude/decisions/0145-ai-architect-skill.md");
            System.exit(1);
        }
        System.out.println("✅ " + aiJava.size() + " AI exemplars decide every default, " + ports.size()
                + " port(s) configured, no other exemplar reaches the AI library, and every blueprint has an AI package.");
    }

    static void yml(String text, Set<String> ports) throws Exception {
        String rel = rel(YML);
        String body = text.replaceAll("(?m)^\\s*#.*\\n", "");
        require(rel, body, "api-key: ${OPENAI_API_KEY}\n", "gives the key placeholder a default, or none at all (secrets.md)");
        require(rel, body, "max-retries: ", "leaves the SDK's retries on their default — the one retrying layer is decided");
        require(rel, body, "initialize-schema: false", "lets the library create the store's schema — the migration owns it");
        if (body.contains("\n    retry:") || body.contains("spring.ai.retry")) {
            fail(rel, "configures spring.ai.retry — the 2.0 OpenAI model never reads it, so it looks like a setting and does nothing");
        }
        for (String flag : List.of("log-prompt", "log-completion", "include-error-logging", "log-query-response")) {
            if (!Pattern.compile("(?m)^\\s+" + flag + ": false$").matcher(body).find()) {
                fail(rel, "does not write `" + flag + ": false` — content logging is a decision, written down");
            }
        }
        if (Pattern.compile("(?m)^\\s+(log-prompt|log-completion|include-error-logging|log-query-response|include-content): true").matcher(body).find()) {
            fail(rel, "turns content logging on — every prompt would reach the log backend");
        }
        String portsBlock = section(body, "    ports:");
        for (String port : ports) {
            String entry = entry(portsBlock, port);
            if (entry == null) {
                fail(rel, "has no app.ai.ports entry for port '" + port + "' — the startup fails, or the port runs on defaults");
                continue;
            }
            for (String key : List.of("model:", "max-tokens:", "timeout:")) {
                if (!entry.contains(key)) fail(rel, "port '" + port + "' has no " + key);
            }
        }
        Matcher threshold = Pattern.compile("similarity-threshold: ([0-9.]+)").matcher(body);
        while (threshold.find()) {
            if (Double.parseDouble(threshold.group(1)) <= 0.0) fail(rel, "sets a similarity threshold of 0 — every item matches");
        }
        Matcher dims = Pattern.compile("(?m)^\\s+dimensions: (\\d+)$").matcher(body);
        Matcher vector = Pattern.compile("vector\\((\\d+)\\)").matcher(read(MIGRATION));
        if (dims.find() && vector.find() && !dims.group(1).equals(vector.group(1))) {
            fail(rel, "declares dimensions " + dims.group(1) + " while the migration creates vector(" + vector.group(1) + ") — schema validation fails at startup");
        }
    }

    static void prompts(List<Path> exemplars) throws Exception {
        for (Path file : exemplars) {
            if (!file.startsWith(AI) || !file.toString().endsWith(".st.example")) continue;
            String text = read(file);
            if (Pattern.compile("\\{[a-zA-Z_]+\\}").matcher(text).find()) {
                fail(rel(file), "holds a `{name}` variable — the renderer is configured with < > delimiters");
            }
        }
    }

    static void tests() throws Exception {
        Path provider = TESTS.resolve("AiProviderIT.java.example");
        Path store = TESTS.resolve("VectorStoreIT.java.example");
        require(rel(provider), read(provider), "baseUrlProperties = \"spring.ai.openai.base-url\"",
                "does not point the starter's base URL at WireMock — the test would call the real provider");
        require(rel(store), read(store), "@Primary", "does not replace the embedding model — the test would call the real provider");
        require(rel(store), read(store), "pgvector/pgvector:", "does not run the real pgvector engine");
    }

    static void blueprints() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(BLUEPRINTS)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") && !p.toString().contains("/references/")).sorted().toList();
        }
        for (Path file : files) {
            String text = read(file);
            String rel = rel(file);
            if (!Pattern.compile("(?m)^    [a-z.]*\\.ai: \"[a-z.]+\"").matcher(text).find()) {
                fail(rel, "has no packages.map key ending in .ai — the AI adapters would have no package, and ai.md no territory");
            }
            Matcher application = Pattern.compile("(?ms)^  - id: application\\n(.*?)(?=^  - id: |^[a-z])").matcher(text);
            if (application.find() && application.group(1).contains("forbidden_imports:")
                    && !application.group(1).contains("\"org.springframework.ai..\"")) {
                fail(rel, "lets the application module import org.springframework.ai — the model is reached through a port");
            }
        }
    }

    static String section(String text, String header) {
        int at = text.indexOf(header + "\n");
        if (at < 0) return "";
        StringBuilder out = new StringBuilder();
        for (String line : text.substring(at + header.length() + 1).split("\n", -1)) {
            if (!line.startsWith("      ") && !line.isBlank()) break;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    static String entry(String block, String name) {
        int at = block.indexOf("      " + name + ":\n");
        if (at < 0) return null;
        StringBuilder out = new StringBuilder();
        for (String line : block.substring(at).split("\n", -1)) {
            if (out.length() > 0 && !line.startsWith("        ")) break;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    static void require(String rel, String text, String needle, String why) {
        if (!text.contains(needle)) fail(rel, why + " (expected `" + needle.strip() + "`)");
    }

    static void fail(String rel, String message) {
        failures.add(rel + " — " + message);
    }

    /** The text without its comments: a header that names a call does not make it. */
    static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static String rel(Path file) {
        return file.toString().replace('\\', '/');
    }
}
