///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the Java templates that a project receives **verbatim** compile against a
// real Spring Boot project, and that the unit tests shipped with them pass.
//
// In scope — the templates copied as files, not read as shape references:
//   - the `dev.nerviz:spring-log-utils` library as `commons-logging-installer` adds it (decision
//     0200): the coordinate, pinned version and exclusions are read from `commons_logging` of
//     `.claude/schemas/extensions.json`, a fixture using `@LogExecution`,
//     `@HttpMethodLogExecution`, `LogMask` and `@MaskSensitiveData` is compiled on Maven and on
//     Gradle, and the run fails when `jackson-databind` 2.x or `guava` resolves on the runtime
//     classpath of either. Nothing is vendored: the pinned release is what a project receives.
//   - `persistence-architect/templates/JpaEntity.java.example` — split on its `// --- ` blocks.
//     It carries `AssignedIdEntity`, the `@MappedSuperclass` every assigned-id entity extends
//     (decision 0096), and `UserEntity` extending it: the base class is new, and a mapped
//     superclass that doesn't compile is the kind of error no prose review sees.
//   - the self-contained blocks of the document family and of the search projection (decision
//     0147): `MongoDocument`, `MongoCollections`, `MongoConfig` (both transaction shapes compile; a
//     project keeps one) of persistence-architect, and `SearchDocument`, `SearchIndexBootstrap` of
//     search-architect. The adapters around them name ports and domain types only a real use case
//     creates, and stay shape references — they were run against real engines when written.
//   - the auditing blocks of persistence-architect (decision 0149): `AuditedEntity` (extending the
//     `AssignedIdEntity` above), `AuditRevision`, `AuditingConfig` and `SystemActor`, against
//     `spring-data-envers` and `spring-security-core` added without a version — the Boot parent
//     manages both. `AuditHistoryAdapter` names a use case's ports and stays a shape reference.
// Out of scope: templates that reference classes only a real use case creates (a controller's
// use case, a test's aggregate). They are shape references; compiling them needs stubs that
// would test the stubs.
//
// Why a real project from the Initializr: `project-bootstrap` gets the Spring Boot version,
// the wrapper and the Lombok wiring from `start.spring.io`, never from memory (@CLAUDE.md
// invariant 8). So does this test — with the Java version of the JDK running it, so the
// generated project compiles on the runner it was generated for.
//
// Why no fixture project is versioned: a checked-in tree would prove the tree compiles, not
// the templates. The templates are read from disk on every run.

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

public class JavaTemplatesTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path EXTENSIONS = Paths.get(".claude/schemas/extensions.json");
    static final Path JPA_ENTITY = SKILLS.resolve("persistence-architect/templates/JpaEntity.java.example");
    static final List<Path> BLOCK_TEMPLATES = List.of(
            JPA_ENTITY,
            SKILLS.resolve("persistence-architect/templates/MongoDocument.java.example"),
            SKILLS.resolve("persistence-architect/templates/MongoCollections.java.example"),
            SKILLS.resolve("persistence-architect/templates/MongoConfig.java.example"),
            SKILLS.resolve("search-architect/templates/SearchDocument.java.example"),
            SKILLS.resolve("search-architect/templates/SearchIndexBootstrap.java.example"),
            SKILLS.resolve("persistence-architect/templates/AuditedEntity.java.example"),
            SKILLS.resolve("persistence-architect/templates/AuditRevision.java.example"),
            SKILLS.resolve("persistence-architect/templates/AuditingConfig.java.example"),
            SKILLS.resolve("persistence-architect/templates/SystemActor.java.example"));
    static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    static final Pattern BLOCK = Pattern.compile("(?m)^// --- (\\S+\\.java)\\s*$");

    public static void main(String[] args) throws Exception {
        Library lib = Library.read();
        Path work = Files.createTempDirectory("java-templates-test");

        // Case 1 and 2 — Maven.
        Path app = initializrProject(work.resolve("maven"), "maven-project", "web,lombok,data-jpa,data-mongodb,data-elasticsearch");
        addAspectjStarter(app.resolve("pom.xml"));
        addDependency(app.resolve("pom.xml"), "org.springframework.data", "spring-data-envers");
        addDependency(app.resolve("pom.xml"), "org.springframework.security", "spring-security-core");
        addLibraryMaven(app.resolve("pom.xml"), lib);

        int files = 0;
        for (Map.Entry<String, String> f : FIXTURES.entrySet()) {
            place(app, f.getKey(), f.getValue());
            files++;
        }
        for (Path template : BLOCK_TEMPLATES) {
            for (Map.Entry<String, String> block : blocks(template).entrySet()) {
                place(app, block.getKey(), block.getValue());
                files++;
            }
        }
        String out = run(app, "case 1 · Maven test-compile with " + lib.coordinate + ":" + lib.version,
                app.resolve("mvnw").toString(), "-B", "-q", "test-compile");
        System.out.println("✅ " + files + " files compile on Maven with " + lib.coordinate + ":" + lib.version + ".");

        // No `-q`: quiet mode prints no dependency line at all, and an empty listing would pass any check.
        // Own lean project: spring-data-elasticsearch brings jackson-databind 2.x on its own, which
        // would hide whether the library's exclusion holds (same reason as the Gradle project).
        Path lean = initializrProject(work.resolve("maven-lean"), "maven-project", "web,lombok");
        addLibraryMaven(lean.resolve("pom.xml"), lib);
        out = run(lean, "case 2 · Maven dependency:list", lean.resolve("mvnw").toString(), "-B", "dependency:list");
        expect(out, "Maven", lib.coordinate.split(":")[1]);
        forbid(out, "Maven", "jackson-databind:jar:2.", "guava");
        System.out.println("✅ Maven runtime graph holds neither jackson-databind 2.x nor guava.");

        // Case 3 — Gradle, same fixture.
        Path gradle = // Only what the fixture needs: spring-data-elasticsearch brings jackson-databind 2.x on its own,
        // which would hide whether the library's exclusion holds.
        initializrProject(work.resolve("gradle"), "gradle-project", "web,lombok");
        addLibraryGradle(gradle.resolve("build.gradle"), lib);
        for (Map.Entry<String, String> f : FIXTURES.entrySet()) place(gradle, f.getKey(), f.getValue());
        run(gradle, "case 3 · Gradle compileJava with " + lib.coordinate + ":" + lib.version,
                gradle.resolve("gradlew").toString(), "-q", "compileJava");
        out = run(gradle, "case 3 · Gradle runtimeClasspath", gradle.resolve("gradlew").toString(), "-q",
                "dependencies", "--configuration", "runtimeClasspath");
        expect(out, "Gradle", lib.coordinate.split(":")[1]);
        forbid(out, "Gradle", "jackson-databind:2.", "guava");
        System.out.println("✅ Gradle compiles the fixture, and its runtimeClasspath holds neither jackson-databind 2.x nor guava.");
    }

    /** What `commons_logging` of extensions.json says; read from disk on every run, never copied here. */
    record Library(String coordinate, String version, List<String> exclusions) {
        static Library read() throws IOException {
            String json = Files.readString(EXTENSIONS, StandardCharsets.UTF_8);
            int at = json.indexOf("\"commons_logging\"");
            if (at < 0) fail("no `commons_logging` block in " + EXTENSIONS);
            String block = json.substring(at, json.indexOf("\"packages\"", at));
            String coordinate = field(block, "coordinate");
            String version = field(block, "version");
            Matcher ex = Pattern.compile("\"exclusions\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(block);
            if (!ex.find()) fail("no `exclusions` in the commons_logging block");
            List<String> exclusions = new ArrayList<>();
            Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(ex.group(1));
            while (item.find()) exclusions.add(item.group(1));
            return new Library(coordinate, version, exclusions);
        }

        private static String field(String block, String name) {
            Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
            if (!m.find()) fail("no `" + name + "` in the commons_logging block");
            return m.group(1);
        }
    }

    /** The three annotations and the interface the library gives a project, used the way a use case would. */
    static final Map<String, String> FIXTURES = new LinkedHashMap<>();
    static {
        FIXTURES.put("MaskedPayload", """
                package com.exemplo.minhaapi.fixture;

                import dev.nerviz.spring.log.utils.features.annotations.MaskSensitiveData;
                import dev.nerviz.spring.log.utils.features.interfaces.LogMask;

                public record MaskedPayload(@MaskSensitiveData String email) implements LogMask {}
                """);
        FIXTURES.put("LoggedService", """
                package com.exemplo.minhaapi.fixture;

                import dev.nerviz.spring.log.utils.features.annotations.LogExecution;
                import org.springframework.stereotype.Service;

                @Service
                public class LoggedService {
                    @LogExecution
                    public MaskedPayload echo(MaskedPayload payload) { return payload; }
                }
                """);
        FIXTURES.put("LoggedController", """
                package com.exemplo.minhaapi.fixture;

                import dev.nerviz.spring.log.utils.features.annotations.HttpMethodLogExecution;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class LoggedController {
                    @HttpMethodLogExecution
                    @GetMapping("/fixture")
                    public MaskedPayload get() { return new MaskedPayload("a@b.c"); }
                }
                """);
    }

    /** The listing must name the library itself, or a forbidden-name check over it proves nothing. */
    static void expect(String out, String build, String artifact) {
        if (!out.contains(artifact)) fail(build + " dependency listing does not name `" + artifact + "` — the check below would be vacuous");
    }

    static void forbid(String out, String build, String... needles) {
        // A Gradle `(c)` line is a constraint the BOM declares, not something that resolved.
        for (String line : out.lines().collect(Collectors.toList()))
            for (String needle : needles)
                if (line.contains(needle) && !line.trim().endsWith("(c)"))
                    fail(build + " runtime graph resolves `" + needle + "` — the exclusion in commons_logging is missing or "
                            + "no longer works: " + line.trim());
    }

    static String run(Path dir, String what, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            System.out.println(tail(out, 60));
            fail(what + " exited " + exit + ". A compile error names the template by its class; a resolution failure names the artifact.");
        }
        return out;
    }

    static void addLibraryMaven(Path pom, Library lib) throws IOException {
        String[] ga = lib.coordinate.split(":");
        StringBuilder x = new StringBuilder("\t\t<dependency>\n\t\t\t<groupId>" + ga[0] + "</groupId>\n\t\t\t<artifactId>"
                + ga[1] + "</artifactId>\n\t\t\t<version>" + lib.version + "</version>\n\t\t\t<exclusions>\n");
        for (String e : lib.exclusions) {
            String[] g = e.split(":");
            x.append("\t\t\t\t<exclusion>\n\t\t\t\t\t<groupId>").append(g[0]).append("</groupId>\n\t\t\t\t\t<artifactId>")
                    .append(g[1]).append("</artifactId>\n\t\t\t\t</exclusion>\n");
        }
        x.append("\t\t\t</exclusions>\n\t\t</dependency>\n\t</dependencies>");
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        Files.writeString(pom, xml.replaceFirst("\t?</dependencies>", Matcher.quoteReplacement(x.toString())), StandardCharsets.UTF_8);
    }

    static void addLibraryGradle(Path build, Library lib) throws IOException {
        StringBuilder x = new StringBuilder("\timplementation('" + lib.coordinate + ":" + lib.version + "') {\n");
        for (String e : lib.exclusions) {
            String[] g = e.split(":");
            x.append("\t\texclude group: '").append(g[0]).append("', module: '").append(g[1]).append("'\n");
        }
        x.append("\t}\n");
        String text = Files.readString(build, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^dependencies \\{\\s*$").matcher(text);
        if (!m.find()) fail("no `dependencies {` block in the Initializr build.gradle");
        Files.writeString(build, text.substring(0, m.end()) + "\n" + x + text.substring(m.end()), StandardCharsets.UTF_8);
    }

    /** start.spring.io, same source `project-bootstrap` step 3 uses. Zip, not tgz: java.util.zip reads it. */
    static Path initializrProject(Path work, String type, String dependencies) throws Exception {
        Files.createDirectories(work);
        int java = Runtime.version().feature();
        String url = "https://start.spring.io/starter.zip?type=" + type + "&language=java"
                + "&dependencies=" + dependencies + "&groupId=com.exemplo&artifactId=minhaapi&name=minhaapi"
                + "&packageName=com.exemplo.minhaapi&baseDir=app&javaVersion=" + java;
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
        Path app = work.resolve("app");
        String wrapper = type.startsWith("gradle") ? "gradlew" : "mvnw";
        if (!app.resolve(wrapper).toFile().setExecutable(true)) fail("could not mark " + wrapper + " executable");
        return app;
    }

    /**
     * The AOP starter the installer adds — renamed in Spring Boot 4, owner of that fact:
     * `project-bootstrap/references/dependency-catalog.md` § Warning. Chosen from the Boot
     * major the Initializr resolved, never from memory. No version: the parent manages it.
     */
    static void addAspectjStarter(Path pom) throws IOException {
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        Matcher parent = Pattern.compile("(?s)<parent>.*?<version>(\\d+)\\.").matcher(xml);
        if (!parent.find()) fail("no Spring Boot parent version in the Initializr pom");
        String artifact = Integer.parseInt(parent.group(1)) >= 4 ? "spring-boot-starter-aspectj" : "spring-boot-starter-aop";
        String dependency = "\t\t<dependency>\n\t\t\t<groupId>org.springframework.boot</groupId>\n\t\t\t<artifactId>"
                + artifact + "</artifactId>\n\t\t</dependency>\n\t</dependencies>";
        Files.writeString(pom, xml.replaceFirst("\t?</dependencies>", Matcher.quoteReplacement(dependency)),
                StandardCharsets.UTF_8);
    }

    /** A dependency the Boot parent manages — no version, never one from memory (invariant 8). */
    static void addDependency(Path pom, String groupId, String artifactId) throws IOException {
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        String dependency = "\t\t<dependency>\n\t\t\t<groupId>" + groupId + "</groupId>\n\t\t\t<artifactId>"
                + artifactId + "</artifactId>\n\t\t</dependency>\n\t</dependencies>";
        Files.writeString(pom, xml.replaceFirst("\t?</dependencies>", Matcher.quoteReplacement(dependency)),
                StandardCharsets.UTF_8);
    }

    /** One entry per `// --- <path>.java` block; the header comment before the first block is dropped. */
    static Map<String, String> blocks(Path file) throws IOException {
        String template = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(template);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start(), m.end()});
            names.add(Paths.get(m.group(1)).getFileName().toString().replace(".java", ""));
        }
        if (names.isEmpty()) fail("no `// --- <File>.java` block in " + file);
        for (int i = 0; i < names.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : template.length();
            out.put(names.get(i), template.substring(starts.get(i)[1], end));
        }
        return out;
    }

    /** src/test for a `*Test`, src/main otherwise, under the directory its `package` line names. */
    static void place(Path app, String className, String source) throws IOException {
        Matcher m = PACKAGE.matcher(source);
        if (!m.find()) fail(className + ": no package declaration");
        String root = className.endsWith("Test") ? "src/test/java" : "src/main/java";
        write(app.resolve(root).resolve(m.group(1).replace('.', '/')).resolve(className + ".java"), source);
    }

    static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().collect(Collectors.toList());
        }
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
