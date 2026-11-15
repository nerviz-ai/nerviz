///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves a generated project extends the boundary map its blueprint delivered through
// `.claude/boundary-extensions.json`, that the extension is enforced by `check` beside the delivered
// lines, and that it survives a rerun of `boundary-map` (decision 0209).
//
// What it holds:
//   - `config set boundary <module-path> <prefix>` writes the file, once per prefix (a second `set`
//     of the same prefix changes nothing), and `config list` shows the key;
//   - `check` exits 2 on a file importing an extended prefix, and 0 on the same file before the
//     extension exists and after `config unset boundary <module-path> <prefix>`;
//   - the delivered lines still block (a framework import in the domain) with an extension present;
//   - a rerun of `boundary-map` rewrites `.claude/forbidden-imports.txt` and leaves the extension
//     file untouched, and `check` still blocks the extension afterwards;
//   - `doctor` counts the extension rules on `Boundaries` and prints `Boundary map` and
//     `Boundary extensions` lines, the first one comparing the delivered file with the blueprint;
//   - the validator refuses, writing nothing: a prefix that is no package name, a module path with
//     `..`, and one under `src` but outside `src/main/java`;
//   - `config unset` of the last prefix deletes the file.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class BoundaryExtensionTest {

    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final Path REPO = Paths.get(".").toAbsolutePath().normalize();
    static final String PKG = "com.acme.shop";
    static final String DOMAIN = "src/main/java/com/acme/shop/domain";
    static final String EXT = ".claude/boundary-extensions.json";

    static int failures = 0;

    record Run(int exit, String out, String err) {}

    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("boundary-extension");
        Run w = run(REPO, "boundary-map", project.toString(), "--blueprint", "clean-architecture-single-module", "--package", PKG);
        if (!expect(w.exit == 0, "map written (exit " + w.exit + ", " + w.err.strip() + ")")) done();
        // The project's own copy of the data the modes read; a generated project carries it through `export`.
        Files.createDirectories(project.resolve(".claude/schemas"));
        Files.copy(REPO.resolve(".claude/schemas/extensions.json"), project.resolve(".claude/schemas/extensions.json"));
        // ... and the active blueprint, which `doctor` re-derives the delivered lines from.
        Path bp = project.resolve(".claude/blueprints/clean-architecture-single-module/clean-architecture-single-module.yaml");
        Files.createDirectories(bp.getParent());
        Files.copy(REPO.resolve(".claude/blueprints/clean-architecture-single-module/clean-architecture-single-module.yaml"), bp);

        String body = "package com.acme.shop.domain.model;\nimport org.apache.pdfbox.pdmodel.PDDocument;\nclass Bad {}\n";
        String rel = DOMAIN + "/model/Bad.java";
        Files.createDirectories(project.resolve(rel).getParent());
        Files.writeString(project.resolve(rel), body, StandardCharsets.UTF_8);

        expect(run(project, "check", rel).exit == 0, "before any extension: the pdfbox import is not forbidden");

        Run set = run(project, "config", "set", "boundary", DOMAIN, "org.apache.pdfbox.");
        expect(set.exit == 0, "config set boundary writes (exit " + set.exit + ", " + set.err.strip() + ")");
        String first = read(project, EXT);
        expect(first.contains(DOMAIN) && first.contains("org.apache.pdfbox."), "the extension file names the module path and the prefix");
        run(project, "config", "set", "boundary", DOMAIN, "org.apache.pdfbox.");
        expect(read(project, EXT).equals(first), "a second `set` of the same prefix changes nothing");
        expect(run(project, "config", "list").out.contains(DOMAIN), "config list shows the key");

        Run chk = run(project, "check", rel);
        expect(chk.exit == 2, "check blocks the extended prefix (exit " + chk.exit + ")");

        // A delivered line still holds beside the extension.
        String spring = DOMAIN + "/model/Spring.java";
        Files.writeString(project.resolve(spring),
                "package com.acme.shop.domain.model;\nimport org.springframework.stereotype.Component;\nclass Spring {}\n", StandardCharsets.UTF_8);
        expect(run(project, "check", spring).exit == 2, "a delivered rule still blocks with an extension present");

        Run doc = run(project, "doctor");
        expect(doc.out.contains("from the project's boundary extensions") || doc.err.contains("from the project's boundary extensions"),
                "doctor Boundaries counts the extension rules");
        String both = doc.out + doc.err;
        expect(both.contains("Boundary map") && both.contains("Boundary extensions"),
                "doctor prints the Boundary map and Boundary extensions lines");
        expect(both.contains("equal what clean-architecture-single-module derives"),
                "doctor Boundary map: the delivered lines equal the derivation");

        // Regeneration drops nothing the project extended.
        Run again = run(REPO, "boundary-map", project.toString(), "--blueprint", "clean-architecture-single-module", "--package", PKG);
        expect(again.exit == 0, "boundary-map reruns (exit " + again.exit + ")");
        expect(read(project, EXT).equals(first), "a rerun of boundary-map leaves the extension file as it was");
        expect(run(project, "check", rel).exit == 2, "the extension still blocks after the rerun");

        // Validator refusals: nothing written, exit 2.
        String before = read(project, EXT);
        refuse(project, before, "a prefix that is no package name", "config", "set", "boundary", DOMAIN, "not a pkg");
        refuse(project, before, "a module path with `..`", "config", "set", "boundary", "../x", "org.a.");
        refuse(project, before, "a path under src outside src/main/java", "config", "set", "boundary", "src/test/x", "org.a.");

        // Taking the prefix out lifts the rule, and the file goes with its last key.
        Run un = run(project, "config", "unset", "boundary", DOMAIN, "org.apache.pdfbox.");
        expect(un.exit == 0, "config unset with a prefix (exit " + un.exit + ")");
        expect(!Files.exists(project.resolve(EXT)), "the file is deleted when no project rule is left");
        expect(run(project, "check", rel).exit == 0, "after unset the import passes again");

        // unset of the whole key, with two prefixes.
        run(project, "config", "set", "boundary", DOMAIN, "org.a.");
        run(project, "config", "set", "boundary", DOMAIN, "org.b.");
        run(project, "config", "unset", "boundary", DOMAIN, "org.a.");
        String left = read(project, EXT);
        expect(left.contains("org.b.") && !left.contains("org.a."), "unset with a prefix takes out only that prefix");
        run(project, "config", "unset", "boundary", DOMAIN);
        expect(!Files.exists(project.resolve(EXT)), "unset without a prefix removes the whole key");

        done();
    }

    static void done() {
        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — a project's boundary extensions are NOT enforced and kept as decision 0209 says.");
            System.exit(1);
        }
        System.out.println("✅ boundary extensions: set/unset, merged check, survive boundary-map, validator refusals.");
    }

    static String read(Path project, String rel) throws Exception {
        Path f = project.resolve(rel);
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
    }

    static void refuse(Path project, String before, String what, String... args) throws Exception {
        Run r = run(project, args);
        String after = read(project, EXT);
        expect(r.exit == 2 && after.equals(before), "refuses " + what + ", exit 2, nothing written (exit " + r.exit + ")");
    }

    static Run run(Path project, String... modeArgs) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(List.of(modeArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("CLAUDE_PROJECT_DIR", project.toString());
        Process p = pb.start();
        p.getOutputStream().close();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Run(-1, "", "timeout");
        }
        return new Run(p.exitValue(),
                new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    static boolean expect(boolean ok, String name) {
        if (ok) { System.out.println("  ✓ " + name); }
        else { System.out.println("  ✗ " + name); failures++; }
        return ok;
    }
}
