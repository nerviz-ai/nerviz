///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar build-report` reads a build the same way whatever the build tool.
// It sums the JUnit XML reports (Surefire, Failsafe, Gradle's `test` and `integrationTest`, at the
// root and in modules) and the JaCoCo XML reports, counts only the counters directly under
// `<report>`, prints at most `build_report.max_failures` failed cases with `failure_lines` lines
// each, ignores a report older than `--newer`, prints the log's error lines only when no test
// failed, and says "none" for a missing test or coverage report instead of failing. It exits 2,
// never 0, on a report it cannot parse.
//
// Why this test exists: the mode is Form 7c and replaces shell the executor used to write per run,
// Maven-only. A JaCoCo XML carries a DOCTYPE the JUnit factory refuses; a nested counter summed in
// reads a wrong coverage; a stale report read as this run's hides a module that did not compile;
// and a throw exits 0 through `main`'s catch. Each case below is one of those. Decision 0203.
//
// The data under test is production: `doctor.platform.upgrade.test_reports` and `build_report` of
// this repository's extensions.json, which the jar reads through CLAUDE_PROJECT_DIR (the cwd).

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

public class BuildReportTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final String DOCTYPE = "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">";

    public static void main(String[] args) throws Exception {
        // 1 — Maven, with the JaCoCo DOCTYPE and nested counters that must not be summed.
        Path p = tmp();
        junit(p, "target/surefire-reports/TEST-a.xml", cases("a.A", 3, 0, 0, 1));
        junit(p, "target/failsafe-reports/TEST-b.xml", cases("b.BIT", 2, 0, 0, 0));
        write(p.resolve("target/site/jacoco/jacoco.xml"), jacoco(20, 80, 10, 30, true));
        Run r = run(p);
        expect("1 Maven: exit 0", r.exit == 0, r);
        expect("1 Maven: totals over Surefire and Failsafe", r.out.contains("tests 6 · failures 0 · errors 0 · skipped 1"), r);
        expect("1 Maven: coverage from the report-level counters, DOCTYPE read", r.out.contains("coverage lines 80.0% · branches 75.0%"), r);

        // 2 — Gradle, two modules: both test tasks and the coverage XML of each.
        p = tmp();
        for (String m : List.of("m1", "m2")) {
            junit(p, m + "/build/test-results/test/TEST-a.xml", cases("a.A", 2, 0, 0, 0));
            junit(p, m + "/build/test-results/integrationTest/TEST-b.xml", cases("b.BIT", 1, 0, 0, 0));
        }
        write(p.resolve("m1/build/reports/jacoco/test/jacocoTestReport.xml"), jacoco(10, 90, 5, 15, true));
        write(p.resolve("m2/build/reports/jacoco/test/jacocoTestReport.xml"), jacoco(30, 70, 15, 5, true));
        r = run(p);
        expect("2 Gradle: totals summed over both modules", r.out.contains("reports 4 · coverage files 2") && r.out.contains("tests 6 · failures 0"), r);
        expect("2 Gradle: coverage summed over both modules", r.out.contains("coverage lines 80.0% · branches 50.0%"), r);

        // 3 — 25 failing cases (24 failures, 1 error): capped by max_failures and failure_lines.
        p = tmp();
        junit(p, "target/surefire-reports/TEST-f.xml", failing("f.F", 24, 1));
        r = run(p);
        expect("3 caps: counts", r.out.contains("failures 24 · errors 1"), r);
        expect("3 caps: max_failures", r.out.contains("failed (20 of 25):") && r.out.contains("… and 5 more"), r);
        expect("3 caps: message printed", r.out.contains("f.F#t0 — boom 0"), r);
        expect("3 caps: failure_lines", r.out.contains("detail-11") && !r.out.contains("detail-12"), r);

        // 4 — a report older than --newer is stale and not counted.
        p = tmp();
        long now = System.currentTimeMillis();
        Path old = junit(p, "target/surefire-reports/TEST-old.xml", cases("o.O", 5, 0, 0, 0));
        Path fresh = junit(p, "target/surefire-reports/TEST-new.xml", cases("n.N", 2, 0, 0, 0));
        Path marker = p.resolve("marker");
        write(marker, "");
        Files.setLastModifiedTime(old, FileTime.fromMillis(now - 100_000));
        Files.setLastModifiedTime(marker, FileTime.fromMillis(now - 50_000));
        Files.setLastModifiedTime(fresh, FileTime.fromMillis(now));
        r = run(p, "--newer", marker.toString());
        expect("4 stale: only the fresh report counts", r.out.contains("reports 1 ·") && r.out.contains("stale 1") && r.out.contains("tests 2 ·"), r);

        // 5 — no test report: the log says why, filtered.
        p = tmp();
        StringBuilder log = new StringBuilder("[ERROR] x\n* What went wrong:\nFoo.java:3: error: y\n");
        for (int i = 0; i < 200; i++) log.append("noise ").append(i).append('\n');
        Path logFile = p.resolve("build.log");
        write(logFile, log.toString());
        r = run(p, "--log", logFile.toString());
        expect("5 log: no test report", r.out.contains("tests none — no test report"), r);
        expect("5 log: the three error lines", r.out.contains("log (3 lines") && r.out.contains("  [ERROR] x")
                && r.out.contains("  * What went wrong:") && r.out.contains("  Foo.java:3: error: y"), r);
        expect("5 log: no noise", !r.out.contains("noise"), r);

        // 6 — a failed test is the explanation: no log section.
        p = tmp();
        junit(p, "target/surefire-reports/TEST-f.xml", failing("f.F", 1, 0));
        logFile = p.resolve("build.log");
        write(logFile, "[ERROR] boom\n");
        r = run(p, "--log", logFile.toString());
        expect("6 failed test: failed section", r.out.contains("failed (1 of 1):"), r);
        expect("6 failed test: no log section", !r.out.contains("log (") && !r.out.contains("[ERROR] boom"), r);

        // 7 — no coverage file: a line, exit 0.
        p = tmp();
        junit(p, "target/surefire-reports/TEST-a.xml", cases("a.A", 1, 0, 0, 0));
        r = run(p);
        expect("7 no coverage: exit 0 and the line", r.exit == 0 && r.out.contains("coverage none — no JaCoCo XML report"), r);

        // 8 — a malformed report: exit 2, the file named.
        p = tmp();
        write(p.resolve("target/surefire-reports/TEST-bad.xml"), "<testsuite><testcase");
        r = run(p);
        expect("8 malformed XML: exit 2, file named", r.exit == 2 && r.err.contains("TEST-bad.xml"), r);

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `build-report` is NOT reading a build as"
                    + " `build_report` and `doctor.platform.upgrade.test_reports` declare.");
            System.exit(1);
        }
        System.out.println("✅ build-report: both build tools' reports summed, coverage from the report-level counters,"
                + " caps and staleness held, the log read only when no test failed, a bad report exits 2.");
    }

    record Run(int exit, String out, String err) { }

    static Run run(Path project, String... extra) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"),
                "-jar", HOOK.toString(), "build-report", project.toString()));
        cmd.addAll(Arrays.asList(extra));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("CLAUDE_PROJECT_DIR", Paths.get("").toAbsolutePath().toString());
        Path errFile = Files.createTempFile("build-report-err", ".txt");
        pb.redirectError(errFile.toFile());
        Process pr = pb.start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = pr.waitFor();
        return new Run(exit, out, Files.readString(errFile, StandardCharsets.UTF_8));
    }

    static void expect(String label, boolean ok, Run r) {
        if (ok) { System.out.println("✅ " + label); return; }
        failures++;
        System.out.println("❌ " + label + " — exit " + r.exit + "\n" + r.out + "\n" + r.err);
    }

    static Path tmp() throws IOException { return Files.createTempDirectory("build-report-project"); }

    static void write(Path f, String s) throws IOException {
        Files.createDirectories(f.getParent());
        Files.writeString(f, s, StandardCharsets.UTF_8);
    }

    static Path junit(Path project, String rel, String cases) throws IOException {
        Path f = project.resolve(rel);
        write(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"s\">\n" + cases + "</testsuite>\n");
        return f;
    }

    /** {@code pass} passing cases, then {@code skip} skipped ones (failures and errors unused here). */
    static String cases(String cls, int pass, int unusedF, int unusedE, int skip) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pass; i++) sb.append("<testcase classname=\"").append(cls).append("\" name=\"p").append(i).append("\"/>\n");
        for (int i = 0; i < skip; i++) sb.append("<testcase classname=\"").append(cls).append("\" name=\"s").append(i).append("\"><skipped/></testcase>\n");
        return sb.toString();
    }

    /** {@code failures} cases with a {@code failure} child, then {@code errors} with an {@code error}; 20 detail lines each. */
    static String failing(String cls, int failures, int errors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < failures + errors; i++) {
            String tag = i < failures ? "failure" : "error";
            sb.append("<testcase classname=\"").append(cls).append("\" name=\"t").append(i).append("\"><")
                    .append(tag).append(" message=\"boom ").append(i).append("\">\n");
            for (int l = 0; l < 20; l++) sb.append("detail-").append(l).append('\n');
            sb.append("</").append(tag).append("></testcase>\n");
        }
        return sb.toString();
    }

    /** A JaCoCo report: the report-level LINE and BRANCH counters, and (nested) a package that must not count. */
    static String jacoco(long lineMissed, long lineCovered, long brMissed, long brCovered, boolean nested) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" + DOCTYPE + "\n"
                + "<report name=\"x\">\n"
                + (nested ? "<package name=\"p\"><counter type=\"LINE\" missed=\"7\" covered=\"3\"/>"
                        + "<counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/></package>\n" : "")
                + "<counter type=\"INSTRUCTION\" missed=\"1\" covered=\"1\"/>\n"
                + "<counter type=\"BRANCH\" missed=\"" + brMissed + "\" covered=\"" + brCovered + "\"/>\n"
                + "<counter type=\"LINE\" missed=\"" + lineMissed + "\" covered=\"" + lineCovered + "\"/>\n"
                + "</report>\n";
    }
}
