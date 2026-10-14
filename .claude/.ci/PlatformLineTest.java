///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar export` refuses a destination whose build declares a Spring Boot
// line below `doctor.platform.min_line` — exit 2, nothing written, the line, the floor and the way
// out named — for a Maven starter parent, an imported Boot BOM, a `spring-boot.version` property,
// a Gradle plugin in Groovy and in Kotlin, and a version catalog; that it admits the floor itself
// and later; that a build whose line no pattern reads, or no build file at all, is never refused;
// and that `doctor` prints the `Platform` line — ✅ at or above the floor, ⚠️ below it, ⚪ with
// nothing detected — and never gates on it.
//
// Why this test exists: `arch-adopt` used to install this .claude/ into a Boot 3 project without a
// word, and the norms, exemplars and the `tools.jackson..` allowlist it wrote do not apply there.
// The refusal is a Form 7c mode, and a mode that throws exits 0 through `main`'s catch — it would
// look like a pass. Decision 0161.
//
// `export` runs from this repository against a throwaway destination; `doctor` runs inside a
// throwaway project holding a copy of the real extensions.json, so the floor and the patterns
// under test are production data.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class PlatformLineTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final Path SOURCE = Paths.get("").toAbsolutePath();

    static String starterParent(String v) {
        return "<project><parent><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-parent</artifactId><version>" + v + "</version></parent></project>\n";
    }

    public static void main(String[] args) throws Exception {
        // export — refused below the floor
        refused("pom.xml", starterParent("3.5.16"), "3.5", "Maven starter parent 3.5.16");
        refused("pom.xml", starterParent("3.9.0"), "3.9", "3.9 — the last minor before the floor");
        refused("pom.xml", "<project><parent><artifactId>acme-parent</artifactId><version>7</version></parent>"
                + "<dependencyManagement><dependencies><dependency><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-dependencies</artifactId><version>3.4.2</version><type>pom</type>"
                + "<scope>import</scope></dependency></dependencies></dependencyManagement></project>\n",
                "3.4", "a corporate parent with the Boot BOM imported");
        refused("pom.xml", "<project><properties><spring-boot.version>3.3.8</spring-boot.version></properties></project>\n",
                "3.3", "the spring-boot.version property");
        refused("build.gradle", "plugins {\n    id 'org.springframework.boot' version '3.5.6'\n}\n",
                "3.5", "Gradle plugin, Groovy");
        refused("build.gradle.kts", "plugins {\n    id(\"org.springframework.boot\") version \"3.5.6\"\n}\n",
                "3.5", "Gradle plugin, Kotlin");
        refused("gradle/libs.versions.toml", "[versions]\nspringBoot = \"3.5.6\"\n",
                "3.5", "a Gradle version catalog");

        // export — admitted at the floor and later, or without evidence
        admitted("pom.xml", starterParent("4.0.0"), "4.0.0, the floor itself");
        admitted("pom.xml", starterParent("4.1.1"), "4.1.1");
        admitted("build.gradle", "plugins {\n    id 'org.springframework.boot' version '4.0.8'\n}\n", "Gradle 4.0.8");
        admitted("pom.xml", starterParent("${boot.version}"), "a version held in a property no pattern reads — no evidence, no refusal");
        admitted("pom.xml", "<project/>\n", "a build file that says nothing about Boot");
        admitted(null, null, "no build file at all, a destination still empty");

        // the refusal writes nothing, with or without --dry-run
        Path dest = project("pom.xml", starterParent("3.5.16"));
        int exit = export(dest, false).exit;
        if (exit != 2) fail("refused, no --dry-run — expected exit 2, got " + exit);
        else if (Files.exists(dest.resolve(".claude"))) fail("refused, no --dry-run — a .claude/ was written");
        else System.out.println("✅ a refused export writes nothing");
        deleteTree(dest);

        // doctor
        Map<String, String> p35 = Map.of("pom.xml", starterParent("3.5.16"));
        expect(doctor(p35), "Platform .......... ⚠️  Spring Boot 3.5 — this .claude/ is written for 4.0 and later",
                "doctor warns on 3.5");
        expect(doctor(Map.of("pom.xml", starterParent("4.1.1"))), "Platform .......... ✅ Spring Boot 4.1",
                "doctor is green on 4.1");
        expect(doctor(Map.of()), "Platform .......... ⚪ no Spring Boot line found",
                "doctor is silent with nothing detected");

        String gate = doctor(p35, "doctor", "gate");
        Matcher g = Pattern.compile("❌ doctor gate: (.*) failed").matcher(gate);
        if (!gate.contains("Platform .......... ⚠️")) {
            fail("gate run — the fixture did not show the line warned, so the case proves nothing");
        } else if (g.find() && Arrays.asList(g.group(1).split(", ")).contains("Platform")) {
            fail("doctor gate names `Platform` — the line warns and must never gate");
        } else {
            System.out.println("✅ doctor gate ignores a warned Platform line");
        }

        System.out.println(failures == 0
                ? "\n✅ A Spring Boot line below the floor is refused by export, reported by doctor, never gated."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Run(int exit, String out) { }

    static void refused(String file, String content, String line, String name) throws Exception {
        Path dest = project(file, content);
        Run r = export(dest, true);
        deleteTree(dest);
        if (r.exit != 2) { fail(name + " — expected exit 2, got " + r.exit); System.out.println(r.out); return; }
        for (String needle : List.of("builds on Spring Boot " + line, "written for 4.0 and later",
                "Nothing was written", "Migrate the project to Spring Boot 4.0 or later")) {
            if (!r.out.contains(needle)) { fail(name + " — message lacks \"" + needle + "\""); System.out.println(r.out); return; }
        }
        System.out.println("✅ refused: " + name);
    }

    static void admitted(String file, String content, String name) throws Exception {
        Path dest = project(file, content);
        Run r = export(dest, true);
        deleteTree(dest);
        if (r.exit != 0) { fail(name + " — expected exit 0, got " + r.exit); System.out.println(r.out); return; }
        System.out.println("✅ admitted: " + name);
    }

    static Path project(String file, String content) throws Exception {
        Path p = Files.createTempDirectory("archhook-platform-ci");
        if (file != null) write(p.resolve(file), content);
        return p;
    }

    static Run export(Path dest, boolean dry) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(javaCmd(), "-jar", HOOK.toString(),
                "export", dest.toString(), "--blueprint", "hexagonal"));
        if (dry) cmd.add("--dry-run");
        return run(cmd, SOURCE);
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
        Path p = Files.createTempDirectory("archhook-platform-doctor-ci");
        Path schemas = p.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        for (Map.Entry<String, String> f : files.entrySet()) write(p.resolve(f.getKey()), f.getValue());
        List<String> cmd = new ArrayList<>(List.of(javaCmd(), "-jar", HOOK.toString()));
        cmd.addAll(mode.length == 0 ? List.of("doctor") : List.of(mode));
        String out = run(cmd, p).out;
        deleteTree(p);
        return out;
    }

    static Run run(List<String> cmd, Path cwd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(proc.waitFor(), out);
    }

    static String javaCmd() { return ProcessHandle.current().info().command().orElse("java"); }

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
