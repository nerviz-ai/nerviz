///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves no Java exemplar breaks the adapter boundary of `hexagonal-single-module` —
// a class in one `adapter.*` package importing another adapter package — and that the blueprint
// itself lists every adapter package against every other one.
//
// Why this test exists: `hexagonal` writes `"adapters/*" -> "adapters/*"` once, because each
// adapter is a Maven module there. A package has no such wildcard, so `hexagonal-single-module`
// lists ten adapter packages against the other nine by hand (decision 0165), and no step ran the
// derived boundary map over the exemplars: a template importing a sibling adapter would be
// refused by the `check` hook in every generated project that chose this blueprint, and the
// one place the matrix could lose an entry — a typo in a 90-name list — fails in silence.
//
// What it holds, each failure named by file and line:
//   - every `adapter.*` key of `packages.map` has a `dependency_rules` entry whose `from` is
//     that key, and whose `to` lists every other adapter key and nothing else — save the one
//     edge in ALLOWED: a GraphQL resolver uses the security and admission wiring that lives in
//     the REST adapter's `security` and `admission` subpackages (authorization.md), as it does in
//     `hexagonal`, where the GraphQL adapter compiles into `bootstrap`;
//   - no `.java.example` declares a package inside one adapter and imports a type from another —
//     a subpackage of the same adapter (`persistence.shared` from `persistence.order`) is fine.
// The rules are read from the blueprint, not repeated here, so a new adapter package fails the
// first check until its entry exists.
//
// It also runs both checks over fixtures that must fail, so a parser that stops matching cannot
// pass for a clean tree.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class HexagonalSingleModuleTemplateTest {

    static final Path BLUEPRINT = Paths.get(
            ".claude/blueprints/hexagonal-single-module/hexagonal-single-module.yaml");
    static final Path SKILLS = Paths.get(".claude/skills");

    // The only adapter-to-adapter edge the blueprint leaves open: from -> to.
    static final Map<String, Set<String>> ALLOWED = Map.of("adapter.in.graphql", Set.of("adapter.in.rest"));

    static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)");
    static final Pattern RULE = Pattern.compile("-\\s+from:\\s*(\\S+)\\s*\\n\\s*to:\\s*\\[([^\\]]*)\\]");
    static final Pattern MAP_KEY = Pattern.compile("^\\s{4}(adapter\\.(?:in|out)\\.\\w+):", Pattern.MULTILINE);

    public static void main(String[] args) throws Exception {
        String yaml = Files.readString(BLUEPRINT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> failures = new ArrayList<>();

        Set<String> adapters = new TreeSet<>();
        Matcher k = MAP_KEY.matcher(yaml);
        while (k.find()) adapters.add(k.group(1));
        Map<String, Set<String>> forbidden = rules(yaml);

        // Fixtures first: the checks must flag what they exist to flag.
        Map<String, Set<String>> broken = new TreeMap<>();
        forbidden.forEach((from, to) -> broken.put(from, new TreeSet<>(to)));
        String dropped = adapters.iterator().next();
        broken.get(dropped).remove(adapters.stream().filter(a -> !a.equals(dropped)).findFirst().get());
        expect(!matrix(adapters, broken).isEmpty(), "a rule missing one target is not flagged", failures);
        expect(!matrix(adapters, new TreeMap<>()).isEmpty(), "a missing rule is not flagged", failures);
        expect(!crossings("p.java", List.of(
                        "package com.x.adapter.out.cache.order;",
                        "import com.x.adapter.out.persistence.order.OrderEntity;"), adapters, forbidden).isEmpty(),
                "a cache decorator importing the persistence adapter is not flagged", failures);
        expect(crossings("p.java", List.of(
                        "package com.x.adapter.out.persistence.order;",
                        "import com.x.adapter.out.persistence.shared.AssignedIdEntity;",
                        "import com.x.application.port.out.OrderRepositoryPort;",
                        "import org.springframework.stereotype.Component;"), adapters, forbidden).isEmpty(),
                "a subpackage of the same adapter, a port and a library are flagged", failures);

        // The blueprint's own matrix.
        expect(adapters.size() >= 2, "no adapter package found in packages.map — the parser stopped matching", failures);
        failures.addAll(matrix(adapters, forbidden));

        // Every exemplar.
        int files = 0, imports = 0;
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().endsWith(".java.example")).sorted().toList();
        }
        for (Path file : exemplars) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            files++;
            imports += (int) lines.stream().filter(l -> IMPORT.matcher(l).find()).count();
            failures.addAll(crossings(file.toString().replace('\\', '/'), lines, adapters, forbidden));
        }
        expect(files > 0 && imports > 0, "no exemplar import was read — the scan stopped matching", failures);

        if (!failures.isEmpty()) {
            System.err.println("❌ hexagonal-single-module: " + failures.size() + " problem(s)");
            failures.forEach(f -> System.err.println("  " + f));
            System.err.println("Rule: .claude/blueprints/hexagonal-single-module/hexagonal-single-module.yaml"
                    + " dependency_rules — adapters talk through the application, never to each other.");
            System.exit(1);
        }
        System.out.println("✅ " + adapters.size() + " adapter packages listed against each other; "
                + files + " exemplars, " + imports + " imports, none crosses an adapter.");
    }

    static Map<String, Set<String>> rules(String yaml) {
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher m = RULE.matcher(yaml);
        while (m.find()) {
            Set<String> to = new TreeSet<>();
            for (String t : m.group(2).split(",")) if (!t.isBlank()) to.add(t.strip());
            out.put(m.group(1), to);
        }
        return out;
    }

    /** Every adapter has a rule, and the rule lists every other adapter and nothing else. */
    static List<String> matrix(Set<String> adapters, Map<String, Set<String>> forbidden) {
        List<String> out = new ArrayList<>();
        for (String a : adapters) {
            Set<String> to = forbidden.get(a);
            if (to == null) { out.add(BLUEPRINT + "  no dependency_rules entry with `from: " + a + "`"); continue; }
            Set<String> expected = new TreeSet<>(adapters);
            expected.remove(a);
            Set<String> open = ALLOWED.getOrDefault(a, Set.of());
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(to);
            missing.removeAll(open);
            Set<String> extra = new TreeSet<>(to);
            extra.removeAll(expected);
            Set<String> closed = new TreeSet<>(to);
            closed.retainAll(open);
            if (!closed.isEmpty()) out.add(BLUEPRINT + "  `from: " + a + "` forbids " + closed
                    + ", an edge this test lists as open (ALLOWED)");
            if (!missing.isEmpty()) out.add(BLUEPRINT + "  `from: " + a + "` does not forbid " + missing);
            if (!extra.isEmpty()) out.add(BLUEPRINT + "  `from: " + a + "` forbids " + extra
                    + ", which is no adapter package of packages.map");
        }
        return out;
    }

    /** Lines of {@code file} that declare a package in adapter A and import a type of adapter B. */
    static List<String> crossings(String file, List<String> lines, Set<String> adapters,
            Map<String, Set<String>> forbidden) {
        List<String> out = new ArrayList<>();
        String current = null;
        for (int i = 0; i < lines.size(); i++) {
            Matcher p = PACKAGE.matcher(lines.get(i));
            if (p.find()) { current = adapterOf(p.group(1), adapters); continue; }
            Matcher im = IMPORT.matcher(lines.get(i));
            if (current == null || !im.find()) continue;
            String target = adapterOf(im.group(1), adapters);
            if (target != null && forbidden.getOrDefault(current, Set.of()).contains(target)) {
                out.add(file + ":" + (i + 1) + "  " + current + " imports " + target
                        + " — go through the application (the port), never to a sibling adapter");
            }
        }
        return out;
    }

    /** The adapter key a qualified name sits in, or null: `com.x.adapter.out.cache.order.Y` -> `adapter.out.cache`. */
    static String adapterOf(String qualified, Set<String> adapters) {
        for (String a : adapters) {
            if (qualified.contains("." + a + ".") || qualified.endsWith("." + a)
                    || qualified.startsWith(a + ".")) return a;
        }
        return null;
    }

    static void expect(boolean ok, String what, List<String> failures) {
        if (!ok) failures.add("self-check: " + what);
    }
}
