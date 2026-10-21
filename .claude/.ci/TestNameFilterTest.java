///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves Maven and Gradle run the same test classes in a generated project, and that every
// test exemplar is named so both run it.
//
// Why this test exists: the Gradle exemplar included only `**/*Test.class`, while Surefire's own
// defaults also run `*Tests`. `ModularityTests` and the Initializr's `*ApplicationTests` end in
// `Tests`, so in a Gradle modular monolith the module verification and the context-load test never
// ran with `./gradlew build` green (lessons-learned-025 § 1, decision 0179). No job built the
// templates' own build files — `BlueprintSkeletonTest` writes its own minimal ones.
//
// What it holds, each failure named by file:
//   - the `test` task of `build.gradle.parent.example` includes exactly Surefire's four default
//     patterns (`Test*`, `*Test`, `*Tests`, `*TestCase`) and excludes `*IT`;
//   - `pom.parent.xml.example` configures no Surefire plugin, so Surefire keeps those defaults;
//   - every Java exemplar under `.claude/skills/**/templates/` that declares a `@Test` method
//     declares a class whose name one of the four patterns or the `*IT` suffix (failsafe) matches;
//     a fragment that declares no class, a method added to an Initializr class, is not read.
//
// Comments and string literals are blanked before reading, keeping every offset.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class TestNameFilterTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path GRADLE = SKILLS.resolve("project-bootstrap/templates/build.gradle.parent.example");
    static final Path POM = SKILLS.resolve("project-bootstrap/templates/pom.parent.xml.example");

    /** Surefire's default includes, as globs over the class name. */
    static final List<String> SUREFIRE = List.of("**/Test*.class", "**/*Test.class", "**/*Tests.class",
            "**/*TestCase.class");
    static final String FAILSAFE = "**/*IT.class";

    static final Pattern TYPE_DECL = Pattern.compile("(?<![.\\w@])(class|enum|record|interface)\\s+([A-Z]\\w*)");
    static final Pattern TEST_METHOD = Pattern.compile("@(?:\\w+\\.)*(?:Test|ParameterizedTest)\\b");

    public static void main(String[] args) throws Exception {
        List<String> violations = new ArrayList<>();
        checkGradle(violations);
        checkPom(violations);
        int tests = checkExemplars(violations);

        if (!violations.isEmpty()) {
            violations.forEach(System.err::println);
            System.err.println("FAIL: " + violations.size() + " test-name violation(s)");
            System.exit(1);
        }
        System.out.println("OK: Gradle runs Surefire's four patterns, the pom leaves them as they are, and "
                + tests + " test exemplar(s) match them");
    }

    static void checkGradle(List<String> violations) throws Exception {
        String code = stripLineComments(Files.readString(GRADLE, StandardCharsets.UTF_8));
        Matcher task = Pattern.compile("tasks\\.named\\('test'\\)\\s*\\{(.*?)\\n    }", Pattern.DOTALL).matcher(code);
        if (!task.find()) {
            violations.add(GRADLE + ": no `tasks.named('test') { … }` block found");
            return;
        }
        String body = task.group(1);
        Set<String> includes = globs(body, "include");
        Set<String> excludes = globs(body, "exclude");
        if (!includes.equals(new TreeSet<>(SUREFIRE))) {
            violations.add(GRADLE + ": the `test` task includes " + includes + " — it must be exactly Surefire's "
                    + "defaults " + new TreeSet<>(SUREFIRE) + ", or Maven and Gradle run different classes");
        }
        if (!excludes.equals(Set.of(FAILSAFE))) {
            violations.add(GRADLE + ": the `test` task excludes " + excludes + " — it must exclude only " + FAILSAFE);
        }
    }

    static void checkPom(List<String> violations) throws Exception {
        String pom = Files.readString(POM, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        if (pom.contains("maven-surefire-plugin")) {
            violations.add(POM + ": configures maven-surefire-plugin — its default includes must stay as they "
                    + "are, because the Gradle template mirrors them");
        }
    }

    static int checkExemplars(List<String> violations) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().endsWith(".java.example")).sorted().toList();
        }
        int tests = 0;
        for (Path file : exemplars) {
            String code = blank(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            if (!TEST_METHOD.matcher(code).find()) continue;
            Matcher decl = TYPE_DECL.matcher(code);
            // A method added to a class the Initializr wrote (`ApplicationTests-tracer`) declares none.
            if (!decl.find()) continue;
            tests++;
            String name = decl.group(2);
            if (!matchesAny(name)) {
                violations.add(rel(file) + ": class `" + name + "` has @Test methods and matches none of "
                        + SUREFIRE + " or " + FAILSAFE + " — no build runs it");
            }
        }
        return tests;
    }

    static boolean matchesAny(String className) {
        for (String glob : SUREFIRE) if (matches(glob, className)) return true;
        return matches(FAILSAFE, className);
    }

    /** `**\/Test*.class` against a simple class name. */
    static boolean matches(String glob, String className) {
        String pattern = glob.substring(glob.lastIndexOf('/') + 1).replace(".class", "");
        String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return className.matches(regex);
    }

    static Set<String> globs(String body, String verb) {
        Set<String> found = new TreeSet<>();
        Matcher line = Pattern.compile("(?m)^\\s*" + verb + "\\s+(.*)$").matcher(body);
        while (line.find()) {
            Matcher q = Pattern.compile("'([^']*)'").matcher(line.group(1));
            while (q.find()) found.add(q.group(1));
        }
        return found;
    }

    static String stripLineComments(String text) {
        return text.replaceAll("(?m)^\\s*//.*$", "");
    }

    static String blank(String text) {
        StringBuilder out = new StringBuilder(text);
        Matcher m = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*|\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"").matcher(text);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) if (out.charAt(i) != '\n') out.setCharAt(i, ' ');
        }
        return out.toString();
    }

    static String rel(Path p) {
        return p.toString().replace('\\', '/');
    }
}
