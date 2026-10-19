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
// Decision 0176 adds what the first run did not see, each as a case the fixture would fail without:
// the baseline (`test-diff snapshot` before the bump, `compare` after, the same cases, none worse); the
// context (`@EnableCaching` needs the cache starter, Flyway needs its starter, both found only when the
// context is built — a `@SpringBootTest` run on its own, as the skill's step 8b does); a Spring Cloud
// BOM on the train of the old line, moved to the Initializr's; a pinned `spring-boot-starter-aop`
// whose pin must not survive the rename; and, on Gradle, a wrapper older than the Boot plugin accepts,
// refused by the new plugin and fixed the way step 5b does it.
//
// Decision 0177 adds one: the context test is an abstract `@SpringBootTest` base and one concrete
// subclass. Step 8b resolves the subclass, and the test fails by name when the base alone is what gets
// selected and nothing ran.
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
    /** A Gradle older than the Spring Boot plugin of the floor accepts: the project's old wrapper is the case. */
    static final String OLD_GRADLE = "8.10.2";

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

        // The skill's step 3: the metadata the Initializr serves for that target, and the train it lists.
        Path initializr = work.resolve("initializr.json");
        Run meta = sh(work, "curl", "-sS", "--fail", "-L", "-H", "Accept: " + engine("initializr_accept"),
                engine("initializr_dependencies"), "-o", initializr.toString());
        if (meta.exit != 0) { fail("the Initializr's dependencies metadata did not download:\n" + meta.out); System.exit(1); }
        String metadata = Files.readString(initializr, StandardCharsets.UTF_8);
        if (!metadata.contains("\"bootVersion\":\"" + target + "\"")) {
            fail("the Initializr's metadata is not for " + target + " — the mode would refuse it"); System.exit(1);
        }
        Matcher cloud = Pattern.compile("\"spring-cloud\"\\s*:\\s*\\{[^}]*\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(metadata);
        if (!cloud.find()) { fail("the Initializr lists no spring-cloud BOM — the case has nothing to move"); System.exit(1); }
        String newTrain = cloud.group(1), oldTrain = trainBefore(newTrain);
        System.out.println("Spring Cloud train " + oldTrain + " → " + newTrain + " (the Initializr's)");

        try {
            maven(work, oldBoot, target, initializr, oldTrain, newTrain);
            gradle(work, oldBoot, target, initializr, oldTrain, newTrain);
        } finally {
            deleteTree(work);
        }
        System.out.println(failures == 0
                ? "\n✅ platform-upgrade takes a project below the floor to the Initializr's line, on Maven and Gradle."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ── Maven ────────────────────────────────────────────────────────────────

    static void maven(Path work, String oldBoot, String target, Path initializr, String oldTrain, String newTrain) throws Exception {
        Path app = fixture(work.resolve("maven-app"), work.resolve("starter-maven"), "maven");
        write(app.resolve("pom.xml"), pomWithBoot(oldBoot, oldTrain));
        commit(app);
        String plugin = engine("maven_plugin");
        String version = lastTag(curl(engine("maven_plugin_metadata")), "release");
        System.out.println("maven engine " + plugin + ":" + version);

        // step 4b — the baseline: the tree as the person left it
        Run before = sh(app, "./mvnw", "-B", "-fae", "clean", "verify", "-Dmaven.test.failure.ignore=true");
        if (!before.out.contains("Tests run: 3, Failures: 0, Errors: 0")) {
            fail("maven — the fixture is not green on " + oldBoot + " before the upgrade; the baseline proves nothing:\n" + tail(before.out));
            return;
        }
        Path baseline = work.resolve("maven-baseline.json");
        testDiff(app, "snapshot", 0, "--out", baseline.toString());

        upgradeRecipe(app, work, "bump", "--target", target, "--initializr", initializr.toString(), "--out", work.resolve("maven-bump.yml").toString());
        step("maven pass 1", app, "./mvnw", "-B", "-q", plugin + ":" + version + ":run",
                "-Drewrite.configLocation=" + work.resolve("maven-bump.yml"),
                "-Drewrite.activeRecipes=" + recipeName("bump_name"));
        checks(app, "pom.xml", target, "spring-boot-starter-webmvc");
        String pom0 = Files.readString(app.resolve("pom.xml"));
        expectIn(pom0, "<spring-cloud.version>" + newTrain + "</spring-cloud.version>", "pom.xml: the Spring Cloud BOM moved to the Initializr's train");
        if (pom0.contains(oldTrain)) fail("pom.xml still carries the old Spring Cloud train " + oldTrain);
        expectIn(pom0, "<artifactId>spring-boot-starter-flyway</artifactId>", "pom.xml: flyway-core renamed to its starter");
        expectIn(pom0, "<artifactId>spring-boot-starter-cache</artifactId>", "pom.xml: the cache starter added, @EnableCaching being used");
        if (Pattern.compile("<artifactId>spring-boot-starter-(cache|aspectj)</artifactId>\\s*<version>").matcher(pom0).find()) {
            fail("pom.xml: an added or renamed Spring Boot starter carries a version the BOM manages");
        } else {
            System.out.println("✅ pom.xml: no version on the added cache starter nor on the renamed, once pinned, aspectj starter");
        }

        step("maven classpath", app, "./mvnw", "-B", "-q", "dependency:build-classpath", "-Dmdep.outputFile=target/nerviz-classpath.txt");
        if (!Files.isRegularFile(app.resolve("target/nerviz-classpath.txt"))) return;
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
        // The project's own `</dependencies>` is the first one; the BOM import's sits deeper, in dependencyManagement.
        int close = pom.indexOf("\n  </dependencies>");
        write(app.resolve("pom.xml"), pom.substring(0, close) + "\n    <dependency>\n      <groupId>" + ga[0]
                + "</groupId>\n      <artifactId>" + ga[1] + "</artifactId>\n      <scope>test</scope>\n    </dependency>" + pom.substring(close));

        // step 8b — the context, on its own: only the cache and the Flyway starters make it load
        if (!contextRun(app, true)) return;
        System.out.println("✅ maven: the context loads, the cache works and the migration ran");

        // step 9 — verify, then compare against the baseline
        Run verify = sh(app, "./mvnw", "-B", "-fae", "clean", "verify", "-Dmaven.test.failure.ignore=true");
        if (verify.exit != 0 || !verify.out.contains("Tests run: 3, Failures: 0")) { fail("maven verify after the test starter:\n" + tail(verify.out)); return; }
        Run compare = testDiff(app, "compare", 0, "--baseline", baseline.toString());
        if (!compare.out.contains("3 test case(s) now, 3 in the baseline")) fail("maven — the comparison saw other cases than the baseline:\n" + compare.out);
        else System.out.println("✅ maven: " + oldBoot + " → " + target + ", verify green, no test worse than the baseline");
    }

    // ── Gradle ───────────────────────────────────────────────────────────────

    static void gradle(Path work, String oldBoot, String target, Path initializr, String oldTrain, String newTrain) throws Exception {
        Path starter = work.resolve("starter-gradle");
        Files.createDirectories(starter);
        sh(starter, "bash", "-c", "curl -sS --fail -L " + engine("initializr_starter")
                + " -d type=gradle-project -d language=java | tar -xzf - -C " + starter);
        Path app = fixture(work.resolve("gradle-app"), starter, "gradle");
        write(app.resolve("build.gradle"), buildGradleWithBoot(oldBoot, oldTrain));
        // A wrapper older than the Boot plugin of the target accepts: an old project has one, and the Initializr
        // never serves it, so the version is the case itself, not a choice of the test's.
        Path props = app.resolve("gradle/wrapper/gradle-wrapper.properties");
        write(props, Files.readString(props).replaceAll("gradle-[0-9.]+-bin\\.zip", "gradle-" + OLD_GRADLE + "-bin.zip"));
        commit(app);
        String pluginVersion = gradlePluginVersion();
        System.out.println("gradle engine " + engine("gradle_plugin") + ":" + pluginVersion);
        Files.copy(SKILL_TEMPLATES.resolve("rewrite-init.gradle.example"), work.resolve("rewrite-init.gradle"));
        Files.copy(SKILL_TEMPLATES.resolve("classpath-init.gradle.example"), work.resolve("classpath-init.gradle"));

        // step 4b — the baseline, on the old wrapper
        Run before = sh(app, "./gradlew", "--no-daemon", "clean", "build", "--continue");
        Path any = app.resolve("build/test-results/test");
        if (before.exit != 0 || !Files.isDirectory(any)) {
            fail("gradle — the fixture is not green on " + oldBoot + " before the upgrade; the baseline proves nothing:\n" + tail(before.out));
            return;
        }
        Path baseline = work.resolve("gradle-baseline.json");
        testDiff(app, "snapshot", 0, "--out", baseline.toString());

        upgradeRecipe(app, work, "bump", "--target", target, "--initializr", initializr.toString(), "--out", work.resolve("gradle-bump.yml").toString());
        step("gradle pass 1", app, "./gradlew", "--no-daemon", "-q", "--init-script", work.resolve("rewrite-init.gradle").toString(),
                "-Dnerviz.plugin.version=" + pluginVersion, "-Dnerviz.recipe=" + work.resolve("gradle-bump.yml"),
                "-Dnerviz.recipe.name=" + recipeName("bump_name"), "rewriteRun");
        checks(app, "build.gradle", target, "spring-boot-starter-webmvc");
        String gradle0 = Files.readString(app.resolve("build.gradle"));
        expectIn(gradle0, "set('springCloudVersion', \"" + newTrain + "\")", "build.gradle: the Spring Cloud BOM moved to the Initializr's train");
        if (gradle0.contains(oldTrain)) fail("build.gradle still carries the old Spring Cloud train " + oldTrain);
        expectIn(gradle0, "org.springframework.boot:spring-boot-starter-flyway", "build.gradle: flyway-core renamed to its starter");
        if (!Pattern.compile("org\\.springframework\\.boot:spring-boot-starter-cache['\"]").matcher(gradle0).find()) {
            fail("build.gradle: the cache starter is missing, or carries a version the BOM manages:\n" + gradle0);
        } else if (Pattern.compile("spring-boot-starter-aspectj:").matcher(gradle0).find()) {
            fail("build.gradle: the pinned aop starter kept its pin after the rename:\n" + gradle0);
        } else {
            System.out.println("✅ build.gradle: the cache starter added and the renamed aspectj starter, neither with a version");
        }

        // step 5b — the new plugin refuses the old wrapper; the skill's way out
        if (!gradleWrapper(app)) return;

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

        // step 8b — the context, on its own
        if (!contextRun(app, false)) return;
        System.out.println("✅ gradle: the context loads, the cache works and the migration ran");

        // step 9 — build, then compare against the baseline
        Run full = sh(app, "./gradlew", "--no-daemon", "clean", "build", "--continue");
        if (full.exit != 0) { fail("gradle build after the test starter:\n" + tail(full.out)); return; }
        Run compare = testDiff(app, "compare", 0, "--baseline", baseline.toString());
        if (!compare.out.contains("3 test case(s) now, 3 in the baseline")) fail("gradle — the comparison saw other cases than the baseline:\n" + compare.out);
        else System.out.println("✅ gradle: " + oldBoot + " → " + target + ", build green, no test worse than the baseline");
    }

    /**
     * The skill's step 8b. The classes annotated `@SpringBootTest` and every class that extends one, down to
     * the concrete ones; the context is run on those, and a report for each must exist afterwards. The
     * abstract base is selected first on purpose: it must run nothing, which is the silent failure the
     * step names (Maven with `failIfNoSpecifiedTests=false` exits 0 there). If it ran the subclass, the
     * case would prove nothing.
     */
    static boolean contextRun(Path app, boolean maven) throws Exception {
        Path tests = app.resolve("src/test/java");
        Map<String, String> source = new TreeMap<>();
        try (Stream<Path> files = Files.walk(tests)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                source.put(f.getFileName().toString().replace(".java", ""), Files.readString(f));
            }
        }
        Set<String> found = new TreeSet<>();
        source.forEach((name, text) -> { if (text.contains("@SpringBootTest")) found.add(name); });
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String base : List.copyOf(found)) {
                for (Map.Entry<String, String> e : source.entrySet()) {
                    if (!found.contains(e.getKey()) && Pattern.compile("extends\\s+" + base + "\\b").matcher(e.getValue()).find()) {
                        found.add(e.getKey());
                        grew = true;
                    }
                }
            }
        }
        List<String> concrete = found.stream().filter(n -> !Pattern.compile("abstract\\s+class\\s+" + n + "\\b").matcher(source.get(n)).find()).toList();
        if (!concrete.equals(List.of("ContextTest"))) {
            fail((maven ? "maven" : "gradle") + " — step 8b's discovery found " + concrete + " (all " + found + "), expected only the concrete ContextTest");
            return false;
        }
        String tool = maven ? "maven" : "gradle";
        Path report = app.resolve(maven ? "target/surefire-reports" : "build/test-results/test").resolve("TEST-com.acme.ContextTest.xml");
        Files.deleteIfExists(report);
        if (maven) sh(app, "./mvnw", "-B", "-q", "test", "-Dtest=ContextBase", "-Dsurefire.failIfNoSpecifiedTests=false");
        else sh(app, "./gradlew", "--no-daemon", "-q", "test", "--tests", "com.acme.ContextBase");
        if (Files.exists(report)) { fail(tool + " — selecting the abstract base ran the subclass; the 8b case proves nothing"); return false; }
        Run context = maven
                ? sh(app, "./mvnw", "-B", "-q", "test", "-Dtest=" + String.join(",", concrete), "-Dsurefire.failIfNoSpecifiedTests=false")
                : sh(app, "./gradlew", "--no-daemon", "-q", "test", "--tests", "com.acme." + concrete.get(0));
        if (context.exit != 0) { fail(tool + " — the @SpringBootTest class is red after the upgrade:\n" + tail(context.out)); return false; }
        if (!Files.exists(report)) { fail(tool + " — the context run exited 0 and wrote no report: the selection ran nothing"); return false; }
        return true;
    }

    /**
     * The skill's step 5b. Ask the build; it must be refused here (an old wrapper, the new plugin) or the case
     * proves nothing. Then the lowest major the message accepts, its newest final release, the wrapper's
     * properties, `./gradlew wrapper`, and the build is asked again.
     */
    static boolean gradleWrapper(Path app) throws Exception {
        Run help = sh(app, "./gradlew", "--no-daemon", "-q", "help");
        if (help.exit == 0) { fail("gradle — the new plugin accepted Gradle " + OLD_GRADLE + "; step 5b has nothing to fix and the case proves nothing"); return false; }
        Matcher accepted = Pattern.compile("requires Gradle (\\d+)\\.x").matcher(help.out);
        if (!accepted.find()) { fail("gradle — the refusal names no accepted Gradle line, step 5b cannot read it:\n" + tail(help.out)); return false; }
        String major = accepted.group(1), best = null;
        Matcher entry = Pattern.compile("\\{[^{}]*\\}").matcher(curl(engine("gradle_versions_all")));
        while (entry.find()) {
            String o = entry.group();
            Matcher v = Pattern.compile("\"version\"\\s*:\\s*\"(" + major + "\\.[0-9.]+)\"").matcher(o);
            if (!v.find() || !o.matches("(?s).*\"final\"\\s*:\\s*true.*") || !o.matches("(?s).*\"broken\"\\s*:\\s*false.*")) continue;
            if (best == null || compareVersions(v.group(1), best) > 0) best = v.group(1);
        }
        if (best == null) { fail("gradle — no final release of Gradle " + major + " in " + engine("gradle_versions_all")); return false; }
        Path props = app.resolve("gradle/wrapper/gradle-wrapper.properties");
        String text = Files.readString(props);
        String url = engine("gradle_distribution").replace("{version}", best).replace(":", "\\:");
        write(props, text.replaceAll("distributionUrl=.*", Matcher.quoteReplacement("distributionUrl=" + url)));
        step("gradle wrapper → " + best, app, "./gradlew", "--no-daemon", "-q", "wrapper", "--gradle-version", best);
        Run again = sh(app, "./gradlew", "--no-daemon", "-q", "help");
        if (again.exit != 0) { fail("gradle — still refused on Gradle " + best + ":\n" + tail(again.out)); return false; }
        System.out.println("✅ gradle: wrapper " + OLD_GRADLE + " → " + best + ", the build configures");
        return true;
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

    /** The newest GA Spring Cloud train strictly before `train` (compared by year and minor), from Maven Central. */
    static String trainBefore(String train) throws Exception {
        int[] now = trainKey(train);
        String best = null;
        Matcher m = Pattern.compile("<version>(\\d{4}\\.\\d+\\.\\d+)</version>").matcher(
                curl("https://repo.maven.apache.org/maven2/org/springframework/cloud/spring-cloud-dependencies/maven-metadata.xml"));
        while (m.find()) {
            int[] k = trainKey(m.group(1));
            boolean before = k[0] < now[0] || (k[0] == now[0] && k[1] < now[1]);
            if (before && (best == null || compareVersions(m.group(1), best) > 0)) best = m.group(1);
        }
        if (best == null) throw new IllegalStateException("no Spring Cloud train before " + train + " on Maven Central");
        return best;
    }

    static int[] trainKey(String v) {
        String[] p = v.split("\\.");
        return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) return c;
        }
        return 0;
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
        // What only a context shows: @EnableCaching with no cache module, a migration with no Flyway.
        write(app.resolve("src/main/java/com/acme/CacheConfig.java"), """
                package com.acme;

                import org.springframework.cache.annotation.EnableCaching;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                @EnableCaching
                public class CacheConfig { }
                """);
        write(app.resolve("src/main/java/com/acme/Lookup.java"), """
                package com.acme;

                import org.springframework.cache.annotation.Cacheable;
                import org.springframework.stereotype.Service;

                @Service
                public class Lookup {
                    @Cacheable("greetings")
                    public String greet(String name) { return "hi " + name; }
                }
                """);
        write(app.resolve("src/main/resources/db/migration/V1__greeting.sql"),
                "create table greeting (id int primary key);\ninsert into greeting values (1);\n");
        // An abstract base and one concrete subclass, the shape step 8b has to resolve: the annotation is
        // inherited, so only the subclass is a test that builds the context (decision 0177).
        write(app.resolve("src/test/java/com/acme/ContextTest.java"), """
                package com.acme;

                class ContextTest extends ContextBase { }
                """);
        write(app.resolve("src/test/java/com/acme/ContextBase.java"), """
                package com.acme;

                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.jdbc.core.JdbcTemplate;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                @SpringBootTest
                abstract class ContextBase {
                    @Autowired Lookup lookup;
                    @Autowired JdbcTemplate jdbc;

                    @Test
                    void cacheWorks() { assertEquals("hi a", lookup.greet("a")); }

                    @Test
                    void migrationRan() { assertEquals(1, jdbc.queryForObject("select count(*) from greeting", Integer.class)); }
                }
                """);
        return app;
    }

    static String pomWithBoot(String boot, String train) {
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
                    <spring-cloud.version>%s</spring-cloud.version>
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
                      <artifactId>spring-boot-starter-jdbc</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.flywaydb</groupId>
                      <artifactId>flyway-core</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.h2database</groupId>
                      <artifactId>h2</artifactId>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-aop</artifactId>
                      <version>3.3.4</version>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.cloud</groupId>
                        <artifactId>spring-cloud-dependencies</artifactId>
                        <version>${spring-cloud.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(boot, train);
    }

    static String buildGradleWithBoot(String boot, String train) {
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

                ext {
                	set('springCloudVersion', "%s")
                }

                dependencies {
                	implementation 'org.springframework.boot:spring-boot-starter-web'
                	implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
                	implementation 'org.springframework.boot:spring-boot-starter-jdbc'
                	implementation 'org.flywaydb:flyway-core'
                	runtimeOnly 'com.h2database:h2'
                	implementation 'org.springframework.boot:spring-boot-starter-aop:3.3.4'
                	testImplementation 'org.springframework.boot:spring-boot-starter-test'
                	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }

                dependencyManagement {
                	imports {
                		mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
                	}
                }

                tasks.named('test') {
                	useJUnitPlatform()
                }
                """.formatted(boot, train);
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

    /** `ArchHook.jar test-diff <verb>` on the project; the exit it must give is checked, the run returned. */
    static Run testDiff(Path app, String verb, int expectedExit, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(javaCmd(), "-jar", HOOK.toString(), "test-diff", verb, app.toString()));
        cmd.addAll(List.of(args));
        Run r = run(cmd, app, Map.of("CLAUDE_PROJECT_DIR", SOURCE.toString()));
        if (r.exit != expectedExit) fail("test-diff " + verb + " exited " + r.exit + ", expected " + expectedExit + ":\n" + r.out);
        else System.out.println("✅ test-diff " + verb + ": " + r.out.trim().split("\n")[0].replaceAll("/\\S+", "…"));
        return r;
    }

    static void expectIn(String text, String needle, String name) {
        if (text.contains(needle)) System.out.println("✅ " + name);
        else fail(name + " — expected: " + needle + "\n" + text);
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
