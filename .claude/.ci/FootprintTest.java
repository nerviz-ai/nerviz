///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar footprint` counts what loads every session the way the runtime
// loads it — an `@path` import outside code counts, one in backticks does not; a rule without
// `paths` counts, one with `paths` does not; a skill with `disable-model-invocation: true` stays
// out of the listing — marks a body past `footprint.limits`, a reference with no contents list
// and a tool-event hook with neither `matcher` nor `if`, sums an agent's preloaded skills, and
// writes nothing while doing so.
//
// Why this test exists: `harness-lessons` judges the harness from these numbers and is told
// never to recompute them. A miscount here — a manual skill counted in the listing, a backticked
// path counted as an import — becomes a finding with a saving that does not exist, filed
// upstream as evidence. Decision 0188.
//
// Builds a fixture tree with the repository's real extensions.json and runs the jar against
// it through CLAUDE_PROJECT_DIR: the bytes the skill's injection runs, not the source.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class FootprintTest {

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path root = Files.createTempDirectory("footprint-");
        Files.createDirectories(root.resolve(".claude/schemas"));
        Files.copy(Paths.get(".claude/schemas/extensions.json"),
                root.resolve(".claude/schemas/extensions.json"));

        write(root, "CLAUDE.md", "# Fixture\n\nSee @docs/imported.md and `@docs/ignored.md`.\n");
        write(root, "docs/imported.md", "one\ntwo\n");
        write(root, "docs/ignored.md", "never counted\n");
        write(root, ".claude/rules/always.md", "# Always\n\nLoads at launch.\n");
        write(root, ".claude/rules/scoped.md", "---\npaths:\n  - \"**/src/**\"\n---\n\n# Scoped\n");
        write(root, ".claude/skills/auto/SKILL.md", "---\nname: auto\ndescription: >\n"
                + "  Folded description\n  on two lines.\nmodel: opus\n---\n"
                + "line\n".repeat(501));
        write(root, ".claude/skills/auto/references/long.md", "row\n".repeat(120));
        write(root, ".claude/skills/manual/SKILL.md", "---\nname: manual\n"
                + "description: Never listed.\ndisable-model-invocation: true\nmodel: sonnet\n---\nbody\n");
        write(root, ".claude/agents/worker.md", "---\nname: worker\ndescription: Works.\n"
                + "skills: [auto]\nmodel: sonnet\ntools: Read\n---\n\n# Worker\n");
        write(root, ".claude/settings.json", "{\"hooks\":{"
                + "\"PreToolUse\":[{\"matcher\":\"Bash\",\"hooks\":[{\"type\":\"command\",\"command\":\"java\",\"args\":[\"narrowed\"]}]}],"
                + "\"PostToolUse\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"java\",\"args\":[\"everywhere\"]}]}],"
                + "\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"java\",\"args\":[\"stop\"]}]}]}}");
        write(root, ".mcp.json", "{\"mcpServers\":{\"srv\":{\"command\":\"x\"}}}");

        Map<String, String> before = snapshot(root);
        String out = footprint(hook, root);
        int failures = 0;

        failures += expect(out, "`@docs/imported.md` (import, hop 1)", true, "an import outside code counts");
        failures += expect(out, "ignored.md", false, "a path in backticks is not an import");
        failures += expect(out, "rules without `paths` (1): always.md", true, "a rule without paths loads every session");
        failures += expect(out, "| `scoped.md` |", true, "a rule with paths is listed as on demand");
        failures += expect(out, "skill listing (1 model-invocable of 2)", true, "a manual skill stays out of the listing");
        failures += expect(out, "| `manual` | 1 |", true, "a manual skill still has a body row");
        failures += expect(out, "skill `auto` — body 501 lines (limit 500)", true, "a body past the limit is marked");
        failures += expect(out, "`references/long.md` over 100 lines without a contents list", true, "a long reference with no contents is marked");
        failures += expect(out, "hook with neither `matcher` nor `if` on a tool event — PostToolUse › java everywhere", true, "an unnarrowed tool hook is marked");
        failures += expect(out, "PreToolUse › java narrowed", false, "a hook under a matcher is not marked");
        failures += expect(out, "| Stop | 1 | — (no matcher read) | 1 |", true, "an event that reads no matcher fires every time");
        failures += expect(out, "MCP servers (1): srv", true, "servers of .mcp.json are named");
        failures += expect(out, "| `worker` | 2 | 2 | 626 |", true, "an agent's preloaded skill is summed");

        if (!before.equals(snapshot(root))) {
            System.out.println("❌ footprint changed the fixture tree");
            failures++;
        } else System.out.println("✅ footprint writes nothing");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `footprint` does NOT count what"
                    + " the runtime loads, and harness-lessons would file savings that do not exist.");
            System.exit(1);
        }
        System.out.println("✅ footprint: imports, rules, listing, limits, hooks, MCP and preloads counted as the runtime loads them.");
    }

    static void write(Path root, String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    static Map<String, String> snapshot(Path root) throws Exception {
        try (Stream<Path> w = Files.walk(root)) {
            Map<String, String> m = new TreeMap<>();
            for (Path p : w.filter(Files::isRegularFile).collect(Collectors.toList()))
                m.put(root.relativize(p).toString(), Files.readString(p, StandardCharsets.UTF_8));
            return m;
        }
    }

    static String footprint(Path hook, Path root) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "footprint");
        pb.environment().put("CLAUDE_PROJECT_DIR", root.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    static int expect(String out, String needle, boolean present, String label) {
        if (out.contains(needle) == present) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — expected `" + needle + "` "
                + (present ? "in" : "absent from") + ":");
        System.out.println(out);
        return 1;
    }
}
