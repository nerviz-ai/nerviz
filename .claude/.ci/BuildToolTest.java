///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `check` compiles the touched module with the project's own build tool, Maven or
// Gradle, and that `doctor` reports whichever wrapper the project carries.
//
// Why this test exists: `wrapper()` resolved `mvnw` only, so on a Gradle project — a supported
// `build.tool` — `check` skipped its incremental compile with a Maven warning and `doctor` printed
// `Maven wrapper ❌` and "Setup incomplete" on every run (issue #167, decision 0180). The tools now
// come from `build_tools` in extensions.json; this test holds what each one is handed.
//
// Each case is a throwaway project with a copy of the real extensions.json and a fake wrapper —
// a shell script and a `.bat` — that writes its arguments to `args.txt` and exits 1, so `check`
// must report the compile as failed (exit 2) and the arguments name the module:
//   - Gradle multi-module: `-q --offline :domain:compileTestJava`;
//   - Gradle single-module: `-q --offline compileTestJava`, no task prefix;
//   - Maven multi-module: `-q -o -pl domain -am test-compile`, unchanged;
//   - `doctor` prints `Build wrapper` green with `(gradle)` on the Gradle project.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class BuildToolTest {

    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    static int failures = 0;

    record Run(int exit, String out) {}

    public static void main(String[] args) throws Exception {
        compiles("Gradle multi-module", "gradlew", "domain/build.gradle.kts",
                "domain/src/main/java/d/Order.java", "-q --offline :domain:compileTestJava");
        compiles("Gradle single-module", "gradlew", "build.gradle",
                "src/main/java/d/Order.java", "-q --offline compileTestJava");
        compiles("Maven multi-module", "mvnw", "domain/pom.xml",
                "domain/src/main/java/d/Order.java", "-q -o -pl domain -am test-compile");

        Path g = project("gradlew", "build.gradle.kts");
        Run d = run(g, "doctor");
        expect(d.out.lines().anyMatch(l -> l.contains("Build wrapper") && l.contains("✅") && l.contains("(gradle)")),
                "doctor reports the Gradle wrapper green, naming the tool");
        expect(d.out.contains("✅ Setup operational."), "doctor verdict is operational on Gradle");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `check` or `doctor` does not follow the project's build tool.");
            System.exit(1);
        }
        System.out.println("✅ check compiles with the project's own wrapper, Maven or Gradle; doctor reports it.");
    }

    static void compiles(String name, String wrapper, String buildFile, String source, String wanted) throws Exception {
        Path p = project(wrapper, buildFile);
        Path src = p.resolve(source);
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package d;\nclass Order {}\n");
        Run r = run(p, "check", source);
        expect(r.exit == 2 && r.out.contains("Compilation failed"),
                name + ": a failing wrapper fails `check` with exit 2 (exit " + r.exit + ")");
        Path argsFile = p.resolve("args.txt");
        String got = Files.isRegularFile(argsFile) ? Files.readString(argsFile).strip() : "<wrapper never ran>";
        expect(got.equals(wanted), name + ": wrapper gets `" + wanted + "`, got `" + got + "`");
    }

    /** A project with the real extensions.json, a boundary map, a build file and a fake wrapper. */
    static Path project(String wrapper, String buildFile) throws Exception {
        Path p = Files.createTempDirectory("archhook-buildtool");
        Path schemas = p.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        Files.writeString(p.resolve(".claude/forbidden-imports.txt"), "# ci\ndomain|org.springframework.\n");
        Path bf = p.resolve(buildFile);
        Files.createDirectories(bf.getParent());
        Files.writeString(bf, "");
        Path sh = p.resolve(wrapper);
        Files.writeString(sh, "#!/bin/sh\necho \"$@\" > \"$(dirname \"$0\")/args.txt\"\nexit 1\n");
        if (!WINDOWS) Files.setPosixFilePermissions(sh, PosixFilePermissions.fromString("rwxr-xr-x"));
        String bat = wrapper.equals("gradlew") ? "gradlew.bat" : "mvnw.cmd";
        Files.writeString(p.resolve(bat), "@echo %*> \"%~dp0args.txt\"\r\n@exit /b 1\r\n");
        return p;
    }

    static Run run(Path project, String... modeArgs) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(List.of(modeArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("CLAUDE_PROJECT_DIR", project.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Run(-1, "timeout");
        }
        return new Run(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    static void expect(boolean ok, String name) {
        if (ok) System.out.println("  ✓ " + name);
        else { System.out.println("  ✗ " + name); failures++; }
    }
}
