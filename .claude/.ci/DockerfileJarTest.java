///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the generated Dockerfile's `COPY` survives a version bump, on both build tools.
//
// What it holds, on a fresh Initializr project per build tool (single-module, the starter as it is):
//   1. the premise: a Dockerfile that copies `<artifactId>-<version>.jar`, the path `project-bootstrap`
//      used to fill in, builds — and fails the moment the project version is bumped, while the
//      project's own build stays green. If this ever stops failing, the fix is no longer needed
//      and the test says so by name;
//   2. the fix: `pom.main-build.xml.example`'s `<finalName>` (Maven) or `build.gradle.main-boot.example`'s
//      `bootJar` block (Gradle) applied as `build-maven.md` / `build-gradle.md` say, the Dockerfile
//      filled from its exemplar with the jar path `scaffold.md` § 4.10 names, `docker build` green —
//      and green again after two further version bumps;
//   3. `scaffold.md` names exactly that path (`target/app.jar`, `build/libs/app.jar`) and no longer
//      names a jar by artifactId and version, so the prose the model follows and the path this test
//      proves cannot drift apart.
//
// Why both build tools: the pin is a different key in each (`finalName`, `archiveFileName`), and
// the default it replaces is a different expression. Why the control runs first: a test that only
// ever sees the fixed state proves nothing about the defect it guards against.
//
// What it does not prove, and says so: the multi-module layout. The fragment is the same one; where
// it lands in the `contains_main` module's file is prose in `build-maven.md` and `build-gradle.md`,
// and `BlueprintSkeletonTest` builds that layout with POMs of its own.
//
// Network, Docker and minutes: runs in `templates.yml`, path-filtered. Issue #149, decision 0183.

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

public class DockerfileJarTest {

    static final Path SKILL = Paths.get(".claude/skills/project-bootstrap");
    static final String ARTIFACT = "minhaapi", START = "0.0.1-SNAPSHOT";

    public static void main(String[] args) throws Exception {
        scaffoldNamesTheFixedPath();
        if (run(Paths.get("."), List.of("docker", "version", "--format", "{{.Server.Version}}")).exit != 0)
            fail("no Docker daemon: this test builds the image, which is the whole point");

        Path work = Files.createTempDirectory("dockerfile-jar-test");
        for (String tool : List.of("maven", "gradle")) {
            try {
                tool(work, tool);
            } finally {
                run(work, List.of("docker", "rmi", "-f", image(tool)));
            }
        }
        System.out.println("✅ the Dockerfile copies a jar whose name no version bump changes, on Maven and Gradle: "
                + "the old path builds, then fails after a bump with the project's own build green; "
                + "the fixed name builds, and builds again after two bumps.");
    }

    // ---------------------------------------------------------------- one build tool

    static void tool(Path work, String tool) throws Exception {
        boolean maven = tool.equals("maven");
        Path app = initializrProject(work, tool + "-project", tool);
        Path buildFile = app.resolve(maven ? "pom.xml" : "build.gradle");
        String dockerfile = read(SKILL.resolve(maven ? "templates/Dockerfile.example" : "templates/Dockerfile-gradle.example"));

        // 1 · the control: the versioned path scaffold.md used to fill in
        String old = (maven ? "target/" : "build/libs/") + ARTIFACT + "-" + START + ".jar";
        write(app.resolve("Dockerfile"), fill(dockerfile, old));
        build(app, tool, "the control (versioned path, version " + START + ")", true);
        bump(buildFile, maven, START, "0.0.2");
        run(app, projectBuild(maven));
        if (build(app, tool, "", false).exit == 0)
            fail(tool + ": a Dockerfile copying " + old + " still builds after the version moved to 0.0.2 — "
                    + "the premise of issue #149 no longer holds, and this test with it");

        // 2 · the fix, applied as the build references say
        String fixed = (maven ? "target/" : "build/libs/") + "app.jar";
        if (maven) addFinalName(buildFile); else addBootJar(buildFile);
        write(app.resolve("Dockerfile"), fill(dockerfile, fixed));
        build(app, tool, "the fixed name (" + fixed + ", version 0.0.2)", true);
        bump(buildFile, maven, "0.0.2", "0.0.3");
        build(app, tool, "the fixed name after a bump to 0.0.3", true);
        bump(buildFile, maven, "0.0.3", "1.0.0");
        build(app, tool, "the fixed name after a bump to 1.0.0", true);
    }

    /** The exemplar's <finalName> into the <build> the Initializr's pom already has, as build-maven.md says. */
    static void addFinalName(Path pom) throws IOException {
        Matcher m = Pattern.compile("<finalName>[^<]*</finalName>").matcher(read(SKILL.resolve("templates/pom.main-build.xml.example")));
        if (!m.find()) fail("pom.main-build.xml.example has no <finalName>");
        String xml = read(pom);
        int at = xml.indexOf("<build>");
        if (at < 0) fail("the Initializr pom has no <build> — build-maven.md's single-module step no longer matches it");
        at += "<build>".length();
        write(pom, xml.substring(0, at) + "\n\t\t" + m.group() + xml.substring(at));
    }

    /** The exemplar's bootJar block, appended to the build.gradle that already applies the plugin. */
    static void addBootJar(Path build) throws IOException {
        String example = read(SKILL.resolve("templates/build.gradle.main-boot.example"));
        int start = example.indexOf("\nbootJar {");
        if (start < 0) fail("build.gradle.main-boot.example has no bootJar block");
        int end = example.indexOf("\n}", start) + 2;
        write(build, read(build) + "\n" + example.substring(start + 1, end) + "\n");
    }

    /** Dockerfile exemplar as scaffold.md § 4.10 fills it, single-module: the module copies line is deleted. */
    static String fill(String template, String jarPath) {
        String out = template.lines()
                .filter(l -> !l.contains("{{MODULE_POM_COPIES}}") && !l.contains("{{MODULE_BUILD_GRADLE_COPIES}}"))
                .collect(Collectors.joining("\n", "", "\n"))
                .replace("{{JAVA_VERSION}}", Integer.toString(Runtime.version().feature()))
                .replace("{{MAIN_MODULE_JAR_PATH}}", jarPath);
        if (out.contains("{{")) fail("a placeholder of the Dockerfile exemplar is left unfilled: "
                + out.lines().filter(l -> l.contains("{{")).findFirst().orElse(""));
        return out;
    }

    static void bump(Path buildFile, boolean maven, String from, String to) throws IOException {
        String s = read(buildFile);
        String was = maven ? "<version>" + from + "</version>" : "version = '" + from + "'";
        String now = maven ? "<version>" + to + "</version>" : "version = '" + to + "'";
        if (s.split(Pattern.quote(was), -1).length != 2)
            fail(buildFile.getFileName() + ": expected exactly one `" + was + "` to bump, the starter's shape changed");
        write(buildFile, s.replace(was, now));
    }

    static List<String> projectBuild(boolean maven) {
        return maven ? List.of("./mvnw", "-B", "-q", "-DskipTests", "package")
                : List.of("./gradlew", "--no-daemon", "-q", "--console=plain", "bootJar", "-x", "test");
    }

    static Run build(Path app, String tool, String what, boolean mustPass) throws Exception {
        Run r = run(app, List.of("docker", "build", "--progress=plain", "-t", image(tool), "."));
        if (mustPass && r.exit != 0) {
            System.out.println(tail(r.out, 60));
            fail(tool + ": `docker build` failed for " + what);
        }
        return r;
    }

    static String image(String tool) {
        return "nerviz-dockerfile-jar-test-" + tool;
    }

    // ---------------------------------------------------------------- the prose

    static void scaffoldNamesTheFixedPath() throws IOException {
        String s = read(SKILL.resolve("references/scaffold.md"));
        for (String path : List.of("`target/app.jar`", "`build/libs/app.jar`", "`<module-path>/target/app.jar`", "`<module-path>/build/libs/app.jar`"))
            if (!s.contains(path)) fail("references/scaffold.md § 4.10 does not name " + path + " as the jar path");
        if (s.contains("<artifactId>-<version>.jar"))
            fail("references/scaffold.md still names a jar by <artifactId>-<version>: a version bump breaks the Dockerfile's COPY");
    }

    // ---------------------------------------------------------------- Initializr, processes, files

    static Path initializrProject(Path work, String dir, String type) throws Exception {
        String url = "https://start.spring.io/starter.zip?type=" + type + "-project&language=java"
                + "&dependencies=web&groupId=com.exemplo&artifactId=" + ARTIFACT + "&name=" + ARTIFACT
                + "&packageName=com.exemplo." + ARTIFACT + "&baseDir=" + dir + "&javaVersion=" + Runtime.version().feature();
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
