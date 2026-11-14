///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.java schema` BLOCKS an `export` destination that the territory of
// `export.writer_skill` (arch-adopt) does not cover, and a `writer_skill` no skill class lists.
//
// Why this test exists: `export` writes `.github/hooks/archhook.json` under an /arch-adopt phase
// while the skill's `write_allow` left it out, so the Stop sweep blocked every update that changed
// it (issue #212). Each case runs the real jar over a temp copy of the real tree, with one edit
// to `extensions.json`, so the test proves what production reads. Decision 0208.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ExportTerritorySchemaTest {

    static final String DATA = ".claude/schemas/extensions.json";

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path repo = Paths.get("").toAbsolutePath();

        int failures = 0;
        failures += expect(repo, hook, "tree as shipped accepted", null, null, 0, null);
        failures += expect(repo, hook, "an overwrite destination outside the territory blocked",
                "\"overwrite\": [",
                "\"overwrite\": [ { \"from\": \".claude/skills/project-bootstrap/templates/copilot-hooks.json.example\", \"to\": \"docs/x.json\" },",
                2, "docs/x.json");
        failures += expect(repo, hook, "…and it names the writer skill",
                "\"overwrite\": [",
                "\"overwrite\": [ { \"from\": \".claude/skills/project-bootstrap/templates/copilot-hooks.json.example\", \"to\": \"docs/x.json\" },",
                2, "arch-adopt");
        failures += expect(repo, hook, "a writer_skill no class lists blocked",
                "\"writer_skill\": \"arch-adopt\"", "\"writer_skill\": \"no-such-skill\"", 2, "no-such-skill");
        failures += expect(repo, hook, "the Copilot hook file dropped from the territory blocked",
                "\".claude/**\", \".github/hooks/archhook.json\", \".mcp.json\"", "\".claude/**\", \".mcp.json\"",
                2, ".github/hooks/archhook.json");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `schema` is NOT enforcing"
                    + " the export territory decision 0208 declares.");
            System.exit(1);
        }
        System.out.println("✅ Export territory enforced: a destination outside the writer skill's"
                + " write_allow, or an unknown writer_skill, is blocked with exit 2 naming it.");
    }

    /**
     * Copies the tree to a temp project, replaces `from` with `to` in extensions.json (when
     * given), runs `schema` and checks exit code and, when given, that the output contains `mustSay`.
     */
    static int expect(Path repo, Path hook, String label, String from, String to,
                      int wanted, String mustSay) throws Exception {
        Path tmp = Files.createTempDirectory("archhook-territory-ci");
        copyTree(repo.resolve(".claude"), tmp.resolve(".claude"));
        for (String f : List.of("CLAUDE.md", "README.md", "LICENSE", "NOTICE")) {
            if (Files.isRegularFile(repo.resolve(f))) Files.copy(repo.resolve(f), tmp.resolve(f));
        }
        if (from != null) {
            Path target = tmp.resolve(DATA);
            String text = Files.readString(target, StandardCharsets.UTF_8);
            if (!text.contains(from)) {
                System.out.println("❌ " + label + " — the text to edit is not in " + DATA + ": " + from);
                return 1;
            }
            Files.writeString(target, text.replace(from, to), StandardCharsets.UTF_8);
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
