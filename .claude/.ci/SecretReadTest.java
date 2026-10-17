///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `guard read` and `guard bash` refuse a read of a path `guard.secret_paths` of
// extensions.json lists, on the payloads the Copilot runtimes send, admit the near misses, hand the
// reason on stdout in the flat shape, and that `schema` holds `permissions.deny` and the Copilot
// hook file to that list and to the `copilot_hooks` block.
//
// Why this test exists: under the Copilot CLI `wc -l .probe/e1.env` ran with no denial — the
// project's `permissions.deny` is a Claude Code file the Copilot CLI does not read, and only the
// model's choice of `wc` kept the content from the provider (lessons-learned-022 § 8, E1). A read
// of a secret cannot be taken back. Three ways this rots in silence: a payload field renamed by
// the runtime (`path`, `filePath`, `toolArgs` as a string), so the guard judges nothing; a near
// miss refused (`.env.example`, `test -f .env`, `printf … >> .env`), which gets the hook deleted
// the first week; and the list drifting between `permissions.deny` and the hooks, so one runtime
// reads what the other refuses (decision 0173).
//
// Runs the jar, not the source: that is what the hooks launch. `guard read` runs with no
// CLAUDE_PROJECT_DIR, from the project root, as `.github/hooks/archhook.json` launches it.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class SecretReadTest {

    static Path hook;
    static Path root;
    static int failures = 0;

    static final String SETTINGS = ".claude/settings.json";
    static final String TEMPLATE = ".claude/skills/project-bootstrap/templates/settings.json.example";
    static final String COPILOT = ".claude/skills/project-bootstrap/templates/copilot-hooks.json.example";

    public static void main(String[] args) throws Exception {
        hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        root = Files.createTempDirectory("archhook-secret-ci").toRealPath();
        Files.createDirectories(root.resolve(".claude/schemas"));
        Files.copy(Paths.get(".claude/schemas/extensions.json"), root.resolve(".claude/schemas/extensions.json"));
        Files.createDirectories(root.resolve(".probe"));
        Files.writeString(root.resolve(".probe/e1.env"), "TOKEN=probe\n");
        String abs = json(root.toString()) + "/";

        // ── guard read: the three runtimes' payloads ─────────────────────────
        // Copilot CLI, `.github/hooks` camelCase: `view`, `toolArgs` an object (captured) or a string.
        Run view = read("{\"sessionId\":\"s\",\"toolName\":\"view\",\"toolArgs\":{\"path\":\"" + abs + ".env\"}}");
        check(view.code == 2, "guard read · Copilot `view`, absolute .env — exit 2");
        check(view.out.startsWith("{\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"")
                && view.out.contains(".env holds secrets"),
                "guard read · camelCase envelope has no event name — the flat reason still reaches stdout");
        check(read("{\"toolName\":\"view\",\"toolArgs\":\"{\\\"path\\\":\\\".probe/e1.env\\\"}\"}").code == 2,
                "guard read · `toolArgs` as a JSON string, relative path — exit 2");
        // VS Code, `.github/hooks`: snake_case envelope, `read_file`, `filePath`.
        check(read("{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"read_file\",\"tool_input\":{\"filePath\":\""
                + abs + "src/main/resources/application-prod.yml\",\"startLine\":1,\"endLine\":20}}").code == 2,
                "guard read · VS Code `read_file` + `filePath`, application-prod.yml — exit 2");
        // Claude Code's own field, for the same mode called by hand.
        check(read("{\"tool_name\":\"Read\",\"tool_input\":{\"file_path\":\"config/secrets/db.txt\"}}").code == 2,
                "guard read · `file_path`, a file under secrets/ — exit 2");
        check(read("{\"toolName\":\"view\",\"toolArgs\":{\"path\":\"" + abs + "certs/server.pem\"}}").code == 2,
                "guard read · a .pem — exit 2");

        // Near misses, admitted.
        for (String[] c : new String[][] {
                {"{\"toolName\":\"view\",\"toolArgs\":{\"path\":\"" + abs + ".env.example\"}}", ".env.example"},
                {"{\"toolName\":\"view\",\"toolArgs\":{\"path\":\"" + abs + "README.md\"}}", "README.md"},
                {"{\"toolName\":\"view\",\"toolArgs\":{\"path\":\"" + abs + ".probe\"}}", "a directory holding a .env"},
                {"{\"toolName\":\"view\",\"toolArgs\":{\"path\":\"/elsewhere/x.env\"}}", "a .env outside the repository"},
                {"{\"toolName\":\"view\",\"toolArgs\":{}}", "no path field"},
                {"not json", "an unreadable payload"}}) {
            Run r = read(c[0]);
            check(r.code == 0 && r.out.isEmpty(), "guard read · " + c[1] + " — exit 0, stdout empty");
        }

        // A symlink to a secret reads as the secret.
        try {
            Files.createSymbolicLink(root.resolve("notes.txt"), root.resolve(".probe/e1.env"));
            check(read("{\"toolName\":\"view\",\"toolArgs\":{\"path\":\"notes.txt\"}}").code == 2,
                    "guard read · a symlink to .probe/e1.env — exit 2");
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("⏭  guard read · symlink case skipped: this runner cannot create one");
        }

        // ── guard bash: what reads, and what only names the file ─────────────
        for (String c : List.of("cat .env", "wc -l .probe/e1.env", "grep -qs '^DB_PASSWORD=' .env",
                "sed -n 1p .env", "head -c 20 config/secrets/key", "python3 x.py < .env",
                "git status && base64 certs/k.p12", "source .env", ". ./.env", "/bin/cat " + root.toString().replace('\\', '/') + "/.env")) {
            Run r = bash(c);
            check(r.code == 2 && r.out.startsWith("{\"permissionDecision\":\"deny\""),
                    "guard bash · `" + c + "` — exit 2, reason on stdout");
        }
        for (String c : List.of("test -f .env", "ls .env", "docker compose --env-file .env up -d",
                "cat .env.example", "printf 'DB_PASSWORD=%s\\n' x >> .env", "cp .env.example .env",
                "sed -i '' s/a/b/ .env", "cat $HOME/.env", "cat *.env", "echo 'cat .env'", "./mvnw verify")) {
            Run r = bash(c);
            check(r.code == 0 && r.out.isEmpty(), "guard bash · `" + c + "` — exit 0");
        }

        // ── The list is data ─────────────────────────────────────────────────
        Path ext = root.resolve(".claude/schemas/extensions.json");
        String real = Files.readString(ext, StandardCharsets.UTF_8);
        Files.writeString(ext, real.replace("\"./**/*.env\",", ""), StandardCharsets.UTF_8);
        check(read("{\"toolName\":\"view\",\"toolArgs\":{\"path\":\".env\"}}").code == 0
                && bash("cat .env").code == 0, "guard read and bash · *.env off the list — admitted");
        Files.writeString(ext, real, StandardCharsets.UTF_8);

        // ── schema: permissions.deny and the Copilot hook file ───────────────
        expect(t -> { }, 0, null, "schema · shipped files accepted");
        expect(t -> edit(t, SETTINGS, "\"Read(./**/*.pem)\",\n", ""), 2,
                SETTINGS + " — `permissions.deny` lacks `Read(./**/*.pem)`", "schema · a deny line dropped in .claude/settings.json");
        expect(t -> edit(t, TEMPLATE, "\"Read(./**/*.pem)\",", "\"Read(./**/*.pem)\", \"Read(./**/*.key)\","), 2,
                "holds `Read(./**/*.key)`", "schema · a deny line the list lacks, in the project template");
        expect(t -> edit(t, COPILOT, "\"timeoutSec\": 30", "\"timeoutSec\": 30, \"exec\": \"java\""), 2,
                "`exec` is not an allowed entry field", "schema · Copilot hook in exec form");
        expect(t -> edit(t, COPILOT, "\"preToolUse\"", "\"PreToolUse\""), 2,
                "not a Copilot hook event", "schema · a PascalCase event in the Copilot file");
        expect(t -> edit(t, COPILOT, "\"matcher\": \"view|read_file\",", ""), 2,
                "lacks `matcher`", "schema · a Copilot entry with no matcher");
        expect(t -> edit(t, COPILOT, "\"bash\": \"java -jar .claude/hooks/ArchHook.jar guard read\"",
                        "\"bash\": \"java -jar \\\"${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar\\\" guard read\""), 2,
                "differ", "schema · a CLAUDE_PROJECT_DIR launch in one shell only");

        deleteTree(root);
        System.out.println(failures == 0 ? "\n✅ SecretReadTest: all checks passed"
                : "\n❌ SecretReadTest: " + failures + " check(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Run(int code, String out, String err) {}

    static Run read(String stdin) throws Exception { return run(false, stdin, "guard", "read"); }

    static Run bash(String command) throws Exception {
        return run(true, "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\""
                + json(command) + "\"}}", "guard", "bash");
    }

    /** Runs the jar at the root; `withDir` false leaves CLAUDE_PROJECT_DIR out, as Copilot's file does. */
    static Run run(boolean withDir, String stdin, String... mode) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", hook.toString()));
        cmd.addAll(List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        if (withDir) pb.environment().put("CLAUDE_PROJECT_DIR", root.toString());
        Path err = Files.createTempFile("stderr", ".txt");
        pb.redirectError(err.toFile());
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int code = p.waitFor();
        String e = Files.readString(err, StandardCharsets.UTF_8);
        Files.delete(err);
        return new Run(code, out, e);
    }

    interface Mutation { void apply(Path tree) throws IOException; }

    /** Copies this repository's `.claude/` into a throwaway tree, mutates it, runs the `schema` sweep. */
    static void expect(Mutation mutation, int wanted, String needle, String label) throws Exception {
        Path tmp = Files.createTempDirectory("archhook-secret-schema-ci");
        copyTree(Paths.get(".claude").toAbsolutePath(), tmp.resolve(".claude"));
        mutation.apply(tmp);
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "schema");
        pb.environment().put("CLAUDE_PROJECT_DIR", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        deleteTree(tmp);
        boolean ok = exit == wanted && (needle == null || out.contains(needle));
        check(ok, label + " — exit " + wanted + (needle == null ? "" : ", naming \"" + needle + "\""));
        if (!ok) System.out.println("   got exit " + exit + ":\n" + out);
    }

    static void edit(Path tree, String rel, String from, String to) throws IOException {
        Path f = tree.resolve(rel);
        String s = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!s.contains(from)) throw new IOException("fixture drifted: `" + from + "` not in " + rel);
        Files.writeString(f, s.replace(from, to), StandardCharsets.UTF_8);
    }

    static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static void check(boolean ok, String label) {
        System.out.println((ok ? "✅ " : "❌ ") + label);
        if (!ok) failures++;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
