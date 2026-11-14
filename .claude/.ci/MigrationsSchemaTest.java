///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.java schema` BLOCKS a `migrations` entry that `arch-adopt`
// would silently never show — a misspelled blueprint id, a missing field, an id reused by
// a second entry, an id out of pattern — and lets the shipped block through.
//
// Why this test exists: `arch-adopt` reads the block with the model, after an update, on
// someone else's machine. A bad entry there fails with no error at all — the project is
// simply never told its code was generated under an older convention. Decision 0104.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class MigrationsSchemaTest {

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        String json = Files.readString(Paths.get(".claude/schemas/extensions.json"),
                StandardCharsets.UTF_8);
        int start = json.indexOf("\"migrations\"");
        int end = json.indexOf("\n  },", start);
        if (start < 0 || end < 0) {
            System.err.println("❌ no `migrations` block in extensions.json — nothing to test.");
            System.exit(1);
        }
        String block = json.substring(start, end);
        String entry = block.substring(block.indexOf("{", block.indexOf("\"entries\"")),
                block.lastIndexOf("}") + 1);

        int failures = 0;
        failures += expect(hook, json, true, 0, null,
                "shipped block accepted");
        failures += expect(hook, mutate(json, start, end, block.replace("\"hexagonal\"]", "\"hexagnal\"]")),
                true, 2, "blueprint `hexagnal` does not exist",
                "misspelled blueprint blocked");
        failures += expect(hook, mutate(json, start, end, block.replace("\"prompt\":", "\"promt\":")),
                true, 2, "has no `prompt`",
                "missing prompt blocked");
        failures += expect(hook, mutate(json, start, end, block.replace(entry, entry + ",\n" + entry)),
                true, 2, "id already used",
                "reused id blocked");
        failures += expect(hook, mutate(json, start, end,
                        block.replace("\"id\": \"use-case-subpackage-per-aggregate\"", "\"id\": \"Use_Case\"")),
                true, 2, "id does not match",
                "id out of pattern blocked");
        // A generated project holds its active blueprint alone: the existence check would
        // fail every entry naming another one, so it runs only where the catalog is.
        failures += expect(hook, mutate(json, start, end, block.replace("\"hexagonal\"]", "\"hexagnal\"]")),
                false, 0, null,
                "blueprint ids not checked outside this repository");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `schema` is NOT checking"
                    + " the migrations block the way extensions.json declares.");
            System.exit(1);
        }
        System.out.println("✅ Migrations block enforced: misspelled blueprint, missing field,"
                + " reused id and bad id blocked with exit 2; shipped block and a project's"
                + " copy accepted.");
    }

    static String mutate(String json, int start, int end, String block) {
        return json.substring(0, start) + block + json.substring(end);
    }

    /**
     * Copies this repository's `.claude/` into a throwaway directory, writes the given
     * `extensions.json` over it, and runs `schema` there. `withCatalog = false` deletes
     * `.claude/blueprints/`, which is what makes the tree read as a generated project
     * (`export.source_marker` no longer resolves).
     */
    static int expect(Path hook, String json, boolean withCatalog, int wanted, String needle,
                      String label) throws Exception {
        Path tmp = Files.createTempDirectory("archhook-migrations-ci");
        copyTree(Paths.get(".claude").toAbsolutePath(), tmp.resolve(".claude"));
        // `export.copy` names LICENSE and NOTICE at the root, outside `.claude/`
        for (String name : new String[] {"LICENSE", "NOTICE"}) {
            Files.copy(Paths.get(name), tmp.resolve(name));
        }
        Files.writeString(tmp.resolve(".claude/schemas/extensions.json"), json,
                StandardCharsets.UTF_8);
        if (!withCatalog) deleteTree(tmp.resolve(".claude/blueprints"));

        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "schema");
        pb.environment().put("CLAUDE_PROJECT_DIR", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();                 // no stdin: sweeps the whole project
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
