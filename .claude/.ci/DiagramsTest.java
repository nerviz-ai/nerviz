///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the committed diagrams under docs/diagrams/ cannot drift from the data they
// cite. It copies what `Diagrams.java` reads into a temp directory and runs
// `Diagrams.java check --root <tmp>` — the repository is never mutated.
//
// Green on the tree, then red BY NAME when: (a) a skill a view cites is renamed, (b) a skill
// joins a class and nobody re-rendered, (c) one byte of a committed .svg changes, (d) a node
// carries neither `fact` nor `from`, (e) a step heading goes back to the retired `### Step`
// grammar, (f) a dist/ .source-sha256 is not its SVG's hash, and the step contract (0192): a step
// with no **Runs:** line, a Runs ref that is no fact, a gate word outside the vocabulary, a
// **Hands over:** command that is no skill, a view drawing another skill's step, an orchestrator view with no `skill`, an ArchHook mode that vanished from the dispatch.
//
// Why this test exists: the diagrams are derived, and "derived" only holds while a stale or
// hand-edited one fails CI. Decision 0190. Needs only `java`; no git, no network, no Node.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.*;

public class DiagramsTest {

    static final String SCRIPT = ".claude/skills/nerviz-diagrams/scripts/Diagrams.java";
    static Path repo;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        repo = Paths.get("").toAbsolutePath();

        // ── green on the tree ──
        Path t = copy();
        expect("the committed tree is green", t, "check", 0, "equal a fresh render");

        // A classes view and a dist file, so cases (a), (b) and (f) have something to bite on.
        write(t.resolve("docs/diagrams/classes/view.json"), """
                {
                  "kind": "classes",
                  "title": "Classes",
                  "groups": [
                    { "id": "design", "label": { "fact": "class:design" }, "nodes": [{ "from": "class:design.members" }] },
                    { "id": "cited", "label": { "caption": "Cited" }, "nodes": [{ "fact": "skill:sonar-lessons" }] }
                  ]
                }
                """);
        run(t, "render");
        expect("a render of a fresh view is green", t, "check", 0, "equal a fresh render");
        Path dist = t.resolve("docs/diagrams/layers/dist");
        Files.createDirectories(dist);
        Files.write(dist.resolve("layers.png"), new byte[] { 1, 2, 3 });
        String svgSha = sha(Files.readString(t.resolve("docs/diagrams/layers/layers.svg")));
        write(dist.resolve("layers.png.source-sha256"), svgSha + "\n");
        expect("a dist file whose hash is its SVG's is green", t, "check", 0, "dist/ hashes match");

        // (f) the sidecar disagrees
        write(dist.resolve("layers.png.source-sha256"), "0".repeat(64) + "\n");
        expect("(f) a .source-sha256 that is not the SVG's hash", t, "check", 2, "layers.png — .source-sha256 is not the SHA-256 of layers.svg");
        write(dist.resolve("layers.png.source-sha256"), svgSha + "\n");

        // (c) one byte of a committed svg
        Path svg = t.resolve("docs/diagrams/layers/layers.svg");
        String good = Files.readString(svg);
        write(svg, good.replaceFirst("Interface", "Interfacf"));
        expect("(c) one byte of a committed .svg changed", t, "check", 2, "docs/diagrams/layers/layers.svg");
        write(svg, good);

        // (d) a node with no evidence
        Path view = t.resolve("docs/diagrams/layers/view.json");
        String vgood = Files.readString(view);
        write(view, vgood.replace("{ \"fact\": \"layer:mods\" }", "{ \"label\": \"Mods, from memory\" }"));
        expect("(d) a node with neither fact nor from", t, "validate", 2, "$.groups[0].nodes[0]: matches none of the allowed shapes");
        write(view, vgood);

        // (b) a skill joins a class: the classes view renders its members, so the render is stale
        Path ext = t.resolve(".claude/schemas/extensions.json");
        String egood = Files.readString(ext);
        write(ext, egood.replace("\"use-case-design\", \"domain-modeling\"", "\"use-case-design\", \"domain-modeling\", \"nerviz-diagrams\""));
        expect("(b) a skill added to a class without a re-render", t, "check", 2, "docs/diagrams/classes/classes.svg — differs from a fresh render");
        write(ext, egood);

        // (a) a skill the view cites is renamed (directory and every entry of extensions.json)
        Files.move(t.resolve(".claude/skills/sonar-lessons"), t.resolve(".claude/skills/sonar-lessons-v2"));
        write(ext, egood.replace("\"sonar-lessons\"", "\"sonar-lessons-v2\""));
        expect("(a) a cited skill renamed", t, "validate", 2, "fact 'skill:sonar-lessons' not found");
        Files.move(t.resolve(".claude/skills/sonar-lessons-v2"), t.resolve(".claude/skills/sonar-lessons"));
        write(ext, egood);
        expect("everything restored is green again", t, "check", 0, "equal a fresh render");

        // (e) a step heading goes back to the retired grammar
        Path nf = t.resolve(".claude/skills/new-feature/SKILL.md");
        String ngood = Files.readString(nf);
        write(nf, ngood.replace("### 3b · Security", "### Step 3b: Security (depends on 1, 2, 3, 3g, conditional)"));
        expect("(e) a step heading in the retired grammar", t, "check", 2, "is the retired grammar");
        write(nf, ngood);

        // (f) a step without its Runs line
        Path ni = t.resolve(".claude/skills/new-feature-implement/SKILL.md");
        String igood = Files.readString(ni);
        write(ni, igood.replaceFirst("(### 2 · Implement\\n\\n)\\*\\*Runs:\\*\\*[^\\n]*\\n\\n", "$1"));
        expect("(f) a step with no **Runs:** line", t, "check", 2, "step 2 has no **Runs:** line");
        write(ni, igood);

        // (g) a Runs ref that is no fact
        Path ip = t.resolve(".claude/skills/init-project/SKILL.md");
        String pgood = Files.readString(ip);
        write(ip, pgood.replace("agent:project-initializer>", "agent:project-initialiser>"));
        expect("(g) a Runs ref that resolves to no fact", t, "check", 2, "runs 'agent:project-initialiser', which is no fact");
        write(ip, pgood);

        // (h) a gate word outside the vocabulary
        Path np = t.resolve(".claude/skills/platform-upgrade/SKILL.md");
        String ugood = Files.readString(np);
        write(np, ugood.replaceFirst("\\*\\*Gate:\\*\\* stop", "**Gate:** halt"));
        expect("(h) a gate word outside the vocabulary", t, "check", 2, "**Runs:** does not match");
        write(np, ugood);

        // (i) a Hands over command that is no skill or native command
        write(ni, igood.replace("`/git-publish`", "`/git-publsh`"));
        expect("(i) a Hands over command that is no skill", t, "check", 2, "'/git-publsh'");
        expect("(i) … and says why", t, "check", 2, "no skill or native command");
        write(ni, igood);

        // (j) a view that draws another skill's step
        Path iv = t.resolve("docs/diagrams/init-project/view.json");
        String ivgood = Files.readString(iv);
        write(iv, ivgood.replace("{ \"from\": \"step:init-project:*\" }", "{ \"fact\": \"step:platform-upgrade:1\" }"));
        expect("(j) an orchestrator view drawing a foreign step", t, "validate", 2, "draws the steps of init-project only");

        // (k) an orchestrator view without skill
        write(iv, ivgood.replace("  \"skill\": \"init-project\",\n", ""));
        expect("(k) an orchestrator view without skill", t, "validate", 2, "requires skill");
        write(iv, ivgood);

        // (l) an ArchHook mode vanishes from the dispatch
        Path hk = t.resolve(".claude/hooks/ArchHook.java");
        String hgood = Files.readString(hk);
        write(hk, hgood.replace("case \"test-diff\" ->", "case \"test-dif\" ->"));
        expect("(l) a cmd mode renamed in the dispatch", t, "check", 2, "runs 'cmd:test-diff', which is no fact");
        write(hk, hgood);
        // (m) a branch gate word with no **Stops when:** line
        write(ip, pgood.replaceFirst("\\n\\*\\*Stops when:\\*\\*[^\\n]*\\n", "\n"));
        expect("(m) a branch gate word with no Stops when line", t, "check", 2, "has 1 branch gate word(s) (stop) and 0 **Stops when:** line(s)");
        write(ip, pgood);

        // (n) a condition past the limit the schema states
        write(np, ugood.replace("ref predates this skill?", "does the ref predate this very skill now?"));
        expect("(n) a condition over 34 characters", t, "check", 2, "is a question of at most 34 characters");
        write(np, ugood);

        // (o) Reads names a step written after the reader
        write(nf, ngood.replace("**Reads:** 3, 3b ·", "**Reads:** 3, 3d ·"));
        expect("(o) Reads names a later step", t, "check", 2, "reads '3d', which is written after it");
        write(nf, ngood);

        // (p) Skips when names no later step
        write(nf, ngood.replace("**To:** approval", "**To:** nowhere"));
        expect("(p) To names no later step", t, "check", 2, "skips to 'nowhere', which is no later step");
        write(nf, ngood);

        // (q) (r) (s) an orchestrator view with emphasis, with steps, of a kind that no longer exists
        write(iv, ivgood.replace("\"edges\"", "\"emphasis\": [\"step:init-project:1\"],\n  \"edges\""));
        expect("(q) an orchestrator view with emphasis", t, "validate", 2, "takes no emphasis");
        write(iv, ivgood.replace("\"edges\"", "\"steps\": [\"step:init-project:1\"],\n  \"edges\""));
        expect("(r) an orchestrator view with steps", t, "validate", 2, "follows the written order");
        write(iv, ivgood.replace("\"orchestrator\"", "\"pipeline\""));
        expect("(s) the retired kind pipeline", t, "validate", 2, "'pipeline' is not one of");
        write(iv, ivgood);

        // (t) a field line below the body's first paragraph
        write(ni, igood.replaceFirst("(\\*\\*Stops when:\\*\\* a group fails\\?[^\\n]*\\n\\n)((?:\\*\\*Then runs:[^\\n]*\\n\\n))", "$2\nA paragraph of the body.\n\n$1"));
        expect("(t) a Stops when line outside the field block", t, "check", 2, "must be in the field block");
        write(ni, igood);

        // (u) a note on a step with one phase
        write(ip, pgood.replace("### 4 · Relay the report\n\n**Runs:** self\n", "### 4 · Relay the report\n\n**Runs:** self · **Note:** relay\n"));
        expect("(u) a note on a one-phase step", t, "check", 2, "a note names a phase");
        write(ip, pgood);

        expect("everything restored is green once more", t, "check", 0, "equal a fresh render");

        deleteTree(t);
        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — a committed diagram can drift from its data in silence.");
            System.exit(1);
        }
        System.out.println("✅ Committed diagrams match the data they cite: green on the tree, red by name on a renamed cited skill,"
                + " a skill added to a class, a changed .svg byte, a node with no evidence, a step heading in the retired"
                + " grammar, a .source-sha256 mismatch and every break of the step contract.");
    }

    /** Copies what Diagrams.java reads: skill dirs as their SKILL.md, the rest of .claude/ as empty entries. */
    static Path copy() throws IOException {
        Path t = Files.createTempDirectory("diagrams-test");
        Path cl = repo.resolve(".claude");
        try (Stream<Path> top = Files.list(cl)) {
            for (Path p : top.collect(Collectors.toList())) {
                String n = p.getFileName().toString();
                Path dst = t.resolve(".claude").resolve(n);
                if (n.equals(".ci") || n.equals("settings.local.json")) continue;
                if (n.equals("schemas") || n.equals("settings.json")) { copyTree(p, dst); continue; }

                if (!Files.isDirectory(p)) continue;
                Files.createDirectories(dst);
                try (Stream<Path> kids = Files.list(p)) {
                    for (Path k : kids.collect(Collectors.toList())) {
                        Path kd = dst.resolve(k.getFileName().toString());
                        if (n.equals("skills") && Files.isDirectory(k)) {
                            Files.createDirectories(kd);
                            if (Files.isRegularFile(k.resolve("SKILL.md"))) Files.copy(k.resolve("SKILL.md"), kd.resolve("SKILL.md"));
                            if (k.getFileName().toString().equals("nerviz-diagrams")) copyTree(k.resolve("references"), kd.resolve("references"));
                        } else if (n.equals("agents") || (n.equals("hooks") && k.getFileName().toString().equals("ArchHook.java"))) Files.copy(k, kd);
                        else if (Files.isDirectory(k)) Files.createDirectories(kd);
                        else Files.createFile(kd);
                    }
                }
            }
        }
        if (Files.isRegularFile(repo.resolve(".mcp.json"))) Files.copy(repo.resolve(".mcp.json"), t.resolve(".mcp.json"));
        Path dg = repo.resolve("docs/diagrams");
        if (Files.isDirectory(dg)) copyTree(dg, t.resolve("docs/diagrams"));
        return t;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> w = Files.walk(from)) {
            for (Path p : w.collect(Collectors.toList())) {
                Path d = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(d);
                else { Files.createDirectories(d.getParent()); Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        try (Stream<Path> w = Files.walk(p)) {
            for (Path x : w.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(x);
        }
    }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    static String sha(String s) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    record Result(int exit, String out) {}

    static Result run(Path root, String mode) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
                repo.resolve(SCRIPT).toString(), mode, "--root", root.toString());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    static void expect(String label, Path root, String mode, int wantExit, String wantText) throws Exception {
        Result r = run(root, mode);
        boolean ok = r.exit() == wantExit && r.out().contains(wantText);
        if (ok) {
            System.out.println("✅ " + label + " (exit " + r.exit() + (wantExit == 2 ? ", names '" + wantText + "'" : "") + ")");
            return;
        }
        failures++;
        System.out.println("❌ " + label + " — wanted exit " + wantExit + " naming '" + wantText + "', got exit " + r.exit());
        System.out.println(r.out());
    }
}
