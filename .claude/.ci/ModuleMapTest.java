///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `format`, `check` and `tests` hand Maven the right `-pl` — `.` for a file
// under the root `src/` of a single-module project, the module directory in a multi-module
// one — and never call Maven for a `.java` outside `src/` or in a project with no POM.
//
// Why this test exists: `moduleOf` never looked at the root POM, so in every project of the
// four single-module blueprints those three hooks returned before calling Maven, with no
// output, from the first commit until issue #60 (decision 0107). A hook that silently does
// nothing looks exactly like a hook that passed — only the recorded call tells them apart.
//
// Why a stub wrapper: the claim is which arguments reach `mvnw`, not what Maven does with
// them (`-pl . -am` on a single-module POM was checked by hand, 0107). The stub appends its
// arguments to a file next to it; `mvnw` on Unix, `mvnw.cmd` on Windows, as `wrapper()` picks.
//
// The `tests` cases also prove what follows `-am`: the goals of `tests.stop_maven_args`, read
// from the project's own extensions.json, and an exit 2 with no Maven call when the key is
// gone. The gate stopped at `test` while spotless:check and checkstyle-test sit at `verify`,
// and printed green on a tree CI rejected (issue #126, decision 0142).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ModuleMapTest {

    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    static final String POM = "<project><modelVersion>4.0.0</modelVersion></project>\n";

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        int failures = 0;

        Path single = project(true, null);
        Path src = source(single, "src/main/java/a/X.java");
        failures += expect(hook, single, "format", fileJson(src), "-pl . spotless:apply",
                "single-module src/main — format runs spotless:apply on the root module");
        failures += expect(hook, single, "check", fileJson(src), "-pl . -am test-compile",
                "single-module src/main — check compiles the root module");
        failures += expect(hook, single, "check",
                fileJson(source(single, "src/test/java/a/XTest.java")), "-pl . -am test-compile",
                "single-module src/test — check compiles the root module");
        failures += expect(hook, single, "check",
                fileJson(source(single, ".claude/hooks/Tool.java")), null,
                "single-module .claude/ file — no Maven call");

        Path tests = project(true, null);
        source(tests, "src/main/java/a/X.java");
        git(tests, "init", "-q");
        schema(tests, null);
        failures += expect(hook, tests, "tests", "{}",
                "-pl . -am verify -DskipITs -Djacoco.skip=true",
                "single-module, root src/ changed — tests runs verify, no ITs, on the root module");

        schema(tests, "[\"verify\", \"-Dcustom.skip=true\"]");
        failures += expect(hook, tests, "tests", "{}", "-pl . -am verify -Dcustom.skip=true",
                "tests.stop_maven_args edited — tests hands Maven the data, not a constant");

        schema(tests, "");
        failures += expect(hook, tests, "tests", "{}", null, 2,
                "tests.stop_maven_args missing — tests blocks, no Maven call");

        Path multi = project(true, "domain");
        failures += expect(hook, multi, "check",
                fileJson(source(multi, "domain/src/main/java/a/X.java")),
                "-pl domain -am test-compile",
                "multi-module — check compiles the module, not the root");

        Path none = project(false, null);
        failures += expect(hook, none, "check",
                fileJson(source(none, "src/main/java/a/X.java")), null,
                "no pom.xml yet — no Maven call");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — the hooks are NOT handing"
                    + " Maven the module the edited file belongs to.");
            System.exit(1);
        }
        System.out.println("✅ moduleOf: root src/ maps to `.`, modules to their directory,"
                + " everything else to no Maven call.");
    }

    /** A throwaway project with a recording wrapper, a root POM, and optionally a module. */
    static Path project(boolean rootPom, String module) throws Exception {
        Path dir = Files.createTempDirectory("archhook-modulemap").toRealPath();
        // The real extensions.json: `build_tools` is where the wrapper, the module files and the
        // compile arguments live (decision 0180), so the fixture must carry production data.
        Path schemas = dir.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        if (rootPom) Files.writeString(dir.resolve("pom.xml"), POM);
        if (module != null) {
            Files.createDirectories(dir.resolve(module));
            Files.writeString(dir.resolve(module).resolve("pom.xml"), POM);
        }
        if (WINDOWS) {
            Files.writeString(dir.resolve("mvnw.cmd"),
                    "@echo %*>> \"%~dp0mvnw-args.txt\"\r\n");
        } else {
            Path w = dir.resolve("mvnw");
            Files.writeString(w,
                    "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$(dirname \"$0\")/mvnw-args.txt\"\n");
            w.toFile().setExecutable(true);
        }
        return dir;
    }

    static Path source(Path project, String rel) throws Exception {
        Path f = project.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "class X {}\n");
        return f;
    }

    static String fileJson(Path f) {
        return "{\"tool_input\":{\"file_path\":\""
                + f.toAbsolutePath().toString().replace("\\", "\\\\") + "\"}}";
    }

    /**
     * Writes the real extensions.json into the project; {@code args} replaces the value of
     * `tests.stop_maven_args`, and an empty string drops the key. Null keeps it as it is.
     */
    static void schema(Path project, String args) throws Exception {
        String real = Files.readString(Paths.get(".claude/schemas/extensions.json"),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(",\\s*\"stop_maven_args\":\\s*\\[[^\\]]*\\]").matcher(real);
        if (!m.find()) throw new IllegalStateException("tests.stop_maven_args not in extensions.json");
        String json = args == null ? real
                : args.isEmpty() ? real.substring(0, m.start()) + real.substring(m.end())
                : real.substring(0, m.start()) + ",\n    \"stop_maven_args\": " + args
                        + real.substring(m.end());
        Path dir = project.resolve(".claude/schemas");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("extensions.json"), json, StandardCharsets.UTF_8);
    }

    static int expect(Path hook, Path project, String mode, String stdin, String wanted,
                      String label) throws Exception {
        return expect(hook, project, mode, stdin, wanted, 0, label);
    }

    /** Runs one mode; {@code wanted} null means the wrapper must not have been called. */
    static int expect(Path hook, Path project, String mode, String stdin, String wanted,
                      int exit, String label) throws Exception {
        Path record = project.resolve("mvnw-args.txt");
        Files.deleteIfExists(record);
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), mode).directory(project.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", project.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        String called = Files.exists(record)
                ? Files.readString(record, StandardCharsets.UTF_8).strip() : null;
        String why = code != exit ? "exit " + code + ", expected " + exit
                : wanted == null && called != null ? "Maven called with: " + called
                : wanted != null && called == null ? "Maven never called"
                : wanted != null && !called.contains(wanted)
                        ? "expected `" + wanted + "`, got `" + called + "`"
                : null;
        if (why == null) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — " + why);
        System.out.println(out);
        return 1;
    }

    static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args));
    }
}
