///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar upgrade-recipe` writes the OpenRewrite recipe that takes a project
// below `doctor.platform.min_line` up to it. `--pass bump`: the version bump with the target as the
// option, one coordinate rename per `renames.dependencies` entry and one type rename per
// `renames.types` entry of the production data, byte for byte the same on a second run; exit 2 and
// nothing written on a target below the floor, a malformed one, a project already at the floor, a
// `recipes` block missing, and an option placeholder no value fills. `--pass properties`: a property
// rename — one recipe for `.properties`, one for YAML — for every entry a jar's
// `spring-configuration-metadata.json` declares as an error with a replacement, in key order, the
// first jar winning a key; a warning, an error with no replacement and a jar with no metadata
// skipped; a classpath file read with a newline and with the OS path separator; exit 0 and nothing
// written when no jar declares a rename.
//
// Why this test exists: the mode is Form 7c, and a mode that throws exits 0 through `main`'s catch —
// it would look like a pass. The OpenRewrite engine that reads the file is proven apart, by the
// `platform-upgrade` job of `templates.yml`, which runs the recipe on a real project; this test
// holds what that job cannot hold cheaply: every refusal, and every shape of metadata. Decision 0175.
//
// The data under test is production: `doctor.platform` of this repository's extensions.json, copied
// into a throwaway source directory the jar reads through CLAUDE_PROJECT_DIR, as `export` is run.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.stream.*;

public class UpgradeRecipeTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final Path SCHEMA = Paths.get(".claude/schemas/extensions.json");
    static String floorMajor;
    static String floorLine;

    public static void main(String[] args) throws Exception {
        String data = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        Matcher floor = Pattern.compile("\"min_line\":\\s*\"(\\d+)\\.(\\d+)\"").matcher(data);
        if (!floor.find()) { fail("no doctor.platform.min_line in the production data"); System.exit(1); }
        floorMajor = floor.group(1);
        floorLine = floor.group(1) + "." + floor.group(2);
        String target = floorLine + ".0";
        String below = (Integer.parseInt(floorMajor) - 1) + ".5.0";
        String atFloor = floorLine + ".1";

        Path source = Files.createTempDirectory("archhook-upgrade-source");
        write(source.resolve(".claude/schemas/extensions.json"), data);
        Path work = Files.createTempDirectory("archhook-upgrade-work");

        // bump — what it writes
        Path pom = project("pom.xml", starterParent(below));
        Path out = work.resolve("bump.yml");
        Run r = upgrade(source, pom, "--pass", "bump", "--target", target, "--out", out.toString());
        if (r.exit != 0 || !Files.isRegularFile(out)) {
            fail("bump on a project below the floor — expected exit 0 and a file, got " + r.exit + "\n" + r.out);
        } else {
            String yaml = Files.readString(out, StandardCharsets.UTF_8);
            expectContains(yaml, "name: org.nerviz.platform.Bump", "bump names its recipe");
            expectContains(yaml, "- org.openrewrite.maven.UpgradeParentVersion:\n      groupId: \"org.springframework.boot\"\n"
                    + "      artifactId: \"spring-boot-starter-parent\"\n      newVersion: \"" + target + "\"",
                    "bump carries the target as the parent's version");
            expectContains(yaml, "- org.openrewrite.gradle.plugins.UpgradePluginVersion:", "bump covers a Gradle plugin");
            Matcher pair = Pattern.compile("\"from\":\\s*\"([^\"]+)\",\\s*\"to\":\\s*\"([^\"]+)\"").matcher(renamesBlock(data));
            int pairs = 0;
            while (pair.find()) {
                pairs++;
                String from = pair.group(1), to = pair.group(2);
                if (from.contains(":")) {
                    String[] f = from.split(":"), t = to.split(":");
                    expectContains(yaml, "oldGroupId: \"" + f[0] + "\"\n      oldArtifactId: \"" + f[1]
                            + "\"\n      newGroupId: \"" + t[0] + "\"\n      newArtifactId: \"" + t[1] + "\"",
                            "bump renames the coordinate " + from);
                } else {
                    expectContains(yaml, "oldFullyQualifiedTypeName: \"" + from + "\"\n      newFullyQualifiedTypeName: \""
                            + to + "\"", "bump renames the type " + from);
                }
            }
            if (pairs == 0) fail("the production data holds no rename pair — the cases above proved nothing");
            if (yaml.contains("${")) fail("bump left a placeholder in the recipe");
            Path again = work.resolve("bump-again.yml");
            upgrade(source, pom, "--pass", "bump", "--target", target, "--out", again.toString());
            if (!yaml.equals(Files.readString(again, StandardCharsets.UTF_8))) fail("bump is not deterministic");
            else System.out.println("✅ bump is deterministic");
        }

        // bump — what it refuses, writing nothing
        refused(source, pom, work, "a target below the floor", below, "is below the floor", "bump");
        refused(source, pom, work, "a target that is not major.minor.patch", floorLine, "--target is a Spring Boot version", "bump");
        Path atFloorPom = project("pom.xml", starterParent(atFloor));
        refused(source, atFloorPom, work, "a project already at the floor",
                target, "is already on Spring Boot " + floorLine, "bump");
        refusedBySource("a source with no recipes block", data.replace("\"recipes\":", "\"recipes_gone\":"),
                work, pom, target, "No `doctor.platform.upgrade.recipes` block");
        refusedBySource("an option placeholder no value fills", data.replace("\"newVersion\": \"${target}\"",
                "\"newVersion\": \"${nothing}\""), work, pom, target, "keeps a placeholder with no value");

        // properties — one jar of each shape
        Path jars = Files.createTempDirectory("archhook-upgrade-jars");
        Path a = jar(jars.resolve("a.jar"), "{\"properties\":["
                + prop("spring.alpha.host", "error", "spring.beta.host")
                + "," + prop("spring.alpha.port", "error", "spring.beta.port")
                + "," + prop("spring.warned.key", "warning", "spring.other.key")
                + "," + prop("spring.gone.key", "error", null)
                + "," + prop("spring.same.key", "error", "spring.same.key")
                + "," + "{\"name\":\"spring.plain.key\"}"
                + "]}");
        Path b = jar(jars.resolve("b.jar"), "{\"properties\":["
                + prop("spring.alpha.host", "error", "spring.second-wins.host")
                + "," + prop("spring.aaa.first", "error", "spring.zzz.first")
                + "]}");
        Path none = jar(jars.resolve("none.jar"), null);
        Path cp = work.resolve("cp-newline.txt");
        write(cp, a + "\n\n" + none + "\n" + jars.resolve("missing.jar") + "\n" + work.resolve("not-a-jar.txt") + "\n" + b + "\n");
        Path props = work.resolve("props.yml");
        r = upgrade(source, pom, "--pass", "properties", "--classpath", cp.toString(), "--out", props.toString());
        if (r.exit != 0 || !Files.isRegularFile(props)) {
            fail("properties — expected exit 0 and a file, got " + r.exit + "\n" + r.out);
        } else {
            String yaml = Files.readString(props, StandardCharsets.UTF_8);
            expectContains(yaml, "name: org.nerviz.platform.Properties", "properties names its recipe");
            for (String type : List.of("org.openrewrite.properties.ChangePropertyKey", "org.openrewrite.yaml.ChangePropertyKey")) {
                expectContains(yaml, "- " + type + ":\n      oldPropertyKey: \"spring.alpha.host\"\n      newPropertyKey: \"spring.beta.host\"",
                        "properties renames an error with a replacement, for " + type);
            }
            expectContains(yaml, "oldPropertyKey: \"spring.alpha.port\"", "properties keeps a second entry of the first jar");
            for (String skipped : List.of("spring.warned.key", "spring.gone.key", "spring.same.key", "spring.plain.key",
                    "spring.second-wins.host")) {
                if (yaml.contains(skipped)) fail("properties wrote a rename for `" + skipped + "`, which no jar declares as one");
            }
            if (yaml.indexOf("spring.aaa.first") > yaml.indexOf("spring.alpha.host")) {
                fail("properties are not in key order — the recipe would change with the order of the classpath");
            } else if (!yaml.contains("spring.aaa.first")) {
                fail("properties — the second jar's own entry is missing");
            } else {
                System.out.println("✅ properties: the first jar wins a key, the skipped shapes stay out, key order");
            }
        }

        // properties — the OS path separator
        Path cpSep = work.resolve("cp-separator.txt");
        write(cpSep, a + File.pathSeparator + b);
        Path propsSep = work.resolve("props-sep.yml");
        r = upgrade(source, pom, "--pass", "properties", "--classpath", cpSep.toString(), "--out", propsSep.toString());
        if (r.exit == 0 && Files.isRegularFile(propsSep)
                && Files.readString(propsSep, StandardCharsets.UTF_8).equals(Files.readString(props, StandardCharsets.UTF_8))) {
            System.out.println("✅ properties: a classpath joined by the path separator reads the same as one by newline");
        } else {
            fail("properties — a classpath joined by the path separator gave another recipe than one by newline");
        }

        // properties — no jar declares a rename
        Path cpNone = work.resolve("cp-none.txt");
        write(cpNone, none.toString() + "\n");
        Path propsNone = work.resolve("props-none.yml");
        r = upgrade(source, pom, "--pass", "properties", "--classpath", cpNone.toString(), "--out", propsNone.toString());
        if (r.exit == 0 && !Files.exists(propsNone) && r.out.contains("nothing written")) {
            System.out.println("✅ properties: no jar declares a rename — exit 0, nothing written");
        } else {
            fail("properties with no rename — expected exit 0, no file and \"nothing written\", got " + r.exit + "\n" + r.out);
        }

        // usage
        r = upgrade(source, pom, "--pass", "bump", "--out", work.resolve("x.yml").toString());
        if (r.exit == 2 && r.out.contains("usage:") && !Files.exists(work.resolve("x.yml"))) {
            System.out.println("✅ bump with no --target: usage, exit 2");
        } else {
            fail("bump with no --target — expected usage and exit 2, got " + r.exit);
        }

        deleteTree(source); deleteTree(work); deleteTree(jars); deleteTree(pom.getParent()); deleteTree(atFloorPom.getParent());
        System.out.println(failures == 0
                ? "\n✅ upgrade-recipe writes the bump and the property renames from data, and refuses what it cannot spell."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    record Run(int exit, String out) { }

    /** The `renames` object of `doctor.platform.upgrade`, by brace matching: other blocks of the file also hold from/to pairs. */
    static String renamesBlock(String data) {
        int at = data.indexOf("\"renames\"", data.indexOf("\"upgrade\":"));
        int open = data.indexOf('{', at), depth = 0;
        for (int i = open; i < data.length(); i++) {
            if (data.charAt(i) == '{') depth++;
            else if (data.charAt(i) == '}' && --depth == 0) return data.substring(open, i + 1);
        }
        throw new IllegalStateException("no renames block in doctor.platform.upgrade");
    }

    static String starterParent(String v) {
        return "<project><parent><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-parent</artifactId><version>" + v + "</version></parent></project>\n";
    }

    static String prop(String name, String level, String replacement) {
        return "{\"name\":\"" + name + "\",\"deprecation\":{\"level\":\"" + level + "\""
                + (replacement == null ? "" : ",\"replacement\":\"" + replacement + "\"") + "}}";
    }

    static void refused(Path source, Path project, Path work, String name, String target, String needle, String pass) throws Exception {
        Path out = work.resolve("refused.yml");
        Files.deleteIfExists(out);
        Run r = upgrade(source, project, "--pass", pass, "--target", target, "--out", out.toString());
        if (r.exit != 2) fail(name + " — expected exit 2, got " + r.exit + "\n" + r.out);
        else if (Files.exists(out)) fail(name + " — a recipe was written");
        else if (!r.out.contains(needle)) fail(name + " — the message lacks \"" + needle + "\"\n" + r.out);
        else System.out.println("✅ refused: " + name);
    }

    static void refusedBySource(String name, String brokenData, Path work, Path project, String target, String needle) throws Exception {
        Path broken = Files.createTempDirectory("archhook-upgrade-broken");
        write(broken.resolve(".claude/schemas/extensions.json"), brokenData);
        refused(broken, project, work, name, target, needle, "bump");
        deleteTree(broken);
    }

    static Run upgrade(Path source, Path project, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(javaCmd(), "-jar", HOOK.toString(), "upgrade-recipe",
                projectRoot(project)));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(source.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", source.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(proc.waitFor(), out);
    }

    /** `project(..)` returns the build file; the project is its directory. */
    static String projectRoot(Path buildFile) { return buildFile.getParent().toString(); }

    static Path project(String file, String content) throws Exception {
        Path p = Files.createTempDirectory("archhook-upgrade-project");
        write(p.resolve(file), content);
        return p.resolve(file);
    }

    static Path jar(Path path, String metadata) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("pkg/Placeholder.class"));
            jar.write(new byte[] {0});
            jar.closeEntry();
            if (metadata != null) {
                jar.putNextEntry(new JarEntry("META-INF/spring-configuration-metadata.json"));
                jar.write(metadata.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return path;
    }

    static void expectContains(String text, String needle, String name) {
        if (text.contains(needle)) {
            System.out.println("✅ " + name);
        } else {
            fail(name + " — expected:\n" + needle);
        }
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
