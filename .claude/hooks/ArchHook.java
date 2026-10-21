///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// ArchHook — project hooks in a single Java file.
//
// Why Java and not bash+PowerShell: this template targets Java developers,
// so the JDK is the only dependency that can be assumed on any operating system.
// Writing the logic twice (.sh and .ps1) would violate the "each rule has a single
// owner" rule this repository enforces everywhere else — and the two copies would drift.
//
// Execution: hooks launch the precompiled jar, java -jar .claude/hooks/ArchHook.jar <mode>;
// a person may run the source directly, java ArchHook.java <mode> (JDK 21+).
//   check   PostToolUse — forbidden imports and names + incremental compile (blocks)
//   format  PostToolUse — spotless on the touched module              (never blocks)
//   tests   Stop        — verify of the changed modules, deferred while a writer
//                         subagent runs; agent-start/agent-end at SubagentStart/Stop
//                         keep that marker                              (blocks)
//   schema  Pre/PostToolUse + Stop — frontmatter, injection paths, skill bodies (blocks)
//   audit   lifecycle   — execution trail of every project skill and agent (never blocks)
//   guard   PreToolUse  — a skill writes only its class's territory, approved specs
//                         frozen, build skills unreachable mid-design       (blocks);
//                         `guard status` prints the open phase for the cockpit mod
//   compose manual      — every compose service up, no foreign container on our ports,
//                         compose image tags equal to the ones src/test pins (never blocks)
//   context SubagentStart (generated project only) — injects the pattern catalog into
//                         each agent whose class declares pattern_catalog  (never blocks)
//   doctor  manual      — diagnoses the setup on this machine         (never blocks)
//   boundary-map manual — writes a generated project's .claude/forbidden-imports.txt from a
//                         blueprint, derived for a single-module one                 (exit 2 on none)
//   upgrade-recipe manual — writes the OpenRewrite recipe that takes a project below the Spring Boot
//                         floor to it, from `doctor.platform.upgrade` and Spring's own metadata
//                         (exit 2 on a target below the floor or data it cannot read)
//   test-diff manual    — snapshots the JUnit reports of a build and compares a later run against it:
//                         a test that was green and is red, or gone, is a regression (exit 2 on one)
//   build   PostToolUse (this repo) + CI — compiles this file into ArchHook.jar
//                         under the pinned JDK; --verify rejects a jar that differs (blocks)
//
// Dependencies: JDK. Nothing else.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.ZipFile;

public class ArchHook {

    static final Path ROOT = Paths.get(
            Optional.ofNullable(System.getenv("CLAUDE_PROJECT_DIR")).orElse("."))
            .toAbsolutePath().normalize();
    static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Windows consoles default to cp1252: forcing UTF-8 avoids broken accented characters. */
    static final PrintStream ERR = new PrintStream(
            new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "check";
        // Only the modes the runtime invokes as hooks are handed a JSON payload on
        // stdin. Reading it for every mode made `export`, `doctor` and `compose` block
        // forever whenever stdin was neither a closed pipe nor a TTY — a command that
        // hangs with no output, which is how lessons-learned-011 § 2 found it. The list
        // is here and not in extensions.json on purpose: it is the dispatch itself, the
        // same place the mode names already live.
        String stdin = switch (mode) {
            // `check <path>` is the form a person, or the bootstrap's boundary probe, types: the
            // path is in argv and stdin is never read, so an open stdin cannot hang it
            // (lessons-learned-020 § 3). `audit genesis` is addressed the same way.
            case "check" -> args.length > 1 ? "" : readAll(System.in);
            case "audit" -> args.length > 1 && "genesis".equals(args[1]) ? "" : readAll(System.in);
            case "format", "tests", "schema", "guard", "context" -> readAll(System.in);
            // `compose gate` is the hook-invoked half of `compose` and needs the payload for
            // `stop_hook_active`. The bare `compose` a person types stays out of the list, which
            // is the hang lessons-learned-011 § 2 found.
            case "compose" -> args.length > 1 && "gate".equals(args[1]) ? readAll(System.in) : "";
            default -> "";
        };
        try {
            switch (mode) {
                case "check"  -> { if (args.length > 1) checkPath(args[1]); else check(filePath(stdin)); }
                case "format" -> format(filePath(stdin));
                case "tests"  -> tests(args.length > 1 ? args[1] : "stop", stdin);
                case "schema" -> schema(stdin);
                case "audit"  -> { if (args.length > 1 && "genesis".equals(args[1])) auditGenesis(args);
                                   else audit(args.length > 1 ? args[1] : "flush", stdin); }
                case "guard"  -> guard(args.length > 1 ? args[1] : "write", stdin);
                case "compose" -> compose(args.length > 1 ? args[1] : "report", stdin);
                case "context" -> context(args.length > 1 ? args[1] : "subagent", stdin);
                case "export" -> export(args);
                case "boundary-map" -> boundaryMap(args);
                case "upgrade-recipe" -> upgradeRecipe(args);
                case "test-diff" -> testDiff(args);
                case "doctor" -> doctor(args.length > 1 && "gate".equals(args[1]));
                case "build"  -> build(args);
                default -> { err("Unknown mode: " + mode); System.exit(0); }
            }
        } catch (Exception e) {
            // A hook must never crash the session because of its own error.
            err("⚠️  ArchHook (" + mode + ") failed: " + e);
            // `doctor gate` is a CI gate, not a hook: a throw there must fail closed, or the
            // job goes green on a report that never finished.
            System.exit("doctor".equals(mode) && args.length > 1 && "gate".equals(args[1]) ? 1 : 0);
        }
        System.exit(0);
    }

    // ── check ────────────────────────────────────────────────────────────────
    /**
     * {@code check <path>} — the same check for a file named in argv, the form a person and the
     * bootstrap's boundary probe type. The payload form passes in silence on anything it cannot
     * check, which is right for a hook on every edit and wrong for a command typed to prove
     * something: here a missing or non-Java file exits 1 and a clean one says so. Invoked by
     * nothing in {@code settings.json}; the hook stays on the stdin form. A relative path resolves
     * against {@link #ROOT}, the same root the module lookup uses.
     */
    static void checkPath(String arg) throws Exception {
        Path abs = ROOT.resolve(arg).normalize();
        if (!arg.endsWith(".java") || !Files.isRegularFile(abs)) {
            err("❌ check: " + abs + " is not an existing .java file — nothing was checked.");
            System.exit(1);
        }
        check(abs.toString());
        System.out.println("✅ check: no boundary violation in " + relative(abs));
    }

    static void check(String file) throws Exception {
        if (file == null || !file.endsWith(".java")) return;
        Path abs = Paths.get(file);
        if (!Files.isRegularFile(abs)) return;
        String rel = relative(abs);

        // 1. forbidden imports
        Path map = ROOT.resolve(".claude/forbidden-imports.txt");
        if (Files.isRegularFile(map)) {
            String src = Files.readString(abs, StandardCharsets.UTF_8);
            List<String> viol = forbiddenImports(rel, src,
                    Files.readAllLines(map, StandardCharsets.UTF_8));
            if (!viol.isEmpty()) {
                err("❌ Architectural boundary violation — " + rel);
                err("Forbidden imports or qualified names found:");
                viol.forEach(v -> err("  " + v));
                err("");
                err("The responsibility is in the wrong module. Move the code, not the rule.");
                err("Rule: .claude/rules/architecture-ddd.md");
                System.exit(2);
            }
        } else {
            err("⚠️  .claude/forbidden-imports.txt does not exist.");
            err("   Boundary enforcement OFF — nothing was checked.");
            err("   This file is generated by /init-project from the active blueprint.");
        }

        // 2. incremental compile of the touched module — Maven or Gradle, per `build_tools`
        String module = moduleOf(rel);
        if (module == null) return;                       // no build file yet: mid-generation
        if (buildTools().isEmpty()) {
            // Silence here would look exactly like a pass: say which file stopped the check.
            err("⚠️  No `build_tools` block in " + SCHEMA_FILE + " — compilation NOT checked.");
            return;
        }
        BuildTool tool = buildTool();
        if (tool == null) {
            err("⚠️  Build wrapper missing — compilation NOT checked.");
            err("   Run /init-project (starter.tgz already brings mvnw or gradlew).");
            return;
        }
        List<String> cmd = new ArrayList<>(List.of(tool.path()));
        cmd.addAll(tool.compileArgs(module));
        Proc r = run(cmd.toArray(String[]::new));
        if (r.exit != 0) {
            err("❌ Compilation failed in " + module);
            tail(r.out, 30).forEach(ArchHook::err);
            System.exit(2);
        }
    }

    /** The token of a boundary-map line that stands for any one module — a format, like the `|`. */
    static final String MODULE_SLOT = "{module}";

    /**
     * The lines of `.claude/forbidden-imports.txt` — `<module-path>|<prefix>` — that the file at
     * {@code rel} breaks, each as {@code <prefix>  (forbidden in module '<module>')}. The single
     * owner of the match: {@code check} runs it on the file just edited, {@code doctor}'s
     * `Boundary violations` line on every file of the project, so the edit-time block and the
     * CI gate can never disagree on what a violation is.
     *
     * <p>A prefix named in code with no {@code import} — {@code @org.springframework.stereotype
     * .Component} — is the same dependency, and is reported as {@code qualified name}: matched
     * over {@link #codeOnly}, so a comment, a Javadoc link or a string naming the prefix is not
     * one. Design: `.claude/decisions/0174-strict-frontmatter-qualified-names-and-the-local-build.md`.
     *
     * <p>A {@value #MODULE_SLOT} segment in the module path matches any one directory, and the
     * same name replaces {@value #MODULE_SLOT} in the prefix: a line written for a blueprint's
     * module slot covers every module, those created after the map was written included, and
     * forbids each one its own packages. Design: `.claude/decisions/0180-module-slot-boundary-gradle-wrapper-and-approval-checks.md`.
     */
    static List<String> forbiddenImports(String rel, String src, List<String> mapLines) {
        List<String> viol = new ArrayList<>();
        String code = null;                          // stripped once, only for a file some rule covers
        for (String line : mapLines) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int bar = line.indexOf('|');
            if (bar < 0) continue;
            String module = line.substring(0, bar).strip();
            String prefix = line.substring(bar + 1).strip();
            if (prefix.isEmpty()) continue;
            if (module.contains(MODULE_SLOT)) {
                Matcher slot = Pattern.compile(Arrays.stream(module.split(Pattern.quote(MODULE_SLOT), -1))
                        .map(Pattern::quote).collect(Collectors.joining("([^/]+)")) + "/").matcher(rel);
                if (!slot.lookingAt()) continue;
                module = module.replace(MODULE_SLOT, slot.group(1));
                prefix = prefix.replace(MODULE_SLOT, slot.group(1));
            } else if (!rel.startsWith(module + "/")) continue;
            Pattern p = Pattern.compile(
                    "(?m)^\\s*import\\s+(static\\s+)?" + Pattern.quote(prefix));
            if (p.matcher(src).find()) {
                viol.add(prefix + "  (forbidden in module '" + module + "')");
                continue;
            }
            if (code == null) code = codeOnly(src);
            if (Pattern.compile("(?<![\\w$.])" + Pattern.quote(prefix)).matcher(code).find()) {
                viol.add(prefix + "  (qualified name, forbidden in module '" + module + "')");
            }
        }
        return viol;
    }

    /**
     * A Java source with every comment, string, char and text-block literal, and the
     * {@code package} and {@code import} statements, blanked: what is left names only what the
     * code itself depends on. A lexer, not a parser — enough to tell a qualified name from a
     * mention of one.
     */
    static String codeOnly(String src) {
        StringBuilder b = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            int end;
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int j = i + 3;
                while (j < n && !src.startsWith("\"\"\"", j)) j += src.charAt(j) == '\\' ? 2 : 1;
                end = Math.min(n, j + 3);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                end = Math.min(n, j + 1);
            } else {
                b.append(c);
                i++;
                continue;
            }
            b.append(' ');
            i = end;
        }
        return b.toString().replaceAll("(?m)^\\s*(?:package|import)\\b[^;]*;", " ");
    }

    // ── format ───────────────────────────────────────────────────────────────
    static void format(String file) throws Exception {
        if (file == null || !file.endsWith(".java")) return;
        Path abs = Paths.get(file);
        if (!Files.isRegularFile(abs)) return;
        String module = moduleOf(relative(abs));
        String mvnw = wrapper();
        if (module == null || mvnw == null) return;
        run(mvnw, "-q", "-o", "-pl", module, "spotless:apply");   // failure is ignored
    }

    // ── tests ────────────────────────────────────────────────────────────────
    static void tests(String phase, String stdin) throws Exception {
        switch (phase) {
            case "agent-start" -> { testsAgentStart(stdin); return; }
            case "agent-end"   -> { testsAgentEnd(stdin); return; }
            default            -> { }
        }
        // If the Stop hook already blocked before, don't block again: avoids cycles.
        if (Pattern.compile("\"stop_hook_active\"\\s*:\\s*true").matcher(stdin).find()) return;

        String mvnw = wrapper();
        if (mvnw == null) return;

        String writer = testsWriterRunning(stdin);
        if (writer != null) {
            err("⏸ Tests deferred: " + writer + " still running in background."
                    + " The Stop of the turn its result opens runs them.");
            return;
        }

        Proc diff = run(gitCmd(), "diff", "--name-only", "HEAD");
        Proc untracked = run(gitCmd(), "ls-files", "--others", "--exclude-standard");
        if (diff.exit != 0 && untracked.exit != 0) return;        // no git or no HEAD

        Set<String> modules = Stream.concat(diff.out.stream(), untracked.out.stream())
                .filter(f -> f.endsWith(".java"))
                .map(ArchHook::moduleOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (modules.isEmpty()) return;

        List<String> goals = testsStopArgs();
        if (goals.isEmpty()) {
            err("❌ tests.stop_maven_args is missing or empty in " + SCHEMA_FILE + ".");
            err("   The Stop gate does not guess what to hand Maven. Restore the key —");
            err("   `/arch-adopt` rewrites the file — or run `./mvnw verify` by hand.");
            System.exit(2);
        }
        String list = String.join(",", modules);
        String ran = String.join(" ", goals);
        err("▶ " + ran + " for affected modules: " + list);
        List<String> cmd = new ArrayList<>(List.of(mvnw, "-q", "-o", "-pl", list, "-am"));
        cmd.addAll(goals);
        Proc r = run(cmd.toArray(String[]::new));
        if (r.exit != 0) {
            err("❌ " + ran + " failed — a formatting failure is fixed by `./mvnw spotless:apply`");
            List<String> relevant = r.out.stream()
                    .filter(l -> l.matches(".*(ERROR|FAIL|Tests run).*"))
                    .collect(Collectors.toList());
            tail(relevant.isEmpty() ? r.out : relevant, 25).forEach(ArchHook::err);
            System.exit(2);
        }
        err("✅ " + ran + " green — CI's `./mvnw clean verify` also runs what these flags skip");
    }

    /**
     * What the `Stop` gate hands Maven after `-pl <modules> -am`, from `tests.stop_maven_args`
     * of `extensions.json`; empty when the key is missing, which the gate refuses to guess.
     *
     * <p>Form 7c of `claude-code-architect-designer`, motivated by axis 16 — `tests` owns the
     * gate, and it stopped at `test` while `checkstyle-test` and `spotless:check` sit at
     * `verify`, so it printed green on a tree CI rejects (issue #126). Data, not a constant,
     * so the POM stays the owner of what `verify` checks and a project can skip a plugin of
     * its own; the closest rejected form named those two goals in the hook, restating the POM.
     * Design: .claude/decisions/0142-stop-gate-runs-verify-without-its.md
     */
    static List<String> testsStopArgs() throws IOException {
        String raw = readOrNull(ROOT.resolve(SCHEMA_FILE));
        return raw == null ? List.of()
                : asStrList(get(Json.parse(raw), "tests", "stop_maven_args"));
    }

    /**
     * Records a writer subagent as running, so a main-thread `Stop` that fires while it works
     * in the background does not test its half-written tree. Registered at `SubagentStart`
     * (with `agent-end` at `SubagentStop`) in the generated project's settings; writes a
     * marker per `agent_id` only for an agent whose class in `agent_classes` has
     * `executor: true`, since only those leave `src/` or a POM mid-edit.
     *
     * <p>Form 7c of `claude-code-architect-designer`, motivated by axis 16 — `tests` owns
     * the gate, so it owns the state the gate reads. The closest rejected form wrote the
     * marker from `context subagent` and cleared it from `audit agent`, which a project can
     * switch off. Design: .claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md
     */
    static void testsAgentStart(String stdin) throws Exception {
        Object in = Json.parse(stdin);
        String id = asStr(get(in, "agent_id"));
        String type = asStr(get(in, "agent_type"));
        if (id == null || id.isBlank() || type == null) return;
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        String cls = agentClassOf(sch, type);
        if (cls == null || !Boolean.TRUE.equals(get(sch, "agent_classes", "classes", cls, "executor"))) return;
        Path dir = testsState(asStr(get(in, "session_id")));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(safeName(id)), type + "\n" + System.currentTimeMillis() + "\n",
                StandardCharsets.UTF_8);
    }

    /**
     * Deletes the marker of this `agent_id` only. `SubagentStop` also fires for internal
     * agents, interleaved with a background one (0041), so a shared counter would be cleared
     * by the wrong event; an id with no marker deletes nothing.
     */
    static void testsAgentEnd(String stdin) throws Exception {
        Object in = Json.parse(stdin);
        String id = asStr(get(in, "agent_id"));
        if (id == null || id.isBlank()) return;
        Files.deleteIfExists(testsState(asStr(get(in, "session_id"))).resolve(safeName(id)));
    }

    /**
     * The agent type of a writer subagent still running in this session, or null. A marker
     * older than `tests.writer_agent_max_minutes` is deleted and ignored: an agent killed by
     * machine sleep never reaches `SubagentStop`, and its marker must not switch the gate off
     * for the rest of the session. No value in `extensions.json` defers nothing.
     */
    static String testsWriterRunning(String stdin) throws IOException {
        Path dir = testsState(asStr(get(Json.parse(stdin), "session_id")));
        if (!Files.isDirectory(dir)) return null;
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        long capMs = num(get(sch, "tests", "writer_agent_max_minutes")) * 60_000L;
        long now = System.currentTimeMillis();
        String running = null;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.collect(Collectors.toList())) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                long started = 0;
                try {
                    started = lines.size() > 1 ? Long.parseLong(lines.get(1).strip()) : 0;
                } catch (NumberFormatException e) {
                    // unreadable marker: treated as expired below
                }
                if (started > 0 && now - started < capMs) {
                    if (running == null) running = lines.get(0).strip();
                } else {
                    Files.deleteIfExists(f);
                }
            }
        }
        return running;
    }

    static Path testsState(String session) {
        return Paths.get(System.getProperty("java.io.tmpdir"), "archhook-tests",
                session == null || session.isBlank() ? "unknown" : safeName(session));
    }

    static String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    // ── doctor ───────────────────────────────────────────────────────────────
    /** Labels of the {@link #report} lines that printed ❌ in this process, in order. */
    static final List<String> REPORT_FAILED = new ArrayList<>();

    /**
     * Answers "does this work on my machine?" without having to guess. With {@code gate}, the
     * same report, then exit 1 when a line whose label {@code doctor.gate.labels} of
     * extensions.json lists printed ❌ — a line not printed passes, and an unreadable list
     * fails closed. Form 7c, invoked by a CI step of the generated project, not by a hook
     * event: the bare {@code doctor} only ever printed, so the step that claimed to prove
     * enforcement went green on {@code ENFORCEMENT OFF} (issue #104). The closer form, a
     * {@code grep} of this output in the project's {@code build.yml}, was rejected because
     * that file is never re-exported while this text changes with every update. Design:
     * .claude/decisions/0128-doctor-gate-fails-the-generated-boundaries-job.md
     */
    static void doctor(boolean gate) {
        err("ArchHook doctor");
        err("  OS ................ " + System.getProperty("os.name")
                + (WINDOWS ? "  (Windows — no shell is used, exec form)" : ""));
        err("  Java .............. " + System.getProperty("java.version"));
        err("  Project root ...... " + ROOT);
        report("CLAUDE_PROJECT_DIR", System.getenv("CLAUDE_PROJECT_DIR") != null,
                "set", "NOT set — hooks use the current directory");
        BuildTool tool = buildTool();
        String w = tool == null ? null : tool.path();
        report("Build wrapper", w != null, w == null ? "" : w + "  (" + tool.id() + ")",
                "missing — run /init-project (starter.tgz already brings mvnw or gradlew)");
        Path map = ROOT.resolve(".claude/forbidden-imports.txt");
        long rules = 0;
        try {
            if (Files.isRegularFile(map)) {
                rules = Files.readAllLines(map).stream()
                        .map(String::strip)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#") && l.contains("|"))
                        .count();
            }
        } catch (IOException ignored) { }
        report("Boundaries", rules > 0, rules + " active rules",
                "0 rules — ENFORCEMENT OFF. Generated by /init-project");
        doctorBoundarySweep(map, rules);
        long badSchema = -1;
        if (Files.isRegularFile(ROOT.resolve(SCHEMA_FILE))) {
            List<String> errs = new ArrayList<>();
            try {
                sweep(asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE)))), errs);
                badSchema = errs.size();
            } catch (IOException ignored) { }
        }
        report("Schema", badSchema == 0,
                "all extension files pass",
                badSchema < 0 ? "no " + SCHEMA_FILE + " — validation OFF"
                              : badSchema + " files with invalid frontmatter");
        Map<String, Object> hb = hookBuild();
        if (hb != null) {
            String jarProblem = hookJarProblem(hb);
            report("Hook jar", jarProblem == null, "built from the current " + asStr(hb.get("source")),
                    jarProblem);
        }
        Path auditDir = auditDir();
        if (Files.isDirectory(auditDir)) {
            long runs = 0;
            try (Stream<Path> s = Files.list(auditDir)) {
                runs = s.filter(f -> f.toString().endsWith(".md")).count();
            } catch (IOException ignored) { }
            // Runs only: the trail measures billable tokens and prices nothing, so a model
            // Anthropic ships next has nothing to be missing from (decision 0134).
            report("Audit", true, runs + " execution(s) recorded", null);

            Map<String, Object> auditSch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
            List<String> ovr = auditSch == null ? null : auditOverrideProblems(auditSch, auditDir);
            if (ovr != null) {
                String set = ovr.remove(ovr.size() - 1);
                report("Audit overrides", ovr.isEmpty(),
                        set.isEmpty() ? "none set — every piece follows its class" : set,
                        String.join("; ", ovr));
            }
            List<String> lim = auditSch == null ? null : planLimitProblems(auditSch, auditDir);
            if (lim != null) {
                String set = lim.remove(lim.size() - 1);
                report("Plan limits", lim.isEmpty(),
                        set.isEmpty() ? "no budget set — no plan window projection" : set,
                        String.join("; ", lim));
            }

            // A synthetic touched file that matches a real rule's `paths` — the exact
            // shape that once threw `ArrayIndexOutOfBoundsException` inside rule
            // inference and froze every report from that point on, silently (the
            // top-level catch in `main` exits 0). Cheap enough to run every `doctor`.
            boolean auditRulesOk;
            try {
                auditRules(Set.of("src/main/java/example/domain/model/Sample.java"));
                auditRulesOk = true;
            } catch (Exception ex) {
                auditRulesOk = false;
            }
            report("Audit rule inference", auditRulesOk,
                    "renders without error on a touched .java file",
                    "auditRules() throws — every report freezes once a run touches"
                            + " src/**. See ArchHook.java's auditRules()");
        } else {
            err("  Audit ............. ⚪ no " + AUDIT_DIR + " — execution trail OFF (optional)");
        }

        Path mcpFile = ROOT.resolve(".mcp.json");
        if (Files.isRegularFile(mcpFile)) {
            List<String> mcpErrs = new ArrayList<>();
            Map<String, Object> schRoot = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
            String mcpContent = readOrNull(mcpFile);
            checkMcp(schRoot == null ? null : asMap(schRoot.get("mcp")), ".mcp.json",
                    mcpContent, mcpErrs);
            Map<String, Object> mcpRoot = asMap(Json.parse(mcpContent));
            Map<String, Object> servers = mcpRoot == null ? null : asMap(mcpRoot.get("mcpServers"));
            int n = servers == null ? 0 : servers.size();
            report("MCP", mcpErrs.isEmpty(), n + " server(s) declared in .mcp.json",
                    mcpErrs.size() + " problem(s) — run `java ArchHook.java schema`");
        } else {
            err("  MCP ................ no .mcp.json — nothing declared (optional)");
        }
        Path settingsFile = ROOT.resolve(".claude/settings.json");
        if (Files.isRegularFile(settingsFile)) {
            List<String> hookErrs = new ArrayList<>();
            Map<String, Object> schRoot = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
            String settingsContent = readOrNull(settingsFile);
            if (schRoot != null) {
                checkSettings(schRoot, ".claude/settings.json", settingsContent, hookErrs);
            }
            Map<String, Object> sRoot = asMap(Json.parse(settingsContent));
            Map<String, Object> hookMap = sRoot == null ? null : asMap(sRoot.get("hooks"));
            int entries = 0;
            if (hookMap != null) {
                for (Object groups : hookMap.values()) {
                    for (Object group : asList(groups)) {
                        Map<String, Object> g = asMap(group);
                        if (g != null) entries += asList(g.get("hooks")).size();
                    }
                }
            }
            int evts = hookMap == null ? 0 : hookMap.size();
            report("Hooks", hookErrs.isEmpty(),
                    entries + " registration(s) across " + evts + " event(s)",
                    hookErrs.size() + " problem(s) — run `java ArchHook.java schema`");
        } else {
            err("  Hooks .............. no .claude/settings.json — no hook registered");
        }

        doctorMods();

        doctorShadowedSkills();

        doctorAgentInstructions();

        doctorVirtualThreads();

        doctorPlatform();

        provenance();

        ComposeReport comp = composeReport();
        report("Compose", comp.ok(), comp.summary(), comp.summary());
        for (String d : comp.detail()) err("    " + d);
        for (String cw : comp.warnings()) err("    ⚠️  " + cw);

        boolean git = false;
        try { git = run("git", "rev-parse", "HEAD").exit == 0; } catch (Exception ignored) { }
        report("git HEAD", git, "exists",
                "no commits — the tests hook does not run (git diff HEAD fails)");

        if (git) {
            String ucRoot = ucReferenceRoot();
            if (ucRoot != null && !Files.isDirectory(ROOT.resolve(ucRoot))) {
                report("UC references", true, "no " + ucRoot + " — nothing to check (optional)", "");
            } else {
                List<String> orphans = orphanUseCaseRefs();
                report("UC references", orphans.isEmpty(), "every cited use-case folder exists",
                        orphans.size() + " citation(s) point at a folder that is gone");
                for (String o : orphans) err("    " + o);
            }
            String blRoot = asStr(get(doctorSpec("bl_references"), "root"));
            if (blRoot != null && !Files.isDirectory(ROOT.resolve(blRoot))) {
                report("BL references", true, "no " + blRoot + " — nothing to check (optional)", "");
            } else {
                List<String> unknown = unknownBacklogRefs();
                report("BL references", unknown.isEmpty(), "every cited backlog row exists",
                        unknown.size() + " citation(s) name a backlog row the file does not have");
                for (String o : unknown) err("    " + o);
            }
        }
        err("");
        err(rules > 0 && w != null
                ? "✅ Setup operational."
                : "⚠️  Setup incomplete — see marked lines above.");
        if (!gate) return;
        List<String> gated = asStrList(get(doctorSpec("gate"), "labels"));
        if (gated.isEmpty()) {
            err("❌ doctor gate: no `doctor.gate.labels` in " + SCHEMA_FILE
                    + " — nothing to gate on, so the gate fails closed. Restore the list"
                    + " (an /arch-adopt update writes it).");
            System.exit(1);
        }
        List<String> hit = REPORT_FAILED.stream().filter(gated::contains).distinct()
                .collect(Collectors.toList());
        if (!hit.isEmpty()) {
            err("❌ doctor gate: " + String.join(", ", hit)
                    + " failed — each marked line above names its fix.");
            System.exit(1);
        }
        err("✅ doctor gate: no gated line failed (" + String.join(", ", gated) + ").");
    }

    /**
     * Where this `.claude/` came from, and what has been edited since. Reads the stamp
     * `export` writes; in the repository that produces one there is no stamp and none is
     * expected, which is a third state and not a failure. The comparison is local: an
     * update overwrites (D54), so the list of locally edited files is the list of what
     * that update would discard, and it is the only thing a person can act on before
     * running it. How far behind the source is cannot be answered without reaching the
     * source — `arch-adopt` does that when it fetches; `doctor` does not open the
     * network.
     */
    static void provenance() {
        Path stamp = ROOT.resolve(".claude/.arch-provenance.json");
        boolean origin = Files.isDirectory(ROOT.resolve(".claude/blueprints"))
                && get(asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE)))), "export") != null;
        if (!Files.isRegularFile(stamp)) {
            err("  Provenance ........ " + (origin
                    ? "⚪ origin repository — writes stamps, carries none"
                    : "⚠️  no stamp — this .claude/ was not written by `export`"));
            return;
        }
        Map<String, Object> s = asMap(Json.parse(readOrNull(stamp)));
        if (s == null) {
            report("Provenance", false, "", ".arch-provenance.json is not valid JSON");
            return;
        }
        Map<String, Object> files = asMap(s.get("files"));
        List<String> changed = new ArrayList<>();
        int missing = 0;
        if (files != null) {
            for (Map.Entry<String, Object> e : files.entrySet()) {
                String cur = readOrNull(ROOT.resolve(e.getKey()));
                if (cur == null) { missing++; changed.add(e.getKey() + " (missing)"); }
                else if (!sha256(cur).equals(asStr(e.getValue()))) changed.add(e.getKey());
            }
        }
        int tracked = files == null ? 0 : files.size();
        report("Provenance", changed.isEmpty(),
                "blueprint " + orDash(asStr(s.get("blueprint"))) + " · ref "
                        + orDash(asStr(s.get("ref"))) + " · " + orDash(asStr(s.get("exported_at")))
                        + " · " + tracked + " file(s) unchanged since",
                changed.size() + " of " + tracked + " exported file(s) edited locally"
                        + (missing > 0 ? " (" + missing + " missing)" : "")
                        + " — an update overwrites them");
        for (String c : changed.stream().sorted().limit(8).collect(Collectors.toList())) {
            err("    " + c);
        }
        if (changed.size() > 8) err("    … and " + (changed.size() - 8) + " more");
    }

    /**
     * Versioned files citing a `docs/use-cases/UC-NNN-slug/` folder that no longer exists.
     * Deleting a use case folder leaves its citations behind — a `docker-compose.yml`
     * comment pointing at `25-mensageria.md` of a case renamed in the next run
     * (lessons-learned-012 § 14) — and nothing sweeps for them, because each citation is
     * correct in the commit that wrote it. Reported, never blocking: a stale reference is
     * a documentation defect, and `doctor` is where a person is already reading.
     * Pattern, scanned extensions and exemptions are data — `doctor.uc_references` in
     * .claude/schemas/extensions.json (invariant 10).
     */
    static Map<String, Object> ucReferenceSpec() {
        try {
            return asMap(get(asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE)))),
                    "doctor", "uc_references"));
        } catch (Exception e) { return null; }
    }

    /** The directory whose absence means there is nothing to sweep, or null. */
    static String ucReferenceRoot() {
        Map<String, Object> spec = ucReferenceSpec();
        return spec == null ? null : asStr(spec.get("root"));
    }

    /** One block of `doctor`'s configuration, or null. */
    static Map<String, Object> doctorSpec(String key) {
        try {
            return asMap(get(asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE)))), "doctor", key));
        } catch (Exception e) { return null; }
    }

    /**
     * `orphanUseCaseRefs`' twin, for the backlog's own identifier.
     *
     * <p>A `BL-NN` is what an impact row cites when the use case that satisfies a precondition
     * has not been designed yet. `BACKLOG.md` reserves no `UC` number on purpose — so that a row
     * dropped or merged leaves no hole in the `UC` sequence — which left the `Satisfied by` gate
     * unsatisfiable whenever the satisfier was in the backlog: two reports of one run wrote
     * `UC-004`, a number that existed nowhere, because the sentence needed a name
     * (lessons-learned-014 § 7).
     *
     * <p>The citation has to keep resolving after that case IS designed, since the spec carrying
     * it is immutable once approved. That is why `BACKLOG.md` keeps a `## Retired` table and this
     * check accepts a hit anywhere in the file: `resolves_in` is the whole file, both tables.
     * Reported, never blocking — a citation nobody can follow is a documentation defect, and
     * `doctor` is where a person is already reading.
     */
    static List<String> unknownBacklogRefs() {
        List<String> found = new ArrayList<>();
        Map<String, Object> spec = doctorSpec("bl_references");
        if (spec == null) return found;
        String pat = asStr(spec.get("pattern"));
        String root = asStr(spec.get("root"));
        if (pat == null || pat.isEmpty()) return found;
        if (root != null && !root.isEmpty() && !Files.isDirectory(ROOT.resolve(root))) return found;
        String backlog = orEmpty(readOrNull(ROOT.resolve(orEmpty(asStr(spec.get("resolves_in"))))));
        List<String> exts = asStrList(spec.get("extensions"));
        List<String> skip = asStrList(spec.get("exempt_paths"));

        List<String> files;
        try {
            Proc p = run("git", "ls-files");
            if (p.exit() != 0) return found;
            files = p.out();
        } catch (Exception e) { return found; }

        Pattern re = Pattern.compile(pat);
        for (String f : files) {
            if (!exts.isEmpty() && exts.stream().noneMatch(f::endsWith)) continue;
            if (skip.stream().anyMatch(f::startsWith)) continue;
            if (f.equals(asStr(spec.get("resolves_in")))) continue;   // the file defines them
            String body = readOrNull(ROOT.resolve(f));
            if (body == null) continue;
            Set<String> seen = new LinkedHashSet<>();
            Matcher m = re.matcher(body);
            while (m.find()) {
                String id = m.group(m.groupCount() >= 1 ? 1 : 0);
                if (!seen.add(id)) continue;
                if (!backlog.contains(id)) {
                    found.add(f + " cites " + id + ", which is in neither table of BACKLOG.md");
                }
            }
        }
        return found;
    }

    static List<String> orphanUseCaseRefs() {
        List<String> found = new ArrayList<>();
        Map<String, Object> spec = ucReferenceSpec();
        if (spec == null) return found;
        String pat = asStr(spec.get("pattern"));
        if (pat == null || pat.isEmpty()) return found;
        // No use-case directory, nothing a citation can be stale against. That is this
        // meta-repository: the check belongs to the generated project.
        String root = asStr(spec.get("root"));
        if (root != null && !root.isEmpty() && !Files.isDirectory(ROOT.resolve(root))) return found;
        List<String> exts = asStrList(spec.get("extensions"));
        List<String> skip = asStrList(spec.get("exempt_paths"));

        List<String> files;
        try {
            Proc p = run("git", "ls-files");
            if (p.exit() != 0) return found;
            files = p.out();
        } catch (Exception e) { return found; }

        Pattern re = Pattern.compile(pat);
        for (String f : files) {
            if (!exts.isEmpty() && exts.stream().noneMatch(f::endsWith)) continue;
            if (skip.stream().anyMatch(f::startsWith)) continue;
            String body = readOrNull(ROOT.resolve(f));
            if (body == null) continue;
            Set<String> seen = new LinkedHashSet<>();
            Matcher m = re.matcher(body);
            while (m.find()) {
                String folder = m.group(m.groupCount() >= 1 ? 1 : 0);
                if (!seen.add(folder)) continue;
                if (!Files.isDirectory(ROOT.resolve(folder))) {
                    found.add(f + " cites " + folder + ", which does not exist");
                }
            }
        }
        return found;
    }

    /**
     * The `Skill shadowing` line: every folder of the personal skills directory whose name is
     * also a skill of this project. Claude Code resolves one name as enterprise over personal
     * over project, so such a folder replaces the project's skill for every /command and every
     * automatic invocation, with no warning. Why a `doctor` line and not a block: the personal
     * directory is outside the project, nothing in the project can refuse it, and a person
     * reads this report before a run (decision matrix § 2.2 — no guarantee reaches it). The
     * closest rejected form, `settings.blocked_plugins`, covers plugins only: a plugin skill is
     * namespaced and cannot shadow. Runner state, never in `doctor.gate.labels`; ⚪ when the
     * directory does not exist. Reads `doctor.skill_shadowing`.
     * Design: `.claude/decisions/0138-block-colliding-plugins-and-report-shadowed-skills.md`.
     */
    static void doctorShadowedSkills() {
        Map<String, Object> spec = doctorSpec("skill_shadowing");
        if (spec == null) return;
        String env = asStr(spec.get("config_dir_env"));
        String base = env == null ? null : System.getenv(env);
        if (base == null || base.isBlank()) base = asStr(spec.get("config_dir_default"));
        String sub = asStr(spec.get("skills_subdir"));
        if (base == null || sub == null) return;
        if (base.equals("~") || base.startsWith("~/")) {
            base = System.getProperty("user.home") + base.substring(1);
        }
        Path personal = Paths.get(base).resolve(sub);
        Path project = ROOT.resolve(".claude/skills");
        if (!Files.isDirectory(personal)) {
            err("  Skill shadowing ... ⚪ no " + personal + " — nothing can shadow (optional)");
            return;
        }
        List<String> hit = new ArrayList<>();
        try {
            if (Files.isDirectory(project)
                    && !personal.toRealPath().equals(project.toRealPath())) {
                try (Stream<Path> s = Files.list(personal)) {
                    s.filter(d -> Files.isRegularFile(d.resolve("SKILL.md")))
                            .map(d -> d.getFileName().toString())
                            .filter(n -> Files.isRegularFile(project.resolve(n).resolve("SKILL.md")))
                            .sorted()
                            .forEach(hit::add);
                }
            }
        } catch (IOException ignored) { }
        report("Skill shadowing", hit.isEmpty(),
                "no personal skill replaces one of .claude/skills",
                hit.size() + " personal skill(s) replace this project's: " + String.join(", ", hit)
                        + " — rename or remove them in " + personal);
    }

    /**
     * The `Boundary violations` line: {@link #forbiddenImports} over every source file of the
     * project — what git tracks or would track (`ls-files --cached --others
     * --exclude-standard`, so build output stays out through `.gitignore`), or a walk of the
     * tree minus `walk_exclude_dirs` where there is no git. No compilation. Gated: the
     * generated project's `boundaries` job runs `doctor gate`, so a forbidden import fails CI
     * whoever wrote it — Claude Code's edit-time `check` covers only its own edits, and the
     * ArchUnit test exists only once `test-architect` installed it. Invoked by `doctor`, not by
     * a hook event. The closest rejected form, a `check --all` step in `ci.yml`, never reaches
     * a project generated before it: that file is not re-exported (0128). With no map it is
     * green with nothing to sweep, since `Boundaries` is already red for that one cause.
     * Reads `doctor.boundary_sweep` (invariant 10).
     * Design: `.claude/decisions/0140-boundary-sweep-in-doctor-gate.md`.
     */
    static void doctorBoundarySweep(Path map, long rules) {
        Map<String, Object> spec = doctorSpec("boundary_sweep");
        if (spec == null) return;
        if (rules == 0) {
            report("Boundary violations", true, "nothing to sweep — no boundary rule", "");
            return;
        }
        List<String> exts = asStrList(spec.get("extensions"));
        List<String> mapLines;
        try {
            mapLines = Files.readAllLines(map, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report("Boundary violations", false, "", map + " unreadable");
            return;
        }
        List<String> files = null;
        try {
            Proc p = run(gitCmd(), "ls-files", "--cached", "--others", "--exclude-standard");
            if (p.exit() == 0) files = p.out();
        } catch (Exception ignored) { }
        if (files == null) {
            Set<String> skip = new HashSet<>(asStrList(spec.get("walk_exclude_dirs")));
            try (Stream<Path> s = Files.walk(ROOT)) {
                files = s.filter(Files::isRegularFile)
                        .map(f -> ROOT.relativize(f).toString().replace('\\', '/'))
                        .filter(r -> Arrays.stream(r.split("/")).noneMatch(skip::contains))
                        .sorted().collect(Collectors.toList());
            } catch (IOException e) {
                files = List.of();
            }
        }
        int swept = 0;
        List<String> hits = new ArrayList<>();
        for (String f : files) {
            if (exts.stream().noneMatch(f::endsWith)) continue;
            String src = readOrNull(ROOT.resolve(f));
            if (src == null) continue;
            swept++;
            for (String v : forbiddenImports(f, src, mapLines)) hits.add(f + " → " + v);
        }
        long badFiles = hits.stream().map(h -> h.substring(0, h.indexOf(" → "))).distinct().count();
        report("Boundary violations", hits.isEmpty(),
                swept + " file(s) swept against " + rules + " rule(s)",
                badFiles + " file(s) depend on what their module forbids — move the code, not the rule"
                        + " (.claude/rules/architecture-ddd.md)");
        for (String h : hits.stream().limit(8).collect(Collectors.toList())) err("    " + h);
        if (hits.size() > 8) err("    … and " + (hits.size() - 8) + " more");
    }

    /**
     * The `Agent instructions` line: the files `project-bootstrap` step 6 writes so that an
     * agent other than Claude Code reads this project's context — the root pointers, and per
     * module of the active blueprint with forbidden imports an `AGENTS.md` listing every
     * prefix, next to a `CLAUDE.md` that imports it. That import is the one piece Claude Code
     * itself depends on: a module `AGENTS.md` alone is skipped while the root has a
     * `CLAUDE.md`, and nothing says so. Why a `doctor` line and not a gate: the build is what
     * refuses a forbidden import, and a stale instruction file is a documentation defect a
     * person fixes when reading this report — the closest rejected form, an entry in
     * `doctor.gate.labels`, would turn every project adopted without these files red. Silent
     * without a provenance stamp; reads `doctor.agent_instructions` (invariant 10).
     * Design: `.claude/decisions/0139-agents-md-root-pointer-module-owner.md`.
     */
    static void doctorAgentInstructions() {
        Map<String, Object> spec = doctorSpec("agent_instructions");
        if (spec == null) return;
        Map<String, Object> stamp = asMap(Json.parse(readOrNull(
                ROOT.resolve(orEmpty(asStr(spec.get("stamp_file")))))));
        String id = stamp == null ? null : asStr(stamp.get("blueprint"));
        if (id == null || id.isEmpty()) return;
        List<String> problems = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (String f : asStrList(spec.get("root_files"))) {
            if (Files.isRegularFile(ROOT.resolve(f))) checked.add(f);
            else problems.add(f + " missing");
        }
        String bpPath = orEmpty(asStr(spec.get("blueprint_dir"))) + "/"
                + orEmpty(asStr(spec.get("blueprint_file"))).replace("{id}", id);
        List<String> lines;
        try {
            lines = Files.readAllLines(ROOT.resolve(bpPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            lines = null;
            problems.add(bpPath + " unreadable — module files not checked");
        }
        String moduleFile = asStr(spec.get("module_file"));
        String importFile = asStr(spec.get("import_file"));
        String importLine = orEmpty(asStr(spec.get("import_line")));
        int modules = 0;
        if (lines != null && moduleFile != null && importFile != null) {
            Map<String, List<String>> byPath = yamlModuleForbidden(lines,
                    asStr(spec.get("modules_key")), asStr(spec.get("path_key")),
                    asStr(spec.get("forbidden_key")));
            for (Map.Entry<String, List<String>> e : byPath.entrySet()) {
                if (e.getValue().isEmpty() || !Files.isDirectory(ROOT.resolve(e.getKey()))) continue;
                modules++;
                String agents = e.getKey() + "/" + moduleFile;
                String body = readOrNull(ROOT.resolve(agents));
                if (body == null) {
                    problems.add(agents + " missing");
                } else {
                    checked.add(agents);
                    for (String prefix : e.getValue()) {
                        if (!body.contains(prefix)) problems.add(agents + " does not list " + prefix);
                    }
                }
                String imp = e.getKey() + "/" + importFile;
                String impBody = readOrNull(ROOT.resolve(imp));
                if (impBody == null || impBody.lines().map(String::strip).noneMatch(importLine::equals)) {
                    problems.add(imp + (impBody == null ? " missing" : " does not hold " + importLine)
                            + " — Claude Code skips " + agents + " without it");
                }
            }
        }
        String pat = asStr(spec.get("cited_path_pattern"));
        if (pat != null && !pat.isEmpty()) {
            Pattern re = Pattern.compile(pat);
            for (String f : checked) {
                Matcher m = re.matcher(orEmpty(readOrNull(ROOT.resolve(f))));
                Set<String> seen = new LinkedHashSet<>();
                while (m.find()) {
                    String cited = m.group(m.groupCount() >= 1 ? 1 : 0);
                    if (seen.add(cited) && !Files.exists(ROOT.resolve(cited))) {
                        problems.add(f + " cites " + cited + ", which does not exist");
                    }
                }
            }
        }
        if (problems.isEmpty()) {
            err("  Agent instructions . ✅ " + checked.size() + " file(s) · " + modules
                    + " module(s) match blueprint " + id);
            return;
        }
        err("  Agent instructions . ⚠️  " + problems.size()
                + " problem(s) — agents other than Claude Code read these files"
                + " (migration `agents-md` in .claude/schemas/extensions.json writes them)");
        for (String p : problems.stream().limit(8).collect(Collectors.toList())) err("    " + p);
        if (problems.size() > 8) err("    … and " + (problems.size() - 8) + " more");
    }

    /**
     * The `Virtual threads` line: a project whose build targets JDK 21 or later sets every
     * property of `doctor.virtual_threads.properties` in its main configuration file, and no
     * configuration file sets one to anything else (`.claude/rules/virtual-threads.md`). The
     * target is the build file's, never the JVM running this: a 17 build on a 21 machine is a
     * 17 project. Why a `doctor` line and not a gate: the norm was set in advance, with no
     * failure observed, and a profile may turn it off with a stated reason — the closest
     * rejected form, an entry in `doctor.gate.labels`, would fail a project's CI over one
     * configuration line. Silent with no build file (this meta-repository), no version found,
     * or a target below `min_feature`; reads `doctor.virtual_threads` (invariant 10).
     * Design: `.claude/decisions/0155-virtual-threads-on-jdk-21.md`.
     */
    /**
     * The Spring Boot line (`major.minor`) a project's build declares, or null when nothing in
     * it says so. `root` is the project, never this repository: `export` asks about its
     * destination, `doctor` about the project it runs in.
     *
     * <p>Why this is a mode helper and not a hook: the refusal has to run before `export` writes
     * a byte, and `doctor` has to report the same answer afterwards — one reader, two callers.
     * Invoked by `export` (exit 2 below the floor) and by `doctorPlatform` (reported, never
     * gated). The closest rejected form is a `permissions` rule: it cannot read a build file.
     */
    static String bootLine(Path root, Map<String, Object> spec) {
        List<Pattern> patterns = new ArrayList<>();
        for (String p : asStrList(spec.get("line_patterns"))) patterns.add(Pattern.compile(p));
        for (String f : asStrList(spec.get("build_files"))) {
            String content = readOrNull(root.resolve(f));
            if (content == null) continue;
            for (Pattern p : patterns) {
                Matcher m = p.matcher(content);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    /** True when `line` and `floor`, both `major.minor`, order `line` first. */
    static boolean lineBelow(String line, String floor) {
        String[] a = line.split("\\."), b = floor.split("\\.");
        int major = Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0]));
        if (major != 0) return major < 0;
        return Integer.parseInt(a[1]) < Integer.parseInt(b[1]);
    }

    /** `export` stops here when the destination's Spring Boot line is below the floor. */
    static void exportPlatformGate(Path destRoot) {
        Map<String, Object> spec = doctorSpec("platform");
        if (spec == null) return;
        String floor = asStr(spec.get("min_line"));
        String line = bootLine(destRoot, spec);
        if (floor == null || line == null || !lineBelow(line, floor)) return;
        err("❌ " + destRoot + " builds on Spring Boot " + line + " — this .claude/ is written for "
                + floor + " and later.");
        err("   Its norms, exemplars and architecture-test allowlist use APIs and packages that do");
        err("   not exist on " + line + ", so what they generate would not build or would misbehave there.");
        err("   Nothing was written. Migrate the project to Spring Boot " + floor + " or later first,");
        err("   then run this again. The `platform-upgrade` skill does that migration; it is delivered");
        err("   with `arch-adopt`. The floor is `doctor.platform.min_line` in " + SCHEMA_FILE + ".");
        System.exit(2);
    }

    static void upgradeRecipeUsage() {
        err("usage: java -jar .claude/hooks/ArchHook.jar upgrade-recipe <project> --pass bump"
                + " --target <x.y.z> --out <file>");
        err("       java -jar .claude/hooks/ArchHook.jar upgrade-recipe <project> --pass bump"
                + " --target <x.y.z> --initializr <file> --out <file>");
        err("       java -jar .claude/hooks/ArchHook.jar upgrade-recipe <project> --pass properties"
                + " --classpath <file> --out <file>");
        err("  <project>     the project's root; its build says which Spring Boot line it is on");
        err("  --pass bump        the version bump, the coordinate renames and the type renames of");
        err("                     `doctor.platform.upgrade`, run on the classpath the project still has;");
        err("                     also the starters a used type now needs and the version a renamed or added");
        err("                     coordinate must not carry");
        err("  --initializr  the Initializr's `dependencies` metadata for --target, already downloaded: with it,");
        err("                the bump also moves each BOM and each versioned dependency it lists");
        err("  --pass properties  the property renames the jars on --classpath declare as errors,");
        err("                     run after the bump, once the new jars resolve");
        err("  --target      the Spring Boot version to move to, as the Initializr serves it");
        err("  --classpath   a file listing the resolved jars, one per line or joined by the OS path separator");
        err("  --out         the recipe file to write");
        err("  The data is read from the source this jar belongs to (CLAUDE_PROJECT_DIR, as `export` does),");
        err("  never from <project>, which has no .claude/ of this shape yet.");
    }

    /**
     * {@code upgrade-recipe} — writes the OpenRewrite recipe that takes a project below
     * {@code doctor.platform.min_line} up to it. {@code --pass bump} emits the version bump, then a
     * coordinate rename per {@code renames.dependencies} entry and a type rename per
     * {@code renames.types} entry; {@code --pass properties} reads
     * {@code META-INF/spring-configuration-metadata.json} out of every jar on {@code --classpath}
     * and emits a property rename for each entry the jar itself declares as an error with a
     * replacement. Which recipe each entry becomes, and with which options, is the {@code recipes}
     * block of the same data, so the engine is swapped by editing it. Exit 2, nothing written, on a
     * target that is malformed or below the floor, a project already at the floor, an entry the
     * data does not spell as asked, or an option placeholder left unresolved; exit 0 and nothing
     * written when the jars declare no rename.
     *
     * <p>The bump also adds, with no version, the starter a type the sources use now needs
     * ({@code requires_starter}); removes the version a renamed or added coordinate carried; and, given
     * {@code --initializr}, moves each BOM and each versioned dependency to the version the Initializr
     * lists for the target. Design: .claude/decisions/0176-platform-upgrade-baseline-context-and-runtime-starters.md
     *
     * <p>Why a mode: the first design left the rename list to the model, which cannot be held by a
     * test and would have been a second owner of what {@code dependency-catalog.md} already lists
     * (invariant 2); the second would have copied a recipe module under a license that restricts
     * derived products. Invoked by the {@code platform-upgrade} skill, by hand, never by a hook. The
     * closest rejected form is a template recipe in the skill, which cannot read a jar's metadata.
     *
     * <p>Design: .claude/decisions/0175-platform-upgrade-below-the-floor.md
     */
    static void upgradeRecipe(String[] args) throws Exception {
        String project = null, pass = null, target = null, classpath = null, out = null, initializr = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--pass") && i + 1 < args.length) pass = args[++i];
            else if (a.equals("--initializr") && i + 1 < args.length) initializr = args[++i];
            else if (a.equals("--target") && i + 1 < args.length) target = args[++i];
            else if (a.equals("--classpath") && i + 1 < args.length) classpath = args[++i];
            else if (a.equals("--out") && i + 1 < args.length) out = args[++i];
            else if (a.startsWith("--")) { err("❌ Unknown option: " + a); upgradeRecipeUsage(); System.exit(2); }
            else if (project == null) project = a;
            else { err("❌ Only one project is accepted, got also: " + a); upgradeRecipeUsage(); System.exit(2); }
        }
        boolean bump = "bump".equals(pass), props = "properties".equals(pass);
        if (project == null || out == null || !(bump || props) || (bump && target == null)
                || (props && classpath == null)) {
            upgradeRecipeUsage();
            System.exit(2);
        }
        Map<String, Object> spec = doctorSpec("platform");
        Map<String, Object> up = spec == null ? null : asMap(spec.get("upgrade"));
        Map<String, Object> recipes = up == null ? null : asMap(up.get("recipes"));
        if (recipes == null) {
            err("❌ No `doctor.platform.upgrade.recipes` block in " + SCHEMA_FILE
                    + " — this mode reads what it emits from it.");
            System.exit(2);
        }
        Path outFile = Paths.get(out).toAbsolutePath().normalize();
        StringBuilder yaml = new StringBuilder();
        int count;

        if (bump) {
            String floor = asStr(spec.get("min_line"));
            String pattern = asStr(up.get("target_pattern"));
            if (pattern == null || !target.matches(pattern)) {
                err("❌ --target is a Spring Boot version such as `4.1.1`, matching `" + pattern
                        + "` (`doctor.platform.upgrade.target_pattern`), got: " + target);
                err("   Resolve it from the Initializr's metadata, never from memory (invariant 8).");
                System.exit(2);
            }
            String targetLine = target.substring(0, target.indexOf('.', target.indexOf('.') + 1));
            if (floor != null && lineBelow(targetLine, floor)) {
                err("❌ --target " + target + " is below the floor " + floor + " of `doctor.platform.min_line`."
                        + " Nothing was written.");
                System.exit(2);
            }
            String line = bootLine(Paths.get(project).toAbsolutePath().normalize(), spec);
            if (line != null && floor != null && !lineBelow(line, floor)) {
                err("❌ " + project + " is already on Spring Boot " + line + ", at or above the floor " + floor
                        + ". Nothing was written; there is nothing to upgrade.");
                System.exit(2);
            }
            List<String> list = new ArrayList<>();
            Map<String, String> fixed = Map.of("target", target);
            for (Object e : asList(recipes.get("bump"))) list.add(recipeEntry(e, fixed));
            // Order matters. `FindAndReplace` reads a build file as text, and a file it changes stops being
            // a Maven or Gradle model for every recipe after it: the unpin steps run last, and the BOMs
            // move before any step that edits the file.
            if (initializr == null) {
                err("ℹ️  upgrade-recipe: no --initializr — the BOMs and versioned dependencies the Initializr lists"
                        + " are not moved.");
            } else {
                for (var c : initializrCoordinates(Paths.get(initializr).toAbsolutePath().normalize(), target).entrySet()) {
                    String[] ga = coordinate(c.getKey());
                    Map<String, String> ph = Map.of("group", ga[0], "artifact", ga[1], "version", c.getValue());
                    requireTemplates(recipes, "bom", "--initializr");
                    for (Object t : asList(recipes.get("bom"))) list.add(recipeEntry(t, ph));
                }
            }
            Map<String, Object> renames = asMap(up.get("renames"));
            Set<String> unpinned = new LinkedHashSet<>();
            for (Object d : asList(renames == null ? null : renames.get("dependencies"))) {
                String[] from = coordinate(asStr(get(d, "from"))), to = coordinate(asStr(get(d, "to")));
                Map<String, String> ph = Map.of("from_group", from[0], "from_artifact", from[1],
                        "to_group", to[0], "to_artifact", to[1]);
                for (Object t : asList(recipes.get("dependency"))) list.add(recipeEntry(t, ph));
                unpinned.add(to[0] + ":" + to[1]);
            }
            Map<String, Object> starters = asMap(up.get("requires_starter"));
            if (starters != null) {
                for (var r : new TreeMap<>(starters).entrySet()) {
                    if (r.getKey().startsWith("$")) continue;
                    String[] ga = coordinate(asStr(r.getValue()));
                    Map<String, String> ph = Map.of("group", ga[0], "artifact", ga[1], "type", r.getKey());
                    requireTemplates(recipes, "add", "`requires_starter`");
                    for (Object t : asList(recipes.get("add"))) list.add(recipeEntry(t, ph));
                    unpinned.add(ga[0] + ":" + ga[1]);
                }
            }
            for (Object d : asList(renames == null ? null : renames.get("types"))) {
                Map<String, String> ph = Map.of("from", orEmpty(asStr(get(d, "from"))),
                        "to", orEmpty(asStr(get(d, "to"))));
                for (Object t : asList(recipes.get("type"))) list.add(recipeEntry(t, ph));
            }
            // A renamed or added coordinate must not keep the version the old one carried: the Spring
            // Boot BOM manages every one of them, and a pin left behind overrides it. Last, see above.
            for (String c : unpinned) {
                String[] ga = coordinate(c);
                Map<String, String> ph = Map.of("group_regex", ga[0].replace(".", "\\."),
                        "artifact_regex", ga[1].replace(".", "\\."));
                requireTemplates(recipes, "unpin", "`renames.dependencies` and `requires_starter`");
                for (Object t : asList(recipes.get("unpin"))) list.add(recipeEntry(t, ph));
            }
            yaml.append(recipeYaml(asStr(recipes.get("bump_name")), "Spring Boot " + target + " — version, coordinates, types", list));
            count = list.size();
        } else {
            String entry = asStr(up.get("metadata_entry"));
            String level = asStr(get(up, "metadata_deprecation", "level"));
            if (entry == null || level == null) {
                err("❌ `doctor.platform.upgrade` has no `metadata_entry` or `metadata_deprecation.level`.");
                System.exit(2);
            }
            Path cp = Paths.get(classpath).toAbsolutePath().normalize();
            String raw = readOrNull(cp);
            if (raw == null) { err("❌ --classpath " + cp + " is not a readable file."); System.exit(2); }
            Map<String, String> renamed = new TreeMap<>();
            int jars = 0;
            for (String piece : raw.split("[\\r\\n" + Pattern.quote(File.pathSeparator) + "]+")) {
                Path jar = Paths.get(piece.trim());
                if (piece.isBlank() || !piece.trim().endsWith(".jar") || !Files.isRegularFile(jar)) continue;
                jars++;
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    var e = zip.getEntry(entry);
                    if (e == null) continue;
                    Map<String, Object> meta;
                    try (InputStream in = zip.getInputStream(e)) {
                        meta = asMap(Json.parse(readAll(in)));
                    }
                    for (Object p : asList(meta == null ? null : meta.get("properties"))) {
                        String key = asStr(get(p, "name"));
                        String replacement = asStr(get(p, "deprecation", "replacement"));
                        if (key == null || replacement == null || replacement.isBlank() || key.equals(replacement)
                                || !level.equals(asStr(get(p, "deprecation", "level")))) continue;
                        renamed.putIfAbsent(key, replacement);
                    }
                }
            }
            if (renamed.isEmpty()) {
                System.out.println("ℹ️  upgrade-recipe: " + jars + " jar(s) on the classpath declare no property"
                        + " rename — nothing written.");
                return;
            }
            List<String> list = new ArrayList<>();
            for (var r : renamed.entrySet()) {
                Map<String, String> ph = Map.of("key", r.getKey(), "replacement", r.getValue());
                for (Object t : asList(recipes.get("property"))) list.add(recipeEntry(t, ph));
            }
            yaml.append(recipeYaml(asStr(recipes.get("properties_name")), "Spring Boot property renames", list));
            count = renamed.size();
        }
        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, yaml.toString(), StandardCharsets.UTF_8);
        System.out.println("✅ upgrade-recipe: " + pass + " — " + count + (bump ? " recipe step(s)" : " property rename(s)")
                + " written to " + outFile);
    }

    /** `group:artifact` as two parts; exit 2 naming the entry when it is anything else. */
    private static String[] coordinate(String gav) {
        String[] p = gav == null ? new String[0] : gav.split(":", -1);
        if (p.length != 2 || p[0].isBlank() || p[1].isBlank()) {
            err("❌ `doctor.platform.upgrade.renames.dependencies` entries are `group:artifact`, got: " + gav);
            System.exit(2);
        }
        return p;
    }

    /** Exit 2 naming the block when the data has no template for something the mode has to emit. */
    private static void requireTemplates(Map<String, Object> recipes, String key, String why) {
        if (asList(recipes.get(key)).isEmpty()) {
            err("❌ `doctor.platform.upgrade.recipes." + key + "` is empty, and " + why + " needs it. Nothing was written.");
            System.exit(2);
        }
    }

    /**
     * The BOMs and the dependencies that carry their own version in the Initializr's metadata, as
     * {@code group:artifact} → version, sorted so the same input writes the same recipe. The file
     * must be for {@code target}: the Initializr serves one Boot line per answer, and a BOM from
     * another one is a version nobody tested against it.
     */
    private static Map<String, String> initializrCoordinates(Path file, String target) {
        String raw = readOrNull(file);
        Map<String, Object> meta = raw == null ? null : asMap(Json.parse(raw));
        if (meta == null) {
            err("❌ --initializr " + file + " is not a readable JSON file. Nothing was written.");
            System.exit(2);
        }
        if (!target.equals(asStr(meta.get("bootVersion")))) {
            err("❌ --initializr " + file + " is for Spring Boot " + asStr(meta.get("bootVersion"))
                    + ", not --target " + target + ". Download it again from `engine.initializr_dependencies`.");
            System.exit(2);
        }
        Map<String, String> found = new TreeMap<>();
        Map<String, Object> boms = asMap(meta.get("boms")), deps = asMap(meta.get("dependencies"));
        List<Object> entries = new ArrayList<>(boms == null ? List.of() : boms.values());
        entries.addAll(deps == null ? List.of() : deps.values());
        for (Object e : entries) {
            String group = asStr(get(e, "groupId")), artifact = asStr(get(e, "artifactId")), version = asStr(get(e, "version"));
            if (group != null && artifact != null && version != null) found.putIfAbsent(group + ":" + artifact, version);
        }
        return found;
    }

    /** One recipe of a `recipes` template: its type, then its options with the placeholders resolved. */
    private static String recipeEntry(Object template, Map<String, String> placeholders) {
        String type = asStr(get(template, "type"));
        Map<String, Object> options = asMap(get(template, "options"));
        if (type == null) { err("❌ A `recipes` entry of `doctor.platform.upgrade` has no `type`."); System.exit(2); }
        StringBuilder sb = new StringBuilder("  - " + type + ":\n");
        if (options != null) {
            for (var o : options.entrySet()) {
                String v = asStr(o.getValue());
                if (v == null) { err("❌ Option `" + o.getKey() + "` of " + type + " is not a string."); System.exit(2); }
                for (var p : placeholders.entrySet()) v = v.replace("${" + p.getKey() + "}", p.getValue());
                if (v.contains("${")) {
                    err("❌ Option `" + o.getKey() + "` of " + type + " keeps a placeholder with no value: " + v);
                    System.exit(2);
                }
                sb.append("      ").append(o.getKey()).append(": ").append(yamlQuote(v)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String yamlQuote(String v) {
        return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String recipeYaml(String name, String display, List<String> entries) {
        if (name == null) { err("❌ `doctor.platform.upgrade.recipes` names no recipe for this pass."); System.exit(2); }
        StringBuilder sb = new StringBuilder("---\ntype: specs.openrewrite.org/v1beta/recipe\nname: " + name
                + "\ndisplayName: " + yamlQuote(display)
                + "\ndescription: \"Written by ArchHook upgrade-recipe from doctor.platform.upgrade; do not edit.\""
                + "\nrecipeList:\n");
        for (String e : entries) sb.append(e);
        return sb.toString();
    }

    static void testDiffUsage() {
        err("usage: java -jar .claude/hooks/ArchHook.jar test-diff snapshot <project> --out <file>");
        err("       java -jar .claude/hooks/ArchHook.jar test-diff compare <project> --baseline <file>");
        err("  snapshot  records every test case in the JUnit XML reports under <project> and whether it passed");
        err("  compare   reads the reports again and lists what changed against --baseline; exit 2 when a test");
        err("            that passed is now failing or gone, or one the baseline never saw is failing");
        err("  The report globs are `doctor.platform.upgrade.test_reports`, read from the source this jar belongs to.");
        err("  Run the build with `clean` before each, or a report left by an earlier run reads as this one's.");
    }

    /**
     * {@code test-diff} — {@code snapshot} writes the outcome of every test case found in the JUnit XML
     * reports of a build ({@code doctor.platform.upgrade.test_reports}); {@code compare} reads the reports
     * again and sorts each case against the snapshot: <em>regression</em> (passed, now failing),
     * <em>missing</em> (passed, now absent — a deleted test is never a fix), <em>new failure</em> (absent
     * from the baseline, failing now), <em>pre-existing</em> (failing in both), <em>fixed</em>. Exit 2 on
     * a regression, a missing test or a new failure, and on a run with no report at all — a build that
     * stopped before the tests ran must not read as green. Every error exits 2 too: the top-level catch
     * of {@code main} exits 0, which here would be a pass.
     *
     * <p>Why a mode: "red" after an upgrade carries no information without the run before it, and the
     * comparison is over hundreds of cases, which a model reads wrong and a test can hold. Invoked by the
     * {@code platform-upgrade} skill (steps 4b and 9), by hand, never by a hook. The closest rejected form
     * is the model comparing report directories: the same input would give a different answer each time.
     *
     * <p>Design: .claude/decisions/0176-platform-upgrade-baseline-context-and-runtime-starters.md
     */
    static void testDiff(String[] args) {
        try {
            String verb = args.length > 1 ? args[1] : null, project = null, out = null, baseline = null;
            for (int i = 2; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--out") && i + 1 < args.length) out = args[++i];
                else if (a.equals("--baseline") && i + 1 < args.length) baseline = args[++i];
                else if (a.startsWith("--")) { err("❌ Unknown option: " + a); testDiffUsage(); System.exit(2); }
                else if (project == null) project = a;
                else { err("❌ Only one project is accepted, got also: " + a); testDiffUsage(); System.exit(2); }
            }
            boolean snapshot = "snapshot".equals(verb), compare = "compare".equals(verb);
            if (project == null || !(snapshot || compare) || (snapshot && out == null) || (compare && baseline == null)) {
                testDiffUsage();
                System.exit(2);
            }
            Map<String, Object> spec = doctorSpec("platform");
            List<String> globs = asStrList(get(spec, "upgrade", "test_reports"));
            if (globs.isEmpty()) {
                err("❌ No `doctor.platform.upgrade.test_reports` in " + SCHEMA_FILE + " — this mode reads where the reports are from it.");
                System.exit(2);
            }
            Path root = Paths.get(project).toAbsolutePath().normalize();
            Map<String, String> now = testReports(root, globs);
            if (now.isEmpty()) {
                err("❌ test-diff: no JUnit XML report under " + root + " (" + String.join(", ", globs) + ").");
                err("   The build stopped before it ran the tests, or it ran them somewhere these globs do not look.");
                System.exit(2);
            }
            if (snapshot) {
                Path file = Paths.get(out).toAbsolutePath().normalize();
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                StringBuilder sb = new StringBuilder("{\n  \"tests\": {");
                String sep = "\n";
                for (var t : now.entrySet()) {
                    sb.append(sep).append("    \"").append(jsonEscape(t.getKey())).append("\": \"").append(t.getValue()).append('"');
                    sep = ",\n";
                }
                Files.writeString(file, sb.append("\n  }\n}\n").toString(), StandardCharsets.UTF_8);
                System.out.println("✅ test-diff: " + now.size() + " test case(s) recorded in " + file + " — "
                        + countStatus(now, "fail") + " failing, " + countStatus(now, "skip") + " skipped.");
                return;
            }
            String raw = readOrNull(Paths.get(baseline).toAbsolutePath().normalize());
            Map<String, Object> was = raw == null ? null : asMap(get(Json.parse(raw), "tests"));
            if (was == null) {
                err("❌ --baseline " + baseline + " is not a snapshot `test-diff snapshot` wrote. Nothing was compared.");
                System.exit(2);
            }
            List<String> regressed = new ArrayList<>(), missing = new ArrayList<>(), fresh = new ArrayList<>(),
                    kept = new ArrayList<>(), fixed = new ArrayList<>();
            for (var t : new TreeMap<>(was).entrySet()) {
                String before1 = asStr(t.getValue()), after = now.get(t.getKey());
                if ("pass".equals(before1) && after == null) missing.add(t.getKey());
                else if ("pass".equals(before1) && "fail".equals(after)) regressed.add(t.getKey());
                else if ("fail".equals(before1) && "fail".equals(after)) kept.add(t.getKey());
                else if ("fail".equals(before1) && "pass".equals(after)) fixed.add(t.getKey());
            }
            for (var t : now.entrySet()) if ("fail".equals(t.getValue()) && !was.containsKey(t.getKey())) fresh.add(t.getKey());
            boolean red = !(regressed.isEmpty() && missing.isEmpty() && fresh.isEmpty());
            System.out.println((red ? "❌" : "✅") + " test-diff: " + now.size() + " test case(s) now, " + was.size() + " in the baseline — "
                    + regressed.size() + " regression(s), " + missing.size() + " missing, " + fresh.size() + " new failure(s), "
                    + kept.size() + " failing before and still, " + fixed.size() + " fixed.");
            testDiffList("regression (passed before, failing now)", regressed);
            testDiffList("missing (passed before, no report now)", missing);
            testDiffList("new failure (not in the baseline)", fresh);
            testDiffList("pre-existing failure (failing before the upgrade)", kept);
            testDiffList("fixed", fixed);
            if (red) System.exit(2);
        } catch (Exception e) {
            err("❌ test-diff failed: " + e);
            System.exit(2);
        }
    }

    private static void testDiffList(String title, List<String> names) {
        if (names.isEmpty()) return;
        System.out.println("  " + title + ":");
        for (int i = 0; i < names.size() && i < 25; i++) System.out.println("    - " + names.get(i));
        if (names.size() > 25) System.out.println("    … and " + (names.size() - 25) + " more");
    }

    private static long countStatus(Map<String, String> tests, String status) {
        return tests.values().stream().filter(status::equals).count();
    }

    /** {@code class#name} → {@code pass | fail | skip} for every test case of every report the globs match. */
    private static Map<String, String> testReports(Path root, List<String> globs) throws Exception {
        List<Pattern> patterns = new ArrayList<>();
        for (String g : globs) patterns.add(Pattern.compile(globRegex(g)));
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                String n = d.getFileName() == null ? "" : d.getFileName().toString();
                return n.equals(".git") || n.equals("node_modules") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                for (Pattern p : patterns) if (p.matcher(rel).matches()) { files.add(f); break; }
                return FileVisitResult.CONTINUE;
            }
        });
        Map<String, String> tests = new TreeMap<>();
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (Path f : files) {
            var doc = factory.newDocumentBuilder().parse(f.toFile());
            var cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                var c = (org.w3c.dom.Element) cases.item(i);
                String status = "pass";
                for (var n = c.getFirstChild(); n != null; n = n.getNextSibling()) {
                    String tag = n.getNodeName();
                    if (tag.equals("failure") || tag.equals("error")) { status = "fail"; break; }
                    if (tag.equals("skipped")) status = "skip";
                }
                String key = c.getAttribute("classname") + "#" + c.getAttribute("name");
                String old = tests.get(key);
                // The same name twice: the worse outcome stands.
                if (old == null || "fail".equals(status) || ("skip".equals(status) && "pass".equals(old))) tests.put(key, status);
            }
        }
        return tests;
    }

    /** A glob with {@code **}, {@code *} and {@code ?} as a regex over a {@code /}-separated relative path; {@code **}/ may match nothing. */
    private static String globRegex(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') { re.append("(?:.*/)?"); i += 2; }
                else { re.append(".*"); i++; }
            } else if (c == '*') re.append("[^/]*");
            else if (c == '?') re.append("[^/]");
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        return re.toString();
    }

    /** The `Platform` line: the Spring Boot line the build declares against the floor. Reported, never gated. */
    static void doctorPlatform() {
        Map<String, Object> spec = doctorSpec("platform");
        if (spec == null) return;
        String label = "  Platform .......... ";
        String floor = asStr(spec.get("min_line"));
        String line = bootLine(ROOT, spec);
        if (line == null) {
            err(label + "⚪ no Spring Boot line found in the build — nothing to check (optional)");
        } else if (floor != null && lineBelow(line, floor)) {
            err(label + "⚠️  Spring Boot " + line + " — this .claude/ is written for " + floor
                    + " and later; its norms and exemplars do not apply as written");
        } else {
            err(label + "✅ Spring Boot " + line);
        }
    }

    static void doctorVirtualThreads() {
        Map<String, Object> spec = doctorSpec("virtual_threads");
        if (spec == null) return;
        String label = "  Virtual threads ... ";
        String build = null;
        String content = null;
        for (String f : asStrList(spec.get("build_files"))) {
            content = readOrNull(ROOT.resolve(f));
            if (content != null) { build = f; break; }
        }
        if (build == null) {
            err(label + "⚪ no build file — nothing to check (optional)");
            return;
        }
        int target = -1;
        for (String p : asStrList(spec.get("version_patterns"))) {
            Matcher m = Pattern.compile(p).matcher(content);
            if (m.find()) { target = Integer.parseInt(m.group(1)); break; }
        }
        long min = num(spec.get("min_feature"));
        if (target < 0) {
            err(label + "⚪ no target JDK found in " + build + " — nothing to check");
            return;
        }
        if (target < min) {
            err(label + "⚪ " + build + " targets JDK " + target + " — virtual threads need " + min);
            return;
        }
        Map<String, Object> wanted = asMap(spec.get("properties"));
        if (wanted == null || wanted.isEmpty()) return;
        Pattern main = Pattern.compile(orEmpty(asStr(spec.get("main_file_pattern"))));
        List<String> globs = asStrList(spec.get("config_globs"));
        List<String> problems = new ArrayList<>();
        List<String> mains = new ArrayList<>();
        Map<String, Boolean> set = new LinkedHashMap<>();
        for (Path f : projectFiles(rel -> matchesAny(globs, rel))) {
            String rel = relative(f);
            Map<String, String> values = configScalars(rel, orEmpty(readOrNull(f)));
            boolean isMain = main.matcher(rel).find();
            if (isMain) mains.add(rel);
            for (Map.Entry<String, Object> w : wanted.entrySet()) {
                String v = values.get(w.getKey());
                String expected = asStr(w.getValue());
                if (v == null) continue;
                if (!v.equals(expected)) problems.add(rel + " sets " + w.getKey() + " to " + v);
                else if (isMain) set.put(w.getKey(), true);
            }
        }
        if (mains.isEmpty()) {
            problems.add("no main configuration file (application.yml, .yaml or .properties)");
        } else {
            for (String k : wanted.keySet()) {
                if (!set.containsKey(k)) {
                    problems.add(k + ": " + asStr(wanted.get(k)) + " missing from "
                            + String.join(", ", mains));
                }
            }
        }
        if (problems.isEmpty()) {
            err(label + "✅ JDK " + target + " · " + String.join(", ", wanted.keySet())
                    + " on in " + String.join(", ", mains));
            return;
        }
        err(label + "⚠️  JDK " + target + " — " + problems.size()
                + " problem(s), see .claude/rules/virtual-threads.md"
                + " (migration `virtual-threads` in .claude/schemas/extensions.json)");
        for (String p : problems.stream().limit(8).collect(Collectors.toList())) err("    " + p);
        if (problems.size() > 8) err("    … and " + (problems.size() - 8) + " more");
    }

    /**
     * Every scalar of an application configuration file by its dotted property: YAML through
     * {@link #yamlScalars}, `.properties` as `key=value` or `key: value` lines.
     */
    static Map<String, String> configScalars(String rel, String content) {
        if (!rel.endsWith(".properties")) return yamlScalars(content);
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : content.split("\r?\n")) {
            String l = raw.strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue;
            Matcher m = Pattern.compile("^([^=:\\s]+)\\s*[=:]\\s*(.*)$").matcher(l);
            if (m.find()) out.putIfAbsent(m.group(1), m.group(2).strip());
        }
        return out;
    }

    /**
     * Every item of a blueprint's top-level `modules` list, as its `path` and the prefixes of
     * its forbidden-imports list — block or inline form. Key names come from the caller,
     * which reads them from extensions.json; an item with no path is dropped.
     */
    static Map<String, List<String>> yamlModuleForbidden(List<String> lines, String modulesKey,
            String pathKey, String forbiddenKey) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (modulesKey == null || pathKey == null || forbiddenKey == null) return out;
        int i = indexOfTopKey(lines, modulesKey);
        if (i < 0) return out;
        Pattern kv = Pattern.compile("^([A-Za-z_][A-Za-z0-9_-]*):\\s*(.*)$");
        int itemIndent = -1;
        String path = null;
        List<String> forbidden = new ArrayList<>();
        boolean inForbidden = false;
        for (int j = i + 1; j <= lines.size(); j++) {
            String l = j < lines.size() ? lines.get(j) : "end:";
            if (l.isBlank() || l.trim().startsWith("#")) continue;
            int ind = indentOf(l);
            String t = l.trim();
            boolean itemStart = t.startsWith("- ") && (itemIndent < 0 || ind == itemIndent);
            if (ind == 0 || itemStart) {
                if (path != null) out.put(path, forbidden);
                path = null;
                forbidden = new ArrayList<>();
                inForbidden = false;
                if (ind == 0) break;
                itemIndent = ind;
                t = t.substring(2).trim();
            } else if (inForbidden && t.startsWith("- ")) {
                forbidden.add(unquote(t.substring(2)));
                continue;
            }
            Matcher m = kv.matcher(t);
            if (!m.matches()) continue;
            String value = m.group(2).trim();
            inForbidden = m.group(1).equals(forbiddenKey);
            if (m.group(1).equals(pathKey)) {
                path = unquote(value);
            } else if (inForbidden && value.startsWith("[")) {
                String inner = value.substring(1, Math.max(1, value.lastIndexOf(']')));
                for (String v : inner.split(",")) if (!v.isBlank()) forbidden.add(unquote(v));
                inForbidden = false;
            }
        }
        return out;
    }

    /**
     * The `Mods` line: how many mods sit under `mods.root`, whether `schema` passes them, and
     * whether the Claude Code on PATH is new enough to load them (`mods.min_version`). A CLI
     * below the floor fails nothing — a mod is a layer over the settings hooks, which keep
     * enforcing — but every mod here is then silently absent, which is what the line says.
     * Silent where `root` does not exist; never in `doctor.gate.labels`, since which CLI a
     * machine runs is machine state. Design: `.claude/decisions/0131-mods-in-architect-designer.md`.
     */
    static void doctorMods() {
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        Map<String, Object> cfg = sch == null ? null : asMap(sch.get("mods"));
        String root = cfg == null ? null : asStr(cfg.get("root"));
        if (root == null || !Files.isDirectory(ROOT.resolve(root))) return;
        long mods;
        try (Stream<Path> s = Files.list(ROOT.resolve(root))) {
            mods = s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith(".")).count();
        } catch (IOException e) {
            mods = 0;
        }
        List<String> errs = new ArrayList<>();
        checkMods(sch, errs);
        String min = orEmpty(asStr(cfg.get("min_version")));
        String version = null;
        try {
            Proc p = runTimed(15, WINDOWS ? "claude.cmd" : "claude", "--version");
            Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(String.join(" ", p.out()));
            if (p.exit() == 0 && m.find()) version = m.group(1);
        } catch (Exception ignored) { }
        String head = mods + " mod(s) in " + root;
        if (!errs.isEmpty()) {
            report("Mods", false, "", head + " — " + errs.size()
                    + " problem(s), run `java ArchHook.java schema`");
        } else if (version == null) {
            err("  Mods .............. ⚪ " + head + " — no `claude` on PATH, version unchecked");
        } else {
            report("Mods", compareVersions(version, min) >= 0,
                    head + " — Claude Code " + version + " loads them",
                    head + " — Claude Code " + version + " is below " + min
                            + ", so none loads; run `claude update`");
        }
    }

    /** Negative, zero or positive, comparing dotted numeric versions part by part. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? Integer.parseInt(x[i].replaceAll("\\D", "0")) : 0;
            int v = i < y.length ? Integer.parseInt(y[i].replaceAll("\\D", "0")) : 0;
            if (u != v) return Integer.compare(u, v);
        }
        return 0;
    }

    static void report(String label, boolean ok, String yes, String no) {
        if (!ok) REPORT_FAILED.add(label);
        String pad = "                  ".substring(Math.min(label.length(), 17));
        err("  " + label + " " + pad.replace(' ', '.') + " " + (ok ? "✅ " + yes : "❌ " + no));
    }

    // ── build ────────────────────────────────────────────────────────────────

    /** DOS epoch plus one month: the earliest timestamp every zip reader agrees on. */
    static final java.time.LocalDateTime JAR_EPOCH = java.time.LocalDateTime.of(1980, 2, 1, 0, 0);

    /**
     * Compiles this file into the jar every hook registration launches, byte for byte the
     * same on every machine running the pinned JDK; with {@code --verify}, refuses a
     * committed jar that is not exactly what this source compiles to.
     *
     * <p>Why a mode: a source launch recompiles 260 KB on every hook call (~3.3 s against
     * ~0.3 s for the jar, measured), and most calls do nothing useful — a turn paid ~14 s of
     * hooks at {@code Stop} alone. Invoked by a {@code PostToolUse} entry with
     * {@code if: Edit(.claude/hooks/ArchHook.java)} in this repository only, and by CI as
     * {@code build --verify}; rejected: a gitignored jar built on {@code SessionStart},
     * because a failed build leaves every {@code java -jar} at exit 1, which blocks nothing.
     *
     * <p>Design: .claude/decisions/0075-precompiled-hook-jar.md
     */
    static void build(String[] args) {
        boolean verify = args.length > 1 && "--verify".equals(args[1]);
        try {
            Map<String, Object> hb = hookBuild();
            if (hb == null) {
                err("❌ No `hook_build` block in " + SCHEMA_FILE + " — nothing says what to build.");
                System.exit(2);
            }
            int feature = (int) num(hb.get("javac_feature"));
            int running = Runtime.version().feature();
            if (running != feature) {
                err("❌ build is pinned to JDK " + feature + "; this runtime is JDK " + running
                        + " (" + System.getProperty("java.home") + ").");
                err("   Another javac emits different bytes for the same source, and");
                err("   `build --verify` in CI would reject the jar. Run it with a JDK " + feature + ":");
                err("   <jdk-" + feature + ">/bin/java " + asStr(hb.get("source")) + " build");
                System.exit(2);
            }
            Path jar = ROOT.resolve(asStr(hb.get("jar")));
            byte[] fresh = compileJar(hb);
            byte[] current = Files.isRegularFile(jar) ? Files.readAllBytes(jar) : null;
            if (verify) {
                if (Arrays.equals(fresh, current)) {
                    err("✅ " + relative(jar) + " is exactly what " + asStr(hb.get("source"))
                            + " compiles to under JDK " + feature + ".");
                    return;
                }
                String stale = hookJarProblem(hb);
                err("❌ " + relative(jar) + " is not what " + asStr(hb.get("source"))
                        + " compiles to under JDK " + feature + ".");
                err(stale != null
                        ? "   " + stale
                        : "   It records this source's hash over different classes: built by"
                          + " another javac, or edited by hand. Rebuild it and commit the jar.");
                System.exit(2);
            }
            if (Arrays.equals(fresh, current)) {
                err("✅ " + relative(jar) + " already up to date.");
                return;
            }
            replaceFile(jar, fresh);
            err("✅ " + relative(jar) + " rebuilt (" + fresh.length / 1024 + " KB, JDK " + feature
                    + "). Commit it with the source.");
        } catch (Exception e) {
            // Not the top-level catch: a build that fails quietly leaves the hooks on the old jar.
            err("❌ ArchHook build failed: " + e);
            System.exit(2);
        }
    }

    static Map<String, Object> hookBuild() {
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        return sch == null ? null : asMap(sch.get("hook_build"));
    }

    /** Compiles in-process and returns the jar's bytes; exits 2 on a compile error. */
    static byte[] compileJar(Map<String, Object> hb) throws IOException {
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            err("❌ This runtime has no javac (" + System.getProperty("java.home") + ") — use a JDK, not a JRE.");
            System.exit(2);
        }
        Path src = ROOT.resolve(asStr(hb.get("source")));
        Path out = Files.createTempDirectory("archhook-build");
        try {
            ByteArrayOutputStream diag = new ByteArrayOutputStream();
            int rc = javac.run(null, diag, diag, "--release", String.valueOf(num(hb.get("release"))),
                    "-encoding", "UTF-8", "-nowarn", "-d", out.toString(), src.toString());
            if (rc != 0) {
                err("❌ " + asStr(hb.get("source")) + " does not compile — the jar was left as it was:");
                err(diag.toString(StandardCharsets.UTF_8));
                System.exit(2);
            }
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\n"
                    + "Main-Class: " + asStr(hb.get("main_class")) + "\r\n"
                    + "Build-Jdk-Spec: " + num(hb.get("javac_feature")) + "\r\n"
                    + "Created-By: ArchHook build\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            entries.put(asStr(hb.get("hash_entry")),
                    (sourceHash(src) + "\n").getBytes(StandardCharsets.UTF_8));
            List<Path> classes;
            try (Stream<Path> s = Files.walk(out)) {
                classes = s.filter(Files::isRegularFile).collect(Collectors.toList());
            }
            TreeMap<String, byte[]> sorted = new TreeMap<>();
            for (Path c : classes) sorted.put(out.relativize(c).toString().replace('\\', '/'), Files.readAllBytes(c));
            entries.putAll(sorted);
            return storedZip(entries);
        } finally {
            try (Stream<Path> s = Files.walk(out)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * STORED, fixed local timestamp, caller's order: no deflater version and no clock can
     * change a byte. {@code JarOutputStream} is not used — it stamps entries with the time.
     */
    static byte[] storedZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.setMethod(java.util.zip.ZipOutputStream.STORED);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                java.util.zip.ZipEntry z = new java.util.zip.ZipEntry(e.getKey());
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(e.getValue());
                z.setMethod(java.util.zip.ZipEntry.STORED);
                z.setSize(e.getValue().length);
                z.setCompressedSize(e.getValue().length);
                z.setCrc(crc.getValue());
                z.setTimeLocal(JAR_EPOCH);
                zip.putNextEntry(z);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** SHA-256 of the source with CRLF read as LF — `* text=auto` gives Windows CRLF. */
    static String sourceHash(Path src) throws IOException {
        String body = Files.readString(src, StandardCharsets.UTF_8).replace("\r\n", "\n");
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * Null when the jar was built from the source as it is now (or there is nothing to
     * compare); otherwise the line that says what is wrong and how to fix it.
     */
    static String hookJarProblem(Map<String, Object> hb) {
        if (hb == null) return null;
        Path src = ROOT.resolve(asStr(hb.get("source")));
        if (!Files.isRegularFile(src)) return null;
        String fix = "run `java " + asStr(hb.get("source")) + " build` under JDK "
                + num(hb.get("javac_feature")) + " and commit the jar";
        Path jar = ROOT.resolve(asStr(hb.get("jar")));
        if (!Files.isRegularFile(jar)) {
            return asStr(hb.get("jar")) + " is missing — every hook launches it and exits 1 (no block) without it; " + fix;
        }
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry e = z.getEntry(asStr(hb.get("hash_entry")));
            String recorded = e == null ? ""
                    : new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).strip();
            if (recorded.equals(sourceHash(src))) return null;
            return asStr(hb.get("jar")) + " is stale — built from another version of "
                    + asStr(hb.get("source")) + ", so every hook runs the old code; " + fix;
        } catch (IOException ex) {
            return asStr(hb.get("jar")) + " is unreadable (" + ex.getMessage() + "); " + fix;
        }
    }

    /**
     * Temp file in the same directory, then an atomic rename. On Windows a jar a running
     * hook holds open cannot be replaced; the parallel hooks of one event release it in
     * well under the retry window.
     */
    static void replaceFile(Path target, byte[] content) throws IOException, InterruptedException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + ProcessHandle.current().pid());
        Files.write(tmp, content);
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                if (attempt >= 20) { Files.deleteIfExists(tmp); throw e; }
                Thread.sleep(250);
            }
        }
    }

    // ── compose ──────────────────────────────────────────────────────────────
    //
    // Five questions `docker compose up -d` does not answer, all cheap:
    //   1. Is every service of this project actually running — not `created`, not
    //      `exited`?
    //   2. Is a container from ANOTHER project publishing a host port this project's
    //      compose file also declares?
    //   3. Does every `image:` of the compose file carry the same tag the test suite
    //      pins for that same repository in `DockerImageName.parse(...)`?
    //   4. Does a service that publishes a port to the host also ADVERTISE an address the
    //      host can reach? Publishing `9092:9092` while advertising only `kafka:9092` is a
    //      promise the compose network keeps and the host cannot: the client bootstraps
    //      against localhost, the broker answers with its advertised address, and every
    //      call after that dies on `UnknownHostException: kafka` — lessons-learned-013 § 11,
    //      shipped by the `docker-architect` exemplar itself. Nothing else sees it: the
    //      healthcheck runs INSIDE the container, where `localhost` is the service, and
    //      Testcontainers configures its own listeners, so the ITs exercise a broker the
    //      compose file does not produce.
    //   5. Does every `${VAR:default}` the application points at a compose service hold on
    //      both sides — the default reaching a port that service publishes, for the run on
    //      the host, and `app` setting VAR, for the run in the container? The collector
    //      published nothing from 0046 to issue #66, so every host-run export failed, and
    //      projects older than 0046 never set the metrics endpoint on `app`.
    //      Its login half — the database, the user and whether a password exists, compared
    //      with the database service the datasource reaches — is a warning, never a block:
    //      lessons-learned-021, decision 0124.
    //
    // Questions 3 to 5 need no Docker at all — they read files — and they are here because
    // this mode already owns `docker-compose.yml`. Two skills used to promise the match in
    // prose (`docker-architect` step 4, `test-architect`'s setup mode), with a YAML comment
    // as the only link between the halves and the execution order deciding which side led.
    // A promise that has to hold is a hook, not a paragraph — `@CLAUDE.md` invariant 6.
    // The mismatch is silent: the test suite passes against an engine version nobody runs.
    //
    // Why this is a hook and not a paragraph in docker-architect/SKILL.md: `docker
    // compose up -d` exits 0 even when an individual service never starts. A container
    // that cannot bind its published host port stays in `Created`, and the command still
    // reports success. It happened for real — an `otel-collector` left running for eight
    // days by a sibling project generated from the same blueprint held host port 4318;
    // this project's collector sat in `Created`; the application shipped every span and
    // metric to the wrong container, which answered with its own older config. Two
    // investigations, one `docker ps` away from the answer. Prose only helps whoever
    // reads it at the right moment. This runs.
    //
    // Never blocks, and Docker is not a dependency of this repository: no compose file,
    // no `docker` on PATH, or a daemon that is down all report and return.

    /** Seconds each `docker` call gets before it is given up on. See {@link #runTimed}. */
    static final int DOCKER_TIMEOUT = 10;

    /**
     * {@code warnings} never change {@code ok}: they are printed by the report form and by
     * {@code doctor}, and the gate never reads them. Decision 0124.
     */
    record ComposeReport(boolean ok, String summary, List<String> detail, List<String> warnings) {}

    static void compose(String sub, String stdin) {
        if ("gate".equals(sub)) { composeGate(stdin); return; }
        ComposeReport r = composeReport();
        err("ArchHook compose");
        err("  Project root ...... " + ROOT);
        report("Compose", r.ok(), r.summary(), r.summary());
        for (String d : r.detail()) err("    " + d);
        for (String w : r.warnings()) err("    ⚠️  " + w);
        err("");
        err(!r.ok() ? "⚠️  See the marked lines above."
                : r.warnings().isEmpty() ? "✅ Compose healthy."
                : "✅ Compose healthy, with " + r.warnings().size()
                        + " warning(s) above that do not block.");
    }

    /**
     * The same report as a gate: silent while healthy, exit 2 with the failing lines otherwise.
     *
     * <p>Why this exists at all, when `compose` already runs the check: it had never been run.
     * `docker-architect` step 7 calls it "the one command that verifies the result" and "not
     * optional", and a `kafka` block publishing 9092 while advertising only `kafka:9092` still
     * shipped on day one and stayed unreachable from the host until a use case needed it —
     * lessons-learned-014 § 13. Both green signals that stood in for it describe a broker the
     * file does not produce: the healthcheck runs inside the container, where `localhost` IS
     * the broker, and Testcontainers wires its own advertised listeners. So the missing piece
     * was never a check; it was an exit code and a registration.
     *
     * <p>Registered on `Stop`, next to `tests`, in `.claude/settings.json` and in
     * `project-bootstrap/templates/settings.json.example`. `composeReport()` stays the single
     * definition of healthy, shared with `doctor` and with the report form — this adds no second
     * opinion. A project with no compose file returns before the first `docker` call, and a
     * machine with the daemon off still gets the two file-only checks, which is the half that
     * catches the defect above. Design: `.claude/decisions/0064-compose-gate-on-stop.md`.
     *
     * <p>A foreign container on one of our ports blocks only once a container of this project
     * exists; before that it is a warning `compose` and `doctor` print and this gate never
     * reads, because a stack nobody started has nothing to bind yet. Scoping by the turn's
     * change set was the closest rejected form: the daemon half catches what no file change
     * caused. Design: `.claude/decisions/0129-compose-gate-foreign-port-warns-without-our-stack.md`.
     */
    static void composeGate(String stdin) {
        // If the Stop hook already blocked before, don't block again: avoids cycles.
        if (Pattern.compile("\"stop_hook_active\"\\s*:\\s*true").matcher(stdin).find()) return;
        ComposeReport r = composeReport();
        if (r.ok()) return;
        err("❌ Compose: " + r.summary());
        for (String d : r.detail()) err("   " + d);
        err("Fix docker-compose.yml, or run `java .claude/hooks/ArchHook.java compose` for the");
        err("full report. A service stopped on purpose is still a stopped service here.");
        System.exit(2);
    }

    /**
     * Diagnoses this project's compose services. Shared by `compose` and `doctor` so the
     * two can never disagree about what "healthy" means.
     */
    static ComposeReport composeReport() {
        Path file = Stream.of("docker-compose.yml", "docker-compose.yaml", "compose.yml",
                        "compose.yaml")
                .map(ROOT::resolve).filter(Files::isRegularFile).findFirst().orElse(null);
        if (file == null) {
            return new ComposeReport(true, "no compose file — nothing to check (optional)",
                    List.of(), List.of());
        }

        Map<String, Set<String>> declared = composeHostPorts(readOrNull(file));

        // Files only, no daemon: computed before the first `docker` call so it survives
        // every early return below. A machine with Docker off still gets this answer.
        List<String> tagIssues = new ArrayList<>(imageTagMismatches(file));
        tagIssues.addAll(advertisedAddressIssues(file, declared));
        tagIssues.addAll(placeholderIssues(file, declared));
        List<String> warnings = new ArrayList<>(datasourceLoginWarnings(file, declared));

        Proc ps;
        try {
            ps = runTimed(DOCKER_TIMEOUT, "docker", "compose", "ps", "-a", "--format", "json");
        } catch (Exception e) {
            return withTags(tagIssues, warnings,
                    "docker not on PATH — service state not checked (optional)");
        }
        if (ps.exit() == -1) {
            return withTags(tagIssues, warnings, "`docker compose ps` did not answer in "
                    + DOCKER_TIMEOUT + "s (daemon starting?) — not checked");
        }
        if (ps.exit() != 0) {
            return withTags(tagIssues, warnings,
                    "`docker compose ps` failed (daemon down?) — not checked");
        }

        List<String> detail = new ArrayList<>();
        Set<String> ours = new LinkedHashSet<>();
        int running = 0, total = 0;
        for (Object o : composeEntries(ps.out())) {
            Map<String, Object> m = asMap(o);
            if (m == null) continue;
            total++;
            String name = orDash(asStr(m.get("Name")));
            String svc = orDash(asStr(m.get("Service")));
            String state = Optional.ofNullable(asStr(m.get("State"))).orElse("?")
                    .toLowerCase(Locale.ROOT);
            ours.add(name);
            if (state.startsWith("running")) { running++; continue; }
            detail.add("service `" + svc + "` is " + state + ", not running"
                    + ("created".equals(state)
                            ? " — a `created` container usually failed to bind a"
                              + " published port; see the port collisions below"
                            : "")
                    + "  →  docker compose logs " + svc);
        }

        // A foreign container holding one of our host ports. This is the check that would
        // have answered lessons-learned-008 in seconds, and it runs even when every
        // service above is fine: the collision is what stops a service from starting.
        // With no container of ours at all, nothing is binding yet: the line is a warning
        // for the next `up`, so `compose gate` does not block a turn on another project's
        // state. Design: .claude/decisions/0129-compose-gate-foreign-port-warns-without-our-stack.md
        List<String> collisions = total == 0 ? warnings : detail;
        Set<String> wanted = declared.values().stream().flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!wanted.isEmpty()) {
            try {
                Proc all = runTimed(DOCKER_TIMEOUT, "docker", "ps",
                        "--format", "{{.Names}}\t{{.Ports}}");
                if (all.exit() == 0) {
                    for (String line : all.out()) {
                        int tab = line.indexOf('\t');
                        if (tab < 0) continue;
                        String name = line.substring(0, tab).strip();
                        if (ours.contains(name)) continue;
                        Set<String> held = publishedPorts(line.substring(tab + 1));
                        held.retainAll(wanted);
                        for (String p : held) {
                            collisions.add("host port " + p + " is held by `" + name
                                    + "`, a container of ANOTHER project — "
                                    + declaredBy(declared, p) + " here cannot bind it"
                                    + (total == 0
                                            ? " once `docker compose up` runs (nothing of"
                                              + " this project is up yet, so not blocking)"
                                            : "")
                                    + "  →  docker stop " + name);
                        }
                    }
                }
            } catch (Exception ignored) {
                // `docker compose ps` worked, so this failing is not worth a line.
            }
        }

        detail.addAll(tagIssues);
        boolean ok = detail.isEmpty();
        String summary = ok
                ? running + "/" + total
                        + " service(s) running, no blocking port collision, image tags match,"
                        + " published ports advertised to the host, placeholders hold on host"
                        + " and container"
                : detail.size() + " problem(s) — " + running + "/" + total + " running";
        return new ComposeReport(ok, summary, detail, warnings);
    }

    /**
     * A report whose service-state half could not be checked. The tag comparison and the
     * advertised-address check read files only, so they still count — and still fail the
     * report, however unreachable the daemon is.
     */
    static ComposeReport withTags(List<String> fileIssues, List<String> warnings, String summary) {
        if (fileIssues.isEmpty()) return new ComposeReport(true, summary, List.of(), warnings);
        return new ComposeReport(false,
                fileIssues.size() + " problem(s) read from the compose file; " + summary,
                fileIssues, warnings);
    }

    /** Accepts both shapes `docker compose ps --format json` emits: an array, or one object per line. */
    static List<Object> composeEntries(List<String> out) {
        String joined = String.join("\n", out).strip();
        if (joined.isEmpty()) return List.of();
        Object arr = Json.parse(joined);
        if (arr instanceof List) return asList(arr);
        List<Object> entries = new ArrayList<>();
        for (String line : out) {
            String s = line.strip();
            if (!s.startsWith("{")) continue;
            Object o = Json.parse(s);
            if (o != null) entries.add(o);
        }
        return entries;
    }

    /**
     * Host ports each service publishes, read from the compose file itself rather than
     * from `docker ps`: a service that never started publishes nothing, and that is
     * precisely the one whose port is being held by someone else.
     */
    static Map<String, Set<String>> composeHostPorts(String yaml) {
        Map<String, Set<String>> byService = new LinkedHashMap<>();
        if (yaml == null) return byService;
        boolean inServices = false, inPorts = false;
        String service = null;
        for (String raw : yaml.split("\r?\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String body = line.strip();

            if (indent == 0) {                       // top-level key
                inServices = body.startsWith("services:");
                inPorts = false;
                service = null;
                continue;
            }
            if (!inServices) continue;
            if (indent == 2 && body.endsWith(":")) { // a service name
                service = body.substring(0, body.length() - 1).strip();
                inPorts = false;
                continue;
            }
            if (service == null) continue;
            if (indent == 4) {                       // a key inside the service
                inPorts = body.startsWith("ports:");
                continue;
            }
            if (inPorts && body.startsWith("- ")) {
                String p = hostPort(body.substring(2).strip());
                if (p != null) byService.computeIfAbsent(service, k -> new LinkedHashSet<>()).add(p);
            }
        }
        return byService;
    }

    /**
     * The host side of one compose `ports:` entry, or null when there is none to collide
     * over — the short form `"4318"` asks Docker for an ephemeral port, which is the
     * whole point of writing it that way.
     */
    static String hostPort(String entry) {
        String s = entry.replace("\"", "").replace("'", "").strip();
        // ${VAR:-16686} resolves to its default; a bare ${VAR} has no value to compare.
        s = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)}").matcher(s).replaceAll("$1");
        if (s.contains("${")) return null;
        int slash = s.indexOf('/');                  // strip /tcp, /udp
        if (slash >= 0) s = s.substring(0, slash);
        String[] parts = s.split(":");
        if (parts.length < 2) return null;           // ephemeral short form
        String host = parts[parts.length - 2].strip();
        return host.matches("\\d+(-\\d+)?") ? host : null;
    }

    /** One image reference pinned by a test file: repository, tag, and where it was read. */
    record ImagePin(String repo, String tag, String where) {}

    /**
     * Compose services whose `image:` tag disagrees with the tag `src/test` pins for the
     * same repository. Compared per repository, reported per tag: `postgres:16-alpine` in
     * the compose file against `postgres:15` in `TestcontainersConfiguration.java` is the
     * whole failure mode — the suite proves nothing about the engine that actually runs.
     *
     * <p>A repository that appears on only one side is not a mismatch: a Testcontainers-only
     * dependency needs no compose service, and a compose service can exist with no test
     * touching it.
     */
    static List<String> imageTagMismatches(Path composeFile) {
        Map<String, String> images = composeImages(readOrNull(composeFile));
        if (images.isEmpty()) return List.of();
        List<ImagePin> pins = testImagePins();
        if (pins.isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        String compose = relative(composeFile);
        for (Map.Entry<String, String> e : images.entrySet()) {
            String[] img = splitImage(e.getValue());
            if (img == null) continue;
            for (ImagePin pin : pins) {
                if (!pin.repo().equals(img[0]) || pin.tag().equals(img[1])) continue;
                out.add("image tag mismatch for `" + img[0] + "`: " + compose + " service `"
                        + e.getKey() + "` pins `" + img[1] + "`, " + pin.where() + " pins `"
                        + pin.tag() + "` — the tests then run against a different version"
                        + " than `docker compose up` does  →  make both the same tag");
            }
        }
        return out;
    }

    /**
     * Services that publish a port to the host while advertising only an address the host
     * cannot resolve. Reads two files' worth of nothing — the compose file and
     * `.claude/schemas/extensions.json` — so it answers with the daemon down, which is the
     * point: the defect it looks for is invisible to every runtime check. The broker's own
     * healthcheck runs inside the container and passes, `docker compose ps` says `running`,
     * and the first host-side client still dies on `UnknownHostException`.
     *
     * <p>The rule, and it is narrow on purpose: a service that publishes at least one host
     * port AND declares an env key ending in one of `compose.advertised_env_suffixes` must
     * advertise at least one entry whose host is in `compose.host_addresses` and whose port
     * that same service publishes. Advertising `localhost:29092` without publishing 29092 is
     * the same broken promise in the other direction, so both halves are checked together.
     * A service that advertises nothing claims nothing and is left alone.
     */
    static List<String> advertisedAddressIssues(Path composeFile, Map<String, Set<String>> ports) {
        Map<String, Object> cfg = asMap(get(asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE)))),
                "compose"));
        if (cfg == null) return List.of();
        List<String> suffixes = asStrList(cfg.get("advertised_env_suffixes"));
        List<String> hosts = asStrList(cfg.get("host_addresses"));
        if (suffixes.isEmpty() || hosts.isEmpty()) return List.of();

        Map<String, Map<String, String>> env = composeServiceEnv(readOrNull(composeFile));
        List<String> out = new ArrayList<>();
        String compose = relative(composeFile);
        for (Map.Entry<String, Map<String, String>> svc : env.entrySet()) {
            Set<String> published = ports.getOrDefault(svc.getKey(), Set.of());
            if (published.isEmpty()) continue;
            for (Map.Entry<String, String> e : svc.getValue().entrySet()) {
                String key = e.getKey();
                if (suffixes.stream().noneMatch(key::endsWith)) continue;
                boolean reachable = false;
                for (String entry : composeDefaults(e.getValue()).split(",")) {
                    String addr = entry.strip();
                    int scheme = addr.indexOf("://");
                    if (scheme >= 0) addr = addr.substring(scheme + 3);
                    int colon = addr.lastIndexOf(':');
                    if (colon < 0) continue;
                    if (hosts.contains(addr.substring(0, colon).strip())
                            && published.contains(addr.substring(colon + 1).strip())) {
                        reachable = true;
                        break;
                    }
                }
                if (reachable) continue;
                out.add("service `" + svc.getKey() + "` publishes host port(s) "
                        + String.join(", ", published) + " but `" + key + "` advertises `"
                        + e.getValue() + "` — no address the host can resolve on a published"
                        + " port, so a client on the host bootstraps, is redirected to the"
                        + " advertised address and never connects again  →  give the service a"
                        + " second listener (one per network) in " + compose + ", advertised as "
                        + hosts.get(0) + ":<published port>");
            }
        }
        return out;
    }

    /** One `${NAME:default}` read from an application config file. */
    record Placeholder(String name, String fallback, String property, String file) {}

    /**
     * Question 5: every `${VAR:default}` the application points at a compose service has to
     * hold on both sides of the network. The default serves `./mvnw spring-boot:run` on the
     * host; the `app` service's environment serves the container. Each side can be wrong
     * alone, and neither failure shows anywhere else:
     *
     * <p>(a) `app` sets `VAR` to `<svc>:<port>`, and the default is not a host address on a
     * port `<svc>` publishes. The host run cannot reach `<svc>` — the OTLP collector from
     * 0046 until issue #66, every export failing while `compose` reported healthy.
     *
     * <p>(b) The default is a host address on a port another service publishes, and `app`
     * does not set `VAR`. Inside the container `localhost` is the application itself — the
     * metrics endpoint of projects generated before 0046. Skipped when a config file already
     * names `<svc>:<port>`: a profile file then wires the container side.
     *
     * <p>Reads files only, like questions 3 and 4, so it answers with the daemon down. Which
     * files are config and which service is the application are data, `compose.app_config_globs`
     * and `compose.app_service_key` (invariant 10). A placeholder with no default claims nothing
     * about the host and is left alone. Design: `.claude/decisions/0110-otlp-host-first-collector.md`.
     */
    static List<String> placeholderIssues(Path composeFile, Map<String, Set<String>> ports) {
        Map<String, Object> cfg = asMap(get(asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE)))),
                "compose"));
        if (cfg == null) return List.of();
        List<String> hosts = asStrList(cfg.get("host_addresses"));
        List<String> globs = asStrList(cfg.get("app_config_globs"));
        String appKey = asStr(cfg.get("app_service_key"));
        if (hosts.isEmpty() || globs.isEmpty() || appKey == null) return List.of();

        String yaml = readOrNull(composeFile);
        Map<String, Set<String>> keys = composeServiceKeys(yaml);
        Set<String> apps = keys.entrySet().stream().filter(e -> e.getValue().contains(appKey))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
        if (apps.isEmpty()) return List.of();

        List<Placeholder> placeholders = new ArrayList<>();
        StringBuilder allConfig = new StringBuilder();
        for (Path f : projectFiles(rel -> matchesAny(globs, rel))) {
            String content = readOrNull(f);
            if (content == null) continue;
            allConfig.append(content).append('\n');
            placeholders.addAll(placeholdersIn(content, relative(f)));
        }
        if (placeholders.isEmpty()) return List.of();

        Map<String, Map<String, String>> env = composeServiceEnv(yaml);
        Set<String> services = keys.keySet();
        String compose = relative(composeFile);
        List<String> out = new ArrayList<>();
        for (String app : apps) {
            Map<String, String> appEnv = env.getOrDefault(app, Map.of());
            for (Placeholder p : placeholders) {
                // Relaxed binding: `SPRING_DATASOURCE_URL` replaces the whole property, so it
                // overrides `${DB_URL:…}` as surely as `DB_URL` would.
                String set = Stream.of(p.name(), envForm(p.name()), envForm(p.property()))
                        .filter(appEnv::containsKey).map(appEnv::get).findFirst().orElse(null);
                List<String[]> fallback = hostPorts(p.fallback());
                if (set != null) {
                    for (String[] target : hostPorts(set)) {
                        String svc = target[0];
                        if (!services.contains(svc) || apps.contains(svc)) continue;
                        Set<String> published = ports.getOrDefault(svc, Set.of());
                        boolean reachable = fallback.stream().anyMatch(hp ->
                                hosts.contains(hp[0]) && published.contains(hp[1]));
                        if (reachable) continue;
                        out.add("`" + p.name() + "` reaches `" + svc + "` from service `" + app
                                + "` (`" + set + "`), but its default in " + p.file() + " is `"
                                + p.fallback() + "` and `" + svc + "` "
                                + (published.isEmpty() ? "publishes no host port"
                                        : "publishes only " + String.join(", ", published))
                                + " — the application run on the host cannot reach it  →  publish"
                                + " the port in " + compose + " (variable host port, e.g."
                                + " `\"${<SVC>_PORT:-<port>}:<port>\"`) and default `" + p.name()
                                + "` to " + hosts.get(0) + ":<published port>");
                    }
                    continue;
                }
                for (String[] hp : fallback) {
                    if (!hosts.contains(hp[0])) continue;
                    String svc = ports.entrySet().stream()
                            .filter(e -> !apps.contains(e.getKey()) && e.getValue().contains(hp[1]))
                            .map(Map.Entry::getKey).findFirst().orElse(null);
                    if (svc == null) continue;
                    if (Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(svc) + ":\\d")
                            .matcher(allConfig).find()) continue;
                    out.add(p.file() + " defaults `" + p.name() + "` to `" + p.fallback()
                            + "`, a host port `" + svc + "` publishes, but service `" + app
                            + "` in " + compose + " does not set `" + p.name() + "` — inside the"
                            + " container `" + hp[0] + "` is the application itself, so it never"
                            + " reaches `" + svc + "`  →  add `" + p.name() + "` to `" + app
                            + "`'s environment, pointing at `" + svc + "` by its compose hostname");
                    break;
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /**
     * Question 5, login half: the datasource reaches a database service, so does it log in?
     * The host-and-port half above passed {@code jdbc:postgresql://localhost:5432/bankingapp}
     * against a {@code postgres} service that created {@code appdb} for user {@code app}: the
     * socket opened and the login failed, and {@code compose} said "placeholders hold on host
     * and container" — lessons-learned-021 § 3, the 0110 shape again.
     *
     * <p>For each side that reaches a database service — the default in the base
     * {@code application.yml} (the run on the host) and the value {@code app} sets (the run in
     * the container) — it compares the database in the JDBC URL and the user with the
     * service's own environment, every {@code ${VAR:default}} read as its default. The
     * password is never compared and never printed: only whether the service requires one
     * and the application side has none (absent, empty, or an empty default).
     *
     * <p>Why this is a warning and not a block, unlike the rest of question 5: a project may
     * declare a database service on the port its datasource uses while the developer connects
     * to another database there on purpose, and a block on every {@code Stop} would stop a
     * legitimate setup. The templates are aligned by construction, so what this catches is
     * drift. Which properties pair with which service variables, per engine image, is data —
     * {@code compose.datasource_pairs} (invariant 10). Invoked through {@code composeReport()}
     * by {@code compose} and {@code doctor}; {@code compose gate} on {@code Stop} never reads
     * it. Design: {@code .claude/decisions/0124-datasource-defaults-one-convention-checked-by-compose.md}.
     */
    static List<String> datasourceLoginWarnings(Path composeFile, Map<String, Set<String>> ports) {
        Map<String, Object> cfg = asMap(get(asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE)))),
                "compose"));
        if (cfg == null) return List.of();
        List<Object> pairs = asList(cfg.get("datasource_pairs"));
        List<String> hosts = asStrList(cfg.get("host_addresses"));
        List<String> globs = asStrList(cfg.get("app_config_globs"));
        String appKey = asStr(cfg.get("app_service_key"));
        if (pairs == null || hosts.isEmpty() || globs.isEmpty() || appKey == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Object pair : pairs) {
            Map<String, Object> p = asMap(pair);
            if (p != null) out.addAll(pairLoginWarnings(p, hosts, globs, appKey, composeFile, ports));
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /**
     * The login comparison for one pair of {@code compose.datasource_pairs}: one connection
     * property set (a JDBC datasource, a MongoDB URI) against the engines it may reach. The
     * username and the password come from their own properties when the pair names them, else
     * from the URL through the pair's patterns — a MongoDB URI carries both in its user info.
     */
    static List<String> pairLoginWarnings(Map<String, Object> pair, List<String> hosts, List<String> globs,
                                          String appKey, Path composeFile, Map<String, Set<String>> ports) {
        String urlProp = asStr(pair.get("url_property"));
        String userProp = asStr(pair.get("username_property"));
        String pwdProp = asStr(pair.get("password_property"));
        List<Object> engines = asList(pair.get("engines"));
        if (urlProp == null || engines == null) return List.of();
        Pattern userInUrl = pattern(asStr(pair.get("username_pattern")));
        Pattern pwdInUrl = pattern(asStr(pair.get("password_pattern")));
        Map<String, Object> hints = asMap(pair.get("variables"));

        // The base file only: the host run has no profile active, and a profile file is what
        // a container usually layers on top.
        Map<String, String> props = new LinkedHashMap<>();
        String configFile = null;
        for (Path f : projectFiles(rel -> matchesAny(globs, rel)
                && rel.matches("(.*/)?application\\.ya?ml"))) {
            String content = readOrNull(f);
            if (content == null) continue;
            Map<String, String> read = yamlScalars(content);
            if (configFile == null && read.containsKey(urlProp)) configFile = relative(f);
            read.forEach(props::putIfAbsent);
        }
        String rawUrl = props.get(urlProp);
        if (rawUrl == null) return List.of();
        String rawUser = userProp == null ? null : props.get(userProp);
        String rawPwd = pwdProp == null ? null : props.get(pwdProp);

        String yaml = readOrNull(composeFile);
        Map<String, String> images = composeImages(yaml);
        Map<String, Map<String, String>> env = composeServiceEnv(yaml);
        Map<String, Set<String>> keys = composeServiceKeys(yaml);
        Set<String> apps = keys.entrySet().stream().filter(e -> e.getValue().contains(appKey))
                .map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
        String compose = relative(composeFile);
        List<String> out = new ArrayList<>();

        // Host side: the defaults of the base file. A placeholder with no default inside the URL
        // — a password in a MongoDB URI — is kept as a marker, so the rest of the URL is read.
        String hostUrl = urlDefaults(rawUrl);
        String hostSvc = null;
        for (String[] hp : hostPorts(hostUrl)) {
            if (!hosts.contains(hp[0])) continue;
            hostSvc = ports.entrySet().stream()
                    .filter(e -> !apps.contains(e.getKey()) && e.getValue().contains(hp[1]))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (hostSvc != null) break;
        }
        if (hostSvc != null) {
            String user = rawUser != null ? springDefault(rawUser) : group(userInUrl, hostUrl);
            boolean hasPwd;
            if (rawPwd != null) {
                String pwd = springDefault(rawPwd);
                hasPwd = pwd == null || !pwd.isEmpty();
            } else {
                hasPwd = group(pwdInUrl, hostUrl) != null;
            }
            loginCompare(out, pair, engines, images, env, hostSvc, compose,
                    "the host run (defaults in " + configFile + ")", hostUrl, user, hasPwd, hints, userProp, urlProp);
        }

        // Container side: what each application service sets, by placeholder name or by the
        // relaxed-binding form of the property; the file's default where it sets nothing.
        for (String app : apps) {
            Map<String, String> appEnv = env.getOrDefault(app, Map.of());
            String url = composeDefaults(appSet(appEnv, rawUrl, urlProp));
            if (url == null) continue;
            String svc = null;
            for (String[] hp : hostPorts(url)) {
                if (images.containsKey(hp[0]) && !apps.contains(hp[0])) { svc = hp[0]; break; }
            }
            if (svc == null) continue;
            String user;
            boolean hasPwd;
            if (rawUser != null || userProp != null && appSet(appEnv, rawUser, userProp) != null) {
                user = appSet(appEnv, rawUser, userProp);
                user = user != null ? composeDefaults(user) : springDefault(rawUser);
            } else {
                user = group(userInUrl, url);
            }
            if (rawPwd != null || pwdProp != null && appSet(appEnv, rawPwd, pwdProp) != null) {
                String setPwd = appSet(appEnv, rawPwd, pwdProp);
                String filePwd = rawPwd == null ? null : springDefault(rawPwd);
                hasPwd = setPwd != null || (filePwd != null && !filePwd.isEmpty());
            } else {
                hasPwd = group(pwdInUrl, url) != null;
            }
            loginCompare(out, pair, engines, images, env, svc, compose, "service `" + app + "`",
                    url, user, hasPwd, hints, userProp, urlProp);
        }
        return out;
    }

    /** A compiled pattern from data, or null when the pair names none. */
    static Pattern pattern(String regex) {
        return regex == null || regex.isBlank() ? null : Pattern.compile(regex);
    }

    /** Group 1 of a pattern found in a value, or null — a missing pattern finds nothing. */
    static String group(Pattern p, String value) {
        if (p == null || value == null) return null;
        Matcher m = p.matcher(value);
        return m.find() && m.group(1) != null && !m.group(1).isEmpty() ? m.group(1) : null;
    }

    /**
     * A Spring value read as its defaults, like {@link #springDefault}, except that a placeholder
     * with no default becomes the marker {@code <unset>} instead of discarding the whole value:
     * {@code mongodb://${U:app}:${PWD}@localhost:27017/app} reads as
     * {@code mongodb://app:<unset>@localhost:27017/app}, so its host, port and user stay comparable.
     * The marker holds no digit, so it is never read as a port.
     */
    static String urlDefaults(String raw) {
        if (raw == null) return null;
        Pattern inner = Pattern.compile("\\$\\{([^:{}]+)(?::([^{}]*))?}");
        String s = raw;
        for (int guard = 0; guard < 16; guard++) {
            Matcher m = inner.matcher(s);
            if (!m.find()) return s;
            String value = m.group(2) == null ? "<unset>" : m.group(2);
            s = s.substring(0, m.start()) + value + s.substring(m.end());
        }
        return null;
    }

    /** One side of the login comparison against one database service. Never prints a password. */
    static void loginCompare(List<String> out, Map<String, Object> pair, List<Object> engines,
                             Map<String, String> images, Map<String, Map<String, String>> env, String svc,
                             String compose, String side, String url, String user, boolean hasPwd,
                             Map<String, Object> hints, String userProp, String urlProp) {
        String[] img = images.containsKey(svc) ? splitImage(images.get(svc)) : null;
        if (img == null) return;
        String repo = img[0].substring(img[0].lastIndexOf('/') + 1);
        Map<String, Object> engine = null;
        for (Object o : engines) {
            Map<String, Object> e = asMap(o);
            if (e != null && asStrList(e.get("images")).contains(repo)) { engine = e; break; }
        }
        if (engine == null) return;
        Map<String, String> svcEnv = env.getOrDefault(svc, Map.of());
        String dbVar = hints == null ? null : asStr(hints.get("database"));
        String userVar = hints == null ? null : asStr(hints.get("username"));
        String pwdVar = hints == null ? null : asStr(hints.get("password"));

        String dbKey = asStr(engine.get("database_env"));
        String svcDb = dbKey == null ? null : composeDefaults(svcEnv.get(dbKey));
        String appDb = group(pattern(asStr(pair.get("database_pattern"))), url);
        if (appDb != null && svcDb != null && !svcDb.contains("${") && !appDb.equals(svcDb)) {
            out.add("login: " + side + " connects to database `" + appDb + "`, but service `" + svc
                    + "` creates `" + svcDb + "` (`" + dbKey + "` in " + compose + ") — the socket"
                    + " opens and the login fails  →  read one variable on both sides (`" + dbVar
                    + "`, the same default in `" + urlProp + "` and in `" + dbKey + "`)");
        }

        String userKey = asStr(engine.get("username_env"));
        String svcUser = userKey == null ? null : composeDefaults(svcEnv.get(userKey));
        if (user != null && svcUser != null && !svcUser.contains("${") && !user.contains("${")
                && !user.equals(svcUser)) {
            out.add("login: " + side + " logs in as `" + user + "`, but service `" + svc
                    + "` creates user `" + svcUser + "` (`" + userKey + "` in " + compose + ")  →"
                    + "  read one variable on both sides (`" + userVar + "`, the same default in"
                    + " `" + (userProp != null ? userProp : urlProp) + "` and in `" + userKey + "`)");
        }

        String pwdKey = asStr(engine.get("password_env"));
        String svcPwd = pwdKey == null ? null : svcEnv.get(pwdKey);
        if (svcPwd != null && !svcPwd.isBlank() && !hasPwd) {
            out.add("login: service `" + svc + "` requires a password (`" + pwdKey + "` in "
                    + compose + "), and " + side + " has none — absent, empty, or an empty"
                    + " default  →  read it from `" + pwdVar + "` with no default, kept in the"
                    + " untracked `.env`");
        }
    }

    /**
     * What an application service sets for a property: the variable its placeholder names,
     * else the relaxed-binding form of the property. Null when it sets neither.
     */
    static String appSet(Map<String, String> appEnv, String raw, String property) {
        Matcher m = Pattern.compile("^\\$\\{([^:}]+)").matcher(raw == null ? "" : raw);
        if (m.find() && appEnv.containsKey(m.group(1).strip())) return appEnv.get(m.group(1).strip());
        return appEnv.get(envForm(property));
    }

    /**
     * A Spring value read as its defaults: {@code ${A:${B:x}}} is {@code x}, {@code ${A:}} is
     * empty. Null when a placeholder has no default — the value comes from the environment
     * and there is nothing in the file to compare.
     */
    static String springDefault(String raw) {
        if (raw == null) return null;
        Pattern inner = Pattern.compile("\\$\\{([^:{}]+)(?::([^{}]*))?}");
        String s = raw;
        for (int guard = 0; guard < 16; guard++) {
            Matcher m = inner.matcher(s);
            if (!m.find()) return s;
            if (m.group(2) == null) return null;
            s = s.substring(0, m.start()) + m.group(2) + s.substring(m.end());
        }
        return null;
    }

    /**
     * Compose interpolation read as its defaults: {@code ${VAR:-x}} and {@code ${VAR-x}} are
     * {@code x}. A {@code ${VAR}} or {@code ${VAR:?message}} has no default and stays as it is.
     */
    static String composeDefaults(String s) {
        if (s == null) return null;
        return Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*:?-([^}]*)}").matcher(s)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
    }

    /**
     * Every scalar of a YAML config file by its dotted property, first occurrence wins.
     * Same indentation walk as {@link #placeholdersIn}: no YAML library, one value per line.
     */
    static Map<String, String> yamlScalars(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        Deque<Map.Entry<Integer, String>> path = new ArrayDeque<>();
        Pattern key = Pattern.compile("^([A-Za-z0-9_.\\-\"']+)\\s*:(\\s+(.*))?$");
        for (String raw : content.split("\r?\n", -1)) {
            String body = raw.strip();
            if (body.isEmpty() || body.startsWith("#") || body.equals("---")) continue;
            int indent = raw.length() - raw.stripLeading().length();
            Matcher k = key.matcher(body);
            if (!k.find()) continue;
            while (!path.isEmpty() && path.peekLast().getKey() >= indent) path.removeLast();
            path.addLast(Map.entry(indent, k.group(1).replace("\"", "").replace("'", "")));
            String value = k.group(3) == null ? "" : k.group(3).replaceAll("\\s+#.*$", "").strip();
            if (value.isEmpty()) continue;
            String property = path.stream().map(Map.Entry::getValue).collect(Collectors.joining("."));
            out.putIfAbsent(property, unquote(value));
        }
        return out;
    }

    /**
     * Every `${NAME:default}` in a YAML config file, with the dotted property it sits under
     * (`spring.datasource.url`). Nested defaults resolve to their own default:
     * `${A:http://localhost:${P:4318}/x}` reads as `http://localhost:4318/x`. `${NAME}` with
     * no default is skipped. Same indentation walk as {@link #composeHostPorts}: no YAML
     * library, one placeholder never spans lines.
     */
    static List<Placeholder> placeholdersIn(String content, String file) {
        List<Placeholder> out = new ArrayList<>();
        Deque<Map.Entry<Integer, String>> path = new ArrayDeque<>();
        Pattern key = Pattern.compile("^([A-Za-z0-9_.\\-\"']+)\\s*:(\\s|$)");
        Pattern nested = Pattern.compile("\\$\\{[^:{}]+:([^{}]*)}");
        for (String raw : content.split("\r?\n", -1)) {
            String body = raw.strip();
            if (body.isEmpty() || body.startsWith("#") || body.equals("---")) continue;
            int indent = raw.length() - raw.stripLeading().length();
            Matcher k = key.matcher(body);
            if (k.find()) {
                while (!path.isEmpty() && path.peekLast().getKey() >= indent) path.removeLast();
                path.addLast(Map.entry(indent, k.group(1).replace("\"", "").replace("'", "")));
            }
            String property = path.stream().map(Map.Entry::getValue).collect(Collectors.joining("."));
            int i = 0;
            while ((i = body.indexOf("${", i)) >= 0) {
                int depth = 0, end = -1;
                for (int j = i; j < body.length(); j++) {
                    char c = body.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}' && --depth == 0) { end = j; break; }
                }
                if (end < 0) break;
                String inner = body.substring(i + 2, end);
                int colon = inner.indexOf(':');
                if (colon > 0) {
                    String fallback = inner.substring(colon + 1);
                    for (String prev = null; !fallback.equals(prev); ) {
                        prev = fallback;
                        fallback = nested.matcher(fallback)
                                .replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
                    }
                    out.add(new Placeholder(inner.substring(0, colon).strip(), fallback.strip(),
                            property, file));
                }
                i = end + 1;
            }
        }
        return out;
    }

    /** Every `host:port` in a value: a URL, a JDBC URL, or a comma-separated list. */
    static List<String[]> hostPorts(String value) {
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_.-])([A-Za-z0-9_][A-Za-z0-9_.-]*):(\\d{1,5})(?!\\d)")
                .matcher(value == null ? "" : value);
        while (m.find()) out.add(new String[] {m.group(1), m.group(2)});
        return out;
    }

    /** The environment variable Spring's relaxed binding reads for a property name. */
    static String envForm(String name) {
        return name.replace('.', '_').replace("-", "").toUpperCase(Locale.ROOT);
    }

    /** The keys directly under each compose service. Same walk as {@link #composeHostPorts}. */
    static Map<String, Set<String>> composeServiceKeys(String yaml) {
        Map<String, Set<String>> byService = new LinkedHashMap<>();
        if (yaml == null) return byService;
        boolean inServices = false;
        String service = null;
        for (String raw : yaml.split("\r?\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String body = line.strip();

            if (indent == 0) {
                inServices = body.startsWith("services:");
                service = null;
                continue;
            }
            if (!inServices) continue;
            if (indent == 2 && body.endsWith(":")) {
                service = body.substring(0, body.length() - 1).strip();
                byService.put(service, new LinkedHashSet<>());
                continue;
            }
            int colon = body.indexOf(':');
            if (service != null && indent == 4 && colon > 0 && !body.startsWith("- ")) {
                byService.get(service).add(body.substring(0, colon).strip());
            }
        }
        return byService;
    }

    /**
     * The `environment:` of each compose service, in both shapes YAML allows: a mapping
     * (`KEY: value`) and a list (`- KEY=value`). Same walk as {@link #composeHostPorts},
     * which is why neither reaches for a YAML library the JDK does not ship.
     */
    static Map<String, Map<String, String>> composeServiceEnv(String yaml) {
        Map<String, Map<String, String>> byService = new LinkedHashMap<>();
        if (yaml == null) return byService;
        boolean inServices = false, inEnv = false;
        String service = null;
        for (String raw : yaml.split("\r?\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String body = line.strip();

            if (indent == 0) {                       // top-level key
                inServices = body.startsWith("services:");
                inEnv = false;
                service = null;
                continue;
            }
            if (!inServices) continue;
            if (indent == 2 && body.endsWith(":")) { // a service name
                service = body.substring(0, body.length() - 1).strip();
                inEnv = false;
                continue;
            }
            if (service == null) continue;
            if (indent == 4) {                       // a key inside the service
                inEnv = body.startsWith("environment:");
                continue;
            }
            if (!inEnv) continue;
            boolean listForm = body.startsWith("- ");
            String pair = listForm ? body.substring(2).strip() : body;
            int sep = listForm ? pair.indexOf('=') : pair.indexOf(':');
            if (sep <= 0) continue;
            String key = pair.substring(0, sep).strip();
            String value = unquote(pair.substring(sep + 1).strip());
            byService.computeIfAbsent(service, k -> new LinkedHashMap<>()).put(key, value);
        }
        return byService;
    }

    /** The `image:` value of each compose service. Same walk as {@link #composeHostPorts}. */
    static Map<String, String> composeImages(String yaml) {
        Map<String, String> byService = new LinkedHashMap<>();
        if (yaml == null) return byService;
        boolean inServices = false;
        String service = null;
        for (String raw : yaml.split("\r?\n", -1)) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String body = line.strip();

            if (indent == 0) {                       // top-level key
                inServices = body.startsWith("services:");
                service = null;
                continue;
            }
            if (!inServices) continue;
            if (indent == 2 && body.endsWith(":")) { // a service name
                service = body.substring(0, body.length() - 1).strip();
                continue;
            }
            if (service != null && indent == 4 && body.startsWith("image:")) {
                byService.put(service, body.substring("image:".length()).strip());
            }
        }
        return byService;
    }

    /**
     * Every `DockerImageName.parse("…")` literal under a `src/test` tree, with the file it
     * came from. Read from the source rather than from a running container: the mismatch
     * has to be visible before anyone starts anything.
     */
    static List<ImagePin> testImagePins() {
        List<ImagePin> pins = new ArrayList<>();
        Pattern re = Pattern.compile("DockerImageName\\s*\\.\\s*parse\\s*\\(\\s*\"([^\"]+)\"");
        for (Path f : projectFiles(rel -> rel.endsWith(".java") && rel.contains("src/test/"))) {
            String content = readOrNull(f);
            if (content == null) continue;
            Matcher m = re.matcher(content);
            while (m.find()) {
                String[] img = splitImage(m.group(1));
                if (img != null) pins.add(new ImagePin(img[0], img[1], relative(f)));
            }
        }
        return pins;
    }

    /** Project files whose relative path passes {@code keep}, build output and VCS metadata skipped. */
    static List<Path> projectFiles(java.util.function.Predicate<String> keep) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    // Build output and VCS metadata hold copies and no source of truth.
                    return Set.of("target", "build", ".git", "node_modules", ".idea")
                            .contains(name) ? FileVisitResult.SKIP_SUBTREE
                                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    if (keep.test(relative(f))) found.add(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;   // unreadable file is not a failure
                }
            });
        } catch (IOException ignored) {
            // An unreadable tree: nothing to compare, not a failure.
        }
        return found;
    }

    /**
     * An image reference split into repository and tag, or null when there is nothing to
     * compare. A digest (`repo@sha256:…`) and an unresolved `${VAR}` both return null: the
     * first pins something stronger than a tag, the second has no value to read here.
     * `${VAR:-17-alpine}` resolves to its default, same as {@link #hostPort} does.
     */
    static String[] splitImage(String ref) {
        String s = ref.replace("\"", "").replace("'", "").strip();
        s = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)}").matcher(s).replaceAll("$1");
        if (s.isEmpty() || s.contains("${") || s.contains("@")) return null;
        int colon = s.lastIndexOf(':');
        // A colon before the last slash is a registry port (`localhost:5000/postgres`),
        // not a tag: such a reference carries no tag at all.
        if (colon < 0 || s.indexOf('/', colon) >= 0) return new String[] {s, "latest"};
        return new String[] {s.substring(0, colon), s.substring(colon + 1)};
    }

    /** Host ports out of a `docker ps` Ports column: `0.0.0.0:4318->4318/tcp, [::]:4318->4318/tcp`. */
    static Set<String> publishedPorts(String ports) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(\\d+)(?:-(\\d+))?->").matcher(ports);
        while (m.find()) found.add(m.group(2) == null ? m.group(1) : m.group(1) + "-" + m.group(2));
        return found;
    }

    static String declaredBy(Map<String, Set<String>> declared, String port) {
        List<String> svcs = declared.entrySet().stream()
                .filter(e -> e.getValue().contains(port)).map(Map.Entry::getKey)
                .collect(Collectors.toList());
        return svcs.isEmpty() ? "a service" : "`" + String.join("`, `", svcs) + "`";
    }

    // ── export ───────────────────────────────────────────────────────────────
    //
    // Writes a target project's `.claude/` from this repository's, transformed for one
    // blueprint: rules with their `paths` derived from `packages.map`, skills and agents
    // without the subdirectories that only serve the meta-repo, and every citation to
    // `decisions/` cut, since it does not exist inside a project. The active blueprint
    // does — `blueprint_copy` ships it — so a citation to it is left as written.
    //
    // Form 7c of `claude-code-architect-designer`, motivated by axis 7 of its interview:
    // the transformation runs unattended on other people's machines — `arch-adopt` pulls
    // this repository and invokes this mode — so the same input has to produce the same
    // tree. The closest rejected form was a skill in prose, which is what steps 6.6 to 7
    // of `project-bootstrap` are today: a model re-deriving the copy on every run, at the
    // reader's expense and with no way for the CI to check it. No lifecycle event invokes
    // this mode; like `compose`, it is called by hand and by a skill.
    //
    // WHAT it copies and HOW each piece is transformed is not here: it is the `export`
    // block of .claude/schemas/extensions.json — invariant 10 — and `schema` cross-checks
    // that block against what is on disk.
    // Design: .claude/decisions/0054-deterministic-export-provenance-plugin.md

    record Blueprint(String id, Map<String, String> packages, List<String> archPaths,
                     List<String> vocabulary) {}

    static void export(String[] args) throws Exception {
        String dest = null, blueprintId = null, ref = null, commit = null;
        boolean dry = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--dry-run")) dry = true;
            else if (a.equals("--blueprint") && i + 1 < args.length) blueprintId = args[++i];
            else if (a.equals("--ref") && i + 1 < args.length) ref = args[++i];
            else if (a.equals("--commit") && i + 1 < args.length) commit = args[++i];
            else if (a.startsWith("--")) { err("❌ Unknown option: " + a); exportUsage(); System.exit(2); }
            else if (dest == null) dest = a;
            else { err("❌ Only one destination is accepted, got also: " + a); exportUsage(); System.exit(2); }
        }
        if (dest == null || blueprintId == null) { exportUsage(); System.exit(2); }
        if (commit != null && !commit.matches("[0-9a-f]{7,40}")) {
            err("❌ --commit takes a commit sha (7 to 40 lowercase hex digits), got: " + commit);
            err("   arch-adopt resolves it with `git ls-remote`; a tag name goes in --ref.");
            System.exit(2);
        }

        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        Map<String, Object> exp = sch == null ? null : asMap(sch.get("export"));
        if (exp == null) {
            err("❌ No `export` block in " + SCHEMA_FILE + " — there is nothing to copy.");
            err("   This mode reads its lists from that file; it hardcodes none.");
            System.exit(2);
        }
        // A tree `new-project` extracted from the source's tarball is no repository, so the
        // stamp would read `working-tree` and `unknown`; the fetch recorded what it resolved.
        // Read only when the source is not its own work tree, and only for a field the command
        // line left out. Design: .claude/decisions/0163-create-project-from-the-plugin.md
        if (sourceCommit() == null) {
            String[] fetched = fetchMarker(sch);
            if (fetched != null) {
                if (ref == null) ref = fetched[0];
                if (commit == null) commit = fetched[1];
            }
        }
        if (ref == null) ref = "working-tree";

        Path destRoot = Paths.get(dest).toAbsolutePath().normalize();
        if (destRoot.equals(ROOT)) {
            err("❌ The destination is this repository itself: " + destRoot);
            err("   Export writes a project's .claude/ from this one; give it another path.");
            System.exit(2);
        }
        exportPlatformGate(destRoot);
        Blueprint bp = loadBlueprint(exp, blueprintId);

        Map<String, String> out = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        exportBlueprint(exp, bp, out);
        exportRules(exp, bp, out);
        exportTree(exp, "skills", out);
        exportTree(exp, "agents", out);
        exportFiles(exp, bp, out, notes, destRoot);
        // Bytes, never text: the jar would not survive a UTF-8 round trip. Kept out of `out`,
        // so neither the stamp nor the residue scan reads it.
        Map<String, Path> binaries = new TreeMap<>();
        for (Object e : asList(exp.get("binary_copy"))) {
            String from = asStr(get(e, "from")), to = asStr(get(e, "to"));
            if (from == null || to == null) continue;
            if (!Files.isRegularFile(ROOT.resolve(from))) {
                err("❌ export.binary_copy names `" + from + "` — no such file.");
                System.exit(2);
            }
            binaries.put(to, ROOT.resolve(from));
        }
        // A file this repo renamed or dropped: export writes, it never deleted, so the old
        // copy stayed in every adopted project — a norm in two places there, invariant 2.
        // Only what exists in the target is listed. Design:
        // .claude/decisions/0082-rules-without-paths-load-at-launch.md
        List<String> retired = new ArrayList<>();
        for (String r : asStrList(exp.get("retired"))) {
            if (out.containsKey(r) || binaries.containsKey(r)) continue;   // shipped again: keep
            Path t = destRoot.resolve(r).normalize();
            if (!t.startsWith(destRoot.resolve(".claude"))) {              // never outside .claude/
                err("❌ export.retired names `" + r + "` — only paths under .claude/ may be deleted.");
                System.exit(2);
            }
            if (Files.isRegularFile(t)) retired.add(r);
        }
        String stampFile = asStr(get(sch, "source", "stamp_file")) == null
                ? ".claude/.arch-provenance.json"
                : asStr(get(sch, "source", "stamp_file"));
        out.put(stampFile, stamp(bp, ref, commit, out));

        List<String> residue = new ArrayList<>();
        List<String> exempt = asStrList(get(exp, "body_transforms", "residue_exempt"));
        for (String marker : asStrList(get(exp, "body_transforms", "residue_markers"))) {
            for (Map.Entry<String, String> e : out.entrySet()) {
                // The stamp is a list of the paths just written, one of which is now the
                // blueprint's — a path, not a citation, and this mode wrote it.
                if (e.getKey().equals(stampFile) || exempt.contains(e.getKey())) continue;
                if (e.getValue().contains(marker)) residue.add(e.getKey() + " still carries `" + marker + "`");
            }
        }

        err("ArchHook export");
        err("  Source ............ " + ROOT);
        err("  Destination ....... " + destRoot);
        err("  Blueprint ......... " + bp.id() + " (" + bp.packages().size() + " packages, "
                + bp.archPaths().size() + " architecture paths)");
        err("  Files ............. " + (out.size() + binaries.size()));
        for (String n : notes) err("  " + n);
        if (!retired.isEmpty()) {
            err("  Retired ........... " + retired.size() + (dry ? " (would delete)" : " (deleted)"));
            retired.forEach(r -> err("    - " + r));
        }
        if (dry) {
            err("");
            new TreeSet<>(Stream.concat(out.keySet().stream(), binaries.keySet().stream())
                    .collect(Collectors.toSet())).forEach(p -> err("    " + p));
            err("");
            err("⚪ --dry-run: nothing was written.");
        } else {
            for (Map.Entry<String, String> e : out.entrySet()) {
                Path target = destRoot.resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
            }
            for (Map.Entry<String, Path> e : binaries.entrySet()) {
                Path target = destRoot.resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Files.copy(e.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
            }
            for (String r : retired) Files.deleteIfExists(destRoot.resolve(r));
            for (String d : asStrList(exp.get("ensure_dirs"))) Files.createDirectories(destRoot.resolve(d));
            appendGitignore(destRoot, asStrList(exp.get("gitignore_lines")));
            err("");
            err("✅ Written. Review with `git -C " + destRoot + " diff`.");
        }
        if (!residue.isEmpty()) {
            err("");
            err("⚠️  " + residue.size() + " dead citation(s) survived the transform:");
            residue.forEach(r -> err("    " + r));
            err("   Neither decisions/ nor blueprints/ exists inside a project — fix the");
            err("   sentence at the source, or add its shape to body_transforms.");
        }
    }

    /**
     * The fetch marker `new-project` writes into a tree it extracted from the source's
     * tarball: the ref and the commit it resolved with `git ls-remote`, which the tree cannot
     * answer by itself. Returns `{ref, commit}`, either of them possibly null; null when
     * `source.fetch_marker` is unset or names no file. A file that is not JSON, or a commit
     * that is not a sha, exits 2 — a stamp written from a marker nobody can read would look
     * right and point nowhere.
     */
    static String[] fetchMarker(Map<String, Object> sch) {
        String rel = asStr(get(sch, "source", "fetch_marker"));
        if (rel == null) return null;
        String text = readOrNull(ROOT.resolve(rel));
        if (text == null) return null;
        Map<String, Object> m = asMap(Json.parse(text));
        String commit = m == null ? null : asStr(m.get("commit"));
        if (m == null || (commit != null && !commit.matches("[0-9a-f]{7,40}"))) {
            err("❌ " + rel + (m == null ? " is not a JSON object."
                    : " records commit `" + commit + "` — not a sha (7 to 40 lowercase hex digits)."));
            err("   Fetch the source again with /nerviz:new-project, or pass --ref and --commit.");
            System.exit(2);
        }
        return new String[] { asStr(m.get("ref")), commit };
    }

    static void exportUsage() {
        err("usage: java .claude/hooks/ArchHook.java export <dest> --blueprint <id>"
                + " [--ref <label>] [--commit <sha>] [--dry-run]");
        err("  <dest>        the target project's root (never this repository)");
        err("  --blueprint   id of a blueprint under .claude/blueprints/");
        err("  --ref         label recorded in the provenance stamp (default: the fetch marker's, else"
                + " working-tree)");
        err("  --commit      the source commit to stamp; without it, the source's own git HEAD, the fetch"
                + " marker's, or \"unknown\"");
    }

    // ── boundary-map ─────────────────────────────────────────────────────────

    static void boundaryMapUsage() {
        err("usage: java .claude/hooks/ArchHook.java boundary-map <dest> --blueprint <id>"
                + " --package <base.package>");
        err("  <dest>        the generated project's root; <dest>/.claude/forbidden-imports.txt is written");
        err("  --blueprint   id of a blueprint under .claude/blueprints/");
        err("  --package     the project's base package, as the Initializr was given it (com.acme.shop)");
    }

    /**
     * {@code boundary-map <dest> --blueprint <id> --package <pkg>} — writes the generated project's
     * {@code .claude/forbidden-imports.txt}, the file {@code check} and {@code doctor gate} read. A
     * {@code multi-module} blueprint declares {@code forbidden_imports} per module: one line per
     * prefix, {@code <module-path>|<prefix>}, the ArchUnit {@code ..} suffix read as a package
     * prefix. A {@code single-module} one declares only {@code dependency_rules.forbidden} by
     * layer name: each name resolves to the {@code packages.map} entries it equals or prefixes
     * (a name no entry matches is reported and skipped), and every (from, to) pair becomes
     * {@code <source_root>/<package path of from>|<package of to>.}, the {@code from} name's
     * {@code boundary_map.framework_roots} added as targets. A pair where one package contains the
     * other is dropped — it would forbid a package its own imports. A blueprint that declares
     * {@code packages.module_slot} has its map values' first segment written as
     * {@link #MODULE_SLOT} on both sides, so the line holds for every module {@code check} later
     * meets, not only the one bootstrap created (decision 0180). Exit 2, nothing written, when
     * the blueprint yields no line: a map with no rule blocks nothing.
     *
     * <p>Why a mode: {@code project-files.md} § 5 used to describe the single-module derivation in
     * prose an agent ran, so no test could hold it and the multi-module copy was a transcription.
     * Motivated by decision 0166's gap — no CI step generated any blueprint — and chosen over a
     * second derivation inside the test, which would drift from the prose (invariant 2). The
     * closest rejected form is a skill step, which cannot be proven.
     */
    static void boundaryMap(String[] args) throws Exception {
        String dest = null, id = null, pkg = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--blueprint") && i + 1 < args.length) id = args[++i];
            else if (a.equals("--package") && i + 1 < args.length) pkg = args[++i];
            else if (a.startsWith("--")) { err("❌ Unknown option: " + a); boundaryMapUsage(); System.exit(2); }
            else if (dest == null) dest = a;
            else { err("❌ Only one destination is accepted, got also: " + a); boundaryMapUsage(); System.exit(2); }
        }
        if (dest == null || id == null || pkg == null) { boundaryMapUsage(); System.exit(2); }
        if (!pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            err("❌ --package is a dotted Java package name, got: " + pkg);
            System.exit(2);
        }

        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        Map<String, Object> bm = sch == null ? null : asMap(sch.get("boundary_map"));
        if (bm == null) {
            err("❌ No `boundary_map` block in " + SCHEMA_FILE + " — this mode reads its lists from it.");
            System.exit(2);
        }
        String tpl = asStr(get(sch, "export", "blueprint", "path"));
        Path file = ROOT.resolve((tpl == null ? ".claude/blueprints/{id}/{id}.yaml" : tpl).replace("{id}", id));
        if (!Files.isRegularFile(file)) {
            err("❌ Blueprint `" + id + "` not found at " + relative(file));
            err("   Available: " + availableBlueprints());
            System.exit(2);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String layout = yamlNestedMap(lines, List.of("build")).get("layout");
        Set<String> out = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();

        if ("single-module".equals(layout)) {
            String sourceRoot = asStr(bm.get("source_root"));
            Map<String, String> map = yamlNestedMap(lines, List.of("packages", "map"));
            Map<String, Object> roots = asMap(bm.get("framework_roots"));
            String basePath = pkg.replace('.', '/');
            String slot = yamlNestedMap(lines, List.of("packages")).get("module_slot");
            for (String[] rule : yamlDependencyRules(lines)) {
                List<String> froms = resolveLayer(map, rule[0]);
                if (froms.isEmpty()) unresolved.add(rule[0]);
                List<String> tos = new ArrayList<>();
                for (String t : rule[1].split(",")) {
                    if (t.isBlank()) continue;
                    List<String> r = resolveLayer(map, t.strip());
                    if (r.isEmpty()) unresolved.add(t.strip()); else tos.addAll(r);
                }
                for (String from : froms) {
                    String left = sourceRoot + "/" + basePath + "/" + slotted(from, slot).replace('.', '/');
                    if (roots != null) {
                        for (String root : asStrList(roots.get(rule[0]))) out.add(left + "|" + root);
                    }
                    for (String to : tos) {
                        if (to.equals(from) || to.startsWith(from + ".") || from.startsWith(to + ".")) continue;
                        out.add(left + "|" + pkg + "." + slotted(to, slot) + ".");
                    }
                }
            }
        } else {
            for (String[] m : yamlModules(lines)) {
                for (String prefix : m[1].isEmpty() ? new String[0] : m[1].split("\n")) {
                    out.add(m[0] + "|" + (prefix.endsWith("..") ? prefix.substring(0, prefix.length() - 1) : prefix));
                }
            }
        }
        for (String u : new TreeSet<>(unresolved)) {
            err("⚠️  boundary-map: `" + u + "` matches no entry of packages.map in " + id + " — its rules are skipped.");
        }
        if (out.isEmpty()) {
            err("❌ boundary-map: " + id + " yields no rule — nothing written. A boundary map with no line blocks nothing.");
            System.exit(2);
        }
        Path target = Paths.get(dest).toAbsolutePath().normalize().resolve(".claude/forbidden-imports.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "# Generated by /init-project — blueprint: " + id + " — do not edit by hand\n"
                + String.join("\n", out) + "\n", StandardCharsets.UTF_8);
        System.out.println("✅ boundary-map: " + out.size() + " rule(s) for " + id + " in " + target);
    }

    /**
     * {@code value} with its first segment written as {@link #MODULE_SLOT} when that segment is
     * the blueprint's {@code packages.module_slot} — the module this blueprint creates at
     * bootstrap, whose shape every later module repeats.
     */
    static String slotted(String value, String slot) {
        if (slot == null || slot.isBlank()) return value;
        return value.equals(slot) ? MODULE_SLOT
                : value.startsWith(slot + ".") ? MODULE_SLOT + value.substring(slot.length()) : value;
    }

    /** Every packages.map value whose key equals {@code name} or sits under {@code name + "."}. */
    static List<String> resolveLayer(Map<String, String> map, String name) {
        List<String> out = new ArrayList<>();
        map.forEach((k, v) -> { if (k.equals(name) || k.startsWith(name + ".")) out.add(v); });
        return out;
    }

    /** {@code dependency_rules.forbidden}: one {@code {from, "a, b, c"}} per entry. */
    static List<String[]> yamlDependencyRules(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        int i = indexOfTopKey(lines, "dependency_rules");
        if (i < 0) return out;
        String from = null;
        for (int j = i + 1; j < lines.size(); j++) {
            String l = lines.get(j);
            if (l.isBlank() || l.trim().startsWith("#")) continue;
            if (indentOf(l) == 0) break;
            Matcher f = Pattern.compile("^\\s*-\\s*from:\\s*(\\S+)").matcher(l);
            if (f.find()) { from = unquote(f.group(1)); continue; }
            Matcher t = Pattern.compile("^\\s*to:\\s*\\[([^\\]]*)\\]").matcher(l);
            if (from != null && t.find()) { out.add(new String[] { from, t.group(1) }); from = null; }
        }
        return out;
    }

    /** {@code modules[]}: one {@code {path, forbidden_imports joined by \n}} per module. */
    static List<String[]> yamlModules(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        int i = indexOfTopKey(lines, "modules");
        if (i < 0) return out;
        String path = null;
        List<String> forbidden = new ArrayList<>();
        boolean inForbidden = false;
        for (int j = i + 1; j <= lines.size(); j++) {
            String l = j < lines.size() ? lines.get(j) : "";
            boolean end = j == lines.size() || (!l.isBlank() && !l.trim().startsWith("#") && indentOf(l) == 0);
            boolean next = l.matches("^\\s*-\\s*id:.*");
            if ((end || next) && path != null) {
                out.add(new String[] { path, String.join("\n", forbidden) });
                path = null;
                forbidden = new ArrayList<>();
                inForbidden = false;
            }
            if (end) break;
            if (l.isBlank() || l.trim().startsWith("#")) continue;
            Matcher p = Pattern.compile("^\\s+path:\\s*(.+?)\\s*$").matcher(l);
            if (p.matches()) { path = unquote(p.group(1)); continue; }
            if (l.matches("^\\s+forbidden_imports:.*")) { inForbidden = true; continue; }
            Matcher item = Pattern.compile("^\\s+-\\s*(.+?)\\s*$").matcher(l);
            if (inForbidden && item.matches()) { forbidden.add(unquote(item.group(1))); continue; }
            if (l.matches("^\\s{4}[A-Za-z_]+:.*")) inForbidden = false;
        }
        return out;
    }

    /** Reads `packages.map`, `architecture_paths` and the naming-convention comment block. */
    static Blueprint loadBlueprint(Map<String, Object> exp, String id) throws IOException {
        String tpl = asStr(get(exp, "blueprint", "path"));
        if (tpl == null) tpl = ".claude/blueprints/{id}/{id}.yaml";
        Path file = ROOT.resolve(tpl.replace("{id}", id));
        if (!Files.isRegularFile(file)) {
            err("❌ Blueprint `" + id + "` not found at " + relative(file));
            err("   Available: " + availableBlueprints());
            System.exit(2);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> mapPath = asStrList(get(exp, "blueprint", "packages_map_path"));
        Map<String, String> pkgs = yamlNestedMap(lines,
                mapPath.isEmpty() ? List.of("packages", "map") : mapPath);
        String apKey = asStr(get(exp, "blueprint", "architecture_paths_key"));
        List<String> arch = yamlList(lines, apKey == null ? "architecture_paths" : apKey);
        String vocKey = asStr(get(exp, "blueprint", "vocabulary_anchor_key"));
        List<String> voc = yamlCommentBlockAbove(lines, vocKey == null ? "dependency_rules" : vocKey);

        if (pkgs.isEmpty() || arch.isEmpty()) {
            err("❌ Blueprint `" + id + "` has no packages.map or no architecture_paths.");
            err("   Both are required by .claude/blueprints/_schema.md; without them every");
            err("   rule with a territory would be copied with a dead glob.");
            System.exit(2);
        }
        return new Blueprint(id, pkgs, arch, voc);
    }

    static String availableBlueprints() throws IOException {
        Path dir = ROOT.resolve(".claude/blueprints");
        if (!Files.isDirectory(dir)) return "(none)";
        try (Stream<Path> walk = Files.list(dir)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve(p.getFileName() + ".yaml")))
                    .map(p -> p.getFileName().toString()).sorted()
                    .collect(Collectors.joining(", "));
        }
    }

    // ── export · the three sets ──────────────────────────────────────────────

    static void exportRules(Map<String, Object> exp, Blueprint bp, Map<String, String> out)
            throws IOException {
        Map<String, Object> block = asMap(exp.get("rules"));
        if (block == null) return;
        String from = asStr(block.get("from")), to = asStr(block.get("to"));
        List<String> exclude = asStrList(block.get("exclude"));
        Path dir = ROOT.resolve(from);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.list(dir)) {
            for (Path f : walk.filter(p -> p.toString().endsWith(".md")).sorted()
                    .collect(Collectors.toList())) {
                String name = f.getFileName().toString();
                if (exclude.contains(name)) continue;
                // LF before any frontmatter match: a CRLF checkout (core.autocrlf on Windows)
                // made derivePaths find no `---\n` and ship the master globs, in silence.
                String body = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
                body = derivePaths(exp, bp, name, body);
                body = transform(exp, bp, to + "/" + name, body);
                out.put(to + "/" + name, body);
            }
        }
    }

    /**
     * Writes the active blueprint into the project. The catalog stays here — a project
     * chooses an architecture once — but the yaml it chose has to travel: the stamp
     * records the id, and an update resolves that id against the source, which for a
     * blueprint written during adoption never had it. Its `references/` citations are
     * dropped on the way: they point at files that do not travel.
     */
    static void exportBlueprint(Map<String, Object> exp, Blueprint bp, Map<String, String> out)
            throws IOException {
        String tpl = asStr(exp.get("blueprint_copy"));
        if (tpl == null || bp == null) return;
        String srcTpl = asStr(get(exp, "blueprint", "path"));
        Path src = ROOT.resolve((srcTpl == null ? ".claude/blueprints/{id}/{id}.yaml" : srcTpl)
                .replace("{id}", bp.id()));
        if (!Files.isRegularFile(src)) return;
        String body = Files.readString(src, StandardCharsets.UTF_8)
                .replaceAll("(?m)^#[ \\t]*@?\\.claude/blueprints/[^\\n]*\\n", "");
        out.put(tpl.replace("{id}", bp.id()), body);
    }

    /** Copies `keep` entries of each included skill, or the single file of each agent. */
    static void exportTree(Map<String, Object> exp, String key, Map<String, String> out)
            throws IOException {
        Map<String, Object> block = asMap(exp.get(key));
        if (block == null) return;
        String from = asStr(block.get("from")), to = asStr(block.get("to"));
        List<String> keep = asStrList(block.get("keep"));
        for (String name : asStrList(block.get("include"))) {
            Path base = ROOT.resolve(from).resolve(name);
            if (keep.isEmpty()) {                                  // agents: one flat file
                Path f = ROOT.resolve(from).resolve(name + ".md");
                if (Files.isRegularFile(f)) {
                    String rel = to + "/" + name + ".md";
                    out.put(rel, transform(exp, null, rel, Files.readString(f, StandardCharsets.UTF_8)));
                }
                continue;
            }
            for (String entry : keep) {
                Path p = base.resolve(entry);
                if (Files.isRegularFile(p)) {
                    String rel = to + "/" + name + "/" + entry;
                    out.put(rel, transform(exp, null, rel, Files.readString(p, StandardCharsets.UTF_8)));
                } else if (Files.isDirectory(p)) {
                    try (Stream<Path> walk = Files.walk(p)) {
                        for (Path f : walk.filter(Files::isRegularFile).sorted()
                                .collect(Collectors.toList())) {
                            String rel = to + "/" + name + "/" + base.relativize(f).toString()
                                    .replace(File.separatorChar, '/');
                            out.put(rel, transform(exp, null, rel,
                                    Files.readString(f, StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
        }
    }

    static void exportFiles(Map<String, Object> exp, Blueprint bp, Map<String, String> out,
                            List<String> notes, Path destRoot) throws IOException {
        for (String group : List.of("copy", "overwrite", "optional_copy")) {
            for (Object e : asList(exp.get(group))) {
                String from = asStr(get(e, "from")), to = asStr(get(e, "to"));
                if (from == null || to == null) continue;
                Path p = ROOT.resolve(from);
                if (!Files.isRegularFile(p)) {
                    if (!group.equals("optional_copy")) {
                        err("❌ export." + group + " names `" + from + "` — no such file.");
                        System.exit(2);
                    }
                    continue;
                }
                String body = Files.readString(p, StandardCharsets.UTF_8);
                String written = Boolean.TRUE.equals(get(e, "verbatim")) ? body : transform(exp, bp, from, body);
                out.put(to, written);
                // Only a file that exists and changes: the note sends the person to `git diff`, and
                // on a fresh tree or an unchanged file there is nothing there to read (issue #123).
                String before = group.equals("overwrite") ? readOrNull(destRoot.resolve(to)) : null;
                if (before != null && !before.replace("\r\n", "\n").equals(written.replace("\r\n", "\n"))) {
                    notes.add("Overwrites ........ " + to + " (its previous content is in the diff)");
                }
            }
        }
    }

    // ── export · transforms ──────────────────────────────────────────────────

    /**
     * Cuts what has no counterpart inside a project and applies the rewrites the manifest
     * names for this file. A sentence whose only job is to cite a decision record goes
     * whole; a citation inside a sentence that says something else loses only its clause.
     */
    static String transform(Map<String, Object> exp, Blueprint bp, String sourceRel, String body) {
        Map<String, Object> bt = asMap(exp.get("body_transforms"));
        if (bt == null) return body;

        String cite = asStr(bt.get("citation_pattern"));
        if (cite != null) {
            // Shapes where the citation IS the content: a parenthetical, or a list item
            // whose whole line describes the record. Cutting only the clause there would
            // leave `()` or a bullet with a dangling dash.
            for (String shape : asStrList(bt.get("cut_shapes"))) {
                body = body.replaceAll(shape.replace("{cite}", cite), "");
            }
            // A lead-in is matched across line breaks: the sentence it opens is wrapped
            // at 90 columns like every other, and "Full\nrecord in `@…`." is the common
            // case, not the exception.
            // A lead-in may open a list of records — "Design: X, Y and Z" — and the
            // sentence says the same thing about all of them, so the whole list goes with
            // it. Without the tail, the first citation is cut and the rest survive as an
            // orphan ", and Z" the next step then turns into prose.
            for (String lead : asStrList(bt.get("sentence_lead_ins"))) {
                String words = Arrays.stream(lead.trim().split("\\s+")).map(Pattern::quote)
                        .collect(Collectors.joining("\\s+"));
                body = body.replaceAll("(?s)" + words + "[^.]{0,200}?" + cite
                        + "(?:(?:,|,? and|,? e)[ \n]+" + cite + ")*\\.?[ ]?", "");
            }
            // What survives both shapes above is a citation inside a sentence that says
            // something else. Deleting it there leaves the sentence mangled — `- **** —`,
            // `Inherits D15 —.` — which no residue marker looking for a path can see, so
            // six of them shipped (lessons-learned-012 § 3). The path is replaced by prose
            // instead: the reader inside a project cannot follow it either way, but the
            // sentence still says what it was written to say.
            String replacement = asStr(bt.get("citation_replacement"));
            body = body.replaceAll(cite, Matcher.quoteReplacement(
                    replacement == null ? "" : replacement));
            // Three records cited in a row become the same phrase three times. The list
            // collapses to one: "Design: X, Y and Z" said the same thing about all three.
            if (replacement != null && !replacement.isEmpty()) {
                String r = Pattern.quote(replacement);
                body = body.replaceAll("(?s)" + r + "(?:(?:,|,? and|,? e)[ \n]+" + r + ")+",
                        Matcher.quoteReplacement(replacement));
            }
        }
        for (Object r : asList(bt.get("replace"))) {
            String find = asStr(get(r, "find")), with = asStr(get(r, "with"));
            if (find != null && with != null) body = body.replace(find, with);
        }
        for (Object r : asList(bt.get("rewrite"))) {
            if (!sourceRel.equals(asStr(get(r, "file")))) continue;
            body = switch (String.valueOf(asStr(get(r, "op")))) {
                case "blueprint_vocabulary" -> replaceBetween(body, asStr(get(r, "anchor_start")),
                        asStr(get(r, "anchor_end")), vocabularyMarkdown(bp));
                case "replace_paragraph" -> replaceParagraph(body, asStr(get(r, "anchor")),
                        String.valueOf(asStr(get(r, "replacement")))
                                .replace("{blueprint}", bp == null ? "" : bp.id()));
                case "drop_block" -> dropJsonBlock(body, asStr(get(r, "block")));
                default -> body;
            };
        }
        return body.replaceAll("(?m)[ \t]+$", "").replaceAll("\n{3,}", "\n\n");
    }

    /** Rewrites a rule's `paths:` from the blueprint, never from the original file. */
    static String derivePaths(Map<String, Object> exp, Blueprint bp, String rule, String body) {
        Map<String, Object> spec = asMap(get(exp, "derived_paths", rule));
        if (spec == null) return body;

        List<String> globs = new ArrayList<>();
        if (asStr(spec.get("from_blueprint")) != null) {
            globs.addAll(bp.archPaths());
        } else {
            String shape = asStr(spec.get("glob"));
            for (String suffix : asStrList(spec.get("suffix"))) {
                for (Map.Entry<String, String> e : bp.packages().entrySet()) {
                    if (e.getKey().endsWith(suffix)) globs.add(shape.replace("{}", e.getValue().replace('.', '/')));
                }
            }
            for (String key : asStrList(spec.get("key"))) {
                String v = bp.packages().get(key);
                if (v != null) { globs.add(shape.replace("{}", v.replace('.', '/'))); break; }
            }
            // An architecture that doesn't name the layer as a package — vertical-slice
            // puts REST and persistence inside each slice — still has a territory, and it
            // is one of its own `architecture_paths`. Selected by token, never by union
            // of every path: a rule that loads on domain edits is noise, not coverage.
            if (globs.isEmpty()) {
                for (String token : asStrList(spec.get("fallback_architecture_paths_containing"))) {
                    for (String p : bp.archPaths()) if (p.contains(token)) globs.add(p);
                }
            }
            globs.addAll(asStrList(spec.get("extra")));
        }
        if (globs.isEmpty()) {
            if (!Boolean.TRUE.equals(spec.get("optional"))) {
                err("❌ Rule `" + rule + "` derives no glob from blueprint `" + bp.id() + "`.");
                err("   A rule copied with a dead glob never enters context, in silence.");
                err("   Mark it `optional` in export.derived_paths, or fix packages.map.");
                System.exit(2);
            }
            // Optional: keeps the master's example globs, which name packages this
            // architecture does not have — inert. Stripping them would make the rule load
            // at launch in every session: a rule without `paths` is not "cited only" (0082).
            return body;
        }
        StringBuilder b = new StringBuilder("paths:\n");
        for (String g : globs) b.append("  - \"").append(g).append("\"\n");
        return replacePathsBlock(body, b.toString());
    }

    /** Replaces (or inserts) the `paths:` block inside the leading frontmatter. */
    static String replacePathsBlock(String body, String block) {
        String stripped = stripPathsBlock(body);
        int open = stripped.indexOf("---\n");
        if (open != 0) return stripped;                        // no frontmatter: nothing to do
        return "---\n" + block + stripped.substring(4);
    }

    static String stripPathsBlock(String body) {
        return body.replaceAll("(?m)^paths:\\n(?:[ \\t]+-[^\\n]*\\n)+", "");
    }

    static String replaceBetween(String body, String start, String end, String replacement) {
        if (start == null || end == null) return body;
        int a = body.indexOf(start);
        if (a < 0) return body;
        int b = body.indexOf(end, a);
        if (b < 0) return body;
        return body.substring(0, a) + replacement + body.substring(b + end.length());
    }

    static String replaceParagraph(String body, String anchor, String replacement) {
        if (anchor == null) return body;
        int a = body.indexOf(anchor);
        if (a < 0) return body;
        int start = body.lastIndexOf("\n\n", a);
        start = start < 0 ? 0 : start + 2;
        int end = body.indexOf("\n\n", a);
        if (end < 0) end = body.length();
        return body.substring(0, start) + replacement + body.substring(end);
    }

    /** Removes a top-level block of a two-space-indented JSON object, braces balanced. */
    static String dropJsonBlock(String body, String key) {
        if (key == null) return body;
        String needle = "\n  \"" + key + "\": {";
        int a = body.indexOf(needle);
        if (a < 0) return body;
        int depth = 0, i = a + needle.length() - 1;
        for (; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) break;
        }
        int end = i + 1;
        if (end < body.length() && body.charAt(end) == ',') end++;
        while (end < body.length() && (body.charAt(end) == '\n' || body.charAt(end) == ' ')) end++;
        return body.substring(0, a + 1) + body.substring(end);
    }

    /** The blueprint's naming-convention comment block, as a markdown list. */
    static String vocabularyMarkdown(Blueprint bp) {
        if (bp == null || bp.vocabulary().isEmpty()) return "";
        List<String> prose = new ArrayList<>(), entries = new ArrayList<>();
        int base = Integer.MAX_VALUE;
        List<String> body = new ArrayList<>();
        for (String raw : bp.vocabulary()) {
            String l = raw.replaceFirst("^#\\s?", "");
            if (l.trim().toLowerCase(Locale.ROOT).startsWith("reference")) break;
            body.add(l);
            int ind = l.length() - l.stripLeading().length();
            if (!l.isBlank() && ind > 0) base = Math.min(base, ind);
        }
        for (String l : body) {
            int ind = l.length() - l.stripLeading().length();
            if (l.isBlank()) continue;
            if (ind == 0) prose.add(l.trim());
            else if (ind == base) entries.add(l.trim());
            else if (!entries.isEmpty()) entries.set(entries.size() - 1,
                    entries.get(entries.size() - 1) + " " + l.trim());
        }
        StringBuilder b = new StringBuilder();
        if (!prose.isEmpty()) b.append(String.join(" ", prose)).append("\n\n");
        for (String e : entries) {
            // The comment aligns its columns with runs of spaces; markdown doesn't, and
            // the role before the first colon is what a reader scans for.
            String flat = e.replaceAll(" {2,}", " ");
            int colon = flat.indexOf(':');
            b.append("- ").append(colon > 0
                    ? "**" + flat.substring(0, colon) + ":**" + flat.substring(colon + 1)
                    : flat).append('\n');
        }
        return b.toString().stripTrailing();
    }

    // ── export · the provenance stamp and the YAML it reads ──────────────────

    /**
     * The provenance stamp. `files` is what makes it answer a question offline: an
     * update overwrites, so before running one the person needs to know which of these
     * files they have edited since the last export — `doctor` recomputes the digests and
     * names them. Everything else in the stamp answers "from where, and how old".
     */
    static String stamp(Blueprint bp, String ref, String given, Map<String, String> files)
            throws Exception {
        String commit = given != null ? given : sourceCommit();
        String origin = firstLine(run("git", "-C", ROOT.toString(), "config", "--get", "remote.origin.url"));
        String manifest = readOrNull(ROOT.resolve(SCHEMA_FILE));
        StringBuilder b = new StringBuilder("{\n"
                + "  \"source\": \"" + jsonEscape(origin == null ? ROOT.toString() : origin) + "\",\n"
                + "  \"ref\": \"" + jsonEscape(ref) + "\",\n"
                + "  \"commit\": \"" + jsonEscape(commit == null ? "unknown" : commit) + "\",\n"
                + "  \"exported_at\": \"" + Instant.now() + "\",\n"
                + "  \"blueprint\": \"" + jsonEscape(bp.id()) + "\",\n"
                + "  \"manifest_digest\": \"" + (manifest == null ? "unknown" : sha256(manifest)) + "\",\n"
                + "  \"files\": {\n");
        int i = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            b.append("    \"").append(jsonEscape(e.getKey())).append("\": \"")
             .append(sha256(e.getValue())).append(++i < files.size() ? "\",\n" : "\"\n");
        }
        return b.append("  }\n}\n").toString();
    }

    /**
     * The source's own HEAD, only when the source root is the top of its own work tree.
     * `arch-adopt` exports from an extracted tarball, which is no repository: `rev-parse` there
     * fails ("unknown"), or — with the tarball extracted inside some other work tree — answers
     * with that repository's HEAD, a wrong commit stamped as if it were right (issue #123).
     * `arch-adopt` passes `--commit` instead; this is what a local `--source` checkout uses.
     */
    static String sourceCommit() throws Exception {
        String top = firstLine(run("git", "-C", ROOT.toString(), "rev-parse", "--show-toplevel"));
        if (top == null) return null;
        try {
            if (!Paths.get(top).toRealPath().equals(ROOT.toRealPath())) return null;
        } catch (IOException e) {
            return null;
        }
        return firstLine(run("git", "-C", ROOT.toString(), "rev-parse", "HEAD"));
    }

    static String firstLine(Proc p) {
        return p.exit() == 0 && !p.out().isEmpty() ? p.out().get(0).trim() : null;
    }

    static void appendGitignore(Path destRoot, List<String> lines) throws IOException {
        if (lines.isEmpty()) return;
        Path gi = destRoot.resolve(".gitignore");
        String cur = Files.isRegularFile(gi) ? Files.readString(gi, StandardCharsets.UTF_8) : "";
        StringBuilder add = new StringBuilder();
        for (String l : lines) if (!cur.contains(l)) add.append(l).append('\n');
        if (add.length() == 0) return;
        Files.writeString(gi, cur.isEmpty() || cur.endsWith("\n") ? cur + add : cur + "\n" + add,
                StandardCharsets.UTF_8);
    }

    static int indentOf(String l) { return l.length() - l.stripLeading().length(); }

    static int indexOfTopKey(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).matches("^" + Pattern.quote(key) + ":.*")) return i;
        return -1;
    }

    static String unquote(String v) {
        v = v.trim();
        if (v.startsWith("\"") && v.indexOf('"', 1) > 0) return v.substring(1, v.indexOf('"', 1));
        if (v.startsWith("'") && v.indexOf('\'', 1) > 0) return v.substring(1, v.indexOf('\'', 1));
        int hash = v.indexOf(" #");
        return (hash > 0 ? v.substring(0, hash) : v).trim();
    }

    /** The list of scalars under a top-level key. */
    static List<String> yamlList(List<String> lines, String key) {
        List<String> out = new ArrayList<>();
        int i = indexOfTopKey(lines, key);
        if (i < 0) return out;
        for (int j = i + 1; j < lines.size(); j++) {
            String l = lines.get(j);
            if (l.trim().startsWith("#")) continue;
            if (l.isBlank()) { if (!out.isEmpty()) break; continue; }
            Matcher m = Pattern.compile("^\\s+-\\s*(.+?)\\s*$").matcher(l);
            if (!m.matches()) break;
            out.add(unquote(m.group(1)));
        }
        return out;
    }

    /** The scalar entries of a nested map, e.g. `packages` → `map`. */
    static Map<String, String> yamlNestedMap(List<String> lines, List<String> path) {
        int i = indexOfTopKey(lines, path.get(0));
        if (i < 0) return Map.of();
        int indent = 0;
        i++;
        for (int d = 1; d < path.size(); d++) {
            int found = -1;
            for (int j = i; j < lines.size(); j++) {
                String l = lines.get(j);
                if (l.isBlank() || l.trim().startsWith("#")) continue;
                if (indentOf(l) <= indent) break;
                if (l.trim().startsWith(path.get(d) + ":")) { found = j; indent = indentOf(l); break; }
            }
            if (found < 0) return Map.of();
            i = found + 1;
        }
        Map<String, String> map = new LinkedHashMap<>();
        Pattern entry = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*:\\s*(.+?)\\s*$");
        for (int j = i; j < lines.size(); j++) {
            String l = lines.get(j);
            if (l.isBlank() || l.trim().startsWith("#")) continue;
            if (indentOf(l) <= indent) break;
            Matcher m = entry.matcher(l);
            if (m.matches()) map.put(m.group(1), unquote(m.group(2)));
        }
        return map;
    }

    /** The contiguous comment block sitting immediately above a top-level key. */
    static List<String> yamlCommentBlockAbove(List<String> lines, String key) {
        int i = indexOfTopKey(lines, key);
        if (i < 0) return List.of();
        int j = i - 1;
        while (j >= 0 && lines.get(j).isBlank()) j--;
        List<String> block = new ArrayList<>();
        while (j >= 0 && lines.get(j).startsWith("#")) block.add(lines.get(j--));
        Collections.reverse(block);
        return block;
    }

    // ── schema ───────────────────────────────────────────────────────────────
    //
    // Guards the frontmatter fields of Claude Code's extension files.
    // The runtime silently ignores an unknown field and `claude plugin validate`
    // lets it through: without this mode, a wrong field looks like behavior and is
    // just decoration. The field list does NOT live here — it lives in
    // .claude/schemas/extensions.json, so that adding a field doesn't require
    // touching Java. Rationale and design:
    // .claude/decisions/0001-schema-frontmatter-extensions.md

    static final String SCHEMA_FILE = ".claude/schemas/extensions.json";

    static void schema(String stdin) throws Exception {
        // If the Stop hook already blocked before, don't block again: avoids cycles.
        if (Pattern.compile("\"stop_hook_active\"\\s*:\\s*true").matcher(stdin).find()) return;

        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        if (sch == null) {
            err("⚠️  " + SCHEMA_FILE + " missing or invalid.");
            err("   Frontmatter validation OFF — nothing was checked.");
            return;
        }

        Map<String, Object> in = asMap(get(Json.parse(stdin), "tool_input"));
        String file = in != null ? asStr(in.get("file_path")) : filePath(stdin);

        List<String> errors = new ArrayList<>();
        if (file == null) {
            sweep(sch, errors);                       // Stop, or manual invocation
        } else {
            String rel = relative(Paths.get(file));
            String modsRoot = asStr(get(sch, "mods", "root"));
            if (modsRoot != null && rel.startsWith(modsRoot + "/")) {
                checkMods(sch, errors);               // a mod is judged whole, from disk
            } else {
                // Write brings the whole file in tool_input.content and is validated before
                // writing. Edit brings old_string/new_string — there only disk works.
                String content = in != null ? asStr(in.get("content")) : null;
                if (content == null) content = readOrNull(Paths.get(file));
                if (content == null) return;          // file deleted or unreadable
                checkOne(sch, rel, content, errors);
            }
        }

        if (file == null) {
            checkSkillClasses(sch, errors);
            checkAgentClasses(sch, errors);
            checkSubagentContext(sch, errors);
            checkExportManifest(sch, errors);
            checkSourceBlock(sch, errors);
            checkMigrations(sch, errors);
            checkMods(sch, errors);
            String jarProblem = hookJarProblem(asMap(sch.get("hook_build")));
            if (jarProblem != null) errors.add("  " + jarProblem);
        }

        if (!errors.isEmpty()) {
            err("❌ Invalid extension file — " + errors.size()
                    + (errors.size() == 1 ? " problem" : " problems"));
            errors.forEach(ArchHook::err);
            err("");
            err("Valid fields by type: " + SCHEMA_FILE);
            err("What each field is for: .claude/skills/claude-code-architect-designer"
                    + "/references/frontmatter-fields.md");
            System.exit(2);
        }
    }

    // ── mods ─────────────────────────────────────────────────────────────────

    /**
     * Every mod under `mods.root` against the `mods` block: the local marketplace lists each
     * mod directory by relative path and nothing else, `.claude/settings.json` registers that
     * marketplace as a `directory` source and enables each mod, and each mod has a manifest
     * named after its directory, one hooks module, at least one test, and a module that hooks
     * only known events, ends every `gating_events` hook in `.catch(...)`, calls none of
     * `forbidden_calls`, and starts no program outside `process_allow`. Each
     * `mods.deny_markers` entry must still appear in this file.
     *
     * <p>Extension of the existing `schema` mode (Form 7c rules, invariant 10 — every list is
     * read from the block), motivated by axis 17 of `claude-code-architect-designer`: a mod
     * that breaks any of these loads half-way or not at all, and the runtime says so only in a
     * debug log nobody reads; `claude plugin validate` catches part of it, but only on a
     * machine with the CLI. The closest rejected form was CI alone — it never runs inside a
     * session, where a broken mod is noticed. Silent where `root` does not exist, which is
     * every generated project. Design: `.claude/decisions/0131-mods-in-architect-designer.md`.
     */
    static void checkMods(Map<String, Object> sch, List<String> errors) {
        Map<String, Object> cfg = asMap(sch.get("mods"));
        if (cfg == null) return;
        String root = orEmpty(asStr(cfg.get("root")));
        Path dir = ROOT.resolve(root);
        if (root.isEmpty() || !Files.isDirectory(dir)) return;
        String block = SCHEMA_FILE + " › mods";

        List<String> onDisk = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".")).sorted().forEach(onDisk::add);
        } catch (IOException e) {
            errors.add("  " + root + " — unreadable: " + e.getMessage());
            return;
        }

        String market = orEmpty(asStr(cfg.get("marketplace")));
        String marketRel = root + "/.claude-plugin/marketplace.json";
        Map<String, Object> mkt = asMap(Json.parse(readOrNull(ROOT.resolve(marketRel))));
        if (mkt == null) {
            errors.add("  " + marketRel + " — missing or invalid JSON. It is what registers the"
                    + " mods here as the `" + market + "` marketplace (" + block + ")");
        } else {
            if (!market.equals(asStr(mkt.get("name")))) {
                errors.add("  " + marketRel + " — `name` must be `" + market + "`, the id"
                        + " .claude/settings.json enables each mod under (" + block + ".marketplace)");
            }
            Set<String> listed = new LinkedHashSet<>();
            for (Object o : asList(mkt.get("plugins"))) {
                Map<String, Object> pl = asMap(o);
                String name = pl == null ? null : asStr(pl.get("name"));
                if (name == null) continue;
                listed.add(name);
                if (!("./" + name).equals(asStr(pl.get("source")))) {
                    errors.add("  " + marketRel + " — `" + name + "` must have `source: \"./"
                            + name + "\"`: a relative path is what loads the mod in place");
                }
                if (!onDisk.contains(name)) {
                    errors.add("  " + marketRel + " — lists `" + name + "`, which is no directory"
                            + " under " + root);
                }
            }
            for (String name : onDisk) {
                if (!listed.contains(name)) {
                    errors.add("  " + root + "/" + name + " — not listed in " + marketRel
                            + "; it never loads");
                }
            }
        }

        Map<String, Object> settings = asMap(Json.parse(readOrNull(ROOT.resolve(".claude/settings.json"))));
        Map<String, Object> src = asMap(get(settings, "extraKnownMarketplaces", market, "source"));
        String srcPath = src == null ? null : orEmpty(asStr(src.get("path"))).replaceFirst("^\\./", "");
        if (src == null || !"directory".equals(asStr(src.get("source"))) || !root.equals(srcPath)) {
            errors.add("  .claude/settings.json — `extraKnownMarketplaces." + market + "` must be"
                    + " `{ \"source\": { \"source\": \"directory\", \"path\": \"./" + root + "\" } }`;"
                    + " without it no mod here loads");
        }
        for (String name : onDisk) {
            if (!Boolean.TRUE.equals(get(settings, "enabledPlugins", name + "@" + market))) {
                errors.add("  .claude/settings.json — `enabledPlugins` lacks `\"" + name + "@"
                        + market + "\": true`; the mod is registered but off");
            }
            checkMod(sch, cfg, root + "/" + name, name, errors);
        }

        String hookSource = Optional.ofNullable(asStr(get(sch, "hook_build", "source")))
                .orElse(".claude/hooks/ArchHook.java");
        String source = orEmpty(readOrNull(ROOT.resolve(hookSource)));
        for (String marker : asStrList(cfg.get("deny_markers"))) {
            if (!source.contains(marker)) {
                errors.add("  " + block + ".deny_markers — `" + marker + "` no longer appears in "
                        + hookSource + "; the cockpit would stop recognizing that refusal."
                        + " Update the marker to the message's new wording");
            }
        }
    }

    /** One mod directory: manifest, hooks module, tests, and the module's own source. */
    static void checkMod(Map<String, Object> sch, Map<String, Object> cfg, String rel, String name,
                         List<String> errors) {
        Path dir = ROOT.resolve(rel);
        Map<String, Object> manifest = asMap(Json.parse(readOrNull(dir.resolve(".claude-plugin/plugin.json"))));
        if (manifest == null) {
            errors.add("  " + rel + "/.claude-plugin/plugin.json — missing or invalid JSON");
        } else if (!name.equals(asStr(manifest.get("name")))) {
            errors.add("  " + rel + "/.claude-plugin/plugin.json — `name` must be `" + name
                    + "`, the directory's name");
        }
        if (name.startsWith("claude-")) {
            errors.add("  " + rel + " — a `claude-` name reads as Anthropic's;"
                    + " `claude plugin validate` refuses it");
        }

        Map<String, Object> hooks = asMap(Json.parse(readOrNull(dir.resolve("hooks/hooks.json"))));
        List<String> modules = hooks == null ? List.of() : asStrList(hooks.get("modules"));
        if (modules.size() != 1) {
            errors.add("  " + rel + "/hooks/hooks.json — needs `\"modules\": [\"./register.ts\"]`,"
                    + " exactly one entry; without it the plugin is no mod");
            return;
        }
        String module = rel + "/hooks/" + modules.get(0).replaceFirst("^\\./", "");
        String ext = module.contains(".") ? module.substring(module.lastIndexOf('.')) : "";
        if (!asStrList(cfg.get("module_extensions")).contains(ext)) {
            errors.add("  " + module + " — `" + ext + "` is not one of "
                    + asStrList(cfg.get("module_extensions")) + " (" + SCHEMA_FILE
                    + " › mods.module_extensions)");
        }
        String code = readOrNull(ROOT.resolve(module));
        if (code == null) {
            errors.add("  " + rel + "/hooks/hooks.json — names " + module + ", which does not exist");
            return;
        }

        boolean tested = false;
        Path tests = dir.resolve(orEmpty(asStr(cfg.get("tests_dir"))));
        if (Files.isDirectory(tests)) {
            try (Stream<Path> s = Files.list(tests)) {
                tested = s.anyMatch(p -> p.getFileName().toString().matches(".*\\.test\\.tsx?$"));
            } catch (IOException ignored) { }
        }
        if (!tested) {
            errors.add("  " + rel + " — no *.test.ts under " + asStr(cfg.get("tests_dir"))
                    + "/; `claude plugin test` would prove nothing");
        }

        checkModSource(sch, cfg, module, stripJsComments(code), errors);
    }

    /** The hooks module's source, comments blanked: events, `.catch`, calls, programs. */
    static void checkModSource(Map<String, Object> sch, Map<String, Object> cfg, String module,
                               String code, List<String> errors) {
        List<String> events = asStrList(cfg.get("events"));
        List<String> classic = new ArrayList<>(asStrList(get(sch, "settings", "hook_events")));
        classic.addAll(asStrList(get(sch, "settings", "hook_events_extra")));
        List<String> gating = asStrList(cfg.get("gating_events"));

        Matcher on = Pattern.compile("\\bon\\(\\s*(['\"`])([^'\"`]+)\\1").matcher(code);
        while (on.find()) {
            String event = on.group(2);
            boolean known = events.contains(event)
                    || (event.startsWith("classic.") && classic.contains(event.substring(8)));
            if (!known) {
                errors.add("  " + module + " — `on('" + event + "')`: no such event; it never"
                        + " fires. Known: " + SCHEMA_FILE + " › mods.events, and classic.<Event>"
                        + " for settings.hook_events");
            }
            if (gating.contains(event)) {
                int close = closingParen(code, code.indexOf('(', on.start()));
                String after = close < 0 ? "" : code.substring(close + 1).stripLeading();
                if (!after.startsWith(".catch(")) {
                    errors.add("  " + module + " — the `" + event + "` hook has no `.catch(...)`:"
                            + " a hook that throws is skipped, and with it what it was holding."
                            + " End it in `.catch(($, e, next) => next(e))` ("
                            + SCHEMA_FILE + " › mods.gating_events)");
                }
            }
        }

        for (String call : asStrList(cfg.get("forbidden_calls"))) {
            if (code.contains(call + "(")) {
                errors.add("  " + module + " — calls `" + call + "`, which a mod here never does ("
                        + SCHEMA_FILE + " › mods.forbidden_calls)");
            }
        }

        List<String> programs = asStrList(cfg.get("process_allow"));
        Matcher run = Pattern.compile("\\$\\.process\\.run\\(\\s*(\\[\\s*(['\"])([^'\"]*)\\2)?").matcher(code);
        while (run.find()) {
            String program = run.group(3);
            if (program == null || !programs.contains(program)) {
                errors.add("  " + module + " — `$.process.run` starts "
                        + (program == null ? "a program not written as a string literal" : "`" + program + "`")
                        + "; only " + programs + " (" + SCHEMA_FILE + " › mods.process_allow)");
            }
        }
    }

    /**
     * `code` with every `//` and `/* *\/` comment replaced by spaces, string and template
     * literals left alone — so a word in a comment never reads as a call, and offsets hold.
     */
    static String stripJsComments(String code) {
        StringBuilder b = new StringBuilder(code);
        int i = 0;
        while (i < b.length()) {
            char c = b.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipString(code, i) + 1;
            } else if (c == '/' && i + 1 < b.length() && b.charAt(i + 1) == '/') {
                while (i < b.length() && b.charAt(i) != '\n') b.setCharAt(i++, ' ');
            } else if (c == '/' && i + 1 < b.length() && b.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                end = end < 0 ? b.length() : end + 2;
                while (i < end) { if (b.charAt(i) != '\n') b.setCharAt(i, ' '); i++; }
            } else {
                i++;
            }
        }
        return b.toString();
    }

    /** Index of the quote closing the string literal that opens at `start`, or the end. */
    static int skipString(String code, int start) {
        char q = code.charAt(start);
        int i = start + 1;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == q) return i;
            i++;
        }
        return code.length() - 1;
    }

    /** Index of the `)` matching the `(` at `open`, skipping string literals; -1 when none. */
    static int closingParen(String code, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\'' || c == '"' || c == '`') { i = skipString(code, i); continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    // ── skill classes ────────────────────────────────────────────────────────
    //
    // The class of a skill is data (`skill_classes` in extensions.json) and it decides two
    // separate things: the sections its body must carry, checked here, and the paths it may
    // write, enforced by the `guard` mode. Both failures used to be silent — nine skills
    // said `## Contract`, project-bootstrap said `## Skill contract`, arch-doctor and
    // init-project said nothing, and `claude plugin validate` printed `✔ Validation passed`
    // over all of it; the territory half let a design run write a service block into
    // docker-compose.yml while every skill involved forbade it in prose.
    //
    // Form 7c of `claude-code-architect-designer`, motivated by axis 7 (prose had already
    // failed) and axis 16 (both checks land on modes that already walk these files). The
    // closest rejected form was a frontmatter field per skill — silently ignored by the
    // runtime, so it would have been decoration.
    // Design: .claude/decisions/0058-skill-classes-territory-schema.md

    /**
     * Whether this tree is the repository `extensions.json`'s lists were written for. Two
     * conditions, and both are needed: the `export` block is present at all — the exported
     * copy drops it, which is the cheapest tell — and `export.source_marker` still resolves
     * to a directory here, which is what separates the source from a tree that merely copied
     * the manifest (the CI sandbox). A list naming a skill or an agent that deliberately does
     * not travel (`export.skills.exclude`, `export.agents.exclude`) is an error only where it
     * should exist; reporting it inside a generated project says nothing but "this is not that
     * repository".
     */
    static boolean isSourceRepo(Map<String, Object> sch) {
        Map<String, Object> exp = asMap(sch.get("export"));
        if (exp == null) return false;
        String marker = asStr(exp.get("source_marker"));
        return marker == null || Files.isDirectory(ROOT.resolve(marker));
    }

    /** The class that lists this skill in `skill_classes`, or null when none does. */
    static String skillClassOf(Map<String, Object> sch, String skill) {
        Map<String, Object> classes = asMap(get(sch, "skill_classes", "classes"));
        if (classes == null || skill == null) return null;
        for (Map.Entry<String, Object> e : classes.entrySet()) {
            Map<String, Object> c = asMap(e.getValue());
            if (c != null && asStrList(c.get("skills")).contains(skill)) return e.getKey();
        }
        return null;
    }

    /** The skill's own `overrides` territory when it has one, else its class default. */
    static List<String> writeAllowOf(Map<String, Object> sch, String skill) {
        String cls = skillClassOf(sch, skill);
        if (cls == null) return List.of();
        Map<String, Object> c = asMap(get(sch, "skill_classes", "classes", cls));
        Map<String, Object> ov = asMap(get(c, "overrides", skill));
        if (ov != null && ov.containsKey("write_allow")) return asStrList(ov.get("write_allow"));
        return asStrList(c == null ? null : c.get("write_allow"));
    }

    /** `<name>` of `.claude/skills/<name>/SKILL.md`. */
    static String skillNameOf(String rel) {
        Matcher m = Pattern.compile("skills/([^/]+)/SKILL\\.md$").matcher(rel);
        return m.find() ? m.group(1) : null;
    }

    /**
     * A skill folder named after a built-in slash command. The folder name is the command, and
     * the runtime resolves the collision without a word — one of the two stops being reachable,
     * which is why this repository's diagnostic is `arch-doctor` and not `doctor`.
     *
     * <p>Form 7c of `claude-code-architect-designer`, motivated by axis 8: the check used to be
     * a `NATIVE="…"` string in `.github/workflows/validate.yml`, so a generated project — which
     * has no copy of the workflow — never ran it, and the list had gone stale beside the
     * runtime. The list is data now, `types.skill.native_commands` (invariant 10). The closest
     * rejected form was refreshing the YAML list: same staleness, same blind spot downstream.
     * Design: .claude/decisions/0084-ci-covers-jar-and-post-0075-guards.md
     */
    static void checkNativeShadow(Map<String, Object> sch, String rel, String skill,
                                  List<String> errors) {
        if (asStrList(get(sch, "types", "skill", "native_commands")).contains(skill)) {
            errors.add("  " + rel + " — skill `" + skill + "` shadows the native /" + skill
                    + " command; one of the two silently stops being reachable. Rename the"
                    + " folder (and `name:`), e.g. `arch-" + skill + "` — see"
                    + " docs/pt-br/11-pitfalls.md. List: types.skill.native_commands in "
                    + SCHEMA_FILE);
        }
    }

    /**
     * One skill body against its class: the `**Class:** <c>` line agrees with the data, and
     * every required section is present. A section is matched as a PREFIX of an H2 line, so
     * `## Procedure — design mode` satisfies `## Procedure`; order is not checked. The
     * frontmatter carries every `universal_fields` entry, and `model` is one of the class's
     * `allowed_models` when it lists any.
     */
    static void checkSkillBody(Map<String, Object> sch, String rel, String content,
                               List<String> errors) {
        String skill = skillNameOf(rel);
        if (skill == null) return;
        checkNativeShadow(sch, rel, skill, errors);
        Map<String, Object> sc = asMap(sch.get("skill_classes"));
        if (sc == null) return;

        Map<String, Object> classes = asMap(sc.get("classes"));
        if (classes == null) return;
        String cls = skillClassOf(sch, skill);
        if (cls == null) {
            errors.add("  " + rel + " — `" + skill + "` is in no skill_classes class."
                    + " Add it to one of " + classes.keySet() + " in " + SCHEMA_FILE);
            return;
        }

        String marker = orEmpty(asStr(sc.get("class_marker")));
        if (!marker.isEmpty()) {
            // `- ` prefix allowed: audit-usage writes its whole contract as a bullet list.
            Matcher m = Pattern.compile("(?m)^[-*]?[ \\t]*" + Pattern.quote(marker)
                            + "\\s*`?([a-z][a-z0-9_-]*)`?")
                    .matcher(content);
            if (!m.find()) {
                errors.add("  " + rel + " — no `" + marker + " " + cls + "` line in the body."
                        + " The class is what the guard enforces; state it in ## Contract");
            } else if (!cls.equals(m.group(1))) {
                errors.add("  " + rel + " — body says `" + marker + " " + m.group(1)
                        + "`, " + SCHEMA_FILE + " lists it under `" + cls + "`");
            }
        }

        List<String> required = new ArrayList<>(asStrList(sc.get("universal_sections")));
        required.addAll(asStrList(get(sch, "skill_classes", "classes", cls, "required_sections")));
        List<String> headings = content.lines().filter(l -> l.startsWith("## "))
                .map(String::strip).collect(Collectors.toList());
        for (String req : required) {
            if (headings.stream().noneMatch(h -> h.startsWith(req))) {
                errors.add("  " + rel + " — class `" + cls + "` requires the section `"
                        + req + "`");
            }
        }

        // An absent `model` is a silent default: the skill runs on whatever the session runs.
        // Every skill states it, and its class says which values it may state — 0081.
        Map<String, String> fm = frontmatter(content);
        if (fm != null) {                              // checkFrontmatter already reported it
            List<String> allowed = asStrList(get(sch, "skill_classes", "classes", cls,
                    "allowed_models"));
            String shown = allowed.isEmpty() ? "" : " (allowed: " + String.join(", ", allowed) + ")";
            for (String f : asStrList(sc.get("universal_fields"))) {
                if (!fm.containsKey(f) || fm.get(f).isEmpty()) {
                    errors.add("  " + rel + " — class `" + cls
                            + "` requires the frontmatter field `" + f + "`" + shown);
                }
            }
            String model = fm.get("model");
            if (model != null && !model.isEmpty() && !allowed.isEmpty()
                    && !allowed.contains(model.strip())) {
                errors.add("  " + rel + " — `model: " + model.strip()
                        + "` is not allowed for class `" + cls + "`" + shown
                        + ". The set is `skill_classes.classes." + cls + ".allowed_models` in "
                        + SCHEMA_FILE);
            }
        }

        // A bare `Bash` in allowed-tools pre-approves every shell command for the skill's whole
        // turn. Scoped `Bash(<cmd> *)` entries, or a written reason in the body — 0076.
        String bashMarker = asStr(sc.get("unfiltered_bash_marker"));
        Matcher at = Pattern.compile("(?m)^allowed-tools:(.*)$").matcher(content);
        if (bashMarker != null && at.find()
                && Pattern.compile("(^|[\\s,])Bash\\s*(,|$)").matcher(at.group(1).strip()).find()
                && !content.contains(bashMarker)) {
            errors.add("  " + rel + " — allowed-tools pre-approves bare `Bash`. Scope it"
                    + " (`Bash(ls *)`) or state why in ## Contract with a `" + bashMarker
                    + " <reason>` line");
        }

        String why = asStr(sc.get("why_section"));
        if (why != null && !Pattern.compile(why).matcher(content).find()) {
            errors.add("  " + rel + " — no `## Why …` section. Three sentences: the form,"
                    + " the interview axis, the closest rejected form");
        }
    }

    /**
     * The `skill_classes` block against the skills on disk. Coverage is total by design:
     * a skill no class lists has no territory, so the guard would let it write anything.
     * Runs only at sweep time (Stop, or a manual `schema`) — it lists a directory.
     */
    static void checkSkillClasses(Map<String, Object> sch, List<String> errors) throws IOException {
        Map<String, Object> classes = asMap(get(sch, "skill_classes", "classes"));
        if (classes == null) return;   // no block: nothing to cross-check

        // The block travels whole into every generated project, and three creation skills
        // deliberately do not (`export.skills.exclude`). So "listed but absent" is only an
        // error in the repository the block was written for. Coverage in the other direction
        // (a skill on disk that no class lists) is checked everywhere: a project that adds a
        // skill of its own needs a class for it just as much.
        boolean here = isSourceRepo(sch);

        Map<String, String> owner = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : classes.entrySet()) {
            Map<String, Object> c = asMap(e.getValue());
            if (c == null) continue;
            if (!c.containsKey("write_allow")) {
                errors.add("  skill_classes." + e.getKey() + " has no `write_allow` key."
                        + " An empty list is a decision; a missing key is an omission");
            }
            for (String s : asStrList(c.get("skills"))) {
                String previous = owner.put(s, e.getKey());
                if (previous != null) {
                    errors.add("  skill_classes lists `" + s + "` in both `" + previous
                            + "` and `" + e.getKey() + "` — one class per skill");
                }
                if (here && !Files.isRegularFile(ROOT.resolve(".claude/skills/" + s + "/SKILL.md"))) {
                    errors.add("  skill_classes." + e.getKey() + " lists `" + s
                            + "` — no `.claude/skills/" + s + "/SKILL.md` file exists");
                }
            }
            Map<String, Object> ov = asMap(c.get("overrides"));
            if (ov != null) {
                for (String s : ov.keySet()) {
                    if (!asStrList(c.get("skills")).contains(s)) {
                        errors.add("  skill_classes." + e.getKey() + ".overrides names `" + s
                                + "` — not a skill of that class");
                    }
                }
            }
        }

        Path skillsDir = ROOT.resolve(".claude/skills");
        if (!Files.isDirectory(skillsDir)) return;
        try (Stream<Path> walk = Files.list(skillsDir)) {
            for (Path d : walk.filter(Files::isDirectory).sorted().collect(Collectors.toList())) {
                String name = d.getFileName().toString();
                if (!Files.isRegularFile(d.resolve("SKILL.md"))) continue;
                if (!owner.containsKey(name)) {
                    errors.add("  `.claude/skills/" + name + "` is in no skill_classes class"
                            + " — add it to one in " + SCHEMA_FILE);
                }
            }
        }
    }

    // ── agent classes ────────────────────────────────────────────────────────
    //
    // The agent-side twin of `skill_classes`, and the same two failures it closes, one layer
    // lower. Structure: the four agent files shared no shape — `project-initializer` had no
    // H1 title, called its procedure `## Steps` while the other three called it
    // `## Procedure`, and carried none of `## Failure mode`, `## Summary format`,
    // `## References`; `claude plugin validate` does not read `.claude/agents/` at all, and
    // `schema` looked only at their frontmatter field NAMES. Territory: each agent documented
    // a prose `**Writes:**` / `**Does not write:**` contract and the guard granted all four an
    // unconditional bypass, so none of those promises was enforced by anything.
    //
    // The territory half is keyed on `agent_type`, which the PreToolUse payload of a
    // subagent's write already carries — the field the old bypass read. So it needs no phase
    // file and no SubagentStop bookkeeping, and it is immune by construction to the two-hook
    // ordering race of lessons-learned-006 § 1: there is nothing to order, the payload names
    // the agent on every single write.
    //
    // Form 7c of `claude-code-architect-designer`, motivated by axis 7 (prose contracts that
    // nothing enforced) and axis 16 (both checks land on modes that already walk these files
    // and already read this payload field). The closest rejected form was body shape alone,
    // leaving the bypass in place — it would have standardized the promise instead of keeping
    // it. Design: .claude/decisions/0059-agent-classes-territory-schema.md

    /** The class that lists this agent in `agent_classes`, or null when none does. */
    static String agentClassOf(Map<String, Object> sch, String agent) {
        Map<String, Object> classes = asMap(get(sch, "agent_classes", "classes"));
        if (classes == null || agent == null) return null;
        for (Map.Entry<String, Object> e : classes.entrySet()) {
            Map<String, Object> c = asMap(e.getValue());
            if (c != null && asStrList(c.get("agents")).contains(agent)) return e.getKey();
        }
        return null;
    }

    /** The agent's own `overrides` territory when it has one, else its class default. */
    static List<String> agentWriteAllowOf(Map<String, Object> sch, String agent) {
        String cls = agentClassOf(sch, agent);
        if (cls == null) return List.of();
        Map<String, Object> c = asMap(get(sch, "agent_classes", "classes", cls));
        Map<String, Object> ov = asMap(get(c, "overrides", agent));
        if (ov != null && ov.containsKey("write_allow")) return asStrList(ov.get("write_allow"));
        return asStrList(c == null ? null : c.get("write_allow"));
    }

    /** `<name>` of `.claude/agents/<name>.md`. */
    static String agentNameOf(String rel) {
        Matcher m = Pattern.compile("agents/([^/]+)\\.md$").matcher(rel);
        return m.find() ? m.group(1) : null;
    }

    /**
     * One agent body and its frontmatter against its class: the `**Class:** <c>` line agrees
     * with the data, every required section is present (matched as a PREFIX of an H2 line, so
     * `## Input validation (guardrail)` satisfies `## Input validation`), the body opens with
     * an H1, the fields the class owes are declared, and no forbidden value is set.
     * `model` and `tools` are universal because they are two of the three reasons an agent is
     * allowed to exist at all (invariant 5) — an agent that declares neither has documented
     * none of them, and inherits whatever the caller had.
     */
    static void checkAgentBody(Map<String, Object> sch, String rel, String content,
                               List<String> errors) {
        Map<String, Object> ac = asMap(sch.get("agent_classes"));
        if (ac == null) return;
        String agent = agentNameOf(rel);
        if (agent == null) return;

        Map<String, Object> classes = asMap(ac.get("classes"));
        if (classes == null) return;
        String cls = agentClassOf(sch, agent);
        if (cls == null) {
            errors.add("  " + rel + " — `" + agent + "` is in no agent_classes class."
                    + " Add it to one of " + classes.keySet() + " in " + SCHEMA_FILE);
            return;
        }

        String marker = orEmpty(asStr(ac.get("class_marker")));
        if (!marker.isEmpty()) {
            Matcher m = Pattern.compile("(?m)^[-*]?[ \\t]*" + Pattern.quote(marker)
                            + "\\s*`?([a-z][a-z0-9_-]*)`?")
                    .matcher(content);
            if (!m.find()) {
                errors.add("  " + rel + " — no `" + marker + " " + cls + "` line in the body."
                        + " The class is what the guard enforces; state it in ## Contract");
            } else if (!cls.equals(m.group(1))) {
                errors.add("  " + rel + " — body says `" + marker + " " + m.group(1)
                        + "`, " + SCHEMA_FILE + " lists it under `" + cls + "`");
            }
        }

        List<String> required = new ArrayList<>(asStrList(ac.get("universal_sections")));
        required.addAll(asStrList(get(sch, "agent_classes", "classes", cls, "required_sections")));
        List<String> headings = content.lines().filter(l -> l.startsWith("## "))
                .map(String::strip).collect(Collectors.toList());
        for (String req : required) {
            if (headings.stream().noneMatch(h -> h.startsWith(req))) {
                errors.add("  " + rel + " — class `" + cls + "` requires the section `"
                        + req + "`");
            }
        }

        String why = asStr(ac.get("why_section"));
        if (why != null && !Pattern.compile(why).matcher(content).find()) {
            errors.add("  " + rel + " — no `## Why …` section. Three sentences: the form,"
                    + " the interview axis, the closest rejected form");
        }

        if (Boolean.TRUE.equals(ac.get("require_h1")) && bodyH1(content) == null) {
            errors.add("  " + rel + " — the body does not open with an H1 title."
                    + " `# " + agent + " — <role in one line>`");
        }

        Map<String, String> fm = frontmatter(content);
        if (fm == null) return;                        // checkFrontmatter already reported it
        List<String> fields = new ArrayList<>(asStrList(ac.get("universal_fields")));
        fields.addAll(asStrList(get(sch, "agent_classes", "classes", cls, "required_fields")));
        for (String f : fields) {
            if (!fm.containsKey(f)) {
                errors.add("  " + rel + " — class `" + cls + "` requires the frontmatter field `"
                        + f + "`");
            }
        }
        Map<String, Object> forbidden = asMap(ac.get("forbidden_values"));
        if (forbidden == null) return;
        for (Map.Entry<String, Object> e : forbidden.entrySet()) {
            String v = fm.get(e.getKey());
            if (v != null && asStrList(e.getValue()).contains(v.strip())) {
                errors.add("  " + rel + " — `" + e.getKey() + ": " + v.strip()
                        + "` is forbidden for an agent (trust surface)");
            }
        }
    }

    /** The first H1 line after the frontmatter, or null when the body opens with anything else. */
    static String bodyH1(String content) {
        boolean inFm = false;
        int seen = 0;
        for (String raw : content.lines().collect(Collectors.toList())) {
            String l = raw.strip();
            if (l.equals("---") && seen < 2) { inFm = !inFm; seen++; continue; }
            if (inFm || l.isEmpty()) continue;
            return l.startsWith("# ") ? l : null;
        }
        return null;
    }

    /**
     * The `agent_classes` block against the agents on disk. Coverage is total by design, both
     * directions, and it also owns what `guard.executor_agents` used to be cross-checked for:
     * a class with `executor: true` whose agent file never claims `**Executor:** yes`, or a
     * file that claims it from a class that does not grant it. lessons-learned-006 § 2:
     * commons-logging-installer shipped for one full session with the two sides disagreeing
     * before anyone noticed by hand.
     */
    static void checkAgentClasses(Map<String, Object> sch, List<String> errors) throws IOException {
        Map<String, Object> classes = asMap(get(sch, "agent_classes", "classes"));
        if (classes == null) return;   // no block: nothing to cross-check

        // `project-initializer` drives `/init-project` and, like the creation skills, does not
        // travel into a generated project (`export.agents.exclude`) — so "listed but absent" is
        // an error only in the repository the block was written for. See isSourceRepo.
        boolean here = isSourceRepo(sch);
        Path agentsDir = ROOT.resolve(".claude/agents");
        String marker = orEmpty(asStr(get(sch, "agent_classes", "executor_marker")));

        Map<String, String> owner = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : classes.entrySet()) {
            Map<String, Object> c = asMap(e.getValue());
            if (c == null) continue;
            if (!c.containsKey("write_allow")) {
                errors.add("  agent_classes." + e.getKey() + " has no `write_allow` key."
                        + " An empty list is a decision; a missing key is an omission");
            }
            boolean executor = Boolean.TRUE.equals(c.get("executor"));
            for (String a : asStrList(c.get("agents"))) {
                String previous = owner.put(a, e.getKey());
                if (previous != null) {
                    errors.add("  agent_classes lists `" + a + "` in both `" + previous
                            + "` and `" + e.getKey() + "` — one class per agent");
                }
                Path f = agentsDir.resolve(a + ".md");
                if (!Files.isRegularFile(f)) {
                    if (here) {
                        errors.add("  agent_classes." + e.getKey() + " lists `" + a
                                + "` — no `.claude/agents/" + a + ".md` file exists");
                    }
                    continue;
                }
                if (marker.isEmpty()) continue;
                boolean claims = orEmpty(readOrNull(f)).contains(marker);
                if (executor && !claims) {
                    errors.add("  agent_classes." + e.getKey() + " grants `executor: true` to `"
                            + a + "` — its file has no `" + marker + "` marker in ## Contract");
                } else if (!executor && claims) {
                    errors.add("  .claude/agents/" + a + ".md claims `" + marker
                            + "` — class `" + e.getKey() + "` does not grant `executor: true`");
                }
            }
            Map<String, Object> ov = asMap(c.get("overrides"));
            if (ov != null) {
                for (String a : ov.keySet()) {
                    if (!asStrList(c.get("agents")).contains(a)) {
                        errors.add("  agent_classes." + e.getKey() + ".overrides names `" + a
                                + "` — not an agent of that class");
                    }
                }
            }
        }

        if (!Files.isDirectory(agentsDir)) return;
        try (Stream<Path> walk = Files.list(agentsDir)) {
            for (Path f : walk.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted().collect(Collectors.toList())) {
                String name = f.getFileName().toString().replaceFirst("\\.md$", "");
                if (!owner.containsKey(name)) {
                    errors.add("  `.claude/agents/" + name + ".md` is in no agent_classes class"
                            + " — add it to one in " + SCHEMA_FILE);
                }
            }
        }
    }

    // ── context ──────────────────────────────────────────────────────────────

    /**
     * Hands an agent the design-pattern catalog before its first turn. Registered at
     * `SubagentStart` with no matcher, and only in the generated project's
     * `settings.json` template: reads `agent_type` from stdin, resolves that agent's
     * `pattern_catalog` in `agent_classes`, and when it is true prints the sections
     * `subagent_context` names as `additionalContext`. Emits nothing otherwise, and never
     * blocks — `SubagentStart` cannot.
     *
     * <p>Form 7c of `claude-code-architect-designer`, motivated by axis 7 — the catalog has
     * to be in context every time a Java-writing agent runs, not when the model remembers to
     * read it. The closest rejected form was the agent's `skills:` field: a skill with
     * `disable-model-invocation: true` cannot be preloaded, so the catalog it promised never
     * arrived. Design: .claude/decisions/0077-pattern-catalog-injected-at-subagent-start.md
     */
    static void context(String sub, String stdin) throws Exception {
        if (!"subagent".equals(sub)) {
            err("Unknown context sub-mode: " + sub + " (expected: subagent)");
            return;
        }
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        if (sch == null) return;
        String agent = asStr(get(Json.parse(stdin), "agent_type"));
        if (!Boolean.TRUE.equals(patternCatalogOf(sch, agent))) return;
        String text = renderSubagentContext(sch, new ArrayList<>());
        if (text == null || text.isBlank()) return;
        // System.out follows the platform charset — cp1252 on Windows — and the runtime reads
        // the hook's stdout as UTF-8: every em dash in the catalog arrived as an invalid byte.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        out.println("{\"hookSpecificOutput\":{\"hookEventName\":\"SubagentStart\","
                + "\"additionalContext\":\"" + jsonEscape(text) + "\"}}");
    }

    /**
     * The agent's effective `pattern_catalog`: its `overrides` entry when that declares the
     * key, else its class's. Null when neither declares it — which `schema` rejects for an
     * agent that writes `src/`.
     */
    static Boolean patternCatalogOf(Map<String, Object> sch, String agent) {
        String cls = agentClassOf(sch, agent);
        if (cls == null) return null;
        Map<String, Object> c = asMap(get(sch, "agent_classes", "classes", cls));
        Map<String, Object> ov = asMap(get(c, "overrides", agent));
        Object v = ov != null && ov.containsKey("pattern_catalog") ? ov.get("pattern_catalog")
                : c == null ? null : c.get("pattern_catalog");
        return v instanceof Boolean b ? b : null;
    }

    /**
     * The text `context subagent` injects: each heading in `subagent_context.sections`,
     * extracted from `subagent_context.file` up to the next H2, then the `pointer` line.
     * Shared with `schema` so the size it checks is the size that ships. A heading missing
     * from the file is added to {@code problems} rather than skipped in silence.
     */
    static String renderSubagentContext(Map<String, Object> sch, List<String> problems) {
        Map<String, Object> sc = asMap(sch.get("subagent_context"));
        if (sc == null) return null;
        String file = orEmpty(asStr(sc.get("file")));
        String content = readOrNull(ROOT.resolve(file));
        if (content == null) {
            problems.add("subagent_context.file `" + file + "` does not exist");
            return null;
        }
        List<String> lines = content.lines().collect(Collectors.toList());
        StringBuilder out = new StringBuilder();
        String header = asStr(sc.get("header"));
        if (header != null) out.append(header).append("\n\n");
        for (String heading : asStrList(sc.get("sections"))) {
            int start = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().equals(heading)) { start = i; break; }
            }
            if (start < 0) {
                problems.add("subagent_context.sections names `" + heading
                        + "` — no such heading in " + file);
                continue;
            }
            int end = start + 1;
            while (end < lines.size() && !lines.get(end).startsWith("## ")) end++;
            out.append(String.join("\n", lines.subList(start, end)).strip()).append("\n\n");
        }
        String pointer = asStr(sc.get("pointer"));
        if (pointer != null) out.append(pointer).append('\n');
        return out.toString();
    }

    /**
     * `subagent_context` and `pattern_catalog` against the files: every agent whose effective
     * territory reaches `src/` has answered whether it receives the catalog, its
     * `pattern_catalog_marker` line says the same as the data, every listed section exists,
     * and the rendered text stays under `max_chars` — above the runtime's 10 000-character
     * cap, `additionalContext` is replaced by a file path the agent is not asked to read.
     */
    static void checkSubagentContext(Map<String, Object> sch, List<String> errors) {
        Map<String, Object> classes = asMap(get(sch, "agent_classes", "classes"));
        String marker = asStr(get(sch, "agent_classes", "pattern_catalog_marker"));
        Path agentsDir = ROOT.resolve(".claude/agents");
        if (classes != null) {
            for (Map.Entry<String, Object> e : classes.entrySet()) {
                Map<String, Object> c = asMap(e.getValue());
                if (c == null) continue;
                for (String a : asStrList(c.get("agents"))) {
                    Boolean flag = patternCatalogOf(sch, a);
                    boolean writesSrc = agentWriteAllowOf(sch, a).stream().anyMatch(g ->
                            g.equals("**") || g.startsWith("src/") || g.startsWith("**/src/"));
                    if (flag == null && writesSrc) {
                        errors.add("  agent_classes." + e.getKey() + ": `" + a + "` may write src/"
                                + " and declares no `pattern_catalog` — set it (true or false)"
                                + " on the class or in overrides." + a);
                    }
                    String body = readOrNull(agentsDir.resolve(a + ".md"));
                    if (body == null || marker == null) continue;
                    Matcher m = Pattern.compile("(?m)^[-*]?[ \\t]*" + Pattern.quote(marker)
                            + "[ \\t]*(not injected|injected)").matcher(body);
                    Boolean claims = m.find() ? m.group(1).equals("injected") : null;
                    if (!Objects.equals(flag, claims)) {
                        errors.add("  .claude/agents/" + a + ".md: `" + marker + "` line says "
                                + (claims == null ? "nothing" : claims ? "injected" : "not injected")
                                + ", agent_classes says " + (flag == null ? "nothing"
                                : flag ? "pattern_catalog: true" : "pattern_catalog: false"));
                    }
                }
            }
        }
        Map<String, Object> sc = asMap(sch.get("subagent_context"));
        if (sc == null) return;
        List<String> problems = new ArrayList<>();
        String text = renderSubagentContext(sch, problems);
        problems.forEach(p -> errors.add("  " + p));
        Object max = sc.get("max_chars");
        if (text != null && max instanceof Number n && text.length() > n.intValue()) {
            errors.add("  subagent_context renders " + text.length() + " characters, over"
                    + " max_chars " + n.intValue() + " — trim the sections it lists");
        }
    }

    /**
     * Cross-checks the `export` manifest against what is actually on disk. The mode that
     * reads the manifest writes a project's whole `.claude/`, so an entry naming a file
     * this repo no longer has produces a dead citation inside every project exported
     * afterwards, and a skill or agent listed in neither `include` nor `exclude` simply
     * never travels — both silent, which is why they are checked here and not left to
     * review. Runs only at sweep time (Stop, or a manual `schema`): it walks three
     * directories, and paying that on every Edit would buy nothing.
     *
     * <p>Form 7c of `claude-code-architect-designer`, motivated by axis 7 — the export
     * runs unattended on other people's machines, so its input cannot be trusted to a
     * reviewer's memory. The closest rejected form was a line in a rule: prose that had
     * already gone stale twice in `project-bootstrap`'s copy tables, which this manifest
     * replaces. Design: .claude/decisions/0054-deterministic-export-provenance-plugin.md
     */
    static void checkExportManifest(Map<String, Object> sch, List<String> errors) throws IOException {
        Map<String, Object> exp = asMap(sch.get("export"));
        if (exp == null) return;                      // no `export` block: nothing to check

        // The manifest describes the repository it lives in. A tree that merely copied
        // extensions.json — the CI injection sandbox, or a project that kept the block —
        // has none of the files it names, and reporting all of them as missing says only
        // that this is not that repository. `source_marker` is the directory that answers
        // it: present here, travels nowhere.
        String marker = asStr(exp.get("source_marker"));
        if (marker != null && !Files.isDirectory(ROOT.resolve(marker))) return;

        for (String group : List.of("copy", "overwrite", "binary_copy")) {
            for (Object e : asList(exp.get(group))) {
                String from = asStr(get(e, "from"));
                if (from == null) {
                    errors.add("  export." + group + " has an entry without `from`");
                } else if (!Files.isRegularFile(ROOT.resolve(from))) {
                    errors.add("  export." + group + " names `" + from + "` — no such file");
                }
            }
        }

        checkExportSet(exp, "skills", ".claude/skills", "/SKILL.md", errors);
        checkExportSet(exp, "agents", ".claude/agents", ".md", errors);

        String rulesDir = asStr(get(exp, "rules", "from"));
        if (rulesDir != null && !Files.isDirectory(ROOT.resolve(rulesDir))) {
            errors.add("  export.rules.from is `" + rulesDir + "` — no such directory");
        }
        for (String r : asStrList(get(exp, "rules", "exclude"))) {
            if (rulesDir != null && !Files.isRegularFile(ROOT.resolve(rulesDir).resolve(r))) {
                errors.add("  export.rules.exclude names `" + r + "` — no such rule");
            }
        }
        List<String> derived = new ArrayList<>();
        for (String r : asMapKeys(exp.get("derived_paths"))) {
            if (r.startsWith("$") || r.equals("derived_paths_exempt_globs")) continue;
            derived.add(r);
            if (rulesDir != null && !Files.isRegularFile(ROOT.resolve(rulesDir).resolve(r))) {
                errors.add("  export.derived_paths names `" + r + "` — no such rule");
            }
        }
        checkRuleTerritories(exp, rulesDir, derived, errors);
        // Retired and still on disk here: `export` would ship it and never delete it.
        for (String r : asStrList(exp.get("retired"))) {
            if (Files.exists(ROOT.resolve(r))) {
                errors.add("  export.retired names `" + r + "` — it still exists here, so it"
                        + " would be shipped and retired at once; delete it or drop the entry");
            }
        }
        for (Object e : asList(get(exp, "body_transforms", "rewrite"))) {
            String f = asStr(get(e, "file"));
            if (f != null && !Files.isRegularFile(ROOT.resolve(f))) {
                errors.add("  export.body_transforms.rewrite names `" + f + "` — no such file");
            }
        }

    }

    /**
     * The `source` block: where a project pulls this `.claude/` from. Checked apart from
     * the `export` manifest and not inside it, because the exported copy keeps this block
     * and drops that one — a project validates its own update path, which is the only
     * path it has. An indirection, never a credential: invariant 11, scanned with the
     * same prefix list the `mcp` block already owns.
     */
    static void checkSourceBlock(Map<String, Object> sch, List<String> errors) {
        Map<String, Object> src = asMap(sch.get("source"));
        if (src == null) return;
        String base = asStr(src.get("base_url"));
        if (base != null) {
            if (!base.startsWith("https://")) {
                errors.add("  source.base_url is not https — `" + base + "`");
            }
            if (!base.contains("{ref}")) {
                errors.add("  source.base_url has no `{ref}` placeholder"
                        + " — every fetch would pull the same content");
            }
            for (String p : asStrList(get(sch, "mcp", "secret_scan", "value_prefixes"))) {
                if (base.contains(p)) {
                    errors.add("  source.base_url carries a literal credential"
                            + " (`" + p + "…`) — use source.auth_env, invariant 11");
                }
            }
        }
        String git = asStr(src.get("git_url"));
        if (git != null && !git.startsWith("https://")) {
            errors.add("  source.git_url is not https — `" + git + "`");
        }
        String authEnv = asStr(src.get("auth_env"));
        if (authEnv != null && !authEnv.matches("[A-Z][A-Z0-9_]*")) {
            errors.add("  source.auth_env is `" + authEnv
                    + "` — it names an environment variable, never its value");
        }
    }

    /**
     * The `migrations` block: what `arch-adopt` shows a project after an update, so code
     * generated under an older convention gets a note and a prompt instead of silently
     * mixing two layouts. Every failure here is silent at runtime — `arch-adopt` reads the
     * block with the model, and an entry with a misspelled blueprint id, a missing `prompt`,
     * or an id already used by another entry is simply never shown, or shown once for two
     * changes. Checked at `schema` time, the one moment a person is still looking.
     *
     * <p>Extension of the existing `schema` mode (Form 7c rules, invariant 10: the field list
     * and the id pattern are read from the block itself), motivated by axis 17 of
     * `claude-code-architect-designer` — the data has no other check, and the arch-adopt step
     * that reads it is procedure CI cannot run. The closest rejected form was data with no
     * check, recorded as untestable: a typo would ship to every project. Blueprint ids are
     * checked only in this repository, since a generated project holds its active blueprint
     * alone. Design: .claude/decisions/0104-use-case-subpackage-per-aggregate.md
     */
    static void checkMigrations(Map<String, Object> sch, List<String> errors) {
        Map<String, Object> mig = asMap(sch.get("migrations"));
        if (mig == null) return;
        List<String> required = asStrList(mig.get("required_fields"));
        String idPattern = asStr(mig.get("id_pattern"));
        boolean source = isSourceRepo(sch);
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (Object o : asList(mig.get("entries"))) {
            String where = "  migrations.entries[" + i++ + "]";
            Map<String, Object> e = asMap(o);
            if (e == null) {
                errors.add(where + " is not an object");
                continue;
            }
            String id = asStr(e.get("id"));
            if (id != null) where += " `" + id + "`";
            for (String f : required) {
                Object v = e.get(f);
                boolean empty = v == null
                        || (v instanceof String s && s.isBlank())
                        || (v instanceof List<?> l && l.isEmpty());
                if (empty) errors.add(where + " has no `" + f + "`");
            }
            if (id != null && idPattern != null && !id.matches(idPattern)) {
                errors.add(where + " — id does not match `" + idPattern + "`");
            }
            if (id != null && !seen.add(id)) {
                errors.add(where + " — id already used by an earlier entry; a project"
                        + " that saw the first would never see this one");
            }
            if (!source) continue;
            for (String bp : asStrList(e.get("blueprints"))) {
                if (!Files.isRegularFile(ROOT.resolve(".claude/blueprints/" + bp + "/" + bp + ".yaml"))) {
                    errors.add(where + " — blueprint `" + bp + "` does not exist under"
                            + " .claude/blueprints/; no project would ever be shown this entry");
                }
            }
        }
    }

    /**
     * The other direction of `derived_paths`: a rule whose `paths` names a package has a
     * territory, and that territory changes with the architecture. Missing from the map,
     * it travels with the glob of whichever blueprint happened to be written into the
     * file, and in every other architecture it never enters context — gap 8 of
     * lessons-learned-001, which cost a norm that silently applied to one blueprint out
     * of three. The globs that name no package are data, in `derived_paths_exempt_globs`.
     */
    static void checkRuleTerritories(Map<String, Object> exp, String rulesDir,
                                     List<String> derived, List<String> errors) throws IOException {
        if (rulesDir == null) return;
        Path dir = ROOT.resolve(rulesDir);
        if (!Files.isDirectory(dir)) return;
        List<String> exempt = asStrList(get(exp, "derived_paths", "derived_paths_exempt_globs"));
        Pattern glob = Pattern.compile("(?m)^[ \t]+-[ \t]+\"([^\"]+)\"");

        try (Stream<Path> walk = Files.list(dir)) {
            for (Path f : walk.filter(p -> p.toString().endsWith(".md")).sorted()
                    .collect(Collectors.toList())) {
                String name = f.getFileName().toString();
                if (derived.contains(name)) continue;
                String body = readOrNull(f);
                if (body == null) continue;
                body = body.replace("\r\n", "\n");             // CRLF checkout: see exportRules
                int at = body.indexOf("\npaths:");
                if (!body.startsWith("---\npaths:") && at < 0) continue;
                int from = body.startsWith("---\npaths:") ? 4 : at + 1;
                int to = body.indexOf("\nstatus:", from);
                Matcher m = glob.matcher(body.substring(from, to < 0 ? body.length() : to));
                while (m.find()) {
                    if (exempt.contains(m.group(1))) continue;
                    errors.add("  .claude/rules/" + name + " has the territory glob `" + m.group(1)
                            + "` and no entry in export.derived_paths — it would travel naming"
                            + " this repo's package, and never load where that package differs");
                    break;
                }
            }
        }
    }

    /**
     * One side of {@link #checkExportManifest}: every directory entry under {@code dir}
     * must appear in `include` or in `exclude`, and every listed name must resolve to a
     * real file. Coverage is the point — a new skill nobody added to either list is the
     * failure this catches, and it looks exactly like a working repository.
     */
    static void checkExportSet(Map<String, Object> exp, String key, String defaultDir,
                               String suffix, List<String> errors) throws IOException {
        Map<String, Object> block = asMap(exp.get(key));
        if (block == null) return;

        String dir = asStr(block.get("from")) != null ? asStr(block.get("from")) : defaultDir;
        Path base = ROOT.resolve(dir);
        if (!Files.isDirectory(base)) {
            errors.add("  export." + key + ".from is `" + dir + "` — no such directory");
            return;
        }

        List<String> include = asStrList(block.get("include"));
        List<String> exclude = asStrList(block.get("exclude"));
        for (String name : include) {
            if (!Files.isRegularFile(base.resolve(name + suffix))) {
                errors.add("  export." + key + ".include names `" + name
                        + "` — no `" + dir + "/" + name + suffix + "`");
            }
        }
        for (String name : exclude) {
            if (!Files.isRegularFile(base.resolve(name + suffix))) {
                errors.add("  export." + key + ".exclude names `" + name
                        + "` — no `" + dir + "/" + name + suffix + "`, stale entry");
            }
        }
        for (String name : include) {
            if (exclude.contains(name)) {
                errors.add("  export." + key + " lists `" + name + "` in include AND exclude");
            }
        }

        try (Stream<Path> walk = Files.list(base)) {
            List<String> onDisk = walk.map(p -> p.getFileName().toString())
                    .map(n -> n.endsWith(".md") ? n.replaceFirst("\\.md$", "") : n)
                    .sorted().collect(Collectors.toList());
            for (String name : onDisk) {
                if (!Files.isRegularFile(base.resolve(name + suffix))) continue;
                if (!include.contains(name) && !exclude.contains(name)) {
                    errors.add("  `" + dir + "/" + name + "` is in neither export." + key
                            + ".include nor .exclude — it would silently never travel");
                }
            }
        }
    }

    static List<String> asMapKeys(Object o) {
        Map<String, Object> m = asMap(o);
        return m == null ? List.of() : new ArrayList<>(m.keySet());
    }

    /** Sweeps every file that some schema `match` captures. */
    static void sweep(Map<String, Object> sch, List<String> errors) throws IOException {
        List<String> globs = new ArrayList<>();
        Map<String, Object> types = asMap(sch.get("types"));
        if (types != null) {
            for (Object t : types.values()) {
                String g = asStr(asMap(t) == null ? null : asMap(t).get("match"));
                if (g != null) globs.add(g);
            }
        }
        globs.addAll(asStrList(get(sch, "settings", "match")));
        globs.addAll(asStrList(get(sch, "mcp", "match")));

        for (String g : globs) {
            Path base = ROOT.resolve(g.substring(0, Math.max(0, g.indexOf('*'))))
                    .normalize();
            if (!Files.isDirectory(base)) {
                if (Files.isRegularFile(base)) {       // glob without `*`: settings.json
                    checkOne(sch, relative(base), readOrNull(base), errors);
                }
                continue;
            }
            Pattern re = glob(g);
            try (Stream<Path> walk = Files.walk(base)) {
                List<Path> hits = walk.filter(Files::isRegularFile)
                        .filter(f -> re.matcher(relative(f)).matches())
                        .sorted().collect(Collectors.toList());
                for (Path f : hits) checkOne(sch, relative(f), readOrNull(f), errors);
            }
        }
    }

    /** Validates one file. A path that no `match` captures exits silently. */
    static void checkOne(Map<String, Object> sch, String rel, String content,
                         List<String> errors) {
        if (content == null) return;

        checkInjections(sch, rel, content, errors);   // every visited file, any type
        checkArguments(sch, rel, content, errors);    // idem
        if (matches(asStr(get(sch, "skill_classes", "match")), rel)) {
            checkSkillBody(sch, rel, content, errors);   // class marker + required sections
        }
        if (matches(asStr(get(sch, "agent_classes", "match")), rel)) {
            checkAgentBody(sch, rel, content, errors);   // idem, plus the frontmatter a class owes
        }

        if (matchesAny(asStrList(get(sch, "settings", "match")), rel)) {
            checkSettings(sch, rel, content, errors);
            return;
        }

        if (matchesAny(asStrList(get(sch, "mcp", "match")), rel)) {
            checkMcp(asMap(sch.get("mcp")), rel, content, errors);
            return;
        }

        Map<String, Object> types = asMap(sch.get("types"));
        if (types == null) return;
        for (Map.Entry<String, Object> e : types.entrySet()) {
            Map<String, Object> t = asMap(e.getValue());
            if (t == null || !matches(asStr(t.get("match")), rel)) continue;
            checkFrontmatter(sch, e.getKey(), t, rel, content, errors);
            return;
        }
    }

    static final Pattern INJECTION = Pattern.compile("!`([^`\\n]+)`");
    /** A fenced block holds examples, not injections the runtime executes. */
    static final Pattern FENCE = Pattern.compile("(?s)```.*?```");
    /**
     * A double-backtick span is how markdown quotes something that itself contains a
     * backtick — which is exactly how prose has to write an injection when it talks about
     * one. Dropped before scanning, so documenting the rule doesn't break the rule.
     */
    static final Pattern TICK_SPAN = Pattern.compile("``.*?``");

    /**
     * Requires every `` !`command` `` injection to resolve its paths from the project
     * root instead of the shell's cwd. The injection runs in the session's persistent
     * shell: a `cd` in an earlier Bash call silently poisons every later one, and a
     * relative `test -f` then reports a file as absent while it exists — which is worse
     * than no information, because it arms the entry guard of the skill it belongs to.
     * lessons-learned-010 § 5: twelve occurrences across ten skills, one of them
     * `docker-architect` aborting on a file that was present and complete.
     * The required substring and the exemptions are data — `injections` in
     * .claude/schemas/extensions.json, never here (invariant 10).
     */
    static void checkInjections(Map<String, Object> sch, String rel, String content,
                                List<String> errors) {
        Map<String, Object> inj = asMap(sch.get("injections"));
        if (inj == null) return;                       // block absent: check is off
        String require = asStr(inj.get("require"));
        if (require == null || require.isEmpty()) return;
        // Only files a `types` match captures — a skill, an agent, or a rule. Everything
        // else the mode may be handed (a decision record, a lessons-learned) is prose
        // about injections, not a file the runtime executes them from.
        if (!typedFile(sch, rel)) return;

        List<Pattern> exempt = new ArrayList<>();
        for (String p : asStrList(inj.get("exempt_patterns"))) {
            try { exempt.add(Pattern.compile(p)); }
            catch (PatternSyntaxException e) {
                errors.add("  " + SCHEMA_FILE + " — injections.exempt_patterns has an"
                        + " invalid regex `" + p + "`: " + e.getDescription());
            }
        }

        String scanned = TICK_SPAN.matcher(FENCE.matcher(content).replaceAll(""))
                .replaceAll("");
        Matcher m = INJECTION.matcher(scanned);
        while (m.find()) {
            String cmd = m.group(1);
            if (cmd.contains(require)) continue;
            if (exempt.stream().anyMatch(p -> p.matcher(cmd).find())) continue;
            errors.add("  " + rel + ":" + lineOf(content, "!`" + cmd + "`")
                    + " — injection `" + shorten(cmd) + "` resolves paths from the shell's"
                    + " cwd. Use \"" + require + ":-.}/<path>\" — a `cd` in an earlier Bash"
                    + " call makes it report a file as absent while it exists."
                    + " Genuinely cwd-independent: add a regex to injections.exempt_patterns");
        }
    }

    /**
     * Rejects the argument marker written in prose. The runtime interpolates every
     * occurrence, not only the one under `## Target`: a sentence that *talks about* the
     * argument ("with empty $ARGUMENTS", "$ARGUMENTS empty") arrives at the model with the
     * real value substituted into it, and reads as the opposite of what it says —
     * lessons-learned-012 § 2, where `/new-feature` ended up ordering `test-architect`
     * into setup mode while naming the argument that means design mode. The interpolation
     * point itself is a line holding nothing but the marker, and that is the only
     * occurrence allowed. Marker, the field that makes a file eligible, the shape of the
     * interpolation line and the exemptions are data — `arguments` in
     * .claude/schemas/extensions.json, never here (invariant 10).
     */
    static void checkArguments(Map<String, Object> sch, String rel, String content,
                               List<String> errors) {
        Map<String, Object> arg = asMap(sch.get("arguments"));
        if (arg == null) return;                       // block absent: check is off
        String marker = asStr(arg.get("marker"));
        if (marker == null || marker.isEmpty()) return;
        if (!typedFile(sch, rel)) return;

        String needs = asStr(arg.get("requires_field"));
        if (needs != null && !needs.isEmpty()) {
            Map<String, String> fm = frontmatter(content);
            if (fm == null || !fm.containsKey(needs)) return;
        }

        List<Pattern> exempt = new ArrayList<>();
        for (String p : asStrList(arg.get("exempt_patterns"))) {
            try { exempt.add(Pattern.compile(p)); }
            catch (PatternSyntaxException e) {
                errors.add("  " + SCHEMA_FILE + " — arguments.exempt_patterns has an"
                        + " invalid regex `" + p + "`: " + e.getDescription());
            }
        }
        String point = asStr(arg.get("interpolation_line"));
        Pattern alone = Pattern.compile(point == null || point.isEmpty()
                ? "^[ \\t]*" + Pattern.quote(marker) + "[ \\t]*$" : point);

        // Same two escapes `checkInjections` grants, for the same reason: a fenced block
        // and a double-backtick span are how this repository documents the marker without
        // the runtime ever interpolating what it wrote.
        String scanned = TICK_SPAN.matcher(FENCE.matcher(content).replaceAll(""))
                .replaceAll("");
        // `\R`, not `\n`: a CRLF checkout (core.autocrlf on Windows) leaves a `\r` on
        // every line, and the anchored `[ \t]*$` would reject the bare marker line.
        for (String l : scanned.split("\\R", -1)) {
            if (!l.contains(marker) || alone.matcher(l).matches()) continue;
            if (exempt.stream().anyMatch(p -> p.matcher(l).find())) continue;
            errors.add("  " + rel + ":" + lineOf(content, l.strip()) + " — `" + marker
                    + "` written in prose. The runtime substitutes it here too, so the"
                    + " sentence reaches the model with the real argument inside it."
                    + " Write \"the argument\" or \"the target above\"; the only allowed"
                    + " occurrence is a line holding nothing else."
                    + " Genuinely safe: add a regex to arguments.exempt_patterns");
        }
    }

    /** True when some `types` entry's `match` captures this path. */
    static boolean typedFile(Map<String, Object> sch, String rel) {
        Map<String, Object> types = asMap(sch.get("types"));
        if (types == null) return false;
        for (Object t : types.values()) {
            Map<String, Object> m = asMap(t);
            if (m != null && matches(asStr(m.get("match")), rel)) return true;
        }
        return false;
    }

    /** 1-based line of `needle` in `content`, or 0 when it isn't there. */
    static int lineOf(String content, String needle) {
        int at = content.indexOf(needle);
        if (at < 0) return 0;
        int line = 1;
        for (int i = 0; i < at; i++) if (content.charAt(i) == '\n') line++;
        return line;
    }

    static String shorten(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 60 ? one : one.substring(0, 57) + "...";
    }

    static void checkFrontmatter(Map<String, Object> sch, String type,
                                 Map<String, Object> t, String rel, String content,
                                 List<String> errors) {
        Map<String, String> fm = frontmatter(content);
        if (fm == null) {
            errors.add("  " + rel + " — no frontmatter block closed by `---`"
                    + " (type '" + type + "')");
            return;
        }
        for (String d : strictYamlDefects(content)) {
            errors.add("  " + rel + ":" + d + " — a strict YAML parser refuses this frontmatter,"
                    + " and GitHub Copilot drops the file; Claude Code's lenient one reads it");
        }

        for (String req : asStrList(t.get("required"))) {
            if (!fm.containsKey(req)) {
                errors.add("  " + rel + " — missing required field `" + req + "`");
            }
        }

        List<String> allowed = asStrList(t.get("allowed"));
        List<String> forbidden = asStrList(sch.get("forbidden_everywhere"));
        for (String key : fm.keySet()) {
            if (forbidden.contains(key)) {
                errors.add("  " + rel + " — `" + key + "` is not a native field."
                        + " What it used to carry lives in the `## Contrato` section of the body");
                continue;
            }
            if (allowed.contains(key)) continue;
            String hint = nearest(key, allowed);
            errors.add("  " + rel + " — `" + key + "` is not a field of '" + type + "'"
                    + (hint != null
                        ? ". It is written `" + hint + "` (skills use kebab-case,"
                          + " agents use camelCase)"
                        : ". The runtime silently ignores it"));
        }

        Map<String, Object> enums = asMap(t.get("enum"));
        if (enums == null) return;
        for (Map.Entry<String, Object> e : enums.entrySet()) {
            String v = fm.get(e.getKey());
            List<String> ok = asStrList(e.getValue());
            if (v == null || v.isEmpty() || ok.contains(v)) continue;
            errors.add("  " + rel + " — `" + e.getKey() + ": " + v + "` invalid."
                    + " Values: " + String.join(", ", ok));
        }
    }

    /**
     * Validates the top-level keys and every hook registration of a settings file —
     * this repo's `.claude/settings.json` and the generated project's template, both
     * listed in extensions.json's `settings.match`.
     *
     * <p>What it catches is the set of mistakes the runtime accepts in silence: an
     * event name that exists nowhere, a `matcher` on an event that never reads one, a
     * `type` this repo has no handler for, a shell string written where the executable
     * goes, and a non-positive `timeout`. None of these fails at startup; the hook
     * simply never runs, or runs without the filter it appears to have. Every list
     * lives in extensions.json — invariant 10, @CLAUDE.md.
     */
    static void checkSettings(Map<String, Object> sch, String rel, String content,
                              List<String> errors) {
        Map<String, Object> root = asMap(Json.parse(content));
        if (root == null) { errors.add("  " + rel + " — invalid JSON"); return; }

        List<String> topAllowed = asStrList(get(sch, "settings", "allowed"));
        if (!topAllowed.isEmpty()) {
            for (String key : root.keySet()) {
                if (!topAllowed.contains(key)) {
                    errors.add("  " + rel + " — `" + key + "` is not a recognized top-level key");
                }
            }
        }

        // A plugin whose skills compete with the pipeline for automatic invocation stays off
        // in the project, whatever the developer enabled at user level. The list has one
        // owner; each settings file only carries it, and drifts the first time one is edited
        // alone. Design: .claude/decisions/0138-block-colliding-plugins-and-report-shadowed-skills.md
        Map<String, Object> plugins = asMap(root.get("enabledPlugins"));
        for (String id : asStrList(get(sch, "settings", "blocked_plugins"))) {
            if (plugins == null || !Boolean.FALSE.equals(plugins.get(id))) {
                errors.add("  " + rel + " — `enabledPlugins` lacks `\"" + id + "\": false`."
                        + " Its skills compete with this pipeline for automatic invocation"
                        + " (.claude/schemas/extensions.json › settings.blocked_plugins)."
                        + " Restore the entry; re-enable it for yourself in"
                        + " .claude/settings.local.json instead");
            }
        }

        Map<String, Object> hooks = asMap(root.get("hooks"));
        if (hooks == null) return;

        List<String> req = asStrList(get(sch, "settings", "hook_entry", "required"));
        List<String> allowed = asStrList(get(sch, "settings", "hook_entry", "allowed"));
        List<String> events = new ArrayList<>(asStrList(get(sch, "settings", "hook_events")));
        events.addAll(asStrList(get(sch, "settings", "hook_events_extra")));
        List<String> matcherEvents = asStrList(get(sch, "settings", "matcher_events"));
        List<String> groupAllowed = asStrList(get(sch, "settings", "group_allowed"));
        List<String> entryTypes = asStrList(get(sch, "settings", "entry_types"));
        List<String> badChars = asStrList(get(sch, "settings", "command_forbidden_chars"));

        for (Map.Entry<String, Object> ev : hooks.entrySet()) {
            String event = ev.getKey();
            String where = rel + " › " + event;

            if (!events.isEmpty() && !events.contains(event)) {
                errors.add("  " + rel + " — `" + event + "` is not a lifecycle event."
                        + " It never fires. Known events in"
                        + " .claude/schemas/extensions.json › settings.hook_events");
            }

            for (Object group : asList(ev.getValue())) {
                Map<String, Object> g = asMap(group);
                if (g == null) continue;

                for (String k : g.keySet()) {
                    if (!groupAllowed.isEmpty() && !groupAllowed.contains(k)) {
                        errors.add("  " + where + " — `" + k
                                + "` is not a hook group field");
                    }
                }
                if (g.containsKey("matcher") && !matcherEvents.isEmpty()
                        && !matcherEvents.contains(event)) {
                    errors.add("  " + where + " — `matcher` is ignored on this event."
                            + " It reads as a filter and filters nothing");
                }

                for (Object entry : asList(g.get("hooks"))) {
                    Map<String, Object> h = asMap(entry);
                    if (h == null) continue;
                    for (String r : req) {
                        if (!h.containsKey(r)) {
                            errors.add("  " + where + " — hook entry missing `" + r + "`");
                        }
                    }
                    for (String k : h.keySet()) {
                        if (!allowed.contains(k)) {
                            errors.add("  " + where + " — `" + k
                                    + "` is not a hook entry field");
                        }
                    }
                    checkHookEntry(where, h, entryTypes, badChars, errors);
                }
            }
        }
    }

    /** Type, exec form, and timeout of one hook entry. Lists come from extensions.json. */
    static void checkHookEntry(String where, Map<String, Object> h, List<String> entryTypes,
                               List<String> badChars, List<String> errors) {
        String type = asStr(h.get("type"));
        if (type != null && !entryTypes.isEmpty() && !entryTypes.contains(type)) {
            errors.add("  " + where + " — `type: " + type + "` has no handler here."
                    + " This repo runs command hooks only: "
                    + String.join(", ", entryTypes));
        }

        String command = asStr(h.get("command"));
        if (command != null) {
            for (String bad : badChars) {
                if (command.contains(bad)) {
                    errors.add("  " + where + " — `command` contains `" + bad
                            + "`: that is a shell string where the executable goes."
                            + " Exec form: `command` is the binary, `args` the arguments");
                    break;
                }
            }
        }

        Object timeout = h.get("timeout");
        if (timeout != null && (!(timeout instanceof Number) || num(timeout) <= 0)) {
            errors.add("  " + where + " — `timeout` must be a positive number of seconds");
        }
    }

    /**
     * Validates .mcp.json (and its project-bootstrap template): unknown top-level or
     * per-server fields, missing field the declared transport requires, a reserved or
     * malformed server name, and a literal secret in `headers`/`env` — invariant 11,
     * @CLAUDE.md. The field list lives in extensions.json's `mcp` block, same
     * single-owner discipline as the frontmatter tables above.
     */
    static void checkMcp(Map<String, Object> mcpSchema, String rel, String content,
                        List<String> errors) {
        if (mcpSchema == null) return;

        Map<String, Object> root = asMap(Json.parse(content));
        if (root == null) { errors.add("  " + rel + " — invalid JSON"); return; }

        List<String> rootAllowed = asStrList(mcpSchema.get("root_allowed"));
        for (String key : root.keySet()) {
            if (!rootAllowed.contains(key)) {
                errors.add("  " + rel + " — `" + key
                        + "` is not a valid top-level key. Only `mcpServers`");
            }
        }

        Map<String, Object> servers = asMap(root.get("mcpServers"));
        if (servers == null) return;

        List<String> serverAllowed = asStrList(mcpSchema.get("server_allowed"));
        Map<String, Object> requiredByType = asMap(mcpSchema.get("required_by_type"));
        String namePattern = asStr(mcpSchema.get("name_pattern"));
        Pattern nameRe = namePattern == null ? null : Pattern.compile(namePattern);
        List<String> reserved = asStrList(mcpSchema.get("reserved_names"));
        Map<String, Object> secretScan = asMap(mcpSchema.get("secret_scan"));
        List<String> secretFields = secretScan == null ? List.of() : asStrList(secretScan.get("fields"));
        Pattern keyRe = secretScan == null || asStr(secretScan.get("key_pattern")) == null
                ? null : Pattern.compile(asStr(secretScan.get("key_pattern")));
        List<String> valuePrefixes = secretScan == null
                ? List.of() : asStrList(secretScan.get("value_prefixes"));

        for (Map.Entry<String, Object> e : servers.entrySet()) {
            String name = e.getKey();
            Map<String, Object> server = asMap(e.getValue());
            if (server == null) {
                errors.add("  " + rel + " — server `" + name + "` is not an object");
                continue;
            }
            if (reserved.contains(name)) {
                errors.add("  " + rel + " — server name `" + name
                        + "` is reserved by the runtime");
            }
            if (nameRe != null && !nameRe.matcher(name).matches()) {
                errors.add("  " + rel + " — server name `" + name
                        + "` doesn't match `" + namePattern + "`");
            }
            for (String key : server.keySet()) {
                if (!serverAllowed.contains(key)) {
                    errors.add("  " + rel + " — server `" + name
                            + "` has unknown field `" + key + "`");
                }
            }

            String type = asStr(server.get("type"));
            if (type == null) {
                errors.add("  " + rel + " — server `" + name + "` is missing `type`");
            } else if (requiredByType != null && !requiredByType.containsKey(type)) {
                errors.add("  " + rel + " — server `" + name + "` has unknown `type: "
                        + type + "`. Allowed: " + String.join(", ", requiredByType.keySet()));
            } else if (requiredByType != null) {
                for (String req : asStrList(requiredByType.get(type))) {
                    if (!server.containsKey(req)) {
                        errors.add("  " + rel + " — server `" + name + "` (type `" + type
                                + "`) is missing required field `" + req + "`");
                    }
                }
            }

            for (String field : secretFields) {
                Map<String, Object> block = asMap(server.get(field));
                if (block == null) continue;
                for (Map.Entry<String, Object> kv : block.entrySet()) {
                    String value = asStr(kv.getValue());
                    if (value != null && looksLikeSecret(kv.getKey(), value, keyRe, valuePrefixes)) {
                        errors.add("  " + rel + " — server `" + name + "` field `" + field
                                + "." + kv.getKey() + "` looks like a literal secret."
                                + " Use `${VAR}` / `${VAR:-default}`, `oauth`, or"
                                + " `headersHelper` instead — invariant 11, @CLAUDE.md");
                    }
                }
            }
        }
    }

    /**
     * A value with no `${...}` expansion whose key name looks credential-shaped, or
     * whose value starts with a known token prefix, is a literal secret. Any `${`
     * anywhere in the value is treated as safe — "Bearer ${API_TOKEN}" is the
     * documented good pattern and must not be flagged alongside "Bearer abc123xyz".
     */
    static boolean looksLikeSecret(String key, String value, Pattern keyRe,
                                   List<String> valuePrefixes) {
        if (value.contains("${")) return false;
        boolean keySensitive = keyRe != null && keyRe.matcher(key).find();
        boolean valueSensitive = valuePrefixes.stream().anyMatch(value::startsWith);
        return keySensitive || valueSensitive;
    }

    static boolean matchesAny(List<String> globPatterns, String rel) {
        for (String g : globPatterns) if (matches(g, rel)) return true;
        return false;
    }

    /**
     * Top-level frontmatter keys and their scalar value, in file order. Returns
     * null when there is no closed `---` block. No YAML library: only unindented
     * keys matter. `#` comments are ignored — two rules in this repo use them
     * inside frontmatter.
     */
    static Map<String, String> frontmatter(String content) {
        List<String> lines = content.lines().collect(Collectors.toList());
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (l.isEmpty()) continue;
            if (l.equals("---")) start = i;
            break;
        }
        if (start < 0) return null;

        Map<String, String> fm = new LinkedHashMap<>();
        for (int i = start + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.strip().equals("---")) return fm;
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            if (Character.isWhitespace(raw.charAt(0)) || raw.startsWith("-")) continue;
            int c = raw.indexOf(':');
            if (c <= 0) continue;
            String v = raw.substring(c + 1).strip();
            if (v.startsWith(">") || v.startsWith("|")) v = "";   // block scalar
            fm.put(raw.substring(0, c).strip(), v);
        }
        return null;                                  // block opened and never closed
    }

    static final Pattern YAML_KEY = Pattern.compile("([A-Za-z0-9_][A-Za-z0-9_.-]*)[ \\t]*:(?:[ \\t]+(.*))?");
    static final Pattern YAML_BLOCK_HEADER =
            Pattern.compile("[|>](?:[+-]?[1-9]?|[1-9][+-])(?:[ \\t]+#.*)?");
    static final String YAML_ESCAPES = "0abtnvfre \"/\\N_LP\t";

    /**
     * Where the frontmatter leaves the YAML a strict parser accepts, as {@code <line> — <why>},
     * the line counted in the file. {@link #frontmatter} reads names only and Claude Code's own
     * parser forgives, so a `description` holding {@code ": "} unquoted loads there and is
     * dropped by GitHub Copilot's — `mapping values are not allowed in this context`. Checked: a
     * tab in indentation, a key at an indentation no open mapping has, a key twice in one
     * mapping, a plain value (or its continuation line) holding {@code ": "} or ending in
     * {@code :}, a plain value starting with a reserved or structural indicator, a quoted value
     * not closed on its line or followed by anything but a comment, an unknown escape in a
     * double-quoted one, a malformed block-scalar header, a flow collection not closed on its
     * line. What it reads is a subset of YAML: anchors, aliases, tags, complex keys and quoted
     * values spanning lines are refused as outside it — the tree has none. Invoked by
     * {@code schema} through {@link #checkFrontmatter}, on every Pre/PostToolUse of an extension
     * file and in CI. The closest rejected form, requiring only {@code description} to be quoted,
     * covers one field of the many a colon breaks. Design:
     * `.claude/decisions/0174-strict-frontmatter-qualified-names-and-the-local-build.md`.
     */
    static List<String> strictYamlDefects(String content) {
        List<String> lines = content.lines().collect(Collectors.toList());
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < lines.size() && lines.get(start).isBlank()) start++;
        List<Integer> indents = new ArrayList<>();          // open mappings, innermost last
        List<Set<String>> keys = new ArrayList<>();
        int open = -1;        // indent of the `key:` (or `-`) whose nested block comes next
        int block = -1;       // indent owning a `|`/`>` block scalar being skipped
        int plain = -1;       // indent owning a plain value whose continuation lines follow
        for (int i = start + 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.strip().equals("---")) break;
            String at = " line " + (i + 1) + " — ";
            int ind = 0;
            while (ind < raw.length() && (raw.charAt(ind) == ' ' || raw.charAt(ind) == '\t')) {
                if (raw.charAt(ind) == '\t' && !raw.isBlank()) {
                    out.add(at + "a tab in the indentation; YAML indents with spaces only");
                    return out;
                }
                ind++;
            }
            if (raw.isBlank()) continue;
            if (block >= 0) {
                if (ind > block) continue;
                block = -1;
            }
            String text = raw.substring(ind);
            if (text.startsWith("#")) continue;
            if (plain >= 0) {
                if (ind > plain && open < 0) {
                    String why = plainDefect(cutComment(text), true);
                    if (why != null) out.add(at + why + " (continuation of the value above)");
                    continue;
                }
                plain = -1;
            }
            int col = ind;
            while (text.startsWith("- ") || text.equals("-")) {   // sequence entries, `- - x`
                int skip = text.equals("-") ? 1 : 2;
                while (skip < text.length() && text.charAt(skip) == ' ') skip++;
                text = text.substring(Math.min(skip, text.length()));
                col += skip;
                open = -1;
                if (text.isEmpty()) { open = col - skip; break; }
                Matcher km = YAML_KEY.matcher(text);
                if (km.matches()) {                       // `- key: v` opens a mapping per entry
                    while (!indents.isEmpty() && indents.get(indents.size() - 1) >= col) {
                        indents.remove(indents.size() - 1);
                        keys.remove(keys.size() - 1);
                    }
                    indents.add(col);
                    keys.add(new HashSet<>());
                    open = -2;                            // the key below lands in that mapping
                }
                break;
            }
            if (text.isEmpty()) continue;
            Matcher km = YAML_KEY.matcher(text);
            if (km.matches()) {
                if (open == -2) {
                    open = -1;
                } else if (open >= 0 && col > open) {
                    indents.add(col);
                    keys.add(new HashSet<>());
                    open = -1;
                } else {
                    open = -1;
                    while (!indents.isEmpty() && indents.get(indents.size() - 1) > col) {
                        indents.remove(indents.size() - 1);
                        keys.remove(keys.size() - 1);
                    }
                    if (indents.isEmpty() && col == 0) {
                        indents.add(0);
                        keys.add(new HashSet<>());
                    } else if (indents.isEmpty() || indents.get(indents.size() - 1) != col) {
                        out.add(at + "the key `" + km.group(1) + "` is indented to a column no"
                                + " open mapping has");
                        continue;
                    }
                }
                if (!keys.get(keys.size() - 1).add(km.group(1))) {
                    out.add(at + "the key `" + km.group(1) + "` appears twice in one mapping");
                }
                String v = km.group(2) == null ? "" : km.group(2).strip();
                if (v.isEmpty() || v.startsWith("#")) { open = col; continue; }
                String why = valueDefect(v);
                if (why != null) out.add(at + "`" + km.group(1) + "` — " + why);
                if (v.startsWith("|") || v.startsWith(">")) block = col;
                else if (!"\"'[{".contains(v.substring(0, 1))) plain = col;
                continue;
            }
            if (col == ind) {                              // neither `key:` nor `- entry`
                out.add(at + "a line that is neither `key: value` nor a `- ` list entry");
                continue;
            }
            String why = valueDefect(text);                // a scalar list entry
            if (why != null) out.add(at + "list entry — " + why);
            if (text.startsWith("|") || text.startsWith(">")) block = ind;
            else if (!"\"'[{".contains(text.substring(0, 1))) plain = ind;
        }
        return out;
    }

    /** Why one value, as written after `key: ` or `- `, is not YAML a strict parser reads, or null. */
    static String valueDefect(String v) {
        char c = v.charAt(0);
        if (c == '"' || c == '\'') {
            int j = 1;
            while (j < v.length()) {
                char x = v.charAt(j);
                if (c == '"' && x == '\\') {
                    if (j + 1 >= v.length()) return "a `\\` at the end of a double-quoted value";
                    char e = v.charAt(j + 1);
                    int hex = e == 'x' ? 2 : e == 'u' ? 4 : e == 'U' ? 8 : 0;
                    if (hex == 0 && YAML_ESCAPES.indexOf(e) < 0) {
                        return "`\\" + e + "` is no YAML escape — write `\\\\` for a backslash,"
                                + " or quote the value with `'`";
                    }
                    j += 2 + hex;
                    continue;
                }
                if (x == c) {
                    if (c == '\'' && j + 1 < v.length() && v.charAt(j + 1) == '\'') { j += 2; continue; }
                    String rest = v.substring(j + 1);
                    if (rest.isBlank() || rest.matches("[ \\t]+#.*")) return null;
                    return "text after the closing quote — quote the whole value";
                }
                j++;
            }
            return "a quoted value not closed on its line — close it there, or use a `>` block";
        }
        if (c == '|' || c == '>') {
            return YAML_BLOCK_HEADER.matcher(v).matches() ? null
                    : "`" + v + "` is no block-scalar header (`|`, `>`, `>-`, `|+` …)";
        }
        if (c == '[' || c == '{') {
            char close = c == '[' ? ']' : '}';
            int depth = 0;
            char q = 0;
            for (int j = 0; j < v.length(); j++) {
                char x = v.charAt(j);
                if (q != 0) { if (x == q) q = 0; continue; }
                if (x == '"' || x == '\'') q = x;
                else if (x == '[' || x == '{') depth++;
                else if (x == ']' || x == '}') {
                    if (--depth == 0) {
                        if (x != close) return "a flow collection closed with the wrong bracket";
                        String rest = v.substring(j + 1);
                        return rest.isBlank() || rest.matches("[ \\t]+#.*") ? null
                                : "text after the closing `" + close + "`";
                    }
                }
            }
            return "a flow collection not closed on its line";
        }
        return plainDefect(cutComment(v), false);
    }

    /** Why an unquoted value is not a YAML plain scalar, or null. */
    static String plainDefect(String v, boolean continuation) {
        if (v.isEmpty()) return null;
        char c = v.charAt(0);
        if (!continuation) {
            if (c == '`') return "a backtick cannot start an unquoted value — quote the value";
            if (c == '@') return "`@` cannot start an unquoted value — quote the value";
            if ("&*!%".indexOf(c) >= 0) {
                return "anchors, aliases, tags and directives (`" + c + "`) are outside what"
                        + " `schema` reads — quote the value";
            }
            if (",]}".indexOf(c) >= 0) return "`" + c + "` cannot start an unquoted value — quote the value";
            if ((c == '?' || c == ':' || c == '-') && (v.length() == 1 || v.charAt(1) == ' ')) {
                return "`" + c + " ` cannot start an unquoted value — quote the value";
            }
        }
        if (v.contains(": ") || v.endsWith(":")) {
            return "`: ` inside an unquoted value (\"mapping values are not allowed in this"
                    + " context\") — quote the value with `\"`, or fold it with `>`";
        }
        return null;
    }

    /** An unquoted value up to its ` #` comment, stripped. */
    static String cutComment(String v) {
        Matcher m = Pattern.compile("[ \\t]#").matcher(v);
        return (m.find() ? v.substring(0, m.start()) : v).strip();
    }

    /** The allowed field that only differs in naming convention, or null. */
    static String nearest(String key, List<String> allowed) {
        String n = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (String a : allowed) {
            if (a.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").equals(n)) {
                return a;
            }
        }
        return null;
    }

    static boolean matches(String globPattern, String rel) {
        return globPattern != null && glob(globPattern).matcher(rel).matches();
    }

    /** Glob to regex: `**` crosses `/`, `*` does not. */
    static Pattern glob(String g) {
        StringBuilder b = new StringBuilder("^");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') { b.append(".*"); i++; }
                else b.append("[^/]*");
            } else if (c == '?') {
                b.append("[^/]");
            } else {
                b.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(b.append("$").toString());
    }

    static String readOrNull(Path p) {
        try { return Files.isRegularFile(p)
                ? Files.readString(p, StandardCharsets.UTF_8) : null; }
        catch (IOException e) { return null; }
    }

    // ── audit ────────────────────────────────────────────────────────────────
    //
    // Execution trail of every skill and agent of this project — any name that resolves to
    // `.claude/skills/<n>/SKILL.md` or `.claude/agents/<n>.md` (`isAudited`), minus the
    // observers `isAuditExcluded` names. It is a hook and not a skill because the record
    // has to survive the model forgetting, the session dying, and the user pressing
    // Ctrl+C. A skill can promise that; only a lifecycle event delivers it.
    // decisions/0035 (hook), 0038 (every piece, not only orchestrators).
    //
    // Phases (args[1]), one per hook event:
    //   prompt    UserPromptSubmit               opens a run when the prompt is `/<skill>` of this project
    //   call      PreToolUse Skill|Task|Agent    a node of the open run; with no run open, opens one (origin: model)
    //   ask       PreToolUse AskUserQuestion     the run starts waiting for the user
    //   answer    PostToolUse AskUserQuestion    the wait ends — measured apart from work;
    //                                            each question and its answer kept, redacted
    //   file      PostToolUse Write|Edit         file touched — feeds rule inference by glob
    //   fail      PostToolUseFailure             rework counter
    //   stopfail  StopFailure                    the turn ended in error
    //   perm      PermissionRequest|Denied       permission asked for mid-run
    //   agent     SubagentStop                   closes the current agent node
    //   compact   PreCompact                     manual `/compact` or automatic overflow
    //   flush     Stop                           marks the turn's end, rewrites the report
    //   close     SessionEnd                     stamps the final status and closes the run
    //
    // Every phase appends one line to .claude/audit-usage/.state/<session>.ndjson, and
    // the report is DERIVED from that log at flush time. Append-only survives a kill -9;
    // a read-modify-write of a structured file does not.
    //
    // A run closes at the first user prompt after it opened. Answers to AskUserQuestion
    // arrive as tool results, never as prompts, so any prompt is work outside the run —
    // counting it stretched a 12-second step to ten minutes in a real report.
    //
    // Inside a git worktree the trail is written to the MAIN checkout's
    // .claude/audit-usage/, so reports, history.jsonl and nodes.jsonl never diverge
    // between checkouts.
    //
    // Switched off by the absence of .claude/audit-usage/: every phase returns
    // immediately. That is why this meta-repository, which does not create the
    // directory, pays nothing for a mode wired only into the generated project. Its
    // entries stay out of this repo's settings.json on purpose: here they would audit
    // the design of the tool instead of its use (decisions/0035).

    static final String AUDIT_DIR = ".claude/audit-usage";

    /** How far back "recent" reaches: the runs `audit summary` lists. */
    static final int AUDIT_RECENT_RUNS = 15;

    /**
     * The trail's directory. In a git worktree `.git` is a file, and the trail belongs to
     * the main checkout — one history.jsonl, one nodes.jsonl, whichever checkout ran.
     * Everywhere else, and whenever git can't answer, it's this project's own.
     */
    static Path auditDir() {
        if (!Files.isRegularFile(ROOT.resolve(".git"))) return ROOT.resolve(AUDIT_DIR);
        try {
            Proc p = run(gitCmd(), "rev-parse", "--git-common-dir");
            if (p.exit == 0 && !p.out.isEmpty()) {
                Path common = ROOT.resolve(p.out.get(0).strip()).normalize();
                if (common.getFileName() != null && ".git".equals(common.getFileName().toString())) {
                    return common.getParent().resolve(AUDIT_DIR);
                }
            }
        } catch (Exception ignored) { }
        return ROOT.resolve(AUDIT_DIR);
    }

    static void audit(String phase, String stdin) throws Exception {
        Path dir = auditDir();
        if (!Files.isDirectory(dir)) return;
        if ("summary".equals(phase)) { auditSummary(dir); return; }

        Object in = Json.parse(stdin);
        String session = asStr(get(in, "session_id"));
        if (session == null || session.isBlank()) session = "unknown";
        Path log = dir.resolve(".state").resolve(session.replaceAll("[^A-Za-z0-9_-]", "_") + ".ndjson");

        switch (phase) {
            case "prompt"   -> auditPrompt(dir, log, in);
            case "call"     -> auditCall(log, in);
            case "file"     -> auditFile(log, in);
            case "ask"      -> append(log, ev("ask"));
            case "answer"   -> auditAnswer(log, in);
            case "fail"     -> append(log, ev("fail", "tool", asStr(get(in, "tool_name"))));
            case "stopfail" -> append(log, ev("stop_fail"));
            case "perm"     -> append(log, ev("perm",
                                       "tool", asStr(get(in, "tool_name")),
                                       "event", asStr(get(in, "hook_event_name"))));
            case "agent"    -> append(log, ev("agent_end",
                                       "name", asStr(get(in, "agent_type")),
                                       "agent_id", asStr(get(in, "agent_id"))));
            case "compact"  -> append(log, ev("compact", "trigger", asStr(get(in, "trigger"))));
            case "flush"    -> { append(log, ev("stop")); auditRender(dir, log, in, false); }
            case "close"    -> auditRender(dir, log, in, true);
            default         -> { }
        }
    }

    /**
     * A prompt ends the run in progress, and opens one when it is `/<name>` for a skill
     * of this project. Which skills qualify is data, not code: a folder under
     * `.claude/skills/` is enough, so a skill written later is audited without this file
     * being touched — @CLAUDE.md invariant 7. A prompt that opens nothing is still kept,
     * overwritten each turn: when the model then invokes a skill or agent on its own,
     * `auditCall` opens the run and needs the words that led to it.
     */
    static void auditPrompt(Path dir, Path log, Object in) throws Exception {
        String prompt = asStr(get(in, "prompt"));
        if (prompt == null) return;

        // The harness injects some prompts of its own while the run that launched a
        // background agent is still open: the agent's `<task-notification>`, and its report
        // as an `<agent-message>` hand-back. Neither is user intent to end anything. Closing
        // on the first froze `/test-architect setup`'s and `/new-feature`'s reports
        // mid-flight (0041); closing on the second ended every background `/new-feature`
        // run at the executor's hand-back, so the `git-publish` after it reached no report
        // (0119). Neither close, nor open, nor overwrite the observer prompt file — let the
        // run keep absorbing the agent's remaining `file`/`agent_end` events. The shapes are
        // data, `audit.harness_prompts` in extensions.json (invariant 10).
        // .claude/decisions/0041-audit-background-subagent-tracking.md
        // .claude/decisions/0119-audit-ignores-subagent-handback-executor-single-report.md
        if (isHarnessPrompt(prompt)) return;

        Matcher m = Pattern.compile("^\\s*/([a-z0-9][a-z0-9-]*)(.*)$", Pattern.DOTALL)
                .matcher(prompt);
        String name = m.find() ? m.group(1) : null;

        // An observer closes the run in progress and starts none of its own. Closing
        // already happened anyway (any second `/command` closes), so what this drops is
        // the empty report an observer would leave behind — and, for `/audit-usage`, the
        // feedback loop of a report about reading reports. The close is the useful half:
        // within one session a report is stuck at "in progress" until something ends
        // the run, so asking for it is what finalizes it.
        if (name != null && isAuditExcluded(dir, "skill", name)) {
            auditRender(dir, log, in, true);
            return;
        }
        // Any prompt ends the run in progress — a second `/command`, or plain text. An
        // AskUserQuestion answer is a tool result, not a prompt, so it never gets here.
        if (Files.isRegularFile(log)) auditRender(dir, log, in, true);

        String stripped = prompt.strip();
        String redacted = redact(stripped);
        String[] said = {
                "prompt", redacted,
                "prompt_sha256", sha256(prompt),
                "redacted", String.valueOf(!redacted.equals(stripped))};

        if (name == null || !isAuditedSkill(name)) {
            Files.createDirectories(log.getParent());
            Files.writeString(promptFile(log), ev("prompt", said) + "\n", StandardCharsets.UTF_8);
            return;
        }
        openRun(log, name, "skill", "user", m.group(2).strip(), said, null, null);
    }

    /** The last prompt of the session that opened no run. Lives in `.state/`, gitignored. */
    static Path promptFile(Path log) {
        return log.resolveSibling(log.getFileName().toString().replace(".ndjson", ".prompt.json"));
    }

    static void openRun(Path log, String name, String kind, String origin, String args,
                        String[] said, String toolUseId, String inAgent) throws IOException {
        Files.createDirectories(log.getParent());
        Files.writeString(log, "", StandardCharsets.UTF_8);
        List<String> kv = new ArrayList<>(List.of("skill", name, "kind", kind, "origin", origin));
        // `args` is redacted too, not just `prompt`: it is a slice of the same text and
        // it is what the report puts in the title. Redacting one and not the other
        // leaks the secret in the most visible line of the file.
        kv.add("args");        kv.add(args == null ? null : redact(args));
        kv.addAll(Arrays.asList(said));
        kv.add("tool_use_id"); kv.add(toolUseId);
        kv.add("in_agent");    kv.add(inAgent);
        kv.add("head");        kv.add(gitShort());
        kv.add("perm_before"); kv.add(String.join(" ", localPermissions()));
        append(log, ev("run_start", kv.toArray(new String[0])));
    }

    /**
     * Pieces that observe instead of producing work — `/audit-usage` reads the trail,
     * `/arch-doctor` reads the setup. Decided by the piece's class, never by a list: a class
     * in `skill_classes` (or `agent_classes`) that declares `audited: false` leaves no
     * report, and every piece it lists inherits that. The class was already the single owner
     * of which skills are observers; a second list naming the same two skills
     * (`audit.exclude_skills`) could drift from it in silence — a new observer registered in
     * its class and not in the list would have written a report about reading reports.
     * Same single-owner discipline as `redact` (invariants 2 and 10).
     * Design: decisions/0086-audit-exclusion-owned-by-the-class.md
     *
     * Three levels, first boolean wins: the piece's own `overrides.<name>.audited` in its
     * class (fixed upstream — `arch-adopt` is never audited, whatever the project says),
     * then the project's `audit.project_overrides` file in the trail directory (the one
     * thing about `.claude/` a generated project sets for itself, never written by
     * `export`), then the class flag. Design: decisions/0111-audit-opt-out-owned-by-the-project.md
     */
    static boolean isAuditExcluded(Path dir, String kind, String name) {
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        if (sch == null || name == null) return false;
        boolean agent = "agent".equals(kind);
        String group = agent ? "agent_classes" : "skill_classes";
        String cls = agent ? agentClassOf(sch, name) : skillClassOf(sch, name);
        if (cls != null && get(sch, group, "classes", cls, "overrides", name, "audited") instanceof Boolean b) {
            return !b;
        }
        if (get(auditProjectOverrides(sch, dir), agent ? "agents" : "skills", name) instanceof Boolean b) {
            return !b;
        }
        return cls != null && Boolean.FALSE.equals(get(sch, group, "classes", cls, "audited"));
    }

    /** The project's own audited/not-audited map, or null when the project wrote none. */
    static Object auditProjectOverrides(Map<String, Object> sch, Path dir) {
        String file = asStr(get(sch, "audit", "project_overrides"));
        return file == null ? null : Json.parse(readOrNull(dir.resolve(file)));
    }

    /**
     * What `doctor` says about the project's override file: null when there is none, else
     * the problems (empty when valid) and, as the last element, the summary of what it sets.
     * Strict on purpose: the file may change only which pieces are audited, and a misspelled
     * name would otherwise switch nothing, in silence.
     */
    static List<String> auditOverrideProblems(Map<String, Object> sch, Path dir) {
        String file = asStr(get(sch, "audit", "project_overrides"));
        if (file == null || !Files.isRegularFile(dir.resolve(file))) return null;
        List<String> out = new ArrayList<>();
        Map<String, Object> root = asMap(Json.parse(readOrNull(dir.resolve(file))));
        if (root == null) {
            out.add(file + " is not a JSON object");
            out.add("");
            return out;
        }
        List<String> set = new ArrayList<>();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            boolean agent = "agents".equals(e.getKey());
            if (!agent && !"skills".equals(e.getKey())) {
                out.add("`" + e.getKey() + "` — only `skills` and `agents` are allowed");
                continue;
            }
            Map<String, Object> pieces = asMap(e.getValue());
            if (pieces == null) {
                out.add("`" + e.getKey() + "` must map a name to true or false");
                continue;
            }
            String group = agent ? "agent_classes" : "skill_classes";
            for (Map.Entry<String, Object> p : pieces.entrySet()) {
                String n = p.getKey();
                String rel = agent ? ".claude/agents/" + n + ".md" : ".claude/skills/" + n + "/SKILL.md";
                String cls = agent ? agentClassOf(sch, n) : skillClassOf(sch, n);
                if (!(p.getValue() instanceof Boolean on)) {
                    out.add("`" + n + "` must be true or false");
                } else if (!Files.isRegularFile(ROOT.resolve(rel))) {
                    out.add("`" + n + "` — no " + rel + " in this project");
                } else if (cls != null && get(sch, group, "classes", cls, "overrides", n, "audited") != null) {
                    out.add("`" + n + "` is fixed by " + group + ".classes." + cls + ".overrides." + n
                            + ".audited — remove it from " + file);
                } else {
                    set.add(n + (on ? " on" : " off"));
                }
            }
        }
        out.add(set.isEmpty() ? "" : String.join(", ", set));
        return out;
    }

    /**
     * A piece is audited when its name resolves to a file of this project. A plugin skill
     * (`plugin:name`) or a runtime agent (`Explore`, `general-purpose`) has no such file
     * and falls out by construction — no list of exclusions to keep in sync.
     */
    static final Pattern PIECE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /**
     * True when the prompt is one the harness injected, not one the user typed — a shape in
     * `audit.harness_prompts` of extensions.json, matched with `find()`. A pattern that does
     * not compile matches nothing: a bad entry must not stop the hook.
     */
    static boolean isHarnessPrompt(String prompt) {
        for (String p : asStrList(get(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))),
                "audit", "harness_prompts"))) {
            try { if (Pattern.compile(p).matcher(prompt).find()) return true; }
            catch (Exception ignored) { }
        }
        return false;
    }

    static boolean isAuditedSkill(String name) {
        return name != null && PIECE_NAME.matcher(name).matches()
                && Files.isRegularFile(ROOT.resolve(".claude/skills").resolve(name).resolve("SKILL.md"));
    }

    static boolean isAuditedAgent(String name) {
        return name != null && PIECE_NAME.matcher(name).matches()
                && Files.isRegularFile(ROOT.resolve(".claude/agents").resolve(name + ".md"));
    }

    static boolean isAudited(String kind, String name) {
        return "agent".equals(kind) ? isAuditedAgent(name) : isAuditedSkill(name);
    }

    /**
     * A Skill or Agent call. Inside an open run it is a node. With no run open, and when
     * the piece belongs to this project, it opens one: the model invoked it on its own —
     * after a plain prompt, or chained by another piece's instructions — and that is the
     * invocation `auditPrompt` never sees. `tool_use_id` ties an agent node to its own
     * transcript; `agent_id` is present only when the call comes from inside a subagent.
     */
    static void auditCall(Path log, Object in) throws IOException {
        Map<String, Object> ti = asMap(get(in, "tool_input"));
        if (ti == null) return;
        String tool = asStr(get(in, "tool_name"));
        boolean skill = "Skill".equals(tool);
        if (!skill && !"Task".equals(tool) && !"Agent".equals(tool)) return;
        String kind = skill ? "skill" : "agent";
        String name = asStr(ti.get(skill ? "skill" : "subagent_type"));
        String toolUseId = asStr(get(in, "tool_use_id"));
        String inAgent = asStr(get(in, "agent_id"));

        if (!Files.isRegularFile(log)) {
            if (!isAudited(kind, name) || isAuditExcluded(log.getParent().getParent(), kind, name)) return;
            Object said = Json.parse(readOrNull(promptFile(log)));
            // `turn_t`: the model message that decided to call this piece was written before
            // PreToolUse fired. Its tokens belong to the run, so they are counted from the prompt.
            String[] kv = said == null ? new String[0] : new String[] {
                    "turn_t", String.valueOf(num(get(said, "t"))),
                    "prompt", asStr(get(said, "prompt")),
                    "prompt_sha256", asStr(get(said, "prompt_sha256")),
                    "redacted", asStr(get(said, "redacted"))};
            openRun(log, name, kind, "model", asStr(ti.get(skill ? "args" : "description")),
                    kv, toolUseId, inAgent);
            return;
        }
        if (skill) {
            String args = asStr(ti.get("args"));
            append(log, ev("skill", "name", name, "args", args == null ? null : redact(args),
                    "tool_use_id", toolUseId, "in_agent", inAgent));
        } else {
            append(log, ev("agent",
                    "name", name,
                    "detail", asStr(ti.get("description")),
                    "model", asStr(ti.get("model")),
                    "tool_use_id", toolUseId, "in_agent", inAgent));
        }
    }

    static void auditFile(Path log, Object in) {
        String f = asStr(get(in, "tool_input", "file_path"));
        if (f == null) return;
        append(log, ev("file", "op", asStr(get(in, "tool_name")),
                "path", relative(Paths.get(f))));
    }

    /**
     * Ends the wait, then keeps what was asked and what was answered — one `asked` event per
     * question, each field through {@link #clip}. Registered on `PostToolUse AskUserQuestion`,
     * the event whose `tool_response` carries both the questions and the `answers` map keyed
     * by question text. A lessons-learned file needs exactly this and it is what a compaction
     * summary loses first; before this the trail kept only the wait's timestamps and the
     * questions had to be dug out of the raw transcript (lessons-learned-016 § 12). Kept in
     * the existing mode rather than a new one: the registration already fired on this event.
     * Design: decisions/0094.
     */
    static void auditAnswer(Path log, Object in) {
        append(log, ev("answer"));
        Object resp = get(in, "tool_response");
        List<Object> qs = asList(get(resp, "questions"));
        if (qs == null || qs.isEmpty()) qs = asList(get(in, "tool_input", "questions"));
        if (qs == null) return;
        Map<String, Object> answers = asMap(get(resp, "answers"));
        for (Object q : qs) {
            String text = asStr(get(q, "question"));
            if (text == null) continue;
            Object a = answers == null ? null : answers.get(text);
            append(log, ev("asked", "header", clip(asStr(get(q, "header"))),
                    "q", clip(text), "a", clip(a == null ? "—" : String.valueOf(a))));
        }
    }

    /** Redacted, table-safe, at most 160 chars — every free text the report carries goes through here. */
    static String clip(String text) {
        if (text == null) return null;
        String red = redact(text.strip()).replace('\n', ' ').replace('|', '/').replace('`', '\'');
        return red.length() > 160 ? red.substring(0, 159) + "…" : red;
    }

    /** One NDJSON event. A null value is dropped instead of written as "null". */
    static String ev(String name, String... kv) {
        StringBuilder b = new StringBuilder("{\"t\":").append(System.currentTimeMillis())
                .append(",\"iso\":\"").append(Instant.now()).append('"')
                .append(",\"e\":\"").append(name).append('"');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] == null) continue;
            b.append(",\"").append(kv[i]).append("\":\"")
             .append(jsonEscape(kv[i + 1])).append('"');
        }
        return b.append('}').toString();
    }

    static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> {
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** Appends to an open run. No open run, no record — that is the off switch. */
    static void append(Path log, String line) {
        if (!Files.isRegularFile(log)) return;
        try {
            Files.writeString(log, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /**
     * Blanks anything credential-shaped before the prompt reaches a versioned file.
     * The patterns live in extensions.json's `audit.redact` block, never here — same
     * single-owner discipline as every other list this hook reads (invariant 10). A
     * secret pasted into a prompt and committed is irreversible; invariant 11 exists
     * for exactly this, and `.claude/audit-usage/` is versioned on purpose.
     */
    static String redact(String text) {
        Map<String, Object> r = asMap(get(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))),
                "audit", "redact"));
        if (r == null) return text;
        String out = text;
        for (String p : asStrList(r.get("patterns"))) {
            try { out = Pattern.compile(p).matcher(out).replaceAll("$1[REDACTED]"); }
            catch (Exception ignored) { }
        }
        for (String prefix : asStrList(r.get("value_prefixes"))) {
            out = Pattern.compile(Pattern.quote(prefix) + "\\S+").matcher(out)
                    .replaceAll(Matcher.quoteReplacement(prefix + "[REDACTED]"));
        }
        return out;
    }

    static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.substring(0, 16);
        } catch (Exception e) { return "?"; }
    }

    static String gitShort() {
        try {
            Proc p = run(gitCmd(), "rev-parse", "--short", "HEAD");
            return p.exit == 0 && !p.out.isEmpty() ? p.out.get(0).strip() : null;
        } catch (Exception e) { return null; }
    }

    /**
     * `permissions.*` of settings.local.json — where a rule granted mid-session lands.
     * Diffing this file before and after is the only observation of "permission added"
     * that does not depend on an undocumented hook payload.
     */
    static List<String> localPermissions() {
        Map<String, Object> p = asMap(get(Json.parse(
                readOrNull(ROOT.resolve(".claude/settings.local.json"))), "permissions"));
        if (p == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String k : List.of("allow", "ask", "deny")) {
            for (String v : asStrList(p.get(k))) out.add(k + ": " + v);
        }
        return out;
    }

    /** `detail`: a skill's args, an agent's model override — the Chain shows it. `description`: an agent call's own label, which names the group of a chained executor (0132). */
    record Node(String kind, String name, String detail, long start, int depth,
                String toolUseId, String inAgent, String description) {}

    /**
     * One assistant message: usage (input, output, cache read, cache write), the tools it
     * called and, index for index, what each call aimed at — see {@link #toolTarget}.
     */
    record Turn(long t, String model, long[] u, List<String> tools, List<String> targets) {
        /** What the request carried: input + cache read + cache write. */
        long context() { return u[0] + u[2] + u[3]; }
    }

    /** Token usage summed per model — a subagent may run on another model, and the report names each. */
    static final class Usage {
        final Map<String, long[]> byModel = new LinkedHashMap<>();

        void add(String model, long[] u) {
            long[] a = byModel.computeIfAbsent(model == null ? "?" : model, k -> new long[4]);
            for (int i = 0; i < 4; i++) a[i] += u[i];
        }
        void addAll(Usage o) { if (o != null) o.byModel.forEach(this::add); }
        long sum(int i) { return byModel.values().stream().mapToLong(a -> a[i]).sum(); }
        long in()         { return sum(0); }
        long out()        { return sum(1); }
        long cacheRead()  { return sum(2); }
        long cacheWrite() { return sum(3); }
        long billable()    { return in() + out() + cacheWrite(); }
        long contextRead() { return in() + cacheRead(); }
        String model() { return byModel.isEmpty() ? null : String.join(", ", byModel.keySet()); }
    }

    /**
     * Rewrites the report from the event log, from scratch, every time. Idempotent by
     * construction: an execution killed halfway keeps the last flush, marked
     * "in progress", instead of leaving nothing behind.
     */
    static void auditRender(Path dir, Path log, Object in, boolean closing) throws Exception {
        if (!Files.isRegularFile(log)) return;
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            Map<String, Object> e = asMap(Json.parse(line));
            if (e != null && asStr(e.get("e")) != null) events.add(e);
        }
        if (events.isEmpty()) return;
        Map<String, Object> s0 = events.get(0);
        if (!"run_start".equals(asStr(s0.get("e")))) return;

        String skill    = orDash(asStr(s0.get("skill")));
        String kind     = "agent".equals(asStr(s0.get("kind"))) ? "agent" : "skill";
        String origin   = "model".equals(asStr(s0.get("origin"))) ? "model" : "user";
        String args     = asStr(s0.get("args"));
        String startIso = asStr(s0.get("iso"));
        long   startMs  = num(s0.get("t"));
        long   lastMs   = num(events.get(events.size() - 1).get("t"));
        final long endMs = lastMs > startMs ? lastMs : System.currentTimeMillis();
        long   elapsed  = Math.max(1, endMs - startMs);
        String rootLabel = "agent".equals(kind) ? "🤖 " + skill
                : "model".equals(origin) ? "Skill(" + skill + ")" : "/" + skill;

        // An agent's own transcript is what proves it exists — build the lookup before
        // the event loop, not after, so `agent_end` can be matched to the node it
        // actually closes instead of decrementing a bare counter. `SubagentStop` fires
        // for internal/ephemeral agents that never went through `Skill`/`Task`/`Agent`
        // PreToolUse too (no transcript of ours, `agent_id` unknown here), interleaved
        // with the real one when the real agent runs in background — a plain depth--
        // per `agent_end` closed the wrong node on the first of those.
        String transcript = asStr(get(in, "transcript_path"));
        Map<String, Path> subs = subagentTranscripts(transcript);
        Map<String, String> toolUseIdOfAgentId = new HashMap<>();
        subs.forEach((toolUseId, file) -> toolUseIdOfAgentId.put(agentIdOf(file), toolUseId));

        List<Node> nodes = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        Map<String, Integer> fails = new LinkedHashMap<>();
        Map<String, Integer> ops = new LinkedHashMap<>();
        List<String> perms = new ArrayList<>();
        int compacts = 0, manualCompacts = 0;
        List<Long> ticks = new ArrayList<>();
        List<long[]> waits = new ArrayList<>();
        List<String[]> asked = new ArrayList<>();
        long askAt = 0;
        boolean errored = false;
        // Stack of tool_use_ids of agents opened and not yet closed — depth is its size
        // plus one. `agent_end` pops the entry whose `agent_id` resolves back to it
        // through `toolUseIdOfAgentId`; an `agent_id` that resolves to nothing (an
        // internal agent, not one this run opened) closes none of ours.
        Deque<String> openAgents = new ArrayDeque<>();

        for (Map<String, Object> e : events) {
            String evKind = asStr(e.get("e"));
            long t = num(e.get("t"));
            // A compaction is the runtime's, not the node's: it doesn't extend whoever ran before it.
            if (!"run_start".equals(evKind) && !"compact".equals(evKind)) ticks.add(t);
            switch (evKind) {
                case "skill" -> nodes.add(new Node("skill", orDash(asStr(e.get("name"))),
                        asStr(e.get("args")), t, openAgents.size() + 1,
                        asStr(e.get("tool_use_id")), asStr(e.get("in_agent")), null));
                case "agent" -> {
                    String toolUseId = asStr(e.get("tool_use_id"));
                    nodes.add(new Node("agent", orDash(asStr(e.get("name"))),
                            asStr(e.get("model")), t, openAgents.size() + 1,
                            toolUseId, asStr(e.get("in_agent")), asStr(e.get("detail"))));
                    openAgents.push(toolUseId);
                }
                case "agent_end" -> {
                    String toolUseId = toolUseIdOfAgentId.get(asStr(e.get("agent_id")));
                    if (toolUseId != null) openAgents.remove(toolUseId);
                }
                case "file" -> {
                    String path = asStr(e.get("path"));
                    if (path != null) touched.add(path);
                    ops.merge(orDash(asStr(e.get("op"))), 1, Integer::sum);
                }
                case "ask" -> askAt = t;
                case "asked" -> asked.add(new String[] {orDash(asStr(e.get("header"))),
                        orDash(asStr(e.get("q"))), orDash(asStr(e.get("a")))});
                case "answer" -> {
                    if (askAt > 0) waits.add(new long[] {askAt, t});
                    askAt = 0;
                }
                case "fail" -> {
                    String tool = orDash(asStr(e.get("tool")));
                    // A rejected AskUserQuestion never gets an answer event: its failure ends the wait.
                    if ("AskUserQuestion".equals(tool) && askAt > 0) {
                        waits.add(new long[] {askAt, t});
                        askAt = 0;
                    }
                    fails.merge(tool, 1, Integer::sum);
                }
                case "stop_fail" -> errored = true;
                case "compact" -> {
                    if ("manual".equals(asStr(e.get("trigger")))) manualCompacts++;
                    else compacts++;
                }
                case "perm" -> perms.add(orDash(asStr(e.get("tool"))));
                default -> { }
            }
        }

        // A question still open when the run is rendered waits until the end.
        if (askAt > 0) waits.add(new long[] {askAt, endMs});
        long wait  = waited(startMs, endMs, waits);
        long total = Math.max(1, elapsed - wait);

        // ── tokens per piece ──
        // An agent's usage is in its own transcript, never in the main one: it is read
        // from there, whole. A main-thread turn belongs to the last main-thread piece that
        // started before it, when that piece is a skill; after an agent call, or before
        // any piece, it is the root's orchestration. A skill called from inside a
        // subagent has no transcript of its own — its tokens are its agent's.
        // `subs` and `toolUseIdOfAgentId` are already built above, ahead of the event loop.

        long turnT = lnum(s0.get("turn_t"));
        long tokensFrom = turnT > 0 && turnT < startMs ? turnT : startMs;
        // Tool calls, peak context and tool errors follow the same attribution: the scan
        // that yields a piece's tokens yields its spend, so the two can never disagree
        // about who owns a turn. `null` spend, like `null` usage, means "counted in its agent".
        Usage all = new Usage(), rootSelf = new Usage();
        Spend allSpend = new Spend(), rootSpend = new Spend();
        List<Usage> selfOf = new ArrayList<>();
        List<Spend> spendOf = new ArrayList<>();
        List<CostlyTurn> costly = new ArrayList<>();
        List<Growth> grown = new ArrayList<>();
        for (Node nd : nodes) {
            if ("agent".equals(nd.kind())) {
                Scan sc = scan(subs.get(nd.toolUseId()));
                Usage u = usageOf(sc);
                Spend sp = new Spend();
                for (Turn tr : sc.turns()) {
                    sp.turn(tr);
                    costly.add(new CostlyTurn(tr.t(), pieceLabel(nd), tr.u()[0] + tr.u()[1] + tr.u()[3], tr.tools()));
                }
                int self = selfOf.size();
                grown.addAll(growthOf(sc.turns(), tr -> self));
                sp.errors.addAll(sc.errors());
                all.addAll(u);
                allSpend.addAll(sp);
                selfOf.add(u);
                spendOf.add(sp);
            } else {
                boolean main = nd.inAgent() == null;
                selfOf.add(main ? new Usage() : null);
                spendOf.add(main ? new Spend() : null);
            }
        }
        if ("agent".equals(kind)) {
            Scan sc = scan(subs.get(asStr(s0.get("tool_use_id"))));
            Usage u = usageOf(sc);
            all.addAll(u);
            rootSelf.addAll(u);
            for (Turn tr : sc.turns()) {
                rootSpend.turn(tr);
                costly.add(new CostlyTurn(tr.t(), rootLabel, tr.u()[0] + tr.u()[1] + tr.u()[3], tr.tools()));
            }
            rootSpend.errors.addAll(sc.errors());
            grown.addAll(growthOf(sc.turns(), tr -> -1));
        }
        Scan mainScan = scan(transcript == null ? null : Paths.get(transcript));
        grown.addAll(growthOf(mainScan.turns().stream().filter(tr -> tr.t() >= tokensFrom).toList(),
                tr -> mainOwner(nodes, tr.t())));
        for (Turn tr : mainScan.turns()) {
            if (tr.t() < tokensFrom) continue;
            int owner = mainOwner(nodes, tr.t());
            if (tr.model() != null) {
                all.add(tr.model(), tr.u());
                (owner < 0 ? rootSelf : selfOf.get(owner)).add(tr.model(), tr.u());
            }
            (owner < 0 ? rootSpend : spendOf.get(owner)).turn(tr);
            costly.add(new CostlyTurn(tr.t(), owner < 0 ? rootLabel : pieceLabel(nodes.get(owner)),
                    tr.u()[0] + tr.u()[1] + tr.u()[3], tr.tools()));
        }
        for (ToolErr er : mainScan.errors()) {
            if (er.t() < tokensFrom) continue;
            int owner = mainOwner(nodes, er.t());
            (owner < 0 ? rootSpend : spendOf.get(owner)).errors.add(er);
        }
        allSpend.addAll(rootSpend);
        for (int i = 0; i < nodes.size(); i++) {
            if (!"agent".equals(nodes.get(i).kind())) allSpend.addAll(spendOf.get(i));
        }
        costly.removeIf(c -> c.billable() == 0);
        costly.sort((a, b) -> Long.compare(b.billable(), a.billable()));

        List<String> rootPreloaded = "agent".equals(kind) ? preloadedSkills(skill) : List.of();

        List<String> added = new ArrayList<>(localPermissions());
        added.removeAll(Arrays.asList(orEmpty(asStr(s0.get("perm_before"))).split(" ")));
        String headStart = asStr(s0.get("head"));
        String headEnd   = gitShort();

        String status = !closing
                ? (!openAgents.isEmpty() ? "⏳ waiting for background subagent" : "⏳ in progress")
                : errored ? "❌ error"
                : fails.isEmpty() ? "✅ success"
                : "⚠️ success with recovered failures";

        StringBuilder md = new StringBuilder();
        md.append("# 🧾 Execution audit — `").append(rootLabel)
          .append(args == null || args.isBlank() ? "" : " " + args).append("`\n\n");

        md.append("| | |\n|---|---|\n")
          .append("| 🎯 Piece | `").append(rootLabel).append("` · ")
          .append("agent".equals(kind) ? "agent" : "skill").append(" |\n")
          .append("| 🙋 Origin | ").append("model".equals(origin)
                  ? "model — called on its own, no `/command`"
                  : "user — `/command`").append(" |\n")
          .append("| 🕐 Start | ").append(startIso).append(" |\n")
          .append("| 🏁 End | ").append(Instant.ofEpochMilli(endMs)).append(" |\n")
          .append("| ⏱️ Duration | ").append(hms(elapsed)).append(" |\n")
          .append("| ⏸️ Waiting for the user | ").append(hms(wait)).append(" |\n")
          .append("| ⚙️ Active duration | ").append(hms(total)).append(" |\n")
          .append("| ").append(status.substring(0, status.indexOf(' ')))
          .append(" Status | ").append(status.substring(status.indexOf(' ') + 1)).append(" |\n")
          .append("| 🤖 Model | ").append(orDash(all.model())).append(" |\n")
          .append("| 🌿 HEAD | ").append(orDash(headStart)).append(" → ")
          .append(orDash(headEnd)).append(" |\n\n");

        String prompt = asStr(s0.get("prompt"));
        md.append("## 📜 Initial command\n\n");
        if (prompt == null) {
            md.append("No prompt recorded before the call.\n\n");
        } else {
            if ("model".equals(origin)) {
                md.append("The user's prompt in the turn where the model called the piece:\n\n");
            }
            md.append("```text\n").append(prompt).append("\n```\n\n")
              .append("`sha256` of the original text (before redaction): `")
              .append(orDash(asStr(s0.get("prompt_sha256")))).append("` · secrets removed: ")
              .append("true".equals(asStr(s0.get("redacted"))) ? "**yes**" : "no").append("\n\n");
        }

        List<Node> ranked = new ArrayList<>(nodes);
        ranked.sort((a, b) -> Long.compare(dur(b, nodes, endMs, ticks, waits), dur(a, nodes, endMs, ticks, waits)));
        if (!ranked.isEmpty()) {
            md.append("## 🏆 Longest steps\n\n| # | Step | Duration | % |\n|---|---|---|---|\n");
            for (int i = 0; i < Math.min(3, ranked.size()); i++) {
                Node nd = ranked.get(i);
                long d = dur(nd, nodes, endMs, ticks, waits);
                md.append("| ").append(i + 1).append(" | `").append(nd.name()).append("` | ")
                  .append(hms(d)).append(" | ").append(pct(d, total)).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## 🔗 Chain\n\n```text\n");
        md.append(pad(rootLabel, 46)).append(bar(1.0)).append(' ')
          .append(pad(hms(total), 9)).append("100%\n");
        for (String p : rootPreloaded) md.append("  📎 ").append(p).append(" (preloaded)\n");
        for (int i = 0; i < nodes.size(); i++) {
            Node nd = nodes.get(i);
            long d = dur(nd, nodes, endMs, ticks, waits);
            boolean last = i == nodes.size() - 1;
            String indent = "  ".repeat(Math.max(0, nd.depth() - 1));
            String label = indent + (last ? "└─ " : "├─ ")
                    + ("agent".equals(nd.kind()) ? "🤖 " : "📘 ") + nd.name()
                    + (nd.detail() == null || nd.detail().isBlank() ? "" : " (" + nd.detail() + ")");
            md.append(pad(label, 46)).append(bar((double) d / total)).append(' ')
              .append(pad(hms(d), 9)).append(pct(d, total)).append('\n');
            if ("agent".equals(nd.kind())) {
                for (String p : preloadedSkills(nd.name())) {
                    md.append(indent).append("     📎 ").append(p).append(" (preloaded)\n");
                }
            }
        }
        md.append("```\n\n")
          .append("> A node's duration runs from its start to **its own last event**, inside the window")
          .append(" that ends at the next node of the same depth or shallower. `AskUserQuestion` waits")
          .append(" are subtracted. Percentages are of the active duration.\n\n");

        md.append("## 🧩 Tokens per piece\n\n")
          .append("| Piece | Origin | 🤖 Model | 🧮 Own billable | ♻️ Cache read | 💾 Cache write | ⏱️ Duration |\n|---|---|---|---|---|---|---|\n")
          .append("| `").append(rootLabel).append("` | ")
          .append("model".equals(origin) ? "model" : "user").append(" | ")
          .append(orDash(rootSelf.model())).append(" | ")
          .append(n(rootSelf.billable())).append(" | ")
          .append(n(rootSelf.cacheRead())).append(" | ").append(n(rootSelf.cacheWrite())).append(" | ")
          .append(hms(total)).append(" |\n");
        for (String p : rootPreloaded) {
            md.append("| `📎 ").append(p).append("` | preloaded | ↳ in the agent | — | — | — | — |\n");
        }
        for (int i = 0; i < nodes.size(); i++) {
            Node nd = nodes.get(i);
            Usage u = selfOf.get(i);
            md.append("| `").append(pieceLabel(nd))
              .append("` | nested | ")
              .append(u == null ? "—" : orDash(u.model())).append(" | ")
              .append(u == null ? "↳ in the agent" : n(u.billable())).append(" | ")
              .append(u == null ? "—" : n(u.cacheRead())).append(" | ")
              .append(u == null ? "—" : n(u.cacheWrite())).append(" | ")
              .append(hms(dur(nd, nodes, endMs, ticks, waits))).append(" |\n");
            if ("agent".equals(nd.kind())) {
                for (String p : preloadedSkills(nd.name())) {
                    md.append("| `📎 ").append(p).append("` | preloaded | ↳ in the agent | — | — | — | — |\n");
                }
            }
        }
        md.append("\n> Agent = the subagent's own transcript. Skill on the main thread = the model's")
          .append(" messages from the call until the next main-thread piece. The rest = root.")
          .append(" Summed, they close the aggregate below.")
          .append(" No runtime event marks where an inline skill ends, so the last skill a run")
          .append(" chains also carries what its caller does after it, up to the next agent or")
          .append(" skill: an orchestrator's consolidation, approval questions and pre-flight")
          .append(" land on that skill's row.\n\n");

        md.append("## 📊 Tokens (aggregate)\n\n")
          .append("| Metric | Value |\n|---|---|\n")
          .append("| ⬇️ input | ").append(n(all.in())).append(" |\n")
          .append("| ⬆️ output | ").append(n(all.out())).append(" |\n")
          .append("| ♻️ cache read | ").append(n(all.cacheRead())).append(" |\n")
          .append("| 💾 cache write | ").append(n(all.cacheWrite())).append(" |\n")
          .append("| 🧮 billable (input + output + cache write) | **").append(n(all.billable())).append("** |\n\n");
        long ctx = all.contextRead();
        double hit = ctx == 0 ? 0 : (double) all.cacheRead() / ctx;
        md.append("Cache hit ").append(pct(all.cacheRead(), Math.max(1, ctx))).append(" ")
          .append(bar(hit)).append("\n\n");

        md.append(planWindowsSection(dir, all.billable(), total));

        md.append("## 🔎 Where the run spent\n\n");
        List<String> pieceLabels = new ArrayList<>();
        List<Spend> pieceSpends = new ArrayList<>();
        pieceLabels.add(rootLabel);
        pieceSpends.add(rootSpend);
        for (int i = 0; i < nodes.size(); i++) {
            if (spendOf.get(i) == null) continue;
            Node nd = nodes.get(i);
            pieceLabels.add(pieceLabel(nd));
            pieceSpends.add(spendOf.get(i));
        }
        md.append("### 🛠️ Tool calls per piece\n\n");
        if (allSpend.calls() == 0) {
            md.append("No tool call found in the transcripts.\n\n");
        } else {
            md.append("| Piece | Calls | By tool | 📏 Peak context |\n|---|---|---|---|\n");
            for (int i = 0; i < pieceLabels.size(); i++) {
                Spend sp = pieceSpends.get(i);
                if (sp.calls() == 0 && sp.peak == 0) continue;
                md.append("| `").append(pieceLabels.get(i)).append("` | ").append(sp.calls()).append(" | ")
                  .append(sp.calls() == 0 ? "—" : sp.inline(6)).append(" | ")
                  .append(sp.peak == 0 ? "—" : n(sp.peak)).append(" |\n");
            }
            md.append("\n> Counted from the `tool_use` blocks of the transcripts, attributed like the tokens.")
              .append(" A skill called inside a subagent is counted in its agent.\n\n");
        }
        md.append("### 💸 Most expensive turns\n\n");
        if (costly.isEmpty()) {
            md.append("No model turn with usage found in the transcripts.\n\n");
        } else {
            md.append("| # | 🕐 When | Piece | 🧮 Billable | Tools called |\n|---|---|---|---|---|\n");
            for (int i = 0; i < Math.min(3, costly.size()); i++) {
                CostlyTurn c = costly.get(i);
                md.append("| ").append(i + 1).append(" | ").append(Instant.ofEpochMilli(c.t())).append(" | `")
                  .append(c.owner()).append("` | ").append(n(c.billable())).append(" | ")
                  .append(c.tools().isEmpty() ? "—" : String.join(", ", c.tools())).append(" |\n");
            }
            md.append('\n');
        }
        md.append("### 📈 What grew the context\n\n");
        if (grown.isEmpty()) {
            md.append("No tool call grew the context between two requests of the same transcript.\n\n");
        } else {
            long top = lnum(get(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))), "audit", "growth_top"));
            List<Growth> byReread = new ArrayList<>(grown);
            byReread.sort((a, b) -> Long.compare(b.reread(), a.reread()));
            if (top > 0) {
                md.append("| # | Piece | Tool | Target | ➕ Added | 🔁 Re-read by | ♻️ Re-read tokens |\n")
                  .append("|---|---|---|---|---|---|---|\n");
                for (int i = 0; i < Math.min(top, byReread.size()); i++) {
                    Growth g = byReread.get(i);
                    md.append("| ").append(i + 1).append(" | `")
                      .append(g.owner() < 0 ? rootLabel : pieceLabel(nodes.get(g.owner()))).append("` | ")
                      .append(g.tool()).append(" | `").append(targetCell(g.target())).append("` | ")
                      .append(n(g.added())).append(" | ").append(g.rereads()).append(" | ")
                      .append(n(g.reread())).append(" |\n");
                }
                md.append('\n');
            }
            md.append("| Piece | By tool — added → re-read |\n|---|---|\n");
            for (int o = -1; o < nodes.size(); o++) {
                List<Map.Entry<String, long[]>> by = growthByTool(grown, o);
                if (by.isEmpty()) continue;
                md.append("| `").append(o < 0 ? rootLabel : pieceLabel(nodes.get(o))).append("` | ")
                  .append(by.stream().map(e -> e.getKey() + " +" + n(e.getValue()[0]) + " → " + n(e.getValue()[1]))
                          .collect(Collectors.joining(" · ")))
                  .append(" |\n");
            }
            md.append("\n> Every request rereads the whole context, so what a call's result added is paid")
              .append(" again by each later request of the same transcript, up to a compaction. Added =")
              .append(" the next request's context minus this one's and its output, split across the")
              .append(" request's calls — an estimate: a harness reminder lands on the call before it.\n\n");
        }
        md.append("### 📏 Peak context\n\n");
        if (allSpend.peak == 0) {
            md.append("No model turn with usage found in the transcripts.\n\n");
        } else {
            String peakOwner = rootLabel;
            for (int i = 0; i < pieceSpends.size(); i++) {
                if (pieceSpends.get(i).peak == allSpend.peak && pieceSpends.get(i).peakT == allSpend.peakT) {
                    peakOwner = pieceLabels.get(i);
                    break;
                }
            }
            md.append("Largest single request: **").append(n(allSpend.peak))
              .append("** tokens (input + cache read + cache write) — `").append(peakOwner)
              .append("` at ").append(Instant.ofEpochMilli(allSpend.peakT)).append(".\n\n")
              .append("> An absolute number on purpose: the model's context window is not written from")
              .append(" memory. Compare it with the incidents below — a compaction follows the peaks.\n\n");
        }

        md.append("## 🔐 Permissions added during the run\n\n");
        if (added.isEmpty()) {
            md.append("None. `settings.local.json` did not change between start and end.\n\n");
        } else {
            md.append("| Rule |\n|---|\n");
            for (String a : added) md.append("| `").append(a).append("` |\n");
            md.append('\n');
        }
        if (!perms.isEmpty()) {
            md.append("Requests observed (").append(perms.size()).append("): ")
              .append(String.join(", ", new LinkedHashSet<>(perms))).append("\n\n");
        }

        md.append("## 📐 Rules loaded (inferred by territory)\n\n");
        List<RuleHit> rules = auditRules(touched);
        if (rules.isEmpty()) {
            md.append("No touched file matches any rule's `paths`.\n\n");
        } else {
            md.append("| Rule | Glob | Files |\n|---|---|---|\n");
            for (RuleHit r : rules) {
                md.append("| `").append(r.rule()).append("` | `").append(r.glob())
                  .append("` | ").append(r.files().size()).append(" |\n");
            }
            md.append("\n> Inference, not observation: no hook event exposes which rule")
              .append(" entered the context. These are the rules that **should** have loaded.\n\n");
        }

        md.append("## 📁 Files touched\n\n");
        if (touched.isEmpty()) {
            md.append("None.\n\n");
        } else {
            md.append(ops.entrySet().stream().map(e -> e.getValue() + "× " + e.getKey())
                    .collect(Collectors.joining(" · ")))
              .append(" — ").append(touched.size())
              .append(touched.size() == 1 ? " distinct file" : " distinct files")
              .append("\n\n```text\n")
              .append(String.join("\n", touched)).append("\n```\n\n");
        }

        if (!asked.isEmpty()) {
            md.append("## 💬 Asked\n\n| # | Topic | Question | Answer |\n|---|---|---|---|\n");
            for (int i = 0; i < asked.size(); i++) {
                String[] a = asked.get(i);
                md.append("| ").append(i + 1).append(" | ").append(a[0]).append(" | ")
                  .append(a[1]).append(" | ").append(a[2]).append(" |\n");
            }
            md.append("\n> Redacted and cut at 160 characters. What the run decided without asking is in its partials.\n\n");
        }

        md.append("## 🔁 Rework\n\n");
        if (fails.isEmpty() && allSpend.errors.isEmpty()) {
            md.append("No tool failed. 🎉\n\n");
        } else {
            if (!fails.isEmpty()) {
                md.append("| Tool | Failures |\n|---|---|\n");
                for (Map.Entry<String, Integer> e : fails.entrySet()) {
                    md.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
                }
                md.append('\n');
            }
            if (!allSpend.errors.isEmpty()) {
                // Distinct (piece, tool, text), in order of first appearance, with a count.
                Map<String, int[]> seen = new LinkedHashMap<>();
                Map<String, String[]> row = new HashMap<>();
                for (int i = 0; i < pieceSpends.size(); i++) {
                    for (ToolErr er : pieceSpends.get(i).errors) {
                        String k = pieceLabels.get(i) + "\u0001" + er.tool() + "\u0001" + er.text();
                        seen.computeIfAbsent(k, x -> new int[1])[0]++;
                        row.putIfAbsent(k, new String[] {pieceLabels.get(i), er.tool(), er.text()});
                    }
                }
                md.append("| Piece | Tool | × | First line of the error (redacted) |\n|---|---|---|---|\n");
                int shown = 0;
                for (Map.Entry<String, int[]> e : seen.entrySet()) {
                    if (shown++ == 10) {
                        md.append("| … | | | ").append(seen.size() - 10).append(" more distinct errors |\n");
                        break;
                    }
                    String[] r = row.get(e.getKey());
                    md.append("| `").append(r[0]).append("` | `").append(r[1]).append("` | ")
                      .append(e.getValue()[0]).append(" | `").append(r[2]).append("` |\n");
                }
                md.append('\n');
            }
            md.append("> A repeated failure on the same tool is a sign of a bad spec, not bad luck.\n\n");
        }

        if (compacts > 0) {
            md.append("## ⚠️ Incidents\n\n🗜️ Context compacted automatically ").append(compacts)
              .append("× during the run — output quality drops after each compaction.\n\n");
        }
        if (manualCompacts > 0) {
            md.append("ℹ️ Manual `/compact`: ").append(manualCompacts)
              .append("× — the user's decision, not an incident.\n\n");
        }

        if (headStart != null && headEnd != null && !headStart.equals(headEnd)) {
            md.append("## 🌿 Commits of the run\n\n```text\n")
              .append(String.join("\n", gitLog(headStart, headEnd))).append("\n```\n\n");
        }

        md.append("---\n\n*Generated by `ArchHook.java audit` · `")
          .append(AUDIT_DIR).append("/`*\n");

        String stamp = orEmpty(startIso).replace(':', '-');
        int dot = stamp.indexOf('.');
        if (dot > 0) stamp = stamp.substring(0, dot);
        Path report = dir.resolve(stamp + "--" + skill + ".md");
        Files.writeString(report, md.toString(), StandardCharsets.UTF_8);

        if (closing) {
            String failures = String.valueOf(fails.values().stream().mapToInt(Integer::intValue).sum());
            Files.writeString(dir.resolve("history.jsonl"),
                    ev("run", "skill", skill, "kind", kind, "origin", origin, "start", startIso,
                       // The model is what the report's header showed and the ledger did
                       // not: a token weighs differently on Sonnet and on Opus, and the
                       // comparison had to be rebuilt by hand from the reports
                       // (lessons-learned-012, header note). A comma-separated list when a
                       // subagent ran on another model.
                       "model", all.model(),
                       "duration_ms", String.valueOf(total),
                       "wait_ms", String.valueOf(wait),
                       "status", status,
                       "tokens_billable", String.valueOf(all.billable()),
                       "tokens_self", String.valueOf(rootSelf.billable()),
                       // The cache split and what grew the context, per piece: the totals alone could
                       // not say where a run's re-reads went (issue #111). decisions/0132
                       "cache_read_self", String.valueOf(rootSelf.cacheRead()),
                       "cache_write_self", String.valueOf(rootSelf.cacheWrite()),
                       "reread_self", rereadLedger(grown, -1),
                       // Run total and root's own: `audit summary` sums the `_self` pair
                       // per piece so nested pieces are not counted twice.
                       // decisions/0085-audit-where-the-run-spent-and-english.md
                       "tool_calls", allSpend.ledger(),
                       "tool_calls_self", rootSpend.ledger(),
                       "peak_context", allSpend.peak == 0 ? null : String.valueOf(allSpend.peak),
                       "peak_context_self", rootSpend.peak == 0 ? null : String.valueOf(rootSpend.peak),
                       "files", String.valueOf(touched.size()),
                       "failures", failures,
                       "report", relative(report)) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // One line per nested piece of this project, in a ledger of its own: the run
            // ledger keeps one line per run, so what reads it does not pay for the finer grain.
            StringBuilder rows = new StringBuilder();
            String run = relative(report);
            for (String p : rootPreloaded) rows.append(nodeRow(run, skill, "skill", p, "preloaded", null, null, null, null, 0)).append('\n');
            for (int i = 0; i < nodes.size(); i++) {
                Node nd = nodes.get(i);
                if (!isAudited(nd.kind(), nd.name())) continue;
                String parent = skill;
                if (nd.inAgent() != null) {
                    String tu = toolUseIdOfAgentId.get(nd.inAgent());
                    for (Node o : nodes) if (tu != null && tu.equals(o.toolUseId())) parent = o.name();
                }
                rows.append(nodeRow(run, parent, nd.kind(), nd.name(), "nested", nd.description(), selfOf.get(i), spendOf.get(i),
                        rereadLedger(grown, i),
                        dur(nd, nodes, endMs, ticks, waits))).append('\n');
                if ("agent".equals(nd.kind())) {
                    for (String p : preloadedSkills(nd.name())) {
                        rows.append(nodeRow(run, nd.name(), "skill", p, "preloaded", null, null, null, null, 0)).append('\n');
                    }
                }
            }
            if (rows.length() > 0) {
                Files.writeString(dir.resolve("nodes.jsonl"), rows.toString(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.deleteIfExists(log);
        }
    }

    /**
     * Owner of a main-thread moment: the last main-thread piece started before it, when that
     * piece is a skill; after an agent call, or before any piece, the root (-1).
     */
    static int mainOwner(List<Node> nodes, long t) {
        int owner = -1;
        for (int i = 0; i < nodes.size(); i++) {
            Node nd = nodes.get(i);
            if (nd.inAgent() != null || nd.start() > t) continue;
            owner = "skill".equals(nd.kind()) ? i : -1;
        }
        return owner;
    }

    /** A `nodes.jsonl` line. No usage means "counted in its agent" — the field is left out, never zero. */
    static String nodeRow(String run, String parent, String kind, String name, String origin, String detail,
                          Usage u, Spend sp, String reread, long durationMs) {
        return ev("node", "run", run, "parent", parent, "kind", kind, "skill", name, "origin", origin,
                "detail", detail == null || detail.isBlank() ? null : redact(detail),
                "model", u == null ? null : u.model(),
                "tokens_self", u == null ? null : String.valueOf(u.billable()),
                "cache_read", u == null ? null : String.valueOf(u.cacheRead()),
                "cache_write", u == null ? null : String.valueOf(u.cacheWrite()),
                "reread", reread,
                "tool_calls", sp == null ? null : sp.ledger(),
                "peak_context", sp == null || sp.peak == 0 ? null : String.valueOf(sp.peak),
                "duration_ms", u == null ? null : String.valueOf(durationMs));
    }

    /**
     * Skills an agent loads through its `skills:` frontmatter. No tool call exists for them,
     * so no hook sees them load — the frontmatter is the only record, and it is data.
     */
    static List<String> preloadedSkills(String agent) {
        if (!isAuditedAgent(agent)) return List.of();
        String c = readOrNull(ROOT.resolve(".claude/agents").resolve(agent + ".md"));
        Map<String, String> fm = c == null ? null : frontmatter(c);
        if (fm == null) return List.of();
        List<String> out = new ArrayList<>();
        String inline = fm.get("skills");
        if (inline != null && !inline.isBlank()) {
            for (String s : inline.replaceAll("[\\[\\]\"']", "").split("[,\\s]+")) {
                if (!s.isBlank()) out.add(s.strip());
            }
            return out;
        }
        boolean inside = false;
        for (String raw : c.lines().collect(Collectors.toList())) {
            if (raw.startsWith("skills:")) { inside = true; continue; }
            if (!inside) continue;
            String l = raw.strip();
            if (!l.startsWith("- ")) break;
            out.add(l.substring(2).strip().replaceAll("^[\"']|[\"']$", ""));
        }
        return out;
    }

    /**
     * From this node's start to its own last event, not to the next node's start. The
     * window closes at the next node at the same depth or shallower; events inside it
     * belong to this node, and the gap after the last of them does not. A node with no
     * event of its own falls back to the whole window. AskUserQuestion waits inside the
     * span are subtracted.
     */
    static long dur(Node n, List<Node> all, long endMs, List<Long> ticks, List<long[]> waits) {
        long boundary = endMs;
        for (Node o : all) {
            if (o.start() > n.start() && o.depth() <= n.depth()) { boundary = o.start(); break; }
        }
        long last = n.start();
        for (long t : ticks) {
            boolean inside = t > n.start() && (t < boundary || boundary == endMs && t <= endMs);
            if (inside) last = Math.max(last, t);
        }
        long end = last > n.start() ? last : boundary;
        return Math.max(0, end - n.start() - waited(n.start(), end, waits));
    }

    /** Milliseconds of [from, to] covered by the wait intervals. */
    static long waited(long from, long to, List<long[]> waits) {
        long sum = 0;
        for (long[] w : waits) sum += Math.max(0, Math.min(to, w[1]) - Math.max(from, w[0]));
        return sum;
    }

    /** A tool call the transcript marked `is_error`: when, which tool, the first line of what it said. */
    record ToolErr(long t, String tool, String text) {}

    /** What one transcript says: the model's turns (usage and the tools each called) and the tool errors. */
    record Scan(List<Turn> turns, List<ToolErr> errors) {}

    /**
     * One pass over a transcript — the only place the runtime writes real token counts, the
     * tools each message called, and which tool results came back as errors. The runtime
     * writes one line per content block, each repeating the message's `usage`: turns are
     * deduplicated by `message.id`, or a message with text and two tool calls counts three
     * times; tool calls are counted once per `tool_use` id for the same reason. An error's
     * tool is resolved through its `tool_use_id` after the pass, and its text is redacted
     * before it can reach a versioned report — an error can echo a command line holding a
     * token (invariant 11). The line layout is observed, not documented — best-effort; a
     * missing file or a changed layout reads as an empty scan, never as an error.
     */
    static Scan scan(Path p) {
        if (p == null || !Files.isRegularFile(p)) return new Scan(List.of(), List.of());
        Map<String, Turn> byId = new LinkedHashMap<>();
        Map<String, List<String>> toolsOf = new HashMap<>();   // message id → tool names
        Map<String, List<String>> targetsOf = new HashMap<>(); // message id → tool targets, same order
        Map<String, Long> toolMsgT = new LinkedHashMap<>();      // message id → timestamp
        Map<String, String> toolName = new HashMap<>();         // tool_use id → tool name
        List<String[]> rawErrors = new ArrayList<>();          // {t, tool_use id, text}
        int anon = 0;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                // Transcripts run to megabytes; most lines carry none of the three and are not worth parsing.
                boolean hasUsage = line.contains("\"usage\"");
                boolean hasTool = line.contains("\"tool_use\"");
                boolean hasError = line.contains("\"is_error\":true");
                if (!hasUsage && !hasTool && !hasError) continue;
                Object e = Json.parse(line);
                long t = epochMs(asStr(get(e, "timestamp")));
                String id = asStr(get(e, "message", "id"));
                Object content = get(e, "message", "content");
                if (content instanceof List<?> blocks) {
                    for (Object b : blocks) {
                        String type = asStr(get(b, "type"));
                        if ("tool_use".equals(type) && id != null) {
                            String tuid = asStr(get(b, "id"));
                            String name = orDash(asStr(get(b, "name")));
                            if (tuid != null && toolName.putIfAbsent(tuid, name) != null) continue;
                            toolsOf.computeIfAbsent(id, k -> new ArrayList<>()).add(name);
                            targetsOf.computeIfAbsent(id, k -> new ArrayList<>()).add(toolTarget(get(b, "input")));
                            toolMsgT.putIfAbsent(id, t);
                        } else if ("tool_result".equals(type) && Boolean.TRUE.equals(get(b, "is_error"))) {
                            rawErrors.add(new String[] {String.valueOf(t), asStr(get(b, "tool_use_id")),
                                    firstLine(get(b, "content"))});
                        }
                    }
                }
                Map<String, Object> u = asMap(get(e, "message", "usage"));
                if (u == null) continue;
                long[] v = {num(u.get("input_tokens")), num(u.get("output_tokens")),
                            num(u.get("cache_read_input_tokens")), num(u.get("cache_creation_input_tokens"))};
                if (v[0] + v[1] + v[2] + v[3] == 0) continue;
                String key = id != null ? id : "#" + anon++;
                byId.put(key, new Turn(t, asStr(get(e, "message", "model")), v,
                        id == null ? new ArrayList<>() : toolsOf.computeIfAbsent(id, k -> new ArrayList<>()),
                        id == null ? new ArrayList<>() : targetsOf.computeIfAbsent(id, k -> new ArrayList<>())));
            }
        } catch (IOException ignored) { }
        // A message whose calls were written but whose usage never was still called its tools.
        toolMsgT.forEach((id, t) -> {
            if (!byId.containsKey(id)) byId.put(id, new Turn(t, null, new long[4], toolsOf.get(id),
                    targetsOf.getOrDefault(id, new ArrayList<>())));
        });
        List<ToolErr> errors = new ArrayList<>();
        for (String[] x : rawErrors) {
            errors.add(new ToolErr(Long.parseLong(x[0]), orDash(toolName.get(x[1])), x[2]));
        }
        return new Scan(new ArrayList<>(byId.values()), errors);
    }

    /** First non-blank line of a tool result — a string, or a list of `text` blocks — redacted, at most 160 chars. */
    static String firstLine(Object content) {
        StringBuilder all = new StringBuilder();
        if (content instanceof String s) all.append(s);
        else if (content instanceof List<?> l) {
            for (Object b : l) {
                String x = asStr(get(b, "text"));
                if (x != null) all.append(x).append('\n');
            }
        }
        List<String> lines = all.toString().lines().map(String::strip).filter(x -> !x.isEmpty())
                .limit(2).collect(Collectors.toList());
        String first = lines.isEmpty() ? "—" : lines.get(0);
        // A failed Bash result opens with `Exit code N` — the same line for every failure.
        // The line after it is what actually failed.
        Matcher exit = Pattern.compile("Exit code (\\d+)").matcher(first);
        if (exit.matches() && lines.size() > 1) first = "exit " + exit.group(1) + " · " + lines.get(1);
        return clip(first);
    }

    static List<Turn> usageTurns(Path p) { return scan(p).turns(); }

    static Usage usage(Path transcript) { return usageOf(scan(transcript)); }

    static Usage usageOf(Scan sc) {
        Usage u = new Usage();
        for (Turn t : sc.turns()) if (t.model() != null) u.add(t.model(), t.u());
        return u;
    }

    /**
     * Where one piece spent: its tool calls by name, the largest single request it sent
     * (input + cache read + cache write — the context that turn carried), and the errors
     * its tools returned. Absolute numbers only: the model's context window is not written
     * from memory, the discipline invariant 8 imposes on versions.
     */
    static final class Spend {
        final Map<String, Integer> tools = new LinkedHashMap<>();
        long peak, peakT;
        final List<ToolErr> errors = new ArrayList<>();

        void turn(Turn tr) {
            for (String tool : tr.tools()) tools.merge(tool, 1, Integer::sum);
            long ctx = tr.context();
            if (ctx > peak) { peak = ctx; peakT = tr.t(); }
        }
        void addAll(Spend o) {
            if (o == null) return;
            o.tools.forEach((k, v) -> tools.merge(k, v, Integer::sum));
            if (o.peak > peak) { peak = o.peak; peakT = o.peakT; }
            errors.addAll(o.errors);
        }
        int calls() { return tools.values().stream().mapToInt(Integer::intValue).sum(); }
        List<Map.Entry<String, Integer>> ranked() {
            List<Map.Entry<String, Integer>> l = new ArrayList<>(tools.entrySet());
            l.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()));
            return l;
        }
        /** Ledger form, `Bash:5,Read:12` — the ledgers carry strings only. */
        String ledger() {
            return tools.isEmpty() ? null : ranked().stream().map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(","));
        }
        /** Report form, `Read 12 · Bash 5`. */
        String inline(int limit) {
            List<Map.Entry<String, Integer>> r = ranked();
            String s = r.stream().limit(limit).map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(" · "));
            return r.size() > limit ? s + " · …" : s;
        }
    }

    /** One model turn, ranked by billable tokens in the report. */
    record CostlyTurn(long t, String owner, long billable, List<String> tools) {}

    /**
     * What one tool call put into the context, and how many later requests of the same
     * transcript carried it again. {@code owner} indexes the run's nodes; -1 is the root.
     */
    record Growth(int owner, String tool, String target, long added, long rereads) {
        long reread() { return added * rereads; }
    }

    /**
     * Context growth per tool call, in one transcript. Every request rereads the whole
     * context, so what a call's result adds is paid again by each later request — the cost
     * the per-piece totals cannot place (issue #111, decision 0132). Growth after request k
     * = context(k+1) − context(k) − output(k), split evenly across the tool calls of k; a
     * request with no tool call adds nothing to attribute. It is reread by the requests after
     * k+1, up to a compaction — a request whose context falls below half of the one before.
     * An estimate: a harness reminder lands on the call before it.
     */
    static List<Growth> growthOf(List<Turn> turns, java.util.function.ToIntFunction<Turn> owner) {
        List<Turn> s = new ArrayList<>();
        for (Turn tr : turns) if (tr.context() > 0) s.add(tr);
        s.sort(Comparator.comparingLong(Turn::t));
        int n = s.size();
        int[] stop = new int[n];
        int next = n;
        for (int j = n - 1; j >= 0; j--) {
            stop[j] = next;
            if (j > 0 && s.get(j).context() * 2 < s.get(j - 1).context()) next = j;
        }
        List<Growth> out = new ArrayList<>();
        for (int k = 0; k + 1 < n; k++) {
            Turn a = s.get(k);
            if (a.tools().isEmpty() || stop[k] == k + 1) continue;
            long grew = s.get(k + 1).context() - a.context() - a.u()[1];
            if (grew <= 0) continue;
            long rereads = Math.max(0, stop[k] - k - 2);
            int calls = a.tools().size();
            for (int c = 0; c < calls; c++) {
                String target = c < a.targets().size() ? a.targets().get(c) : "";
                out.add(new Growth(owner.applyAsInt(a), a.tools().get(c), target, grew / calls, rereads));
            }
        }
        return out;
    }

    /**
     * What a tool call aimed at: the path for a file tool, relative to the project; a shell
     * command's first line. Raw — {@link #targetCell} redacts and cuts it, and only for the
     * calls the report prints: redaction reads the schema, and a transcript holds thousands.
     */
    static String toolTarget(Object input) {
        String fp = asStr(get(input, "file_path"));
        if (fp == null) fp = asStr(get(input, "notebook_path"));
        if (fp == null) fp = asStr(get(input, "path"));
        String t;
        if (fp != null) {
            try {
                Path p = Paths.get(fp);
                t = p.isAbsolute() && p.startsWith(ROOT) ? ROOT.relativize(p).toString() : fp;
            } catch (Exception e) { t = fp; }
            t = t.replace('\\', '/');
        } else {
            String cmd = asStr(get(input, "command"));
            if (cmd == null) cmd = asStr(get(input, "pattern"));
            if (cmd == null) return "";
            t = cmd.strip().lines().findFirst().orElse("");
        }
        return t;
    }

    /** A target as a table cell: redacted (invariant 11 — a command line can hold a token), table-safe, at most 80 chars. */
    static String targetCell(String target) {
        if (target == null || target.isBlank()) return "—";
        String t = redact(target).replace('|', '/').replace('`', '\'');
        return t.length() > 80 ? t.substring(0, 79) + "…" : t;
    }

    /** Per-tool `added` and `reread` totals of one piece, largest reread first. */
    static List<Map.Entry<String, long[]>> growthByTool(List<Growth> grown, int owner) {
        Map<String, long[]> by = new LinkedHashMap<>();
        for (Growth g : grown) {
            if (g.owner() != owner) continue;
            long[] a = by.computeIfAbsent(g.tool(), k -> new long[2]);
            a[0] += g.added();
            a[1] += g.reread();
        }
        List<Map.Entry<String, long[]>> l = new ArrayList<>(by.entrySet());
        l.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        return l;
    }

    /** Ledger form of {@link #growthByTool}, `Read:4100000,Bash:900000` — reread tokens per tool. */
    static String rereadLedger(List<Growth> grown, int owner) {
        List<Map.Entry<String, long[]>> l = growthByTool(grown, owner);
        return l.isEmpty() ? null : l.stream().map(e -> e.getKey() + ":" + e.getValue()[1])
                .collect(Collectors.joining(","));
    }

    // ── plan windows: how many runs fit a plan's 5-hour and weekly windows ──────────
    // Anthropic publishes no limit in tokens or dollars, only Max's multipliers over Pro per
    // 5-hour session. The budget is the project's own reading (`audit.plan_limits`), in
    // billable tokens like every other number of the trail; every other number is
    // `audit.plans` / `audit.windows` — nothing here is a constant (0133, unit by 0134).

    /** `audit.windows` as {key, label, hours, budget field}, in file order. */
    static List<String[]> planWindows(Map<String, Object> sch) {
        List<String[]> out = new ArrayList<>();
        Map<String, Object> w = asMap(get(sch, "audit", "windows"));
        if (w == null) return out;
        for (Map.Entry<String, Object> e : w.entrySet()) {
            Map<String, Object> m = asMap(e.getValue());
            if (m == null || lnum(m.get("hours")) <= 0) continue;
            out.add(new String[] {e.getKey(), orDash(asStr(m.get("label"))),
                    String.valueOf(lnum(m.get("hours"))), asStr(m.get("budget"))});
        }
        return out;
    }

    /** The project's budgets for `audit.budget_plan`, or null when it wrote no plan-limits file. */
    static Map<String, Object> planBudgets(Map<String, Object> sch, Path dir) {
        String file = asStr(get(sch, "audit", "plan_limits"));
        String base = asStr(get(sch, "audit", "budget_plan"));
        if (file == null || base == null) return null;
        Object root = Json.parse(readOrNull(dir.resolve(file)));
        return root == null ? null : Objects.requireNonNullElse(asMap(get(root, base)), Map.of());
    }

    /**
     * One cell: runs like this one that fit {@code window} on {@code plan}, as if nothing
     * else ran — budget × multiplier ÷ billable tokens, capped by the window's hours ÷ active
     * duration back to back — with the limit that binds; or why there is no number.
     */
    static String planCell(Map<String, Object> sch, Map<String, Object> budgets, String plan,
                           String[] window, double tokens, long durationMs) {
        Object mult = get(sch, "audit", "plans", plan, "multipliers", window[0]);
        if (!(mult instanceof Number m)) return "not published";
        if (tokens <= 0) return "— no tokens";
        Double budget = window[3] == null ? null : dbl(budgets.get(window[3]));
        if (budget == null || budget <= 0) return "— set `" + window[3] + "`";
        long byBudget = (long) Math.floor(budget * m.doubleValue() / tokens);
        long byTime = durationMs <= 0 ? Long.MAX_VALUE : Long.parseLong(window[2]) * 3_600_000L / durationMs;
        return byTime < byBudget ? n(byTime) + " · time" : n(byBudget) + " · budget";
    }

    /** The run report's `Plan windows` section: one row per plan, one column per window. */
    static String planWindowsSection(Path dir, long tokens, long durationMs) {
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        List<String[]> windows = sch == null ? List.of() : planWindows(sch);
        if (windows.isEmpty()) return "";
        StringBuilder md = new StringBuilder("## 🪟 Plan windows\n\n");
        String file = asStr(get(sch, "audit", "plan_limits"));
        Map<String, Object> budgets = planBudgets(sch, dir);
        if (budgets == null) {
            return md.append("No `.claude/audit-usage/").append(file).append("` — no projection. Anthropic publishes")
              .append(" no limit in tokens or dollars: write the `").append(asStr(get(sch, "audit", "budget_plan")))
              .append("` budget you observed, in billable tokens, and every plan is projected from it.\n\n")
              .toString();
        }
        md.append("Runs like this one that fit each window, as if nothing else ran — ")
          .append(n(tokens)).append(" billable tokens per run, ")
          .append(hms(durationMs)).append(" active.\n\n| Plan |");
        for (String[] w : windows) md.append(' ').append(w[1]).append(" |");
        md.append("\n|---|").append("---|".repeat(windows.size())).append('\n');
        for (String p : planKeys(sch)) {
            md.append("| ").append(orDash(asStr(get(sch, "audit", "plans", p, "label")))).append(" |");
            for (String[] w : windows) md.append(' ').append(planCell(sch, budgets, p, w, tokens, durationMs)).append(" |");
            md.append('\n');
        }
        return md.append("\n> budget = the ").append(asStr(get(sch, "audit", "budget_plan")))
          .append(" budget in `").append(file).append("` × the plan's multiplier (")
          .append(asStr(get(sch, "audit", "plans_source"))).append("); time = the window ÷ this run's")
          .append(" active duration, back to back. An estimate: chat and other sessions share the real")
          .append(" window, the budget is the project's own reading, and a token weighs the same on")
          .append(" every model here, which it does not on the plan.\n\n").toString();
    }

    /**
     * `audit summary`'s plan-window table: per piece, the mean billable tokens and active
     * duration of its runs, projected like {@link #planWindowsSection}. A root run counts its
     * whole run — what typing that command spends; a chained piece only its own. Only the
     * plan × window pairs with a published multiplier get a column.
     */
    static String planWindowsSummary(Path dir, List<Map<String, Object>> runs, List<Map<String, Object>> nested) {
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        List<String[]> windows = sch == null ? List.of() : planWindows(sch);
        if (windows.isEmpty()) return "";
        String file = asStr(get(sch, "audit", "plan_limits"));
        Map<String, Object> budgets = planBudgets(sch, dir);
        StringBuilder o = new StringBuilder("\n### Plan windows (runs per window, mean per piece, as if nothing else ran)\n\n");
        if (budgets == null) {
            return o.append("not configured — no `").append(file).append("` in the trail\n").toString();
        }
        Map<String, double[]> mean = new LinkedHashMap<>();   // label → tokens sum, duration sum, runs
        for (Map<String, Object> r : runs) {
            long t = lnum(r.get("tokens_billable"));
            if (t <= 0) continue;
            double[] a = mean.computeIfAbsent(keyLabel(pieceKey(r)), k -> new double[3]);
            a[0] += t; a[1] += lnum(r.get("duration_ms")); a[2]++;
        }
        for (Map<String, Object> r : nested) {
            long t = lnum(r.get("tokens_self"));
            if (t <= 0 || "preloaded".equals(asStr(r.get("origin")))) continue;
            double[] a = mean.computeIfAbsent(keyLabel(pieceKey(r)) + " (chained, own)", k -> new double[3]);
            a[0] += t; a[1] += lnum(r.get("duration_ms")); a[2]++;
        }
        if (mean.isEmpty()) return o.append("no run with tokens yet\n").toString();
        List<String[]> cols = new ArrayList<>();                // {plan, window index}
        for (String p : planKeys(sch)) {
            for (int i = 0; i < windows.size(); i++) {
                if (get(sch, "audit", "plans", p, "multipliers", windows.get(i)[0]) instanceof Number) {
                    cols.add(new String[] {p, String.valueOf(i)});
                }
            }
        }
        o.append("| Piece | Runs | Mean billable | Mean active |");
        for (String[] c : cols) {
            o.append(' ').append(orDash(asStr(get(sch, "audit", "plans", c[0], "label")))).append(' ')
             .append(windows.get(Integer.parseInt(c[1]))[1]).append(" |");
        }
        o.append("\n|---|---|---|---|").append("---|".repeat(cols.size())).append('\n');
        List<Map.Entry<String, double[]>> rows = new ArrayList<>(mean.entrySet());
        rows.sort((a, b) -> Double.compare(b.getValue()[0] / b.getValue()[2], a.getValue()[0] / a.getValue()[2]));
        for (Map.Entry<String, double[]> e : rows) {
            double[] a = e.getValue();
            double tokens = a[0] / a[2];
            long dur = (long) (a[1] / a[2]);
            o.append("| ").append(e.getKey()).append(" | ").append((long) a[2]).append(" | ")
             .append(n(Math.round(tokens))).append(" | ").append(hms(dur)).append(" |");
            for (String[] c : cols) {
                o.append(' ').append(planCell(sch, budgets, c[0], windows.get(Integer.parseInt(c[1])), tokens, dur)).append(" |");
            }
            o.append('\n');
        }
        return o.append("\nbudget = `").append(file).append("` × the plan's multiplier (")
          .append(asStr(get(sch, "audit", "plans_source"))).append("); time = the window ÷ the mean active duration;")
          .append(" a plan × window with no published multiplier has no column\n").toString();
    }

    /** `audit.plans` keys, in file order. */
    static List<String> planKeys(Map<String, Object> sch) {
        Map<String, Object> p = asMap(get(sch, "audit", "plans"));
        return p == null ? List.of() : new ArrayList<>(p.keySet());
    }

    /**
     * What `doctor` says about the project's plan-limits file: null when there is none, else
     * the problems (empty when valid) and, last, what it sets. Strict like `audited.json`: a
     * misspelled field would otherwise project nothing, in silence.
     */
    static List<String> planLimitProblems(Map<String, Object> sch, Path dir) {
        String file = asStr(get(sch, "audit", "plan_limits"));
        String base = asStr(get(sch, "audit", "budget_plan"));
        if (file == null || base == null || !Files.isRegularFile(dir.resolve(file))) return null;
        List<String> out = new ArrayList<>();
        Map<String, Object> root = asMap(Json.parse(readOrNull(dir.resolve(file))));
        if (root == null) {
            out.add(file + " is not a JSON object");
            out.add("");
            return out;
        }
        Set<String> fields = new LinkedHashSet<>();
        for (String[] w : planWindows(sch)) if (w[3] != null) fields.add(w[3]);
        List<String> set = new ArrayList<>();
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getKey().startsWith("$")) continue;
            if (!base.equals(e.getKey())) {
                out.add("`" + e.getKey() + "` — only `" + base + "` is read; every other plan is its multiple");
                continue;
            }
            Map<String, Object> b = asMap(e.getValue());
            if (b == null) { out.add("`" + base + "` must map " + String.join(" and ", fields) + " to a number"); continue; }
            for (Map.Entry<String, Object> f : b.entrySet()) {
                if (f.getKey().startsWith("$")) continue;
                if (!fields.contains(f.getKey())) {
                    out.add("`" + base + "." + f.getKey() + "` — only " + String.join(", ", fields) + " are read");
                } else if (f.getValue() != null && !(f.getValue() instanceof Number x && x.doubleValue() > 0)) {
                    out.add("`" + base + "." + f.getKey() + "` must be a number above 0, or null");
                } else if (f.getValue() != null) {
                    double v = ((Number) f.getValue()).doubleValue();
                    set.add(base + " " + f.getKey() + " " + (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v)));
                }
            }
        }
        out.add(set.isEmpty() ? "" : String.join(", ", set));
        return out;
    }

    /** A node as the report's tables name it: icon, name and, for an agent call, its description. */
    static String pieceLabel(Node nd) {
        return ("agent".equals(nd.kind()) ? "🤖 " : "📘 ") + nd.name()
                + (nd.description() == null || nd.description().isBlank() ? "" : " (" + nd.description().replace('|', '/') + ")");
    }

    /** Parsed, not compared as text: `…12Z` sorts after `…12.5Z` as a string. */
    static long epochMs(String iso) {
        try { return Instant.parse(iso).toEpochMilli(); } catch (Exception e) { return 0L; }
    }

    /**
     * Agent tool call → that subagent's own transcript. Observed layout, not documented:
     * `<session>.jsonl` sits next to `<session>/subagents/agent-<agentId>.jsonl`, and a
     * sibling `.meta.json` carries the `toolUseId` of the call that spawned it — the same
     * `tool_use_id` PreToolUse delivered.
     */
    static Map<String, Path> subagentTranscripts(String transcript) {
        Map<String, Path> out = new HashMap<>();
        if (transcript == null || !transcript.endsWith(".jsonl")) return out;
        Path sub = Paths.get(transcript.substring(0, transcript.length() - ".jsonl".length()))
                .resolve("subagents");
        if (!Files.isDirectory(sub)) return out;
        try (Stream<Path> s = Files.list(sub)) {
            for (Path meta : s.filter(f -> f.toString().endsWith(".meta.json")).collect(Collectors.toList())) {
                String id = asStr(get(Json.parse(readOrNull(meta)), "toolUseId"));
                String fn = meta.getFileName().toString();
                if (id != null) {
                    out.put(id, meta.resolveSibling(fn.substring(0, fn.length() - ".meta.json".length()) + ".jsonl"));
                }
            }
        } catch (IOException ignored) { }
        return out;
    }

    static String agentIdOf(Path subagentTranscript) {
        String fn = subagentTranscript.getFileName().toString();
        return fn.replaceFirst("^agent-", "").replaceFirst("\\.jsonl$", "");
    }

    /**
     * {@code audit genesis <project> <session-id>} — fills the three figures of a freshly generated
     * project's GENESIS record from this session's transcripts: Started (the `/init-project`
     * message), Finished (now) and the tokens — no cost, the trail prices nothing (0134). Form 7c,
     * run by `/init-project` through Bash after its agent returns and before `git-publish`; no
     * event invokes it, and it writes nothing in this repository, whose trail stays off. A number
     * the model wrote would be memory — a start recovered from a directory's birth time once
     * landed after the finish, in local time with a literal `Z` (lessons-learned-020 § 4) — so
     * the agent leaves the placeholders and this reads the runtime's own record; the rejected
     * alternative, the agent counting its own transcript, sees neither the main session nor its
     * own last turns. Every turn of the main transcript and of each subagent transcript from
     * Started on is counted, deduplicated by message id like every audit report. Names in
     * `audit.genesis` of extensions.json (invariant 10). Exit 1, saying why, on anything it
     * cannot fill; it never overwrites a filled record.
     *
     * Started has two anchors, and the latest of either wins. A typed `/init-project` leaves a
     * `<command-name>` tag in a user entry; a session that reaches the skill through the Skill
     * tool leaves none, only a tool result whose `toolUseResult.commandName` is the skill, so
     * Started is the prompt that shares that result's `promptId`, or the result itself when no
     * prompt does (0164, issue #146). Why this is Form 7c: the figures must come from a
     * deterministic reader (0123), the closest rejected form is a `permissions.deny` on the
     * Skill tool, which refuses the path instead of measuring it and rests on a runtime
     * behavior nobody proved.
     */
    static void auditGenesis(String[] args) throws Exception {
        if (args.length < 4) {
            err("Usage: ArchHook audit genesis <project> <session-id>");
            System.exit(1);
        }
        Map<String, Object> cfg = asMap(get(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))), "audit", "genesis"));
        if (cfg == null) {
            err("❌ audit genesis: no `audit.genesis` block in " + ROOT.resolve(SCHEMA_FILE));
            System.exit(1);
        }
        Map<String, Object> ph = asMap(cfg.get("placeholders"));
        String pStart = asStr(get(ph, "started")), pEnd = asStr(get(ph, "finished")),
               pTokens = asStr(get(ph, "tokens"));
        Path project = Paths.get(args[2]).toAbsolutePath().normalize();
        Path genesis = project.resolve(asStr(cfg.get("file")));
        String body = readOrNull(genesis);
        if (body == null) {
            err("❌ audit genesis: " + genesis + " does not exist — step 8.6 of project-bootstrap writes it.");
            System.exit(1);
        }
        if (!body.contains(pStart) || !body.contains(pEnd) || !body.contains(pTokens)) {
            err("❌ audit genesis: " + genesis + " has no " + pStart + ", " + pEnd + " or " + pTokens
                    + " left — already filled, or written without them. Nothing was changed.");
            System.exit(1);
        }

        String session = args[3];
        String cfgDir = System.getenv("CLAUDE_CONFIG_DIR");
        Path projects = (cfgDir != null && !cfgDir.isBlank() ? Paths.get(cfgDir)
                : Paths.get(System.getProperty("user.home"), ".claude")).resolve("projects");
        Path transcript = null;
        if (Files.isDirectory(projects)) {
            try (Stream<Path> s = Files.list(projects)) {
                transcript = s.map(d -> d.resolve(session + ".jsonl")).filter(Files::isRegularFile)
                        .findFirst().orElse(null);
            }
        }
        if (transcript == null) {
            err("❌ audit genesis: no " + session + ".jsonl under " + projects + " — pass this session's id.");
            System.exit(1);
        }

        String command = asStr(cfg.get("command"));
        String tag = "<command-name>/" + command + "</command-name>";
        List<String> lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        long start = 0;
        for (String line : lines) {
            boolean typed = line.contains(tag), viaSkill = line.contains("commandName");
            if (!typed && !viaSkill) continue;
            Object e = Json.parse(line);
            if (!"user".equals(asStr(get(e, "type")))) continue;
            // The typed command is a user message whose content is a plain string. A tool result
            // is a user message too, with a list of blocks — and one that echoes a file citing
            // the tag would otherwise move Started to whenever that file was read.
            if (typed && get(e, "message", "content") instanceof String c && c.contains(tag)) {
                start = Math.max(start, epochMs(asStr(get(e, "timestamp"))));
            }
            // The Skill tool leaves no tag. Its tool result carries the skill's name in a field
            // the runtime writes and no file can echo into.
            if (viaSkill && command.equals(asStr(get(e, "toolUseResult", "commandName")))) {
                start = Math.max(start, skillPromptStart(lines, e));
            }
        }
        if (start == 0) {
            err("❌ audit genesis: no " + tag + " message and no Skill tool result for `" + command
                    + "` in " + transcript + " — nothing to measure from. Start the skill by typing /" + command
                    + " as the first characters of the prompt.");
            System.exit(1);
        }
        long end = System.currentTimeMillis();
        if (end < start) {
            err("❌ audit genesis: Finished would precede Started (" + Instant.ofEpochMilli(start) + ") — check the clock.");
            System.exit(1);
        }

        List<Path> all = new ArrayList<>(List.of(transcript));
        all.addAll(subagentTranscripts(transcript.toString()).values());
        Usage u = new Usage();
        int requests = 0;
        for (Path p : all) {
            for (Turn t : usageTurns(p)) {
                if (t.model() == null || t.t() < start) continue;
                u.add(t.model(), t.u());
                requests++;
            }
        }
        String tokens = String.format(Locale.ROOT,
                "%,d input · %,d output · %,d cache read · %,d cache write — %d requests, %s",
                u.in(), u.out(), u.cacheRead(), u.cacheWrite(), requests, orDash(u.model()));
        String iso0 = Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.SECONDS).toString();
        String iso1 = Instant.ofEpochMilli(end).truncatedTo(ChronoUnit.SECONDS).toString();
        Files.writeString(genesis, body.replace(pStart, iso0).replace(pEnd, iso1)
                .replace(pTokens, tokens), StandardCharsets.UTF_8);
        System.out.println("✅ GENESIS filled — " + genesis);
        System.out.println("   Started " + iso0 + " · Finished " + iso1);
        System.out.println("   Tokens  " + tokens);
    }

    /**
     * Started of a run that entered through the Skill tool: the earliest plain-string user entry
     * sharing the tool result's `promptId` — the prompt that made the model call the tool — or
     * the result's own timestamp when the id is missing or no prompt carries it. Only a prompt
     * at or before the result counts.
     */
    static long skillPromptStart(List<String> lines, Object toolResult) {
        long at = epochMs(asStr(get(toolResult, "timestamp")));
        String promptId = asStr(get(toolResult, "promptId"));
        if (promptId == null) return at;
        long first = at;
        for (String line : lines) {
            if (!line.contains(promptId)) continue;
            Object e = Json.parse(line);
            if ("user".equals(asStr(get(e, "type"))) && promptId.equals(asStr(get(e, "promptId")))
                    && get(e, "message", "content") instanceof String) {
                long t = epochMs(asStr(get(e, "timestamp")));
                if (t > 0 && t < first) first = t;
            }
        }
        return first;
    }

    // ── audit summary ────────────────────────────────────────────────────────
    //
    // The consolidated view `/audit-usage` injects. Aggregation runs here and not in the
    // model: the ledger grows without bound, and injecting it raw costs tokens on every
    // read and leaves the arithmetic to the piece most likely to get it wrong. Output is
    // bounded — 15 runs, one line per piece — whatever the ledger's size.

    static void auditSummary(Path dir) throws IOException {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        List<Map<String, Object>> runs = jsonl(dir.resolve("history.jsonl"));
        List<Map<String, Object>> nested = jsonl(dir.resolve("nodes.jsonl"));
        StringBuilder o = new StringBuilder();

        Set<String> closed = new HashSet<>();
        for (Map<String, Object> r : runs) closed.add(asStr(r.get("report")));
        List<String> open = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            // Reports only: `<timestamp>--<piece>.md`. GENESIS.md and anything a person drops here are not runs.
            s.filter(f -> f.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}T.*--.+\\.md"))
             .map(ArchHook::relative).filter(f -> !closed.contains(f))
             .sorted(Comparator.reverseOrder()).forEach(open::add);
        }

        if (runs.isEmpty()) {
            o.append("closed runs: 0 — `history.jsonl` missing or empty\n");
        } else {
            String first = orDash(asStr(runs.get(0).get("start")));
            String last  = orDash(asStr(runs.get(runs.size() - 1).get("start")));

            // ── spend per piece: root self + nested self, never the run total twice ──
            Map<String, long[]> tok = new LinkedHashMap<>();      // tokens, roots, nested, preloaded
            Map<String, int[]> health = new LinkedHashMap<>();    // failed, runs — roots only
            // Same self-only discipline for tool calls and peak context. Rows written before
            // these fields existed carry none and add nothing — unknown, never zero.
            Map<String, Map<String, Long>> toolsBy = new LinkedHashMap<>();
            Map<String, Long> peakBy = new LinkedHashMap<>();
            long sumTok = 0, sumDur = 0;
            int failed = 0;
            for (Map<String, Object> r : runs) {
                String key = pieceKey(r);
                boolean hasSelf = r.get("tokens_self") != null;
                long self = lnum(r.get(hasSelf ? "tokens_self" : "tokens_billable"));
                long[] t = tok.computeIfAbsent(key, k -> new long[4]);
                t[0] += self; t[1]++;
                mergeTools(toolsBy, key, asStr(r.get("tool_calls_self")));
                mergePeak(peakBy, key, r.get("peak_context_self"));
                sumTok += lnum(r.get("tokens_billable"));
                sumDur += lnum(r.get("duration_ms"));
                boolean bad = lnum(r.get("failures")) > 0 || orEmpty(asStr(r.get("status"))).startsWith("❌");
                if (bad) failed++;
                int[] h = health.computeIfAbsent(key, k -> new int[2]);
                if (bad) h[0]++;
                h[1]++;
            }
            for (Map<String, Object> r : nested) {
                String key = pieceKey(r);
                long[] t = tok.computeIfAbsent(key, k -> new long[4]);
                if ("preloaded".equals(asStr(r.get("origin")))) { t[3]++; continue; }
                t[2]++;
                mergeTools(toolsBy, key, asStr(r.get("tool_calls")));
                mergePeak(peakBy, key, r.get("peak_context"));
                if (r.get("tokens_self") == null) continue;
                t[0] += lnum(r.get("tokens_self"));
            }

            o.append("closed runs: ").append(runs.size())
             .append(" · period: ").append(first).append(" → ").append(last)
             .append(" · distinct pieces: ").append(tok.size()).append("\n\n");

            o.append("### Latest runs\n\n")
             .append("| # | 🕐 When | 🎯 Piece | 🙋 Origin | 🤖 Model | Status | ⏱️ Duration | 🧮 Billable | 🛠️ Calls | 📏 Peak | 📁 Files | 🔁 Failures |\n")
             .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            int shown = 0;
            for (int i = runs.size() - 1; i >= 0 && shown < AUDIT_RECENT_RUNS; i--, shown++) {
                Map<String, Object> r = runs.get(i);
                String st = orEmpty(asStr(r.get("status")));
                String calls = asStr(r.get("tool_calls"));
                o.append("| ").append(shown + 1).append(" | ").append(orDash(asStr(r.get("start"))))
                 .append(" | `").append(pieceLabel(r)).append("` | ")
                 .append("model".equals(asStr(r.get("origin"))) ? "model" : "user").append(" | ")
                 // Absent in every row written before this column existed: a run recorded
                 // by an older hook shows `—`, not a model it never knew.
                 .append(orDash(asStr(r.get("model")))).append(" | ")
                 // The emoji alone: rows written before 0085 carry a Portuguese label after it.
                 .append(st.isEmpty() ? "—" : st.substring(0, Math.max(1, st.indexOf(' ')))).append(" | ")
                 .append(hms(lnum(r.get("duration_ms")))).append(" | ")
                 .append(n(lnum(r.get("tokens_billable")))).append(" | ")
                 .append(calls == null ? "—" : String.valueOf(sumCalls(calls))).append(" | ")
                 .append(r.get("peak_context") == null ? "—" : n(lnum(r.get("peak_context")))).append(" | ")
                 .append(lnum(r.get("files"))).append(" | ").append(lnum(r.get("failures"))).append(" |\n");
            }
            if (runs.size() > AUDIT_RECENT_RUNS) {
                o.append("\n… ").append(runs.size() - AUDIT_RECENT_RUNS).append(" more runs\n");
            }

            o.append("\n### Totals\n\n")
             .append("billable: ").append(n(sumTok)).append(" tok")
             .append(" · active duration: ").append(hms(sumDur))
             .append(" · runs: ").append(runs.size()).append("\n\n");

            long pieceSum = Math.max(1, tok.values().stream().mapToLong(a -> a[0]).sum());
            List<Map.Entry<String, long[]>> ranked = new ArrayList<>(tok.entrySet());
            ranked.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            o.append("### Spend per piece (own tokens — root + nested, never counted twice)\n\n```text\n");
            for (Map.Entry<String, long[]> e : ranked) {
                long[] t = e.getValue();
                String calls = t[1] + t[2] == 0 ? t[3] + "× preloaded"
                        : (t[1] + t[2]) + "×"
                          + (t[2] > 0 ? " (" + t[1] + " root · " + t[2] + " nested)" : "")
                          + (t[3] > 0 ? " · " + t[3] + "× preloaded" : "");
                o.append(pad(keyLabel(e.getKey()), 34)).append(barW((double) t[0] / pieceSum, 25)).append(' ')
                 .append(pad(pct(t[0], pieceSum), 5)).append(pad(n(t[0]) + " tok", 16))
                 .append(calls).append('\n');
            }
            o.append("```\n");

            if (!toolsBy.isEmpty() || !peakBy.isEmpty()) {
                o.append("\n### Where the pieces spent (tool calls, top 5 tools · largest single request)\n\n```text\n");
                List<String> keys = new ArrayList<>(toolsBy.keySet());
                for (String k : peakBy.keySet()) if (!keys.contains(k)) keys.add(k);
                keys.sort(Comparator.comparingLong((String k) -> toolsBy.getOrDefault(k, Map.of())
                        .values().stream().mapToLong(Long::longValue).sum()).reversed());
                for (String k : keys) {
                    Map<String, Long> m = toolsBy.getOrDefault(k, Map.of());
                    long total = m.values().stream().mapToLong(Long::longValue).sum();
                    List<Map.Entry<String, Long>> top = new ArrayList<>(m.entrySet());
                    top.sort(Map.Entry.<String, Long>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()));
                    String by = top.stream().limit(5).map(e -> e.getKey() + " " + e.getValue())
                            .collect(Collectors.joining(" · ")) + (top.size() > 5 ? " · …" : "");
                    o.append(pad(keyLabel(k), 34)).append(pad(total + " calls", 12))
                     .append(pad("peak " + (peakBy.containsKey(k) ? n(peakBy.get(k)) : "—"), 18))
                     .append(by).append('\n');
                }
                o.append("```\n");
            }

            o.append(planWindowsSummary(dir, runs, nested));

            o.append("\n### Health\n\n").append("failure rate: ").append(failed).append('/')
             .append(runs.size()).append(" (").append(pct(failed, runs.size())).append(")");
            health.entrySet().stream().filter(e -> e.getValue()[0] > 0)
                  .max(Comparator.comparingDouble(e -> (double) e.getValue()[0] / e.getValue()[1]))
                  .ifPresent(e -> o.append(" · worst: ").append(keyLabel(e.getKey())).append(" (")
                          .append(e.getValue()[0]).append('/').append(e.getValue()[1]).append(')'));
            o.append("\n\n### Open\n\n");
            Map<String, Object> newest = runs.get(runs.size() - 1);
            o.append("most recent: `").append(asStr(newest.get("report"))).append("`\n");
            for (int i = runs.size() - 1; i >= 0; i--) {
                Map<String, Object> r = runs.get(i);
                if (lnum(r.get("failures")) > 0 || orEmpty(asStr(r.get("status"))).startsWith("❌")) {
                    o.append("most recent with failures: `").append(asStr(r.get("report"))).append("`\n");
                    break;
                }
            }
        }

        if (!open.isEmpty()) {
            o.append("\n### ⏳ No ledger line (in progress, or the session died)\n\n");
            open.stream().limit(5).forEach(f -> o.append("- `").append(f).append("`\n"));
            if (open.size() > 5) o.append("- … ").append(open.size() - 5).append(" more\n");
        }
        out.print(o);
    }

    /** Adds a ledger `Bash:5,Read:12` string into a piece's per-tool totals. */
    static void mergeTools(Map<String, Map<String, Long>> into, String key, String ledger) {
        if (ledger == null || ledger.isBlank()) return;
        Map<String, Long> m = into.computeIfAbsent(key, k -> new LinkedHashMap<>());
        for (String part : ledger.split(",")) {
            int c = part.lastIndexOf(':');
            if (c <= 0) continue;
            long v = lnum(part.substring(c + 1));
            if (v > 0) m.merge(part.substring(0, c), v, Long::sum);
        }
    }

    static void mergePeak(Map<String, Long> into, String key, Object value) {
        long v = lnum(value);
        if (v > 0) into.merge(key, v, Math::max);
    }

    static long sumCalls(String ledger) {
        Map<String, Map<String, Long>> m = new HashMap<>();
        mergeTools(m, "", ledger);
        return m.getOrDefault("", Map.of()).values().stream().mapToLong(Long::longValue).sum();
    }

    static List<Map<String, Object>> jsonl(Path p) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isRegularFile(p)) return out;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            Map<String, Object> m = asMap(Json.parse(line));
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Lines written before this field existed are skills — only skills were audited then. */
    static String pieceKey(Map<String, Object> row) {
        return ("agent".equals(asStr(row.get("kind"))) ? "agent" : "skill") + ":" + orDash(asStr(row.get("skill")));
    }

    static String keyLabel(String key) {
        return (key.startsWith("agent:") ? "🤖 " : "📘 ") + key.substring(key.indexOf(':') + 1);
    }

    static String pieceLabel(Map<String, Object> row) {
        String name = orDash(asStr(row.get("skill")));
        return "agent".equals(asStr(row.get("kind"))) ? "🤖 " + name
                : "model".equals(asStr(row.get("origin"))) ? "Skill(" + name + ")" : "/" + name;
    }

    /** The ledgers write every value as a string. */
    static long lnum(Object o) {
        if (o instanceof Number x) return x.longValue();
        try { return o instanceof String s ? Long.parseLong(s.strip()) : 0L; }
        catch (NumberFormatException e) { return 0L; }
    }


    static String barW(double fraction, int width) {
        int full = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        return "█".repeat(full) + "░".repeat(width - full);
    }

    /** One rule whose `paths` captured at least one file touched during the run. */
    record RuleHit(String rule, String glob, List<String> files) {}

    /**
     * Rules whose `paths` capture at least one file touched during the run. A record
     * keyed by nothing beats a string key split back apart at render time: the previous
     * "file glob" key joined the two with a literal NUL byte as separator, invisible in
     * an editor, and any `split(" ", 2)` on a rule or glob containing a real space threw
     * `ArrayIndexOutOfBoundsException` the moment a touched file actually matched one --
     * silently, since the hook's own top-level catch swallows it and exits 0. That froze
     * every report the instant a run touched `src/**` for good, `flush` and `close`
     * included: see `.claude/decisions/0041-audit-background-subagent-tracking.md`.
     */
    static List<RuleHit> auditRules(Set<String> touched) throws IOException {
        List<RuleHit> hits = new ArrayList<>();
        Path rules = ROOT.resolve(".claude/rules");
        if (!Files.isDirectory(rules) || touched.isEmpty()) return hits;
        try (Stream<Path> s = Files.list(rules)) {
            List<Path> files = s.filter(f -> f.toString().endsWith(".md")).sorted()
                    .collect(Collectors.toList());
            for (Path f : files) {
                for (String g : ruleGlobs(f)) {
                    Pattern re = glob(g);
                    List<String> matched = touched.stream()
                            .filter(t -> re.matcher(t).matches()).sorted()
                            .collect(Collectors.toList());
                    if (!matched.isEmpty()) {
                        hits.add(new RuleHit(f.getFileName().toString(), g, matched));
                    }
                }
            }
        }
        return hits;
    }

    /**
     * The `paths` of a rule. `frontmatter()` reads unindented scalars only, and this
     * repo writes `paths` as an indented block list — so the block is read here.
     */
    static List<String> ruleGlobs(Path file) {
        String c = readOrNull(file);
        if (c == null) return List.of();
        List<String> out = new ArrayList<>();
        boolean inside = false;
        for (String raw : c.lines().collect(Collectors.toList())) {
            String l = raw.strip();
            if (raw.startsWith("paths:")) { inside = true; continue; }
            if (!inside) continue;
            if (l.startsWith("#")) continue;
            if (!l.startsWith("- ")) break;
            out.add(l.substring(2).strip().replaceAll("^[\"']|[\"']$", ""));
        }
        return out;
    }

    static List<String> gitLog(String from, String to) {
        try {
            Proc p = run(gitCmd(), "log", "--oneline", from + ".." + to);
            return p.exit == 0 && !p.out.isEmpty() ? p.out : List.of("—");
        } catch (Exception e) { return List.of("—"); }
    }

    static String bar(double fraction) {
        int width = 20;
        int full = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        return "█".repeat(full) + "░".repeat(width - full);
    }

    static String hms(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%dh%02dm%02ds", s / 3600, (s % 3600) / 60, s % 60)
             : s >= 60   ? String.format(Locale.ROOT, "%dm%02ds", s / 60, s % 60)
                         : s + "s";
    }

    static String pct(long part, long whole) {
        return whole <= 0 ? "0%" : Math.round(100.0 * part / whole) + "%";
    }

    static String n(long v) { return String.format(Locale.ROOT, "%,d", v); }

    static String pad(String s, int width) {
        return s.length() >= width ? s.substring(0, width - 1) + " "
                                   : s + " ".repeat(width - s.length());
    }

    static long num(Object o) { return o instanceof Number x ? x.longValue() : 0L; }

    static Double dbl(Object o) { return o instanceof Number x ? x.doubleValue() : null; }

    static String orDash(String s) { return s == null || s.isBlank() ? "—" : s; }

    static String orEmpty(String s) { return s == null ? "" : s; }

    // ── guard ────────────────────────────────────────────────────────────────
    //
    // Three boundaries the pipeline broke in real runs, while its skills said the opposite
    // in prose:
    //   1. a design skill wrote a migration under src/ — src/ belongs to the executor;
    //   2. a later use case edited the specs of an earlier, already-decided one;
    //   3. a design run wrote a service block into docker-compose.yml — which the denylist
    //      this mode used to carry (`src/**`) could not see, because the leaked file is
    //      never the one somebody thought to forbid. Hence the allowlist below.
    //
    // Phases (args[1]):
    //   prompt   UserPromptSubmit         a prompt ends any phase; `/<skill>` opens one
    //   call     PreToolUse Skill|Agent   a skill opens the phase; a `blocked_during_design`
    //                                     class is refused; a classed agent's call is skipped
    //   write    PreToolUse Write|Edit    blocks (exit 2) what the three boundaries forbid
    //
    // The phase is one file per session in the OS temp dir — never in the project, so it
    // can't be committed. It holds the active skills' NAMES, one per line — the opener, then
    // every same-class skill it chained (decision 0092); the class, the territories and
    // which agents execute are data (`skill_classes`, `agent_classes` and `guard` in
    // extensions.json — invariants 7, 10). Territory is deny-by-default: while a phase is
    // open, a write is allowed only where the active skill's `write_allow` says so. No phase
    // open means no restriction — a person editing a file by hand is not a skill overstepping.
    //
    // A write carrying an `agent_type` is judged by that AGENT's class instead, and the phase
    // is not consulted at all — nor is it by a `Skill` call carrying one (decision 0126). That is why nothing closes the phase on an Agent call any more:
    // the main thread's territory is not the subagent's business, and deleting the phase
    // silently unrestricted the caller for the rest of the turn. It also retires the ordering
    // race of lessons-learned-006 § 1 for every classed agent — there is nothing left to
    // order, since the payload names the agent on every single write.
    //
    // Known gap, accepted: a design skill that asks in plain text instead of
    // AskUserQuestion gets its answer as a prompt, and that prompt ends the phase.
    //
    // Known gap, accepted: an agent no class lists (a generic subagent, a plugin one) falls
    // back to the caller's phase, because nothing else describes what it may write.

    static void guard(String phase, String stdin) throws Exception {
        Map<String, Object> sch = asMap(Json.parse(readOrNull(ROOT.resolve(SCHEMA_FILE))));
        if (sch == null || (sch.get("guard") == null && sch.get("skill_classes") == null
                && sch.get("agent_classes") == null)) return;
        Object in = Json.parse(stdin);
        Path state = guardState(asStr(get(in, "session_id")));
        switch (phase) {
            case "prompt" -> guardPrompt(sch, state, in);
            case "call"   -> guardCall(sch, state, in);
            case "write"  -> guardWrite(sch, state, in);
            case "bash"   -> guardBash(sch, state, in);
            case "sweep"  -> guardSweep(sch, state, in, stdin);
            case "status" -> guardStatus(sch, state);
            default       -> { }
        }
    }

    static Path guardState(String session) {
        String s = session == null || session.isBlank() ? "unknown"
                : session.replaceAll("[^A-Za-z0-9_-]", "_");
        return Paths.get(System.getProperty("java.io.tmpdir"), "archhook-guard", s);
    }

    /**
     * `guard status` — the open phase as one JSON line on stdout: its skills (opener first),
     * their class, the union of their `write_allow`, and the `mods.deny_markers` a refusal of
     * this mode carries. Read-only: it opens, closes and judges nothing, and an empty `phase`
     * means no skill phase is open.
     *
     * <p>Form 7c of `claude-code-architect-designer`, invoked by the `nerviz-cockpit` mod
     * through `$.process.run` rather than by a hook event, with the session's id on stdin.
     * Axis 9 motivated it: the band above the prompt shows the phase `guard prompt` and
     * `guard call` opened, and the only other way to know it was to re-derive the phase in
     * TypeScript — a second owner of the rule, invariant 2. The closest rejected form was the
     * mod reading the state file under `java.io.tmpdir` itself: its location and line format
     * would become a contract nobody owns. Design:
     * `.claude/decisions/0131-mods-in-architect-designer.md`.
     */
    static void guardStatus(Map<String, Object> sch, Path state) {
        List<String> phase = phaseSkills(state);
        String cls = phase.isEmpty() ? null : skillClassOf(sch, phase.get(0));
        List<String> allow = new ArrayList<>();
        for (String s : phase) {
            for (String g : writeAllowOf(sch, s)) if (!allow.contains(g)) allow.add(g);
        }
        System.out.println("{\"phase\":" + jsonArray(phase)
                + ",\"class\":" + (cls == null ? "null" : "\"" + jsonEscape(cls) + "\"")
                + ",\"write_allow\":" + jsonArray(allow)
                + ",\"deny_markers\":" + jsonArray(asStrList(get(sch, "mods", "deny_markers")))
                + "}");
    }

    static String jsonArray(List<String> items) {
        return items.stream().map(i -> "\"" + jsonEscape(i) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    static void guardPrompt(Map<String, Object> sch, Path state, Object in) throws IOException {
        Files.deleteIfExists(state);
        Files.deleteIfExists(guardAdmittedFile(state));
        guardBaseline(state);
        Matcher m = Pattern.compile("^\\s*/([a-z0-9][a-z0-9-]*)").matcher(orEmpty(asStr(get(in, "prompt"))));
        if (m.find() && skillClassOf(sch, m.group(1)) != null) guardOpen(state, m.group(1));
    }

    /** Where `guard sweep` finds the working tree as it stood when the turn began. */
    static Path guardBaselineFile(Path state) {
        return state.resolveSibling(state.getFileName() + ".baseline");
    }

    /**
     * The paths `guard write` and `guard bash` admitted this turn, one per line. Each was judged
     * against the phase and the frozen state of its own moment, so `guard sweep` skips them and
     * judges only what no tool-time guard saw. Re-judging them at `Stop` is what flagged
     * `/new-feature`'s own spec once `git-publish` (class `ops`) narrowed the phase, and its
     * just-approved folder as immutable: issue #74,
     * `.claude/decisions/0114-guard-sweep-judges-only-unseen-writes.md`.
     */
    static Path guardAdmittedFile(Path state) {
        return state.resolveSibling(state.getFileName() + ".admitted");
    }

    /**
     * Records `git status --porcelain` at `UserPromptSubmit`, so the `Stop` sweep can tell what
     * this turn wrote from what was already dirty when it started. Without the baseline the
     * sweep opens every run by reporting somebody's half-finished edit from before it — the
     * shape lessons-learned-014 § 11 records from the other direction, where the entry
     * guardrail read the index alone and swept two pre-existing changes into the run's commit.
     *
     * <p>`--untracked-files=all` is not optional: plain porcelain collapses a brand-new
     * directory into one `?? src/` entry, and a heredoc that creates the first file under a new
     * path is exactly the write this sweep exists to see.
     */
    static void guardBaseline(Path state) {
        try {
            Proc p = run(gitCmd(), "status", "--porcelain", "--untracked-files=all");
            Files.createDirectories(state.getParent());
            Files.writeString(guardBaselineFile(state),
                    p.exit == 0 ? String.join("\n", p.out) : "", StandardCharsets.UTF_8);
        } catch (Exception e) {
            // No git, no baseline: the sweep returns instead of guessing.
        }
    }

    /**
     * Judges a `Skill` call against the open phase: refuses a `blocked_during_design` callee,
     * then joins or replaces the phase. A call carrying the `agent_type` of a classed agent is
     * skipped whole, because that agent's writes are judged by its own `write_allow` and never
     * by the phase. Refusing the call would protect nothing, and opening the callee's phase
     * would overwrite the main thread's phase (issue #99: `project-initializer` chaining
     * `transport-security-setup` under `/init-project`). Invoked on `PreToolUse`
     * `Skill|Agent|Task`. The rejected alternative was dropping `design_phase` from
     * `orchestrator`, which would let `/new-feature` reach `build` skills again. Design:
     * .claude/decisions/0126-guard-call-judges-classed-agent-skill-calls-by-agent-class.md
     */
    static void guardCall(Map<String, Object> sch, Path state, Object in) throws IOException {
        if (agentClassOf(sch, asStr(get(in, "agent_type"))) != null) return;
        String tool = asStr(get(in, "tool_name"));
        if ("Skill".equals(tool)) {
            String skill = asStr(get(in, "tool_input", "skill"));
            String cls = skillClassOf(sch, skill);
            if (cls == null) return;                       // plugin skill: not our territory
            guardRefuseBuildCall(sch, state, skill, cls);
            // A skill of the phase's own class JOINS the phase instead of replacing it: the
            // territory becomes the union of both. Replacing would shrink the caller's
            // territory for the rest of the turn — project-bootstrap chains docker-architect
            // in its step 4.10 and then keeps writing src/. Keeping only the caller's, the rule
            // before decision 0092, assumed two skills of one class share a territory, which
            // `build` does not: each of its skills carries its own override, so arch-adopt
            // (`.claude/**`) chaining sonarqube-setup refused every write the callee exists
            // for, and so did sonarqube-setup chaining docker-architect. A skill of another
            // class still replaces the phase — that narrowing is what keeps a design skill
            // called from `/new-feature` inside docs.
            List<String> active = phaseSkills(state);
            if (!active.isEmpty() && cls.equals(skillClassOf(sch, active.get(0)))) {
                if (!active.contains(skill)) guardJoin(state, skill);
                return;
            }
            guardOpen(state, skill);
        }
        // An `Agent`/`Task` call changes nothing here. The subagent's own writes carry
        // `agent_type` and are judged by `agent_classes`; the caller's phase stays as it was.
    }

    /**
     * A `blocked_during_design` class cannot be reached from inside an open `design_phase`
     * one. This is what makes the design pipeline docs-only in the mechanism rather than in
     * prose: `docker-architect` owns docker-compose.yml, and a `/new-feature` run reaches it
     * by reporting the missing service, not by writing the file mid-design.
     */
    static void guardRefuseBuildCall(Map<String, Object> sch, Path state, String skill, String cls)
            throws IOException {
        if (!Boolean.TRUE.equals(get(sch, "skill_classes", "classes", cls, "blocked_during_design"))) return;
        List<String> phase = phaseSkills(state);
        if (phase.isEmpty()) return;
        String active = phase.get(0);
        String activeCls = skillClassOf(sch, active);
        if (activeCls == null
                || !Boolean.TRUE.equals(get(sch, "skill_classes", "classes", activeCls, "design_phase"))) {
            return;
        }
        err("❌ `" + skill + "` is class `" + cls + "` and `" + active + "` (class `"
                + activeCls + "`) is open — a design run does not materialize files.");
        err("Record what is missing in the partial and in the consolidated spec, finish the run,");
        err("then invoke `/" + skill + "` from a prompt of its own.");
        System.exit(2);
    }

    static void guardOpen(Path state, String skill) throws IOException {
        Files.createDirectories(state.getParent());
        Files.writeString(state, skill, StandardCharsets.UTF_8);
    }

    /** Adds a same-class callee to the open phase, one skill name per line. */
    static void guardJoin(Path state, String skill) throws IOException {
        Files.writeString(state, "\n" + skill, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /**
     * The skills of the open phase, opener first — empty when no phase is open. Every name
     * after the first joined through {@link #guardCall} and shares the opener's class, so the
     * class is read from the first and the territory is the union of all.
     */
    static List<String> phaseSkills(Path state) {
        String raw = readOrNull(state);
        if (raw == null) return List.of();
        return Arrays.stream(raw.split("\n")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    static void guardWrite(Map<String, Object> sch, Path state, Object in) throws IOException {
        String file = asStr(get(in, "tool_input", "file_path"));
        if (file == null) return;
        guardPath(sch, state, in, relative(Paths.get(file)));
    }

    /**
     * The two checks themselves, over one repo-relative path: class territory first, frozen
     * `UC-NNN` folder second. Split out of `guardWrite` so `guard bash` applies exactly the
     * same rules to a path it parsed out of a shell command — invariant 2, one owner. A
     * territory widened in `skill_classes` widens for both entry points at once, because there
     * is no second copy of the rule to forget.
     *
     * <p>`in` still travels through: the two spec exemptions below read `tool_name` and
     * `old_string`/`new_string` from it and already require `Edit`, so a Bash-sourced write
     * fails them by construction. That is deliberate — `sed -i` cannot prove it is closing a
     * `status:` line, and the legitimate path for that single write is the `Edit` the executor
     * already makes.
     *
     * <p>An admitted path goes into {@link #guardAdmittedFile}, so the `Stop` sweep does not
     * judge it a second time against later state.
     */
    static void guardPath(Map<String, Object> sch, Path state, Object in, String rel)
            throws IOException {
        List<String> violation = guardViolations(sch, state, in, rel, false);
        if (violation.isEmpty()) {
            Files.createDirectories(state.getParent());
            Files.writeString(guardAdmittedFile(state), rel + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return;
        }
        violation.forEach(ArchHook::err);
        System.exit(2);
    }

    /**
     * The two rules themselves, over one repo-relative path, as the lines they would print —
     * empty when the path is admissible. Returning instead of exiting is what lets `guard sweep`
     * report every offending path of a turn at once while `guard write` and `guard bash` keep
     * stopping at the first: one owner for the rules, three callers, invariant 2.
     *
     * <p>`sweep` is true only for the `Stop` sweep, which has no `old_string`/`new_string` to
     * read and therefore cannot recognise the two spec edits a frozen folder admits the way the
     * tool-time callers do. It compares against `git show HEAD:` instead — see
     * {@link #isSpecEditLegalOnDisk}.
     */
    static List<String> guardViolations(Map<String, Object> sch, Path state, Object in,
            String rel, boolean sweep) throws IOException {
        List<String> out = new ArrayList<>();
        Map<String, Object> cfg = asMap(sch.get("guard"));

        // 1. Territory, deny by default. `agent_type` is present only when the call comes from
        //    a subagent: an agent with a class is judged by ITS OWN territory and the caller's
        //    phase is irrelevant — no state file is read, so there is nothing to race with. An
        //    agent no class lists falls back to the phase, which is all that describes it.
        String agent = asStr(get(in, "agent_type"));
        String agentCls = agentClassOf(sch, agent);
        if (agentCls != null) {
            List<String> allow = agentWriteAllowOf(sch, agent);
            if (!matchesAny(allow, rel)) {
                out.add("❌ agent `" + agent + "` is class `" + agentCls + "` — " + rel
                        + " is outside its territory.");
                out.add("   write_allow: " + (allow.isEmpty() ? "(nothing — this class writes no file)"
                        : String.join(", ", allow)));
                out.add("Report the path in the summary you return and stop, or hand it to the piece");
                out.add("that owns it. If the path is legitimately this agent's, widen");
                out.add("agent_classes." + agentCls + " in " + SCHEMA_FILE + " — retrying will not help.");
                return out;
            }
        } else if (!phaseSkills(state).isEmpty()) {
            List<String> phase = phaseSkills(state);
            String active = String.join("` + `", phase);
            String cls = skillClassOf(sch, phase.get(0));
            List<String> allow = new ArrayList<>();
            for (String s : phase) {
                for (String g : writeAllowOf(sch, s)) if (!allow.contains(g)) allow.add(g);
            }
            if (cls != null && !matchesAny(allow, rel)) {
                out.add("❌ `" + active + "` is class `" + cls + "` — " + rel
                        + " is outside its territory.");
                out.add("   write_allow: " + (allow.isEmpty() ? "(nothing — this class writes no file)"
                        : String.join(", ", allow)));
                out.add("Put the content in the spec as a code block, or finish the run and invoke the");
                out.add("skill that owns this path. If the path is legitimately this skill's, widen");
                out.add("skill_classes." + cls + " in " + SCHEMA_FILE + " — retrying will not help.");
                return out;
            }
        }

        // 2. A folder whose consolidated spec is approved or implemented is frozen.
        if (cfg == null) return out;
        Matcher m = Pattern.compile("^" + Pattern.quote(orEmpty(asStr(cfg.get("use_cases_dir")))) + "/(UC-[^/]+)/")
                .matcher(rel);
        if (!m.find()) return out;
        Path folder = ROOT.resolve(asStr(cfg.get("use_cases_dir"))).resolve(m.group(1));
        String status = specStatus(folder);
        if (status == null || !asStrList(cfg.get("frozen_statuses")).contains(status)) return out;
        if (isStatusClose(cfg, in, rel, status) || isChecklistToggle(cfg, in, rel, status)
                || isFrozenExempt(cfg, rel.substring(m.end()))
                || (sweep && isSpecEditLegalOnDisk(cfg, rel))) {
            return out;
        }
        List<String> exempt = asStrList(cfg.get("frozen_exempt_basenames"));
        out.add("❌ " + m.group(1) + " is " + status + " — its specs are immutable.");
        out.add("Record the change in the new use case's \"Impact on approved use cases\" section,");
        out.add("and the one-line entry in " + m.group(1) + "/CHANGELOG.md, which stays writable.");
        // The transitions come from guard.status_transitions, the owner isStatusClose reads too.
        List<String> to = asStrList(get(cfg, "status_transitions", status));
        out.add("Writable inside a frozen folder: " + String.join(", ", exempt)
                + "; the spec's checklist toggles; and its `status:` line, "
                + (to.isEmpty() ? "which no tool moves from `" + status + "`."
                        : "only from `" + status + "` to " + to.stream().map(s -> "`" + s + "`")
                                .collect(Collectors.joining(" or ")) + "."));
        out.add("To reopen a spec that was never implemented, set `status: draft` by hand.");
        return out;
    }

    /**
     * `guard`, applied to what a shell command writes. Every other enforcement in this file is
     * reached through a tool-name matcher — `Write`, `Edit`, `MultiEdit`, `NotebookEdit` — so a
     * heredoc, a `sed -i` or a `tee` passed through none of them: the net was tool-shaped, not
     * filesystem-shaped, and a host setting that prefers `Bash` for edits disabled the whole of
     * it in silence (lessons-learned-014 § 1). The two guarantees with no second net are the
     * ones this closes: class territory, and the frozen `UC-NNN` folder. The layer boundary is
     * re-checked by ArchUnit at `./mvnw verify` and formatting by `spotless:apply`, which is why
     * `check` and `format` deliberately stay off `Bash` — a `PostToolUse` matcher there would
     * pay a JVM on every `ls`.
     *
     * <p><b>The bar is not completeness.</b> Shell has more ways to write a file than any parser
     * will hold, and one that blocked on what it could not read would stop `./mvnw` on its first
     * false positive and be deleted the same week. A target that does not resolve to a literal
     * path inside the repository is skipped without a word; a command with no recognized write
     * shape exits immediately. What this buys is that the obvious spellings — the ones in the
     * harness instruction that caused the incident — stop being free.
     *
     * <p>Shapes are data, `guard.bash_write_shapes` in {@code .claude/schemas/extensions.json}
     * — invariant 10, no list of command names in this file.
     *
     * <p>Registration: `PreToolUse`, matcher `Bash`, in `.claude/settings.json` and in
     * `project-bootstrap/templates/settings.json.example`. Design:
     * `.claude/decisions/0063-bash-write-enforcement.md`.
     */
    static void guardBash(Map<String, Object> sch, Path state, Object in) throws IOException {
        String cmd = asStr(get(in, "tool_input", "command"));
        if (cmd == null || cmd.isBlank()) return;
        String force = bashForcePush(asMap(get(sch, "guard", "force_push")), cmd, 0);
        if (force != null) {
            err("guard: force push blocked — `" + force + "` rewrites published history.");
            err("  Push without force. A shared branch is rewritten by a human, outside Claude"
                    + " Code. Rule: guard.force_push in " + SCHEMA_FILE);
            System.exit(2);
        }
        for (String rel : bashWriteTargets(asMap(get(sch, "guard", "bash_write_shapes")), cmd)) {
            guardPath(sch, state, in, rel);
        }
    }

    /**
     * Repo-relative paths a shell command writes, as far as they can be read literally.
     *
     * <p>The command is cut into segments on `;`, `&&`, `||`, `|` and newlines, and each segment
     * is read on its own. A heredoc body is skipped whole: `cat > f <<'EOF'` followed by
     * markdown whose lines start with `>` would otherwise read every blockquote as a redirect.
     * Quoting is honoured, so a `>` inside quotes is a character and not an operator.
     *
     * <p>Two ways a segment names a target: a redirect operator, whose target is the next token,
     * and a command in `bash_write_shapes.commands`, whose targets sit where its `targets` field
     * says — `all` operands, the `tail` after the first (a `sed` script), the `last` one, or the
     * ones carrying an `operand_prefix` (`dd of=`). A `requires_flag_prefix` entry only counts
     * when that flag is present, which is what keeps a read-only `sed` out of it.
     *
     * <p>Two operands a `tail` shape must not see. An empty word is skipped: it is the BSD
     * in-place suffix of `sed -i ''`, and taking it for the script pushed the real script into
     * the targets. A flag in the shape's `script_flags` (`-e`, `-f`) carries the script as its
     * value, so that value is consumed with the flag, and with a script supplied that way every
     * remaining operand is a file — `tail` becomes `all`. Both read `s/a/b/g` as a path and
     * blocked the executor in two real runs (decision 0118).
     */
    static List<String> bashWriteTargets(Map<String, Object> shapes, String cmd) {
        List<String> out = new ArrayList<>();
        if (shapes == null) return out;
        List<String> ops = asStrList(shapes.get("redirect_operators"));
        List<String> markers = asStrList(shapes.get("unresolvable_markers"));
        List<Object> commands = asList(shapes.get("commands"));
        for (List<Tok> seg : bashSegments(cmd)) {
            // 1. Redirection: the operator is bare, and its target is the token after it.
            for (int i = 0; i < seg.size() - 1; i++) {
                Tok t = seg.get(i);
                if (!t.quoted() && ops.contains(t.text())) {
                    addBashTarget(out, markers, seg.get(i + 1));
                }
            }
            // 2. A command whose own arguments are what it writes.
            List<Tok> words = new ArrayList<>();
            for (Tok t : seg) {
                if (!t.quoted() && ops.contains(t.text())) { words.clear(); continue; }
                words.add(t);
            }
            if (words.isEmpty()) continue;
            String head = bashCommandName(words.get(0).text());
            for (Object o : commands) {
                Map<String, Object> shape = asMap(o);
                if (shape == null || !head.equals(asStr(shape.get("name")))) continue;
                String flag = asStr(shape.get("requires_flag_prefix"));
                List<String> scriptFlags = asStrList(shape.get("script_flags"));
                List<Tok> operands = new ArrayList<>();
                boolean flagSeen = flag == null;
                boolean scriptByFlag = false;
                List<Tok> rest = words.subList(1, words.size());
                for (int i = 0; i < rest.size(); i++) {
                    Tok t = rest.get(i);
                    if (t.text().isEmpty()) continue;
                    if (t.text().startsWith("-") && !t.quoted()) {
                        if (flag != null && t.text().startsWith(flag)) flagSeen = true;
                        for (String sf : scriptFlags) {
                            if (t.text().equals(sf)) { scriptByFlag = true; i++; break; }
                            if (t.text().startsWith(sf + "=")) { scriptByFlag = true; break; }
                        }
                        continue;
                    }
                    operands.add(t);
                }
                if (!flagSeen || operands.isEmpty()) continue;
                String targets = orEmpty(asStr(shape.get("targets")));
                if (scriptByFlag && "tail".equals(targets)) targets = "all";
                switch (targets) {
                    case "all"  -> operands.forEach(t -> addBashTarget(out, markers, t));
                    case "tail" -> operands.subList(1, operands.size())
                                           .forEach(t -> addBashTarget(out, markers, t));
                    case "last" -> addBashTarget(out, markers, operands.get(operands.size() - 1));
                    case "prefixed" -> {
                        String p = orEmpty(asStr(shape.get("operand_prefix")));
                        for (Tok t : operands) {
                            if (t.text().startsWith(p)) {
                                addBashTarget(out, markers,
                                        new Tok(t.text().substring(p.length()), t.quoted()));
                            }
                        }
                    }
                    default -> { }
                }
            }
        }
        return out;
    }

    /**
     * Keeps a target only when it is literal and lands inside the repository. Anything holding
     * an expansion, a glob or a home reference is unreadable from here, and a path outside
     * `ROOT` is not this repository's territory to police — both are dropped in silence, which
     * is the rule that makes this hook safe to run on every command.
     */
    static void addBashTarget(List<String> out, List<String> markers, Tok tok) {
        String raw = tok.text();
        if (raw.isBlank()) return;
        for (String marker : markers) if (raw.contains(marker)) return;
        try {
            Path abs = ROOT.resolve(raw).toAbsolutePath().normalize();
            if (!abs.startsWith(ROOT.toAbsolutePath().normalize())) return;
            String rel = relative(abs);
            if (!rel.isBlank() && !out.contains(rel)) out.add(rel);
        } catch (Exception e) {
            // An unparseable path is an unreadable target, not a violation.
        }
    }

    /**
     * The same two rules, over everything this turn wrote, whatever wrote it.
     *
     * <p>`guard write` and `guard bash` are both tool-shaped: the first matches four tool names,
     * the second reads the shell spellings it knows and — deliberately — lets through what it
     * cannot parse, because a parser that blocked on a target it could not read would stop
     * `./mvnw` on its first false positive. This is the other shape of the rule. It looks at what
     * changed on disk, so a generated script, a `python3 -c` with `open(..., 'w')` or an editor
     * launched from the shell is caught the same as a heredoc.
     *
     * <p>It is detection, not prevention — the write already happened — which is why it backs
     * the tool-time guards instead of replacing them. And it is <b>git-shaped</b>: a path git
     * ignores never appears in `git status --porcelain` and is therefore never swept — whatever
     * `.gitignore` lists. `.claude/decisions/` and `.claude/lessons-learned/` were that case in
     * this repository until 2026-09-28; both are versioned now and are swept like anything else.
     *
     * <p>Only paths whose porcelain entry is new or changed since the baseline are read, so a
     * tree dirty before the turn stays out of the report. Exit 2 hands the lines back to the
     * model; `stop_hook_active` bounds it to one firing, the way `tests` already does.
     *
     * <p>A path matching `guard.sweep_exempt` is skipped before the territory check: those are
     * versioned files a hook writes in parallel with the baseline — the `audit` trail — so the
     * turn never wrote them, and the advice to revert would delete what `git-publish` commits.
     * Only here: `guard write` and `guard bash` still refuse the model those paths.
     *
     * <p>A path in {@link #guardAdmittedFile} is skipped too: a tool-time guard already admitted
     * it against the phase and the frozen state of its own moment. Judging it again here used the
     * phase open at `Stop` — `git-publish`'s empty territory after `/new-feature` — and the spec
     * status at `Stop`, `approved` by then, so the run's own spec, partials and executor writes
     * came back as violations (issue #74). What is left is what this sweep was built for, the
     * writes no tool-time guard saw, judged as before against the phase open now. Known gap,
     * accepted: an admitted path rewritten later in the turn by a spelling no guard reads is
     * skipped as well.
     * Design: `.claude/decisions/0065-guard-sweep-on-stop.md`.
     * Exemption: `.claude/decisions/0105-guard-sweep-exempts-audit-trail.md`.
     * Admitted paths: `.claude/decisions/0114-guard-sweep-judges-only-unseen-writes.md`.
     */
    static void guardSweep(Map<String, Object> sch, Path state, Object in, String stdin)
            throws Exception {
        if (Pattern.compile("\"stop_hook_active\"\\s*:\\s*true").matcher(stdin).find()) return;
        Path baselineFile = guardBaselineFile(state);
        if (!Files.isRegularFile(baselineFile)) return;
        Proc now = run(gitCmd(), "status", "--porcelain", "--untracked-files=all");
        if (now.exit != 0) return;                               // no git, nothing to compare
        Set<String> before = new LinkedHashSet<>(
                Arrays.asList(readOrNull(baselineFile).split("\n")));
        List<String> exempt = asStrList(get(sch, "guard", "sweep_exempt"));
        Set<String> admitted = new HashSet<>(
                Arrays.asList(orEmpty(readOrNull(guardAdmittedFile(state))).split("\n")));

        List<String> lines = new ArrayList<>();
        for (String entry : now.out) {
            if (entry.isBlank() || before.contains(entry)) continue;
            String rel = porcelainPath(entry);
            if (rel == null) continue;
            if (matchesAny(exempt, rel) || admitted.contains(rel)) continue;
            List<String> v = guardViolations(sch, state, in, rel, true);
            if (!v.isEmpty()) { lines.add(""); lines.addAll(v); }
        }
        if (lines.isEmpty()) return;
        err("❌ This turn wrote outside what the open phase and the frozen folders admit.");
        err("   The tool-time guards did not see these writes — a shell spelling they do not");
        err("   parse, or a tool with no matcher. Revert them, or report them and stop.");
        lines.forEach(ArchHook::err);
        System.exit(2);
    }

    /**
     * The path out of one `git status --porcelain` line. A rename prints `old -> new`; the new
     * name is the one that was written, and the one the territory has to admit.
     */
    static String porcelainPath(String entry) {
        if (entry.length() < 4) return null;
        String path = entry.substring(3).strip();
        int arrow = path.indexOf(" -> ");
        if (arrow >= 0) path = path.substring(arrow + 4);
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) {
            path = path.substring(1, path.length() - 1);
        }
        return path.isBlank() || path.endsWith("/") ? null : path;
    }

    /**
     * The sweep's stand-in for {@link #isStatusClose} and {@link #isChecklistToggle}, which read
     * `Edit`'s `old_string`/`new_string` and so cannot answer at `Stop`.
     *
     * <p>Compares the working copy against `git show HEAD:<path>` and admits exactly the same two
     * edits: a `status:` line moved along `guard.status_transitions` from the value HEAD carries,
     * and `[ ]` → `[x]` toggles on a status `guard.checklist_toggle_statuses` lists. It reads the
     * same two lists the tool-time checks read, so the three cannot disagree about what the state
     * machine is. Without this the executor's own legal close — the one write the frozen folder
     * exists to allow — would be reported every run, and a guard that cries on the legitimate
     * path is the guard people route around.
     */
    static boolean isSpecEditLegalOnDisk(Map<String, Object> cfg, String rel) {
        if (cfg == null || !rel.matches(".*/UC-[^/]*-spec\\.md")) return false;
        try {
            Proc head = run(gitCmd(), "show", "HEAD:" + rel);
            if (head.exit != 0) return false;                    // not committed: nothing to compare
            Map<String, String> fm = frontmatter(String.join("\n", head.out));
            String was = fm == null ? null : fm.get("status");
            boolean closable = was != null && !asStrList(get(cfg, "status_transitions", was)).isEmpty();
            boolean togglable = was != null
                    && asStrList(cfg.get("checklist_toggle_statuses")).contains(was);
            if (!closable && !togglable) return false;
            List<String> before = head.out;
            List<String> now = Arrays.asList(orEmpty(readOrNull(ROOT.resolve(rel))).split("\n"));
            if (before.size() != now.size()) return false;
            for (int i = 0; i < before.size(); i++) {
                String a = before.get(i), b = now.get(i);
                if (a.equals(b)) continue;
                boolean statusLine = closable && a.strip().equals("status: " + was)
                        && asStrList(get(cfg, "status_transitions", was))
                                .contains(b.strip().replaceFirst("^status:\\s*", ""));
                boolean toggle = togglable && a.replace("[ ]", "[x]").equals(b);
                if (!statusLine && !toggle) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** `/usr/bin/sed` and `sed` are the same command for the purposes of the shape table. */
    /**
     * The argument that makes a `git push` a force push, or null. A `deny` rule on
     * `Bash(git push --force:*)` is a prefix match, and the permission docs name what walks
     * past it: `-f`, `--force-with-lease`, a `+ref` refspec, `git -C . push --force` and
     * `sh -c "git push --force"` (lessons-learned-015 § H). This reads the same segments the
     * write shapes read: leading `VAR=value` words are skipped, a shell wrapper's `-c` string is
     * parsed once more (one level), git's global options are skipped — two words when the option
     * takes a value — and the word after them must be `push`. Every list is data,
     * `guard.force_push` — invariant 10. Invoked at `PreToolUse`, matcher `Bash`, through the
     * registration `guard bash` already has; a mode of its own would pay a second JVM per shell
     * command, and a wider `deny` list stays blind to `-C` and `sh -c`. The bar is the one 0063
     * set: a flag held in a variable or a git alias passes. Design:
     * `.claude/decisions/0076-bash-scope-and-force-push-guard.md`.
     */
    static String bashForcePush(Map<String, Object> fp, String cmd, int depth) {
        if (fp == null) return null;
        List<String> shells = asStrList(fp.get("shell_wrappers"));
        String shellFlag = orEmpty(asStr(fp.get("shell_command_flag")));
        List<String> valued = asStrList(fp.get("git_options_with_value"));
        List<String> longFlags = asStrList(fp.get("long_flags"));
        String shortFlag = orEmpty(asStr(fp.get("short_flag")));
        String refspec = orEmpty(asStr(fp.get("refspec_prefix")));
        for (List<Tok> seg : bashSegments(cmd)) {
            List<String> w = seg.stream().map(Tok::text).collect(Collectors.toList());
            int i = 0;
            while (i < w.size() && w.get(i).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) i++;
            if (i >= w.size()) continue;
            String head = bashCommandName(w.get(i));
            if (shells.contains(head)) {
                if (depth > 0) continue;
                int c = w.indexOf(shellFlag);
                if (c > i && c + 1 < w.size()) {
                    String inner = bashForcePush(fp, w.get(c + 1), depth + 1);
                    if (inner != null) return inner;
                }
                continue;
            }
            if (!head.equals("git")) continue;
            i++;
            while (i < w.size() && w.get(i).startsWith("-")) i += valued.contains(w.get(i)) ? 2 : 1;
            if (i >= w.size() || !w.get(i).equals("push")) continue;
            for (String a : w.subList(i + 1, w.size())) {
                if (a.startsWith("--")) {
                    for (String f : longFlags) {
                        if (a.equals(f) || a.startsWith(f + "=")) return a;
                    }
                } else if (a.startsWith("-")) {
                    if (!shortFlag.isEmpty() && a.substring(1).contains(shortFlag)) return a;
                } else if (!refspec.isEmpty() && a.startsWith(refspec)) {
                    return a;
                }
            }
        }
        return null;
    }

    static String bashCommandName(String word) {
        int slash = word.lastIndexOf('/');
        return slash < 0 ? word : word.substring(slash + 1);
    }

    /** One token of a shell command, and whether it was written inside quotes. */
    record Tok(String text, boolean quoted) {}

    /**
     * Cuts a command into the segments a separator delimits, dropping heredoc bodies. Quoting
     * is tracked so that a separator or a redirect operator inside quotes stays a character.
     */
    static List<List<Tok>> bashSegments(String cmd) {
        List<List<Tok>> segs = new ArrayList<>();
        List<Tok> cur = new ArrayList<>();
        StringBuilder tok = new StringBuilder();
        boolean quoted = false, started = false;
        char quote = 0;
        String heredoc = null;
        for (String line : cmd.split("\n", -1)) {
            if (heredoc != null) {                       // inside a heredoc body: not a command
                if (line.strip().equals(heredoc)) heredoc = null;
                continue;
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0; else tok.append(c);
                    continue;
                }
                if (c == '\'' || c == '"') { quote = c; quoted = true; started = true; continue; }
                if (c == '\\' && i + 1 < line.length()) { tok.append(line.charAt(++i)); started = true; continue; }
                if (Character.isWhitespace(c)) {
                    if (started) { cur.add(new Tok(tok.toString(), quoted)); tok.setLength(0); }
                    quoted = false; started = false;
                    continue;
                }
                if (c == ';' || c == '|' || c == '&') {
                    if (started) { cur.add(new Tok(tok.toString(), quoted)); tok.setLength(0); }
                    quoted = false; started = false;
                    if (!cur.isEmpty()) { segs.add(cur); cur = new ArrayList<>(); }
                    continue;
                }
                if (c == '<' && i + 1 < line.length() && line.charAt(i + 1) == '<') {
                    if (started) { cur.add(new Tok(tok.toString(), quoted)); tok.setLength(0); }
                    quoted = false; started = false;
                    heredoc = heredocDelimiter(line.substring(i + 2));
                    i = line.length();
                    continue;
                }
                if (c == '>' || c == '<') {
                    if (started) { cur.add(new Tok(tok.toString(), quoted)); tok.setLength(0); }
                    quoted = false; started = false;
                    StringBuilder op = new StringBuilder().append(c);
                    while (i + 1 < line.length() && line.charAt(i + 1) == c) { op.append(c); i++; }
                    cur.add(new Tok(op.toString(), false));
                    continue;
                }
                tok.append(c);
                started = true;
            }
            if (started) { cur.add(new Tok(tok.toString(), quoted)); tok.setLength(0); }
            quoted = false; started = false;
        }
        if (!cur.isEmpty()) segs.add(cur);
        return segs;
    }

    /** The word that ends a heredoc: `<<EOF`, `<<-EOF`, `<<'EOF'` and `<<"EOF"` all end at EOF. */
    static String heredocDelimiter(String rest) {
        String s = rest.strip();
        if (s.startsWith("-")) s = s.substring(1).strip();
        int end = 0;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return s.substring(0, end).replace("'", "").replace("\"", "");
    }

    /**
     * The third write a frozen `UC-NNN` folder admits, and the only one that is not an edit
     * of the spec itself: a file whose basename `guard.frozen_exempt_basenames` lists, sitting
     * DIRECTLY under the folder — `CHANGELOG.md`. `/new-feature`'s consolidation requires one
     * line there for every change an impact row makes to code an already-approved case
     * produced, which is exactly how the approved spec stays untouched while what changed
     * underneath it is still recorded. Until lessons-learned-013 § 1 the guard blocked that
     * write categorically and told the person to set `status: draft` — reopening an
     * implemented spec to append a changelog line, far worse than the block itself.
     *
     * <p>Matched on the basename alone, for any tool and any phase: knowing that an append is
     * really an append would mean reimplementing `Edit`'s semantics here, and the file is a log
     * the guard has no reason to interpret. `remainder` is what follows `<use_cases_dir>/UC-*\/`,
     * so a nested `notes/CHANGELOG.md` is still frozen — one path, one owner.
     */
    static boolean isFrozenExempt(Map<String, Object> cfg, String remainder) {
        return !remainder.contains("/")
                && asStrList(cfg.get("frozen_exempt_basenames")).contains(remainder);
    }

    /** `status:` of the folder's UC-*-spec.md, or null when there's no consolidated spec. */
    static String specStatus(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return null;
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : s.filter(f -> f.getFileName().toString().matches("UC-.*-spec\\.md"))
                           .collect(Collectors.toList())) {
                Map<String, String> fm = frontmatter(orEmpty(readOrNull(p)));
                if (fm != null && fm.get("status") != null) return fm.get("status");
            }
        }
        return null;
    }

    /** The one edit an approved spec admits: its status line, approved → implemented. */
    /**
     * The one edit that closes a spec: its `status:` line moved to a value
     * `guard.status_transitions` lists as reachable from the one it carries. The map is data and
     * not a pair of literals here because the machine has three states now — `approved` closes to
     * `implemented` or to `implemented-blocked`, and either closed state reverts to `approved`,
     * which is the exit the run that closed a spec by mistake did not have (lessons-learned-014
     * § 5). `draft` is absent from the map: it is not closable by a tool.
     */
    static boolean isStatusClose(Map<String, Object> cfg, Object in, String rel, String status) {
        if (cfg == null || !rel.matches(".*/UC-[^/]*-spec\\.md")) return false;
        if (!"Edit".equals(asStr(get(in, "tool_name")))) return false;
        String oldS = asStr(get(in, "tool_input", "old_string"));
        String newS = asStr(get(in, "tool_input", "new_string"));
        if (oldS == null || newS == null || !oldS.contains("status: " + status)) return false;
        for (String to : asStrList(get(cfg, "status_transitions", status))) {
            if (newS.equals(oldS.replace("status: " + status, "status: " + to))) return true;
        }
        return false;
    }

    /**
     * The other edit a frozen spec admits: toggling `- [ ]` to `- [x]` in its implementation
     * checklist, incremental progress that resuming the executor across sessions depends on
     * (lessons-learned-006 § 7). `[ ]` and `[x]` are the same length, so normalizing both to
     * `[ ]` and comparing catches any number of toggles in one `Edit` while still rejecting a
     * change to anything else in the snippet.
     *
     * <p>Which statuses admit it is `guard.checklist_toggle_statuses`, and it is all of them.
     * Requiring `approved` meant that a run closing the status before ticking the boxes froze 23
     * of them unticked, permanently, with the revert refused too — the spec then describing a
     * feature as unfinished that was on disk, green and committed (lessons-learned-014 § 4). A
     * toggle is monotone: it carries none of the risk immutability exists to prevent.
     */
    static boolean isChecklistToggle(Map<String, Object> cfg, Object in, String rel, String status) {
        if (cfg == null || !rel.matches(".*/UC-[^/]*-spec\\.md")) return false;
        if (!asStrList(cfg.get("checklist_toggle_statuses")).contains(status)) return false;
        if (!"Edit".equals(asStr(get(in, "tool_name")))) return false;
        String oldS = asStr(get(in, "tool_input", "old_string"));
        String newS = asStr(get(in, "tool_input", "new_string"));
        if (oldS == null || newS == null || oldS.length() != newS.length()) return false;
        String oldNorm = oldS.replace("[x]", "[ ]");
        String newNorm = newS.replace("[x]", "[ ]");
        return oldNorm.equals(newNorm) && !oldS.equals(newS);
    }

    // ── utilities ────────────────────────────────────────────────────────────

    /** Extracts tool_input.file_path from the stdin JSON without an external library. */
    static String filePath(String json) {
        Matcher m = Pattern.compile("\"file_path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        if (!m.find()) return null;
        String raw = m.group(1)
                .replace("\\\\", "\\").replace("\\\"", "\"").replace("\\/", "/");
        return raw;   // Windows arrives with '\\'; Paths.get handles both separators
    }

    /**
     * Path of the build module containing the file, {@code "."} for the root build file of a
     * single-module project, or null if there is none. A module is a directory holding one of the
     * {@code module_files} of {@code build_tools} — {@code pom.xml}, {@code build.gradle(.kts)}.
     * The walk never reaches the root, so it is tested last, and only for the {@code src/} tree:
     * {@code .claude/hooks/ArchHook.java} matches the hooks' {@code Edit(**}{@code /*.java)}
     * filter too, and must not build the whole project. Design: .claude/decisions/0107-moduleof-root-pom.md,
     * Gradle .claude/decisions/0180-module-slot-boundary-gradle-wrapper-and-approval-checks.md.
     */
    static String moduleOf(String rel) {
        List<String> files = buildTools().stream().flatMap(t -> t.moduleFiles().stream()).toList();
        Path p = Paths.get(rel).getParent();
        while (p != null) {
            for (String f : files) {
                if (Files.isRegularFile(ROOT.resolve(p).resolve(f))) return p.toString().replace('\\', '/');
            }
            p = p.getParent();
        }
        if (rel.replace('\\', '/').startsWith("src/")
                && files.stream().anyMatch(f -> Files.isRegularFile(ROOT.resolve(f)))) return ".";
        return null;
    }

    /**
     * One entry of {@code build_tools.tools} in extensions.json: the wrapper it runs (Unix, Windows),
     * the files that mark a module of it, and the arguments {@code check}'s incremental compile
     * hands the wrapper — {@code {module}} the module path, {@code {task}} its Gradle task prefix
     * ({@code :a:b:}, empty at the root).
     */
    record BuildTool(String id, String wrapper, String windowsWrapper, List<String> moduleFiles,
                     List<String> compile) {
        /** This tool's wrapper at the project root for this operating system, or null when absent. */
        String path() {
            Path w = ROOT.resolve(WINDOWS ? windowsWrapper : wrapper);
            return Files.isRegularFile(w) ? w.toString() : null;
        }

        List<String> compileArgs(String module) {
            String task = module.equals(".") ? "" : ":" + module.replace('/', ':') + ":";
            return compile.stream().map(a -> a.replace("{module}", module).replace("{task}", task)).toList();
        }
    }

    static List<BuildTool> buildToolsCache;

    /** {@code build_tools.tools}, read once; empty when extensions.json has no such block. */
    static List<BuildTool> buildTools() {
        if (buildToolsCache != null) return buildToolsCache;
        List<BuildTool> out = new ArrayList<>();
        String raw = readOrNull(ROOT.resolve(SCHEMA_FILE));
        try {
            for (Object o : asList(get(raw == null ? null : Json.parse(raw), "build_tools", "tools"))) {
                Map<String, Object> t = asMap(o);
                List<String> w = t == null ? List.of() : asStrList(t.get("wrapper"));
                if (w.size() != 2) continue;
                out.add(new BuildTool(asStr(t.get("id")), w.get(0), w.get(1),
                        asStrList(t.get("module_files")), asStrList(t.get("compile"))));
            }
        } catch (Exception ignored) { }
        return buildToolsCache = out;
    }

    /** The first tool of {@code build_tools.tools} whose wrapper the project carries, or null. */
    static BuildTool buildTool() {
        for (BuildTool t : buildTools()) if (t.path() != null) return t;
        return null;
    }

    /**
     * The Maven wrapper for this operating system. {@code format} and the {@code tests} gate run
     * Maven goals and stay Maven-only; {@code check} and {@code doctor} use {@link #buildTool}.
     */
    static String wrapper() {
        Path w = ROOT.resolve(WINDOWS ? "mvnw.cmd" : "mvnw");
        return Files.isRegularFile(w) ? w.toString() : null;
    }

    static String gitCmd() { return "git"; }

    static String relative(Path abs) {
        try { return ROOT.relativize(abs.toAbsolutePath().normalize())
                        .toString().replace('\\', '/'); }
        catch (Exception e) { return abs.toString().replace('\\', '/'); }
    }

    record Proc(int exit, List<String> out) {}

    static Proc run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(ROOT.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        List<String> out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            out = r.lines().collect(Collectors.toList());
        }
        return new Proc(proc.waitFor(), out);
    }

    /**
     * {@link #run} with a wall clock. For commands that can block indefinitely on
     * something outside this process — a Docker daemon that is starting, hibernating, or
     * unreachable. A diagnostic that hangs is worse than one that says "not checked":
     * `doctor` is what someone runs when the session already feels broken.
     * Returns exit -1 when the deadline passes, and the partial output.
     */
    static Proc runTimed(int seconds, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(ROOT.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        List<String> out = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) out.add(l);
            } catch (IOException ignored) { }
        });
        reader.setDaemon(true);
        reader.start();
        if (!proc.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            return new Proc(-1, List.copyOf(out));
        }
        reader.join(1000);
        return new Proc(proc.exitValue(), List.copyOf(out));
    }

    static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    static List<String> tail(List<String> lines, int n) {
        return lines.subList(Math.max(0, lines.size() - n), lines.size());
    }

    static void err(String s) { ERR.println(s); }

    // ── access to already-parsed JSON ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    static String asStr(Object o) { return o instanceof String ? (String) o : null; }

    static List<String> asStrList(Object o) {
        return asList(o).stream().map(ArchHook::asStr).filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    static Object get(Object o, String... path) {
        for (String k : path) {
            Map<String, Object> m = asMap(o);
            if (m == null) return null;
            o = m.get(k);
        }
        return o;
    }

    // ── JSON ─────────────────────────────────────────────────────────────────
    //
    // ~50-line recursive parser. Regex over JSON breaks with escaped quotes
    // and nesting — and here we read the runtime's stdin and the user's
    // settings.json, two places where breaking silently is worse than not
    // validating. The dependency remains just the JDK.

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        /** The top-level value, or null if the text is not valid JSON. */
        static Object parse(String text) {
            if (text == null || text.isBlank()) return null;
            try { Json j = new Json(text); j.ws(); return j.value(); }
            catch (RuntimeException e) { return null; }
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> str();
                case 't' -> { i += 4; yield Boolean.TRUE; }
                case 'f' -> { i += 5; yield Boolean.FALSE; }
                case 'n' -> { i += 4; yield null; }
                default  -> num();
            };
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws(); i++;                                   // ':'
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') return m;           // otherwise it was ','
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') return l;           // otherwise it was ','
            }
        }

        private String str() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default  -> b.append(e);                 // \" \\ \/
                }
            }
        }

        private Object num() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            return Double.valueOf(s.substring(start, i));
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
