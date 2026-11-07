///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar guard bash` refuses a force push in every spelling
// `guard.force_push` claims to read, refuses an unquoted glob flag when the shell is zsh
// (`guard.unquoted_glob`, decision 0203), applies the open phase's territory to the targets
// `guard.bash_write_shapes` names, and lets an ordinary command through.
//
// Why this test exists: `guard bash` runs at PreToolUse on EVERY shell command, so it can
// fail in two directions and both are expensive. Too loose, and a `-uf` or `sh -c "…"` walks
// past the one refusal decision 0076 promised — the `deny` line it replaced only caught the
// literal `--force`. Too tight, and it blocks `ls` for every session in every generated
// project. Neither shows up in review: the parser is data-driven, and a data edit to
// `extensions.json` changes behaviour without touching a line of Java.
//
// Uses the repository's real extensions.json, like SkillTerritoryTest. Runs the jar, not the
// source: that is what the hooks launch, and `build --verify` earlier in the job proves the
// two are the same bytes.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class BashGuardTest {

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        String session = "ci-bash-" + System.nanoTime();
        int failures = 0;

        // No phase open: only the force-push refusal applies.
        failures += run(hook, "prompt", session, "{\"session_id\":\"%s\",\"prompt\":\"hi\"}",
                0, "plain prompt — no phase");
        failures += bash(hook, session, "git push origin main", 0, "plain push — allowed");
        failures += bash(hook, session, "git push -f origin main", 2, "`-f` — blocked");
        failures += bash(hook, session, "git push -uf origin main", 2, "`-uf` — blocked");
        failures += bash(hook, session, "git push --force-with-lease", 2,
                "`--force-with-lease` — blocked");
        failures += bash(hook, session, "git push origin +main", 2, "`+ref` — blocked");
        failures += bash(hook, session, "git -C . push --force", 2, "`git -C . push` — blocked");
        failures += bash(hook, session, "sh -c \\\"git push --force\\\"", 2,
                "`sh -c \"…\"` — blocked");
        failures += bash(hook, session, "echo x > src/main/java/A.java", 0,
                "redirect with no phase open — allowed");

        // A design phase is docs-only: the same shell spellings now hit the territory.
        failures += run(hook, "prompt", session,
                "{\"session_id\":\"%s\",\"prompt\":\"/persistence-architect design it\"}",
                0, "prompt opens a design phase");
        failures += bash(hook, session, "echo x > src/main/java/A.java", 2,
                "redirect outside the territory — blocked");
        failures += bash(hook, session, "sed -i s/a/b/ src/main/java/A.java", 2,
                "`sed -i` outside the territory — blocked");
        failures += bash(hook, session, "sed s/a/b/ src/main/java/A.java", 0,
                "`sed` without `-i` reads — allowed");
        failures += bash(hook, session, "echo x > docs/use-cases/UC-001-x/notes.md", 0,
                "redirect inside the territory — allowed");
        failures += bash(hook, session, "echo x > $OUT/A.java", 0,
                "unresolvable target — skipped, by design");
        failures += bash(hook, session, "ls -la", 0, "innocent command — allowed");

        // sonar-lessons (decision 0100) keeps the scan's output short with `-q` precisely
        // because a redirect to a log file is a write outside its territory. Both halves are
        // pinned: if `guard bash` ever reads `./mvnw` itself as a writer of target/, the skill
        // stops working in every generated project, with nothing in review to show it.
        String report = "ci-bash-report-" + System.nanoTime();
        failures += run(hook, "prompt", report,
                "{\"session_id\":\"%s\",\"prompt\":\"/sonar-lessons\"}",
                0, "prompt opens sonar-lessons' report phase");
        failures += bash(hook, report, "./mvnw -B -q verify sonar:sonar", 0,
                "the scan itself — allowed");
        failures += bash(hook, report, "./mvnw -B verify sonar:sonar > target/scan.log", 2,
                "the scan redirected to target/ — blocked");
        failures += bash(hook, report,
                "gh issue create -R o/r --title t --body-file docs/lessons-learned/sonar-001.issue.md",
                0, "publishing the body file — allowed");

        // java-spring-boot-developer (decision 0117) runs every test build into a log under
        // $TMPDIR and reads failures from the Surefire/Failsafe reports, so the build output
        // stays out of its context. The log cannot go to target/: that is outside the
        // executor's territory. Both halves are pinned. If the guard ever refused the
        // documented command, every generated project's executor would be blocked at Block 4.
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "L=\\\"${TMPDIR:-/tmp}/uc-build.log\\\"; ./mvnw -q verify > \\\"$L\\\" 2>&1; echo \\\"EXIT=$?\\\"",
                0, "executor: the documented test run, log under $TMPDIR — allowed");
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "./mvnw -q verify > /tmp/uc-build.log 2>&1", 0,
                "executor: log at an absolute path outside the project — allowed");
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "./mvnw -q verify > target/uc-build.log 2>&1", 2,
                "executor: log under target/ — blocked, outside its territory");

        // `sed -i` reads its first operand as the script. BSD/macOS `sed -i ''` puts an empty
        // suffix there, and `-e`/`-f` carry the script as a flag value: before decision 0118
        // the script was then judged as a path (`s/a/b/g is outside its territory`), which
        // blocked the executor twice in real runs. The real target must still be judged.
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "sed -i '' 's/a/b/g' src/test/java/A.java", 0,
                "executor: BSD `sed -i ''` on a file in its territory — allowed");
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "sed -i '' -e 's/a/b/' src/test/java/A.java", 0,
                "executor: `sed -i '' -e` — allowed");
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "sed -i -e 's/a/b/' -e 's/c/d/' src/test/java/A.java", 0,
                "executor: two `-e` scripts — allowed");
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "sed -i --expression=s/a/b/ src/test/java/A.java", 0,
                "executor: `--expression=` — allowed");
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "sed -i '' 's/a/b/g' docs/notes.md", 2,
                "executor: BSD `sed -i ''` on a file outside its territory — blocked");
        failures += agentBash(hook, report, "java-spring-boot-developer",
                "sed -i -e 's/a/b/' docs/notes.md", 2,
                "executor: `sed -i -e` on a file outside its territory — blocked");

        // Decision 0203: under zsh an unquoted glob flag aborts the command with `no matches
        // found` before it runs; bash passes the word through. `guard.unquoted_glob` refuses it
        // only when SHELL names zsh. Every case sets SHELL on the spawned process explicitly:
        // the ones above keep what they inherit, and the machine running this may use either.
        String globs = "ci-bash-glob-" + System.nanoTime();
        failures += shell(hook, globs, "/bin/zsh", "grep -rn x src --include=*.java", 2,
                "--include='*.java'", "G1 zsh: unquoted `--include=*.java` — blocked, the message names the fix");
        failures += shell(hook, globs, "/bin/zsh", "grep -rn x src --include='*.java'", 0, null,
                "G2 zsh: quoted `--include='*.java'` — allowed");
        failures += shell(hook, globs, "/bin/zsh", "grep -rn x src --include=\\*.java", 0, null,
                "G3 zsh: backslash-escaped `--include=\\*.java` — allowed");
        failures += shell(hook, globs, "/bin/bash", "grep -rn x src --include=*.java", 0, null,
                "G4 bash: unquoted `--include=*.java` — allowed, bash passes the word through");
        failures += shell(hook, globs, "/usr/local/bin/zsh", "ls && grep -r x . --exclude-dir=*tmp*", 2, null,
                "G5 zsh by basename: unquoted `--exclude-dir=*tmp*` in the second segment — blocked");
        failures += shell(hook, globs, "/bin/zsh",
                "cat > \"$TMPDIR/x.md\" <<'EOF'\n--include=*.java\nEOF", 0, null,
                "G6 zsh: the glob sits in a heredoc body — allowed, not a command");
        failures += shell(hook, globs, "/bin/zsh", "git commit -m \"use --include=*.java\"", 0, null,
                "G7 zsh: the glob sits inside a quoted string — allowed");
        failures += shell(hook, globs, null, "grep -r x . --include=*.java", 0, null,
                "G8 SHELL unset: unquoted `--include=*.java` — allowed, no shell to judge");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `guard bash` is NOT"
                    + " enforcing what guard.force_push, guard.bash_write_shapes and guard.unquoted_glob declare.");
            System.exit(1);
        }
        System.out.println("✅ guard bash: every force-push spelling refused, write shapes"
                + " held to the open phase, an unquoted glob flag refused under zsh only,"
                + " ordinary commands let through.");
    }

    static int bash(Path hook, String session, String command, int wanted, String label)
            throws Exception {
        return run(hook, "bash", session,
                "{\"session_id\":\"%s\",\"tool_input\":{\"command\":\""
                        + command.replace("%", "%%") + "\"}}",
                wanted, label);
    }

    /**
     * One Bash call with SHELL set to {@code shell} on the spawned process ({@code null}: removed).
     * {@code command} is the real command; it is JSON-escaped here.
     * {@code needle}, when given, must appear in the output of a refusal.
     */
    static int shell(Path hook, String session, String shell, String command, int wanted,
                     String needle, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "guard", "bash");
        pb.environment().put("CLAUDE_PROJECT_DIR", Paths.get("").toAbsolutePath().toString());
        if (shell == null) pb.environment().remove("SHELL"); else pb.environment().put("SHELL", shell);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(("{\"session_id\":\"" + session + "\",\"tool_input\":{\"command\":\""
                + command.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                + "\"}}").getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code == wanted && (needle == null || out.contains(needle))) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — expected exit " + wanted
                + (needle == null ? "" : " naming " + needle) + ", got " + code);
        System.out.println(out);
        return 1;
    }

    /** A Bash call made by a subagent: judged by that agent's class, not by the open phase. */
    static int agentBash(Path hook, String session, String agentType, String command,
                         int wanted, String label) throws Exception {
        return run(hook, "bash", session,
                "{\"session_id\":\"%s\",\"agent_id\":\"ci-agent\",\"agent_type\":\""
                        + agentType + "\",\"tool_input\":{\"command\":\""
                        + command.replace("%", "%%") + "\"}}",
                wanted, label);
    }

    /** Runs one `guard <phase>` with the given stdin and checks the exit code. */
    static int run(Path hook, String phase, String session, String jsonTemplate,
                   int wanted, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "guard", phase);
        pb.environment().put("CLAUDE_PROJECT_DIR", Paths.get("").toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(jsonTemplate.formatted(session)
                .getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code == wanted) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — expected exit " + wanted + ", got " + code);
        System.out.println(out);
        return 1;
    }
}
