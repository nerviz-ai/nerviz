///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves a refusal (exit 2) of an ArchHook mode repeats its stderr on stdout, in the one
// shape `settings.deny_output` of extensions.json names for the payload's `hook_event_name`, and
// writes nothing on stdout anywhere else.
//
// Why this test exists: under the GitHub Copilot CLI a `preToolUse` deny reached the model as
// "Denied by preToolUse hook: hook exited with code 2", and a `postToolUse` stderr reached only
// the screen. Every explanation `guard` and `check` write was lost (lessons-learned-022 § 4). The
// probe of evidence/D3b-deny-reason.txt found the shapes that carry the reason without touching
// Claude Code: at exit 2, a flat `permissionDecision`/`permissionDecisionReason` on PreToolUse,
// which Claude Code ignores while it keeps showing stderr, and `decision: block` on PostToolUse
// (decision 0170). Two ways this rots in silence: a `hookSpecificOutput` added "for symmetry",
// which at exit 2 makes Copilot drop the reason and Claude Code drop stderr; and stdout written on
// a run with no payload, which breaks `doctor gate` and every command a person types.
//
// Runs in a throwaway project holding a copy of the real extensions.json. Runs the jar, not the
// source: that is what the hooks launch.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class DenyReasonTest {

    static Path hook;
    static Path root;
    static int failures = 0;

    static final String MODEL = "src/main/java/com/example/probe/domain/model/";
    static final String FORCE_PUSH = "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git push -f origin main\"}";

    public static void main(String[] args) throws Exception {
        hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        root = Files.createTempDirectory("archhook-deny-ci").toRealPath();
        Files.createDirectories(root.resolve(".claude/schemas"));
        Path schema = root.resolve(".claude/schemas/extensions.json");
        String real = Files.readString(Paths.get(".claude/schemas/extensions.json"), StandardCharsets.UTF_8);
        Files.writeString(schema, real, StandardCharsets.UTF_8);

        // ── PreToolUse: the flat object, its reason exactly stderr ───────────
        Run pre = run("guard", "bash", event("PreToolUse", FORCE_PUSH));
        check(pre.code == 2, "guard bash · PreToolUse force push — exit 2");
        check(pre.out.lines().count() == 1, "guard bash · PreToolUse — one stdout line");
        check(pre.out.startsWith("{\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\""),
                "guard bash · PreToolUse — the flat Copilot shape");
        check(!pre.out.contains("hookSpecificOutput"),
                "guard bash · PreToolUse — no hookSpecificOutput (it silences the reason at exit 2)");
        check(!pre.err.isBlank() && reason(pre.out, "permissionDecisionReason").equals(pre.err.strip()),
                "guard bash · PreToolUse — the reason is stderr, verbatim");
        if (failures > 0) System.out.println("stdout: " + pre.out + "\nstderr: " + pre.err);

        // ── PostToolUse: decision block, opened by the prefix ────────────────
        Files.writeString(root.resolve(".claude/forbidden-imports.txt"),
                MODEL.substring(0, MODEL.length() - 1) + "|org.springframework.\n");
        Path probe = root.resolve(MODEL + "Probe.java");
        Files.createDirectories(probe.getParent());
        Files.writeString(probe, "package com.example.probe.domain.model;\n\n"
                + "import org.springframework.stereotype.Component;\n\n@Component\npublic class Probe {}\n");
        Run post = run("check", null, event("PostToolUse", "{\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\""
                + json(probe.toString()) + "\",\"content\":\"x\"}"));
        check(post.code == 2, "check · PostToolUse forbidden import — exit 2");
        check(post.out.startsWith("{\"decision\":\"block\",\"reason\":\""),
                "check · PostToolUse — decision block, the one channel to Copilot's model");
        String postReason = reason(post.out, "reason");
        check(postReason.startsWith("The edit was applied") && postReason.endsWith(post.err.strip())
                        && postReason.contains("org.springframework."),
                "check · PostToolUse — the prefix that says the file is written, then stderr verbatim");

        // ── Nothing on stdout where no shape is named ────────────────────────
        Run none = run("guard", "bash", FORCE_PUSH + "}");
        check(none.code == 2 && none.out.isEmpty(), "guard bash · no hook_event_name (a person, `doctor gate`) — exit 2, stdout empty");
        Run stop = run("guard", "bash", event("Stop", FORCE_PUSH));
        check(stop.code == 2 && stop.out.isEmpty(), "guard bash · an event deny_output does not name — exit 2, stdout empty");
        Run ok = run("guard", "bash", event("PreToolUse", "{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git status\"}"));
        check(ok.code == 0 && ok.out.isEmpty(), "guard bash · an admitted call — exit 0, stdout empty");

        // ── The shape is data: without the block, stdout stays empty ─────────
        Files.writeString(schema, real.replace("\"deny_output\"", "\"deny_output_removed\""), StandardCharsets.UTF_8);
        Run noData = run("guard", "bash", event("PreToolUse", FORCE_PUSH));
        check(noData.code == 2 && noData.out.isEmpty() && !noData.err.isBlank(),
                "guard bash · settings.deny_output absent — exit 2, stderr only");

        System.out.println(failures == 0 ? "\n✅ DenyReasonTest: all checks passed"
                : "\n❌ DenyReasonTest: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Run(int code, String out, String err) {}

    /** `body` is a payload object without its closing brace; adds `hook_event_name` and closes it. */
    static String event(String name, String body) {
        return body + ",\"hook_event_name\":\"" + name + "\"}";
    }

    /** The string value of `field` in the one-line JSON `out`, unescaped. */
    static String reason(String out, String field) {
        String key = "\"" + field + "\":\"";
        int i = out.indexOf(key);
        if (i < 0) return "";
        StringBuilder b = new StringBuilder();
        for (int k = i + key.length(); k < out.length(); k++) {
            char c = out.charAt(k);
            if (c == '"') break;
            if (c == '\\' && k + 1 < out.length()) {
                char n = out.charAt(++k);
                switch (n) {
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> { b.append((char) Integer.parseInt(out.substring(k + 1, k + 5), 16)); k += 4; }
                    default -> b.append(n);
                }
            } else b.append(c);
        }
        return b.toString();
    }

    static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static void check(boolean ok, String label) {
        System.out.println((ok ? "✅ " : "❌ ") + label);
        if (!ok) failures++;
    }

    /** Runs one mode with `stdin`, stdout and stderr kept apart. */
    static Run run(String mode, String sub, String stdin) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", hook.toString(), mode));
        if (sub != null) cmd.add(sub);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", root.toString());
        Path err = Files.createTempFile(root, "stderr", ".txt");
        pb.redirectError(err.toFile());
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int code = p.waitFor();
        return new Run(code, out, Files.readString(err, StandardCharsets.UTF_8));
    }
}
