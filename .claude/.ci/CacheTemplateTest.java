///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the cache exemplars carry every decision `.claude/rules/caching.md` makes
// explicit, and that no other exemplar caches at all.
//
// Why this test exists: every default of a cache library is a silent choice — a Redis cache that
// never expires, a Caffeine cache that grows until the heap does, Java serialization, a cache
// error that fails a read the database could answer, eviction before the commit. The executor
// copies an exemplar's shape, so one default left in a template ships into every project that
// caches (0144). Nothing at runtime would say so: each of these compiles, starts and passes a test
// that disables caching.
//
// What it holds, each failure named by file:
//   - every exemplar declaring `@EnableCaching` registers a `CacheErrorHandler`, and none sits
//     next to `@SpringBootApplication` anywhere under `.claude/skills/**/templates`;
//   - the Caffeine manager builds every cache with `expireAfterWrite` and `maximumSize`, in static
//     mode, refusing nulls, transaction-aware;
//   - the Redis manager sets an expiry, a typed serializer, refused nulls, a versioned prefix,
//     `SCAN` clearing, transaction awareness, statistics, and no cache created on demand — and no
//     exemplar names the JDK or the deprecated Jackson 2 serializers;
//   - every `@Cacheable` names its `key`, and none combines `sync = true` with `unless`, which the
//     framework refuses at startup;
//   - a cache exemplar imports no other adapter's package: the decorator depends on the port;
//   - every cache name a `CacheNames` block declares has an `app.cache.caches` entry with a `ttl`
//     and a `maximum-size` in `application-cache.yml.example`, and every `CacheNames.X` the
//     exemplars use is declared; the Redis password placeholder has no default, and the client
//     timeouts are set;
//   - no exemplar outside `cache-architect/templates/` carries a cache annotation.
//
// Offline and in milliseconds. The exemplars are shape references, not compiled here: whether
// their imports resolve is `validate` › `exemplar-imports`'s job.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class CacheTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path CACHE = SKILLS.resolve("cache-architect/templates");
    static final Path CAFFEINE = CACHE.resolve("CaffeineCacheConfig.java.example");
    static final Path REDIS = CACHE.resolve("RedisCacheConfig.java.example");
    static final Path YML = CACHE.resolve("application-cache.yml.example");

    static final Pattern ANNOTATION = Pattern.compile("@(?:[\\w.]+\\.)?(Cacheable|CachePut|CacheEvict|Caching|EnableCaching)\\b");
    static final Pattern CACHEABLE = Pattern.compile("@Cacheable\\(([^)]*)\\)", Pattern.DOTALL);
    static final Pattern DECLARED_NAME = Pattern.compile("static final String (\\w+) = \"([a-z0-9-]+)\";");
    static final Pattern USED_NAME = Pattern.compile("CacheNames\\.([A-Z_]+)");
    static final Pattern FOREIGN_ADAPTER = Pattern.compile(
            "^import [\\w.]*\\.(adapter\\.out\\.(persistence|http|messaging)|infrastructure\\.(persistence|http|messaging))\\.",
            Pattern.MULTILINE);

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".java.example"))
                    .sorted().toList();
        }
        List<Path> cacheExemplars = exemplars.stream().filter(p -> p.startsWith(CACHE)).toList();
        if (cacheExemplars.isEmpty()) {
            System.out.println("❌ No Java exemplar under " + CACHE);
            System.exit(1);
        }

        for (Path file : exemplars) {
            String text = read(file);
            String rel = rel(file);
            if (!file.startsWith(CACHE) && ANNOTATION.matcher(code(text)).find()) {
                fail(rel, "carries a cache annotation outside cache-architect/templates — caching lives in the cache package only (caching.md § Boundary)");
            }
            if (ANNOTATION.matcher(code(text)).results().anyMatch(m -> m.group(1).equals("EnableCaching")) && code(text).contains("@SpringBootApplication")) {
                fail(rel, "puts @EnableCaching next to @SpringBootApplication — caching becomes mandatory in every test context");
            }
            if (text.contains("JdkSerializationRedisSerializer") || text.contains("Jackson2JsonRedisSerializer")) {
                fail(rel, "names the JDK or a deprecated Jackson 2 Redis serializer — one typed JacksonJsonRedisSerializer per cache");
            }
        }

        Set<String> declared = new TreeSet<>();
        Set<String> declaredValues = new TreeSet<>();
        for (Path file : cacheExemplars) {
            String text = read(file);
            String rel = rel(file);
            if (text.contains("@EnableCaching") && !text.contains("CacheErrorHandler errorHandler()")) {
                fail(rel, "declares @EnableCaching with no CacheErrorHandler — a cache failure would fail the read (caching.md § Failure)");
            }
            Matcher cacheable = CACHEABLE.matcher(text);
            while (cacheable.find()) {
                String attrs = cacheable.group(1);
                if (!attrs.matches("(?s).*\\bkey\\s*=.*")) {
                    fail(rel, "has a @Cacheable without an explicit key: " + oneLine(attrs));
                }
                if (attrs.matches("(?s).*\\bsync\\s*=\\s*true.*") && attrs.matches("(?s).*\\bunless\\s*=.*")) {
                    fail(rel, "combines sync = true with unless on one @Cacheable — the framework refuses it at startup");
                }
            }
            if (FOREIGN_ADAPTER.matcher(text).find()) {
                fail(rel, "imports another adapter's package — a decorator depends on the port, and is wired by bean name");
            }
            Matcher name = DECLARED_NAME.matcher(text);
            while (name.find()) {
                declared.add(name.group(1));
                declaredValues.add(name.group(2));
            }
        }
        for (Path file : cacheExemplars) {
            Matcher used = USED_NAME.matcher(read(file));
            while (used.find()) {
                if (!declared.contains(used.group(1))) {
                    fail(rel(file), "uses CacheNames." + used.group(1) + ", which no CacheNames block declares");
                }
            }
        }

        caffeine(read(CAFFEINE));
        redis(read(REDIS));
        yml(read(YML), declaredValues);

        if (!failures.isEmpty()) {
            System.out.println("❌ Cache exemplars leave a library default undecided — " + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/caching.md · design: .claude/decisions/0144-cache-architect-skill.md");
            System.exit(1);
        }
        System.out.println("✅ " + cacheExemplars.size() + " cache exemplars decide every default, "
                + declaredValues.size() + " caches configured, and no other exemplar caches.");
    }

    static void caffeine(String text) {
        String rel = rel(CAFFEINE);
        require(rel, text, ".expireAfterWrite(", "builds a cache without expireAfterWrite — no expiry is a copy that never refreshes");
        require(rel, text, ".maximumSize(", "builds a cache without maximumSize — an unbounded local cache grows until the heap does");
        require(rel, text, "setCacheNames(List.of())", "leaves the manager in dynamic mode — an undeclared name gets an unbounded cache with no expiry");
        require(rel, text, "setAllowNullValues(false)", "accepts nulls — caching an absence is a decision, never a default");
        require(rel, text, "TransactionAwareCacheManagerProxy", "is not transaction-aware — an eviction before the commit lets a concurrent read store the old row");
        require(rel, text, ".recordStats()", "does not record statistics — the metrics binder reports zeros");
    }

    static void redis(String text) {
        String rel = rel(REDIS);
        require(rel, text, ".entryTtl(", "sets no expiry — Redis stores without one by default");
        require(rel, text, "new JacksonJsonRedisSerializer<>(", "has no typed serializer per cache — the default is Java serialization");
        require(rel, text, ".disableCachingNullValues()", "caches nulls — the library's default");
        require(rel, text, ".prefixCacheNameWith(", "has no versioned key prefix — a rolling deploy reads the other version's bytes");
        require(rel, text, "BatchStrategies.scan(", "clears with KEYS — blocks the server over a large keyspace");
        require(rel, text, ".transactionAware()", "is not transaction-aware");
        require(rel, text, ".enableStatistics()", "has statistics off — nothing for the metrics binder to report");
        require(rel, text, ".disableCreateOnMissingCache()", "creates caches on demand — an undeclared name gets the defaults");
    }

    static void yml(String text, Set<String> names) {
        String rel = rel(YML);
        require(rel, text, "password: ${REDIS_PASSWORD}\n", "gives the Redis password placeholder a default, or none at all (secrets.md)");
        require(rel, text, "timeout: ", "leaves the Redis client on its own command timeout");
        require(rel, text, "connect-timeout: ", "leaves the Redis connect timeout on its default");
        String caches = section(text, "    caches:");
        for (String name : names) {
            String entry = entry(caches, name);
            if (entry == null) {
                fail(rel, "has no app.cache.caches entry for cache '" + name + "' — it would fail on first use, or worse, get defaults");
                continue;
            }
            if (!entry.contains("ttl:")) fail(rel, "cache '" + name + "' has no ttl");
            if (!entry.contains("maximum-size:")) fail(rel, "cache '" + name + "' has no maximum-size");
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

    static String entry(String caches, String name) {
        int at = caches.indexOf("      " + name + ":\n");
        if (at < 0) return null;
        StringBuilder out = new StringBuilder();
        for (String line : caches.substring(at).split("\n", -1)) {
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

    /** The text without its comments: a header that names an annotation does not use it. */
    static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static String rel(Path file) {
        return file.toString().replace('\\', '/');
    }

    static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
