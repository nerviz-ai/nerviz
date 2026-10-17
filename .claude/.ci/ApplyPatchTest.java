///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the write hooks — `guard write`, `check`, `format`, `schema` and `audit file` —
// judge a Copilot CLI `apply_patch` as they judge Claude Code's `Write`/`Edit`, and still exit 0
// on a payload they cannot read.
//
// Why this test exists: the Copilot CLI reads `.claude/settings.json` and sends every file write
// as `tool_name: "Edit"` with the patch as the whole `tool_input` — no `file_path`. Every mode
// read `file_path`, found none and exited 0: a line went into an approved spec, a forbidden
// import through, and nothing was formatted (lessons-learned-022 § 3). The fix translates the
// patch into the payloads the modes already read (decision 0169), and a translation rots in
// silence — a wrong hunk reconstruction refuses a legitimate spec close, a lost path lets a
// write through, and neither shows in review.
//
// The three Copilot payloads are real captures (`fixtures/apply-patch/`, from
// lessons-learned-022/captures/, with the project prefix replaced by `{ROOT}`): an Add of two
// files with absolute paths, an Update with an absolute path, and the Update of a spec with a
// path relative to the root. The other patches are built here in the same grammar.
//
// Runs in a throwaway project holding a copy of the real extensions.json. Runs the jar, not the
// source: that is what the hooks launch.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ApplyPatchTest {

    static Path hook;
    static Path root;
    static String session;
    static int failures = 0;

    static final String SPEC = "docs/use-cases/UC-900-probe/UC-900-spec.md";
    static final String MODEL = "src/main/java/com/example/probesingle/domain/model/";

    public static void main(String[] args) throws Exception {
        hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        Path fixtures = Paths.get(".claude/.ci/fixtures/apply-patch").toAbsolutePath();
        root = Files.createTempDirectory("archhook-patch-ci").toRealPath();
        session = "ci-patch-" + System.nanoTime();
        Files.createDirectories(root.resolve(".claude/schemas"));
        Files.copy(Paths.get(".claude/schemas/extensions.json"),
                root.resolve(".claude/schemas/extensions.json"));

        String d1Add = fixture(fixtures, "d1-add-two-files-absolute.json");
        String d1Update = fixture(fixtures, "d1-update-absolute.json");
        String d4Spec = fixture(fixtures, "d4-update-spec-relative.json");

        // ── guard write (PreToolUse) ─────────────────────────────────────────
        spec("approved");
        expect("guard", "write", d4Spec, 2, "UC-900-probe is approved",
                "guard write · captured D4 patch, relative path, on an approved spec — refused");
        expect("guard", "write", d4Spec.replace("Update File: docs/", "Update File: "
                        + json(root.toString().replace('\\', '/')) + "/docs/"), 2, "UC-900-probe",
                "guard write · the same patch with an absolute path — refused");
        expect("guard", "write", patch("*** Update File: " + SPEC + "\n@@\n ---\n-status: approved\n"
                        + "+status: implemented\n ---\n"), 0, null,
                "guard write · a hunk closing `status: approved` → `implemented` — admitted");
        expect("guard", "write", patch("*** Update File: " + SPEC + "\n@@\n ## Checklist\n-- [ ] a\n"
                        + "+- [x] a\n"), 0, null,
                "guard write · a hunk toggling `[ ]` → `[x]` — admitted");
        expect("guard", "write", patch("*** Update File: " + SPEC + "\n@@\n ## Checklist\n-- [ ] a\n"
                        + "+- [x] b\n"), 2, "immutable",
                "guard write · a hunk toggling and changing a word — refused");
        expect("guard", "write", patch("*** Update File: " + SPEC + "\n@@\n ## Checklist\n-- [ ] a\n"
                        + "+- [x] a\n@@\n # UC-900\n+- probe\n"), 2, "immutable",
                "guard write · a legal hunk then an illegal one in the same file — refused");
        expect("guard", "write", patch("*** Delete File: " + SPEC + "\n"), 2, "immutable",
                "guard write · Delete File of a frozen spec — refused");
        expect("guard", "write", patch("*** Update File: notes/a.md\n*** Move to: "
                        + "docs/use-cases/UC-900-probe/moved.md\n@@\n-a\n+b\n"), 2, "immutable",
                "guard write · Move to into a frozen folder — refused");
        expect("guard", "write", patch("*** Add File: notes/ok.md\n+ok\n*** Update File: " + SPEC
                        + "\n@@\n # UC-900\n+- probe\n"), 2, "UC-900-probe",
                "guard write · two files, the second frozen — refused, naming it");
        expect("guard", "write", patch("*** Update File: docs/use-cases/UC-900-probe/CHANGELOG.md\n"
                        + "@@\n+- x\n"), 0, null,
                "guard write · CHANGELOG.md inside the frozen folder — admitted");
        expect("guard", "write", claudeEdit(SPEC, "# UC-900", "# UC-900\n- probe"), 2, "immutable",
                "guard write · Claude Code `Edit` with file_path — refused, as before");
        spec("draft");
        expect("guard", "write", d4Spec, 0, null,
                "guard write · the captured D4 patch on a draft spec — admitted");
        expect("guard", "prompt", "{\"session_id\":\"" + session
                        + "\",\"prompt\":\"/persistence-architect design it\"}", 0, null,
                "guard prompt · opens a design phase");
        expect("guard", "write", d1Add, 2, "outside its territory",
                "guard write · captured D1 Add under src/ inside a design phase — refused");
        expect("guard", "prompt", "{\"session_id\":\"" + session + "\",\"prompt\":\"hi\"}", 0, null,
                "guard prompt · closes the phase");
        expect("guard", "write", d1Add, 0, null,
                "guard write · the same Add with no phase open — admitted");

        // ── check (PostToolUse) ──────────────────────────────────────────────
        Files.createDirectories(root.resolve(".claude"));
        Files.writeString(root.resolve(".claude/forbidden-imports.txt"),
                MODEL.substring(0, MODEL.length() - 1) + "|org.springframework.\n");
        write(MODEL + "Probe.java", "package com.example.probesingle.domain.model;\n\n"
                + "import org.springframework.stereotype.Component;\n\n@Component\npublic class Probe {}\n");
        write("src/test/java/com/example/probesingle/domain/model/ProbeTest.java",
                "package com.example.probesingle.domain.model;\n\nclass ProbeTest {}\n");
        expect("check", null, d1Add, 2, "org.springframework.",
                "check · captured D1 Add, two files, absolute — forbidden import refused");
        expect("check", null, patch("*** Update File: " + MODEL + "Probe.java\n@@\n-a\n+b\n"), 2,
                "org.springframework.", "check · the same file through a relative path — refused");
        write(MODEL + "Probe.java", "package com.example.probesingle.domain.model;\n\npublic class Probe {}\n");
        expect("check", null, d1Add, 0, null, "check · captured D1 Add, clean file — admitted");

        // ── format (PostToolUse) ─────────────────────────────────────────────
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Path log = root.resolve("mvnw.log");
        Path sh = root.resolve("mvnw");
        Files.writeString(sh, "#!/bin/sh\necho \"$@\" >> \"$(dirname \"$0\")/mvnw.log\"\n");
        sh.toFile().setExecutable(true);
        Files.writeString(root.resolve("mvnw.cmd"), "@echo %* >> \"%~dp0mvnw.log\"\r\n");
        expect("format", null, d1Update, 0, null, "format · captured D1 Update, absolute — exit 0");
        String ran = Files.isRegularFile(log) ? Files.readString(log) : "";
        check(ran.contains("spotless:apply") && ran.contains("-pl ."),
                "format · the wrapper ran `spotless:apply` for the file's module (saw: "
                        + ran.strip().replace('\n', ' ') + ")");
        Files.delete(sh);
        Files.delete(root.resolve("mvnw.cmd"));
        Files.delete(root.resolve("pom.xml"));

        // ── schema (PostToolUse) ─────────────────────────────────────────────
        write(".claude/skills/bad/SKILL.md", "---\nname: bad\ndescription: x\nnot_a_field: 1\n---\n\n# bad\n");
        expect("schema", null, patch("*** Update File: .claude/skills/bad/SKILL.md\n@@\n-x\n+y\n"), 2,
                "not_a_field", "schema · patch on a SKILL.md with an unknown field — refused, naming it");
        expect("schema", null, patch("*** Update File: notes/plain.md\n@@\n-x\n+y\n"), 0, null,
                "schema · patch on a file no type matches while another is invalid — no sweep, exit 0");

        // ── audit file (PostToolUse) ─────────────────────────────────────────
        Path state = root.resolve(".claude/audit-usage/.state");
        Files.createDirectories(state);
        Path trail = state.resolve(session + ".ndjson");
        Files.writeString(trail, "");
        expect("audit", "file", d1Add, 0, null, "audit file · captured D1 Add — exit 0");
        String events = Files.readString(trail);
        check(events.contains(MODEL + "Probe.java")
                        && events.contains("src/test/java/com/example/probesingle/domain/model/ProbeTest.java"),
                "audit file · one `file` event per file of the patch");

        // ── unreadable payloads: every mode exits 0 ──────────────────────────
        spec("approved");
        for (String bad : List.of(
                "{\"session_id\":\"" + session + "\",\"tool_name\":\"Edit\",\"tool_input\":\"not a patch\"}",
                patch(""),
                "{}",
                "not json")) {
            for (String[] m : new String[][] {{"guard", "write"}, {"check", null}, {"format", null},
                    {"audit", "file"}}) {
                expect(m[0], m[1], bad, 0, null, m[0] + (m[1] == null ? "" : " " + m[1])
                        + " · unreadable payload `" + bad.substring(0, Math.min(bad.length(), 40))
                        + "` — exit 0");
            }
        }
        expect("schema", null, "{\"session_id\":\"" + session
                        + "\",\"tool_name\":\"Edit\",\"tool_input\":\"not a patch\"}", 0, null,
                "schema · a string tool_input that is not a patch — exit 0, no sweep");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — a write hook does not judge a"
                    + " Copilot `apply_patch` as it judges Claude Code's payload (decision 0169).");
            System.exit(1);
        }
        System.out.println("✅ every write hook reads the Copilot CLI's apply_patch, and exits 0 on"
                + " what it cannot read.");
    }

    /** A fixture with `{ROOT}` replaced by this run's project, and this run's session. */
    static String fixture(Path dir, String name) throws Exception {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8).strip()
                .replace("{ROOT}", json(root.toString().replace('\\', '/')))
                .replaceFirst("\"session_id\":\"[^\"]*\"", "\"session_id\":\"" + session + "\"");
    }

    /** A Copilot CLI PreToolUse/PostToolUse payload carrying `sections` as its patch. */
    static String patch(String sections) {
        return "{\"session_id\":\"" + session + "\",\"hook_event_name\":\"PreToolUse\","
                + "\"tool_name\":\"Edit\",\"tool_input\":\""
                + json("*** Begin Patch\n" + sections + "*** End Patch\n") + "\"}";
    }

    static String claudeEdit(String rel, String oldS, String newS) {
        return "{\"session_id\":\"" + session + "\",\"tool_name\":\"Edit\",\"tool_input\":{"
                + "\"file_path\":\"" + json(root.resolve(rel).toString()) + "\","
                + "\"old_string\":\"" + json(oldS) + "\",\"new_string\":\"" + json(newS) + "\"}}";
    }

    static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static void spec(String status) throws Exception {
        write(SPEC, "---\nstatus: " + status + "\n---\n# UC-900\n\n## Checklist\n- [ ] a\n");
    }

    static void write(String rel, String content) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    static void check(boolean ok, String label) {
        System.out.println((ok ? "✅ " : "❌ ") + label);
        if (!ok) failures++;
    }

    /** Runs one mode with `stdin`, checks the exit code and, when given, a fragment of stderr. */
    static void expect(String mode, String sub, String stdin, int wanted, String says,
                       String label) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", hook.toString(), mode));
        if (sub != null) cmd.add(sub);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", root.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        boolean ok = code == wanted && (says == null || out.contains(says));
        check(ok, label + (ok ? "" : " — expected exit " + wanted
                + (says == null ? "" : " saying `" + says + "`") + ", got " + code));
        if (!ok) System.out.println(out);
    }
}
