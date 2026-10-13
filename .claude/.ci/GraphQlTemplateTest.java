///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the GraphQL exemplars carry every decision `.claude/rules/api-graphql.md` makes
// explicit, that no other exemplar reaches the GraphQL libraries, and that every blueprint gives the
// GraphQL adapter a package and keeps the libraries out of its inner layers.
//
// Why this test exists: Spring for GraphQL and graphql-java turn no limit on by default — no depth,
// no complexity, no timeout, an unbounded batch, introspection on, a schema inspection that only
// logs — and a resolver that loads a child per parent runs and passes every functional test. The
// executor copies an exemplar's shape, so one default left in a template ships into every project
// that exposes GraphQL (0146). Two of the shapes held here were wrong on the first run of the
// exemplars and caught only by running them: a complexity calculator that multiplied every field,
// and a schema snapshot on the test classpath.
//
// What it holds, each failure named by file:
//   - the config exemplar declares the depth and complexity instrumentations, the timeout
//     interceptor and the batch size, multiplies complexity only on a field that declares `first`,
//     and turns the schema inspection report into a startup failure;
//   - the controller exemplar has no transaction, no repository, at least one `@BatchMapping`, no
//     `@SchemaMapping` returning a list, a page ceiling, and a mutation returning a `...Payload`;
//   - the exception resolver returns `null` for what it does not own — no catch-all;
//   - the yml: introspection and GraphiQL off in the base document and on only in a `dev` profile,
//     CORS methods `POST` and no `*` origin, and an `app.graphql` key for every `GraphQlLimits`
//     component;
//   - the schema: no root list without a connection, no `Float`, every mutation one `input` and a
//     `...Payload` return, a `UserError` with `field`, `code`, `message`;
//   - the tests: access cases over a real server, none in the slice; the slice proves one batch
//     call; the schema snapshot lives outside the test classpath and comments are not printed;
//   - no exemplar names `graphql.operation.name`, and none outside the GraphQL exemplars imports
//     `graphql.` or `org.springframework.graphql.`;
//   - every blueprint's `packages.map` has a key ending in `.graphql`; every module named
//     `application` that declares `forbidden_imports` forbids both libraries, every `domain` one
//     forbids `graphql..`; a `graphql` package in a single-module layout has its own dependency
//     rule.
//
// Offline and in milliseconds. The exemplars are shape references, not compiled here: whether their
// imports resolve is `validate` › `exemplar-imports`'s job, and whether they run was checked against
// a real Initializr project when they were written (0146 § CI coverage).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class GraphQlTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path GQL = SKILLS.resolve("graphql-api-architect/templates");
    static final Path TESTS = SKILLS.resolve("test-architect/templates");
    static final Path CONFIG = GQL.resolve("GraphQlConfig.java.example");
    static final Path CONTROLLER = GQL.resolve("GraphQlController.java.example");
    static final Path RESOLVER = GQL.resolve("GraphQlExceptionResolver.java.example");
    static final Path YML = GQL.resolve("application-graphql.yml.example");
    static final Path SCHEMA = GQL.resolve("schema.graphqls.example");
    static final Path SLICE = TESTS.resolve("GraphQlControllerTest.java.example");
    static final Path HTTP = TESTS.resolve("GraphQlHttpIT.java.example");
    static final Path COMPAT = TESTS.resolve("GraphQlSchemaCompatibilityTest.java.example");
    static final Path BLUEPRINTS = Paths.get(".claude/blueprints");
    static final Set<String> GQL_TESTS = Set.of(
            "GraphQlControllerTest.java.example", "GraphQlHttpIT.java.example", "GraphQlSchemaCompatibilityTest.java.example");

    static final Pattern GQL_IMPORT = Pattern.compile("^import (static )?(graphql|org\\.springframework\\.graphql)\\.", Pattern.MULTILINE);
    static final Pattern LIST_SCHEMA_MAPPING = Pattern.compile("@SchemaMapping[^\\n]*\\n(\\s*@[^\\n]*\\n)*\\s*(List|Collection|Set)<");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".example"))
                    .sorted().toList();
        }
        if (!Files.isDirectory(GQL)) {
            System.out.println("❌ No GraphQL exemplars under " + GQL);
            System.exit(1);
        }
        for (Path file : exemplars) {
            String text = read(file);
            if (text.contains("graphql.operation.name")) {
                fail(rel(file), "names graphql.operation.name — chosen by the client, never a metric tag (api-graphql.md § Observability)");
            }
            if (!file.toString().endsWith(".java.example")) continue;
            boolean owned = file.startsWith(GQL) || file.startsWith(TESTS) && GQL_TESTS.contains(file.getFileName().toString());
            if (!owned && GQL_IMPORT.matcher(text).find()) {
                fail(rel(file), "imports a GraphQL library outside the GraphQL exemplars (api-graphql.md § Boundary)");
            }
        }
        config();
        controller();
        resolver();
        yml();
        schema();
        tests();
        blueprints();
        if (!failures.isEmpty()) {
            System.out.println("❌ GraphQL exemplars or blueprints leave a library default undecided — " + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/api-graphql.md · design: .claude/decisions/0146-graphql-api-architect-skill.md");
            System.exit(1);
        }
        System.out.println("✅ GraphQL exemplars decide every limit, batch every child list, resolve only what they own;"
                + " no other exemplar reaches the GraphQL libraries, and every blueprint has a GraphQL package.");
    }

    static void config() throws Exception {
        String rel = rel(CONFIG);
        String text = code(read(CONFIG));
        require(rel, text, "new MaxQueryDepthInstrumentation(", "declares no depth limit — none is on by default");
        require(rel, text, "new MaxQueryComplexityInstrumentation(", "declares no complexity limit — none is on by default");
        require(rel, text, "new TimeoutWebGraphQlInterceptor(", "declares no request timeout — a slow port holds the request thread");
        require(rel, text, ".setMaxBatchSize(", "leaves the batch size unbounded — java-dataloader's default is -1");
        require(rel, text, "inspectSchemaMappings(", "leaves the schema inspection to Boot, which only logs it");
        if (!text.contains("unmappedFields()") || !Pattern.compile("throw new [A-Za-z]+Exception\\(\"GraphQL schema").matcher(text).find()) {
            fail(rel, "does not fail the startup on an unmapped schema field — it would answer null in production");
        }
        if (!text.contains(".getArgument(FIRST) == null")) {
            fail(rel, "multiplies complexity on fields that declare no `first` — a two-order page priced at 344421 on the first run");
        }
    }

    static void controller() throws Exception {
        String rel = rel(CONTROLLER);
        String text = code(read(CONTROLLER));
        if (text.contains("@Transactional")) fail(rel, "opens a transaction in a resolver — nested fields resolve after it closes");
        if (text.contains("@GraphQlRepository") || Pattern.compile("import [a-zA-Z.]*(Repository|JpaRepository);").matcher(text).find()) {
            fail(rel, "reaches a repository from the GraphQL adapter — resolvers call input ports only");
        }
        if (!text.contains("@BatchMapping")) fail(rel, "has no @BatchMapping — a child list would be loaded once per parent");
        if (LIST_SCHEMA_MAPPING.matcher(text).find()) {
            fail(rel, "resolves a child list with @SchemaMapping, once per parent — the N+1 a batch removes");
        }
        require(rel, text, "ScrollSubrange", "pages no connection — a growing list needs one");
        if (!Pattern.compile(">\\s*\\w+\\.maxPageSize\\(\\)").matcher(text).find()) {
            fail(rel, "takes `first` with no ceiling — the page is never compared with the maximum");
        }
        if (!Pattern.compile("@MutationMapping\\s*\\n\\s*[A-Za-z]+Payload ").matcher(text).find()) {
            fail(rel, "has a mutation that returns no ...Payload — userErrors have nowhere to go");
        }
        require(rel, text, "@HasRole(", "guards no sensitive field — the root query's check does not reach a field by another path");
    }

    static void resolver() throws Exception {
        String rel = rel(RESOLVER);
        String text = code(read(RESOLVER));
        if (!Pattern.compile("default\\s*->\\s*null;").matcher(text).find()) {
            fail(rel, "resolves everything — a catch-all turns access denials into internal errors and leaks messages");
        }
        require(rel, text, "extends DataFetcherExceptionResolverAdapter", "is not a DataFetcherExceptionResolver");
        require(rel, text, "\"errorCode\"", "puts no errorCode in the extensions — the field a client branches on");
    }

    static void yml() throws Exception {
        String rel = rel(YML);
        String text = read(YML).replaceAll("(?m)^\\s*#.*\\n", "");
        String[] documents = text.split("(?m)^---$");
        String base = documents[0];
        if (!Pattern.compile("introspection:\\n\\s+enabled: false").matcher(base).find()) {
            fail(rel, "leaves introspection on in the base configuration — it is on by default");
        }
        if (!Pattern.compile("graphiql:\\n\\s+enabled: false").matcher(base).find()) {
            fail(rel, "does not write GraphiQL off in the base configuration");
        }
        for (int i = 1; i < documents.length; i++) {
            if (Pattern.compile("(introspection|graphiql):\\n\\s+enabled: true").matcher(documents[i]).find()
                    && !documents[i].contains("on-profile: dev")) {
                fail(rel, "turns introspection or GraphiQL on outside the dev profile");
            }
        }
        if (!Pattern.compile("(?m)^\\s+allowed-methods: POST$").matcher(base).find()) {
            fail(rel, "does not allow exactly POST for CORS — the endpoint is POST only");
        }
        if (Pattern.compile("allowed-origins:\\s*[\"']?\\*").matcher(base).find()) {
            fail(rel, "allows every origin");
        }
        Matcher record = Pattern.compile("record GraphQlLimits\\((.*?)\\)\\s*\\{", Pattern.DOTALL).matcher(code(read(CONFIG)));
        if (!record.find()) {
            fail(rel(CONFIG), "declares no GraphQlLimits record — the limits would be literals");
            return;
        }
        Matcher component = Pattern.compile("(?:int|long|Duration)\\s+([a-zA-Z]+)").matcher(record.group(1));
        while (component.find()) {
            String key = component.group(1).replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT);
            if (!Pattern.compile("(?m)^    " + Pattern.quote(key) + ": \\S").matcher(base).find()) {
                fail(rel, "has no app.graphql." + key + " — GraphQlLimits." + component.group(1) + " would fail the startup");
            }
        }
    }

    static void schema() throws Exception {
        String rel = rel(SCHEMA);
        String text = read(SCHEMA).replaceAll("(?m)^\\s*#.*\\n", "").replaceAll("(?s)\"\"\".*?\"\"\"", "").replaceAll("\"[^\"\\n]*\"", "");
        if (Pattern.compile(":\\s*Float").matcher(text).find()) fail(rel, "uses Float — money is a decimal as String");
        String query = block(text, "type Query");
        if (Pattern.compile("\\)?:\\s*\\[").matcher(query).find()) {
            fail(rel, "has a root query returning a plain list — a list that grows is a connection");
        }
        String mutation = block(text, "type Mutation");
        Matcher field = Pattern.compile("(?m)^\\s+[a-z][A-Za-z]*\\(([^)]*)\\):\\s*([A-Za-z]+)!?").matcher(mutation);
        int mutations = 0;
        while (field.find()) {
            mutations++;
            if (!field.group(1).matches("\\s*input:\\s*[A-Za-z]+Input!\\s*")) fail(rel, "has a mutation whose argument is not one `input: ...Input!`");
            if (!field.group(2).endsWith("Payload")) fail(rel, "has a mutation that does not return a ...Payload");
        }
        if (mutations == 0) fail(rel, "declares no mutation — the payload shape has no exemplar");
        String userError = block(text, "type UserError");
        for (String name : List.of("field:", "code:", "message:")) {
            if (!userError.contains(name)) fail(rel, "UserError has no " + name.replace(":", "") + " field");
        }
    }

    static void tests() throws Exception {
        String http = code(read(HTTP));
        require(rel(HTTP), http, "WebEnvironment.RANDOM_PORT", "does not run a real server — the filter chain would not run");
        require(rel(HTTP), http, "ErrorType.FORBIDDEN", "proves no field-level denial");
        require(rel(HTTP), http, "containsIgnoringCase(\"depth\")", "proves no depth limit");
        if (http.contains("@WithMockUser")) fail(rel(HTTP), "fakes the authentication — the real chain is what this level proves");
        String slice = code(read(SLICE));
        require(rel(SLICE), slice, "@GraphQlTest", "is not a GraphQL slice");
        require(rel(SLICE), slice, "times(1)", "does not prove one batch call per request");
        if (slice.contains("ErrorType.FORBIDDEN") || slice.contains("ErrorType.UNAUTHORIZED")) {
            fail(rel(SLICE), "asserts access in the slice, where no filter chain and no method security run");
        }
        String compat = code(read(COMPAT));
        Matcher snapshot = Pattern.compile("SNAPSHOT = Path\\.of\\(\"([^\"]+)\"\\)").matcher(compat);
        if (!snapshot.find()) {
            fail(rel(COMPAT), "names no snapshot path");
        } else if (snapshot.group(1).contains("src/test/resources") || snapshot.group(1).contains("src/main/resources")) {
            fail(rel(COMPAT), "keeps the snapshot on a classpath — Boot would load it as schema, every type defined twice");
        }
        require(rel(COMPAT), compat, "includeAstDefinitionComments(false)", "prints comments — two runs over the same files differ");
        require(rel(COMPAT), compat, "DiffCategory.DEPRECATION_REMOVED", "admits no removal at all, or every one — the policy is deprecate first");
    }

    static void blueprints() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(BLUEPRINTS)) {
            files = s.filter(p -> p.toString().endsWith(".yaml") && !p.toString().replace('\\', '/').contains("/references/"))
                    .sorted().toList();
        }
        for (Path file : files) {
            String text = read(file);
            String rel = rel(file);
            if (!Pattern.compile("(?m)^    [a-z.]*\\.graphql: \"[a-z.]+\"").matcher(text).find()) {
                fail(rel, "has no packages.map key ending in .graphql — the adapter would have no package, and api-graphql.md no territory");
            }
            Matcher application = Pattern.compile("(?ms)^  - id: application\\n(.*?)(?=^  - id: |^[a-z])").matcher(text);
            if (application.find() && application.group(1).contains("forbidden_imports:")) {
                for (String prefix : List.of("\"org.springframework.graphql..\"", "\"graphql..\"")) {
                    if (!application.group(1).contains(prefix)) {
                        fail(rel, "lets the application module import " + prefix + " — a resolver reaches it through the input port");
                    }
                }
            }
            Matcher domain = Pattern.compile("(?ms)^  - id: (domain|core)\\n(.*?)(?=^  - id: |^  #|^[a-z])").matcher(text);
            if (domain.find() && domain.group(2).contains("forbidden_imports") && !domain.group(2).contains("\"graphql..\"")) {
                fail(rel, "lets the domain import graphql-java");
            }
            if (text.contains("    graphql.graphql:") && !text.contains("    - from: graphql\n")) {
                fail(rel, "has a graphql package with no dependency rule of its own");
            }
        }
    }

    static String block(String text, String header) {
        int at = text.indexOf(header + " {");
        if (at < 0) return "";
        int end = text.indexOf("}", at);
        return text.substring(at, end < 0 ? text.length() : end);
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
