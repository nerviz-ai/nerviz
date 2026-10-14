///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar doctor` prints `Virtual threads ... ✅` when a project whose build
// targets JDK 21 or later sets spring.threads.virtual.enabled and spring.main.keep-alive to true in
// its main configuration file, ⚠️ naming what is missing or what a profile turns off, ⚪ below
// 21, with no target found, or with no build file — and that `doctor gate` never fails on it.
// The target is read from the build file, Maven and Gradle, never from the JVM running doctor.
// It also proves project-bootstrap's application.yml exemplar carries both properties.
//
// Why this test exists: the norm (.claude/rules/virtual-threads.md) was set in advance, with no
// failure observed, and a profile may turn it off with a stated reason — so doctor reports it
// and never gates. Decision 0155.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class VirtualThreadsDoctorTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final String POM_21 = "<project><properties><java.version>21</java.version></properties></project>\n";
    static final String BOTH = "spring:\n  threads:\n    virtual:\n      enabled: true\n  main:\n    keep-alive: true\n";
    static final String YML = "src/main/resources/application.yml";

    public static void main(String[] args) throws Exception {
        expect(doctor(Map.of("pom.xml", POM_21, YML, BOTH)),
                "Virtual threads ... ✅ JDK 21 · spring.threads.virtual.enabled, spring.main.keep-alive on in " + YML,
                "Maven 21, both properties in application.yml — green");

        String bootstrap = Files.readString(
                Paths.get(".claude/skills/project-bootstrap/templates/application.yml.example"));
        expect(doctor(Map.of("pom.xml", POM_21, YML, bootstrap)), "Virtual threads ... ✅ JDK 21",
                "project-bootstrap's application.yml exemplar carries both properties");

        String missing = doctor(Map.of("pom.xml", POM_21, YML, "spring:\n  application:\n    name: x\n"));
        expect(missing, "Virtual threads ... ⚠️  JDK 21 — 2 problem(s)", "Maven 21, neither property — warned");
        expect(missing, "spring.threads.virtual.enabled: true missing from " + YML, "the missing key is named");

        String profile = doctor(Map.of("pom.xml", POM_21, YML, BOTH,
                "src/main/resources/application-local.yml", "spring:\n  threads:\n    virtual:\n      enabled: false\n"));
        expect(profile, "src/main/resources/application-local.yml sets spring.threads.virtual.enabled to false",
                "a profile that turns it off is named");

        expect(doctor(Map.of("pom.xml", POM_21, "src/main/resources/application.properties",
                        "spring.threads.virtual.enabled=true\nspring.main.keep-alive: true\n")),
                "Virtual threads ... ✅ JDK 21", "application.properties read in both separators");

        expect(doctor(Map.of("build.gradle",
                        "java {\n    sourceCompatibility = JavaVersion.toVersion('25')\n}\n", YML, BOTH)),
                "Virtual threads ... ✅ JDK 25", "Gradle sourceCompatibility read as the target");

        expect(doctor(Map.of("build.gradle.kts",
                        "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\n",
                        "app/src/main/resources/application.yml", "spring:\n  application:\n    name: x\n")),
                "Virtual threads ... ⚠️  JDK 21", "Gradle toolchain read; a module's application.yml is found");

        expect(doctor(Map.of("pom.xml", "<project><properties><java.version>17</java.version></properties></project>\n",
                        YML, "spring:\n  application:\n    name: x\n")),
                "Virtual threads ... ⚪ pom.xml targets JDK 17 — virtual threads need 21",
                "Maven 17 — the rule does not apply, not warned");

        expect(doctor(Map.of("pom.xml", "<project/>\n")), "Virtual threads ... ⚪ no target JDK found in pom.xml",
                "a build file with no target — silent");

        expect(doctor(Map.of()), "Virtual threads ... ⚪ no build file", "no build file — silent");

        String gate = doctor(Map.of("pom.xml", POM_21, YML, "spring:\n  application:\n    name: x\n"), "doctor", "gate");
        Matcher g = Pattern.compile("❌ doctor gate: (.*) failed").matcher(gate);
        if (!gate.contains("Virtual threads ... ⚠️")) {
            fail("gate run — the fixture did not show the line warned, so the case proves nothing");
        } else if (g.find() && Arrays.asList(g.group(1).split(", ")).contains("Virtual threads")) {
            fail("doctor gate names `Virtual threads` — the line warns and must never gate");
        } else {
            System.out.println("✅ doctor gate ignores a warned Virtual threads line");
        }

        System.out.println(failures == 0 ? "\n✅ Virtual threads reported from the build's target JDK, and never gated."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void expect(String out, String needle, String name) {
        if (out.contains(needle)) {
            System.out.println("✅ " + name);
        } else {
            fail(name + " — expected \"" + needle + "\"");
            System.out.println(out);
        }
    }

    /** A project holding {@code files} and this repository's extensions.json, run through doctor. */
    static String doctor(Map<String, String> files, String... mode) throws Exception {
        Path p = Files.createTempDirectory("archhook-vt-ci");
        Path schemas = p.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        for (Map.Entry<String, String> f : files.entrySet()) write(p.resolve(f.getKey()), f.getValue());
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(mode.length == 0 ? List.of("doctor") : List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(p.toFile());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();
        deleteTree(p);
        return out;
    }

    static void fail(String m) { failures++; System.out.println("❌ " + m); }

    static void write(Path f, String s) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, s, StandardCharsets.UTF_8);
    }

    static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
