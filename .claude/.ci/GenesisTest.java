///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `audit genesis` fills a generated project's GENESIS record from the session's
// transcripts, exactly, once — and refuses, by name, what it cannot fill.
//
// Why this test exists: a mode that throws exits 0 through ArchHook's top-level catch and looks
// like it passed. GENESIS once recorded a start after its finish, written from a directory's
// birth time in local zone with a literal `Z` (lessons-learned-020 §§ 4–5, decision 0123).
// Tokens only, never a cost: the trail prices nothing (decision 0134). The figures now come only from this mode, so a wrong sum, a turn counted from
// before `/init-project`, or a second run overwriting the record would land in every project.
//
// What it builds: a throwaway CLAUDE_CONFIG_DIR with one main transcript (a turn before the
// `/init-project` message, which must not count, and a turn written as two content blocks, which
// must count once, and a later tool result quoting the command tag, which must not move Started)
// and one subagent transcript with its `.meta.json`; a throwaway project with a leftover
// pricing.json, which must be ignored, and a GENESIS carrying the three placeholders.
// `audit.genesis` is read from this repository's real extensions.json, through
// CLAUDE_PROJECT_DIR. Runs the jar, the bytes the skill invokes (0084).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GenesisTest {

    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final Path REPO = Paths.get("").toAbsolutePath();
    static final String SID = "11111111-2222-3333-4444-555555555555";
    static final String GENESIS = """
            | Started | {{startIso}} |
            | Finished | {{endIso}} |
            | Tokens | {{tokens}} |
            """;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path cfg = Files.createTempDirectory("genesis-cfg");
        Path session = cfg.resolve("projects/-tmp-nerviz");
        Files.createDirectories(session.resolve(SID + "/subagents"));
        Files.writeString(session.resolve(SID + ".jsonl"), String.join("\n",
                turn("2026-10-06T08:00:00Z", "m0", "claude-opus-5-5", 1_000_000, 0, 0, 0),
                "{\"type\":\"user\",\"timestamp\":\"2026-10-06T08:55:27.413Z\",\"message\":{\"role\":\"user\","
                        + "\"content\":\"<command-name>/init-project</command-name>\"}}",
                turn("2026-10-06T08:55:40Z", "m1", "claude-opus-5-5", 10_000, 100_000, 1_000_000, 200_000),
                turn("2026-10-06T08:55:41Z", "m1", "claude-opus-5-5", 10_000, 100_000, 1_000_000, 200_000),
                "{\"type\":\"user\",\"timestamp\":\"2026-10-06T09:30:00Z\",\"message\":{\"role\":\"user\",\"content\":"
                        + "[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_9\",\"content\":"
                        + "\"a file citing <command-name>/init-project</command-name>\"}]}}") + "\n");
        Path sub = session.resolve(SID + "/subagents");
        Files.writeString(sub.resolve("agent-abc.jsonl"),
                turn("2026-10-06T09:00:00Z", "s1", "claude-sonnet-5", 2_000, 50_000, 5_000_000, 100_000) + "\n");
        Files.writeString(sub.resolve("agent-abc.meta.json"), "{\"toolUseId\":\"toolu_1\"}");

        Path project = Files.createTempDirectory("genesis-project");
        Path audit = project.resolve(".claude/audit-usage");
        Files.createDirectories(audit);
        Path genesis = audit.resolve("GENESIS.md");

        Files.writeString(audit.resolve("pricing.json"), "{\"currency\":\"USD\",\"per\":1000000,\"models\":{"
                + "\"claude-opus-5-5\":{\"input\":4,\"output\":20,\"cache_read\":0.2,\"cache_write\":5}}}");
        Files.writeString(genesis, GENESIS);
        int exit = run(cfg, project, SID);
        String filled = Files.readString(genesis);
        check("fills the record", exit == 0, "exit " + exit);
        check("Started is the /init-project message, UTC, to the second",
                filled.contains("| Started | 2026-10-06T08:55:27Z |"), filled);
        check("Finished is a UTC instant, not before Started",
                filled.matches("(?s).*\\| Finished \\| 20\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ \\|.*"), filled);
        check("tokens: the main session from Started on plus the subagent, one turn per message id",
                filled.contains("12,000 input · 150,000 output · 6,000,000 cache read · 300,000 cache write — 2 requests"),
                filled);
        check("no cost, though a pricing.json is left in the trail", !filled.contains("USD") && !filled.contains("Cost"),
                filled);
        check("no placeholder left", !filled.contains("{{"), filled);

        exit = run(cfg, project, SID);
        check("a filled record is refused, not overwritten", exit == 1 && Files.readString(genesis).equals(filled),
                "exit " + exit);

        Files.writeString(genesis, GENESIS);
        exit = run(cfg, project, "00000000-0000-0000-0000-000000000000");
        check("an unknown session is refused", exit == 1 && Files.readString(genesis).equals(GENESIS), "exit " + exit);

        // The Skill tool leaves no <command-name> tag (0164, issue #146): the anchor is the tool
        // result's toolUseResult.commandName, and Started the prompt that shares its promptId.
        String other = userPrompt("2026-10-06T08:50:00Z", "pid-other", "an earlier, unrelated prompt");
        String prompt = userPrompt("2026-10-06T08:55:27.413Z", "pid-1", "please start a new project");
        String before = turn("2026-10-06T08:00:00Z", "m0", "claude-opus-5-5", 1_000_000, 0, 0, 0);
        String t1 = turn("2026-10-06T08:55:30Z", "m1", "claude-opus-5-5", 10_000, 100_000, 1_000_000, 200_000);
        String t2 = turn("2026-10-06T08:55:40Z", "m2", "claude-opus-5-5", 10_000, 100_000, 1_000_000, 200_000);

        String out = scenario(before, other, prompt, t1,
                skillResult("2026-10-06T08:55:31Z", "pid-1", "init-project"), t2);
        check("Skill tool: Started is the prompt that shares the result's promptId",
                lastExit == 0 && out.contains("| Started | 2026-10-06T08:55:27Z |"), "exit " + lastExit + "\n" + out);
        check("Skill tool: tokens count from that prompt, not from before it nor from another prompt",
                out.contains("20,000 input · 200,000 output · 2,000,000 cache read · 400,000 cache write — 2 requests"),
                out);

        out = scenario(before, prompt, t1, skillResult("2026-10-06T08:55:31Z", null, "init-project"), t2);
        check("Skill tool, no promptId: Started is the tool result itself",
                lastExit == 0 && out.contains("| Started | 2026-10-06T08:55:31Z |"), "exit " + lastExit + "\n" + out);

        out = scenario(before, prompt, t1, skillResult("2026-10-06T08:55:31Z", "pid-1", "git-publish"), t2);
        check("Skill tool of another skill is no anchor", lastExit == 1 && out.equals(GENESIS),
                "exit " + lastExit + "\n" + out);

        out = scenario(before, prompt, t1,
                "{\"type\":\"user\",\"promptId\":\"pid-1\",\"timestamp\":\"2026-10-06T08:55:31Z\",\"message\":"
                        + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_2\","
                        + "\"content\":\"a file citing \\\"commandName\\\":\\\"init-project\\\"\"}]}}", t2);
        check("a tool result echoing the field, with no toolUseResult of its own, is no anchor",
                lastExit == 1 && out.equals(GENESIS), "exit " + lastExit + "\n" + out);

        out = scenario(before, typedCommand("2026-10-06T08:55:27Z"), t1,
                userPrompt("2026-10-06T09:10:00Z", "pid-2", "and again"), t2,
                skillResult("2026-10-06T09:10:05Z", "pid-2", "init-project"));
        check("a typed run then a later Skill run: the later one wins",
                lastExit == 0 && out.contains("| Started | 2026-10-06T09:10:00Z |"), "exit " + lastExit + "\n" + out);

        out = scenario(before, typedCommand("2026-10-06T09:20:00Z"), t1,
                userPrompt("2026-10-06T08:10:00Z", "pid-3", "earlier"),
                skillResult("2026-10-06T08:10:05Z", "pid-3", "init-project"), t2);
        check("a Skill run then a later typed run: the later one wins",
                lastExit == 0 && out.contains("| Started | 2026-10-06T09:20:00Z |"), "exit " + lastExit + "\n" + out);

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — GENESIS figures are not what the transcripts say.");
            System.exit(1);
        }
        System.out.println("✅ audit genesis fills Started, Finished and tokens from the transcripts, once.");
    }

    static int lastExit;

    /** One throwaway session with these transcript lines; returns the GENESIS left behind. */
    static String scenario(String... entries) throws Exception {
        Path cfg = Files.createTempDirectory("genesis-cfg");
        Path dir = cfg.resolve("projects/-tmp-nerviz");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SID + ".jsonl"), String.join("\n", entries) + "\n");
        Path project = Files.createTempDirectory("genesis-project");
        Files.createDirectories(project.resolve(".claude/audit-usage"));
        Path genesis = project.resolve(".claude/audit-usage/GENESIS.md");
        Files.writeString(genesis, GENESIS);
        lastExit = run(cfg, project, SID);
        return Files.readString(genesis);
    }

    static String userPrompt(String iso, String promptId, String text) {
        return "{\"type\":\"user\",\"promptId\":\"" + promptId + "\",\"timestamp\":\"" + iso
                + "\",\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}";
    }

    static String typedCommand(String iso) {
        return userPrompt(iso, "pid-typed", "<command-name>/init-project</command-name>");
    }

    /** The tool result the runtime writes for a Skill tool call, as observed in a real transcript. */
    static String skillResult(String iso, String promptId, String commandName) {
        return "{\"type\":\"user\"," + (promptId == null ? "" : "\"promptId\":\"" + promptId + "\",")
                + "\"timestamp\":\"" + iso + "\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":"
                + "\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"Launching skill: " + commandName
                + "\"}]},\"toolUseResult\":{\"success\":true,\"commandName\":\"" + commandName + "\"}}";
    }

    static String turn(String iso, String id, String model, long in, long out, long cr, long cw) {
        return "{\"type\":\"assistant\",\"timestamp\":\"" + iso + "\",\"message\":{\"id\":\"" + id
                + "\",\"model\":\"" + model + "\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}],"
                + "\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out
                + ",\"cache_read_input_tokens\":" + cr + ",\"cache_creation_input_tokens\":" + cw + "}}}";
    }

    static int run(Path cfg, Path project, String sid) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
                "-jar", HOOK.toString(), "audit", "genesis", project.toString(), sid);
        pb.environment().put("CLAUDE_CONFIG_DIR", cfg.toString());
        pb.environment().put("CLAUDE_PROJECT_DIR", REPO.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            System.out.println("  ✗ hung for 30 s — `audit genesis` must not read stdin");
            failures++;
            return -1;
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!out.isBlank()) System.out.println(out.strip().indent(6).stripTrailing());
        return p.exitValue();
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { System.out.println("  ✓ " + name); return; }
        System.out.println("  ✗ " + name + "\n" + detail.indent(6).stripTrailing());
        failures++;
    }
}
