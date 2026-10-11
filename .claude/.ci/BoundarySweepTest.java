///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar doctor`'s `Boundary violations` line sweeps every source file
// against `.claude/forbidden-imports.txt` with the edit-time `check` hook's own match — and
// that the two agree. Cases: a clean tree is green with the swept count; a module file
// importing a forbidden prefix, plain or `static`, is named with the prefix and the module; a
// test file under the module is swept, as the hook does; a file outside every module path is
// not; a single-module package-path prefix matches; in a git repository an untracked file is
// swept and an ignored one (`target/`) is not; without git the walk skips `walk_exclude_dirs`;
// with no map the line is green with nothing to sweep; and `check <path>` blocks exactly the
// file the sweep names.
//
// Why this test exists: the line is gated — the generated project's `boundaries` job runs
// `doctor gate` — and it is the only thing in CI that refuses a forbidden import before the
// ArchUnit test is installed, which under an agent other than Claude Code may be never. A
// sweep that silently matched nothing would read as a clean project. Decision 0140.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class BoundarySweepTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final String MAP = "# boundary\ndomain|org.springframework.\ndomain|jakarta.persistence.\n"
            + "src/main/java/com/acme/shop/domain|org.springframework.\n";
    static final String CLEAN = "package x;\n\nimport java.util.List;\n\nclass Clean { }\n";

    public static void main(String[] args) throws Exception {
        Map<String, String> f = tree();
        expect(doctor(f, false), "Boundary violations . ✅ 2 file(s) swept against 3 rule(s)",
                "clean tree — green, with the files swept and the rules counted");

        f = tree();
        f.put("domain/src/main/java/x/Order.java",
                "package x;\n\nimport org.springframework.stereotype.Component;\n\nclass Order { }\n");
        f.put("domain/src/test/java/x/OrderTest.java",
                "package x;\n\nimport static jakarta.persistence.Persistence.createEntityManagerFactory;\n");
        f.put("adapters/rest/src/main/java/x/Api.java",
                "package x;\n\nimport org.springframework.web.bind.annotation.RestController;\n");
        String out = doctor(f, false);
        expect(out, "Boundary violations . ❌ 2 file(s) import what their module forbids",
                "two module files counted; the adapter outside every module path is not");
        expect(out, "domain/src/main/java/x/Order.java → org.springframework.  (forbidden in module 'domain')",
                "a forbidden import is named with its prefix and module");
        expect(out, "domain/src/test/java/x/OrderTest.java → jakarta.persistence.",
                "a static import in a test under the module is swept, as the hook does");
        if (out.contains("adapters/rest")) fail("a file outside every module path was reported");

        f = tree();
        f.put("src/main/java/com/acme/shop/domain/model/Sku.java",
                "package com.acme.shop.domain.model;\nimport org.springframework.util.Assert;\n");
        expect(doctor(f, false), "src/main/java/com/acme/shop/domain/model/Sku.java → org.springframework.",
                "a single-module package-path prefix matches");

        f = tree();
        f.put("target/generated-sources/x/Gen.java", "import org.springframework.Gen;\n");
        f.put("domain/src/main/java/x/Wip.java", "import jakarta.persistence.Entity;\n");
        f.put(".gitignore", "target/\n");
        out = doctor(f, true);
        expect(out, "domain/src/main/java/x/Wip.java → jakarta.persistence.",
                "git — an untracked file that would be committed is swept");
        if (out.contains("Gen.java")) fail("git — an ignored file under target/ was swept");
        else System.out.println("✅ git — an ignored file under target/ is not swept");

        f = tree();
        f.put("build/classes/x/Gen.java", "import org.springframework.Gen;\n");
        f.put("domain/build/generated/Gen.java", "import org.springframework.Gen;\n");
        out = doctor(f, false);
        expect(out, "Boundary violations . ✅", "no git — the walk skips build directories at any depth");

        f = tree();
        f.remove(".claude/forbidden-imports.txt");
        expect(doctor(f, false), "Boundary violations . ✅ nothing to sweep — no boundary rule",
                "no map — green; Boundaries is the red line for that cause");

        f = tree();
        f.put("domain/src/main/java/x/Order.java", "import org.springframework.stereotype.Component;\n");
        Path p = project(f, false);
        int code = exec(p, "check", "domain/src/main/java/x/Order.java");
        int clean = exec(p, "check", "domain/src/main/java/x/Clean.java");
        deleteTree(p);
        if (code == 2 && clean != 2) System.out.println("✅ check blocks the file the sweep names, and only it");
        else fail("check and the sweep disagree — check exit " + code + " on the violation, " + clean + " on the clean file");

        System.out.println(failures == 0 ? "\n✅ Every source file swept with check's own match."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Map<String, String> tree() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(".claude/forbidden-imports.txt", MAP);
        f.put("domain/src/main/java/x/Clean.java", CLEAN);
        f.put("adapters/rest/src/main/java/x/Ok.java", CLEAN);
        return f;
    }

    static void expect(String out, String needle, String name) {
        if (out.contains(needle)) {
            System.out.println("✅ " + name);
        } else {
            fail(name + " — expected \"" + needle + "\"");
            System.out.println(out);
        }
    }

    static Path project(Map<String, String> files, boolean git) throws Exception {
        Path p = Files.createTempDirectory("archhook-sweep-ci");
        Files.createDirectories(p.resolve(".claude/schemas"));
        Files.copy(Paths.get(".claude/schemas/extensions.json"), p.resolve(".claude/schemas/extensions.json"));
        for (Map.Entry<String, String> e : files.entrySet()) write(p.resolve(e.getKey()), e.getValue());
        if (git) {
            new ProcessBuilder("git", "init", "-q").directory(p.toFile()).start().waitFor();
            new ProcessBuilder("git", "add", "domain/src/main/java/x/Clean.java", ".gitignore")
                    .directory(p.toFile()).start().waitFor();
        }
        return p;
    }

    static String doctor(Map<String, String> files, boolean git) throws Exception {
        Path p = project(files, git);
        String out = capture(p, "doctor");
        deleteTree(p);
        return out;
    }

    static String capture(Path p, String... mode) throws Exception {
        Process proc = start(p, mode);
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();
        return out;
    }

    static int exec(Path p, String... mode) throws Exception {
        Process proc = start(p, mode);
        proc.getInputStream().readAllBytes();
        return proc.waitFor();
    }

    static Process start(Path p, String... mode) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(p.toFile());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        return proc;
    }

    static void fail(String m) { failures++; System.out.println("❌ " + m); }

    static void write(Path f, String s) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, s, StandardCharsets.UTF_8);
    }

    static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }
}
