///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the `tests` Stop gate defers while a writer subagent of the same session is
// still running, and runs Maven as before in every other case — a read-only agent, an agent
// no class lists, a writer that already stopped, another session's writer, a marker past
// `tests.writer_agent_max_minutes` — and between two chained writers, when the first stopped
// and the second already started.
//
// Why this test exists: a main-thread `Stop` that fired while `java-spring-boot-developer`
// worked in the background ran Maven over its half-written tree and blocked a thread that
// cannot write src/ (issue #76, decision 0116). Both failure directions are silent: a marker
// never written lets the misfire back in, and a marker never cleared turns the gate off — a
// hook that skipped looks exactly like a hook that passed, only the recorded call tells.
//
// Why a stub wrapper: same as ModuleMapTest — the claim is whether `mvnw` is called, not
// what Maven does. Each project gets a copy of the real extensions.json, so the writer set
// is what `agent_classes` says today.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class TestsDeferTest {

    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    // -Darchhook.test.jar=<abs path> points the test at another jar (the red run against the jar
    // before decision 0215); without it the committed jar is the target.
    static final Path HOOK = Paths.get(
            System.getProperty("archhook.test.jar", ".claude/hooks/ArchHook.jar")).toAbsolutePath();
    static final Path SCHEMA = Paths.get(".claude/schemas/extensions.json").toAbsolutePath();
    static final String WRITER = "java-spring-boot-developer";
    static final String READER = "issue-verifier";
    static final List<String> SESSIONS = new ArrayList<>();
    static final List<String> AGENTS = new ArrayList<>();
    static final List<Path> PROJECTS = new ArrayList<>();
    static final Path STATE = Paths.get(System.getProperty("java.io.tmpdir"), "archhook-tests");

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(HOOK)) throw new IllegalStateException("no hook jar at " + HOOK);
        int failures = 0;
        try {
            failures += run("writer running — tests deferred, Maven not called", null, p -> {
                String s = session();
                hook(p, "agent-start", agent(s, "a1", WRITER));
                return s;
            }, false);

            failures += run("read-only agent running — Maven called", null, p -> {
                String s = session();
                hook(p, "agent-start", agent(s, "a1", READER));
                return s;
            }, true);

            failures += run("agent no class lists — Maven called", null, p -> {
                String s = session();
                hook(p, "agent-start", agent(s, "a1", "Explore"));
                return s;
            }, true);

            failures += run("writer started and stopped — Maven called", null, p -> {
                String s = session();
                hook(p, "agent-start", agent(s, "a1", WRITER));
                hook(p, "agent-end", agent(s, "a1", WRITER));
                return s;
            }, true);

            // /new-feature-implement chains three executor groups; the next one starts in the turn that
            // receives the previous one's report, before that turn's Stop (decision 0130).
            failures += run("group 1 stopped, group 2 started — still deferred", null, p -> {
                String s = session();
                hook(p, "agent-start", agent(s, "g1", WRITER));
                hook(p, "agent-end", agent(s, "g1", WRITER));
                hook(p, "agent-start", agent(s, "g2", WRITER));
                return s;
            }, false);

            failures += run("every group stopped — Maven called", null, p -> {
                String s = session();
                for (String g : List.of("g1", "g2", "g3")) {
                    hook(p, "agent-start", agent(s, g, WRITER));
                    hook(p, "agent-end", agent(s, g, WRITER));
                }
                return s;
            }, true);

            failures += run("another agent_id stopped (internal agent) — still deferred", null, p -> {
                String s = session();
                hook(p, "agent-start", agent(s, "a1", WRITER));
                hook(p, "agent-end", agent(s, "internal-9", ""));
                return s;
            }, false);

            failures += run("writer of another session — Maven called", null, p -> {
                hook(p, "agent-start", agent(session(), "a1", WRITER));
                return session();
            }, true);

            failures += run("marker past writer_agent_max_minutes — Maven called", 0, p -> {
                String s = session();
                hook(p, "agent-start", agent(s, "a1", WRITER));
                return s;
            }, true);

            failures += lifecycleCases();
        } finally {
            for (String s : SESSIONS) deleteTree(STATE.resolve(s));
            // Only this run's own markers: the shared `unknown` directory is never touched.
            for (String a : AGENTS) Files.deleteIfExists(STATE.resolve("unknown").resolve(a));
            for (Path p : PROJECTS) deleteTree(p);
        }

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — the tests Stop gate does NOT"
                    + " defer exactly while a writer subagent of this session runs.");
            System.exit(1);
        }
        System.out.println("✅ tests: deferred while a writer subagent runs, Maven called otherwise.");
    }

    interface Setup { String apply(Path project) throws Exception; }

    /** {@code cap} null keeps the real value; {@code maven} says whether mvnw must be called. */
    static int run(String label, Integer cap, Setup setup, boolean maven) throws Exception {
        Path p = project(cap);
        String s = setup.apply(p);
        Path record = p.resolve("mvnw-args.txt");
        Files.deleteIfExists(record);
        Result r = hook(p, null, "{\"session_id\":\"" + s + "\"}");
        boolean called = Files.exists(record);
        String why = r.code != 0 ? "exit " + r.code
                : maven && !called ? "Maven never called"
                : !maven && called ? "Maven called with: " + Files.readString(record).strip()
                : !maven && !r.out.contains("Tests deferred") ? "no `Tests deferred` line"
                : null;
        if (why == null) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — " + why);
        System.out.println(r.out);
        return 1;
    }

    static final String SESSION_ALIASES = "{\"session_id\": [\"sessionId\"]}";

    /** Start and Stop payloads of one spelling; `key` is the session field's name. */
    static String payload(String key, String session, String id, String type) {
        return "{\"" + key + "\":\"" + session + "\",\"agent_id\":\"" + id
                + "\",\"agent_type\":\"" + type + "\"}";
    }

    static String agentId() {
        String a = "ag-" + UUID.randomUUID();
        AGENTS.add(a);
        return a;
    }

    static Path marker(String session, String agent) {
        return STATE.resolve(session).resolve(agent);
    }

    static Path unknownMarker(String agent) {
        return STATE.resolve("unknown").resolve(agent);
    }

    /** A hook call whose non-zero exit is a problem of the case, never swallowed. */
    static void step(List<String> problems, Path p, String phase, String stdin) throws Exception {
        Result r = hook(p, phase, stdin);
        if (r.code != 0) problems.add("`tests " + phase + "` exited " + r.code + ": " + r.out.strip());
    }

    static void expect(List<String> problems, boolean ok, String what) {
        if (!ok) problems.add(what);
    }

    /** The Stop gate: its exit, whether the wrapper ran, and the deferral line when it did not. */
    static void gate(List<String> problems, Path p, String session, boolean maven, String when)
            throws Exception {
        Path record = p.resolve("mvnw-args.txt");
        Files.deleteIfExists(record);
        Result r = hook(p, null, "{\"session_id\":\"" + session + "\"}");
        boolean called = Files.exists(record);
        if (r.code != 0) problems.add(when + ": gate exited " + r.code);
        else if (maven && !called) problems.add(when + ": gate must call Maven, it deferred");
        else if (!maven && called) problems.add(when + ": gate must defer, it called Maven");
        else if (!maven && !r.out.contains("Tests deferred")) problems.add(when + ": no `Tests deferred` line");
    }

    static int report(String label, List<String> problems) {
        if (problems.isEmpty()) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — " + String.join("; ", problems));
        return 1;
    }

    /**
     * Decision 0215: Copilot's SubagentStart names the session `sessionId` and its SubagentStop
     * `session_id`; both must reach the same per-session marker, which only Stop's deletion and
     * the gate can then find. Real marker paths are asserted, not just exit codes.
     */
    static int lifecycleCases() throws Exception {
        int failures = 0;

        List<String> pr = new ArrayList<>();
        Path p = project(null, null);
        String s = session();
        String a = agentId();
        step(pr, p, "agent-start", payload("sessionId", s, a, WRITER));
        expect(pr, Files.isRegularFile(marker(s, a)),
                "mixed-session: Start(sessionId) wrote no marker in the session directory");
        expect(pr, !Files.exists(unknownMarker(a)),
                "mixed-session: Start(sessionId) wrote the miskeyed unknown/" + a);
        gate(pr, p, s, false, "after Start(sessionId)");
        step(pr, p, "agent-end", payload("session_id", s, a, WRITER));
        expect(pr, !Files.exists(marker(s, a)), "mixed-session: Stop(session_id) left the session marker");
        gate(pr, p, s, true, "after Stop(session_id)");
        failures += report("Start(sessionId) + Stop(session_id) — same session marker, then cleared", pr);

        pr = new ArrayList<>();
        p = project(null, null);
        s = session();
        a = agentId();
        step(pr, p, "agent-start", payload("session_id", s, a, WRITER));
        expect(pr, Files.isRegularFile(marker(s, a)), "snake_case Start wrote no session marker");
        expect(pr, !Files.exists(unknownMarker(a)), "snake_case Start wrote unknown/" + a);
        gate(pr, p, s, false, "after snake_case Start");
        step(pr, p, "agent-end", payload("session_id", s, a, WRITER));
        expect(pr, !Files.exists(marker(s, a)), "snake_case Stop left the marker");
        gate(pr, p, s, true, "after snake_case Stop");
        failures += report("Claude snake_case Start/Stop — unchanged", pr);

        pr = new ArrayList<>();
        p = project(null, null);
        s = session();
        String a1 = agentId();
        String a2 = agentId();
        step(pr, p, "agent-start", payload("sessionId", s, a1, WRITER));
        step(pr, p, "agent-start", payload("session_id", s, a2, WRITER));
        step(pr, p, "agent-end", payload("session_id", s, a1, WRITER));
        expect(pr, !Files.exists(marker(s, a1)), "interleaved: Stop A left A's marker");
        expect(pr, Files.isRegularFile(marker(s, a2)), "interleaved: Stop A removed B's marker");
        gate(pr, p, s, false, "after Stop A, B running");
        step(pr, p, "agent-end", payload("sessionId", s, a2, WRITER));
        expect(pr, !Files.exists(marker(s, a2)), "interleaved: Stop B left B's marker");
        gate(pr, p, s, true, "after Stop B");
        failures += report("two interleaved writers — each Stop clears only its own marker", pr);

        pr = new ArrayList<>();
        p = project(null, null);
        String s1 = session();
        String s2 = session();
        a = agentId();
        step(pr, p, "agent-start", payload("sessionId", s1, a, WRITER));
        step(pr, p, "agent-start", payload("session_id", s2, a, WRITER));
        step(pr, p, "agent-end", payload("session_id", s1, a, WRITER));
        expect(pr, !Files.exists(marker(s1, a)), "isolation: Stop left the marker of its own session");
        expect(pr, Files.isRegularFile(marker(s2, a)),
                "isolation: Stop deleted the same agent id's marker of another session");
        gate(pr, p, s2, false, "other session still running");
        failures += report("same agent id in two sessions — isolated", pr);

        pr = new ArrayList<>();
        p = project(null, null);
        s = session();
        a1 = agentId();
        a2 = agentId();
        step(pr, p, "agent-start", payload("session_id", s, a1, WRITER));
        step(pr, p, "agent-start", payload("sessionId", s, a2, READER));
        expect(pr, !Files.exists(marker(s, a2)) && !Files.exists(unknownMarker(a2)),
                "non-executor: a reader's Start wrote a marker");
        step(pr, p, "agent-end", payload("sessionId", s, a2, READER));
        expect(pr, Files.isRegularFile(marker(s, a1)), "non-executor: reader's Stop removed the writer's marker");
        gate(pr, p, s, false, "reader stopped, writer running");
        failures += report("non-executor Start writes nothing, its Stop leaves the writer", pr);

        pr = new ArrayList<>();
        p = project(null, SESSION_ALIASES);
        s = session();
        String other = session();
        a = agentId();
        step(pr, p, "agent-start",
                "{\"session_id\":\"" + s + "\",\"sessionId\":\"" + other + "\",\"agent_id\":\"" + a
                        + "\",\"agent_type\":\"" + WRITER + "\"}");
        expect(pr, Files.isRegularFile(marker(s, a)), "precedence: the canonical session_id did not win");
        expect(pr, !Files.exists(marker(other, a)), "precedence: the alias replaced the canonical session_id");
        failures += report("both spellings present — canonical session_id wins", pr);

        // The recognized spelling is data: a project's own alias key works, and the Copilot spelling
        // is then no alias at all (no Java name list).
        pr = new ArrayList<>();
        p = project(null, "{\"session_id\": [\"fixtureSid\"]}");
        s = session();
        a = agentId();
        step(pr, p, "agent-start", payload("fixtureSid", s, a, WRITER));
        expect(pr, Files.isRegularFile(marker(s, a)), "alias data: the fixture's alias was not recognized");
        expect(pr, !Files.exists(unknownMarker(a)), "alias data: the fixture's alias wrote unknown/" + a);
        a2 = agentId();
        step(pr, p, "agent-start", payload("sessionId", s, a2, WRITER));
        expect(pr, !Files.exists(marker(s, a2)),
                "alias data: `sessionId` still recognized though the fixture's data does not list it");
        failures += report("alias spelling is read from extensions.json", pr);

        pr = new ArrayList<>();
        p = project(0, null);
        s = session();
        a = agentId();
        step(pr, p, "agent-start", payload("sessionId", s, a, WRITER));
        expect(pr, Files.isRegularFile(marker(s, a)), "expiry: Start(sessionId) wrote no session marker");
        gate(pr, p, s, true, "expired marker");
        expect(pr, !Files.exists(marker(s, a)), "expiry: the expired marker was not deleted");
        failures += report("expired marker — gate runs Maven and deletes it", pr);

        return failures;
    }

    static String session() {
        String s = "ci-" + UUID.randomUUID();
        SESSIONS.add(s);
        return s;
    }

    static String agent(String session, String id, String type) {
        return "{\"session_id\":\"" + session + "\",\"agent_id\":\"" + id
                + "\",\"agent_type\":\"" + type + "\"}";
    }

    record Result(int code, String out) { }

    static Result hook(Path project, String phase, String stdin) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", HOOK.toString(), "tests"));
        if (phase != null) cmd.add(phase);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", project.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(proc.waitFor(), out);
    }

    /** A single-module project with one untracked source, a recording wrapper, and the schema. */
    static Path project(Integer cap) throws Exception {
        return project(cap, null);
    }

    /** {@code aliases}, when given, is the fixture's `tests.input_aliases`, replacing or adding it. */
    static Path project(Integer cap, String aliases) throws Exception {
        Path dir = Files.createTempDirectory("archhook-testsdefer").toRealPath();
        PROJECTS.add(dir);
        Files.writeString(dir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>\n");
        Path src = dir.resolve("src/main/java/a/X.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "class X {}\n");
        String schema = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        if (cap != null) {
            Matcher m = Pattern.compile("\"writer_agent_max_minutes\"\\s*:\\s*\\d+").matcher(schema);
            if (!m.find()) throw new IllegalStateException(
                    "extensions.json has no tests.writer_agent_max_minutes to override");
            schema = m.replaceFirst("\"writer_agent_max_minutes\": " + cap);
        }
        if (aliases != null) {
            Matcher m = Pattern.compile("\"input_aliases\"\\s*:\\s*\\{[^{}]*\\}").matcher(schema);
            String entry = "\"input_aliases\": " + aliases;
            if (m.find()) {
                schema = m.replaceFirst(Matcher.quoteReplacement(entry));
            } else {
                Matcher w = Pattern.compile("\"writer_agent_max_minutes\"\\s*:\\s*\\d+").matcher(schema);
                if (!w.find()) throw new IllegalStateException(
                        "extensions.json has no tests.writer_agent_max_minutes to anchor the aliases");
                schema = schema.substring(0, w.end()) + ",\n    " + entry + schema.substring(w.end());
            }
        }
        Files.createDirectories(dir.resolve(".claude/schemas"));
        Files.writeString(dir.resolve(".claude/schemas/extensions.json"), schema);
        if (WINDOWS) {
            Files.writeString(dir.resolve("mvnw.cmd"), "@echo %*>> \"%~dp0mvnw-args.txt\"\r\n");
        } else {
            Path w = dir.resolve("mvnw");
            Files.writeString(w,
                    "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$(dirname \"$0\")/mvnw-args.txt\"\n");
            w.toFile().setExecutable(true);
        }
        Process g = new ProcessBuilder("git", "init", "-q").directory(dir.toFile())
                .inheritIO().start();
        if (g.waitFor() != 0) throw new IllegalStateException("git init");
        return dir;
    }

    static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                f.toFile().setWritable(true);
                Files.deleteIfExists(f);
            }
        }
    }
}
