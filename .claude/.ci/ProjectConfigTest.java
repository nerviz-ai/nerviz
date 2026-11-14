///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar config` lists every file of `project_config`, writes a valid
// value, refuses an invalid one without touching the file (exit 2), refuses a key fixed
// upstream and a setting of an audit trail the project switched off, deletes a file `unset`
// leaves empty — and that `guard` refuses a tool's direct write into a registered path, naming
// `config set`, while `guard sweep` lets the write `config` made through.
//
// Why this test exists: `config` is the only writer of the files a generated project owns in
// its `.claude/` (decision 0187). A validator that let a wrong value through would write the
// silent switch-nothing file 0111 and 0186 made `doctor` fail on; a guard that let the model
// edit the JSON would bring back two owners of each file's shape; a sweep that judged the
// write `config` made would block every `/project-config` run at Stop.
//
// Runs in a throwaway git project holding copies of the real extensions.json and ArchHook.jar,
// with CLAUDE_PROJECT_DIR pointing at it. Runs the jar, not the source: that is what the skill,
// the guard hook and a later mod launch.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ProjectConfigTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static Path p;

    public static void main(String[] args) throws Exception {
        p = fixture();

        String list = run(0, null, "config", "list").out;
        for (String id : List.of("audited", "plan-limits", "compose-gate")) {
            check(list.contains("\"id\":\"" + id + "\""), "config list names `" + id + "`");
        }
        check(list.contains("\"key\":\"skills.report-issue\"") && list.contains("\"key\":\"pro.five_hour_tokens\""),
                "config list expands {skill} and {budget_plan}.{window_budget} to concrete keys");
        check(list.contains("\"key\":\"skills.arch-adopt\",") && list.matches("(?s).*\"skills.arch-adopt\"[^}]*\"fixed\":true.*"),
                "config list marks skills.arch-adopt as fixed");

        Path gate = p.resolve(".claude/compose-gate.json");
        run(0, null, "config", "set", "compose-gate", "exited", "warn");
        check(Files.readString(gate).contains("\"exited\": \"warn\""), "set compose-gate exited warn writes the file");
        String before = Files.readString(gate);
        run(2, null, "config", "set", "compose-gate", "exited", "maybe");
        run(2, null, "config", "set", "compose-gate", "foreign_ports", "warn");
        check(Files.readString(gate).equals(before), "a refused set leaves the file as it was");
        check(run(0, null, "doctor").out.contains("`exited` = \"warn\""), "doctor reports the value config wrote as in force");

        Path trail = p.resolve(".claude/audit-usage");
        run(2, null, "config", "set", "audited", "skills.report-issue", "false");
        check(!Files.exists(trail), "a setting of a switched-off audit trail is refused and creates no trail");
        Files.createDirectories(trail);
        run(0, null, "config", "set", "audited", "skills.report-issue", "false");
        run(2, null, "config", "set", "audited", "skills.arch-adopt", "true");
        run(2, null, "config", "set", "audited", "skills.no-such-skill", "true");
        check(Files.readString(trail.resolve("audited.json")).contains("\"report-issue\": false")
                        && !Files.readString(trail.resolve("audited.json")).contains("arch-adopt"),
                "audited.json holds the valid piece and none of the refused ones");

        run(2, null, "config", "set", "plan-limits", "pro.five_hour_tokens", "-3");
        run(0, null, "config", "set", "plan-limits", "pro.five_hour_tokens", "400000");
        check(Files.readString(trail.resolve("plan-limits.json")).contains("\"five_hour_tokens\": 400000"),
                "a whole number is written without a fraction");

        run(0, null, "config", "unset", "audited", "skills.report-issue");
        check(!Files.exists(trail.resolve("audited.json")), "unset of the last key deletes the file — the default");
        run(2, null, "config", "set", "no-such-id", "a", "b");
        run(1, null, "config", "bogus");

        for (String rel : List.of(".claude/compose-gate.json", ".claude/audit-usage/audited.json",
                ".claude/audit-usage/plan-limits.json")) {
            Res r = run(2, "{\"session_id\":\"cfg\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\""
                    + p.resolve(rel).toString().replace("\\", "\\\\") + "\"}}", "guard", "write");
            check(r.out.contains("config set"), "guard write refuses " + rel + " and names config set");
        }
        Res bash = run(2, "{\"session_id\":\"cfg\",\"tool_input\":{\"command\":\"echo {} > .claude/compose-gate.json\"}}",
                "guard", "bash");
        check(bash.out.contains("config set"), "guard bash refuses a redirect into a project setting");
        run(0, "{\"session_id\":\"cfg\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\""
                + p.resolve("notes.md").toString().replace("\\", "\\\\") + "\"}}", "guard", "write");

        // The sweep: a `/project-config` phase (class ops, nothing writable) is open, `config`
        // writes the file, Stop must pass. A stray file in the same turn must still be caught.
        git("add", "-A");
        git("commit", "-qm", "base");
        String sweep = "{\"session_id\":\"cfg-sweep\"}";
        run(0, "{\"session_id\":\"cfg-sweep\",\"prompt\":\"/project-config\"}", "guard", "prompt");
        run(0, null, "config", "set", "compose-gate", "exited", "block");
        run(0, sweep, "guard", "sweep");
        Files.writeString(p.resolve("stray.md"), "x\n");
        run(2, sweep, "guard", "sweep");

        System.out.println(failures == 0
                ? "\n✅ config writes only valid project settings, and guard leaves it the only writer."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Res(int code, String out) { }

    static Res run(int wanted, String stdin, String... mode) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(p.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", p.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (var os = proc.getOutputStream()) {
            if (stdin != null) os.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = proc.waitFor();
        String name = String.join(" ", mode) + " → exit " + wanted;
        if (code == wanted) {
            System.out.println("✅ " + name);
        } else {
            fail(name + " — got " + code);
            System.out.println(out);
        }
        return new Res(code, out);
    }

    static void check(boolean ok, String name) {
        if (ok) System.out.println("✅ " + name); else fail(name);
    }

    static void fail(String m) {
        failures++;
        System.out.println("❌ " + m);
    }

    static void git(String... a) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.email=ci@x", "-c", "user.name=ci"));
        cmd.addAll(List.of(a));
        new ProcessBuilder(cmd).directory(p.toFile()).redirectErrorStream(true).start().waitFor();
    }

    /** A git project with the real extensions.json, three skills and no audit trail. */
    static Path fixture() throws Exception {
        Path d = Files.createTempDirectory("archhook-project-config-ci");
        Path schemas = d.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        for (String s : List.of("report-issue", "arch-adopt", "project-config")) {
            Path f = d.resolve(".claude/skills/" + s + "/SKILL.md");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "---\nname: " + s + "\n---\n");
        }
        new ProcessBuilder("git", "init", "-q").directory(d.toFile()).start().waitFor();
        return d;
    }
}
