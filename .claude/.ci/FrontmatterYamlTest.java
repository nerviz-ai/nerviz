///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar schema` refuses, by file and line, frontmatter a strict YAML
// parser refuses — in a skill, an agent and a rule — and reads every shape the tree writes.
// Refused: `: ` inside an unquoted value (the observed defect), its continuation line too, an
// unquoted value ending in `:`, a value starting with `` ` ``, `@`, `*` or `- `, a quote not
// closed on its line, text after a closing quote, an unknown `\` escape, a bad block-scalar
// header, an unclosed flow list, a tab in the indentation, a key at a column no mapping has,
// and a key twice in one mapping. Read: a quoted value holding `: `, a `>` and `>-` block whose
// lines hold `: `, a `paths:` list, a hook block of a skill's frontmatter (`- matcher:` entries
// with a nested `hooks:` list and a flow `args`), a URL, a `#` comment, `''` inside `'…'`, and
// the same key in two list entries. The tree itself is swept by `validate` › `frontmatter schema`.
//
// Why this test exists: `agents/mapstruct-installer.md` shipped a `description` with an
// unquoted `: `. Claude Code's parser forgave it, so nothing in this repository saw it; GitHub
// Copilot's refused it and dropped the agent (lessons-learned-022 § 9). The scanner is written
// by hand, with no YAML library, so each shape it refuses or reads is a case here. Decision 0174.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class FrontmatterYamlTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(
            System.getProperty("archhook.test.jar", ".claude/hooks/ArchHook.jar")).toAbsolutePath();
    static final String MARK = "a strict YAML parser refuses this frontmatter";
    static final String SKILL = ".claude/skills/probe/SKILL.md";
    static final String TOO_LONG = "characters; maximum is ";

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(HOOK)) throw new IllegalStateException("no hook jar at " + HOOK);
        descriptionLength();
        String colon = "---\nname: probe\ndescription: writes the `**Mappers: manual.**` paragraph\n---\n";
        for (String rel : List.of(".claude/rules/probe.md", ".claude/agents/probe.md",
                ".claude/skills/probe/SKILL.md")) {
            refused(rel, colon, "line 3 — `description` — `: ` inside an unquoted value",
                    "unquoted `: ` refused in " + rel);
        }

        refused("---\ndescription: one line\n  and a second: with a colon\n---\n",
                "line 3 — `: ` inside an unquoted value",
                "a continuation line of an unquoted value is checked like its first");
        refused("---\ndescription: Use when:\n---\n", "line 2 — `description` — `: ` inside",
                "an unquoted value ending in `:`");
        refused("---\ndescription: `x` first\n---\n", "line 2 — `description` — a backtick cannot start",
                "a backtick starting an unquoted value");
        refused("---\ndescription: @x\n---\n", "line 2 — `description` — `@` cannot start",
                "`@` starting an unquoted value");
        refused("---\ndescription: *x\n---\n", "line 2 — `description` — anchors, aliases",
                "an alias");
        refused("---\ndescription: - x\n---\n", "line 2 — `description` — `- ` cannot start",
                "a block entry on the key's line");
        refused("---\ndescription: \"open\n---\n", "line 2 — `description` — a quoted value not closed",
                "a double quote not closed on its line");
        refused("---\ndescription: 'a' b\n---\n", "line 2 — `description` — text after the closing quote",
                "text after a closing quote");
        refused("---\ndescription: \"C:\\dir\"\n---\n", "line 2 — `description` — `\\d` is no YAML escape",
                "an unknown escape in a double-quoted value");
        refused("---\ndescription: >x\n---\n", "line 2 — `description` — `>x` is no block-scalar header",
                "a malformed block-scalar header");
        refused("---\ntools: [Read, Write\n---\n", "line 2 — `tools` — a flow collection not closed",
                "a flow list not closed on its line");
        refused("---\npaths:\n\t- \"x\"\n---\n", "line 3 — a tab in the indentation",
                "a tab in the indentation");
        refused("---\nhooks:\n    Stop:\n  PreToolUse:\n---\n",
                "line 4 — the key `PreToolUse` is indented to a column no open mapping has",
                "a key at a column no open mapping has");
        refused("---\nstatus: active\nstatus: draft\n---\n",
                "line 3 — the key `status` appears twice in one mapping",
                "a duplicate key — Psych reads it, the Node parsers refuse it");
        refused("---\nname: x\nnot a key\n---\n", "line 3 — a line that is neither `key: value`",
                "a bare line at mapping level");

        read("---\ndescription: \"writes the `**Mappers: manual.**` paragraph\"\n---\n",
                "a double-quoted value holding `: ` — the fix applied to mapstruct-installer");
        read("---\ndescription: >\n  Use when: the request says so.\n  Second line: too.\nmodel: opus\n---\n",
                "a `>` block whose lines hold `: `");
        read("---\nargument-hint: >-\n  [path]: optional\n---\n", "a `>-` block");
        read("---\npaths:\n  - \"**/src/**/*.java\"\n  - \"**/*.yml\"\nstatus: active\n---\n",
                "a `paths:` list of quoted globs");
        read("""
                ---
                name: probe
                hooks:
                  PostToolUse:
                    - matcher: "Edit|Write"
                      hooks:
                        - type: command
                          command: java
                          args: [".claude/hooks/ArchHook.java", "check"]
                        - type: command
                          command: java
                  Stop:
                    - hooks:
                        - type: command
                model: sonnet
                ---
                """, "a skill's hook block — `- key:` entries, nested lists, a flow `args`, a key repeated across entries");
        read("---\ndescription: see https://example.com/a:b # a comment: here\n---\n",
                "a URL in an unquoted value, and a `#` comment holding `: `");
        read("---\n# a comment line\ndescription: 'it''s fine: quoted'\n---\n", "`''` inside a single-quoted value");
        read("---\ndescription: one line\n  continued without a colon\n---\n", "a continuation line with no `: `");

        System.out.println(failures == 0 ? "\n✅ Frontmatter a strict YAML parser refuses is refused by name."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Decision 0215: GitHub Copilot's loader rejects a skill whose `description` is over 1024
     * characters and Claude Code does not, so `schema` reads the cap from `types.skill.max_description_chars`.
     * The length diagnostic is asserted by name: a partial skill fixture raises unrelated errors.
     */
    static void descriptionLength() throws Exception {
        String over = words(1025), at = words(1024);
        lengthRefused("plain " + over.length(), "---\nname: probe\ndescription: " + over + "\n---\n", 1024, 1025);
        lengthRefused("double-quoted", "---\nname: probe\ndescription: \"" + over + "\"\n---\n", 1024, 1025);
        lengthRefused("single-quoted", "---\nname: probe\ndescription: '" + over + "'\n---\n", 1024, 1025);
        lengthRefused("folded", "---\nname: probe\ndescription: >\n" + fold(over, "\n") + "\nmodel: sonnet\n---\n",
                1024, 1025);
        lengthRefused("folded, CRLF", ("---\nname: probe\ndescription: >\n" + fold(over, "\n")
                + "\nmodel: sonnet\n---\n").replace("\n", "\r\n"), 1024, 1025);
        lengthAccepted("plain, exactly at the cap", "---\nname: probe\ndescription: " + at + "\n---\n", 1024);
        lengthAccepted("folded, exactly at the cap", "---\nname: probe\ndescription: >\n" + fold(at, "\n")
                + "\nmodel: sonnet\n---\n", 1024);
        lengthAccepted("folded CRLF, exactly at the cap", ("---\nname: probe\ndescription: >\n" + fold(at, "\n")
                + "\nmodel: sonnet\n---\n").replace("\n", "\r\n"), 1024);

        // The cap is data: a fixture schema with a smaller one refuses what 1024 admits.
        String sixty = words(60);
        lengthRefused("a fixture cap of 50", "---\nname: probe\ndescription: " + sixty + "\n---\n", 50, 60);
        lengthAccepted("a fixture cap of 60", "---\nname: probe\ndescription: " + sixty + "\n---\n", 60);
    }

    /** Exactly {@code n} characters, no leading or trailing space, safe as a plain scalar. */
    static String words(int n) {
        return "abcdefgh ".repeat(n / 9 + 1).substring(0, n);
    }

    /** The words of {@code s} on indented lines, which a folded scalar joins back with single spaces. */
    static String fold(String s, String eol) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + w.length() > 72) {
                lines.add("  " + cur);
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        lines.add("  " + cur);
        return String.join(eol, lines);
    }

    static void lengthRefused(String name, String content, int cap, int length) throws Exception {
        Res r = run(SKILL, content + "\n# Probe\n", cap);
        String needle = "`description` is " + length + " characters; maximum is " + cap;
        if (r.code == 2 && r.out.contains(needle) && r.out.contains("types.skill.max_description_chars"))
            System.out.println("✅ refused (exit 2) — description over the cap: " + name);
        else fail("description over the cap: " + name + " — expected exit 2 and \"" + needle
                + "\", got exit " + r.code + "\n" + r.out);
    }

    static void lengthAccepted(String name, String content, int cap) throws Exception {
        Res r = run(SKILL, content + "\n# Probe\n", cap);
        if (r.code == 2 && r.out.contains("`probe` is in no skill_classes class")
                && !r.out.contains(TOO_LONG)) {
            System.out.println("✅ not refused for length — " + name);
        } else fail("description at the cap: " + name
                + " — expected only the partial fixture's class diagnostic, got exit "
                + r.code + "\n" + r.out);
    }

    static void refused(String frontmatter, String needle, String name) throws Exception {
        refused(".claude/rules/probe.md", frontmatter, needle, name);
    }

    static void refused(String rel, String frontmatter, String needle, String name) throws Exception {
        String out = schema(rel, frontmatter + "\n# Probe\n");
        if (out.contains(needle) && out.contains(MARK)) System.out.println("✅ refused — " + name);
        else fail(name + " — expected \"" + needle + "\"\n" + out);
    }

    static void read(String frontmatter, String name) throws Exception {
        String out = schema(".claude/rules/probe.md", frontmatter + "\n# Probe\n");
        if (!out.contains(MARK)) System.out.println("✅ read — " + name);
        else fail(name + " — refused\n" + out);
    }

    /** `schema` over one file of a throwaway project, the PostToolUse form: only that file is checked. */
    static String schema(String rel, String content) throws Exception {
        return run(rel, content, null).out;
    }

    record Res(int code, String out) { }

    /** {@code cap}, when given, is the fixture schema's `types.skill.max_description_chars`, replaced or added. */
    static Res run(String rel, String content, Integer cap) throws Exception {
        Path p = Files.createTempDirectory("archhook-yaml-ci");
        try {
            Files.createDirectories(p.resolve(".claude/schemas"));
            Path sch = p.resolve(".claude/schemas/extensions.json");
            if (cap == null) {
                Files.copy(Paths.get(".claude/schemas/extensions.json"), sch);
            } else {
                String s = Files.readString(Paths.get(".claude/schemas/extensions.json"), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"max_description_chars\"\\s*:\\s*\\d+").matcher(s);
                if (m.find()) {
                    s = m.replaceFirst("\"max_description_chars\": " + cap);
                } else {
                    java.util.regex.Matcher k = java.util.regex.Pattern
                            .compile("\"types\"\\s*:\\s*\\{\\s*\"skill\"\\s*:\\s*\\{").matcher(s);
                    if (!k.find()) throw new IllegalStateException("extensions.json has no types.skill to anchor the cap");
                    s = s.substring(0, k.end()) + "\n      \"max_description_chars\": " + cap + "," + s.substring(k.end());
                }
                Files.writeString(sch, s, StandardCharsets.UTF_8);
            }
            Path f = p.resolve(rel);
            Files.createDirectories(f.getParent());
            Files.writeString(f, content, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(
                    ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString(), "schema");
            pb.environment().put("CLAUDE_PROJECT_DIR", p.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String json = "{\"tool_input\":{\"file_path\":\"" + f.toString().replace("\\", "\\\\") + "\"}}";
            proc.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().close();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Res(proc.waitFor(), out);
        } finally {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
            }
        }
    }

    static void fail(String m) { failures++; System.out.println("❌ " + m); }
}
