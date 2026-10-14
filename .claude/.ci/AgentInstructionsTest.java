///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar doctor` prints `Agent instructions . ✅` when a generated
// project carries the files `project-bootstrap` step 6 writes for agents other than Claude
// Code — root `AGENTS.md`, `.github/copilot-instructions.md`, and per module of the active
// blueprint with forbidden imports an `AGENTS.md` listing every prefix next to a `CLAUDE.md`
// holding `@AGENTS.md` — and names each defect otherwise: a missing file, a prefix not
// listed, a module `CLAUDE.md` that does not import, a cited path that does not exist. Also
// that the line is silent without a provenance stamp, reads the inline list form, skips a
// module whose directory does not exist, and never fails `doctor gate`.
//
// Why this test exists: the module `CLAUDE.md` holding `@AGENTS.md` is the only thing that
// makes Claude Code load a module's context once it lives in `AGENTS.md` — a module
// `AGENTS.md` alone is skipped while the root has a `CLAUDE.md`, in silence (reproduced on
// Claude Code 2.1.296). The files are rendered by the model from templates, so the check is
// what catches a module left out. Report only, by the user's answer: the build refuses a
// forbidden import, and an adopted project has none of these files. Decision 0139.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class AgentInstructionsTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final String DOMAIN_PREFIXES = "- `org.springframework..`\n- `jakarta.persistence..`\n"
            + "- `jakarta.validation..`\n- `com.fasterxml.jackson..`\n- `tools.jackson..`\n- `graphql..`\n"
            + "- `com.mongodb..`\n- `org.bson..`\n- `co.elastic..`\n- `org.opensearch..`\n- `org.hibernate..`\n"
            + "- `org.slf4j..`\n- `ch.qos.logback..`\n- `org.apache.logging..`\n- `org.apache.commons.logging..`\n"
            + "- `lombok.extern..`\n- `org.mapstruct..`\n";

    public static void main(String[] args) throws Exception {
        expect(doctor(complete()), "Agent instructions . ✅ 4 file(s) · 2 module(s) match blueprint hexagonal",
                "every file present and matching — green; adapter modules with no directory are skipped");

        Map<String, String> p = complete();
        p.put("domain/AGENTS.md", p.get("domain/AGENTS.md").replace("- `tools.jackson..`\n", ""));
        expect(doctor(p), "domain/AGENTS.md does not list tools.jackson..", "a forbidden prefix not listed is named");

        p = complete();
        p.remove("application/CLAUDE.md");
        expect(doctor(p), "application/CLAUDE.md missing — Claude Code skips application/AGENTS.md without it",
                "a module CLAUDE.md missing is named, with what it costs Claude Code");

        p = complete();
        p.put("domain/CLAUDE.md", "# domain\nForbidden: org.springframework..\n");
        expect(doctor(p), "domain/CLAUDE.md does not hold @AGENTS.md", "a module CLAUDE.md that does not import is named");

        p = complete();
        p.put("domain/AGENTS.md", p.get("domain/AGENTS.md") + "\nFull rule: `.claude/rules/gone.md`\n");
        expect(doctor(p), "domain/AGENTS.md cites .claude/rules/gone.md, which does not exist", "a dead cited path is named");

        p = complete();
        p.remove("AGENTS.md");
        p.remove(".github/copilot-instructions.md");
        String out = doctor(p);
        expect(out, "Agent instructions . ⚠️  2 problem(s)", "the two root pointers missing are counted");
        expect(out, "    AGENTS.md missing", "the root AGENTS.md missing is named");

        p = complete();
        p.remove(".claude/.arch-provenance.json");
        if (doctor(p).contains("Agent instructions")) fail("no stamp — the line must be silent");
        else System.out.println("✅ no provenance stamp — silent");

        Map<String, String> mini = new LinkedHashMap<>(base("mini"));
        mini.put(".claude/blueprints/mini/mini.yaml", "id: mini\nmodules:\n  - id: core\n    path: core\n"
                + "    forbidden_imports: [\"org.x..\", 'org.y..']  # inline form\n  - id: app\n    path: \".\"\n"
                + "packages:\n  map:\n    domain: \"domain\"\n");
        mini.put("core/AGENTS.md", "# core\n- `org.x..`\n");
        mini.put("core/CLAUDE.md", "@AGENTS.md\n");
        out = doctor(mini);
        expect(out, "core/AGENTS.md does not list org.y..", "the inline list form is read, quotes and comment stripped");
        if (out.contains("does not list org.x..")) fail("inline form — a listed prefix reported as missing");

        p = complete();
        p.remove("application/CLAUDE.md");
        String gate = doctor(p, "doctor", "gate");
        Matcher g = Pattern.compile("❌ doctor gate: (.*) failed").matcher(gate);
        if (!gate.contains("Agent instructions . ⚠️")) {
            fail("gate run — the fixture did not show the line, so the case proves nothing");
        } else if (g.find() && Arrays.asList(g.group(1).split(", ")).contains("Agent instructions")) {
            fail("doctor gate names `Agent instructions` — it reports, it must never gate");
        } else {
            System.out.println("✅ doctor gate ignores an Agent instructions problem");
        }

        System.out.println(failures == 0 ? "\n✅ Agent instruction files checked against the blueprint, never gated."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** What every fixture carries: the schema, a stamp naming {@code id}, the rule index. */
    static Map<String, String> base(String id) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(".claude/.arch-provenance.json", "{\n  \"blueprint\": \"" + id + "\"\n}\n");
        f.put(".claude/rules/00-index.md", "# Index\n");
        f.put(".claude/rules/architecture-ddd.md", "# Architecture\n");
        f.put("AGENTS.md", "# demo\nRead `CLAUDE.md`. Norms: `.claude/rules/00-index.md`.\n");
        f.put(".github/copilot-instructions.md", "Read [CLAUDE.md](../CLAUDE.md).\n");
        f.put("CLAUDE.md", "# demo\n");
        return f;
    }

    /** A hexagonal project as step 6 writes it: `domain` and `application` restricted. */
    static Map<String, String> complete() throws Exception {
        Map<String, String> f = base("hexagonal");
        f.put(".claude/blueprints/hexagonal/hexagonal.yaml",
                Files.readString(Paths.get(".claude/blueprints/hexagonal/hexagonal.yaml"), StandardCharsets.UTF_8));
        f.put("domain/AGENTS.md", "# `domain` module\n\n## Forbidden in this module\n\n" + DOMAIN_PREFIXES
                + "\nFull rule: `.claude/rules/architecture-ddd.md`\n");
        f.put("domain/CLAUDE.md", "@AGENTS.md\n");
        f.put("application/AGENTS.md", "# `application` module\n\n- `jakarta.persistence..`\n"
                + "- `org.springframework.web..`\n- `org.springframework.ai..`\n"
                + "- `org.springframework.graphql..`\n- `graphql..`\n"
                + "- `org.springframework.data.mongodb..`\n- `com.mongodb..`\n- `org.bson..`\n"
                + "- `org.springframework.data.elasticsearch..`\n- `co.elastic..`\n- `org.opensearch..`\n"
                + "- `org.hibernate.envers..`\n- `org.hibernate.annotations..`\n- `org.springframework.data.history..`\n"
                + "- `org.springframework.data.envers..`\n- `org.springframework.data.annotation..`\n"
                + "- `org.mapstruct..`\n");
        f.put("application/CLAUDE.md", "@AGENTS.md\n");
        return f;
    }

    static void expect(String out, String needle, String name) {
        if (out.contains(needle)) {
            System.out.println("✅ " + name);
        } else {
            fail(name + " — expected \"" + needle + "\"");
            System.out.println(out);
        }
    }

    static String doctor(Map<String, String> files, String... mode) throws Exception {
        Path p = Files.createTempDirectory("archhook-agents-ci");
        Files.createDirectories(p.resolve(".claude/schemas"));
        Files.copy(Paths.get(".claude/schemas/extensions.json"), p.resolve(".claude/schemas/extensions.json"));
        for (Map.Entry<String, String> e : files.entrySet()) write(p.resolve(e.getKey()), e.getValue());
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(mode.length == 0 ? List.of("doctor") : List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(p.toFile());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        proc.waitFor();
        deleteTree(p);
        return out;
    }

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
