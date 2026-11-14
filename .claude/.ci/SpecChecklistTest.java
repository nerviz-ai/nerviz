///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar spec check` reports a spec whose `## Implementation order
// (checklist)` does not match the blocks the spec carries (missing, extra and duplicate ids),
// that `guard write` refuses to move a draft spec to `approved` while that holds, and that
// `schema` fails when the `spec_checklist` data and the template disagree.
//
// Why this test exists: a mode that throws exits 0 through the top-level catch and looks like it
// passed, and the guard rule sits on the path of every spec edit — a false positive blocks an
// approval, a false negative lets issue #191 back in. Each case runs the real jar over a
// temp project holding a copy of the real extensions.json, so the data under test is
// production data. Decision 0197.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class SpecChecklistTest {

    static final String FOLDER = "docs/use-cases/UC-901-x";
    static final String SPEC = FOLDER + "/UC-901-x-spec.md";
    static final String TEMPLATE = ".claude/skills/new-feature/templates/feature-spec.md.example";

    static final List<String> CORE_HEAD = List.of("1", "2", "3", "4", "5", "6", "7");
    static final List<String> CORE_TAIL = List.of("16", "17", "18", "19");
    static final List<String> PERSIST = List.of("8", "9", "10", "11", "12");
    static final List<String> REST = List.of("13", "14", "15");

    static Path hook;
    static Path repo;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        repo = Paths.get("").toAbsolutePath();

        List<String> full = ids(CORE_HEAD, PERSIST, REST, CORE_TAIL);
        List<String> jobsOnly = ids(CORE_HEAD, List.of("J"), CORE_TAIL);

        // ── spec check ──────────────────────────────────────────────────────────
        check("full spec, 19 lines", spec("draft", false, true, true, false, full), 0, "19 lines");
        check("full spec, line 9 removed", spec("draft", false, true, true, false, without(full, "9")),
                2, "missing: 9");
        check("jobs-only spec, core and J", spec("draft", false, false, false, true, jobsOnly),
                0, "12 lines");
        check("jobs-only spec with 13-15 left in",
                spec("draft", false, false, false, true, ids(CORE_HEAD, REST, List.of("J"), CORE_TAIL)),
                2, "extra: 13, 14, 15");
        check("jobs-only spec without J", spec("draft", false, false, false, true, ids(CORE_HEAD, CORE_TAIL)),
                2, "missing: J");
        List<String> dup = new ArrayList<>(full);
        dup.add(dup.indexOf("5"), "5");
        check("duplicated 5", spec("draft", false, true, true, false, dup), 2, "duplicate: 5");
        check("no checklist section", spec("draft", false, true, true, false, null), 2, "no checklist section");
        List<String> all19 = ids(CORE_HEAD, PERSIST, REST, CORE_TAIL);
        check("path: short, 19 lines", spec("draft", true, true, true, false, all19), 0, "19 lines");
        check("path: short, 18 lines", spec("draft", true, true, true, false, without(all19, "12")),
                2, "missing: 12");

        // ── guard write ─────────────────────────────────────────────────────────
        String bad = spec("draft", false, false, false, true, ids(CORE_HEAD, CORE_TAIL));
        String good = spec("draft", false, false, false, true, jobsOnly);
        guard("Edit draft to approved on the bad jobs-only spec", bad,
                edit("status: draft", "status: approved"), 2, "cannot be approved", "missing: J");
        guard("Edit draft to approved on the good spec", good,
                edit("status: draft", "status: approved"), 0, null, null);
        guard("Write of an approved bad spec over a draft", bad,
                write(bad.replace("status: draft", "status: approved")), 2, "cannot be approved", null);
        guard("MultiEdit draft to approved on the bad spec", bad,
                multiEdit("status: draft", "status: approved"), 2, "cannot be approved", null);
        guard("checklist toggle on an approved good spec",
                good.replace("status: draft", "status: approved"),
                edit("- [ ] 5. Use case command", "- [x] 5. Use case command"), 0, null, null);
        guard("Edit of a draft spec that does not touch status", bad,
                edit("# UC-901 — x", "# UC-901 — y"), 0, null, null);

        // ── schema ──────────────────────────────────────────────────────────────
        schemaDrift("schema on a template that lost one checklist line");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `spec check`, the approval guard or"
                    + " the `schema` drift check is NOT enforcing what extensions.json"
                    + " `spec_checklist` declares.");
            System.exit(1);
        }
        System.out.println("✅ Spec checklist enforced: missing, extra and duplicate ids reported, a draft"
                + " cannot be approved over a wrong checklist, template and data cannot drift.");
    }

    // ── cases ────────────────────────────────────────────────────────────────────

    static void check(String label, String spec, int wanted, String mustSay) throws Exception {
        Path root = project();
        Path file = root.resolve(SPEC);
        Files.createDirectories(file.getParent());
        Files.writeString(file, spec, StandardCharsets.UTF_8);
        report(label, run(root, "", "spec", "check", FOLDER), wanted, mustSay, null);
    }

    static void guard(String label, String onDisk, String toolJson, int wanted, String mustSay,
                      String mustAlso) throws Exception {
        Path root = project();
        Path file = root.resolve(SPEC);
        Files.createDirectories(file.getParent());
        Files.writeString(file, onDisk, StandardCharsets.UTF_8);
        String stdin = toolJson.replace("@FILE@", jsonEscape(file.toString()))
                .replace("@SESSION@", "ci-spec-" + System.nanoTime());
        report(label, run(root, stdin, "guard", "write"), wanted, mustSay, mustAlso);
    }

    /** `schema` over a copy of the tree whose template lost line 9: data and template disagree. */
    static void schemaDrift(String label) throws Exception {
        Path root = Files.createTempDirectory("archhook-spec-schema").toRealPath();
        copyTree(repo.resolve(".claude"), root.resolve(".claude"));
        for (String f : List.of("CLAUDE.md", "README.md", "LICENSE", "NOTICE")) {
            if (Files.isRegularFile(repo.resolve(f))) Files.copy(repo.resolve(f), root.resolve(f));
        }
        Result clean = run(root, "", "schema");
        report("schema on the tree as shipped", clean, 0, null, null);
        Path template = root.resolve(TEMPLATE);
        String text = Files.readString(template, StandardCharsets.UTF_8);
        String cut = text.lines().filter(l -> !l.startsWith("- [ ] 9. ")).collect(Collectors.joining("\n"));
        if (cut.equals(text.stripTrailing())) {
            System.out.println("❌ " + label + " — the template has no `- [ ] 9. ` line to remove");
            failures++;
            return;
        }
        Files.writeString(template, cut + "\n", StandardCharsets.UTF_8);
        Result r = run(root, "", "schema");
        report(label, r, 2, "spec_checklist", "9 only in data");
    }

    // ── spec builder ──────────────────────────────────────────────────────────────

    /**
     * A spec text: frontmatter, `## 3` and `## 4` present or `none`, `## 3.6` present when
     * `jobs`, the checklist lines for `checklist` (null: no section).
     */
    static String spec(String status, boolean shortPath, boolean persistence, boolean rest,
                       boolean jobs, List<String> checklist) {
        StringBuilder b = new StringBuilder();
        b.append("---\nstatus: ").append(status).append("\n");
        if (shortPath) b.append("path: short\n");
        b.append("---\n\n# UC-901 — x\n\n## 1. Use case\n\nDetail: `00-use-case.md`.\n\n---\n\n");
        b.append("## 2. Domain model — implement first\n\nDetail: `10-domain.md`.\n\n---\n\n");
        b.append("## 3. Persistence — implement after 2\n\n")
                .append(persistence ? "Detail: `20-persistence.md`.\n" : "none — no storage\n").append("\n---\n\n");
        b.append("## 3.6 Jobs — conditional\n\n")
                .append(jobs ? "Detail: `35-jobs.md`.\n" : "none — no schedule\n").append("\n---\n\n");
        b.append("## 4. REST API — implement after 2\n\n")
                .append(rest ? "Detail: `30-rest.md`.\n" : "none — no HTTP adapter\n").append("\n---\n\n");
        b.append("## 5. Tests — validates 1-4\n\nDetail: `40-tests.md`.\n\n---\n\n");
        if (checklist != null) {
            b.append("## Implementation order (checklist)\n\n");
            for (String id : checklist) {
                String name = switch (id) {
                    case "5" -> "Use case command";
                    default -> "Step " + id;
                };
                b.append("- [ ] ").append(id).append(". ").append(name).append("\n");
            }
            b.append("\n---\n\n");
        }
        b.append("## Resolved divergences\n\nnone\n");
        return b.toString();
    }

    @SafeVarargs
    static List<String> ids(List<String>... parts) {
        List<String> out = new ArrayList<>();
        for (List<String> p : parts) out.addAll(p);
        return out;
    }

    static List<String> without(List<String> in, String id) {
        List<String> out = new ArrayList<>(in);
        out.remove(id);
        return out;
    }

    // ── tool input builders ───────────────────────────────────────────────────────

    static String edit(String oldS, String newS) {
        return "{\"session_id\":\"@SESSION@\",\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"@FILE@\","
                + "\"old_string\":\"" + jsonEscape(oldS) + "\",\"new_string\":\"" + jsonEscape(newS) + "\"}}";
    }

    static String multiEdit(String oldS, String newS) {
        return "{\"session_id\":\"@SESSION@\",\"tool_name\":\"MultiEdit\",\"tool_input\":{\"file_path\":\"@FILE@\","
                + "\"edits\":[{\"old_string\":\"" + jsonEscape(oldS) + "\",\"new_string\":\""
                + jsonEscape(newS) + "\"}]}}";
    }

    static String write(String content) {
        return "{\"session_id\":\"@SESSION@\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"@FILE@\","
                + "\"content\":\"" + jsonEscape(content) + "\"}}";
    }

    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }

    // ── process plumbing ──────────────────────────────────────────────────────────

    record Result(int exit, String out) { }

    static Path project() throws IOException {
        Path root = Files.createTempDirectory("archhook-spec-ci").toRealPath();
        Path schemas = root.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(repo.resolve(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        return root;
    }

    static Result run(Path root, String stdin, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", hook.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", root.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    static void report(String label, Result r, int wanted, String mustSay, String mustAlso) {
        String why = r.exit() != wanted ? "expected exit " + wanted + ", got " + r.exit()
                : mustSay != null && !r.out().contains(mustSay) ? "output does not say `" + mustSay + "`"
                : mustAlso != null && !r.out().contains(mustAlso) ? "output does not say `" + mustAlso + "`"
                : null;
        if (why == null) {
            System.out.println("✅ " + label + " (exit " + r.exit() + ")");
            return;
        }
        System.out.println("❌ " + label + " — " + why);
        System.out.println(r.out());
        failures++;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String rel = from.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("audit-usage") || rel.contains("node_modules")) continue;
                Path dest = to.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
