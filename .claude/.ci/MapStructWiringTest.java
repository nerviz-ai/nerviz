///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the MapStruct wiring `mapstruct-installer` writes is correct on both build tools,
// and that every MapStruct mapper exemplar compiles under it.
//
// What it holds, on a fresh Initializr project per build tool (Lombok + configuration processor):
//   1. the build fragment of `new-feature-implement/templates/mapstruct/` merges as its header
//      says — on Maven into the Initializr's own `default-compile` execution, whose processor list
//      the fragment must keep whole (a path the Initializr lists and the fragment drops fails here
//      by name: it would stop running with no error in every project the installer touched);
//   2. every `*/templates/*MapStructMapper.java.example` compiles, with stubs of the ports, records
//      and domain types it names — written here, because those come from other exemplars that are
//      shape references, not compilable files. A new MapStruct exemplar with no stub fails;
//   3. Lombok still runs before MapStruct (a mapper reading a Lombok getter generates), the
//      configuration processor still writes its metadata, and a generated mapper is a Spring
//      `@Component` injected by constructor — the three processor options reached the compiler;
//   4. red on purpose: a mapper with a target property nobody maps fails the compile with
//      MapStruct's "Unmapped target property" — `unmappedTargetPolicy = ERROR` is on.
//
// Why both build tools: the fragment differs, and the failure it guards against differs — Maven
// drops a processor in silence when the path list is incomplete, Gradle never had one listed only
// as a dependency. Why versions are resolved here: the installer resolves them from Maven Central
// at install time (invariant 8), and so does this test.
//
// Network and minutes: runs in `templates.yml`, path-filtered.

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;

public class MapStructWiringTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path FRAGMENTS = SKILLS.resolve("new-feature-implement/templates/mapstruct");
    static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    static final Pattern BLOCK = Pattern.compile("(?m)^// --- (\\S+\\.java)\\b.*$");
    static final String RESOLVED = "<!-- resolved:.*?-->";

    public static void main(String[] args) throws Exception {
        String mapstruct = latestStable("org/mapstruct/mapstruct");
        String binding = latestStable("org/projectlombok/lombok-mapstruct-binding");
        List<Path> exemplars = exemplars();
        if (exemplars.isEmpty()) fail("no *MapStructMapper.java.example under " + SKILLS);

        Path work = Files.createTempDirectory("mapstruct-wiring-test");

        Path maven = initializrProject(work, "maven-project", "maven");
        mergeMaven(maven.resolve("pom.xml"), mapstruct, binding);
        int sources = placeSources(maven, exemplars);
        green(maven, List.of(maven.resolve("mvnw").toString(), "-B", "-q", "compile"),
                maven.resolve("target/generated-sources/annotations"), maven.resolve("target/classes"), "Maven");
        red(maven, List.of(maven.resolve("mvnw").toString(), "-B", "-q", "compile"), "Maven");

        Path gradle = initializrProject(work, "gradle-project", "gradle");
        mergeGradle(gradle.resolve("build.gradle"), mapstruct, binding);
        placeSources(gradle, exemplars);
        green(gradle, List.of(gradle.resolve("gradlew").toString(), "-q", "--console=plain", "compileJava"),
                gradle.resolve("build/generated/sources/annotationProcessor/java/main"),
                gradle.resolve("build/classes/java/main"), "Gradle");
        red(gradle, List.of(gradle.resolve("gradlew").toString(), "-q", "--console=plain", "compileJava"), "Gradle");

        System.out.println("✅ mapstruct " + mapstruct + " + lombok-mapstruct-binding " + binding
                + ": the build fragments wire Lombok, the configuration processor and MapStruct on Maven and Gradle; "
                + exemplars.size() + " MapStruct exemplars (" + sources + " source files with their stubs) compile; "
                + "a generated mapper is a constructor-injected @Component; an unmapped target fails the compile.");
    }

    // ---------------------------------------------------------------- build fragments

    /** Every element of the fragment into the element of the same name, as its header says. */
    static void mergeMaven(Path pom, String mapstruct, String binding) throws IOException {
        String fragment = read(FRAGMENTS.resolve("mapstruct-pom.xml.example"));
        String props = inner(fragment, "properties")
                .replaceFirst("(<mapstruct\\.version>)" + RESOLVED, "$1" + mapstruct)
                .replaceFirst("(<lombok-mapstruct-binding\\.version>)" + RESOLVED, "$1" + binding);
        if (props.contains("resolved:")) fail("a version placeholder of the Maven fragment was not filled: " + props);
        String dm = whole(fragment, "dependencyManagement");
        String deps = inner(fragment.substring(fragment.indexOf("</dependencyManagement>")), "dependencies");
        String paths = whole(fragment, "annotationProcessorPaths");
        String args = whole(fragment, "compilerArgs");

        String xml = read(pom);
        xml = xml.replaceFirst("</properties>", Matcher.quoteReplacement(props + "</properties>"));
        int top = xml.indexOf("<dependencies>", xml.indexOf("</properties>"));
        int topEnd = xml.indexOf("</dependencies>", top);
        xml = xml.substring(0, topEnd) + deps + xml.substring(topEnd);
        xml = xml.substring(0, top) + dm + "\n\t" + xml.substring(top);

        int exec = xml.indexOf("<id>default-compile</id>");
        if (exec < 0) fail("the Initializr pom has no `default-compile` execution — the fragment's placement note no longer matches it");
        int start = xml.indexOf("<annotationProcessorPaths>", exec);
        int end = xml.indexOf("</annotationProcessorPaths>", start) + "</annotationProcessorPaths>".length();
        Set<String> initializr = artifacts(xml.substring(start, end));
        Set<String> kept = artifacts(paths);
        for (String a : initializr) {
            if (!kept.contains(a)) fail("the Initializr's `default-compile` runs processor `" + a
                    + "`, and mapstruct-pom.xml.example's annotationProcessorPaths drops it — it would stop running with no error");
        }
        xml = xml.substring(0, start) + paths + "\n" + args + xml.substring(end);
        write(pom, xml);
        addMavenDependency(pom, "org.springframework.data", "spring-data-commons");
    }

    /** `ext` before the first `dependencies` block that reads it; the rest appended, as the header says. */
    static void mergeGradle(Path build, String mapstruct, String binding) throws IOException {
        String fragment = read(FRAGMENTS.resolve("mapstruct-build.gradle.example"));
        String ext = block(fragment, "ext {")
                .replaceFirst("(mapstructVersion = ')" + RESOLVED, "$1" + mapstruct)
                .replaceFirst("(lombokMapstructBindingVersion = ')" + RESOLVED, "$1" + binding);
        if (ext.contains("resolved:")) fail("a version placeholder of the Gradle fragment was not filled: " + ext);
        String deps = block(fragment, "dependencies {");
        String compile = block(fragment, "tasks.withType(JavaCompile)");
        String g = read(build);
        int at = g.indexOf("dependencies {");
        if (at < 0) fail("no dependencies block in the Initializr build.gradle");
        g = g.substring(0, at) + ext + "\n\n" + g.substring(at);
        g = g + "\n" + deps + "\n\ndependencies {\n\timplementation 'org.springframework.data:spring-data-commons'\n}\n\n" + compile + "\n";
        write(build, g);
    }

    // ---------------------------------------------------------------- runs

    static void green(Path app, List<String> cmd, Path generated, Path classes, String tool) throws Exception {
        Run r = run(app, cmd);
        if (r.exit != 0) {
            System.out.println(tail(r.out, 80));
            fail(tool + ": the MapStruct exemplars do not compile under the fragment's wiring (exit " + r.exit
                    + "). A MapStruct error names the exemplar's interface; \"cannot find symbol\" on a getter means Lombok stopped running.");
        }
        Path impl = find(generated, "UserMapperImpl.java");
        String src = read(impl);
        if (!src.contains("@Component")) fail(tool + ": UserMapperImpl is not a @Component — `mapstruct.defaultComponentModel=spring` did not reach the compiler");
        if (!Pattern.compile("UserMapperImpl\\(\\s*UserRestConversions").matcher(src).find())
            fail(tool + ": UserMapperImpl takes no UserRestConversions in its constructor — `mapstruct.defaultInjectionStrategy=constructor` did not reach the compiler");
        find(generated, "LombokSourceMapperImpl.java");
        Path metadata = classes.resolve("META-INF/spring-configuration-metadata.json");
        if (!Files.exists(metadata) || !read(metadata).contains("fixture.name"))
            fail(tool + ": no `fixture.name` in META-INF/spring-configuration-metadata.json — the configuration processor stopped running");
    }

    /** The defect the policy exists for: a target property nobody maps. Must fail by name, then is removed. */
    static void red(Path app, List<String> cmd, String tool) throws Exception {
        Path bad = app.resolve("src/main/java/com/exemplo/minhaapi/fixture/UnmappedFixtureMapper.java");
        write(bad, """
                package com.exemplo.minhaapi.fixture;

                import org.mapstruct.Mapper;

                @Mapper
                interface UnmappedFixtureMapper {
                    record From(String name) {}
                    record To(String name, String forgotten) {}
                    To map(From from);
                }
                """);
        Run r = run(app, cmd);
        Files.delete(bad);
        if (r.exit == 0) fail(tool + ": a mapper with an unmapped target property compiled — `mapstruct.unmappedTargetPolicy=ERROR` is not on");
        if (!r.out.contains("Unmapped target property")) {
            System.out.println(tail(r.out, 40));
            fail(tool + ": the compile failed, but not on MapStruct's unmapped-target error");
        }
    }

    // ---------------------------------------------------------------- sources

    static List<Path> exemplars() throws IOException {
        try (Stream<Path> s = Files.list(SKILLS)) {
            List<Path> out = new ArrayList<>();
            for (Path skill : s.sorted().toList()) {
                Path templates = skill.resolve("templates");
                if (!Files.isDirectory(templates)) continue;
                try (Stream<Path> t = Files.list(templates)) {
                    t.filter(p -> p.getFileName().toString().endsWith("MapStructMapper.java.example")).sorted().forEach(out::add);
                }
            }
            return out;
        }
    }

    static int placeSources(Path app, List<Path> exemplars) throws IOException {
        int n = 0;
        for (Path exemplar : exemplars) {
            for (Map.Entry<String, String> b : blocks(exemplar).entrySet()) {
                place(app, b.getKey(), b.getValue());
                n++;
            }
        }
        for (Map.Entry<String, String> stub : STUBS.entrySet()) {
            place(app, stub.getKey(), stub.getValue());
            n++;
        }
        return n;
    }

    /**
     * The types the exemplars name, shaped after the exemplar that owns each — Dtos, the domain
     * catalog, the GraphQL and HTTP adapters, the producer. Only what a mapper reads or builds.
     */
    static final Map<String, String> STUBS = new LinkedHashMap<>();
    static {
        String r = "com.exemplo.minhaapi.";
        String d = "com.example.demo.";
        // rest-api-architect
        stub(r + "adapter.in.rest.dto", "CreateUserRequest", "public record CreateUserRequest(String email, String name, String departmentId) {}");
        stub(r + "adapter.in.rest.dto", "UserResponse", "public record UserResponse(String id, String email, String name, java.time.Instant createdAt) {}");
        stub(r + "application.port.in", "PageCriteria", "public record PageCriteria(int page, int size, java.util.List<SortCriterion> sort) {\n"
                + "    public record SortCriterion(String property, Direction direction) { public enum Direction { ASC, DESC } }\n}");
        stub(r + "application.port.in.user", "CreateUserCommand", "import " + r + "domain.model.user.*;\n"
                + "public record CreateUserCommand(Email email, String name, DepartmentId departmentId) {}");
        stub(r + "domain.model.user", "User", "public record User(UserId id, Email email, String name, DepartmentId departmentId, java.time.Instant createdAt) {}");
        stub(r + "domain.model.user", "UserId", "public record UserId(java.util.UUID value) {}");
        stub(r + "domain.model.user", "Email", "public record Email(String value) { public static Email of(String raw) { return new Email(raw); } }");
        stub(r + "domain.model.user", "DepartmentId", "public record DepartmentId(String value) {\n"
                + "    public static DepartmentId unassigned() { return new DepartmentId(null); }\n"
                + "    public static DepartmentId of(String raw) { return new DepartmentId(raw); }\n}");
        // persistence-architect
        stub(d + "domain.model", "User", "import java.time.Instant;\n"
                + "public record User(UserId id, Email email, String name, DepartmentId departmentId, UserStatus status, Instant createdAt) {\n"
                + "    public static User rehydrate(UserId id, Email email, String name, DepartmentId departmentId, UserStatus status, Instant createdAt) {\n"
                + "        return new User(id, email, name, departmentId, status, createdAt);\n    }\n}");
        stub(d + "domain.model", "UserId", "public record UserId(java.util.UUID value) { public static UserId of(java.util.UUID v) { return new UserId(v); } }");
        stub(d + "domain.model", "Email", "public record Email(String value) { public static Email of(String raw) { return new Email(raw); } }");
        stub(d + "domain.model", "DepartmentId", "public record DepartmentId(java.util.UUID value) {\n"
                + "    public static DepartmentId unassigned() { return new DepartmentId(null); }\n"
                + "    public static DepartmentId of(java.util.UUID v) { return new DepartmentId(v); }\n"
                + "    public boolean isAssigned() { return value != null; }\n}");
        stub(d + "domain.model", "UserStatus", "public enum UserStatus { ACTIVE, SUSPENDED, CLOSED }");
        stub(d + "adapter.out.persistence.user", "UserStatus", "enum UserStatus { ACTIVE, SUSPENDED, CLOSED }");
        stub(d + "adapter.out.persistence.user", "UserEntity", "import java.time.Instant;\nimport java.util.UUID;\n"
                + "import lombok.AccessLevel;\nimport lombok.Getter;\nimport lombok.NoArgsConstructor;\n"
                + "@Getter\n@NoArgsConstructor(access = AccessLevel.PROTECTED)\nclass UserEntity {\n"
                + "    private UUID id; private String email; private String name; private UUID departmentId; private UserStatus status; private Instant createdAt;\n"
                + "    UserEntity(UUID id, String email, String name, UUID departmentId, UserStatus status, Instant createdAt) {\n"
                + "        this.id = id; this.email = email; this.name = name; this.departmentId = departmentId; this.status = status; this.createdAt = createdAt;\n    }\n}");
        // graphql-api-architect
        stub(r + "application.port.in.order", "OrderStatus", "public enum OrderStatus { PLACED, CONFIRMED, CANCELLED }");
        stub(r + "application.port.in.order", "OrderView", "import java.math.BigDecimal;\nimport java.time.Instant;\nimport java.util.UUID;\n"
                + "public record OrderView(UUID id, OrderStatus status, Instant placedAt, BigDecimal total, String currency, UUID customerId) {}");
        stub(r + "application.port.in.order", "OrderLineView", "public record OrderLineView(String sku, int quantity, java.math.BigDecimal unitPrice, String currency) {}");
        stub(r + "application.port.in.customer", "CustomerView", "public record CustomerView(java.util.UUID id, String name, String taxId) {}");
        stub(r + "adapter.in.graphql", "Order", "record Order(java.util.UUID id, String status, String placedAt, Money total, java.util.UUID customerId) {}");
        stub(r + "adapter.in.graphql", "OrderLine", "record OrderLine(String sku, int quantity, Money unitPrice) {}");
        stub(r + "adapter.in.graphql", "Money", "record Money(String amount, String currency) {}");
        stub(r + "adapter.in.graphql", "Customer", "record Customer(java.util.UUID id, String name, String taxId) {}");
        // http-client-architect
        stub(r + "domain.model.payment", "PaymentId", "public record PaymentId(java.util.UUID value) {}");
        stub(r + "domain.model.payment", "Money", "import java.math.BigDecimal;\nimport java.util.Currency;\n"
                + "public record Money(BigDecimal amount, Currency currency) {\n"
                + "    public long minorUnits() { return amount.movePointRight(currency.getDefaultFractionDigits()).longValueExact(); }\n}");
        stub(r + "domain.model.payment", "ChargeReference", "public record ChargeReference(String value) {}");
        stub(r + "domain.model.payment", "ChargeStatus", "public enum ChargeStatus { PENDING, CAPTURED, DECLINED, UNKNOWN }");
        stub(r + "adapter.out.http.paymentgateway", "CreateChargeRequest", "record CreateChargeRequest(String merchantReference, long amountMinor, String currency) {}");
        stub(r + "adapter.out.http.paymentgateway", "ChargeResource", "record ChargeResource(String id, String status) {}");
        // messaging-architect
        stub(d + "domain.model", "OrderId", "public record OrderId(java.util.UUID value) {}");
        stub(d + "domain.model", "CustomerId", "public record CustomerId(java.util.UUID value) {}");
        stub(d + "domain.event", "OrderConfirmed", "import " + d + "domain.model.*;\n"
                + "public record OrderConfirmed(OrderId orderId, CustomerId customerId, java.time.Instant occurredAt) {}");
        stub(d + "adapter.out.messaging.orderconfirmed", "OrderConfirmedPayload",
                "public record OrderConfirmedPayload(java.util.UUID orderId, java.util.UUID customerId, java.time.Instant occurredAt) {}");
        // the wiring fixtures: Lombok before MapStruct, the configuration processor still on
        stub(r + "fixture", "LombokSource", "@lombok.Getter\n@lombok.RequiredArgsConstructor\npublic class LombokSource { private final String name; }");
        stub(r + "fixture", "LombokSourceMapper", "@org.mapstruct.Mapper\ninterface LombokSourceMapper {\n"
                + "    record Target(String name) {}\n    Target map(LombokSource source);\n}");
        stub(r + "fixture", "FixtureProperties", "@org.springframework.boot.context.properties.ConfigurationProperties(\"fixture\")\n"
                + "public record FixtureProperties(String name) {}");
    }

    static void stub(String pkg, String name, String body) {
        String key = pkg + "." + name;
        if (STUBS.containsKey(key)) fail("stub declared twice: " + key);
        STUBS.put(key, "package " + pkg + ";\n\n" + body + "\n");
    }

    // ---------------------------------------------------------------- helpers

    static String latestStable(String path) throws Exception {
        String url = "https://repo1.maven.org/maven2/" + path + "/maven-metadata.xml";
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) fail("HTTP " + r.statusCode() + " from " + url);
        // The installer's filter, not `<release>`: that is the last deployed, and MapStruct deploys betas.
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(r.body());
        String stable = null;
        while (m.find()) {
            if (!Pattern.compile("(?i)alpha|beta|rc|cr[0-9]|m[0-9]|snapshot").matcher(m.group(1)).find()) stable = m.group(1);
        }
        if (stable == null) fail("no stable <version> in " + url);
        return stable;
    }

    /** start.spring.io, same source `project-bootstrap` step 3 uses. */
    static Path initializrProject(Path work, String type, String dir) throws Exception {
        int java = Runtime.version().feature();
        String url = "https://start.spring.io/starter.zip?type=" + type + "&language=java"
                + "&dependencies=lombok,configuration-processor,web&groupId=com.exemplo&artifactId=minhaapi&name=minhaapi"
                + "&packageName=com.exemplo.minhaapi&baseDir=" + dir + "&javaVersion=" + java;
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

    static void addMavenDependency(Path pom, String groupId, String artifactId) throws IOException {
        String xml = read(pom);
        int top = xml.indexOf("</dependencyManagement>");
        int end = xml.indexOf("</dependencies>", xml.indexOf("<dependencies>", top));
        String dep = "\t\t<dependency>\n\t\t\t<groupId>" + groupId + "</groupId>\n\t\t\t<artifactId>" + artifactId
                + "</artifactId>\n\t\t</dependency>\n\t";
        write(pom, xml.substring(0, end) + dep + xml.substring(end));
    }

    static Map<String, String> blocks(Path file) throws IOException {
        String template = read(file);
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
            String body = template.substring(starts.get(i)[1], end);
            Matcher p = PACKAGE.matcher(body);
            if (!p.find()) fail(file + " › " + names.get(i) + ": no package declaration");
            out.put(p.group(1) + "." + names.get(i), body);
        }
        return out;
    }

    /** Key is `<package>.<Class>`; the file lands under src/main/java by its package. */
    static void place(Path app, String fqcn, String source) throws IOException {
        int dot = fqcn.lastIndexOf('.');
        Path file = app.resolve("src/main/java").resolve(fqcn.substring(0, dot).replace('.', '/'))
                .resolve(fqcn.substring(dot + 1) + ".java");
        if (Files.exists(file)) fail("two sources for " + fqcn + " — an exemplar and a stub, or two exemplars");
        write(file, source);
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

    static Set<String> artifacts(String xml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
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

    static Path find(Path root, String name) throws IOException {
        if (!Files.isDirectory(root)) fail("no generated sources at " + root);
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equals(name)).findFirst()
                    .orElseGet(() -> { fail(name + " was not generated under " + root); return null; });
        }
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
