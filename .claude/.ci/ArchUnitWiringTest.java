///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the ArchUnit + coverage-gate wiring `archunit-installer` writes is correct on
// both build tools, and that the gate FAILS when coverage is missing.
//
// What it holds, on a fresh Initializr project per build tool (web), given the JaCoCo
// instrumentation and report `project-bootstrap` writes (no gate):
//   1. the fragment of `test-architect/templates/` merges as its header places it
//      (`archunit-pom.xml.example`, `archunit-build.gradle.example`) with the ArchUnit version
//      resolved from repo1.maven.org's `maven-metadata.xml` `<release>`;
//   2. an `ArchitectureTest` using `com.tngtech.archunit` runs (its report exists) and a class
//      with two branches covered by `CalcTest` passes the build: `./mvnw -q verify` /
//      `./gradlew -q --console=plain build` green;
//   3. red on purpose: with `CalcTest` removed the same build fails, and the output names the
//      gate (Maven: "Coverage checks have not been met"; Gradle: `jacocoTestCoverageVerification`).
//      A fragment whose `check` execution / `check` wiring is missing never goes red here.
//
// Network and minutes: runs in `templates.yml`, path-filtered. Decision 0204.

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

public class ArchUnitWiringTest {

    static final Path FRAGMENTS = Paths.get(".claude/skills/test-architect/templates");
    static final String RESOLVED = "<!-- resolved:.*?-->";
    static final String PKG = "com.exemplo.minhaapi";

    public static void main(String[] args) throws Exception {
        String archunit = release("com/tngtech/archunit/archunit-junit5");
        String jacocoMaven = latestStable("org/jacoco/jacoco-maven-plugin");
        String jacocoCore = latestStable("org/jacoco/org.jacoco.core");
        Path work = Files.createTempDirectory("archunit-wiring-test");

        Path maven = initializrProject(work, "maven-project", "maven");
        mergeMaven(maven.resolve("pom.xml"), archunit, jacocoMaven);
        fixtures(maven);
        List<String> mvn = List.of(maven.resolve("mvnw").toString(), "-B", "-q", "clean", "verify");
        green(maven, mvn, maven.resolve("target/surefire-reports"), "Maven");
        red(maven, mvn, "Coverage checks have not been met", "Maven");

        Path gradle = initializrProject(work, "gradle-project", "gradle");
        mergeGradle(gradle.resolve("build.gradle"), archunit, jacocoCore);
        fixtures(gradle);
        List<String> gr = List.of(gradle.resolve("gradlew").toString(), "-q", "--console=plain", "clean", "build");
        green(gradle, gr, gradle.resolve("build/test-results/test"), "Gradle");
        red(gradle, gr, "jacocoTestCoverageVerification", "Gradle");

        System.out.println("✅ archunit-junit5 " + archunit + ": the fragments wire ArchUnit and a JaCoCo gate on Maven and Gradle;"
                + " a covered class passes `verify`/`build`, and without its test the gate fails by name.");
    }

    // ---------------------------------------------------------------- merge

    static void mergeMaven(Path pom, String archunit, String jacoco) throws IOException {
        String fragment = read(FRAGMENTS.resolve("archunit-pom.xml.example"));
        String props = inner(fragment, "properties")
                .replaceFirst("(<archunit\\.version>)" + RESOLVED, "$1" + archunit);
        if (props.contains("resolved:")) fail("a version placeholder of the Maven fragment was not filled: " + props);
        String dm = whole(fragment, "dependencyManagement");
        String deps = inner(fragment.substring(fragment.indexOf("</dependencyManagement>")), "dependencies");
        String execution = whole(fragment, "execution");

        String xml = read(pom);
        // What project-bootstrap leaves: JaCoCo instrumenting and reporting, no gate.
        String plugin = "\t\t\t<plugin>\n\t\t\t\t<groupId>org.jacoco</groupId>\n\t\t\t\t<artifactId>jacoco-maven-plugin</artifactId>\n"
                + "\t\t\t\t<version>" + jacoco + "</version>\n"
                + "\t\t\t\t<configuration><excludes><exclude>**/*Application.class</exclude></excludes></configuration>\n"
                + "\t\t\t\t<executions>\n"
                + "\t\t\t\t\t<execution><id>prepare-agent</id><goals><goal>prepare-agent</goal></goals></execution>\n"
                + "\t\t\t\t\t<execution><id>report</id><phase>verify</phase><goals><goal>report</goal></goals></execution>\n"
                + "\t\t\t\t</executions>\n\t\t\t</plugin>\n";
        int build = xml.indexOf("<build>");
        int plugins = xml.indexOf("</plugins>", build);
        xml = xml.substring(0, plugins) + plugin + "\t\t" + xml.substring(plugins);

        // The fragment, as its header places it.
        xml = xml.replaceFirst("</properties>", Matcher.quoteReplacement(props + "</properties>"));
        int top = xml.indexOf("<dependencies>", xml.indexOf("</properties>"));
        int topEnd = xml.indexOf("</dependencies>", top);
        xml = xml.substring(0, topEnd) + deps + xml.substring(topEnd);
        xml = xml.substring(0, top) + dm + "\n\t" + xml.substring(top);
        int exec = xml.indexOf("</executions>", xml.indexOf("jacoco-maven-plugin"));
        xml = xml.substring(0, exec) + execution + "\n\t\t\t\t" + xml.substring(exec);
        write(pom, xml);
    }

    static void mergeGradle(Path build, String archunit, String jacoco) throws IOException {
        String fragment = read(FRAGMENTS.resolve("archunit-build.gradle.example"));
        String ext = block(fragment, "ext {")
                .replaceFirst("(archunitVersion = ')" + RESOLVED, "$1" + archunit);
        if (ext.contains("resolved:")) fail("a version placeholder of the Gradle fragment was not filled: " + ext);
        String deps = block(fragment, "dependencies {");
        String gate = block(fragment, "jacocoTestCoverageVerification {");
        String check = block(fragment, "tasks.named('check') {");

        String g = read(build);
        // What project-bootstrap leaves: JaCoCo instrumenting and reporting, no gate.
        g += "\napply plugin: 'jacoco'\n\njacoco {\n\ttoolVersion = '" + jacoco + "'\n}\n\n"
                + "tasks.named('test') {\n\tfinalizedBy 'jacocoTestReport'\n}\n\n"
                + "jacocoTestReport {\n\tdependsOn tasks.named('test')\n"
                + "\tclassDirectories.setFrom(\n\t\tfiles(classDirectories.files.collect {\n"
                + "\t\t\tfileTree(dir: it, exclude: ['**/*Application.class'])\n\t\t})\n\t)\n}\n";
        // The fragment: ext first, dependencies and the gate beside jacocoTestReport.
        g += "\n" + ext + "\n\n" + deps + "\n\n" + gate + "\n\n" + check + "\n";
        write(build, g);
    }

    // ---------------------------------------------------------------- project

    /** Calc with two branches, an ArchitectureTest using ArchUnit and CalcTest covering both. */
    static void fixtures(Path app) throws IOException {
        Path main = app.resolve("src/main/java/com/exemplo/minhaapi");
        Path test = app.resolve("src/test/java/com/exemplo/minhaapi");
        write(main.resolve("Calc.java"), "package " + PKG + ";\n\npublic class Calc {\n"
                + "    public int sign(int x) {\n        if (x >= 0) {\n            return 1;\n        }\n        return -1;\n    }\n}\n");
        write(test.resolve("ArchitectureTest.java"), "package " + PKG + ";\n\n"
                + "import com.tngtech.archunit.junit.AnalyzeClasses;\nimport com.tngtech.archunit.junit.ArchTest;\n"
                + "import com.tngtech.archunit.lang.ArchRule;\n"
                + "import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;\n\n"
                + "@AnalyzeClasses(packages = \"" + PKG + "\")\nclass ArchitectureTest {\n"
                + "    @ArchTest\n    static final ArchRule domain_does_not_reach_adapters = noClasses()\n"
                + "            .that().resideInAPackage(\"..domain..\").should().dependOnClassesThat()\n"
                + "            .resideInAPackage(\"..adapter..\").allowEmptyShould(true);\n}\n");
        write(test.resolve("CalcTest.java"), "package " + PKG + ";\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n"
                + "import org.junit.jupiter.api.Test;\n\nclass CalcTest {\n    @Test\n    void signs() {\n"
                + "        Calc calc = new Calc();\n        assertEquals(1, calc.sign(3));\n        assertEquals(-1, calc.sign(-3));\n    }\n}\n");
    }

    static void green(Path app, List<String> cmd, Path reports, String tool) throws Exception {
        Run r = run(app, cmd);
        if (r.exit != 0) {
            System.out.println(tail(r.out, 80));
            fail(tool + ": the build is not green with ArchUnit and a covered class (exit " + r.exit + ")");
        }
        try (Stream<Path> s = Files.isDirectory(reports) ? Files.walk(reports) : Stream.<Path>empty()) {
            if (s.noneMatch(p -> p.getFileName().toString().contains("ArchitectureTest") && p.toString().endsWith(".xml")))
                fail(tool + ": no report for ArchitectureTest under " + reports + " — the ArchUnit test did not run");
        }
    }

    /** The defect the gate exists for: a class with no test. Must fail by name, then CalcTest returns. */
    static void red(Path app, List<String> cmd, String mustSay, String tool) throws Exception {
        Path calcTest = app.resolve("src/test/java/com/exemplo/minhaapi/CalcTest.java");
        String saved = read(calcTest);
        Files.delete(calcTest);
        Run r = run(app, cmd);
        write(calcTest, saved);
        if (r.exit == 0) fail(tool + ": a build with an untested class passed — the coverage gate is not wired into `check`/`verify`");
        if (!r.out.contains(mustSay)) {
            System.out.println(tail(r.out, 40));
            fail(tool + ": the build failed, but the output does not name the gate (`" + mustSay + "`)");
        }
    }

    // ---------------------------------------------------------------- helpers

    static String release(String path) throws Exception {
        String body = metadata(path);
        Matcher m = Pattern.compile("<release>([^<]+)</release>").matcher(body);
        if (!m.find()) fail("no <release> in the metadata of " + path);
        return m.group(1);
    }

    static String latestStable(String path) throws Exception {
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(metadata(path));
        String stable = null;
        while (m.find()) {
            if (!Pattern.compile("(?i)alpha|beta|rc|cr[0-9]|m[0-9]|snapshot").matcher(m.group(1)).find()) stable = m.group(1);
        }
        if (stable == null) fail("no stable <version> in the metadata of " + path);
        return stable;
    }

    static String metadata(String path) throws Exception {
        String url = "https://repo1.maven.org/maven2/" + path + "/maven-metadata.xml";
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) fail("HTTP " + r.statusCode() + " from " + url);
        return r.body();
    }

    /** start.spring.io, same source `project-bootstrap` step 3 uses. */
    static Path initializrProject(Path work, String type, String dir) throws Exception {
        int java = Runtime.version().feature();
        String url = "https://start.spring.io/starter.zip?type=" + type + "&language=java"
                + "&dependencies=web&groupId=com.exemplo&artifactId=minhaapi&name=minhaapi"
                + "&packageName=" + PKG + "&baseDir=" + dir + "&javaVersion=" + java;
        HttpResponse<InputStream> r = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) fail("HTTP " + r.statusCode() + " from start.spring.io: " + url);
        try (ZipInputStream zip = new ZipInputStream(r.body())) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                Path target = work.resolve(e.getName()).normalize();
                if (!target.startsWith(work)) fail("zip entry escapes the work dir: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path app = work.resolve(dir);
        for (String wrapper : List.of("mvnw", "gradlew")) {
            Path w = app.resolve(wrapper);
            if (Files.exists(w) && !w.toFile().setExecutable(true)) fail("could not mark " + wrapper + " executable");
        }
        return app;
    }

    static String inner(String xml, String tag) {
        Matcher m = Pattern.compile("(?s)<" + tag + ">(.*?)</" + tag + ">").matcher(xml);
        if (!m.find()) fail("no <" + tag + "> in the Maven fragment");
        return m.group(1);
    }

    static String whole(String xml, String tag) {
        Matcher m = Pattern.compile("(?s)<" + tag + "[ >].*?</" + tag + ">").matcher(xml);
        if (!m.find()) fail("no <" + tag + "> in the Maven fragment");
        return m.group();
    }

    /** A top-level Groovy block of the fragment, from its opening line to the matching brace. */
    static String block(String groovy, String opening) {
        int start = groovy.indexOf("\n" + opening);
        if (start < 0) fail("no `" + opening + "` block in the Gradle fragment");
        int depth = 0;
        for (int i = groovy.indexOf('{', start); i < groovy.length(); i++) {
            char c = groovy.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return groovy.substring(start + 1, i + 1);
        }
        fail("unbalanced `" + opening + "` block in the Gradle fragment");
        return null;
    }

    record Run(int exit, String out) {}

    static Run run(Path dir, List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out);
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static String tail(String s, int lines) {
        List<String> all = s.lines().collect(Collectors.toList());
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    static void fail(String message) {
        System.out.println("❌ " + message);
        System.exit(1);
    }
}
