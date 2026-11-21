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
// Decision 0206 adds a fourth, on Windows: the `powershell` field every ArchHook entry
// carries (`$env:CLAUDE_PROJECT_DIR`), run through PowerShell as the Copilot CLI does — Windows
// PowerShell, or `pwsh` where it is installed. Section 1b also checks the field is the one
// derived from `command`. Decision 0213 appends the fixed suffix `archhook_powershell.suffix`
// of extensions.json to that field and launches it with command-string semantics
// (`-EncodedCommand`, which keeps the double quotes through the JDK's argv joining), never
// `-File`: `-File` returns the script's own exit code, while `-Command` returns 1 for any
// failing native command, which is where the exit 2 of a deny was lost.
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
        // The one payload whose answer is exit 2 (decision 0213): `guard bash` blocks a force push.
        // Every launch must carry that 2 — and the PowerShell form used to turn it into 1.
        PAYLOADS.put("Claude Bash force push", "{\"session_id\":\"" + SESSION + "\",\"hook_event_name\":\"PreToolUse\","
                + "\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git push --force origin main\",\"description\":\"x\"}}");
    }

    static final Pattern COMMAND = Pattern.compile("\"command\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");
    static final String PLACEHOLDER = "${CLAUDE_PROJECT_DIR}";
    static final String PS_PLACEHOLDER = "$env:CLAUDE_PROJECT_DIR";
    /** `settings.archhook_powershell.suffix` of extensions.json: data, never a literal here. */
    static String psSuffix;
    /** `command`, then the `powershell` field right after it, as both settings files write them (0206). */
    static final Pattern PAIR = Pattern.compile(
            "\"command\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"powershell\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        int failures = 0;
        Matcher sm = Pattern.compile("\"archhook_powershell\"[^\\n]*?\"suffix\":\\s*\"([^\"]*)\"").matcher(
                Files.readString(root.resolve(".claude/schemas/extensions.json"), StandardCharsets.UTF_8));
        psSuffix = sm.find() ? sm.group(1) : "";
        if (psSuffix.isEmpty()) {
            System.out.println("\u274c extensions.json settings.archhook_powershell.suffix is missing");
            System.exit(1);
        }

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

            // 1b. Every ArchHook entry carries `powershell`, derived from `command` (0206): the
            //     Copilot CLI copies `command` to PowerShell on Windows, where the placeholder
            //     is an unset variable and expands to nothing.
            int archEntries = 0;
            Matcher all = COMMAND.matcher(text);
            while (all.find()) {
                if (all.group(1).contains("ArchHook.")) archEntries++;
            }
            int pairs = 0;
            Matcher pm = PAIR.matcher(text);
            while (pm.find()) {
                String cmd = pm.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
                String ps = pm.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
                if (!cmd.contains("ArchHook.")) continue;
                pairs++;
                if (!ps.equals(cmd.replace(PLACEHOLDER, PS_PLACEHOLDER) + psSuffix)) {
                    System.out.println("❌ " + rel + " — `powershell` is `" + ps + "`, not `command` with "
                            + PLACEHOLDER + " replaced by " + PS_PLACEHOLDER + " and `" + psSuffix
                            + "` appended (decision 0213)");
                    failures++;
                }
            }
            if (pairs != archEntries) {
                System.out.println("❌ " + rel + " — " + (archEntries - pairs) + " of " + archEntries
                        + " ArchHook entries have no `powershell` field right after `command`: under"
                        + " Windows the Copilot CLI runs `command` in PowerShell, where "
                        + PLACEHOLDER + " expands to nothing (decision 0206)");
                failures++;
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
                    System.out.println(e.out + e.err);
                    failures++;
                } else if (c.code != e.code || !c.out.equals(e.out)) {
                    System.out.println("❌ " + label + " — Claude Code's shell form (exit " + c.code
                            + ") differs from the exec form it replaced (exit " + e.code + ")");
                    System.out.println(c.out + c.err);
                    failures++;
                } else if (k.code != e.code || !k.out.equals(e.out)) {
                    System.out.println("❌ " + label + " — the Copilot launch (exit " + k.code
                            + ") differs from the exec form (exit " + e.code + "): the shell did"
                            + " not expand " + PLACEHOLDER + " from the environment");
                    System.out.println(k.out + k.err);
                    failures++;
                } else if (PWSH != null && psCode(cmd, root, p.getValue(), e)) {
                    System.out.println("❌ " + label + " — the PowerShell launch differs from the exec"
                            + " form (exit " + e.code + "): `powershell` did not read "
                            + PS_PLACEHOLDER + " (decision 0206) or lost the exit code (decision 0213)");
                    failures++;
                } else {
                    System.out.println("✅ " + label + " — exit " + e.code + " in all launches");
                }
            }
        }

        // 4. The exit 2 through the `powershell` field, asserted on its own: a force push through
        //    `guard bash`, launched with command-string semantics, must exit 2 and hand over the
        //    flat object.
        if (PWSH == null) {
            if (WINDOWS) { System.out.println("❌ no PowerShell on this Windows runner"); failures++; }
            else System.out.println("⏭  `pwsh` is not installed here, the PowerShell launch is skipped");
        } else {
            String guardBash = commands.stream().filter(c -> c.endsWith(" guard bash")).findFirst().orElse(null);
            if (guardBash == null) {
                System.out.println("❌ no `guard bash` registration found");
                failures++;
            } else {
                Result r = psRun(guardBash, root, PAYLOADS.get("Claude Bash force push"));
                boolean ok = r.code == 2 && r.out.strip().startsWith("{\"permissionDecision\":\"deny\"");
                System.out.println((ok ? "✅ " : "❌ ") + "`guard bash` · force push through the `powershell` form ("
                        + PWSH + " -EncodedCommand) — exit " + r.code
                        + (ok ? ", flat deny object on stdout" : " (wanted 2 and the flat object)"));
                if (!ok) { System.out.println(r.out + r.err); failures++; }
            }
        }

        // 5. `schema` holds the field to the suffix, and holds `;` out of everywhere else.
        String hookJar = root.resolve(".claude/hooks/ArchHook.jar").toString();
        String settingsFile = ".claude/settings.json";
        String tpl = ".claude/skills/project-bootstrap/templates/settings.json.example";
        failures += schemaCase("schema · a `powershell` field without the suffix — refused",
                settingsFile, "guard prompt" + psSuffix + "\"", "guard prompt\"", "expected", hookJar, root);
        failures += schemaCase("schema · a `;` in the body of the `powershell` field — refused",
                settingsFile, "guard prompt" + psSuffix + "\"", "guard prompt; echo x" + psSuffix + "\"",
                ";", hookJar, root);
        failures += schemaCase("schema · the suffix dropped in the project template — refused",
                tpl, "guard prompt" + psSuffix + "\"", "guard prompt\"", "expected", hookJar, root);
        failures += schemaCase("schema · the shipped files — accepted", settingsFile, null, null, null, hookJar, root);

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — a hook registration does not"
                    + " run the same under Claude Code and the Copilot CLI (decision 0168).");
            System.exit(1);
        }
        System.out.println("✅ " + commands.size() + " registration(s): shell form, the same"
                + " behaviour as exec form, exit 0 or 2 on every payload.");
    }

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /** The shell Claude Code uses for shell form: `sh -c`, or Git Bash on Windows. */
    static List<String> shell() {
        if (!WINDOWS) return List.of("sh", "-c");
        for (String p : List.of("C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe")) {
            if (Files.isRegularFile(Paths.get(p))) return List.of(p);
        }
        return List.of("bash");
    }

    /**
     * The shell's argv for one command string. On Windows the string reaches Git Bash in a file,
     * not after `-c`: the JDK joins argv into one Windows command line without escaping the double
     * quotes inside an argument, so `bash -c` received the jar path unquoted and dropped its
     * backslashes (`D:\a\nerviz` read as `D:anerviz`) — a defect of this harness, not of the hook.
     * Claude Code spawns its shell from Node, which escapes them. Bash parses a script file the
     * way it parses `-c`.
     */
    static List<String> concat(List<String> shell, String cmd) throws Exception {
        List<String> l = new ArrayList<>(shell);
        if (WINDOWS) {
            Path script = Files.createTempFile("hook-", ".sh");
            script.toFile().deleteOnExit();
            Files.writeString(script, cmd + "\n", StandardCharsets.UTF_8);
            l.add(script.toString().replace('\\', '/'));
        } else {
            l.add(cmd);
        }
        return l;
    }

    /** The PowerShell this runner has: Windows PowerShell on Windows, else `pwsh` when it starts. */
    static final String PWSH = findPowershell();

    static String findPowershell() {
        for (String name : WINDOWS ? List.of("powershell", "pwsh") : List.of("pwsh")) {
            try {
                Process p = new ProcessBuilder(name, "-NoProfile", "-Command", "exit 0").start();
                p.getOutputStream().close();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) return name;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /**
     * Runs the `powershell` form the way the Copilot CLI does: the field's string as a command in
     * PowerShell, `CLAUDE_PROJECT_DIR` in the environment, the payload on stdin. The string is
     * `command` with the placeholder replaced and the suffix appended, as the settings files hold
     * it, and travels as `-EncodedCommand` (UTF-16LE base64), PowerShell's command-string mode, so
     * the double quotes survive the JDK's argv joining on Windows. Not `-File`: a script file
     * returns its own exit code and cannot see the loss decision 0213 closed.
     */
    static Result psRun(String cmd, Path root, String stdin) {
        try {
            String field = cmd.replace(PLACEHOLDER, PS_PLACEHOLDER) + psSuffix;
            String enc = Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_16LE));
            return run(List.of(PWSH, "-NoProfile", "-NonInteractive", "-EncodedCommand", enc), root, stdin);
        } catch (Exception e) {
            return new Result(-1, "", String.valueOf(e));
        }
    }

    static boolean psCode(String cmd, Path root, String stdin, Result exec) throws Exception {
        Result r = psRun(cmd, root, stdin);
        return r.code != exec.code || !r.out.equals(exec.out);
    }

    /**
     * Copies `.claude/` and the licence files into a throwaway tree, replaces `from` by `to` in
     * one settings file (when given) and runs `schema` there: refused (exit 2, naming `needle`)
     * when it changed something, accepted (exit 0) when it did not. Returns the failure count.
     */
    static int schemaCase(String label, String rel, String from, String to, String needle, String jar,
                          Path repo) throws Exception {
        Path tmp = Files.createTempDirectory("archhook-shellform-schema");
        try {
            try (var s = Files.walk(repo.resolve(".claude"))) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Path t = tmp.resolve(repo.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(t);
                    else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (String name : new String[] {"LICENSE", "NOTICE", "CLAUDE.md"}) {
                if (Files.exists(repo.resolve(name))) Files.copy(repo.resolve(name), tmp.resolve(name));
            }
            if (from != null) {
                Path f = tmp.resolve(rel);
                String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (!text.contains(from)) {
                    System.out.println("❌ " + label + " — fixture drifted: `" + from + "` not in " + rel);
                    return 1;
                }
                Files.writeString(f, text.replace(from, to), StandardCharsets.UTF_8);
            }
            ProcessBuilder pb = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
                    "-jar", jar, "schema");
            pb.environment().put("CLAUDE_PROJECT_DIR", tmp.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            boolean ok = from == null ? exit == 0 : exit == 2 && out.contains(needle);
            System.out.println((ok ? "✅ " : "❌ ") + label + (ok ? "" : " — got exit " + exit));
            if (!ok) System.out.println(out);
            return ok ? 0 : 1;
        } finally {
            try (var s = Files.walk(tmp)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            } catch (Exception ignored) { }
        }
    }

    record Result(int code, String out, String err) {}

    /** Spawns argv with the hook's environment and stdin; stdout is what the runtime parses, stderr goes to the report. */
    static Result run(List<String> argv, Path root, String stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("CLAUDE_PROJECT_DIR", root.toString());
        Path errFile = Files.createTempFile("hook-err-", ".txt");
        errFile.toFile().deleteOnExit();
        pb.redirectError(errFile.toFile());
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result(-1, "timed out", "");
        }
        return new Result(p.exitValue(), out, Files.readString(errFile, StandardCharsets.UTF_8));
    }
}
