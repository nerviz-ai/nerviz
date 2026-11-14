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
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
    static final String MARK = "a strict YAML parser refuses this frontmatter";

    public static void main(String[] args) throws Exception {
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
        Path p = Files.createTempDirectory("archhook-yaml-ci");
        try {
            Files.createDirectories(p.resolve(".claude/schemas"));
            Files.copy(Paths.get(".claude/schemas/extensions.json"), p.resolve(".claude/schemas/extensions.json"));
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
            proc.waitFor();
            return out;
        } finally {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(x);
            }
        }
    }

    static void fail(String m) { failures++; System.out.println("❌ " + m); }
}
