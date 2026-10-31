///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar audit` renders "where the run spent" from the transcripts —
// tool calls per piece, including a subagent's own transcript; the most expensive turns; the
// peak context; each tool error's first line, redacted — writes the matching ledger fields,
// that `audit summary` aggregates them next to a legacy pt-BR ledger row, that the trail
// measures billable tokens only — no cost anywhere, a leftover pricing.json ignored, the model
// named per piece (decision 0134) — and that an
// observer — a piece whose class declares `audited: false` — leaves no report of its own, and
// that a project's audited.json sets a piece on or off over its class, except arch-adopt,
// fixed off by its own class override (decision 0111), and that a prompt the harness injects —
// a background agent's notice or its hand-back — leaves the run open (decisions 0041, 0119),
// and that each piece carries its cache split and its agent call's description, and the report
// ranks the tool calls whose results later requests re-read the most (decision 0132),
// and that runs per plan window are the project's Pro budget × each plan's multiplier ÷ the run's
// billable tokens, capped by the window's hours, in the report and in `summary` (decisions 0133,
// 0134), and that the account's measured plan usage is the delta of a personal status line's
// snapshot read at the run's open and close — each window from its own key, nothing but numbers
// copied, the reasons it is not captured, not valid or not available, and the mean over valid
// deltas only (decision 0194).
//
// Why this test exists: every number here is parsed out of a transcript layout that is
// observed, not documented, and a render that throws exits 0 through `main`'s catch — the
// report just stops updating, which is how decision 0041's crash went unseen. The redaction
// is the other half: an error line can echo a command holding a token, and the report is a
// versioned file (invariant 11). Decision 0085.
//
// Runs in a throwaway project holding a copy of the real extensions.json, so the redaction
// patterns under test are production data.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

public class AuditRenderTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path repo = Files.createTempDirectory("archhook-audit-ci");
        Files.createDirectories(repo.resolve(".claude/schemas"));
        Files.copy(Paths.get(".claude/schemas/extensions.json"),
                repo.resolve(".claude/schemas/extensions.json"));
        Files.createDirectories(repo.resolve(".claude/skills/demo-skill"));
        Files.writeString(repo.resolve(".claude/skills/demo-skill/SKILL.md"),
                "---\nname: demo-skill\ndescription: fixture\n---\n\nbody\n");
        Files.createDirectories(repo.resolve(".claude/agents"));
        Files.writeString(repo.resolve(".claude/agents/demo-agent.md"),
                "---\nname: demo-agent\ndescription: fixture\n---\n\nbody\n");
        Path trail = repo.resolve(".claude/audit-usage");
        Files.createDirectories(trail);
        home = repo.resolve("home");
        Files.createDirectories(home);
        // The variables that mark an account with no plan window are data (0194); read before the first spawn.
        java.util.regex.Matcher vars = java.util.regex.Pattern.compile("\"not_available_env\"\\s*:\\s*\\[([^\\]]*)\\]")
                .matcher(Files.readString(repo.resolve(".claude/schemas/extensions.json")));
        if (!vars.find()) { System.out.println("❌ extensions.json lists no audit.plan_usage.not_available_env"); System.exit(1); }
        vars = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(vars.group(1));
        while (vars.find()) notAvailableEnv.add(vars.group(1));

        // A row written before 0085: Portuguese status, formatted pt-BR cost — read for its
        // tokens and status, its cost never shown (0134).
        Files.writeString(trail.resolve("history.jsonl"),
                "{\"e\":\"run\",\"skill\":\"demo-skill\",\"kind\":\"skill\",\"origin\":\"user\","
                + "\"start\":\"2026-01-01T00:00:00Z\",\"duration_ms\":\"1000\",\"status\":\"❌ erro\","
                + "\"tokens_billable\":\"10\",\"tokens_self\":\"10\",\"cost\":\"USD 1.234,56\","
                + "\"files\":\"0\",\"failures\":\"0\",\"report\":\".claude/audit-usage/old--demo-skill.md\"}\n");

        // A project generated before 0134 keeps a pricing.json until its next /arch-adopt:
        // nothing may read it.
        Files.writeString(trail.resolve("pricing.json"), "{\"currency\":\"USD\",\"per\":1000000,"
                + "\"models\":{\"other-model\":{\"input\":1,\"output\":1,\"cache_read\":1,\"cache_write\":1}}}");

        Path transcripts = repo.resolve("transcripts");
        Path main = transcripts.resolve("s1.jsonl");
        Path subs = transcripts.resolve("s1/subagents");
        Files.createDirectories(subs);
        String tp = main.toString().replace("\\", "\\\\");

        run(hook, repo, "prompt", "{\"session_id\":\"s1\",\"prompt\":\"/demo-skill go\","
                + "\"transcript_path\":\"" + tp + "\"}");
        run(hook, repo, "call", "{\"session_id\":\"s1\",\"tool_name\":\"Agent\",\"tool_use_id\":\"toolu_agent\","
                + "\"tool_input\":{\"subagent_type\":\"demo-agent\",\"description\":\"work\"}}");

        // Timestamps after the run opened, so every turn falls inside its token window.
        // m1: one message, two content blocks — the usage repeats and must count once.
        String m1 = usage(100, 50, 1000, 200);
        Files.writeString(main, String.join("\n",
                assistant(at(10), "m1", m1, "{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"Bash\",\"input\":{}}"),
                assistant(at(10), "m1", m1, "{\"type\":\"tool_use\",\"id\":\"tu2\",\"name\":\"Read\",\"input\":{}}"),
                "{\"type\":\"user\",\"timestamp\":\"" + at(11) + "\",\"message\":{\"role\":\"user\",\"content\":"
                        + "[{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"is_error\":true,"
                        + "\"content\":\"Exit code 1\\nerror: token=supersecret123 rejected\\nthird line\"}]}}",
                assistant(at(12), "m2", usage(10, 900, 5000, 3000),
                        "{\"type\":\"tool_use\",\"id\":\"tu3\",\"name\":\"Edit\",\"input\":{}}")) + "\n");
        Files.writeString(subs.resolve("agent-abc.meta.json"), "{\"toolUseId\":\"toolu_agent\"}");
        Files.writeString(subs.resolve("agent-abc.jsonl"), String.join("\n",
                assistant(at(20), "a1", usage(20, 30, 100, 50),
                        "{\"type\":\"tool_use\",\"id\":\"ta1\",\"name\":\"Grep\",\"input\":{}}"),
                assistant(at(21), "a2", usage(5, 5, 200, 0),
                        "{\"type\":\"tool_use\",\"id\":\"ta2\",\"name\":\"Grep\",\"input\":{}}")) + "\n");

        run(hook, repo, "agent", "{\"session_id\":\"s1\",\"agent_type\":\"demo-agent\",\"agent_id\":\"abc\"}");
        // 0094: the answer event keeps each question and its answer, through the same redaction.
        run(hook, repo, "ask", "{\"session_id\":\"s1\",\"tool_name\":\"AskUserQuestion\"}");
        run(hook, repo, "answer", "{\"session_id\":\"s1\",\"tool_name\":\"AskUserQuestion\","
                + "\"tool_response\":{\"questions\":[{\"question\":\"Which prefix?\",\"header\":\"Context\"},"
                + "{\"question\":\"Credential | scope?\",\"header\":\"Auth\"}],"
                + "\"answers\":{\"Which prefix?\":\"banking\",\"Credential | scope?\":\"password=hunter2x\"}}}");
        run(hook, repo, "close", "{\"session_id\":\"s1\",\"transcript_path\":\"" + tp + "\"}");

        Path report;
        try (var s = Files.list(trail)) {
            report = s.filter(f -> f.getFileName().toString().endsWith("--demo-skill.md")).findFirst().orElse(null);
        }
        if (report == null) {
            System.out.println("❌ no report written — the render threw or the run never opened");
            System.exit(1);
        }
        String md = Files.readString(report);
        must(md, "## 🔎 Where the run spent", "report has the new section");
        must(md, "✅ Status | success", "status is in English");
        must(md, "Bash 1 · Edit 1 · Read 1", "root's tool calls, one per tool_use id, not per repeated line");
        must(md, "| `🤖 demo-agent (work)` | 2 | Grep 2 |", "agent's calls come from its own transcript, labelled by its call's description");
        must(md, "Largest single request: **8,010**", "peak context = input + cache read + cache write");
        must(md, "### 💸 Most expensive turns", "top turns listed");
        must(md, "exit 1 · error: token=[REDACTED] rejected", "Bash exit line joined with what failed, redacted");
        mustNot(md, "supersecret123", "no secret reaches the versioned report");
        mustNot(md, "third line", "one line of an error, not the whole output");
        must(md, "| 1 | Context | Which prefix? | banking |", "asked: question and answer kept (0094)");
        must(md, "| 2 | Auth | Credential / scope? | password=[REDACTED] |", "asked: redacted, pipe made table-safe");
        mustNot(md, "hunter2x", "no secret from an answer reaches the versioned report");

        mustNot(md, "💰", "no cost cell anywhere in the report (0134)");
        mustNot(md, "USD", "no amount in the report, though a pricing.json is in the trail");
        must(md, "| Piece | Origin | 🤖 Model | 🧮 Own billable | ♻️ Cache read | 💾 Cache write | ⏱️ Duration |",
                "per piece: the model column replaces the cost column (0134)");
        must(md, "| `/demo-skill` | user | fixture-model | ", "per piece: the root's own model");
        must(md, "| `🤖 demo-agent (work)` | nested | fixture-model | ", "per piece: the agent's own model");
        must(md, "## 🪟 Plan windows", "plan windows section rendered (0133)");
        must(md, "No `.claude/audit-usage/plan-limits.json` — no projection", "no plan-limits file: the report says so, projects nothing");

        String history = Files.readString(trail.resolve("history.jsonl"));
        must(history, "\"tool_calls\":\"Grep:2,Bash:1,Edit:1,Read:1\"", "history: run total of tool calls");
        must(history, "\"tool_calls_self\":\"Bash:1,Edit:1,Read:1\"", "history: root's own tool calls");
        must(history, "\"peak_context\":\"8010\"", "history: run peak");
        must(history, "\"status\":\"✅ success\"", "history: English status");
        mustNot(history.substring(history.indexOf('\n') + 1), "cost", "history: no cost field written (0134)");
        must(Files.readString(trail.resolve("nodes.jsonl")), "\"tool_calls\":\"Grep:2\"", "nodes: agent's own tool calls");
        mustNot(Files.readString(trail.resolve("nodes.jsonl")), "cost", "nodes: no cost field written (0134)");

        String doctor = runMode(hook, repo, "doctor");
        must(doctor, "✅ 1 execution(s) recorded", "doctor: the Audit line counts the reports, nothing else");
        mustNot(doctor, "pricing", "doctor: pricing.json is neither required nor read (0134)");

        String summary = run(hook, repo, "summary", "");
        must(summary, "closed runs: 2", "summary reads the legacy row and the new one");
        must(summary, "failure rate: 1/2", "legacy Portuguese status still classified by its emoji");
        mustNot(summary, "USD", "summary: no cost, not even a legacy row's (0134)");
        must(summary, "Where the pieces spent", "summary aggregates tool calls per piece");
        must(summary, "Grep 2", "summary counts the agent's calls");

        // Exclusion is the class's (`observer` declares `audited: false`), never a list — 0086.
        // An observer typed mid-run closes that run and opens none; called by the model with
        // no run open, it opens none either.
        Files.createDirectories(repo.resolve(".claude/skills/audit-usage"));
        Files.writeString(repo.resolve(".claude/skills/audit-usage/SKILL.md"),
                "---\nname: audit-usage\ndescription: fixture\n---\n\nbody\n");
        run(hook, repo, "prompt", "{\"session_id\":\"s2\",\"prompt\":\"/demo-skill again\"}");
        run(hook, repo, "prompt", "{\"session_id\":\"s2\",\"prompt\":\"/audit-usage\"}");
        // The Stop that follows: it would render a run the observer wrongly opened.
        run(hook, repo, "flush", "{\"session_id\":\"s2\"}");
        run(hook, repo, "call", "{\"session_id\":\"s3\",\"tool_name\":\"Skill\",\"tool_use_id\":\"t3\","
                + "\"tool_input\":{\"skill\":\"audit-usage\"}}");
        long closedRuns = Files.readAllLines(trail.resolve("history.jsonl")).stream()
                .filter(l -> !l.isBlank()).count();
        boolean observerReport;
        try (var s = Files.list(trail)) {
            observerReport = s.anyMatch(f -> f.getFileName().toString().endsWith("--audit-usage.md"));
        }
        check(closedRuns == 3, "an observer typed mid-run closes the run in progress (3 ledger rows, got "
                + closedRuns + ")");
        check(!observerReport, "an observer leaves no report of its own — its class says `audited: false`");
        check(!Files.exists(trail.resolve(".state/s3.ndjson")),
                "a model call to an observer with no run open opens none");

        // `ops` declares `audited: false` too: git-publish's own report would land after its
        // commit and leave the tree dirty on every publish (lessons-learned-012 § 15).
        Files.createDirectories(repo.resolve(".claude/skills/git-publish"));
        Files.writeString(repo.resolve(".claude/skills/git-publish/SKILL.md"),
                "---\nname: git-publish\ndescription: fixture\n---\n\nbody\n");
        run(hook, repo, "prompt", "{\"session_id\":\"s4\",\"prompt\":\"/git-publish\"}");
        run(hook, repo, "flush", "{\"session_id\":\"s4\"}");
        run(hook, repo, "call", "{\"session_id\":\"s5\",\"tool_name\":\"Skill\",\"tool_use_id\":\"t5\","
                + "\"tool_input\":{\"skill\":\"git-publish\"}}");
        boolean publishReport;
        try (var s = Files.list(trail)) {
            publishReport = s.anyMatch(f -> f.getFileName().toString().endsWith("--git-publish.md"));
        }
        check(!publishReport && !Files.exists(trail.resolve(".state/s5.ndjson")),
                "git-publish opens no run of its own, typed or called by the model");

        // The project's audited.json sets a piece on or off over its class default; arch-adopt's
        // own class override is fixed, out of the file's reach — 0111.
        for (String n : new String[] {"arch-adopt", "report-issue"}) {
            Files.createDirectories(repo.resolve(".claude/skills/" + n));
            Files.writeString(repo.resolve(".claude/skills/" + n + "/SKILL.md"),
                    "---\nname: " + n + "\ndescription: fixture\n---\n\nbody\n");
        }
        run(hook, repo, "prompt", "{\"session_id\":\"s6\",\"prompt\":\"/arch-adopt\"}");
        run(hook, repo, "flush", "{\"session_id\":\"s6\"}");
        check(!reportOf(trail, "arch-adopt"), "arch-adopt is never audited, with no project file");

        Files.writeString(trail.resolve("audited.json"),
                "{\"skills\":{\"report-issue\":false,\"audit-usage\":true,\"arch-adopt\":true}}");
        run(hook, repo, "prompt", "{\"session_id\":\"s7\",\"prompt\":\"/report-issue\"}");
        run(hook, repo, "flush", "{\"session_id\":\"s7\"}");
        run(hook, repo, "call", "{\"session_id\":\"s8\",\"tool_name\":\"Skill\",\"tool_use_id\":\"t8\","
                + "\"tool_input\":{\"skill\":\"report-issue\"}}");
        check(!reportOf(trail, "report-issue") && !Files.exists(trail.resolve(".state/s8.ndjson")),
                "audited.json `false` takes an audited piece out, typed or called by the model");
        run(hook, repo, "prompt", "{\"session_id\":\"s9\",\"prompt\":\"/audit-usage\"}");
        run(hook, repo, "flush", "{\"session_id\":\"s9\"}");
        check(reportOf(trail, "audit-usage"), "audited.json `true` puts back a piece its class leaves out");
        run(hook, repo, "prompt", "{\"session_id\":\"s10\",\"prompt\":\"/arch-adopt\"}");
        run(hook, repo, "flush", "{\"session_id\":\"s10\"}");
        check(!reportOf(trail, "arch-adopt"), "audited.json cannot turn arch-adopt back on");
        must(runMode(hook, repo, "doctor"), "`arch-adopt` is fixed by skill_classes.classes.build.overrides",
                "doctor refuses arch-adopt in audited.json");

        Files.writeString(trail.resolve("audited.json"),
                "{\"skills\":{\"nope\":false,\"report-issue\":\"no\"},\"redact\":{}}");
        String bad = runMode(hook, repo, "doctor");
        must(bad, "`nope` — no .claude/skills/nope/SKILL.md", "doctor names a piece that does not exist");
        must(bad, "`report-issue` must be true or false", "doctor names a non-boolean value");
        must(bad, "`redact` — only `skills` and `agents` are allowed", "doctor refuses any other key");

        Files.writeString(trail.resolve("audited.json"), "{\"skills\":{\"report-issue\":false}}");
        must(runMode(hook, repo, "doctor"), "✅ report-issue off", "doctor lists what the file sets");

        // A background agent's notice and its hand-back are prompts the harness injects, not
        // user intent: neither closes the run that launched the agent, nor overwrites the
        // observer file. The next prompt the user types does close it — 0041, 0119.
        run(hook, repo, "prompt", "{\"session_id\":\"s11\",\"prompt\":\"/demo-skill background\"}");
        long before = rows(trail);
        run(hook, repo, "prompt", "{\"session_id\":\"s11\",\"prompt\":\"<agent-message from=\\\"a1\\\">\\n"
                + "[Subagent hand-back] PROGRESS UPDATE (not final)\\n</agent-message>\"}");
        check(rows(trail) == before && Files.exists(trail.resolve(".state/s11.ndjson")),
                "an <agent-message> hand-back leaves the run open (0119)");
        run(hook, repo, "prompt", "{\"session_id\":\"s11\",\"prompt\":\"<task-notification>\\n"
                + "<task-id>a1</task-id>\\n<status>completed</status>\\n</task-notification>\"}");
        check(rows(trail) == before && Files.exists(trail.resolve(".state/s11.ndjson")),
                "a <task-notification> leaves the run open (0041)");
        check(!Files.exists(trail.resolve(".state/s11.prompt.json")),
                "neither harness prompt overwrites the observer file");
        run(hook, repo, "prompt", "{\"session_id\":\"s11\",\"prompt\":\"thanks, looks good\"}");
        check(rows(trail) == before + 1, "the user's next prompt closes the run");

        growth(hook, repo, trail, transcripts);
        plans(hook, repo, trail, transcripts);
        planUsage(hook, repo, trail);
        bashWrites(hook);
        effort(hook, repo, trail, transcripts);

        if (failures > 0) {
            System.err.println("❌ " + failures + " check(s) failed — `audit` is NOT rendering where the run"
                    + " spent, is leaking an unredacted error into the versioned trail, or is recording"
                    + " a piece its class, its fixed override or the project's audited.json takes out of the trail,"
                    + " or is closing a run on a prompt the harness injected, or is misplacing what grew the context, or is projecting a plan window wrong, or is showing a cost the trail no longer computes, or is reading the account's plan usage wrong.");
            System.exit(1);
        }
        System.out.println("✅ audit: tool calls, top turns, peak context and redacted errors rendered;"
                + " ledgers and summary carry them next to legacy rows; model, cache split, context growth and plan windows per piece, in tokens only.");
    }

    /**
     * A chained executor group whose context grows by known amounts, then compacts: each piece
     * carries its cache split and its call's description, and the call whose result later
     * requests re-read the most ranks first, its re-reads stopping at the compaction — 0132.
     * Context per request (input + cache read + cache write), output 100 / 100 / 100 / 10 / 10:
     * 1,000 → 6,100 → 6,400 → 6,550 → 6,570 → 1,000. The Read at request 1 adds 6,100 − 1,000 −
     * 100 = 5,000, re-read by requests 3–5 (the 6th compacted): 15,000. The Bash at request 2
     * adds 200, re-read twice; the Edit at 3 adds 50, once. Requests 4 and 5 call no tool.
     */
    static void growth(Path hook, Path repo, Path trail, Path transcripts) throws Exception {
        Path main = transcripts.resolve("s12.jsonl");
        Path subs = transcripts.resolve("s12/subagents");
        Files.createDirectories(subs);
        Files.writeString(main, "");
        String tp = main.toString().replace("\\", "\\\\");
        String big = repo.resolve("src/Big.java").toString().replace("\\", "\\\\");
        run(hook, repo, "prompt", "{\"session_id\":\"s12\",\"prompt\":\"/demo-skill growth\","
                + "\"transcript_path\":\"" + tp + "\"}");
        run(hook, repo, "call", "{\"session_id\":\"s12\",\"tool_name\":\"Agent\",\"tool_use_id\":\"toolu_g\","
                + "\"tool_input\":{\"subagent_type\":\"demo-agent\",\"description\":\"group tests\"}}");
        Files.writeString(subs.resolve("agent-def.meta.json"), "{\"toolUseId\":\"toolu_g\"}");
        String text = "{\"type\":\"text\",\"text\":\"…\"}";
        Files.writeString(subs.resolve("agent-def.jsonl"), String.join("\n",
                assistant(at(30), "g1", usage(0, 100, 0, 1000),
                        "{\"type\":\"tool_use\",\"id\":\"tg1\",\"name\":\"Read\",\"input\":{\"file_path\":\"" + big + "\"}}"),
                assistant(at(31), "g2", usage(0, 100, 1000, 5100),
                        "{\"type\":\"tool_use\",\"id\":\"tg2\",\"name\":\"Bash\",\"input\":{\"command\":"
                        + "\"./mvnw -q verify --token=growthsecret9\\nsecond line\"}}"),
                assistant(at(32), "g3", usage(0, 100, 6100, 300),
                        "{\"type\":\"tool_use\",\"id\":\"tg3\",\"name\":\"Edit\",\"input\":{\"file_path\":\"" + big + "\"}}"),
                assistant(at(33), "g4", usage(0, 10, 6400, 150), text),
                assistant(at(34), "g5", usage(0, 10, 6550, 20), text),
                assistant(at(35), "g6", usage(0, 10, 0, 1000), text)) + "\n");
        run(hook, repo, "agent", "{\"session_id\":\"s12\",\"agent_type\":\"demo-agent\",\"agent_id\":\"def\"}");
        run(hook, repo, "close", "{\"session_id\":\"s12\",\"transcript_path\":\"" + tp + "\"}");

        String md = null;
        try (var s = Files.list(trail)) {
            for (Path f : s.filter(f -> f.getFileName().toString().endsWith("--demo-skill.md")).toList()) {
                String c = Files.readString(f);
                if (c.contains("group tests")) md = c;
            }
        }
        if (md == null) { check(false, "growth: a report names the `group tests` agent call"); return; }
        must(md, "| Piece | Origin | 🤖 Model | 🧮 Own billable | ♻️ Cache read | 💾 Cache write |", "per piece: cache columns");
        must(md, "| `🤖 demo-agent (group tests)` | nested | fixture-model | 7,900 | 20,050 | 7,570 |",
                "per piece: the agent's own cache read and write, labelled by its call's description");
        must(md, "### 📈 What grew the context", "growth section rendered");
        must(md, "| 1 | `🤖 demo-agent (group tests)` | Read | `src/Big.java` | 5,000 | 3 | 15,000 |",
                "growth: the Read whose result was re-read most ranks first, re-reads stop at the compaction");
        must(md, "| 2 | `🤖 demo-agent (group tests)` | Bash | `./mvnw -q verify --token=[REDACTED]` | 200 | 2 | 400 |",
                "growth: a command's first line, redacted");
        mustNot(md, "growthsecret9", "growth: no secret from a command reaches the versioned report");
        must(md, "Read +5,000 → 15,000 · Bash +200 → 400 · Edit +50 → 50", "growth: per-tool line of the piece");
        String nodes = Files.readString(trail.resolve("nodes.jsonl"));
        must(nodes, "\"detail\":\"group tests\"", "nodes: the call's description, so a group is a field, not a row position");
        must(nodes, "\"cache_read\":\"20050\",\"cache_write\":\"7570\"", "nodes: the piece's cache split");
        must(nodes, "\"reread\":\"Read:15000,Bash:400,Edit:50\"", "nodes: re-read tokens per tool");
    }

    /**
     * Runs per plan window, from a run's billable tokens and a Pro budget the project wrote —
     * 0133, in tokens since 0134. Budget: Pro 5 h 1,000 tokens, week 5,000; multipliers from
     * extensions.json (Max 5x ×5, Max 20x ×20, weekly for Max not published). A run of exactly
     * 200 billable tokens: Pro 5 per 5 h and 25 per week, Max 5x 25, Max 20x 100. In the
     * summary, two runs of 25 and 75 tokens and one minute each — mean 50: Pro 20 and 100,
     * Max 5x 100, and Max 20x capped by time, 300 one-minute runs in 5 h, not 400.
     */
    static void plans(Path hook, Path repo, Path trail, Path transcripts) throws Exception {
        Files.writeString(trail.resolve("plan-limits.json"),
                "{\"max-5x\":{},\"pro\":{\"five_hour_tokens\":-1,\"monthly_tokens\":3,\"weekly_usd\":50}}");
        String bad = runMode(hook, repo, "doctor");
        must(bad, "`max-5x` — only `pro` is read", "doctor: a plan other than the budget plan is refused by name");
        must(bad, "`pro.five_hour_tokens` must be a number above 0, or null", "doctor: a non-positive budget is refused");
        must(bad, "`pro.monthly_tokens` — only five_hour_tokens, weekly_tokens are read", "doctor: an unknown field is refused");
        must(bad, "`pro.weekly_usd` — only five_hour_tokens, weekly_tokens are read",
                "doctor: a budget written in USD before 0134 is refused by name");
        Files.writeString(trail.resolve("plan-limits.json"), "{\"pro\":{\"five_hour_tokens\":1000,\"weekly_tokens\":5000}}");
        must(runMode(hook, repo, "doctor"), "✅ pro five_hour_tokens 1000, pro weekly_tokens 5000", "doctor lists the budgets set");

        Path main = transcripts.resolve("s13.jsonl");
        String tp = main.toString().replace("\\", "\\\\");
        run(hook, repo, "prompt", "{\"session_id\":\"s13\",\"prompt\":\"/demo-skill plans\","
                + "\"transcript_path\":\"" + tp + "\"}");
        Files.writeString(main, "{\"type\":\"assistant\",\"timestamp\":\"" + at(40) + "\",\"message\":{\"model\":"
                + "\"plan-model\",\"id\":\"p1\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"…\"}],"
                + "\"usage\":" + usage(0, 0, 0, 200) + "}}\n");
        run(hook, repo, "close", "{\"session_id\":\"s13\",\"transcript_path\":\"" + tp + "\"}");
        String md = null;
        try (var s = Files.list(trail)) {
            for (Path f : s.filter(f -> f.getFileName().toString().endsWith("--demo-skill.md")).toList()) {
                String c = Files.readString(f);
                if (c.contains("200 billable tokens per run")) md = c;
            }
        }
        if (md == null) { check(false, "plans: a report projects the 200-token run"); return; }
        must(md, "| Plan | 5 h | week |", "plans: one column per window");
        must(md, "| Pro | 5 · budget | 25 · budget |", "plans: Pro = its own budget ÷ the run's billable tokens");
        must(md, "| Max 5x | 25 · budget | not published |", "plans: Max 5x = Pro × 5 on 5 h; weekly not published");
        must(md, "| Max 20x | 100 · budget | not published |", "plans: Max 20x = Pro × 20 on 5 h");

        Files.writeString(trail.resolve("history.jsonl"), Files.readString(trail.resolve("history.jsonl"))
                + planRow(25) + planRow(75));
        String summary = run(hook, repo, "summary", "");
        must(summary, "### Plan windows", "summary: plan windows table");
        must(summary, "| Piece | Runs | Mean billable | Mean active | Pro 5 h | Pro week | Max 5x 5 h | Max 20x 5 h |",
                "summary: a column per plan × window with a published multiplier, none for Max weekly");
        must(summary, "| 📘 plan-skill | 2 | 50 | 1m00s | 20 · budget | 100 · budget | 100 · budget | 300 · time |",
                "summary: mean per piece, and the window's hours cap a run that budget alone would allow more of");
    }

    /**
     * Decision 0195, in a throwaway git project of its own: the files a run wrote through `Bash`
     * (parsed from the transcript, dropped when the call came back an error), through nothing
     * the parser reads (the working tree against the turn's `guard prompt` baseline), their
     * origin per file, and the permission diff comparing whole rules. The project holds a real
     * `settings.local.json`, a rule whose `paths` match `docs/**` and a real `git init`.
     */
    static void bashWrites(Path hook) throws Exception {
        Path repo = Files.createTempDirectory("archhook-audit-0195").toRealPath();
        Files.createDirectories(repo.resolve(".claude/schemas"));
        Files.copy(Paths.get(".claude/schemas/extensions.json"), repo.resolve(".claude/schemas/extensions.json"));
        Files.createDirectories(repo.resolve(".claude/skills/demo-skill"));
        Files.writeString(repo.resolve(".claude/skills/demo-skill/SKILL.md"),
                "---\nname: demo-skill\ndescription: fixture\n---\n\nbody\n");
        Files.createDirectories(repo.resolve(".claude/rules"));
        Files.writeString(repo.resolve(".claude/rules/demo-docs.md"),
                "---\ndescription: fixture\npaths:\n  - \"docs/**\"\nstatus: active\n---\n\nbody\n");
        Path trail = repo.resolve(".claude/audit-usage");
        Files.createDirectories(trail);
        Files.createDirectories(repo.resolve("docs"));
        Path local = repo.resolve(".claude/settings.local.json");
        Files.writeString(local, "{\"permissions\":{\"allow\":[\"Bash(git add *)\",\"Bash(git status)\"]}}");
        new ProcessBuilder("git", "init", "-q").directory(repo.toFile()).inheritIO().start().waitFor();
        Path transcripts = repo.resolve("transcripts");
        Files.createDirectories(transcripts);

        // 1. A heredoc through Bash is listed with origin Bash, and the rule whose paths match it is loaded.
        String t1 = bashTranscript(transcripts, "bw1", "cat > docs/x.md <<'EOF'\\n# hello\\nEOF", false);
        run(hook, repo, "prompt", "{\"session_id\":\"bw1\",\"prompt\":\"/demo-skill bw1\",\"transcript_path\":\"" + t1 + "\"}");
        run(hook, repo, "close", "{\"session_id\":\"bw1\",\"transcript_path\":\"" + t1 + "\"}");
        String md = puReport(trail, "bw1");
        must(md, "docs/x.md  [Bash]", "0195: a Bash heredoc is listed in Files touched with origin Bash");
        must(md, "1× Bash — 1 distinct file", "0195: the count line counts origins");
        must(md, "| `demo-docs.md` | `docs/**` | 1 |", "0195: Rules loaded reads the Bash-written file too");

        // 2. The same command whose result came back an error wrote nothing.
        String t2 = bashTranscript(transcripts, "bw2", "cat > docs/failed.md <<'EOF'\\nx\\nEOF", true);
        run(hook, repo, "prompt", "{\"session_id\":\"bw2\",\"prompt\":\"/demo-skill bw2\",\"transcript_path\":\"" + t2 + "\"}");
        run(hook, repo, "close", "{\"session_id\":\"bw2\",\"transcript_path\":\"" + t2 + "\"}");
        md = puReport(trail, "bw2");
        mustNot(md, "docs/failed.md", "0195: a Bash call that came back is_error contributes no file");

        // 3. A file the working tree gained after the turn baseline, with no tool call, is listed as disk.
        run(hook, repo, "prompt", "{\"session_id\":\"bw3\",\"prompt\":\"/demo-skill bw3\"}");
        exec(hook, repo, "{\"session_id\":\"bw3\",\"prompt\":\"/demo-skill bw3\"}", "guard", "prompt");
        Files.writeString(repo.resolve("docs/disk.txt"), "written by a script");
        run(hook, repo, "flush", "{\"session_id\":\"bw3\"}");
        md = puReport(trail, "bw3");
        must(md, "docs/disk.txt  [disk]", "0195: a file absent from the turn baseline is listed with origin disk");
        check(md.split("\\[disk\\]", -1).length == 2, "0195: the audit trail itself is not listed as a disk write, only the one new file");

        // 4. Written by Write and by Bash: listed once, origin Write.
        String t4 = bashTranscript(transcripts, "bw4", "cat > docs/both.md <<'EOF'\\nx\\nEOF", false);
        run(hook, repo, "prompt", "{\"session_id\":\"bw4\",\"prompt\":\"/demo-skill bw4\",\"transcript_path\":\"" + t4 + "\"}");
        run(hook, repo, "file", "{\"session_id\":\"bw4\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\""
                + repo.resolve("docs/both.md").toString().replace("\\", "\\\\") + "\"}}");
        run(hook, repo, "close", "{\"session_id\":\"bw4\",\"transcript_path\":\"" + t4 + "\"}");
        md = puReport(trail, "bw4");
        must(md, "docs/both.md  [Write]", "0195: Write and Bash on one path keep the origin Write");
        check(md.split("docs/both\\.md  \\[", -1).length == 2, "0195: that path is listed once, not once per origin");

        // 5. The permission diff compares whole rules.
        run(hook, repo, "prompt", "{\"session_id\":\"bw5\",\"prompt\":\"/demo-skill bw5\"}");
        run(hook, repo, "close", "{\"session_id\":\"bw5\"}");
        md = puReport(trail, "bw5");
        must(md, "None. `settings.local.json` did not change between start and end.",
                "0195: permissions unchanged since the run opened -> none reported as added");
        run(hook, repo, "prompt", "{\"session_id\":\"bw6\",\"prompt\":\"/demo-skill bw6\"}");
        Files.writeString(local, "{\"permissions\":{\"allow\":[\"Bash(git add *)\",\"Bash(git status)\",\"Bash(git push *)\"]}}");
        run(hook, repo, "close", "{\"session_id\":\"bw6\"}");
        md = puReport(trail, "bw6");
        must(md, "| `allow: Bash(git push *)` |", "0195: the rule added mid-run is reported");
        mustNot(md, "allow: Bash(git add *)", "0195: the rule that was there at open is not");
    }

    /** A main transcript with one Bash call running `command` (already JSON-escaped), optionally answered by an error. */
    static String bashTranscript(Path dir, String id, String command, boolean error) throws Exception {
        Path f = dir.resolve(id + ".jsonl");
        StringBuilder b = new StringBuilder(assistant(at(10), id + "m", usage(1, 1, 0, 0),
                "{\"type\":\"tool_use\",\"id\":\"" + id + "tu\",\"name\":\"Bash\",\"input\":{\"command\":\"" + command + "\"}}"));
        b.append("\n{\"type\":\"user\",\"timestamp\":\"").append(at(11)).append("\",\"message\":{\"role\":\"user\",\"content\":")
         .append("[{\"type\":\"tool_result\",\"tool_use_id\":\"").append(id).append("tu\"")
         .append(error ? ",\"is_error\":true,\"content\":\"Exit code 1\\nboom\"}" : ",\"content\":\"ok\"}")
         .append("]}}\n");
        Files.writeString(f, b.toString());
        return f.toString().replace("\\", "\\\\");
    }

    // Where `~` resolves for every spawned jar: a throwaway home, never the host's.
    static Path home;
    // Extra environment of the next spawn only; the `not_available_env` variables are removed from every one.
    static java.util.Map<String, String> env = new java.util.HashMap<>();
    static java.util.List<String> notAvailableEnv = new java.util.ArrayList<>();

    /**
     * The account's plan usage (0194). Each case opens a run, replaces the snapshot, and closes —
     * the jar reads it at both. Windows: five_hour reads `five_hour`, weekly reads `seven_day`.
     */
    static void planUsage(Path hook, Path repo, Path trail) throws Exception {
        Path snap = home.resolve(".claude/rate-limits.json");
        Files.createDirectories(snap.getParent());

        // 1 — each window from its own key; the history row holds both deltas
        Files.writeString(snap, snapshot(20.0, 1000, 40.0, 5000));
        String id = puOpen(hook, repo, "pu1");
        Files.writeString(snap, snapshot(23.5, 1000, 41.2, 5000));
        String md = puClose(hook, repo, id, "pu1");
        must(md, "## 📉 Plan usage (account, measured)", "plan usage: the section is in the report");
        must(md, "| 5 h | 20.0 % | 23.5 % | +3.5 pp |", "plan usage: 5 h delta from five_hour");
        must(md, "| week | 40.0 % | 41.2 % | +1.2 pp |", "plan usage: week delta from seven_day");
        must(md, "it is this run's only if nothing else ran", "plan usage: the report says the delta is the account's");
        must(lastHistory(trail), "\"plan_usage\":{\"five_hour\":3.5,\"weekly\":1.2}", "plan usage: history row holds both deltas");
        mustNot(md, "/canary/path", "plan usage: no text of the snapshot reaches the report");
        mustNot(lastHistory(trail), "/canary/path", "plan usage: no text of the snapshot reaches history.jsonl");

        // 2 — a reset of one window invalidates that window only
        Files.writeString(snap, snapshot(20.0, 1000, 40.0, 5000));
        id = puOpen(hook, repo, "pu2");
        Files.writeString(snap, snapshot(23.5, 2000, 41.2, 5000));
        md = puClose(hook, repo, id, "pu2");
        must(md, "| 5 h | 20.0 % | 23.5 % | not valid — the window reset mid-run |", "plan usage: a changed resets_at is not valid");
        must(md, "| week | 40.0 % | 41.2 % | +1.2 pp |", "plan usage: the other window still has its delta");
        must(lastHistory(trail), "\"five_hour\":null,\"weekly\":1.2", "plan usage: history holds null for the invalid window");

        // 3 — no snapshot at all
        Files.delete(snap);
        id = puOpen(hook, repo, "pu3");
        md = puClose(hook, repo, id, "pu3");
        must(md, "not captured — no snapshot at ~/.claude/rate-limits.json", "plan usage: no file is not captured");
        must(md, "| 5 h | — | — |", "plan usage: missing readings show a dash");

        // 4 — a snapshot older than max_age_minutes
        Files.writeString(snap, snapshot(20.0, 1000, 40.0, 5000));
        id = puOpen(hook, repo, "pu4");
        Files.writeString(snap, snapshot(23.5, 1000, 41.2, 5000));
        Files.setLastModifiedTime(snap, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(20 * 60)));
        md = puClose(hook, repo, id, "pu4");
        must(md, "not captured — snapshot 20 min old (max 15)", "plan usage: a stale snapshot is not captured, with its age");
        mustNot(md, "+3.5 pp", "plan usage: a stale snapshot gives no delta");

        // 5 — no snapshot on an account that has no plan window
        Files.delete(snap);
        env.put("CLAUDE_CODE_USE_BEDROCK", "1");
        id = puOpen(hook, repo, "pu5");
        md = puClose(hook, repo, id, "pu5");
        env.clear();
        must(md, "not available — CLAUDE_CODE_USE_BEDROCK is set", "plan usage: a provider variable makes it not available");

        // 6 — equal readings are below the resolution, never +0.0
        Files.writeString(snap, snapshot(20.0, 1000, 40.0, 5000));
        id = puOpen(hook, repo, "pu6");
        md = puClose(hook, repo, id, "pu6");
        must(md, "| 5 h | 20.0 % | 20.0 % | < 0.1 pp |", "plan usage: equal readings read < 0.1 pp");
        mustNot(md, "+0.0 pp", "plan usage: equality is not printed as +0.0 pp");

        // 6b — one step of the resolution is a delta, though 23.4 − 23.3 is 0.0999… in binary
        Files.writeString(snap, snapshot(23.3, 1000, 40.0, 5000));
        id = puOpen(hook, repo, "pu6b");
        Files.writeString(snap, snapshot(23.4, 1000, 40.0, 5000));
        md = puClose(hook, repo, id, "pu6b");
        must(md, "| 5 h | 23.3 % | 23.4 % | +0.1 pp |", "plan usage: one resolution step reads +0.1 pp, not < 0.1");

        // 6c — doctor: a missing snapshot is optional (⚪), a fresh one lists its windows; never ❌
        Files.delete(snap);
        String doc = runMode(hook, repo, "doctor");
        must(doc, "Plan usage ........ ⚪ no snapshot at ~/.claude/rate-limits.json", "plan usage: doctor marks a missing snapshot optional");
        mustNot(doc, "Plan usage ........ ❌", "plan usage: doctor never fails on the personal snapshot");
        Files.writeString(snap, snapshot(20.0, 1000, 40.0, 5000));
        must(runMode(hook, repo, "doctor"), "windows: five_hour, seven_day", "plan usage: doctor lists the windows of a fresh snapshot");

        // 7 — provisional at flush, the delta from the reading taken at close
        Files.writeString(snap, snapshot(20.0, 1000, 40.0, 5000));
        id = puOpen(hook, repo, "pu7");
        Files.writeString(snap, snapshot(22.0, 1000, 40.5, 5000));
        run(hook, repo, "flush", "{\"session_id\":\"" + id + "\"}");
        md = puReport(trail, "pu7");
        must(md, "| 5 h | 20.0 % | 22.0 % | provisional |", "plan usage: the reading at flush is provisional");
        mustNot(md, "+2.0 pp", "plan usage: no delta before close");
        Files.writeString(snap, snapshot(23.5, 1000, 41.2, 5000));
        md = puClose(hook, repo, id, "pu7");
        must(md, "| 5 h | 20.0 % | 23.5 % | +3.5 pp |", "plan usage: close reads the snapshot again, not flush's reading");

        // 9 — the summary's mean counts valid deltas only
        Files.createDirectories(repo.resolve(".claude/skills/pu-skill"));
        Files.writeString(repo.resolve(".claude/skills/pu-skill/SKILL.md"),
                "---\nname: pu-skill\ndescription: fixture\n---\n\nbody\n");
        double[][] runs = {{2.0, 0}, {4.0, 0}, {9.0, 1}};         // the third resets both windows
        for (int i = 0; i < runs.length; i++) {
            Files.writeString(snap, snapshot(10.0, 1000, 10.0, 5000));
            String sid = "pus" + i;
            run(hook, repo, "prompt", "{\"session_id\":\"" + sid + "\",\"prompt\":\"/pu-skill r" + i + "\"}");
            boolean reset = runs[i][1] != 0;
            Files.writeString(snap, snapshot(10.0 + runs[i][0], reset ? 7000 : 1000, 10.0 + runs[i][0], reset ? 9000 : 5000));
            run(hook, repo, "close", "{\"session_id\":\"" + sid + "\"}");
        }
        String summary = run(hook, repo, "summary", "");
        must(summary, "### Plan usage (measured)", "plan usage: summary has the measured table");
        must(summary, "| 📘 pu-skill | 3.0 pp | 3.0 pp | n = 2 |",
                "plan usage: summary mean is over the valid deltas (2.0 and 4.0), the reset run counts in none");
    }

    /**
     * The effort the run's turns were made at (0196): the top-level {@code effort} of every
     * transcript entry, read where {@code message.model} is, aggregated per piece in the order
     * the report meets the transcripts (a nested agent's before the main thread's), absent
     * showing {@code —} and never {@code null}.
     */
    static void effort(Path hook, Path repo, Path trail, Path transcripts) throws Exception {
        // 1 — every entry low: header, run row and the nested agent's node row
        Path main = transcripts.resolve("e1.jsonl");
        Path subs = transcripts.resolve("e1/subagents");
        Files.createDirectories(subs);
        Files.writeString(main, "");
        String tp = main.toString().replace("\\", "\\\\");
        run(hook, repo, "prompt", "{\"session_id\":\"e1\",\"prompt\":\"/demo-skill e1\",\"transcript_path\":\"" + tp + "\"}");
        run(hook, repo, "call", "{\"session_id\":\"e1\",\"tool_name\":\"Agent\",\"tool_use_id\":\"toolu_e1\","
                + "\"tool_input\":{\"subagent_type\":\"demo-agent\",\"description\":\"effort one\"}}");
        Files.writeString(main, assistant(at(10), "em1", usage(10, 10, 100, 100), TEXT, "low") + "\n");
        Files.writeString(subs.resolve("agent-e1.meta.json"), "{\"toolUseId\":\"toolu_e1\"}");
        Files.writeString(subs.resolve("agent-e1.jsonl"), assistant(at(20), "ea1", usage(5, 5, 50, 50), TEXT, "low") + "\n");
        run(hook, repo, "agent", "{\"session_id\":\"e1\",\"agent_type\":\"demo-agent\",\"agent_id\":\"e1\"}");
        run(hook, repo, "close", "{\"session_id\":\"e1\",\"transcript_path\":\"" + tp + "\"}");
        must(puReport(trail, "e1"), "| 🧠 Effort | low |", "effort: every entry low -> header `low`");
        must(lastHistory(trail), "\"effort\":\"low\"", "effort: the run row carries `low`");
        must(lastNodes(trail, "effort one"), "\"effort\":\"low\"", "effort: the nested agent's node row carries `low`");

        // 2 — main low, the subagent high: both named, the subagent's node row high
        main = transcripts.resolve("e2.jsonl");
        subs = transcripts.resolve("e2/subagents");
        Files.createDirectories(subs);
        Files.writeString(main, "");
        tp = main.toString().replace("\\", "\\\\");
        run(hook, repo, "prompt", "{\"session_id\":\"e2\",\"prompt\":\"/demo-skill e2\",\"transcript_path\":\"" + tp + "\"}");
        run(hook, repo, "call", "{\"session_id\":\"e2\",\"tool_name\":\"Agent\",\"tool_use_id\":\"toolu_e2\","
                + "\"tool_input\":{\"subagent_type\":\"demo-agent\",\"description\":\"effort two\"}}");
        Files.writeString(main, assistant(at(10), "em2", usage(10, 10, 100, 100), TEXT, "low") + "\n");
        Files.writeString(subs.resolve("agent-e2.meta.json"), "{\"toolUseId\":\"toolu_e2\"}");
        Files.writeString(subs.resolve("agent-e2.jsonl"), assistant(at(20), "ea2", usage(5, 5, 50, 50), TEXT, "high") + "\n");
        run(hook, repo, "agent", "{\"session_id\":\"e2\",\"agent_type\":\"demo-agent\",\"agent_id\":\"e2\"}");
        run(hook, repo, "close", "{\"session_id\":\"e2\",\"transcript_path\":\"" + tp + "\"}");
        must(puReport(trail, "e2"), "| 🧠 Effort | high, low |", "effort: main low and subagent high -> both in the header");
        must(lastHistory(trail), "\"effort\":\"high, low\"", "effort: the run row carries both");
        must(lastNodes(trail, "effort two"), "\"effort\":\"high\"", "effort: the subagent's node row carries `high`");

        // 3 — no effort field anywhere: a dash, never the word null
        main = transcripts.resolve("e3.jsonl");
        Files.writeString(main, "");
        tp = main.toString().replace("\\", "\\\\");
        run(hook, repo, "prompt", "{\"session_id\":\"e3\",\"prompt\":\"/demo-skill e3\",\"transcript_path\":\"" + tp + "\"}");
        Files.writeString(main, assistant(at(10), "em3", usage(10, 10, 100, 100), TEXT) + "\n");
        run(hook, repo, "close", "{\"session_id\":\"e3\",\"transcript_path\":\"" + tp + "\"}");
        String md = puReport(trail, "e3");
        must(md, "| 🧠 Effort | — |", "effort: no field -> header `—`");
        mustNot(md, "null", "effort: the absent field never prints null");
        mustNot(lastHistory(trail), "\"effort\"", "effort: no field -> no key in the run row");
        must(run(hook, repo, "summary", ""), "| 🤖 Model | 🧠 Effort | Status |", "effort: summary has the Effort column after Model");
    }

    static final String TEXT = "{\"type\":\"text\",\"text\":\"…\"}";

    static String lastNodes(Path trail, String description) throws Exception {
        String hit = "";
        for (String l : Files.readAllLines(trail.resolve("nodes.jsonl"))) {
            if (l.contains(description)) hit = l;
        }
        return hit;
    }

    static String snapshot(double five, long fiveReset, double week, long weekReset) {
        return "{\"cwd\":\"/canary/path\",\"rate_limits\":{\"five_hour\":{\"used_percentage\":" + five
                + ",\"resets_at\":" + fiveReset + "},\"seven_day\":{\"used_percentage\":" + week
                + ",\"resets_at\":" + weekReset + "}}}";
    }

    static String puOpen(Path hook, Path repo, String marker) throws Exception {
        run(hook, repo, "prompt", "{\"session_id\":\"" + marker + "\",\"prompt\":\"/demo-skill " + marker + "\"}");
        return marker;
    }

    static String puClose(Path hook, Path repo, String id, String marker) throws Exception {
        run(hook, repo, "close", "{\"session_id\":\"" + id + "\"}");
        return puReport(repo.resolve(".claude/audit-usage"), marker);
    }

    /** The report whose title carries the marker (reports of one second share a file name, so read at once). */
    static String puReport(Path trail, String marker) throws Exception {
        try (var s = Files.list(trail)) {
            for (Path f : s.filter(f -> f.getFileName().toString().endsWith("--demo-skill.md")).toList()) {
                String c = Files.readString(f);
                if (c.contains("/demo-skill " + marker)) return c;
            }
        }
        check(false, "plan usage: a report for " + marker);
        return "";
    }

    static String lastHistory(Path trail) throws Exception {
        var l = Files.readAllLines(trail.resolve("history.jsonl")).stream().filter(x -> !x.isBlank()).toList();
        return l.get(l.size() - 1);
    }

    static String planRow(long tokens) {
        return "{\"e\":\"run\",\"skill\":\"plan-skill\",\"kind\":\"skill\",\"origin\":\"user\","
                + "\"start\":\"2026-02-01T00:00:00Z\",\"duration_ms\":\"60000\",\"status\":\"✅ success\","
                + "\"tokens_billable\":\"" + tokens + "\",\"tokens_self\":\"" + tokens + "\","
                + "\"files\":\"0\",\"failures\":\"0\",\"report\":\".claude/audit-usage/x--plan-skill.md\"}\n";
    }

    static long rows(Path trail) throws Exception {
        return Files.readAllLines(trail.resolve("history.jsonl")).stream().filter(l -> !l.isBlank()).count();
    }

    static boolean reportOf(Path trail, String piece) throws Exception {
        try (var s = Files.list(trail)) {
            return s.anyMatch(f -> f.getFileName().toString().endsWith("--" + piece + ".md"));
        }
    }

    static String at(int seconds) { return Instant.now().plusSeconds(seconds).toString(); }

    static String usage(int in, int out, int cacheRead, int cacheWrite) {
        return "{\"input_tokens\":" + in + ",\"output_tokens\":" + out + ",\"cache_read_input_tokens\":"
                + cacheRead + ",\"cache_creation_input_tokens\":" + cacheWrite + "}";
    }

    static String assistant(String ts, String id, String usage, String block) {
        return "{\"type\":\"assistant\",\"timestamp\":\"" + ts + "\",\"message\":{\"model\":\"fixture-model\","
                + "\"id\":\"" + id + "\",\"role\":\"assistant\",\"content\":[" + block + "],\"usage\":" + usage + "}}";
    }

    static String assistant(String ts, String id, String usage, String block, String effort) {
        return assistant(ts, id, usage, block).replaceFirst("^\\{", "{\"effort\":\"" + effort + "\",");
    }

    static void must(String text, String needle, String label) {
        if (text.contains(needle)) { System.out.println("✅ " + label); return; }
        System.out.println("❌ " + label + " — missing: " + needle);
        failures++;
    }

    static void check(boolean ok, String label) {
        if (ok) { System.out.println("✅ " + label); return; }
        System.out.println("❌ " + label);
        failures++;
    }

    static void mustNot(String text, String needle, String label) {
        if (!text.contains(needle)) { System.out.println("✅ " + label); return; }
        System.out.println("❌ " + label + " — found: " + needle);
        failures++;
    }

    static String run(Path hook, Path repo, String phase, String stdin) throws Exception {
        return exec(hook, repo, stdin, "audit", phase);
    }

    static String runMode(Path hook, Path repo, String mode) throws Exception {
        return exec(hook, repo, "", mode);
    }

    static String exec(Path hook, Path repo, String stdin, String... argv) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-Duser.home=" + home, "-jar", hook.toString()));
        cmd.addAll(java.util.List.of(argv));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", repo.toString());
        // The host's own account never leaks into a case (0194): its variables are removed, a case adds its own.
        notAvailableEnv.forEach(pb.environment()::remove);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();                                 // audit never blocks: the files are the signal
        return out;
    }
}
