///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar test-diff` tells what an upgrade made worse from what was already
// broken. `snapshot` records every test case of the JUnit XML reports under a project — Surefire,
// Failsafe and Gradle, at the root and in a module — as pass, fail (a `failure` or an `error`) or
// skip; `compare` sorts each case against the snapshot and exits 2 on a regression (passed, now
// failing), a missing test (passed, now absent — a deleted test is not a fix) and a new failure
// (absent from the baseline, failing now), and exits 0 on a failure that was already there, a fixed
// test and a skip. It exits 2, never 0, on a project with no report, on a baseline that is not a
// snapshot, on a report that is not XML and on one carrying a DOCTYPE.
//
// Why this test exists: the mode is Form 7c, and a mode that throws exits 0 through `main`'s catch —
// here that would read as "no regression". Each refusal below is a case where the wrong answer is a
// green. Decision 0176.
//
// The data under test is production: `doctor.platform.upgrade.test_reports` of this repository's
// extensions.json, copied into a throwaway source directory the jar reads through CLAUDE_PROJECT_DIR.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class TestDiffTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final Path SCHEMA = Paths.get(".claude/schemas/extensions.json");

    public static void main(String[] args) throws Exception {
        Path source = Files.createTempDirectory("archhook-testdiff-source");
        write(source.resolve(".claude/schemas/extensions.json"), Files.readString(SCHEMA, StandardCharsets.UTF_8));
        Path work = Files.createTempDirectory("archhook-testdiff-work");

        // A project with a report of each tool's shape: Surefire and Failsafe at the root, Gradle in a module.
        Path project = Files.createTempDirectory("archhook-testdiff-project");
        report(project, "target/surefire-reports/TEST-a.A.xml",
                ok("a.A", "passes"), ok("a.A", "goes-red"), ok("a.A", "goes-away"), failing("a.A", "was-red", "failure"));
        report(project, "target/failsafe-reports/TEST-a.AIT.xml", ok("a.AIT", "integrates"));
        report(project, "module/build/test-results/test/TEST-b.B.xml",
                ok("b.B", "gradle-passes"), skipped("b.B", "skipped"), failing("b.B", "errors", "error"));
        Path baseline = work.resolve("baseline.json");

        Run r = diff(source, "snapshot", project, "--out", baseline.toString());
        String json = Files.isRegularFile(baseline) ? Files.readString(baseline, StandardCharsets.UTF_8) : "";
        if (r.exit != 0 || !r.out.contains("8 test case(s) recorded") || !r.out.contains("2 failing, 1 skipped")) {
            fail("snapshot — expected exit 0, 8 cases, 2 failing and 1 skipped, got " + r.exit + "\n" + r.out);
        } else {
            expect(json, "\"a.A#passes\": \"pass\"", "snapshot: a passing case");
            expect(json, "\"a.A#was-red\": \"fail\"", "snapshot: a `failure` is a fail");
            expect(json, "\"b.B#errors\": \"fail\"", "snapshot: an `error` is a fail, from a Gradle report in a module");
            expect(json, "\"b.B#skipped\": \"skip\"", "snapshot: a skipped case");
            expect(json, "\"a.AIT#integrates\": \"pass\"", "snapshot: a Failsafe report");
        }

        // compare — nothing changed
        r = diff(source, "compare", project, "--baseline", baseline.toString());
        if (r.exit == 0 && r.out.contains("0 regression(s), 0 missing, 0 new failure(s), 2 failing before and still, 0 fixed")) {
            System.out.println("✅ compare: the same reports — exit 0, the two failures listed as pre-existing");
        } else {
            fail("compare on unchanged reports — expected exit 0 and two pre-existing failures, got " + r.exit + "\n" + r.out);
        }

        // compare — fixed and skipped never fail it
        report(project, "target/surefire-reports/TEST-a.A.xml",
                ok("a.A", "passes"), ok("a.A", "goes-red"), ok("a.A", "goes-away"), ok("a.A", "was-red"));
        report(project, "module/build/test-results/test/TEST-b.B.xml",
                ok("b.B", "gradle-passes"), skipped("b.B", "skipped"), skipped("b.B", "errors"));
        r = diff(source, "compare", project, "--baseline", baseline.toString());
        if (r.exit == 0 && r.out.contains("1 fixed") && r.out.contains("a.A#was-red")) {
            System.out.println("✅ compare: a failing test now passing is fixed — exit 0");
        } else {
            fail("compare with a fixed test — expected exit 0 and one fixed, got " + r.exit + "\n" + r.out);
        }

        // compare — each kind of worse alone: any one of them is enough to exit 2
        record Worse(String name, String needle, String[] cases) { }
        for (Worse w : List.of(
                new Worse("only a regression", "1 regression(s), 0 missing, 0 new failure(s)",
                        new String[] {ok("a.A", "passes"), failing("a.A", "goes-red", "failure"), ok("a.A", "goes-away"), failing("a.A", "was-red", "failure")}),
                new Worse("only a test that disappeared", "0 regression(s), 1 missing, 0 new failure(s)",
                        new String[] {ok("a.A", "passes"), ok("a.A", "goes-red"), failing("a.A", "was-red", "failure")}),
                new Worse("only a failure the baseline never saw", "0 regression(s), 0 missing, 1 new failure(s)",
                        new String[] {ok("a.A", "passes"), ok("a.A", "goes-red"), ok("a.A", "goes-away"), failing("a.A", "was-red", "failure"), failing("a.A", "brand-new", "failure")}))) {
            report(project, "target/surefire-reports/TEST-a.A.xml", w.cases());
            report(project, "module/build/test-results/test/TEST-b.B.xml", ok("b.B", "gradle-passes"), skipped("b.B", "skipped"), failing("b.B", "errors", "error"));
            r = diff(source, "compare", project, "--baseline", baseline.toString());
            if (r.exit == 2 && r.out.contains(w.needle())) System.out.println("✅ compare: " + w.name() + " — exit 2");
            else fail("compare with " + w.name() + " — expected exit 2 and \"" + w.needle() + "\", got " + r.exit + "\n" + r.out);
        }

        // compare — a regression, a missing test, a new failure, together
        report(project, "target/surefire-reports/TEST-a.A.xml",
                ok("a.A", "passes"), failing("a.A", "goes-red", "failure"), failing("a.A", "was-red", "error"),
                failing("a.A", "brand-new", "failure"));
        r = diff(source, "compare", project, "--baseline", baseline.toString());
        if (r.exit != 2) {
            fail("compare with a regression, a missing test and a new failure — expected exit 2, got " + r.exit + "\n" + r.out);
        } else {
            expect(r.out, "1 regression(s), 1 missing, 1 new failure(s), 2 failing before and still", "compare counts each kind");
            expect(r.out, "regression (passed before, failing now):\n    - a.A#goes-red", "compare names the regression");
            expect(r.out, "missing (passed before, no report now):\n    - a.A#goes-away", "compare names the test that disappeared");
            expect(r.out, "new failure (not in the baseline):\n    - a.A#brand-new", "compare names the new failure");
            if (r.out.contains("a.A#was-red\n") && !r.out.contains("pre-existing failure (failing before the upgrade):\n    - a.A#was-red")) {
                fail("compare listed a failure that was already there as something else");
            }
        }

        // refusals — each is a case where the wrong answer would be a green
        Path empty = Files.createTempDirectory("archhook-testdiff-empty");
        refused(diff(source, "snapshot", empty, "--out", work.resolve("none.json").toString()), "snapshot of a project with no report",
                "no JUnit XML report");
        if (Files.exists(work.resolve("none.json"))) fail("snapshot of a project with no report wrote a file");
        refused(diff(source, "compare", empty, "--baseline", baseline.toString()), "compare of a build that ran no tests", "no JUnit XML report");
        Path notSnapshot = work.resolve("not-a-snapshot.json");
        write(notSnapshot, "{\"something\": \"else\"}");
        refused(diff(source, "compare", project, "--baseline", notSnapshot.toString()), "a baseline that is not a snapshot", "is not a snapshot");
        refused(diff(source, "compare", project, "--baseline", work.resolve("absent.json").toString()), "a baseline that is not there", "is not a snapshot");
        Path broken = Files.createTempDirectory("archhook-testdiff-broken");
        write(broken.resolve("target/surefire-reports/TEST-x.X.xml"), "<testsuite><testcase classname=\"x.X\" name=\"half\">");
        refused(diff(source, "snapshot", broken, "--out", work.resolve("broken.json").toString()), "a report that is not XML", "test-diff failed");
        Path doctype = Files.createTempDirectory("archhook-testdiff-doctype");
        write(doctype.resolve("target/surefire-reports/TEST-x.X.xml"),
                "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><testsuite><testcase classname=\"x.X\" name=\"&e;\"/></testsuite>");
        refused(diff(source, "snapshot", doctype, "--out", work.resolve("doctype.json").toString()), "a report carrying a DOCTYPE", "test-diff failed");
        Run usage = diff(source, "compare", project);
        refused(usage, "compare with no --baseline", "usage:");

        // a source whose data does not say where the reports are
        Path noGlobs = Files.createTempDirectory("archhook-testdiff-noglobs");
        write(noGlobs.resolve(".claude/schemas/extensions.json"),
                Files.readString(SCHEMA, StandardCharsets.UTF_8).replace("\"test_reports\":", "\"test_reports_gone\":"));
        refused(diff(noGlobs, "snapshot", project, "--out", work.resolve("noglobs.json").toString()), "a source with no `test_reports`",
                "No `doctor.platform.upgrade.test_reports`");

        for (Path p : List.of(source, work, project, empty, broken, doctype, noGlobs)) deleteTree(p);
        System.out.println(failures == 0
                ? "\n✅ test-diff separates what the upgrade broke from what was already broken, and never reads a failure to run as a pass."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Run(int exit, String out) { }

    static String ok(String cls, String name) { return "<testcase classname=\"" + cls + "\" name=\"" + name + "\" time=\"0.01\"/>"; }

    static String failing(String cls, String name, String tag) {
        return "<testcase classname=\"" + cls + "\" name=\"" + name + "\"><" + tag + " message=\"boom\"/></testcase>";
    }

    static String skipped(String cls, String name) { return "<testcase classname=\"" + cls + "\" name=\"" + name + "\"><skipped/></testcase>"; }

    static void report(Path project, String file, String... cases) throws Exception {
        write(project.resolve(file), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"s\">" + String.join("", cases) + "</testsuite>\n");
    }

    static void refused(Run r, String name, String needle) {
        if (r.exit != 2) fail(name + " — expected exit 2, got " + r.exit + "\n" + r.out);
        else if (!r.out.contains(needle)) fail(name + " — the message lacks \"" + needle + "\"\n" + r.out);
        else System.out.println("✅ refused: " + name);
    }

    static Run diff(Path source, String verb, Path project, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(javaCmd(), "-jar", HOOK.toString(), "test-diff", verb, project.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(source.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", source.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(proc.waitFor(), out);
    }

    static void expect(String text, String needle, String name) {
        if (text.contains(needle)) System.out.println("✅ " + name);
        else fail(name + " — expected:\n" + needle + "\n--- got:\n" + text);
    }

    static String javaCmd() { return ProcessHandle.current().info().command().orElse("java"); }

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
