///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves two things `ArchHook.jar export` tells the person who runs it. The
// `Overwrites` note appears only for an `overwrite` entry whose destination exists and changes
// — not on a fresh tree, not on an unchanged file. And the provenance stamp's `commit` is the
// one given with `--commit`, else the source's own HEAD only when the source root is the top of
// its own work tree, else "unknown" — never the HEAD of a repository the source happens to sit
// inside; a malformed `--commit` exits 2.
//
// Why this test exists: issue #123. `arch-adopt` exports from an extracted tarball, so the
// stamp said "unknown" — and would have stamped a parent repository's HEAD had the tarball sat
// inside one — while `report-issue` compares that field with the source's HEAD whenever the ref
// is not a tag. And the note named `settings.json` on every update, sending the person to an
// empty `git diff`. Decision 0141.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class ExportProvenanceTest {

    static int failures = 0;
    static final Path REPO = Paths.get("").toAbsolutePath();
    static final Path HOOK = REPO.resolve(".claude/hooks/ArchHook.jar");
    static final String NOTE = "Overwrites ........ .claude/settings.json";

    public static void main(String[] args) throws Exception {
        Path dest = Files.createTempDirectory("archhook-export-prov");

        Result r = export(REPO, dest);
        check(r.code == 0 && !r.out.contains("Overwrites"), "a fresh tree — no Overwrites note", r);

        r = export(REPO, dest);
        check(r.code == 0 && !r.out.contains("Overwrites"), "an unchanged file — no Overwrites note", r);

        Files.writeString(dest.resolve(".claude/settings.json"), "{\"edited\": true}\n");
        r = export(REPO, dest);
        check(r.code == 0 && r.out.contains(NOTE), "an edited settings.json — the note names it", r);

        r = export(REPO, dest, "--commit", "abcdef1234567");
        check(r.code == 0 && stampCommit(dest).equals("abcdef1234567"), "--commit — stamped as given", r);

        String head = git(REPO, "rev-parse", "HEAD");
        r = export(REPO, dest);
        check(r.code == 0 && stampCommit(dest).equals(head),
                "no flag, source at the top of its work tree — its own HEAD", r);

        Path outer = Files.createTempDirectory("archhook-export-outer");
        git(outer, "init", "-q");
        git(outer, "-c", "user.email=ci@example.com", "-c", "user.name=ci",
                "commit", "-q", "--allow-empty", "-m", "outer");
        Path inner = outer.resolve("arch-src");
        copyTree(REPO.resolve(".claude"), inner.resolve(".claude"));
        Path dest2 = Files.createTempDirectory("archhook-export-prov2");
        r = export(inner, dest2);
        check(r.code == 0 && stampCommit(dest2).equals("unknown"),
                "no flag, source nested in another work tree — \"unknown\", not the outer HEAD", r);

        r = export(REPO, dest, "--commit", "v0.25.0");
        check(r.code == 2 && r.out.contains("--commit takes a commit sha"), "a malformed --commit — exit 2", r);

        for (Path p : List.of(dest, dest2, outer)) deleteTree(p);
        System.out.println(failures == 0 ? "\n✅ Overwrites only when a file changes; the stamp's commit is never a guess."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Result(int code, String out) { }

    static void check(boolean ok, String name, Result r) {
        if (ok) {
            System.out.println("✅ " + name);
        } else {
            failures++;
            System.out.println("❌ " + name + " (exit " + r.code + ")");
            System.out.println(r.out);
        }
    }

    static Result export(Path source, Path dest, String... extra) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar",
                source.resolve(".claude/hooks/ArchHook.jar").toString(),
                "export", dest.toString(), "--blueprint", "hexagonal"));
        cmd.addAll(List.of(extra));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(source.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", source.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    static String stampCommit(Path dest) throws Exception {
        String s = Files.readString(dest.resolve(".claude/.arch-provenance.json"));
        Matcher m = Pattern.compile("\"commit\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : "<none>";
    }

    static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return out;
    }

    static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path x : s.toList()) {
                Path t = to.resolve(from.relativize(x).toString());
                if (Files.isDirectory(x)) Files.createDirectories(t);
                else Files.copy(x, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Git writes its objects read-only, and Windows refuses to delete a read-only file. */
    static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                x.toFile().setWritable(true);
                Files.delete(x);
            }
        }
    }
}
