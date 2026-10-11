///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar schema` BLOCKS a settings file of `settings.match` — this
// repository's `.claude/settings.json` and project-bootstrap's `settings.json.example` — that
// does not carry `"<id>": false` in `enabledPlugins` for every `settings.blocked_plugins` entry
// of extensions.json, lets the shipped files through, and follows the list rather than any id
// spelled in the Java.
//
// Why this test exists: a project-level `false` is what keeps a plugin the developer enabled
// at user level from competing with the pipeline for automatic invocation, and nothing at
// runtime says when the line is gone — the plugin simply loads again. The same list sits in
// two files; the first edit to only one of them would drift in silence. Decision 0138.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class PluginBlockTest {

    static final String SETTINGS = ".claude/settings.json";
    static final String TEMPLATE = ".claude/skills/project-bootstrap/templates/settings.json.example";
    static final String EXT = ".claude/schemas/extensions.json";
    static final String ID = "superpowers@claude-plugins-official";

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        int failures = 0;

        failures += expect(hook, t -> { }, 0, null, "shipped settings files accepted");
        failures += expect(hook, t -> dropEntry(t, SETTINGS),
                2, SETTINGS + " — `enabledPlugins` lacks `\"" + ID + "\": false`",
                "entry missing from .claude/settings.json blocked");
        failures += expect(hook, t -> dropEntry(t, TEMPLATE),
                2, TEMPLATE + " — `enabledPlugins` lacks `\"" + ID + "\": false`",
                "entry missing from the project template blocked");
        failures += expect(hook, t -> edit(t, TEMPLATE, "\"" + ID + "\": false", "\"" + ID + "\": true"),
                2, "lacks `\"" + ID + "\": false`", "plugin switched to true blocked");
        failures += expect(hook, t -> edit(t, EXT, "\"blocked_plugins\": [",
                        "\"blocked_plugins\": [\n      \"no-such-plugin@example\","),
                2, "lacks `\"no-such-plugin@example\": false`",
                "an id added to the list is required in both files");
        failures += expect(hook, t -> emptyList(t),
                0, null, "an empty list requires nothing");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `schema` is NOT enforcing"
                    + " settings.blocked_plugins the way extensions.json declares.");
            System.exit(1);
        }
        System.out.println("✅ Plugin block enforced: a missing or true entry, in either"
                + " settings file, and an id added to the list blocked with exit 2; shipped"
                + " files and an empty list accepted.");
    }

    interface Mutation { void apply(Path tree) throws IOException; }

    static String read(Path f) throws IOException {
        return Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static void edit(Path tree, String rel, String from, String to) throws IOException {
        Path f = tree.resolve(rel);
        String s = read(f);
        if (!s.contains(from)) throw new IOException("fixture drifted: `" + from + "` not in " + rel);
        Files.writeString(f, s.replace(from, to), StandardCharsets.UTF_8);
    }

    /** Removes the `"<ID>": false,` line, the way a hand edit would. */
    static void dropEntry(Path tree, String rel) throws IOException {
        edit(tree, rel, "\"" + ID + "\": false,\n", "");
    }

    static void emptyList(Path tree) throws IOException {
        Path f = tree.resolve(EXT);
        String s = read(f);
        String e = s.replaceFirst("(\"blocked_plugins\"\\s*:\\s*)\\[[^\\]]*\\]", "$1[]");
        if (e.equals(s)) throw new IOException("fixture drifted: no blocked_plugins in " + EXT);
        Files.writeString(f, e, StandardCharsets.UTF_8);
    }

    /**
     * Copies this repository's `.claude/` into a throwaway directory, applies the mutation, and
     * runs `schema` there with no stdin — the whole-project sweep the Stop hook runs.
     */
    static int expect(Path hook, Mutation mutation, int wanted, String needle, String label)
            throws Exception {
        Path tmp = Files.createTempDirectory("archhook-plugins-ci");
        copyTree(Paths.get(".claude").toAbsolutePath(), tmp.resolve(".claude"));
        mutation.apply(tmp);

        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "schema");
        pb.environment().put("CLAUDE_PROJECT_DIR", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        deleteTree(tmp);

        if (exit == wanted && (needle == null || out.contains(needle))) {
            System.out.println("✅ " + label + " (exit " + exit + ")");
            return 0;
        }
        System.out.println("❌ " + label + " — expected exit " + wanted
                + (needle == null ? "" : " naming \"" + needle + "\"") + ", got " + exit);
        System.out.println(out);
        return 1;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
