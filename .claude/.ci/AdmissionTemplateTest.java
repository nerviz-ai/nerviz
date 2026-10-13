///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the inbound admission exemplars carry what `.claude/rules/inbound-admission.md`
// decides — a quota per caller that never leaks, never waits and is divided per replica, a
// concurrency cap in its own namespace that runs ahead of the idempotency claim, and both
// rejections with `Retry-After`.
//
// Why this test exists: every trap of admission is silent. A `RateLimiterRegistry` per caller keeps
// every client forever and publishes a metric series for each; an unbounded or short-lived limiter
// cache leaks memory or hands a quota back early; a limiter or a cap that waits holds the request
// thread it was bought to free; a quota written per instance without the division multiplies by the
// replica count; a bulkhead named like an outbound one shares its permits; an `@Order` on
// `IdempotencyAspect` or a `bulkhead-aspect-order` makes the rejected call claim the idempotency key
// (proven against Boot 4.1.1 / Resilience4j 2.4.0, 0151 § Verified); a rejection with no
// `Retry-After` synchronizes every client's retry; and a test of the cap in a web slice passes with
// the cap absent, since the aspect is not part of one. The executor copies an exemplar's shape, so
// one of these left in a template ships into every project.
//
// What it holds, each failure named by file:
//   - no `.java.example` uses a `RateLimiterRegistry`;
//   - the admission exemplar keeps one `RateLimiter.of` per caller in a Caffeine cache bounded by
//     size with an expiry after access, never waits, divides the stated quota by the replicas, keys
//     on the principal (anonymous excluded) or the client address and never on a raw header, and
//     counts nothing of its own;
//   - every `@Bulkhead` of the REST, GraphQL and test exemplars is named `inbound-…`, and every
//     inbound bulkhead instance of the admission yml waits zero; no yml exemplar sets
//     `bulkhead-aspect-order`, and `IdempotencyAspect` declares no `@Order`;
//   - `ApiExceptionHandler` maps the spent quota to 429 `RATE_LIMITED` and the full cap to 503
//     `SERVICE_BUSY` with `traceId`, both with `Retry-After`;
//   - `GraphQlExceptionResolver` maps the full cap to `SERVICE_BUSY`, `retryable`, with the wait;
//   - `AdmissionIT` runs on a real server, never a web slice, and proves 429, 503, 401 and both
//     error codes.
//
// Offline and in milliseconds. Whether the imports resolve is `validate` › `exemplar-imports`'s job,
// and whether the shapes run was checked against a real Initializr project (0151 § Verified).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class AdmissionTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path REST = SKILLS.resolve("rest-api-architect/templates");
    static final Path GRAPHQL = SKILLS.resolve("graphql-api-architect/templates");
    static final Path TESTS = SKILLS.resolve("test-architect/templates");
    static final Path CONFIG = REST.resolve("AdmissionConfig.java.example");
    static final Path YML = REST.resolve("application-admission.yml.example");
    static final Path HANDLER = REST.resolve("ApiExceptionHandler.java.example");
    static final Path IDEMPOTENCY = REST.resolve("IdempotencyAspect.java.example");
    static final Path RESOLVER = GRAPHQL.resolve("GraphQlExceptionResolver.java.example");
    static final Path IT = TESTS.resolve("AdmissionIT.java.example");

    static final Pattern BULKHEAD = Pattern.compile("@(?:[\\w.]+\\.)?Bulkhead\\(\\s*name\\s*=\\s*\"([^\"]*)\"");
    static final Pattern INBOUND_INSTANCE = Pattern.compile("(?m)^(\\s+)(inbound-[\\w-]+):\\s*\\n((?:\\1\\s+.*\\n?)*)");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (Path required : List.of(CONFIG, YML, HANDLER, IDEMPOTENCY, RESOLVER, IT)) {
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
            String rel = rel(file);
            if (name.endsWith(".java.example")) {
                String code = code(read(file));
                if (code.contains("RateLimiterRegistry")) {
                    fail(rel, "uses a RateLimiterRegistry — its store keeps every caller forever, and its metrics name each one");
                }
                boolean inbound = file.startsWith(REST) || file.startsWith(GRAPHQL) || file.startsWith(TESTS);
                Matcher m = BULKHEAD.matcher(inbound ? code : "");
                while (m.find()) {
                    if (!m.group(1).startsWith("inbound-")) {
                        fail(rel, "names an inbound bulkhead `" + m.group(1) + "` outside `inbound-` — an outbound instance of that name shares its permits");
                    }
                }
            } else if (name.matches(".*\\.(yml|yaml|properties)\\.example")
                    && read(file).replaceAll("(?m)#.*$", "").contains("bulkhead-aspect-order")) {
                fail(rel, "sets bulkhead-aspect-order — the cap must keep running ahead of IdempotencyAspect");
            }
        }
        config();
        yml();
        idempotency();
        handler();
        resolver();
        integrationTest();
        if (!failures.isEmpty()) {
            System.out.println("❌ Admission exemplars leak, wait, share a cap or reject without Retry-After — "
                    + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/inbound-admission.md · design: .claude/decisions/0151-inbound-admission-in-rest-and-graphql.md");
            System.exit(1);
        }
        System.out.println("✅ Admission exemplars keep one bounded, non-waiting limiter per caller and replica, an inbound cap"
                + " ahead of the idempotency claim, and Retry-After on every rejection.");
    }

    static void config() throws Exception {
        String rel = rel(CONFIG);
        String text = code(read(CONFIG));
        require(rel, text, "RateLimiter.of(", "does not create one limiter per caller");
        require(rel, text, "Caffeine.newBuilder()", "does not keep the limiters in a Caffeine cache");
        require(rel, text, ".maximumSize(", "leaves the limiter cache unbounded");
        require(rel, text, ".expireAfterAccess(", "never expires a limiter — every caller retained");
        require(rel, text, ".timeoutDuration(Duration.ZERO)", "lets an over-quota request wait for a permit");
        require(rel, text, "Math.ceilDiv(quota.limit(), properties.replicas())", "does not divide the stated quota per replica");
        require(rel, text, "AnonymousAuthenticationToken", "keys an anonymous caller as a principal");
        require(rel, text, "getRemoteAddr()", "does not key a public caller on its address");
        require(rel, text, "addInterceptor(", "registers no interceptor — the quota never runs");
        if (text.contains("getHeader(")) {
            fail(rel, "reads a raw header — a caller key the client sets freely");
        }
        if (text.contains("MeterRegistry") || text.contains("Counter")) {
            fail(rel, "counts rejections itself — they are read from the request metric's status (observability.md § Metrics)");
        }
    }

    static void yml() throws Exception {
        String rel = rel(YML);
        String text = read(YML).replaceAll("(?m)^\\s*#.*$\\n?", "");
        Matcher m = INBOUND_INSTANCE.matcher(text);
        int instances = 0;
        while (m.find()) {
            instances++;
            if (!Pattern.compile("max-wait-duration:\\s*0\\s*$", Pattern.MULTILINE).matcher(m.group(3)).find()) {
                fail(rel, "inbound bulkhead `" + m.group(2) + "` does not wait zero — a waiting request holds the thread the cap protects");
            }
        }
        if (instances == 0) {
            fail(rel, "shows no `inbound-` bulkhead instance");
        }
    }

    static void idempotency() throws Exception {
        if (code(read(IDEMPOTENCY)).contains("@Order")) {
            fail(rel(IDEMPOTENCY), "declares an @Order — it may then run ahead of the cap, and a rejected call claims the key");
        }
    }

    static void handler() throws Exception {
        String rel = rel(HANDLER);
        String quota = method(code(read(HANDLER)), "QuotaExceededException.class");
        require(rel, quota, "HttpStatus.TOO_MANY_REQUESTS", "does not answer a spent quota with 429");
        require(rel, quota, "\"RATE_LIMITED\"", "does not give the spent quota its errorCode");
        require(rel, quota, "HttpHeaders.RETRY_AFTER", "answers a spent quota without Retry-After");
        String busy = method(code(read(HANDLER)), "BulkheadFullException.class");
        require(rel, busy, "HttpStatus.SERVICE_UNAVAILABLE", "does not answer a full cap with 503");
        require(rel, busy, "\"SERVICE_BUSY\"", "does not give the full cap its errorCode");
        require(rel, busy, "\"traceId\"", "answers a full cap, a 5xx, without traceId");
        require(rel, busy, "HttpHeaders.RETRY_AFTER", "answers a full cap without Retry-After");
    }

    static void resolver() throws Exception {
        String rel = rel(RESOLVER);
        String text = code(read(RESOLVER));
        require(rel, text, "case BulkheadFullException", "does not map a full cap");
        require(rel, text, "\"SERVICE_BUSY\"", "does not give the full cap its errorCode");
        require(rel, text, "\"retryable\", true", "does not mark the full cap retryable");
        require(rel, text, "\"retryAfterSeconds\"", "does not announce the wait of a full cap");
    }

    static void integrationTest() throws Exception {
        String rel = rel(IT);
        String text = code(read(IT));
        require(rel, text, "WebEnvironment.RANDOM_PORT", "does not run on a real server");
        if (text.contains("@WebMvcTest") || text.contains("@GraphQlTest")) {
            fail(rel, "tests admission in a slice — the bulkhead aspect is not part of one");
        }
        for (String needle : List.of("isEqualTo(429)", "isEqualTo(503)", "isEqualTo(401)", "RATE_LIMITED", "SERVICE_BUSY", "Retry-After")) {
            require(rel, text, needle, "does not prove an admission case");
        }
    }

    /** The text from an `@ExceptionHandler(<type>)` to the next handler, or empty when absent. */
    static String method(String code, String type) {
        int start = code.indexOf("@ExceptionHandler(" + type + ")");
        if (start < 0) return "";
        int next = code.indexOf("@ExceptionHandler(", start + 1);
        return code.substring(start, next < 0 ? code.length() : next);
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
