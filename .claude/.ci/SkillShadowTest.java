///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar doctor` prints `Skill shadowing ... ❌` naming every folder of
// the personal skills directory — `$CLAUDE_CONFIG_DIR/skills` here — whose name is also a skill
// of the project, prints ✅ when none collides and ⚪ when the directory does not exist, and
// that `doctor gate` never fails on the line.
//
// Why this test exists: Claude Code resolves one skill name as enterprise over personal over
// project, so a personal `use-case-design` replaces the project's for every /command and every
// automatic invocation, with no warning. Nothing in the project can refuse that directory;
// the doctor report is where a person sees it. It is runner state — a developer's home is not
// the project — so it must never gate. Decision 0138.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class SkillShadowTest {

    static int failures = 0;
    static final Path HOOK = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();

    public static void main(String[] args) throws Exception {
        Path cfg = Files.createTempDirectory("archhook-shadow-cfg");
        write(cfg.resolve("skills/use-case-design/SKILL.md"), "---\nname: use-case-design\n---\n");
        write(cfg.resolve("skills/my-own-skill/SKILL.md"), "---\nname: my-own-skill\n---\n");
        Files.createDirectories(cfg.resolve("skills/empty-folder"));

        String out = doctor(cfg, "doctor");
        expect(out, "Skill shadowing ... ❌ 1 personal skill(s) replace this project's: use-case-design",
                "a colliding personal skill is named; a distinct one and a folder with no SKILL.md are not");

        Files.delete(cfg.resolve("skills/use-case-design/SKILL.md"));
        expect(doctor(cfg, "doctor"), "Skill shadowing ... ✅ no personal skill replaces one of .claude/skills",
                "no collision — green");

        expect(doctor(cfg.resolve("missing"), "doctor"), "Skill shadowing ... ⚪ no ",
                "no personal skills directory — optional, not red");

        write(cfg.resolve("skills/use-case-design/SKILL.md"), "---\nname: use-case-design\n---\n");
        String gate = doctor(cfg, "doctor", "gate");
        Matcher g = Pattern.compile("❌ doctor gate: (.*) failed").matcher(gate);
        if (!gate.contains("Skill shadowing ... ❌")) {
            fail("gate run — the fixture did not show the line red, so the case proves nothing");
        } else if (g.find() && Arrays.asList(g.group(1).split(", ")).contains("Skill shadowing")) {
            fail("doctor gate names `Skill shadowing` — runner state must never gate");
        } else {
            System.out.println("✅ doctor gate ignores a red Skill shadowing line");
        }

        System.out.println(failures == 0 ? "\n✅ Skill shadowing reported, and never gated."
                : "\n❌ " + failures + " case(s) failed.");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void expect(String out, String needle, String name) {
        if (out.contains(needle)) {
            System.out.println("✅ " + name);
        } else {
            fail(name + " — expected \"" + needle + "\"");
            System.out.println(out);
        }
    }

    /** A project with one real skill name, run with the personal directory at {@code cfg}. */
    static String doctor(Path cfg, String... mode) throws Exception {
        Path p = Files.createTempDirectory("archhook-shadow-ci");
        Path schemas = p.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        write(p.resolve(".claude/skills/use-case-design/SKILL.md"), "---\nname: use-case-design\n---\n");
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", HOOK.toString()));
        cmd.addAll(List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(p.toFile());
        pb.environment().remove("CLAUDE_PROJECT_DIR");
        pb.environment().put("CLAUDE_CONFIG_DIR", cfg.toString());
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
