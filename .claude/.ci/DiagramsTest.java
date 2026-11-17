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

    static final String SCRIPT = ".claude/skills/project-diagrams/scripts/Diagrams.java";
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
        projectCases();
        legibilityCases();
        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — a committed diagram can drift from its data in silence.");
            System.exit(1);
        }
        System.out.println("✅ Committed diagrams match the data they cite: green on the tree, red by name on a renamed cited skill,"
                + " a skill added to a class, a changed .svg byte, a node with no evidence, a step heading in the retired"
                + " grammar, a .source-sha256 mismatch and every break of the step contract; and, over a fixture project (--source project): a renamed package,"
                + " a Mermaid block that is not an allowed type, a compose service removed, a long type name wrapped, one red case per legibility finding and the doctor line.");
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
                            if (k.getFileName().toString().equals("project-diagrams")) copyTree(k.resolve("references"), kd.resolve("references"));
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

    static Result run(Path root, String mode) throws Exception { return run(root, mode, new String[0]); }

    static Result run(Path root, String mode, String... more) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), repo.resolve(SCRIPT).toString(), mode));
        cmd.addAll(Arrays.asList(more));
        cmd.addAll(List.of("--root", root.toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    static void expect(String label, Path root, String mode, int wantExit, String wantText) throws Exception {
        expect(label, root, mode, wantExit, wantText, new String[0]);
    }

    static void expect(String label, Path root, String mode, int wantExit, String wantText, String... more) throws Exception {
        Result r = run(root, mode, more);
        boolean ok = r.exit() == wantExit && r.out().contains(wantText);
        if (ok) {
            System.out.println("✅ " + label + " (exit " + r.exit() + (wantExit == 2 ? ", names '" + wantText + "'" : "") + ")");
            return;
        }
        failures++;
        System.out.println("❌ " + label + " — wanted exit " + wantExit + " naming '" + wantText + "', got exit " + r.exit());
        System.out.println(r.out());
    }

    // ── --source project (0211): a fixture project written here, never the repository's own files ──

    static Path fixture() throws IOException {
        Path t = Files.createTempDirectory("diagrams-project");
        Files.createDirectories(t.resolve(".claude/schemas"));
        Files.copy(repo.resolve(".claude/schemas/extensions.json"), t.resolve(".claude/schemas/extensions.json"));
        copyTree(repo.resolve(".claude/skills/project-diagrams/references"), t.resolve(".claude/skills/project-diagrams/references"));
        write(t.resolve(".claude/blueprints/b/b.yaml"), """
                id: b
                packages:
                  base: "{{groupId}}.{{artifactName}}"
                  map:
                    domain.model: "domain.model"
                    application.usecase: "application.usecase"
                    application.port: "application.port"
                    infrastructure.rest: "infrastructure.rest"
                    infrastructure.persistence: "infrastructure.persistence"
                """);
        String b = "src/main/java/com/acme/app/";
        write(t.resolve(b + "domain/model/Order.java"), "package com.acme.app.domain.model;\npublic class Order {}\n");
        write(t.resolve(b + "application/port/OrderRepository.java"), "package com.acme.app.application.port;\npublic interface OrderRepository {}\n");
        write(t.resolve(b + "application/usecase/order/PlaceOrderUseCase.java"), "package com.acme.app.application.usecase.order;\nclass PlaceOrderUseCase { Order o; OrderRepository r; }\n");
        write(t.resolve(b + "infrastructure/rest/OrderController.java"), "package com.acme.app.infrastructure.rest;\nclass OrderController { PlaceOrderUseCase u; }\n");
        write(t.resolve(b + "infrastructure/persistence/OrderJpaAdapter.java"), "package com.acme.app.infrastructure.persistence;\nclass OrderJpaAdapter implements OrderRepository {}\n");
        write(t.resolve("src/main/resources/application.yml"), "spring:\n  application:\n    name: acme\n");
        write(t.resolve("docker-compose.yml"), """
                services:
                  app:
                    build: .
                    ports:
                      - "8080:8080"
                    environment:
                      DB_URL: jdbc:postgresql://postgres:5432/acme
                  postgres:
                    image: postgres:16-alpine
                    ports:
                      - "5432:5432"
                """);
        write(t.resolve("docs/use-cases/UC-001-place-order/00-use-case.md"), """
                # UC-001 · Place order

                | Field | Value |
                |---|---|
                | Trigger | placing an order, over HTTP |
                | Side effects | **1** — `INSERT` into `orders` |
                | External calls | none |

                ```mermaid
                sequenceDiagram
                    autonumber
                    actor caller
                    participant adapter as inbound adapter
                    participant PlaceOrderUseCase
                    caller->>adapter: request
                    adapter->>PlaceOrderUseCase: PlaceOrderCommand
                    PlaceOrderUseCase-->>adapter: confirmation
                ```

                ```mermaid
                flowchart TD
                    trigger((placing an order)) --> ok{"order valid?"}
                    ok -->|no| bad(["order refused"])
                    ok -->|yes| done(["order placed"])
                ```
                """);
        write(t.resolve("docs/diagrams/architecture/view.json"), """
                { "kind": "architecture", "title": "acme", "facts": ["type:Order", "type:OrderRepository", "type:PlaceOrderUseCase", "type:OrderController", "type:OrderJpaAdapter"] }
                """);
        return t;
    }

    static void projectCases() throws Exception {
        Path t = fixture();
        String[] src = { "--source", "project" };
        Result r = run(t, "render", src);
        if (r.exit() != 0) { failures++; System.out.println("❌ the project fixture does not render: " + r.out()); deleteTree(t); return; }
        expect("project: a fresh render of the four kinds is green", t, "check", 0, "equal a fresh render", src);
        Result ov = run(t, "render", "--source", "project", "UC-001-place-order");
        if (ov.exit() != 0) { failures++; System.out.println("❌ the flow view does not render: " + ov.out()); }
        expect("project: the flow view is green too", t, "check", 0, "equal a fresh render", src);

        // a 70-character type name must be wrapped, not drawn on one line past its box
        String longName = "PlaceVeryLongNamedOrderWithManyWordsAndMoreWordsToCrossAnyBoxWidth";
        Path uc = t.resolve("src/main/java/com/acme/app/application/usecase/order/PlaceOrderUseCase.java");
        Files.move(uc, uc.resolveSibling(longName + "UseCase.java"));
        write(t.resolve("docs/diagrams/architecture/view.json"), """
                { "kind": "architecture", "title": "acme", "facts": ["type:*"] }
                """);
        Result lr = run(t, "render", "--source", "project", "architecture");
        String svg = Files.isRegularFile(t.resolve("docs/diagrams/architecture/architecture.svg")) ? Files.readString(t.resolve("docs/diagrams/architecture/architecture.svg")) : "";
        boolean wrapped = lr.exit() == 0 && !svg.contains(">" + longName + "UseCase<") && svg.contains("UseCase<");
        report("project: a long type name is wrapped inside its box", wrapped, lr.out());
        Files.move(uc.resolveSibling(longName + "UseCase.java"), uc);

        // (a) a package renamed: the type a view cites by id no longer exists
        write(t.resolve("docs/diagrams/architecture/view.json"), """
                { "kind": "architecture", "title": "acme", "facts": ["type:Order", "type:OrderController"] }
                """);
        run(t, "render", "--source", "project", "architecture");
        Path rest = t.resolve("src/main/java/com/acme/app/infrastructure/rest"), web = t.resolve("src/main/java/com/acme/app/infrastructure/web");
        Files.move(rest, web);
        expect("project: (a) a renamed package", t, "check", 2, "fact 'type:OrderController' not found", src);
        Files.move(web, rest);

        // (b) a spec whose flow block is no allowed Mermaid type, named by file and line
        Path spec = t.resolve("docs/use-cases/UC-001-place-order/00-use-case.md");
        String sgood = Files.readString(spec);
        write(spec, sgood.replace("flowchart TD", "graph LR"));
        expect("project: (b) a Mermaid block that is not an allowed type", t, "check", 2, "00-use-case.md:", src);
        expect("project: (b) … and says which", t, "check", 2, "opens with 'graph LR'", src);
        write(spec, sgood);

        // (c) a compose service removed: the committed blueprint view is stale
        Path comp = t.resolve("docker-compose.yml");
        String cgood = Files.readString(comp);
        Result br = run(t, "render", "--source", "project", "blueprint");
        if (br.exit() != 0) { failures++; System.out.println("❌ the blueprint view does not render: " + br.out()); }
        write(comp, cgood.replace("  postgres:\n    image: postgres:16-alpine\n    ports:\n      - \"5432:5432\"\n", ""));
        expect("project: (c) a compose service removed", t, "check", 2, "differs from a fresh render", src);
        write(comp, cgood);

        // the doctor line: an input changed after the render warns, and `doctor gate` is the same either way
        Files.createDirectories(t.resolve(".claude/hooks"));
        Files.copy(repo.resolve(".claude/hooks/ArchHook.java"), t.resolve(".claude/hooks/ArchHook.java"));
        run(t, "render", "--source", "project", "architecture", "blueprint", "map", "UC-001-place-order");
        Result d1 = doctor(t, "doctor");
        Result g1 = doctor(t, "doctor", "gate");
        write(t.resolve("src/main/java/com/acme/app/domain/model/Order.java"), "package com.acme.app.domain.model;\npublic class Order { int x; }\n");
        Result d2 = doctor(t, "doctor");
        Result g2 = doctor(t, "doctor", "gate");
        report("doctor: the Diagrams line is green with the inputs unchanged", d1.out().contains("Diagrams") && d1.out().contains("unchanged"), d1.out());
        report("doctor: an input changed after render makes `Diagrams` warn, naming it and /project-diagrams",
                d2.out().contains("Diagrams") && d2.out().contains("Order.java (changed)") && d2.out().contains("/project-diagrams"), d2.out());
        report("doctor: `doctor gate` is not moved by it (same exit, `Diagrams` never a failing line)", g1.exit() == g2.exit() && !g2.out().contains("❌ Diagrams"), "gate exit " + g1.exit() + " → " + g2.exit() + "\n" + g2.out());
        deleteTree(t);
    }

    static Result doctor(Path project, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), project.resolve(".claude/hooks/ArchHook.java").toString()));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("CLAUDE_PROJECT_DIR", project.toString());
        pb.directory(project.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    static void report(String label, boolean ok, String detail) {
        if (ok) { System.out.println("✅ " + label); return; }
        failures++;
        System.out.println("❌ " + label + "\n" + detail);
    }

    // ── the legibility detector: one red case per finding, green on a clean SVG and on a badge ──

    static String svgOf(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"200\" viewBox=\"0 0 400 200\" font-family=\"Inter, sans-serif\">\n"
                + "<rect width=\"400\" height=\"200\" fill=\"#FBFAF7\"/>\n" + body + "</svg>\n";
    }

    static void legibilityCases() throws Exception {
        Path d = Files.createTempDirectory("diagrams-legible");
        String box = "<g id=\"a\">\n<rect x=\"20\" y=\"20\" width=\"160\" height=\"40\" rx=\"8\" fill=\"#E4F1DE\" stroke=\"#4C8C3A\"/>\n<text x=\"30\" y=\"45\" font-size=\"13\" fill=\"#1F2933\">Inside its box</text>\n</g>\n";
        write(d.resolve("clean.svg"), svgOf(box));
        expectLegible("legibility: a clean SVG is green", d, "clean.svg", 0, "has no label over");
        write(d.resolve("tt.svg"), svgOf(box + "<g id=\"b\">\n<text x=\"34\" y=\"47\" font-size=\"13\" fill=\"#1F2933\">Second label here</text>\n</g>\n"));
        expectLegible("legibility: text over text", d, "tt.svg", 2, "TEXT×TEXT");
        write(d.resolve("ts.svg"), svgOf(box.replace("Inside its box", "A label far wider than the box that holds it")));
        expectLegible("legibility: text past its box", d, "ts.svg", 2, "TEXT×SHAPE");
        write(d.resolve("tl.svg"), svgOf(box + "<g id=\"edge-x\">\n<line x1=\"60\" y1=\"10\" x2=\"60\" y2=\"90\" stroke=\"#52606D\" stroke-width=\"1.5\"/>\n</g>\n"));
        expectLegible("legibility: text crossed by an edge", d, "tl.svg", 2, "TEXT×LINE");
        write(d.resolve("ov.svg"), svgOf("<g id=\"c\">\n<text x=\"330\" y=\"100\" font-size=\"13\" fill=\"#1F2933\">A label running past the right edge</text>\n</g>\n"));
        expectLegible("legibility: a label past the viewBox", d, "ov.svg", 2, "OUT OF VIEWBOX");
        String two = "<g id=\"edge-a\">\n<line x1=\"40\" y1=\"120\" x2=\"200\" y2=\"120\" stroke=\"#52606D\" stroke-width=\"1.5\"/>\n</g>\n";
        write(d.resolve("ll.svg"), svgOf(two + "<g id=\"edge-b\">\n<line x1=\"100\" y1=\"120\" x2=\"300\" y2=\"120\" stroke=\"#52606D\" stroke-width=\"1.5\"/>\n</g>\n"));
        expectLegible("legibility: two edges on one line", d, "ll.svg", 2, "LINE×LINE");
        write(d.resolve("lv.svg"), svgOf("<g id=\"edge-a\">\n<line x1=\"60\" y1=\"20\" x2=\"60\" y2=\"150\" stroke=\"#52606D\" stroke-width=\"1.5\"/>\n</g>\n<g id=\"edge-b\">\n<line x1=\"60\" y1=\"100\" x2=\"60\" y2=\"180\" stroke=\"#52606D\" stroke-width=\"1.5\"/>\n</g>\n"));
        expectLegible("legibility: two vertical edges on one line", d, "lv.svg", 2, "LINE×LINE");
        write(d.resolve("lf.svg"), svgOf(two.replace("<g id=\"edge-a\">", "<g id=\"edge-a\" data-fan=\"port-1\">") + "<g id=\"edge-b\" data-fan=\"port-1\">\n<line x1=\"100\" y1=\"120\" x2=\"300\" y2=\"120\" stroke=\"#52606D\" stroke-width=\"1.5\"/>\n</g>\n"));
        expectLegible("legibility: edges leaving one declared source port may share a segment", d, "lf.svg", 0, "has no label over");
        write(d.resolve("lo.svg"), svgOf(two + "<g id=\"edge-b\">\n<line x1=\"100\" y1=\"124\" x2=\"300\" y2=\"124\" stroke=\"#52606D\" stroke-width=\"1.5\"/>\n</g>\n"));
        expectLegible("legibility: edges 4 px apart are two channels", d, "lo.svg", 0, "has no label over");
        write(d.resolve("bd.svg"), svgOf(box + "<g id=\"badge-1\" data-badge=\"code-resolved\">\n<circle cx=\"180\" cy=\"20\" r=\"8\" fill=\"#4C8C3A\"/>\n<text x=\"175\" y=\"24\" font-size=\"11\" fill=\"#FFFFFF\">✓</text>\n</g>\n"));
        expectLegible("legibility: a badge on a box corner is the declared exception", d, "bd.svg", 0, "has no label over");
        deleteTree(d);
    }

    static void expectLegible(String label, Path dir, String file, int wantExit, String wantText) throws Exception {
        Result r = run(repo, "legible", dir.resolve(file).toString());
        boolean ok = r.exit() == wantExit && r.out().contains(wantText);
        if (ok) { System.out.println("✅ " + label + " (exit " + r.exit() + ")"); return; }
        failures++;
        System.out.println("❌ " + label + " — wanted exit " + wantExit + " naming '" + wantText + "', got exit " + r.exit() + "\n" + r.out());
    }
}
