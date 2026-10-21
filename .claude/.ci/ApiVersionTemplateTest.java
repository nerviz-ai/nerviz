///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the REST exemplars carry the API versioning `.claude/rules/api-rest.md`
// § Versioning decides, and that no exemplar binds a route, a request rule or a path set to one
// API version.
//
// Why this test exists: a version spelled in one place keeps working until the first `/v2` — and
// then fails silently, in three ways the first run of these exemplars showed or the norm names. A
// class mapping that still spells `/api/v1` under the configured prefix serves `/api/v1/api/v1/...`;
// a request rule on `/api/v1/...` leaves the `/v2` of a role-protected route to the last rule,
// "authenticated"; a path set of protected `POST`s lets the `/v2` skip the `Idempotency-Key` check.
// And two shapes of the configuration were wrong on the first run against Boot 4.1.1 / Framework
// 7.0.9 / springdoc 3.1.1: a prefix `/api/{version}`, which springdoc documents as `/api/1/users`,
// and a prefix scoped by `@RestController` alone, which reaches springdoc's own endpoint and turns
// `/v3/api-docs` into a 404 (0150). The executor copies an exemplar's shape, so one of these left
// in a template ships into every project.
//
// What it holds, each failure named by file:
//   - no `.java.example` maps a path or a request rule naming `/api/v<N>`, nor declares a
//     `BASE_PATH` that spells it;
//   - every `@Get/Post/Put/Patch/DeleteMapping` of an exemplar declares a `version`;
//   - the config exemplar adds the `/api/v{version}` prefix scoped to the application base package, reads the
//     version through the two-argument `usePathSegment`, requires it, detects no supported version
//     from the mappings, sets no default, and never calls `setSunsetLink`;
//   - no yml or properties exemplar sets `spring.mvc.apiversion.*` — a second resolver;
//   - `ApiExceptionHandler` answers `InvalidApiVersionException` with `violations` on `version` and
//     no `errorCode`;
//   - the observation convention reads the `ApiVersionHolder`, never the raw path;
//   - the controller builds `Location` from the current request's path;
//   - the `Idempotency-Key` interceptor finds protected routes by `@Idempotent`, with no path set;
//   - the controller test calls a literal public URI and proves the unsupported-version case.
//
// Offline and in milliseconds. Whether the exemplars' imports resolve is `validate` ›
// `exemplar-imports`'s job, and whether they run was checked against a real Initializr project when
// they were written (0150 § CI coverage).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class ApiVersionTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path REST = SKILLS.resolve("rest-api-architect/templates");
    static final Path TESTS = SKILLS.resolve("test-architect/templates");
    static final Path CONFIG = REST.resolve("ApiVersioningConfig.java.example");
    static final Path CONVENTION = REST.resolve("ApiVersionObservationConvention.java.example");
    static final Path HANDLER = REST.resolve("ApiExceptionHandler.java.example");
    static final Path CONTROLLER = REST.resolve("Controller.java.example");
    static final Path INTERCEPTOR = REST.resolve("IdempotencyKeyInterceptor.java.example");
    static final Path CONTROLLER_TEST = TESTS.resolve("ControllerTest.java.example");

    static final Pattern VERSIONED_PATH = Pattern.compile("\"/api/v[0-9]");
    static final Pattern MAPPING_WITH_VERSIONED_PATH =
            Pattern.compile("@(Request|Get|Post|Put|Patch|Delete)Mapping\\([^)]*\"/api/v[0-9]");
    static final Pattern RULE_WITH_VERSIONED_PATH = Pattern.compile("requestMatchers\\([^;]*\"/api/v[0-9]");
    static final Pattern BASE_PATH_WITH_VERSION = Pattern.compile("BASE_PATH\\s*=\\s*\"/api/v[0-9]");
    static final Pattern HANDLER_MAPPING = Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping\\b(\\([^)]*\\))?");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (Path required : List.of(CONFIG, CONVENTION, HANDLER, CONTROLLER, INTERCEPTOR, CONTROLLER_TEST)) {
            if (!Files.isRegularFile(required)) {
                System.out.println("❌ Missing exemplar " + rel(required));
                System.exit(1);
            }
        }
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".example"))
                    .sorted().toList();
        }
        for (Path file : exemplars) {
            String name = file.getFileName().toString();
            if (name.endsWith(".java.example")) {
                everyExemplar(file, code(read(file)));
            } else if (name.matches(".*\\.(yml|yaml|properties)\\.example") && setsMvcApiVersion(read(file))) {
                fail(rel(file), "sets spring.mvc.apiversion.* — Boot would register a second resolver beside ApiVersioningConfig's");
            }
        }
        config();
        convention();
        handler();
        controller();
        interceptor();
        controllerTest();
        if (!failures.isEmpty()) {
            System.out.println("❌ REST exemplars bind a route to one API version or leave a versioning default undecided — "
                    + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/api-rest.md § Versioning · design: .claude/decisions/0150-api-versioning-in-rest-api-architect.md");
            System.exit(1);
        }
        System.out.println("✅ REST exemplars version every handler from one configuration; no mapping, request rule or path set"
                + " is bound to one API version.");
    }

    static void everyExemplar(Path file, String code) {
        String rel = rel(file);
        if (MAPPING_WITH_VERSIONED_PATH.matcher(code).find()) {
            fail(rel, "spells /api/v<N> in a mapping — the prefix is ApiVersioningConfig's, and here it would double");
        }
        if (RULE_WITH_VERSIONED_PATH.matcher(code).find()) {
            fail(rel, "binds a request rule to one API version — a /v2 of the route falls to the last rule (authorization.md § Default access)");
        }
        if (BASE_PATH_WITH_VERSION.matcher(code).find()) {
            fail(rel, "declares a BASE_PATH spelling /api/v<N> — a class mapping carries the resource alone");
        }
        // Only an inbound controller: an outbound Feign interface maps with the same annotations.
        Matcher m = HANDLER_MAPPING.matcher(code.contains("@RestController") ? code : "");
        while (m.find()) {
            String args = m.group(2) == null ? "" : m.group(2);
            if (!args.contains("version")) {
                fail(rel, "has a handler mapping with no `version` — it answers every version (" + m.group().strip() + ")");
            }
        }
    }

    /**
     * `spring.mvc.apiversion.*` only — `spring.http.serviceclient.<group>.apiversion` is an
     * outbound client inserting a provider's version, and stays. In YAML, an `apiversion` key
     * whose ancestor is `mvc`.
     */
    static boolean setsMvcApiVersion(String text) {
        if (text.contains("spring.mvc.apiversion")) return true;
        Deque<String[]> stack = new ArrayDeque<>();
        for (String line : text.split("\n")) {
            String stripped = line.replaceAll("#.*$", "");
            if (stripped.isBlank() || !stripped.contains(":")) continue;
            int indent = stripped.length() - stripped.stripLeading().length();
            String key = stripped.strip().split(":", 2)[0].strip();
            while (!stack.isEmpty() && Integer.parseInt(stack.peek()[0]) >= indent) stack.pop();
            if (key.equals("apiversion") && stack.stream().anyMatch(e -> e[1].equals("mvc"))) return true;
            stack.push(new String[] {Integer.toString(indent), key});
        }
        return false;
    }

    static void config() throws Exception {
        String rel = rel(CONFIG);
        String text = code(read(CONFIG));
        require(rel, text, "addPathPrefix(\"/api/v{version}\"",
                "does not add the /api/v{version} prefix — /api/{version} is documented by springdoc as /api/1/...");
        require(rel, text, "forBasePackage(APPLICATION_PACKAGE)",
                "does not scope the prefix to the application's base package — by annotation alone springdoc's own @RestController gets it and /v3/api-docs answers 404; by this class's package, a module's controllers in another REST package get none (0180)");
        if (text.contains("forBasePackageClass(")) {
            fail(rel, "scopes the prefix to one class's package — a modular monolith's other modules keep unversioned paths (issue #167, 0180)");
        }
        if (!Pattern.compile("usePathSegment\\([^;]*,").matcher(text).find()) {
            fail(rel, "reads the version from every path — the two-argument usePathSegment keeps /actuator and /v3/api-docs out");
        }
        if (text.contains("setVersionRequired(true)")) {
            fail(rel, "requires a version on every handler — a library controller outside /api/ (Springwolf's /springwolf/docs) throws MissingApiVersionException and answers 500 (0167)");
        }
        require(rel, text, "setVersionRequired(false)", "does not state that the flag stays off — the /api/v{version} prefix already requires the segment (0167)");
        require(rel, text, "detectSupportedVersions(false)",
                "detects supported versions from the mappings — a retired version stays alive while its mapping does");
        require(rel, text, "addSupportedVersions(", "lists no supported version");
        if (text.contains("setDefaultVersion(")) {
            fail(rel, "sets a default version — the norm requires one on every request, and required + default fail the startup");
        }
        if (text.contains("setSunsetLink(")) {
            fail(rel, "writes a Link rel=\"sunset\" — api-rest.md § Versioning writes the deprecation link alone");
        }
        require(rel, text, "StandardApiVersionDeprecationHandler", "declares no deprecation handler bean");
    }

    static void convention() throws Exception {
        String rel = rel(CONVENTION);
        String text = code(read(CONVENTION));
        require(rel, text, "HandlerMapping.API_VERSION_ATTRIBUTE", "does not read the resolved version");
        require(rel, text, "instanceof ApiVersionHolder",
                "treats the attribute as the version — it holds an ApiVersionHolder, whose toString is an object id");
        require(rel, text, "hasVersion()", "tags an unsupported version — a client guessing versions mints a series per guess");
        if (text.contains("getRequestURI()") || text.contains("getServletPath()")) {
            fail(rel, "reads the version from the raw path — unbounded cardinality");
        }
    }

    static void handler() throws Exception {
        String rel = rel(HANDLER);
        String text = code(read(HANDLER));
        Matcher m = Pattern.compile("@ExceptionHandler\\(InvalidApiVersionException\\.class\\)(?s)(.*?)\\n    }").matcher(text);
        if (!m.find()) {
            fail(rel, "has no handler for InvalidApiVersionException — the framework's own 400 skips the project's body");
            return;
        }
        String body = m.group(1);
        if (!body.contains("new Violation(\"version\"")) {
            fail(rel, "does not answer an unsupported version with `violations` on `version`");
        }
        if (body.contains("errorCode")) {
            fail(rel, "puts errorCode on an unsupported version — it is structural (api-rest.md § Error body)");
        }
    }

    static void controller() throws Exception {
        String rel = rel(CONTROLLER);
        String text = code(read(CONTROLLER));
        require(rel, text, "fromCurrentRequestUri().build().getPath()",
                "does not build Location from the request's own path — a v2 call would answer a v1 location");
        if (text.contains(".toUriString()") || text.contains("fromCurrentRequest().")) {
            fail(rel, "builds an absolute Location — it leaks the internal host behind a proxy");
        }
    }

    static void interceptor() throws Exception {
        String rel = rel(INTERCEPTOR);
        String text = code(read(INTERCEPTOR));
        require(rel, text, "hasMethodAnnotation(Idempotent.class)", "does not find protected routes by @Idempotent");
        if (Pattern.compile("Set\\.of\\(\"/").matcher(text).find()) {
            fail(rel, "keeps a set of protected paths — bound to one API version, the /v2 of a protected POST skips the key check");
        }
    }

    static void controllerTest() throws Exception {
        String rel = rel(CONTROLLER_TEST);
        String text = code(read(CONTROLLER_TEST));
        if (text.contains("BASE_PATH")) {
            fail(rel, "builds URIs from the controller's constant — it no longer carries the version prefix");
        }
        if (!VERSIONED_PATH.matcher(text).find()) {
            fail(rel, "calls no literal public URI (/api/v1/...)");
        }
        require(rel, text, "jsonPath(\"$.violations[0].field\").value(\"version\")",
                "does not prove the unsupported-version case");
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
