///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves ArchHook warns when CLAUDE_PROJECT_DIR names another Nerviz project than the
// one the running jar ships in, and stays silent everywhere else — without ever changing an exit
// code (a PreToolUse hook that exits other than 0 or 2 is a denied call under Copilot).
//
// Decision 0207. Two throwaway projects each hold a copy of the real jar and extensions.json:
//   env = other project   -> warning on stderr, same exit code as the unwarned run
//   env = own project     -> silent
//   env = dir with no jar -> silent (how every other CI test points the jar at a temp project)
//   env unset             -> silent
//   doctor, env = other   -> `Root cross-check` line red, and `doctor gate` does not fail on it
//   the warning is never part of stdout (a refusal repeats stderr there as its reason)

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class RootCrossCheckTest {

    static int failures = 0;
    static final Path JAR = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final Path SCHEMA = Paths.get(".claude/schemas/extensions.json").toAbsolutePath();
    static final String WARN = "ArchHook: CLAUDE_PROJECT_DIR is another Nerviz project";

    record Result(int rc, String out, String err) { }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("rootcheck");
        Path p1 = project(tmp, "p1");
        Path p2 = project(tmp, "p2");
        Path bare = Files.createDirectories(tmp.resolve("bare"));
        Path jar1 = p1.resolve(".claude/hooks/ArchHook.jar");

        Result base = run(jar1, bare.toString(), "schema");
        Result other = run(jar1, p2.toString(), "schema");
        Result own = run(jar1, p1.toString(), "schema");
        Result unset = run(jar1, null, "schema");

        if (!other.err.contains(WARN)) fail("env at another Nerviz project: no warning on stderr:\n" + other.err);
        if (other.out.contains(WARN)) fail("the warning leaked into stdout");
        if (!other.err.contains("$env:CLAUDE_PROJECT_DIR") && !other.err.contains("export CLAUDE_PROJECT_DIR")) {
            fail("the warning prints no command to fix the variable");
        }
        if (!other.err.contains(p1.toString())) fail("the warning does not name the project this jar ships in");
        // Same exit code as a run the check cannot touch: both read an extensions.json that is a copy.
        if (other.rc != own.rc) fail("warning changed the exit code: " + other.rc + " vs " + own.rc);
        if (own.err.contains(WARN)) fail("env = own project warned");
        if (base.err.contains(WARN)) fail("env at a dir with no jar warned — every temp-project test would");
        if (unset.err.contains(WARN)) fail("env unset warned");

        Result d = run(jar1, p2.toString(), "doctor");
        if (!d.out.matches("(?s).*Root cross-check \\.+ ❌.*") && !d.err.matches("(?s).*Root cross-check \\.+ ❌.*")) {
            fail("doctor does not show a red `Root cross-check` line");
        }
        Result dg = run(jar1, p2.toString(), "doctor", "gate");
        if ((dg.out + dg.err).contains("Root cross-check") && (dg.out + dg.err).contains("doctor gate: ")
                && (dg.out + dg.err).matches("(?s).*❌ doctor gate:.*Root cross-check.*")) {
            fail("doctor gate fails on the root cross-check — runner state must never gate");
        }
        Result dOwn = run(jar1, p1.toString(), "doctor");
        if ((dOwn.out + dOwn.err).matches("(?s).*Root cross-check \\.+ ❌.*")) fail("doctor, env = own project: red");

        if (failures > 0) {
            System.out.println("\n❌ " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("✅ root cross-check: warns on another Nerviz project, silent otherwise, exit codes untouched");
    }

    static Path project(Path tmp, String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.createDirectories(p.resolve(".claude/hooks"));
        Files.createDirectories(p.resolve(".claude/schemas"));
        Files.copy(JAR, p.resolve(".claude/hooks/ArchHook.jar"));
        Files.copy(SCHEMA, p.resolve(".claude/schemas/extensions.json"));
        return p.toRealPath();
    }

    static Result run(Path jar, String env, String... mode) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("java", "-jar", jar.toString()));
        cmd.addAll(Arrays.asList(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        if (env != null) pb.environment().put("CLAUDE_PROJECT_DIR", env);
        Path o = Files.createTempFile("rc-out", ".txt");
        Path e = Files.createTempFile("rc-err", ".txt");
        pb.redirectOutput(o.toFile()).redirectError(e.toFile()).redirectInput(ProcessBuilder.Redirect.from(
                Files.writeString(Files.createTempFile("rc-in", ".json"), "{}").toFile()));
        int rc = pb.start().waitFor();
        return new Result(rc, Files.readString(o, StandardCharsets.UTF_8), Files.readString(e, StandardCharsets.UTF_8));
    }

    static void fail(String m) {
        System.out.println("❌ " + m);
        failures++;
    }
}
