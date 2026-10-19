///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the procedure of the `platform-upgrade` skill takes a real project from the line
// below `doctor.platform.min_line` to the Initializr's current one, on Maven and on Gradle, and
// leaves a green build. Per build tool: a fixture on the newest GA line below the floor, read from
// Maven Central's `maven-metadata.xml` for `spring-boot-starter-parent` at run time; the target read
// from a project the Initializr generates; the engine's version resolved the way the skill does
// (`doctor.platform.upgrade.engine`); `upgrade-recipe --pass bump` run through the engine; the
// resolved classpath; `--pass properties` run through the engine without compiling; then the test
// slice's starter added from `test_starters`, found by the package the compile error names; then
// the tests. It asserts the build declares the target, the renamed starter is in place, the
// configuration property was renamed, the test compiled against the renamed types and ran.
//
// Why this test exists: `UpgradeRecipeTest` holds the recipe file, but the file is only worth what
// OpenRewrite does with it. Two things went wrong the first time it ran for real and nothing short
// of this would have caught them: the rewrite plugin compiles the project before it runs, so the
// property pass has to skip compilation; and the Gradle plugin the Plugin Portal lists newest
// imports a `rewrite-bom` Maven Central does not serve. Decision 0175.
//
// Network and minutes: path-filtered, in `templates.yml`, never in `validate`.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class PlatformUpgradeTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final Path SOURCE = Paths.get("").toAbsolutePath();
    static final Path SKILL_TEMPLATES = Paths.get(".claude/skills/platform-upgrade/templates").toAbsolutePath();
    static String data;
    static String floorMajor;

    record Run(int exit, String out) { }

    public static void main(String[] args) throws Exception {
        data = Files.readString(Paths.get(".claude/schemas/extensions.json"), StandardCharsets.UTF_8);
        Matcher floor = Pattern.compile("\"min_line\":\\s*\"(\\d+)\\.(\\d+)\"").matcher(data);
        if (!floor.find()) { fail("no doctor.platform.min_line in the data"); System.exit(1); }
        floorMajor = floor.group(1);

        Path work = Files.createTempDirectory("archhook-platform-upgrade");
        String oldBoot = newestBelowFloor();
        System.out.println("fixture on Spring Boot " + oldBoot);

        Path starter = work.resolve("starter-maven");
        Files.createDirectories(starter);
        sh(starter, "bash", "-c", "curl -sS --fail -L " + engine("initializr_starter")
                + " -d type=maven-project -d language=java | tar -xzf - -C " + starter);
        Matcher parent = Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(Files.readString(starter.resolve("pom.xml")));
        if (!parent.find()) { fail("the Initializr's project has no parent version to read"); System.exit(1); }
        String target = parent.group(1);
        System.out.println("target " + target + " (the Initializr's)");

        try {
            maven(work, oldBoot, target);
            gradle(work, oldBoot, target);
        } finally {
            deleteTree(work);
        }
        System.out.println(failures == 0
                ? "\n✅ platform-upgrade takes a project below the floor to the Initializr's line, on Maven and Gradle."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ── Maven ────────────────────────────────────────────────────────────────

    static void maven(Path work, String oldBoot, String target) throws Exception {
        Path app = fixture(work.resolve("maven-app"), work.resolve("starter-maven"), "maven");
        write(app.resolve("pom.xml"), pomWithBoot(oldBoot));
        commit(app);
        String plugin = engine("maven_plugin");
        String version = lastTag(curl(engine("maven_plugin_metadata")), "release");
        System.out.println("maven engine " + plugin + ":" + version);

        upgradeRecipe(app, work, "bump", "--target", target, "--out", work.resolve("maven-bump.yml").toString());
        step("maven pass 1", app, "./mvnw", "-B", "-q", plugin + ":" + version + ":run",
                "-Drewrite.configLocation=" + work.resolve("maven-bump.yml"),
                "-Drewrite.activeRecipes=" + recipeName("bump_name"));
        checks(app, "pom.xml", target, "spring-boot-starter-webmvc");

        step("maven classpath", app, "./mvnw", "-B", "-q", "dependency:build-classpath", "-Dmdep.outputFile=target/nerviz-classpath.txt");
        Path cp = work.resolve("maven-classpath.txt");
        Files.writeString(cp, Files.readString(app.resolve("target/nerviz-classpath.txt")) + "\n");

        upgradeRecipe(app, work, "properties", "--classpath", cp.toString(), "--out", work.resolve("maven-props.yml").toString());
        step("maven pass 2", app, "./mvnw", "-B", "-q", plugin + ":" + version + ":run",
                "-Dmaven.main.skip=true", "-Dmaven.test.skip=true",
                "-Drewrite.configLocation=" + work.resolve("maven-props.yml"),
                "-Drewrite.activeRecipes=" + recipeName("properties_name"));
        propertyRenamed(app);

        Run compile = sh(app, "./mvnw", "-B", "test-compile");
        if (compile.exit == 0) { fail("maven — the test compiled before its test starter was added; the case proves nothing"); return; }
        String starterDep = starterFor(compile.out);
        if (starterDep == null) { fail("maven — the compile error names no package `test_starters` holds:\n" + tail(compile.out)); return; }
        String[] ga = starterDep.split(":");
        String pom = Files.readString(app.resolve("pom.xml"));
        write(app.resolve("pom.xml"), pom.replace("  </dependencies>", "    <dependency>\n      <groupId>" + ga[0]
                + "</groupId>\n      <artifactId>" + ga[1] + "</artifactId>\n      <scope>test</scope>\n    </dependency>\n  </dependencies>"));
        Run verify = sh(app, "./mvnw", "-B", "verify");
        if (verify.exit != 0 || !verify.out.contains("Tests run: 1, Failures: 0")) fail("maven verify after the test starter:\n" + tail(verify.out));
        else System.out.println("✅ maven: " + oldBoot + " → " + target + ", verify green");
    }

    // ── Gradle ───────────────────────────────────────────────────────────────

    static void gradle(Path work, String oldBoot, String target) throws Exception {
        Path starter = work.resolve("starter-gradle");
        Files.createDirectories(starter);
        sh(starter, "bash", "-c", "curl -sS --fail -L " + engine("initializr_starter")
                + " -d type=gradle-project -d language=java | tar -xzf - -C " + starter);
        Path app = fixture(work.resolve("gradle-app"), starter, "gradle");
        write(app.resolve("build.gradle"), buildGradleWithBoot(oldBoot));
        commit(app);
        String pluginVersion = gradlePluginVersion();
        System.out.println("gradle engine " + engine("gradle_plugin") + ":" + pluginVersion);
        Files.copy(SKILL_TEMPLATES.resolve("rewrite-init.gradle.example"), work.resolve("rewrite-init.gradle"));
        Files.copy(SKILL_TEMPLATES.resolve("classpath-init.gradle.example"), work.resolve("classpath-init.gradle"));

        upgradeRecipe(app, work, "bump", "--target", target, "--out", work.resolve("gradle-bump.yml").toString());
        step("gradle pass 1", app, "./gradlew", "--no-daemon", "-q", "--init-script", work.resolve("rewrite-init.gradle").toString(),
                "-Dnerviz.plugin.version=" + pluginVersion, "-Dnerviz.recipe=" + work.resolve("gradle-bump.yml"),
                "-Dnerviz.recipe.name=" + recipeName("bump_name"), "rewriteRun");
        checks(app, "build.gradle", target, "spring-boot-starter-webmvc");

        Path cp = work.resolve("gradle-classpath.txt");
        step("gradle classpath", app, "./gradlew", "--no-daemon", "-q", "--init-script", work.resolve("classpath-init.gradle").toString(),
                "-Dnerviz.classpath.out=" + cp, "nervizClasspath");

        upgradeRecipe(app, work, "properties", "--classpath", cp.toString(), "--out", work.resolve("gradle-props.yml").toString());
        step("gradle pass 2", app, "./gradlew", "--no-daemon", "-q", "-x", "compileJava", "-x", "compileTestJava",
                "--init-script", work.resolve("rewrite-init.gradle").toString(),
                "-Dnerviz.plugin.version=" + pluginVersion, "-Dnerviz.recipe=" + work.resolve("gradle-props.yml"),
                "-Dnerviz.recipe.name=" + recipeName("properties_name"), "rewriteRun");
        propertyRenamed(app);

        Run compile = sh(app, "./gradlew", "--no-daemon", "-q", "compileTestJava");
        if (compile.exit == 0) { fail("gradle — the test compiled before its test starter was added; the case proves nothing"); return; }
        String starterDep = starterFor(compile.out);
        if (starterDep == null) { fail("gradle — the compile error names no package `test_starters` holds:\n" + tail(compile.out)); return; }
        String build = Files.readString(app.resolve("build.gradle"));
        write(app.resolve("build.gradle"), build.replace("testRuntimeOnly", "testImplementation '" + starterDep + "'\n\ttestRuntimeOnly"));
        Run test = sh(app, "./gradlew", "--no-daemon", "-q", "test");
        Path results = app.resolve("build/test-results/test/TEST-com.acme.AppTest.xml");
        if (test.exit != 0 || !Files.isRegularFile(results) || !Files.readString(results).contains("failures=\"0\"")) {
            fail("gradle test after the test starter:\n" + tail(test.out));
        } else {
            System.out.println("✅ gradle: " + oldBoot + " → " + target + ", test green");
        }
    }

    // ── what the skill resolves ──────────────────────────────────────────────

    /** The newest GA `spring-boot-starter-parent` whose major is below the floor's, read from Maven Central. */
    static String newestBelowFloor() throws Exception {
        String xml = curl("https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml");
        String best = null;
        Matcher m = Pattern.compile("<version>(" + (Integer.parseInt(floorMajor) - 1) + "\\.\\d+\\.\\d+)</version>").matcher(xml);
        while (m.find()) best = m.group(1);
        if (best == null) throw new IllegalStateException("no GA line below the floor on Maven Central");
        return best;
    }

    /** The skill's step 4, Gradle: the newest candidate whose `rewrite-bom` Maven Central serves. */
    static String gradlePluginVersion() throws Exception {
        List<String> versions = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(curl(engine("gradle_plugin_metadata")));
        while (m.find()) versions.add(m.group(1));
        versions = versions.subList(Math.max(0, versions.size() - Integer.parseInt(engine("gradle_candidates"))), versions.size());
        for (int i = versions.size() - 1; i >= 0; i--) {
            String v = versions.get(i);
            Matcher bom = Pattern.compile("rewrite-bom</artifactId>\\s*<version>([^<]+)</version>")
                    .matcher(curl(engine("gradle_plugin_pom").replace("{version}", v)));
            if (!bom.find()) continue;
            Run head = sh(SOURCE, "curl", "-sSfI", "-o", "/dev/null", engine("central_bom").replace("{version}", bom.group(1)));
            if (head.exit == 0) return v;
        }
        throw new IllegalStateException("no Gradle plugin candidate imports a BOM Maven Central serves");
    }

    /** The test-scope dependency of the `test_starters` key that the compile error's package names. */
    static String starterFor(String compileOutput) {
        Matcher pkg = Pattern.compile("package ([a-z0-9.]+) does not exist").matcher(compileOutput);
        String block = block("test_starters");
        while (pkg.find()) {
            Matcher entry = Pattern.compile("\"" + Pattern.quote(pkg.group(1)) + "\":\\s*\"([^\"]+)\"").matcher(block);
            if (entry.find()) return entry.group(1);
        }
        return null;
    }

    static String engine(String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\\s*(?:\"([^\"]+)\"|(\\d+))").matcher(block("engine"));
        if (!m.find()) throw new IllegalStateException("doctor.platform.upgrade.engine." + key + " is missing");
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    static String recipeName(String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\\s*\"([^\"]+)\"").matcher(block("recipes"));
        if (!m.find()) throw new IllegalStateException("doctor.platform.upgrade.recipes." + key + " is missing");
        return m.group(1);
    }

    /** The object under `doctor.platform.upgrade.<key>`, by brace matching. */
    static String block(String key) {
        int at = data.indexOf("\"" + key + "\"", data.indexOf("\"upgrade\":"));
        int open = data.indexOf('{', at), depth = 0;
        for (int i = open; i < data.length(); i++) {
            if (data.charAt(i) == '{') depth++;
            else if (data.charAt(i) == '}' && --depth == 0) return data.substring(open, i + 1);
        }
        throw new IllegalStateException("no " + key + " block");
    }

    // ── fixtures and assertions ──────────────────────────────────────────────

    /** A project root holding the Initializr's wrapper and the same three sources for either tool. */
    static Path fixture(Path app, Path starter, String tool) throws Exception {
        Files.createDirectories(app.resolve("src/main/java/com/acme"));
        Files.createDirectories(app.resolve("src/main/resources"));
        Files.createDirectories(app.resolve("src/test/java/com/acme"));
        if (tool.equals("maven")) {
            sh(starter, "bash", "-c", "cp -R mvnw .mvn '" + app + "'/");
        } else {
            sh(starter, "bash", "-c", "cp -R gradlew gradle settings.gradle '" + app + "'/");
        }
        write(app.resolve("src/main/java/com/acme/App.java"), """
                package com.acme;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @SpringBootApplication
                @RestController
                public class App {
                    public static void main(String[] args) { SpringApplication.run(App.class, args); }

                    @GetMapping("/ping")
                    String ping() { return "pong"; }
                }
                """);
        write(app.resolve("src/test/java/com/acme/AppTest.java"), """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                import org.springframework.boot.test.mock.mockito.MockBean;
                import org.springframework.test.web.servlet.MockMvc;

                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

                @WebMvcTest(App.class)
                class AppTest {
                    @Autowired MockMvc mvc;
                    @MockBean Runnable unused;

                    @Test
                    void pings() throws Exception { mvc.perform(get("/ping")).andExpect(status().isOk()); }
                }
                """);
        write(app.resolve("src/main/resources/application.properties"),
                "spring.data.mongodb.host=localhost\nspring.data.mongodb.port=27017\n");
        return app;
    }

    static String pomWithBoot(String boot) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>%s</version>
                    <relativePath/>
                  </parent>
                  <groupId>com.acme</groupId>
                  <artifactId>demo</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <properties>
                    <java.version>21</java.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-data-mongodb</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(boot);
    }

    static String buildGradleWithBoot(String boot) {
        return """
                plugins {
                	id 'java'
                	id 'org.springframework.boot' version '%s'
                	id 'io.spring.dependency-management' version '1.1.7'
                }

                group = 'com.acme'
                version = '0.0.1-SNAPSHOT'

                java {
                	toolchain {
                		languageVersion = JavaLanguageVersion.of(21)
                	}
                }

                repositories {
                	mavenCentral()
                }

                dependencies {
                	implementation 'org.springframework.boot:spring-boot-starter-web'
                	implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
                	testImplementation 'org.springframework.boot:spring-boot-starter-test'
                	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }

                tasks.named('test') {
                	useJUnitPlatform()
                }
                """.formatted(boot);
    }

    static void checks(Path app, String buildFile, String target, String starter) throws Exception {
        String build = Files.readString(app.resolve(buildFile));
        if (!build.contains(target)) fail(buildFile + " does not declare the target " + target + " after pass 1");
        else if (!build.contains(starter)) fail(buildFile + " does not carry the renamed starter " + starter);
        else System.out.println("✅ " + buildFile + ": target declared, starter renamed");
        String test = Files.readString(app.resolve("src/test/java/com/acme/AppTest.java"));
        if (test.contains("org.springframework.boot.test.mock.mockito.MockBean") || !test.contains("MockitoBean")) {
            fail("the type renames did not reach AppTest.java");
        } else {
            System.out.println("✅ the type renames reached AppTest.java");
        }
    }

    static void propertyRenamed(Path app) throws Exception {
        String props = Files.readString(app.resolve("src/main/resources/application.properties"));
        if (props.contains("spring.data.mongodb.host") || !props.contains("spring.mongodb.host")) {
            fail("pass 2 left application.properties as it was:\n" + props);
        } else {
            System.out.println("✅ application.properties: spring.data.mongodb.* renamed from the jar's own metadata");
        }
    }

    static void upgradeRecipe(Path app, Path work, String pass, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(javaCmd(), "-jar", HOOK.toString(), "upgrade-recipe", app.toString(), "--pass", pass));
        cmd.addAll(List.of(args));
        Run r = run(cmd, app, Map.of("CLAUDE_PROJECT_DIR", SOURCE.toString()));
        if (r.exit != 0) { fail("upgrade-recipe --pass " + pass + " exited " + r.exit + ":\n" + r.out); }
    }

    static void step(String name, Path dir, String... cmd) throws Exception {
        Run r = sh(dir, cmd);
        if (r.exit != 0) fail(name + " exited " + r.exit + ":\n" + tail(r.out));
        else System.out.println("✅ " + name);
    }

    static void commit(Path app) throws Exception {
        sh(app, "git", "init", "-q");
        sh(app, "git", "add", "-A");
        sh(app, "git", "-c", "user.email=ci@example.invalid", "-c", "user.name=ci", "commit", "-qm", "fixture");
    }

    static String curl(String url) throws Exception {
        Run r = sh(SOURCE, "curl", "-sS", "--fail", "-L", url);
        if (r.exit != 0) throw new IllegalStateException("curl " + url + " exited " + r.exit + ": " + r.out);
        return r.out;
    }

    static String lastTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(xml);
        if (!m.find()) throw new IllegalStateException("no <" + tag + "> in the metadata");
        return m.group(1);
    }

    static String tail(String out) {
        String[] lines = out.split("\n");
        return String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - 25), lines.length));
    }

    static Run sh(Path dir, String... cmd) throws Exception { return run(List.of(cmd), dir, Map.of()); }

    static Run run(List<String> cmd, Path cwd, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(proc.waitFor(), out);
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
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.delete(x); } catch (IOException ignored) { }
            }
        }
    }
}
