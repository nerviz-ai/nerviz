///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the Java templates that a project receives **verbatim** compile against a
// real Spring Boot project, and that the unit tests shipped with them pass.
//
// In scope — the templates copied as files, not read as shape references:
//   - `new-feature-implement/templates/commons/` — `commons-logging-installer` translates only the
//     package and writes every file as-is, its three `*Test` templates included
//     (decision 0098). A template there that stops compiling breaks the installer's own
//     `test-compile` in every project that runs it.
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
    static final Path COMMONS = SKILLS.resolve("new-feature-implement/templates/commons");
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
        Path work = Files.createTempDirectory("java-templates-test");
        Path app = initializrProject(work);
        addAspectjStarter(app.resolve("pom.xml"));
        addDependency(app.resolve("pom.xml"), "org.springframework.data", "spring-data-envers");
        addDependency(app.resolve("pom.xml"), "org.springframework.security", "spring-security-core");

        List<String> tests = new ArrayList<>();
        int files = 0;
        for (Path template : list(COMMONS)) {
            String name = template.getFileName().toString();
            String content = Files.readString(template, StandardCharsets.UTF_8);
            if (name.equals("AutoConfiguration.imports.example")) {
                write(app.resolve("src/main/resources/META-INF/spring/"
                        + "org.springframework.boot.autoconfigure.AutoConfiguration.imports"), content);
            } else if (name.endsWith(".java.example")) {
                String className = name.substring(0, name.length() - ".java.example".length());
                place(app, className, content);
                if (className.endsWith("Test")) tests.add(className);
            }
            files++;
        }
        for (Path template : BLOCK_TEMPLATES) {
            for (Map.Entry<String, String> block : blocks(template).entrySet()) {
                place(app, block.getKey(), block.getValue());
                files++;
            }
        }
        if (tests.isEmpty()) fail("no *Test template found under " + COMMONS + " — the installer ships three");

        List<String> cmd = new ArrayList<>(List.of(app.resolve("mvnw").toString(), "-B", "-q", "test",
                "-Dtest=" + String.join(",", tests), "-Dsurefire.failIfNoSpecifiedTests=false"));
        Process p = new ProcessBuilder(cmd).directory(app.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            System.out.println(tail(out, 60));
            fail(files + " template files placed; `./mvnw test` exited " + exit
                    + ". A compile error names the template by its class; a test failure names the *Test template.");
        }
        System.out.println("✅ " + files + " template files compile in a fresh Initializr project, and "
                + tests.size() + " shipped test classes pass: " + String.join(", ", tests) + ".");
    }

    /** start.spring.io, same source `project-bootstrap` step 3 uses. Zip, not tgz: java.util.zip reads it. */
    static Path initializrProject(Path work) throws Exception {
        int java = Runtime.version().feature();
        String url = "https://start.spring.io/starter.zip?type=maven-project&language=java"
                + "&dependencies=lombok,data-jpa,data-mongodb,data-elasticsearch&groupId=com.exemplo&artifactId=minhaapi&name=minhaapi"
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
        if (!app.resolve("mvnw").toFile().setExecutable(true)) fail("could not mark mvnw executable");
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
