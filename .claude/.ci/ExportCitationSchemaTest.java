///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.java schema` BLOCKS an exported file (a kept skill file, an agent, a rule,
// the active blueprint) that cites a path inside a skill `export.skills.exclude` keeps out of a
// generated project (`project-bootstrap/references/...`), and lets prose, `residue_exempt` files and
// decision records through.
//
// Why this test exists: such a citation reached generated projects three times (0200, issue #194)
// and the export's residue scan only warns, in the target. Each case runs the real jar over a temp
// copy of the real tree, so the test proves what production reads. Decision 0204.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ExportCitationSchemaTest {

    static final String SKILL = ".claude/skills/sonarqube-setup/SKILL.md";
    static final String AGENT = ".claude/agents/archunit-installer.md";
    static final String RULE = ".claude/rules/naming.md";
    static final String EXEMPT = ".claude/skills/arch-adopt/SKILL.md";
    static final String RECORD = ".claude/decisions/0001-citation-probe.md";
    static final String DEAD = "\nsee `project-bootstrap/references/build-maven.md`\n";

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path repo = Paths.get("").toAbsolutePath();

        int failures = 0;
        failures += expect(repo, hook, "tree as shipped accepted", null, null, 0, null);
        failures += expect(repo, hook, "dead citation in a skill blocked", SKILL, DEAD, 2,
                "sonarqube-setup/SKILL.md:");
        failures += expect(repo, hook, "…and it names the excluded skill", SKILL, DEAD, 2, "project-bootstrap");
        failures += expect(repo, hook, "dead citation in an agent blocked", AGENT,
                "\n`@.claude/skills/init-project/SKILL.md`\n", 2, "archunit-installer.md:");
        failures += expect(repo, hook, "skill named in prose accepted", RULE,
                "\nsee `project-bootstrap` step 4\n", 0, null);
        failures += expect(repo, hook, "residue_exempt file accepted", EXEMPT,
                "\n`$SRC/.claude/skills/project-bootstrap/SKILL.md`\n", 0, null);
        failures += expect(repo, hook, "dead citation in a decision record ignored", RECORD,
                "# probe\n" + DEAD, 0, null);

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `schema` is NOT enforcing"
                    + " the export citations decision 0204 declares.");
            System.exit(1);
        }
        System.out.println("✅ Export citations enforced: a path inside an excluded skill is blocked"
                + " with exit 2 naming file and line; prose, exempt files and decision records accepted.");
    }

    /**
     * Copies the tree to a temp project, appends `addition` to `file` (creating it when
     * absent), runs `schema` over the whole project and checks exit code and, when given,
     * that the output contains `mustSay`.
     */
    static int expect(Path repo, Path hook, String label, String file, String addition,
                      int wanted, String mustSay) throws Exception {
        Path tmp = Files.createTempDirectory("archhook-citation-ci");
        copyTree(repo.resolve(".claude"), tmp.resolve(".claude"));
        for (String f : List.of("CLAUDE.md", "README.md", "LICENSE", "NOTICE")) {
            if (Files.isRegularFile(repo.resolve(f))) Files.copy(repo.resolve(f), tmp.resolve(f));
        }
        if (file != null) {
            Path target = tmp.resolve(file);
            Files.createDirectories(target.getParent());
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
