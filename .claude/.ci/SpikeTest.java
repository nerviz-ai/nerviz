///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar spike <worktree>` writes a linked worktree's hook registrations
// down to `spike.keep_modes` of extensions.json, marks the file skip-worktree so it cannot be
// committed from there, leaves the main checkout alone, refuses the main checkout, and reads the
// kept modes from data.
//
// Why this test exists: the mode is what keeps the audit trail on in a spike while every other
// check is off. Each way it can break is quiet in review: a filter that keeps everything is a
// spike with enforcement still on; a write under the wrong root relaxes the main checkout; a
// missing skip-worktree lets the relaxed registrations be committed and merged; a missing
// linked-worktree check lets `spike .` strip the main checkout; a hardcoded "audit" ignores the
// data (invariant 10). Decision 0198.
//
// Runs in a throwaway git project holding a copy of the real extensions.json, so the data under
// test is production data. Needs `java` and `git` only.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class SpikeTest {

    static final String SETTINGS = """
            {
              "$schema": "https://json.schemastore.org/claude-code-settings.json",
              "permissions": {
                "deny": ["Bash(git push --force:*)"]
              },
              "hooks": {
                "UserPromptSubmit": [
                  {
                    "hooks": [
                      {
                        "type": "command",
                        "command": "java",
                        "args": ["-jar", "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar", "audit", "prompt"],
                        "timeout": 30
                      }
                    ]
                  }
                ],
                "PreToolUse": [
                  {
                    "matcher": "Write|Edit",
                    "hooks": [
                      {
                        "type": "command",
                        "command": "java",
                        "args": ["-jar", "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar", "guard", "write"],
                        "timeout": 30
                      }
                    ]
                  }
                ],
                "PostToolUse": [
                  {
                    "matcher": "Edit",
                    "hooks": [
                      {
                        "type": "command",
                        "command": "java",
                        "args": ["-jar", "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar", "check"],
                        "timeout": 30
                      }
                    ]
                  }
                ]
              }
            }
            """;

    record R(int exit, String out) {}

    static Path hook;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path base = Files.createTempDirectory("archhook-spike-ci").toRealPath();
        Path project = base.resolve("proj");
        Path wt = base.resolve("w");
        Path schemas = project.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        Files.writeString(project.resolve(".claude/settings.json"), SETTINGS);
        git(project, "init", "-q");
        git(project, "add", "-A");
        git(project, "-c", "user.name=ci", "-c", "user.email=ci@example.com", "commit", "-q", "-m", "init");
        git(project, "worktree", "add", "-q", wt.toString(), "-b", "spike/t");
        Path mainSettings = project.resolve(".claude/settings.json");
        Path wtSettings = wt.resolve(".claude/settings.json");
        byte[] mainBefore = Files.readAllBytes(mainSettings);

        R first = spike(project, wt.toString());
        check("1 worktree keeps the audit entry, drops guard and check, keeps permissions",
                first.exit == 0 && keepsOnlyAudit(Files.readString(wtSettings)), first.out);

        check("2 main checkout settings.json is byte-identical",
                Arrays.equals(mainBefore, Files.readAllBytes(mainSettings)), "main settings changed");

        R ls = git(wt, "ls-files", "-v", ".claude/settings.json");
        R st = git(wt, "status", "--porcelain");
        Files.writeString(wt.resolve("note.txt"), "x");
        git(wt, "add", "-A");
        git(wt, "-c", "user.name=ci", "-c", "user.email=ci@example.com", "commit", "-q", "-m", "spike");
        R files = git(wt, "show", "--name-only", "--format=", "HEAD");
        check("3 skip-worktree: flag S, clean status, settings.json left out of a commit",
                ls.out.startsWith("S") && st.out.isBlank() && files.out.contains("note.txt")
                        && !files.out.contains("settings.json"),
                "ls-files=" + ls.out + " status=" + st.out + " commit=" + files.out);

        byte[] before = Files.readAllBytes(mainSettings);
        R refused = spike(project, project.toString());
        check("4 the main checkout is refused: exit 2, 'not a linked worktree', file unchanged",
                refused.exit == 2 && refused.out.contains("not a linked worktree")
                        && Arrays.equals(before, Files.readAllBytes(mainSettings)),
                "exit " + refused.exit + " " + refused.out);

        // Case 5 and 6 run on a fresh worktree so case 3's commit does not interfere.
        Path wt2 = base.resolve("w2");
        git(project, "worktree", "add", "-q", wt2.toString(), "-b", "spike/t2");
        Path ext = schemas.resolve("extensions.json");
        Files.writeString(ext, Files.readString(ext).replace("\"keep_modes\": [\"audit\"]",
                "\"keep_modes\": [\"audit\", \"guard\"]"));
        R kept = spike(project, wt2.toString());
        String s2 = squash(Files.readString(wt2.resolve(".claude/settings.json")));
        check("5 keep_modes comes from the data: guard survives when listed",
                kept.exit == 0 && s2.contains("\"guard\",\"write\"") && s2.contains("\"audit\",\"prompt\"")
                        && !s2.contains("PostToolUse"),
                kept.out + s2);

        byte[] once = Files.readAllBytes(wt2.resolve(".claude/settings.json"));
        R again = spike(project, wt2.toString());
        check("6 a second run exits 0 with the same bytes",
                again.exit == 0 && Arrays.equals(once, Files.readAllBytes(wt2.resolve(".claude/settings.json"))),
                again.out);

        if (failures > 0) {
            System.out.println(failures + " case(s) failed");
            System.exit(1);
        }
    }

    /** The writer compacts arrays, so compare with whitespace stripped. */
    static String squash(String s) { return s.replaceAll("\\s", ""); }

    static boolean keepsOnlyAudit(String raw) {
        String s = squash(raw);
        return s.contains("\"audit\",\"prompt\"") && !s.contains("\"guard\"") && !s.contains("\"check\"")
                && !s.contains("PostToolUse") && !s.contains("PreToolUse")
                && s.contains("\"permissions\"") && s.contains("\"deny\":[\"Bash(gitpush--force:*)\"]");
    }

    static void check(String label, boolean ok, String detail) {
        if (ok) { System.out.println("✅ " + label); return; }
        failures++;
        System.out.println("❌ " + label);
        System.out.println(detail);
    }

    static R spike(Path project, String worktree) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "spike", worktree).directory(project.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", project.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new R(p.waitFor(), out);
    }

    static R git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        return new R(code, out);
    }
}
