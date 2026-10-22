///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar schema` BLOCKS a mod under `.claude/mods/` that would load
// half-way or not at all — an event that does not exist, a gating hook without `.catch`, a
// forbidden call, a program other than `java`, a mod the marketplace or the settings forgot,
// no tests, a deny marker that no longer matches the guard's wording — lets the shipped mods
// through, and stays silent in a tree with no `.claude/mods/`, which is every generated project.
//
// Why this test exists: every one of those defects is silent at runtime. The engine skips a
// hook it cannot run and says so only in a debug log; a mod nobody enabled simply never
// draws. `claude plugin validate` sees part of it, but only where the CLI is installed — the
// path-filtered `mods.yml` workflow. `schema` is what runs in every session, at Stop.
// Decision 0131.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class ModsSchemaTest {

    static final String MOD = ".claude/mods/nerviz-cockpit";
    static final String MODULE = MOD + "/hooks/register.ts";

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        int failures = 0;

        failures += expect(hook, t -> { }, 0, null, "shipped mods accepted");
        failures += expect(hook, t -> edit(t, MODULE, "on('turn.start'", "on('turn.begin'"),
                2, "no such event", "unknown event blocked");
        failures += expect(hook, t -> edit(t, MODULE,
                        "on('tool.call', { tool: 'Bash' }, async ($, e, next) => {",
                        "on('tool.call', { tool: 'Bash' }, async ($, e, next) => { /* x */"),
                0, null, "a comment inside a gating hook is not a defect");
        failures += expect(hook, t -> dropCatchAfter(t, "on('tool.call', { tool: 'Bash' }"),
                2, "has no `.catch(...)`", "gating hook without .catch blocked");
        failures += expect(hook, t -> edit(t, MODULE, "await readPhase($)\n    return result\n  })",
                        "await readPhase($)\n    await $.http.fetch('https://example.com')\n    return result\n  })"),
                2, "calls `$.http.fetch`", "forbidden call blocked");
        failures += expect(hook, t -> edit(t, MODULE, "['java', '-jar', JAR, 'doctor']", "['sh', '-c', 'doctor']"),
                2, "starts `sh`", "program other than java blocked");
        failures += expect(hook, t -> edit(t, ".claude/mods/.claude-plugin/marketplace.json",
                        "\"name\": \"nerviz-cockpit\"", "\"name\": \"nerviz-other\""),
                2, "not listed in", "mod missing from the marketplace blocked");
        failures += expect(hook, t -> edit(t, ".claude/settings.json",
                        "\"nerviz-cockpit@nerviz-mods\": true", "\"nerviz-cockpit@nerviz-mods\": false"),
                2, "`enabledPlugins` lacks", "mod not enabled blocked");
        failures += expect(hook, t -> deleteTree(t.resolve(MOD + "/tests")),
                2, "no *.test.ts", "mod without tests blocked");
        failures += expect(hook, t -> edit(t, ".claude/schemas/extensions.json",
                        "\"is outside its territory\"", "\"is beyond its territory\""),
                2, "no longer appears in", "stale deny marker blocked");
        failures += expect(hook, t -> deleteTree(t.resolve(".claude/mods")),
                0, null, "no .claude/mods — silent, as in a generated project");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `schema` is NOT checking"
                    + " the mods the way the `mods` block of extensions.json declares.");
            System.exit(1);
        }
        System.out.println("✅ Mods enforced: unknown event, missing .catch, forbidden call,"
                + " foreign program, unlisted or disabled mod, no tests and a stale marker"
                + " blocked with exit 2; shipped mods and a tree without mods accepted.");
    }

    interface Mutation { void apply(Path tree) throws IOException; }

    /**
     * Line endings normalized first: a Windows checkout with `core.autocrlf` holds the module
     * as CRLF, and a fixture spanning lines would never match it.
     */
    static String read(Path f) throws IOException {
        return Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static void edit(Path tree, String rel, String from, String to) throws IOException {
        Path f = tree.resolve(rel);
        String s = read(f);
        if (!s.contains(from)) throw new IOException("fixture drifted: `" + from + "` not in " + rel);
        Files.writeString(f, s.replace(from, to), StandardCharsets.UTF_8);
    }

    /** Removes the `.catch(...)` that closes the first hook opening with `opening`. */
    static void dropCatchAfter(Path tree, String opening) throws IOException {
        Path f = tree.resolve(MODULE);
        String s = read(f);
        int at = s.indexOf(opening);
        int c = s.indexOf(".catch(", at);
        int end = s.indexOf('\n', c);
        if (at < 0 || c < 0) throw new IOException("fixture drifted: no `.catch` after " + opening);
        Files.writeString(f, s.substring(0, c) + s.substring(end), StandardCharsets.UTF_8);
    }

    /**
     * Copies this repository's `.claude/` into a throwaway directory, applies the mutation, and
     * runs `schema` there with no stdin — the whole-project sweep the Stop hook runs.
     */
    static int expect(Path hook, Mutation mutation, int wanted, String needle, String label)
            throws Exception {
        Path tmp = Files.createTempDirectory("archhook-mods-ci");
        copyTree(Paths.get(".claude").toAbsolutePath(), tmp.resolve(".claude"));
        // `export.copy` names LICENSE and NOTICE at the root, outside `.claude/`
        for (String name : new String[] {"LICENSE", "NOTICE"}) {
            Files.copy(Paths.get(name), tmp.resolve(name));
        }
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
