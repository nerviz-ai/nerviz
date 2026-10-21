///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar guard sweep` reports, at Stop, a file this turn wrote outside
// the open phase's territory — whatever tool wrote it — and stays silent about a file that
// was already dirty when the turn began.
//
// Why this test exists: the sweep is the only guard that is filesystem-shaped instead of
// tool-shaped, and it is only as good as the baseline `guard prompt` takes at
// UserPromptSubmit. Break the baseline and it fails one of two ways, both quiet in review:
// it reports somebody's half-finished edit on every Stop (and gets routed around), or it
// reports nothing at all. Decision 0065.
//
// It also proves the one exemption, `guard.sweep_exempt`: the audit trail a hook writes during
// the turn is not handed back as the phase's write, while a Write to it is still refused.
// Decision 0105, issue #55.
//
// And it proves the sweep skips what a tool-time guard already admitted: a `/new-feature` run
// whose design skill wrote the spec and partials, whose executor wrote src/, and which then
// chained `git-publish` (class `ops`, nothing writable) and approved the folder, is silent —
// while a file no tool-time guard saw in that same turn is still named. Decision 0114,
// issue #74.
//
// Runs in a throwaway git repository holding a copy of the real extensions.json, so the
// territory under test is production data. Writes the files with plain Java — the point is
// that no tool-time guard saw them.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class SweepTest {

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path repo = Files.createTempDirectory("archhook-sweep-ci");
        Path schemas = repo.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        git(repo, "init", "-q");

        int failures = 0;

        // Dirty before the turn: must never be reported.
        Files.writeString(repo.resolve("before.txt"), "x");

        String s1 = "ci-sweep-" + System.nanoTime();
        failures += guard(hook, repo, "prompt", "{\"session_id\":\"" + s1
                + "\",\"prompt\":\"/persistence-architect design it\"}", 0, null, null,
                "prompt opens a design phase and takes the baseline");
        Path java = repo.resolve("src/main/java/Out.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "class Out {}");
        failures += guard(hook, repo, "sweep", "{\"session_id\":\"" + s1 + "\"}", 2,
                "src/main/java/Out.java", "before.txt",
                "write outside the territory — reported, pre-existing dirt is not");
        failures += guard(hook, repo, "sweep", "{\"session_id\":\"" + s1
                + "\",\"stop_hook_active\":true}", 0, null, null,
                "stop_hook_active — does not block twice");

        // A second turn that stays inside the territory is silent, even with the tree dirty.
        String s2 = "ci-sweep-" + System.nanoTime();
        failures += guard(hook, repo, "prompt", "{\"session_id\":\"" + s2
                + "\",\"prompt\":\"/persistence-architect again\"}", 0, null, null,
                "new turn — new baseline");
        Path doc = repo.resolve("docs/use-cases/UC-001-x/notes.md");
        Files.createDirectories(doc.getParent());
        Files.writeString(doc, "notes");
        failures += guard(hook, repo, "sweep", "{\"session_id\":\"" + s2 + "\"}", 0, null, null,
                "write inside the territory — silent");

        // The audit hook writes its versioned trail in parallel with the baseline and at Stop.
        // The sweep must not hand it back as the phase's write; the tool-time guard still
        // refuses the model the same path. Decision 0105, issue #55.
        String s3 = "ci-sweep-" + System.nanoTime();
        failures += guard(hook, repo, "prompt", "{\"session_id\":\"" + s3
                + "\",\"prompt\":\"/new-feature UC-002-x\"}", 0, null, null,
                "orchestrator phase opens");
        Path audit = repo.resolve(".claude/audit-usage");
        Files.createDirectories(audit);
        Files.writeString(audit.resolve("history.jsonl"), "{}\n");
        Files.writeString(audit.resolve("2026-10-01T11-07-39--new-feature.md"), "# run");
        failures += guard(hook, repo, "sweep", "{\"session_id\":\"" + s3 + "\"}", 0, null,
                ".claude/audit-usage/", "audit trail written during the turn — not reported");
        failures += guard(hook, repo, "write", "{\"session_id\":\"" + s3
                + "\",\"tool_input\":{\"file_path\":\".claude/audit-usage/history.jsonl\"}}", 2,
                ".claude/audit-usage/history.jsonl", null,
                "the model writing the trail through Write — still refused");

        // A write a tool-time guard admitted is not judged again at Stop. `/new-feature` hands
        // over to a design skill, writes its spec and partials, approves the folder, chains
        // `git-publish` (class `ops`, nothing writable) — and the sweep used to judge all of it
        // against that empty territory, then against the folder's `approved` status. Issue #74,
        // decision 0114.
        String s4 = "ci-sweep-" + System.nanoTime();
        failures += guard(hook, repo, "prompt", "{\"session_id\":\"" + s4
                + "\",\"prompt\":\"/new-feature an order\"}", 0, null, null,
                "orchestrator phase opens");
        failures += guard(hook, repo, "call", "{\"session_id\":\"" + s4
                + "\",\"tool_name\":\"Skill\",\"tool_input\":{\"skill\":\"use-case-design\"}}", 0,
                null, null, "a design skill narrows the phase");
        Path uc = repo.resolve("docs/use-cases/UC-006-order");
        String spec = "docs/use-cases/UC-006-order/UC-006-spec.md";
        String partial = "docs/use-cases/UC-006-order/00-use-case.md";
        String changelog = "docs/use-cases/UC-001-x/CHANGELOG.md";
        for (String rel : new String[] { spec, partial, changelog }) {
            failures += guard(hook, repo, "write", "{\"session_id\":\"" + s4
                    + "\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"" + abs(repo, rel)
                    + "\"}}",
                    0, null, null, "design skill writes " + rel + " — admitted");
            Files.createDirectories(repo.resolve(rel).getParent());
            Files.writeString(repo.resolve(rel), "---\nstatus: draft\n---\n");
        }
        Files.writeString(repo.resolve(spec), "---\nstatus: approved\n---\n");
        failures += guard(hook, repo, "call", "{\"session_id\":\"" + s4
                + "\",\"tool_name\":\"Skill\",\"tool_input\":{\"skill\":\"git-publish\"}}", 0,
                null, null, "git-publish (ops) replaces the phase");
        String src = "src/main/java/Order.java";
        failures += guard(hook, repo, "write", "{\"session_id\":\"" + s4
                + "\",\"agent_type\":\"java-spring-boot-developer\",\"tool_name\":\"Write\","
                + "\"tool_input\":{\"file_path\":\"" + abs(repo, src) + "\"}}", 0, null, null,
                "executor writes src/ — admitted by its own class");
        Files.createDirectories(repo.resolve(src).getParent());
        Files.writeString(repo.resolve(src), "class Order {}");
        failures += guard(hook, repo, "sweep", "{\"session_id\":\"" + s4 + "\"}", 0, null, "UC-006",
                "admitted writes under an earlier phase, folder approved since — silent");

        // The same turn with one file no tool-time guard saw: still judged, against the phase
        // open at Stop, and named alone.
        Path unseen = uc.resolve("20-persistence.md");
        Files.writeString(unseen, "written by a script");
        failures += guard(hook, repo, "sweep", "{\"session_id\":\"" + s4 + "\"}", 2,
                "UC-006-order/20-persistence.md", "UC-006-spec.md",
                "a write no tool-time guard saw — still reported, admitted ones are not");

        // A write into the now-approved folder, in a session with no phase open: refused as
        // frozen, and the refusal names the status moves guard.status_transitions admits from
        // `approved` — the message once said only "the spec's own `status:` line" (issue #167,
        // decision 0180).
        String s5 = "ci-sweep-" + System.nanoTime();
        failures += guard(hook, repo, "write", "{\"session_id\":\"" + s5
                + "\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"" + abs(repo, partial)
                + "\"}}", 2, "only from `approved` to `implemented` or `implemented-blocked`", null,
                "write into an approved folder — refused, naming the admitted status moves");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `guard sweep` is NOT"
                    + " separating what this turn wrote from what was already dirty.");
            System.exit(1);
        }
        System.out.println("✅ guard sweep: this turn's out-of-territory write reported,"
                + " pre-existing changes, in-territory writes, the audit trail and writes a tool-time"
                + " guard already admitted left alone.");
    }

    /** An absolute `file_path`, as the runtime sends it, with `/` so Windows stays valid JSON. */
    static String abs(Path repo, String rel) {
        return repo.resolve(rel).toString().replace('\\', '/');
    }

    static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args));
    }

    /** Runs one `guard <phase>`; checks the exit code, a line it must name and one it must not. */
    static int guard(Path hook, Path repo, String phase, String stdin, int wanted,
                     String mustName, String mustNotName, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "guard", phase).directory(repo.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", repo.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        String why = code != wanted ? "expected exit " + wanted + ", got " + code
                : mustName != null && !out.contains(mustName) ? "output does not name " + mustName
                : mustNotName != null && out.contains(mustNotName) ? "output names " + mustNotName
                : null;
        if (why == null) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — " + why);
        System.out.println(out);
        return 1;
    }
}
