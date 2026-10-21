///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar doctor gate` exits 1 when a line `doctor.gate.labels` lists
// prints ❌, naming it, and exits 0 on a healthy project although runner-state lines
// (CLAUDE_PROJECT_DIR, Compose, Build wrapper, git HEAD) are red — while the bare `doctor`
// keeps exiting 0 on the same broken tree, which is what this repository's own CI relies on.
//
// Why this test exists: the generated project's "architectural boundaries" job ran the bare
// `doctor`, which only prints, and went green on `ENFORCEMENT OFF` (issue #104, decision
// 0128). A label is matched by text, so renaming a `report(...)` label would drop it from the
// gate in silence: the healthy fixture must print every listed label, or this test fails.
//
// Runs in a throwaway project holding copies of the real extensions.json, ArchHook.java and
// ArchHook.jar, with CLAUDE_PROJECT_DIR unset — as on a CI runner.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class DoctorGateTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();

    public static void main(String[] args) throws Exception {
        List<String> labels = gateLabels();
        if (labels.isEmpty()) {
            System.out.println("❌ doctor.gate.labels is empty or missing in extensions.json");
            System.exit(1);
        }

        String healthy = run(new String[] {"doctor", "gate"}, p -> { }, 0, "healthy project — gate passes");
        for (String l : labels) {
            if (!healthy.contains("  " + l + " .")) fail("gated label `" + l + "` is never printed by doctor"
                    + " — renamed in ArchHook.java? The gate would skip it in silence");
        }
        for (String l : List.of("CLAUDE_PROJECT_DIR", "Build wrapper", "Compose", "git HEAD")) {
            if (!Pattern.compile("  " + Pattern.quote(l) + " \\.+ ❌").matcher(healthy).find()) {
                fail("healthy fixture should show `" + l + "` red, to prove it does not gate");
            }
        }
        if (!healthy.contains("✅ doctor gate:")) fail("healthy project — no `✅ doctor gate:` line");

        broken("Boundaries", p -> Files.delete(p.resolve(".claude/forbidden-imports.txt")));
        broken("Boundary violations", p -> write(p.resolve("domain/src/main/java/x/Order.java"),
                "package x;\n\nimport org.springframework.stereotype.Component;\n\nclass Order { }\n"));
        broken("Schema", p -> write(p.resolve(".claude/skills/x/SKILL.md"),
                "---\nname: x\nnot-a-field: 1\n---\n# x\n"));
        broken("Hook jar", p -> Files.writeString(p.resolve(".claude/hooks/ArchHook.java"),
                "\n// edited after the build\n", StandardOpenOption.APPEND));
        broken("Hooks", p -> write(p.resolve(".claude/settings.json"),
                "{\"hooks\":{\"NoSuchEvent\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"java\"}]}]}}"));
        broken("MCP", p -> write(p.resolve(".mcp.json"),
                "{\"mcpServers\":{\"x\":{\"command\":\"npx\",\"not-a-field\":1}}}"));
        broken("Audit overrides", p -> write(p.resolve(".claude/audit-usage/audited.json"),
                "{\"skills\":{\"no-such-skill\":true}}"));

        run(new String[] {"doctor"}, p -> Files.delete(p.resolve(".claude/forbidden-imports.txt")), 0,
                "bare doctor on ENFORCEMENT OFF — still exit 0, report only");
        run(new String[] {"doctor", "gate"}, p -> {
            Path ext = p.resolve(".claude/schemas/extensions.json");
            String s = Files.readString(ext);
            String emptied = s.replaceFirst("(\"gate\"\\s*:\\s*\\{\\s*\"labels\"\\s*:\\s*)\\[[^\\]]*\\]", "$1[]");
            if (emptied.equals(s)) throw new IllegalStateException("gate.labels not found to empty");
            Files.writeString(ext, emptied);
        }, 1, "empty doctor.gate.labels — fails closed");

        System.out.println(failures == 0 ? "\n✅ doctor gate fails on every gated line and only on those."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Mutation { void apply(Path p) throws Exception; }

    static void broken(String label, Mutation m) throws Exception {
        String out = run(new String[] {"doctor", "gate"}, m, 1, label + " broken — gate fails");
        Matcher g = Pattern.compile("❌ doctor gate: (.*) failed").matcher(out);
        if (!g.find() || !Arrays.asList(g.group(1).split(", ")).contains(label)) {
            fail(label + " broken — the gate line does not name `" + label + "`");
        }
    }

    static String run(String[] mode, Mutation m, int wanted, String name) throws Exception {
        Path p = fixture();
        m.apply(p);
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(p.toFile());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = proc.waitFor();
        if (code == wanted) {
            System.out.println("✅ " + name);
        } else {
            fail(name + " — expected exit " + wanted + ", got " + code);
            System.out.println(out);
        }
        return out;
    }

    /** Every gated line green; the runner-state lines red, as on a CI runner. */
    static Path fixture() throws Exception {
        Path p = Files.createTempDirectory("archhook-doctor-gate-ci");
        Path schemas = p.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        Path hooks = p.resolve(".claude/hooks");
        Files.createDirectories(hooks);
        Files.copy(Paths.get(".claude/hooks/ArchHook.java"), hooks.resolve("ArchHook.java"));
        Files.copy(HOOK, hooks.resolve("ArchHook.jar"));
        write(p.resolve(".claude/forbidden-imports.txt"), "# boundary\ndomain|org.springframework\n");
        write(p.resolve(".claude/settings.json"), blockedPluginsOff());
        write(p.resolve(".mcp.json"), "{\"mcpServers\":{}}");
        write(p.resolve(".claude/audit-usage/audited.json"), "{}");
        // Advertised only on its compose-network name: Compose is red with or without Docker.
        write(p.resolve("docker-compose.yml"), """
                services:
                  kafka:
                    image: apache/kafka:3.8.0
                    ports:
                      - "9092:9092"
                    environment:
                      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
                """);
        return p;
    }

    /**
     * A settings file carrying `"<id>": false` for every `settings.blocked_plugins` entry, read
     * from the real extensions.json — `schema` fails without them (decision 0138), and an
     * empty `{}` would turn the healthy fixture's Schema and Hooks lines red.
     */
    static String blockedPluginsOff() throws Exception {
        String s = Files.readString(Paths.get(".claude/schemas/extensions.json"));
        Matcher m = Pattern.compile("\"blocked_plugins\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(s);
        List<String> off = new ArrayList<>();
        if (m.find()) {
            Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (q.find()) off.add("\"" + q.group(1) + "\":false");
        }
        return "{\"enabledPlugins\":{" + String.join(",", off) + "}}";
    }

    static List<String> gateLabels() throws Exception {
        String s = Files.readString(Paths.get(".claude/schemas/extensions.json"));
        Matcher m = Pattern.compile("\"gate\"\\s*:\\s*\\{\\s*\"labels\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(s);
        List<String> out = new ArrayList<>();
        if (!m.find()) return out;
        Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (q.find()) out.add(q.group(1));
        return out;
    }

    static void write(Path f, String content) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    static void fail(String msg) {
        failures++;
        System.out.println("❌ " + msg);
    }
}
