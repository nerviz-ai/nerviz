///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the committed diagrams under docs/diagrams/ cannot drift from the data they
// cite. It copies what `Diagrams.java` reads into a temp directory and runs
// `Diagrams.java check --root <tmp>` — the repository is never mutated.
//
// Green on the tree, then red BY NAME when: (a) a skill a view cites is renamed, (b) a skill
// joins a class and nobody re-rendered, (c) one byte of a committed .svg changes, (d) a node
// carries neither `fact` nor `from`, (e) a `### Step` heading of new-feature/SKILL.md loses its
// `(depends on …)`, (f) a dist/ .source-sha256 is not its SVG's hash.
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
                    { "id": "cited", "label": { "caption": "Cited" }, "nodes": [{ "fact": "skill:git-publish" }] }
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
        Files.move(t.resolve(".claude/skills/git-publish"), t.resolve(".claude/skills/git-publish-v2"));
        write(ext, egood.replace("\"git-publish\"", "\"git-publish-v2\""));
        expect("(a) a cited skill renamed", t, "validate", 2, "fact 'skill:git-publish' not found");
        Files.move(t.resolve(".claude/skills/git-publish-v2"), t.resolve(".claude/skills/git-publish"));
        write(ext, egood);
        expect("everything restored is green again", t, "check", 0, "equal a fresh render");

        // (e) a step heading loses its dependency clause
        Path nf = t.resolve(".claude/skills/new-feature/SKILL.md");
        String ngood = Files.readString(nf);
        write(nf, ngood.replace("### Step 2: Domain (depends on 1)", "### Step 2: Domain"));
        expect("(e) a step heading without (depends on …)", t, "check", 2, "step heading does not match");
        write(nf, ngood);

        deleteTree(t);
        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — a committed diagram can drift from its data in silence.");
            System.exit(1);
        }
        System.out.println("✅ Committed diagrams match the data they cite: green on the tree, red by name on a renamed cited skill,"
                + " a skill added to a class, a changed .svg byte, a node with no evidence, a step heading without its"
                + " dependencies and a .source-sha256 mismatch.");
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
                        } else if (n.equals("agents")) Files.copy(k, kd);
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
