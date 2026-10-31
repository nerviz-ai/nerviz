///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.java schema` BLOCKS a glob value of a shell flag written without
// quotes (`--include=*.java`) in the files `shell_globs.scope` names, and lets the quoted
// forms, a non-glob value and a decision record through.
//
// Why this test exists: the Bash tool runs the user's shell. zsh aborts a command with
// `no matches found: --include=*.java` before it starts; bash, which CI uses, passes the word
// through — so the defect never shows in CI and a prose fix (0037) came back in 13 files.
// Each case runs the real jar over a temp copy of the real tree, so the test proves what
// production reads. Decision 0196.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ShellGlobSchemaTest {

    static final String RULE = ".claude/rules/naming.md";
    static final String AGENT = ".claude/agents/java-spring-boot-developer.md";
    static final String RECORD = ".claude/decisions/0001-shell-glob-probe.md";

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path repo = Paths.get("").toAbsolutePath();

        int failures = 0;
        failures += expect(repo, hook, "tree as shipped accepted", null, null, 0, null);
        failures += expect(repo, hook, "unquoted --include in a rule blocked", RULE,
                "\ngrep -rn X --include=*.java src/main\n", 2, RULE + ":");
        failures += expect(repo, hook, "single-quoted --include accepted", RULE,
                "\ngrep -rn X --include='*.java' src/main\n", 0, null);
        failures += expect(repo, hook, "double-quoted --include accepted", RULE,
                "\ngrep -rn X --include=\"*.java\" src/main\n", 0, null);
        failures += expect(repo, hook, "unquoted --exclude-dir in an agent blocked", AGENT,
                "\ngrep -rn X --exclude-dir=* src/main\n", 2, AGENT + ":");
        failures += expect(repo, hook, "unquoted glob in a decision record ignored", RECORD,
                "# probe\n\ngrep -rn X --include=*.java src/main\n", 0, null);
        failures += expect(repo, hook, "--include with no glob character accepted", RULE,
                "\ngrep -rn X --include=src src/main\n", 0, null);

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `schema` is NOT enforcing"
                    + " the quoted shell globs extensions.json `shell_globs` declares.");
            System.exit(1);
        }
        System.out.println("✅ Shell globs enforced: unquoted --include/--exclude-dir blocked"
                + " with exit 2 naming file and line; quoted, non-glob and decision-record"
                + " forms accepted.");
    }

    /**
     * Copies the tree to a temp project, appends `addition` to `file` (creating it when
     * absent), runs `schema` over the whole project and checks exit code and, when given,
     * that the output contains `mustSay`.
     */
    static int expect(Path repo, Path hook, String label, String file, String addition,
                      int wanted, String mustSay) throws Exception {
        Path tmp = Files.createTempDirectory("archhook-shellglob-ci");
        copyTree(repo.resolve(".claude"), tmp.resolve(".claude"));
        for (String f : List.of("CLAUDE.md", "README.md", "LICENSE", "NOTICE")) {
            if (Files.isRegularFile(repo.resolve(f))) Files.copy(repo.resolve(f), tmp.resolve(f));
        }
        if (file != null) {
            Path target = tmp.resolve(file);
            String old = Files.isRegularFile(target)
                    ? Files.readString(target, StandardCharsets.UTF_8) : "";
            Files.writeString(target, old + addition, StandardCharsets.UTF_8);
        }

        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", tmp.resolve(".claude/hooks/ArchHook.jar").toString(), "schema");
        pb.environment().put("CLAUDE_PROJECT_DIR", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();                 // no stdin: sweeps the whole project
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();

        boolean said = mustSay == null || out.contains(mustSay);
        if (exit == wanted && said) {
            System.out.println("✅ " + label + " (exit " + exit + ")");
            return 0;
        }
        System.out.println("❌ " + label + " — expected exit " + wanted
                + (mustSay != null ? " naming `" + mustSay + "`" : "") + ", got " + exit);
        System.out.println(out);
        return 1;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String rel = from.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("audit-usage") || rel.contains("node_modules")) continue;
                Path dest = to.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
