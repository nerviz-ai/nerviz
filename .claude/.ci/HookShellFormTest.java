///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves every hook registration of both settings files starts the same way under
// Claude Code and under the Copilot CLI, behaves exactly as the exec form it replaced, and
// never exits with a code the Copilot CLI turns into a denial of every call.
//
// Why this test exists: until decision 0168 every entry was exec form (`command: java` +
// `args`). The Copilot CLI reads `.claude/settings.json` too, runs `command` alone and ignores
// `args`, so it launched a bare `java`, which exits 1 — and on `preToolUse` any exit other
// than 0 or 2 denies the call. Every Edit, Shell and Read of a generated project was denied
// (lessons-learned-022 § 2). The fix is a shape, and a shape rots in silence: the next entry
// written in exec form, or a mode that throws on a payload it cannot read, brings the block
// back with nothing failing here. `schema` holds the text of each entry; this holds what the
// text does when a shell runs it.
//
// Three launches per registration and payload, and all three must agree:
//   exec     — the old exec form, argv spawned with no shell: the behaviour to preserve
//   claude   — the placeholder substituted first, then the shell, as Claude Code runs it
//   copilot  — the raw string with CLAUDE_PROJECT_DIR in the environment, as Copilot runs it
// Same exit code, same stdout, and the code is 0 or 2.
//
// The shell is `sh -c` on Linux and macOS and Git Bash on Windows — the shells Claude Code
// itself picks for shell form. The payloads are the shapes lessons-learned-022 captured,
// trimmed: Claude's object `tool_input`, Copilot's `path` on Read and its patch as a string.
// Only `java -jar …/ArchHook.jar` entries run here: the meta-repo's `build` entry compiles the
// source and rewrites the jar, which `build --verify` earlier in the job already proves.
//
// Uses the repository's real settings files and jar, like BashGuardTest. Runs the jar, not the
// source: that is what the hooks launch.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public class HookShellFormTest {

    static final List<String> SETTINGS = List.of(
            ".claude/settings.json",
            ".claude/skills/project-bootstrap/templates/settings.json.example");

    static final String SESSION = "ci-shellform";

    /** label → stdin. Shapes from lessons-learned-022 § 6, paths made relative. */
    static final Map<String, String> PAYLOADS = new LinkedHashMap<>();
    static {
        PAYLOADS.put("empty payload", "{}");
        PAYLOADS.put("Claude Bash", "{\"session_id\":\"" + SESSION + "\",\"hook_event_name\":\"PreToolUse\","
                + "\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git status\",\"description\":\"x\"}}");
        PAYLOADS.put("Claude Write", "{\"session_id\":\"" + SESSION + "\",\"hook_event_name\":\"PreToolUse\","
                + "\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"notes/probe.txt\",\"content\":\"ok\"}}");
        PAYLOADS.put("Copilot Read (path)", "{\"session_id\":\"" + SESSION + "\",\"hook_event_name\":\"PreToolUse\","
                + "\"tool_name\":\"Read\",\"tool_input\":{\"path\":\"CLAUDE.md\"}}");
        PAYLOADS.put("Copilot Edit (patch string)", "{\"session_id\":\"" + SESSION + "\",\"hook_event_name\":\"PreToolUse\","
                + "\"tool_name\":\"Edit\",\"tool_input\":\"*** Begin Patch\\n*** Add File: notes/probe.txt\\n+ok\\n*** End Patch\\n\"}");
    }

    static final Pattern COMMAND = Pattern.compile("\"command\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");
    static final String PLACEHOLDER = "${CLAUDE_PROJECT_DIR}";

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        int failures = 0;

        // 1. Every registration is shell form: no `args`, and an ArchHook entry carries the
        //    quoted placeholder. `schema` says the same in words; this fails the job by name.
        Set<String> commands = new LinkedHashSet<>();
        for (String rel : SETTINGS) {
            String text = Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
            if (text.contains("\"args\"")) {
                System.out.println("❌ " + rel + " still holds an `args` list — exec form, which"
                        + " the Copilot CLI ignores");
                failures++;
            }
            Matcher m = COMMAND.matcher(text);
            while (m.find()) {
                String cmd = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
                if (cmd.contains("ArchHook.") && !cmd.contains("\"" + PLACEHOLDER + "/")) {
                    System.out.println("❌ " + rel + " — `" + cmd + "` does not quote " + PLACEHOLDER);
                    failures++;
                }
                if (cmd.startsWith("java -jar ")) commands.add(cmd);
            }
        }
        if (commands.isEmpty()) {
            System.out.println("❌ no `java -jar` registration found — nothing was exercised");
            System.exit(1);
        }

        // 2. and 3. Each registration × each payload, under the three launches.
        List<String> shell = shell();
        for (String cmd : commands) {
            String claudeCmd = cmd.replace(PLACEHOLDER, root.toString());
            List<String> exec = new ArrayList<>();
            Matcher t = TOKEN.matcher(claudeCmd);
            while (t.find()) exec.add(t.group(1) != null ? t.group(1) : t.group(2));

            String mode = cmd.substring(cmd.indexOf("ArchHook.jar\"") + 13).trim();
            for (Map.Entry<String, String> p : PAYLOADS.entrySet()) {
                Result e = run(exec, root, p.getValue());
                Result c = run(concat(shell, claudeCmd), root, p.getValue());
                Result k = run(concat(shell, cmd), root, p.getValue());
                String label = "`" + mode + "` · " + p.getKey();
                if (e.code != 0 && e.code != 2) {
                    System.out.println("❌ " + label + " — exit " + e.code + ": under Copilot a"
                            + " preToolUse hook that exits neither 0 nor 2 denies the call");
                    System.out.println(e.out);
                    failures++;
                } else if (c.code != e.code || !c.out.equals(e.out)) {
                    System.out.println("❌ " + label + " — Claude Code's shell form (exit " + c.code
                            + ") differs from the exec form it replaced (exit " + e.code + ")");
                    System.out.println(c.out);
                    failures++;
                } else if (k.code != e.code || !k.out.equals(e.out)) {
                    System.out.println("❌ " + label + " — the Copilot launch (exit " + k.code
                            + ") differs from the exec form (exit " + e.code + "): the shell did"
                            + " not expand " + PLACEHOLDER + " from the environment");
                    System.out.println(k.out);
                    failures++;
                } else {
                    System.out.println("✅ " + label + " — exit " + e.code + " in all three launches");
                }
            }
        }

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — a hook registration does not"
                    + " run the same under Claude Code and the Copilot CLI (decision 0168).");
            System.exit(1);
        }
        System.out.println("✅ " + commands.size() + " registration(s): shell form, the same"
                + " behaviour as exec form, exit 0 or 2 on every payload.");
    }

    /** The shell Claude Code uses for shell form: `sh -c`, or Git Bash on Windows. */
    static List<String> shell() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return List.of("sh", "-c");
        for (String p : List.of("C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe")) {
            if (Files.isRegularFile(Paths.get(p))) return List.of(p, "-c");
        }
        return List.of("bash", "-c");
    }

    static List<String> concat(List<String> shell, String cmd) {
        List<String> l = new ArrayList<>(shell);
        l.add(cmd);
        return l;
    }

    record Result(int code, String out) {}

    /** Spawns argv with the hook's environment and stdin; stdout is what the runtime parses. */
    static Result run(List<String> argv, Path root, String stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("CLAUDE_PROJECT_DIR", root.toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result(-1, "timed out");
        }
        return new Result(p.exitValue(), out);
    }
}
