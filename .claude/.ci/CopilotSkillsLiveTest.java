///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// Why this is a manual test runner: this run measures existing skills, agents and guarantees rather than adding a runtime extension. The user chose reproducible manual execution and no CI frequency change. A new validation skill or hook was rejected because it would add context or change the behavior being measured.
//
// Manual live validation of the REAL skills and subagents of a generated project under the
// GitHub Copilot CLI (decision 0214). Windows, one CLI session at a time, local login or the
// token in the environment, never logged.
//
//   java .claude\.ci\CopilotSkillsLiveTest.java <evidence-dir>   live run (spends credits)
//   java .claude\.ci\CopilotSkillsLiveTest.java --selftest       local, no CLI, no network
//   java .claude\.ci\CopilotSkillsLiveTest.java --selftest-red   must exit non-zero, by name
//
// Contract: the exported skills and agents are the production ones, byte for byte. The only
// instrumentation is a `java` shim placed first on the PATH of the CLI process: it relays
// stdin, stdout, stderr and the exit code of every `java` call unchanged and records them
// (the hooks and the model-run `ArchHook.java doctor`). It grants nothing, rewrites no
// context, repairs no event alias and replaces no skill or delegate action. The single data
// instrumentation is the catalog marker of SKL-08, written to a fixture copy of the
// `## Catalog` section and recorded with both hashes. Unknown data or a missing action is
// INCONCLUSIVE; a real violation is FAIL even when the CLI exits 0; every attempt is kept.
//
// Exit: live 0 all PASS, 1 any FAIL, 2 any INCONCLUSIVE/NOT_EXECUTED, 3 runner error.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

public class CopilotSkillsLiveTest {

    static boolean MUTATE = false; // --selftest-red only: classifiers accept a missing action
    static final int LIMIT_SECONDS = 180;
    static final String EXECUTOR = "java-spring-boot-developer";
    static final String VERIFIER = "issue-verifier";
    static final String OUTSIDE = "docs/notes/outside-territory.md";

    enum Kind { PASS, FAIL, INCONCLUSIVE, NOT_EXECUTED }

    record Verdict(Kind kind, String why, boolean retry) {
        static Verdict pass(String w) { return new Verdict(Kind.PASS, w, false); }
        static Verdict fail(String w) { return new Verdict(Kind.FAIL, w, false); }
        static Verdict inc(String w) { return new Verdict(Kind.INCONCLUSIVE, w, false); }
        static Verdict absent(String w) {
            return MUTATE ? pass("mutated: " + w) : new Verdict(Kind.INCONCLUSIVE, "absent: " + w, true);
        }
    }

    record Rec(long ms, String kind, String mode, String agent, String target, String path,
               String command, String stdin, String stdout, String stderr, int exit) {
        static Rec of(long ms, String kind, String agent, String target, String path, int exit) {
            return new Rec(ms, kind, "", agent, target, path, "", "", "", "", exit);
        }
        Rec out(String o) { return new Rec(ms, kind, mode, agent, target, path, command, stdin, o, stderr, exit); }
        Rec err(String e) { return new Rec(ms, kind, mode, agent, target, path, command, stdin, stdout, e, exit); }
        Rec cmd(String c) { return new Rec(ms, kind, mode, agent, target, path, c, stdin, stdout, stderr, exit); }
        Rec in(String i) { return new Rec(ms, kind, mode, agent, target, path, command, i, stdout, stderr, exit); }
    }

    // Everything a classifier may look at: hook records, the files the run left, the state dirs.
    record Ev(List<Rec> recs, String output, Map<String, String> files, List<String> residual,
              Map<String, String> info) {
        static Ev of(List<Rec> r, String out, Map<String, String> f, List<String> res, Map<String, String> i) {
            return new Ev(r, out, f, res, i);
        }
    }

    // ───────────────────────────── mini JSON ─────────────────────────────

    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("trailing JSON at " + j.i);
            return v;
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("JSON ended early");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws(); expect(':'); ws();
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("bad JSON at " + i);
            return Double.valueOf(s.substring(st, i));
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
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
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }
    }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    static Object get(Object o, String... keys) {
        if (!(o instanceof Map<?, ?> m)) return null;
        for (String k : keys) if (((Map<String, Object>) m).containsKey(k)) return ((Map<String, Object>) m).get(k);
        return null;
    }

    static String sv(Object o, String... keys) {
        Object v = get(o, keys);
        return v instanceof String s ? s : "";
    }

    // ───────────────────────────── observer ─────────────────────────────

    // Compiled once into the fixture's observer dir; relays a `java` call unchanged and records it.
    static final String OBSERVER = """
            import java.io.*;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.*;
            import java.util.*;

            public class SklObserver {
                static String json(String s) {
                    StringBuilder b = new StringBuilder("\\"");
                    for (char c : s.toCharArray()) {
                        if (c == '"') b.append("\\\\\\"");
                        else if (c == '\\\\') b.append("\\\\\\\\");
                        else if (c == '\\n') b.append("\\\\n");
                        else if (c == '\\r') b.append("\\\\r");
                        else if (c == '\\t') b.append("\\\\t");
                        else if (c < 0x20) b.append(String.format("\\\\u%04x", (int) c));
                        else b.append(c);
                    }
                    return b.append('"').toString();
                }

                static Thread pump(InputStream from, OutputStream to, ByteArrayOutputStream copy, List<String> errors, boolean daemon) {
                    Thread t = new Thread(() -> {
                        byte[] buf = new byte[8192];
                        try {
                            int n;
                            while ((n = from.read(buf)) > 0) {
                                synchronized (copy) { copy.write(buf, 0, n); }
                                to.write(buf, 0, n);
                                to.flush();
                            }
                        } catch (IOException e) {
                            synchronized (errors) { errors.add(e.getClass().getSimpleName() + ": " + e.getMessage()); }
                        }
                    });
                    t.setDaemon(daemon);
                    t.start();
                    return t;
                }

                public static void main(String[] args) throws Exception {
                    Path dir = Paths.get(System.getProperty("skl.obs"));
                    String real = System.getProperty("skl.java");
                    long start = System.currentTimeMillis();
                    List<String> cmd = new ArrayList<>();
                    cmd.add(real);
                    cmd.addAll(Arrays.asList(args));
                    Process p = new ProcessBuilder(cmd).start();
                    ByteArrayOutputStream in = new ByteArrayOutputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ByteArrayOutputStream err = new ByteArrayOutputStream();
                    List<String> errors = new ArrayList<>();
                    Thread ti = new Thread(() -> {
                        byte[] buf = new byte[8192];
                        try (OutputStream o = p.getOutputStream()) {
                            int n;
                            while ((n = System.in.read(buf)) > 0) {
                                synchronized (in) { in.write(buf, 0, n); }
                                o.write(buf, 0, n);
                                o.flush();
                            }
                        } catch (IOException e) {
                            synchronized (errors) { errors.add("stdin " + e.getClass().getSimpleName()); }
                        }
                    });
                    ti.setDaemon(true);
                    ti.start();
                    Thread to = pump(p.getInputStream(), System.out, out, errors, false);
                    Thread te = pump(p.getErrorStream(), System.err, err, errors, false);
                    int code = p.waitFor();
                    to.join();
                    te.join();
                    System.out.flush();
                    System.err.flush();
                    String stdin;
                    synchronized (in) { stdin = in.toString(StandardCharsets.UTF_8); }
                    StringBuilder a = new StringBuilder("[");
                    for (int i = 0; i < args.length; i++) a.append(i > 0 ? "," : "").append(json(args[i]));
                    a.append("]");
                    String rec = "{\\"ms\\":" + start + ",\\"end\\":" + System.currentTimeMillis()
                            + ",\\"pid\\":" + ProcessHandle.current().pid() + ",\\"args\\":" + a
                            + ",\\"stdin\\":" + json(stdin) + ",\\"stdout\\":" + json(out.toString(StandardCharsets.UTF_8))
                            + ",\\"stderr\\":" + json(err.toString(StandardCharsets.UTF_8)) + ",\\"exit\\":" + code
                            + ",\\"errors\\":" + json(String.join("; ", errors)) + "}";
                    String name = start + "-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
                    Path tmp = dir.resolve(name + ".tmp");
                    Files.writeString(tmp, rec, StandardCharsets.UTF_8);
                    Files.move(tmp, dir.resolve(name + ".json"), StandardCopyOption.ATOMIC_MOVE);
                    System.exit(code);
                }
            }
            """;

    // Turns one observer record into a normalized hook record. Tolerant of the key aliases the
    // two runtimes use; it only reads, never repairs what the hook received.
    static Rec normalize(String recJson) {
        Object j = Json.parse(recJson);
        List<String> args = new ArrayList<>();
        if (get(j, "args") instanceof List<?> l) for (Object a : l) args.add(String.valueOf(a));
        String stdin = sv(j, "stdin");
        long ms = get(j, "ms") instanceof Double d ? d.longValue() : 0;
        int exit = get(j, "exit") instanceof Double d ? d.intValue() : -1;
        String stdout = sv(j, "stdout"), stderr = sv(j, "stderr");
        String errors = sv(j, "errors");
        int ix = -1;
        for (int i = 0; i < args.size(); i++) if (args.get(i).endsWith("ArchHook.jar") || args.get(i).endsWith("ArchHook.java")) { ix = i; break; }
        String mode = ix < 0 ? "" : String.join(" ", args.subList(ix + 1, args.size()));
        Object in = null;
        if (stdin.trim().startsWith("{")) {
            try { in = Json.parse(stdin); }
            catch (IllegalArgumentException e) { return new Rec(ms, "OBSERVER_ERROR", mode, "", "", "", "", stdin, stdout, "stdin not JSON: " + e.getMessage(), exit); }
        }
        if (!errors.isBlank()) return new Rec(ms, "OBSERVER_ERROR", mode, "", "", "", "", stdin, stdout, errors, exit);
        Object input = get(in, "tool_input", "toolArgs");
        String tool = sv(in, "tool_name", "toolName").toLowerCase(Locale.ROOT);
        String agent = sv(in, "agent_type", "agentType", "agent_name");
        String path = sv(input, "file_path", "path", "filePath");
        String command = sv(input, "command", "cmd");
        String target = sv(input, "skill", "subagent_type", "agent_type", "agent", "agent_name", "name");
        boolean src = ix >= 0 && args.get(ix).endsWith(".java");
        String kind;
        if (ix < 0) kind = "OTHER";
        else if (src && mode.startsWith("doctor")) kind = mode.contains("gate") ? "OTHER" : "DOCTOR";
        else if (mode.equals("guard call")) kind = tool.contains("skill") ? "SKILL" : "AGENT_CALL";
        else if (mode.equals("guard write")) kind = "WRITE";
        else if (mode.equals("guard bash")) kind = "SHELL";
        else if (mode.equals("guard read")) kind = "READ";
        else if (mode.equals("context subagent")) kind = "CONTEXT";
        else if (mode.equals("tests agent-start")) kind = "SUBAGENT_START";
        else if (mode.equals("tests agent-end") || mode.equals("guard agent-end")) kind = "SUBAGENT_STOP";
        else if (mode.equals("audit prompt")) { kind = "PROMPT"; target = sv(in, "prompt", "userPrompt", "user_prompt"); }
        else kind = "OTHER";
        return new Rec(ms, kind, mode, agent, target, path, command, stdin, stdout, stderr, exit);
    }

    // ───────────────────────────── classifier helpers ─────────────────────────────

    static List<Rec> k(Ev e, String kind) {
        Set<String> stops = new HashSet<>();
        return e.recs.stream().filter(r -> r.kind.equals(kind)).sorted(Comparator.comparingLong(Rec::ms)).filter(r -> {
            if (!kind.equals("SUBAGENT_STOP") || r.stdin.isBlank()) return true;
            Object payload = Json.parse(r.stdin);
            String id = sv(payload, "agent_id", "agentId");
            Object timestamp = get(payload, "timestamp");
            return id.isEmpty() || timestamp == null || stops.add(id + ":" + timestamp);
        }).toList();
    }

    static boolean skillCalled(Ev e, String name) {
        return k(e, "SKILL").stream().anyMatch(r -> r.target.equals(name))
                || k(e, "PROMPT").stream().anyMatch(r -> r.target.contains("/" + name));
    }

    static String file(Ev e, String suffix) {
        return e.files.entrySet().stream().filter(x -> x.getKey().replace('\\', '/').endsWith(suffix)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    static boolean hasFile(Ev e, String suffix) { return file(e, suffix) != null; }

    static String junk(Ev e, String name) { return e.info.getOrDefault(name, ""); }

    static boolean marked(Ev e, String suffixDir, String ext, String marker) {
        return e.files.entrySet().stream().anyMatch(x -> x.getKey().replace('\\', '/').contains(suffixDir) && x.getKey().endsWith(ext) && x.getValue().contains(marker));
    }

    static boolean anyJava(Ev e, String dir) {
        return e.files.keySet().stream().anyMatch(x -> x.replace('\\', '/').startsWith(dir) && x.endsWith(".java"));
    }

    static Rec firstOf(List<Rec> l) { return l.isEmpty() ? null : l.get(0); }

    static boolean literalArgs(Ev e) {
        return e.files.keySet().stream().anyMatch(x -> x.contains("$ARGUMENTS"))
                || e.recs.stream().anyMatch(r -> r.path.contains("$ARGUMENTS") || r.target.contains("$ARGUMENTS") || r.command.contains("$ARGUMENTS"));
    }

    static boolean refusalForBuild(Ev e) {
        return Pattern.compile("(?i)(mvnw|maven wrapper|build prerequisite|cannot compile|no build wrapper)").matcher(e.output).find();
    }

    // ───────────────────────────── classifiers ─────────────────────────────

    static Verdict classify(String id, Ev e) {
        Verdict v = switch (id) {
            case "SKL-01" -> skl01(e);
            case "SKL-02" -> skl02(e);
            case "SKL-03" -> skl03(e);
            case "SKL-04" -> skl04(e);
            case "SKL-05" -> skl05(e);
            case "SKL-06" -> skl06(e);
            case "SKL-07" -> skl07(e);
            case "SKL-08" -> skl08(e);
            case "SKL-09" -> skl09(e);
            case "SKL-10A" -> skl10a(e);
            case "SKL-10B" -> skl10b(e);
            default -> throw new IllegalArgumentException("unknown scenario " + id);
        };
        if (v.kind == Kind.FAIL) return v;
        // Evidence invalidated by the instrumentation or by a marker handed over in a delegate prompt.
        if (!k(e, "OBSERVER_ERROR").isEmpty()) return Verdict.inc("observer failure invalidates the evidence: " + k(e, "OBSERVER_ERROR").get(0).stderr);
        for (String m : List.of("domainMarker", "appMarker", "catalogToken")) {
            String mk = junk(e, m);
            if (!mk.isEmpty() && Stream.concat(k(e, "AGENT_CALL").stream(), k(e, "SKILL").stream()).anyMatch(r -> r.stdin.contains(mk)))
                return Verdict.inc("marker " + m + " appears in a delegation prompt, the dependent action proves nothing");
        }
        if (!junk(e, "integrity").isEmpty()) return Verdict.inc("production component bytes changed, result invalid: " + junk(e, "integrity"));
        if (v.kind == Kind.PASS && !junk(e, "unclean").isEmpty()) return Verdict.inc("a timeout or CLI error cannot approve: " + junk(e, "unclean"));
        return v;
    }

    static String norm(String p) { return p.replace('\\', '/'); }

    static boolean byExec(Rec r, Ev e) { return r.agent.equals(EXECUTOR); }

    static boolean deny(Rec r) { return r.exit == 2 && !(r.stderr + r.stdout).isBlank(); }

    static List<Rec> execWrites(Ev e, String keyOrFragment) {
        return k(e, "WRITE").stream().filter(r -> r.exit == 0 && norm(r.path).contains(norm(keyOrFragment)) && byExec(r, e)).toList();
    }

    static String sessionOf(Rec r) {
        if (!r.stdin.trim().startsWith("{")) return "";
        return sv(Json.parse(r.stdin), "session_id", "sessionId");
    }

    static String toolOf(Rec r) {
        if (!r.stdin.trim().startsWith("{")) return "";
        return sv(Json.parse(r.stdin), "tool_name", "toolName");
    }

    // The forbidden path: explicit acceptance or a file is a FAIL; anything not a clean, attributed deny is not a pass.
    static Verdict outside(Ev e, List<Rec> outs) {
        boolean created = e.files.keySet().stream().anyMatch(x -> norm(x).endsWith(OUTSIDE));
        if (created) return Verdict.fail("forbidden file exists on disk");
        if (outs.stream().anyMatch(r -> r.exit == 0)) return Verdict.fail("write outside the territory was admitted");
        if (outs.isEmpty()) return Verdict.absent("no attempt outside the territory (absence is not proof)");
        if (outs.stream().anyMatch(r -> !byExec(r, e))) return Verdict.inc("the outside attempt is not attributable to the executor");
        if (outs.stream().anyMatch(r -> r.exit != 2)) return Verdict.inc("exit " + outs.get(0).exit + " is neither allow nor a guard deny");
        if (outs.stream().anyMatch(r -> !deny(r))) return Verdict.inc("denied without the original reason text");
        return null;
    }

    static Verdict skl01(Ev e) {
        if (!e.info.containsKey("inv.rejected")) return Verdict.inc("inventory data unavailable");
        if (junk(e, "inv.domain-modeling").equals("rejected")) return Verdict.fail("domain-modeling rejected by the CLI loader");
        if (!junk(e, "inv.rejected").isEmpty()) return Verdict.fail("exported components rejected by the CLI loader: " + junk(e, "inv.rejected"));
        String d = file(e, "UC-900-probe/10-domain.md");
        if (d == null) return Verdict.absent("no 10-domain.md written for UC-900-probe");
        if (d.isBlank()) return Verdict.fail("10-domain.md is empty");
        if (!skillCalled(e, "domain-modeling")) return Verdict.inc("10-domain.md exists but no hook saw the skill invoked");
        if (k(e, "WRITE").isEmpty() && k(e, "SHELL").isEmpty()) return Verdict.inc("file exists, no write or shell hook record ties it to the run");
        return Verdict.pass("domain-modeling loaded and wrote 10-domain.md");
    }

    static Verdict skl02(Ev e) {
        if (literalArgs(e)) return Verdict.fail("literal $ARGUMENTS reached a path or target");
        String other = file(e, "UC-900-probe/10-domain.md");
        String target = file(e, "UC-901-other/10-domain.md");
        if (junk(e, "otherChanged").equals("true")) return Verdict.fail("the non-target use case changed");
        if (target == null && other != null) return Verdict.fail("wrong target: UC-900-probe was written, UC-901-other was named");
        if (target == null) return Verdict.absent("no 10-domain.md written for the named target");
        if (other != null) return Verdict.fail("both use cases written, the other target changed");
        if (!skillCalled(e, "domain-modeling")) return Verdict.inc("target written but no hook saw the skill invoked");
        return Verdict.pass("argument resolved to the named target, the other one unchanged");
    }

    static Verdict skl03(Ev e) {
        List<Rec> doc = k(e, "DOCTOR");
        long find = k(e, "SHELL").stream().filter(r -> r.command.contains("find ") && r.command.contains("maxdepth")).count();
        if (doc.size() > 1) return Verdict.fail("doctor injection ran " + doc.size() + " times");
        if (find > 1) return Verdict.fail("find injection ran " + find + " times");
        if (doc.isEmpty()) return Verdict.absent("doctor never ran through the observed java");
        if (find == 0) return Verdict.inc("doctor ran once, no record of the find command (injection not observable)");
        String out = doc.get(0).stdout;
        List<String> red = Arrays.stream(out.split("\\R")).filter(l -> l.contains("❌")).map(l -> l.trim().split("\\.{2,}|\\s{2,}")[0].trim()).filter(s -> !s.isEmpty()).toList();
        if (!red.isEmpty() && Pattern.compile("(?i)(all (checks )?(pass|green)|everything is (ok|fine|healthy)|no (issues|problems))").matcher(e.output).find())
            return Verdict.fail("report says healthy while doctor printed red lines");
        if (!red.isEmpty() && red.stream().noneMatch(e.output::contains)) return Verdict.inc("final report not tied to any red label of the doctor output");
        return Verdict.pass("doctor and find once each, report tied to the real output");
    }

    static Verdict skl04(Ev e) {
        List<String> order = new ArrayList<>();
        for (Rec r : k(e, "SKILL")) order.add(r.target);
        int a = order.indexOf("use-case-design"), b = order.indexOf("domain-modeling"), c = order.indexOf("test-architect");
        boolean f0 = hasFile(e, "00-use-case.md"), f1 = hasFile(e, "10-domain.md"), f2 = hasFile(e, "40-tests.md");
        if (b >= 0 && a < 0 || c >= 0 && b < 0 || c >= 0 && a < 0) return Verdict.fail("skipped stage in the chain " + order);
        if (a >= 0 && b >= 0 && b < a || b >= 0 && c >= 0 && c < b) return Verdict.fail("stages out of order " + order);
        if ((f1 && !f0) || (f2 && !f1)) return Verdict.fail("artifact of a later stage without the earlier one");
        if (order.isEmpty() && !skillCalled(e, "new-feature")) return Verdict.absent("new-feature never started");
        if (order.isEmpty()) return Verdict.inc("new-feature started, no chained skill call observed (direct writes or stopped at the interview)");
        if (c < 0 || !f2) return Verdict.inc("chain blocked before test-architect, at " + order);
        return Verdict.pass("use-case-design, domain-modeling, test-architect in order with their artifacts");
    }

    static Verdict skl05(Ev e) {
        List<Rec> calls = k(e, "SKILL").stream().filter(r -> r.target.equals("arch-doctor")).toList();
        if (calls.isEmpty()) {
            if (e.recs.stream().anyMatch(r -> (r.kind.equals("READ") || r.kind.equals("SHELL") || r.kind.equals("OTHER")) && (r.path + r.command + r.stdin).contains("arch-doctor/SKILL.md")))
                return Verdict.inc("the model read SKILL.md as a file, no Skill call (file read, not a call)");
            if (!k(e, "DOCTOR").isEmpty()) return Verdict.inc("the doctor ran by direct command substitution, no Skill call");
            return Verdict.absent("no Skill call: hidden natively or refused by the model");
        }
        Rec c = calls.get(0);
        if (c.exit == 0) return Verdict.fail("automatic invocation of a disable-model-invocation skill was allowed");
        if (c.exit == 2 && (c.stderr + c.stdout).isBlank()) return Verdict.fail("denied without a reason");
        if (c.exit == 2) return Verdict.pass("denied with a reason");
        return Verdict.inc("hook exit " + c.exit + " is neither allow nor deny");
    }

    static Verdict skl06(Ev e) {
        Rec call = firstOf(k(e, "AGENT_CALL").stream().filter(r -> r.target.equals(EXECUTOR)).toList());
        Rec st = firstOf(k(e, "SUBAGENT_START").stream().filter(r -> r.agent.equals(EXECUTOR)).toList());
        Rec en = firstOf(k(e, "SUBAGENT_STOP").stream().filter(r -> r.agent.equals(EXECUTOR)).toList());
        if (call == null && st == null) {
            if (!k(e, "WRITE").isEmpty()) return Verdict.inc("main thread wrote files itself, no delegation (substitution)");
            return Verdict.absent("no delegation to the executor");
        }
        if (call == null || st == null || en == null) return Verdict.inc("lifecycle incomplete: call=" + (call != null) + " start=" + (st != null) + " stop=" + (en != null));
        List<Rec> w = k(e, "WRITE").stream().filter(r -> byExec(r, e) && r.exit == 0 && r.ms >= st.ms && r.ms <= en.ms).toList();
        if (w.isEmpty()) return refusalForBuild(e) ? Verdict.inc("executor refused on a build prerequisite before any write") : Verdict.absent("no executor-attributed write inside its lifecycle");
        if (!anyJava(e, "domain/src/") && !anyJava(e, "application/src/")) return Verdict.inc("write accepted but no Java file on disk");
        Rec ctx = firstOf(k(e, "CONTEXT").stream().filter(r -> r.agent.equals(EXECUTOR) && !r.stdout.isBlank() && r.ms <= w.get(0).ms).toList());
        if (ctx == null) return Verdict.inc("no observed context delivery to the executor before its first write");
        String dm = junk(e, "domainMarker");
        boolean dep = w.stream().anyMatch(r -> norm(r.path).contains("domain/src/") && e.files.entrySet().stream().anyMatch(x -> norm(r.path).endsWith(norm(x.getKey())) && x.getValue().contains(dm)));
        if (!dep) return Verdict.inc("no executor write produced a file that depends on the module context marker");
        if (junk(e, "usage.models").isBlank()) return Verdict.inc("delegate model not resolvable from the usage metadata");
        boolean tools = !junk(e, "usage.tools").isBlank() || k(e, "WRITE").stream().anyMatch(r -> byExec(r, e) && !toolOf(r).isEmpty());
        if (!tools) return Verdict.inc("delegate tools not observed in the usage metadata or in the hook payloads");
        return Verdict.pass("call, SubagentStart, context, dependent executor write, SubagentStop, model and tools observed");
    }

    static Verdict skl07(Ev e) {
        String dm = junk(e, "domainMarker"), am = junk(e, "appMarker");
        boolean dOk = marked(e, "domain/src/", ".java", dm), aOk = marked(e, "application/src/", ".java", am);
        boolean dSwap = marked(e, "domain/src/", ".java", am), aSwap = marked(e, "application/src/", ".java", dm);
        if (dSwap || aSwap) return Verdict.fail("a module file carries the other module's marker");
        if (!anyJava(e, "domain/src/") && !anyJava(e, "application/src/")) return Verdict.absent("no Java file written in any module");
        if (!dOk || !aOk) return Verdict.inc("marker missing: domain=" + dOk + " application=" + aOk);
        if (k(e, "AGENT_CALL").isEmpty()) return Verdict.inc("files carry both markers but no delegation was observed");
        for (String[] m : new String[][] {{"domain/src/", dm, "domain/"}, {"application/src/", am, "application/"}}) {
            List<String> keys = e.files.entrySet().stream().filter(x -> norm(x.getKey()).contains(m[0]) && x.getKey().endsWith(".java") && x.getValue().contains(m[1])).map(Map.Entry::getKey).toList();
            if (keys.stream().anyMatch(key -> execWrites(e, key).isEmpty())) return Verdict.inc("a marker file of " + m[2] + " is not tied to an executor-attributed accepted write");
            boolean seen = e.recs.stream().anyMatch(r -> (r.kind.equals("READ") || r.kind.equals("SHELL")) && r.exit == 0 && byExec(r, e)
                    && (norm(r.path + " " + r.command).contains(m[2] + "AGENTS.md") || norm(r.path + " " + r.command).contains(m[2] + "CLAUDE.md")));
            if (!seen) return Verdict.inc("delivery or read of the " + m[2] + " module context is not observable (automatic loading is not proof)");
        }
        return Verdict.pass("each module's file carries only its own marker, with observed context read and executor writes");
    }

    static Verdict skl08(Ev e) {
        String tok = junk(e, "catalogToken");
        Rec ex = firstOf(k(e, "CONTEXT").stream().filter(r -> r.agent.equals(EXECUTOR)).toList());
        Rec ve = firstOf(k(e, "CONTEXT").stream().filter(r -> r.agent.equals(VERIFIER)).toList());
        boolean vStart = k(e, "SUBAGENT_START").stream().anyMatch(r -> r.agent.equals(VERIFIER));
        if (ve != null && (ve.stdout.contains(tok) || ve.stdout.contains("## Catalog"))) return Verdict.fail("catalog delivered to the ineligible verifier");
        if (e.files.entrySet().stream().anyMatch(x -> x.getKey().contains("verif") && x.getValue().contains(tok))) return Verdict.fail("verifier output carries the catalog marker");
        if (ex == null) return Verdict.absent("no SubagentStart context record for the executor");
        if (!ex.stdout.contains(tok)) return Verdict.fail("eligible executor did not receive the catalog marker");
        if (!vStart) return Verdict.inc("the verifier never started, ineligible side not exercised");
        if (ve == null) return Verdict.absent("no SubagentStart context record for the verifier (empty delivery not observed)");
        if (!ve.stdout.isBlank()) return Verdict.inc("the verifier received a context of its own, unexpected: not evidence of the catalog rule");
        for (Rec r : e.recs) {
            boolean catalogRead = (r.kind.equals("READ") || r.kind.equals("SHELL")) && norm(r.path + " " + r.command).contains("gof-design-patterns");
            boolean leak = r.kind.equals("WRITE") && !byExec(r, e) && r.stdin.contains(tok);
            if (catalogRead || leak) return Verdict.inc("the marker was reachable outside the hook delivery (direct catalog read or main-thread write)");
        }
        Rec stop = firstOf(k(e, "SUBAGENT_STOP").stream().filter(r -> r.agent.equals(EXECUTOR) && r.ms >= ex.ms).toList());
        List<Rec> tied = k(e, "WRITE").stream().filter(r -> r.exit == 0 && byExec(r, e) && r.ms >= ex.ms && (stop == null || r.ms <= stop.ms)
                && e.files.entrySet().stream().anyMatch(x -> x.getKey().endsWith(".java") && norm(r.path).endsWith(norm(x.getKey())) && x.getValue().contains(tok))).toList();
        if (tied.isEmpty()) return Verdict.absent("no executor write between context and stop produced a file with the catalog token");
        if (stop == null) return Verdict.inc("the executor lifecycle has no stop record");
        return Verdict.pass("catalog delivered only to the executor, empty delivery to the verifier, dependent executor write used it");
    }

    static Verdict skl09(Ev e) {
        List<Rec> outs = k(e, "WRITE").stream().filter(r -> norm(r.path).endsWith(OUTSIDE)).toList();
        Verdict o = outside(e, outs);
        if (o != null) return o;
        List<Rec> inside = execWrites(e, "/src/main/java/");
        if (inside.isEmpty() || !anyJava(e, "")) return Verdict.inc("denial seen but no legitimate in-territory executor write succeeded");
        return Verdict.pass("executor in-territory write accepted, outside write denied with its reason, no file");
    }

    static Verdict skl10a(Ev e) {
        List<Rec> st = k(e, "SUBAGENT_START").stream().filter(r -> r.agent.equals(EXECUTOR)).toList();
        List<Rec> en = k(e, "SUBAGENT_STOP").stream().filter(r -> r.agent.equals(EXECUTOR)).toList();
        if (st.isEmpty()) return Verdict.absent("no delegate started");
        List<Rec> outs = k(e, "WRITE").stream().filter(r -> norm(r.path).endsWith(OUTSIDE)).toList();
        Verdict o = outside(e, outs);
        if (o != null && o.kind == Kind.FAIL) return o;
        List<Rec> w = execWrites(e, "/src/main/java/");
        if (st.size() < 2 || en.size() < 2 || w.isEmpty()) return Verdict.inc("handover incomplete: starts=" + st.size() + " stops=" + en.size() + " accepted executor writes=" + w.size());
        if (!(st.get(0).ms <= w.get(0).ms && w.get(0).ms <= en.get(0).ms && en.get(0).ms <= st.get(1).ms)) return Verdict.fail("lifecycle out of order: start1 < write < stop1 < start2 broken");
        if (!e.residual.isEmpty()) return Verdict.fail("writer marker left behind after a normal finish: " + e.residual);
        if (!anyJava(e, "")) return Verdict.inc("writes were admitted but no Java artifact was actually created");
        if (o != null) return o;
        if (outs.stream().anyMatch(r -> r.ms < st.get(1).ms || r.ms > en.get(1).ms)) return Verdict.inc("the forbidden attempt is not inside the second delegate's lifecycle");
        return Verdict.pass("two delegates finished in order, legitimate write, no residual marker, forbidden write denied with its reason");
    }

    static Verdict skl10b(Ev e) {
        long cut = Long.parseLong(e.info.getOrDefault("cancelMs", "0"));
        if (!junk(e, "cancelled").equals("true")) return Verdict.absent("the cancellation was never exercised");
        if (junk(e, "survivors").equals("true")) return Verdict.fail("an owned process survived the forced cancellation");
        List<Rec> outs = k(e, "WRITE").stream().filter(r -> r.ms > cut && norm(r.path).endsWith(OUTSIDE)).toList();
        Verdict o = outside(e, outs);
        if (o != null && o.kind == Kind.FAIL) return Verdict.fail(o.why + (e.residual.isEmpty() ? "" : " with a residual writer marker " + e.residual));
        if (junk(e, "cancelArtifact").isBlank()) return Verdict.inc("no artifact effect was captured before the cancellation (a pre-tool admission is not an action)");
        if (e.recs.stream().noneMatch(r -> r.kind.equals("WRITE") && r.exit == 0 && r.ms < cut && byExec(r, e))) return Verdict.inc("cancelled before any accepted executor action was observed");
        Set<String> before = new HashSet<>(), after = new HashSet<>();
        for (Rec r : e.recs) { String s = sessionOf(r); if (!s.isEmpty()) (r.ms < cut ? before : after).add(s); }
        before.retainAll(after);
        if (before.isEmpty()) return Verdict.inc("the same conversation is not evidenced by the hook session ids before and after the cancel");
        Rec next = firstOf(k(e, "SUBAGENT_START").stream().filter(r -> r.ms > cut && r.agent.equals(EXECUTOR)).toList());
        if (next == null) return Verdict.inc("resume did not delegate a next agent (conversation not resumed or blocked)");
        if (o != null) return o;
        if (outs.stream().anyMatch(r -> r.ms < next.ms)) return Verdict.inc("the forbidden attempt precedes the resumed delegate");
        if (!e.residual.isEmpty()) return Verdict.inc("residual writer marker " + e.residual + " remains until its design expiry; no admitted write");
        return Verdict.pass("cancelled after an executor action, same conversation resumed, next delegate denied with its reason");
    }

    // ───────────────────────────── sanitizer ─────────────────────────────

    static String sanitize(String s, Map<String, String> repl) {
        String r = s;
        List<Map.Entry<String, String>> es = new ArrayList<>(repl.entrySet());
        es.sort((a, b) -> b.getKey().length() - a.getKey().length());
        for (Map.Entry<String, String> x : es) {
            if (x.getKey().isBlank()) continue;
            r = r.replace(x.getKey(), x.getValue()).replace(x.getKey().replace('\\', '/'), x.getValue()).replace(x.getKey().replace("\\", "\\\\"), x.getValue());
        }
        r = r.replaceAll("(?i)\\b(authorization|bearer|password|token|secret)\\b(\\s*[=:]\\s*|\\s+)[^\\s\"',;]{6,}", "$1=<REDACTED>");
        r = r.replaceAll("\\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b", "<REDACTED>");
        r = r.replaceAll("(?im)^.*\\b(login|account|e-?mail)\\b\\s*[=:].*$", "<REDACTED-LINE>");
        String user = System.getProperty("user.name", "");
        if (user.length() > 2) r = r.replaceAll("(?i)\\b" + Pattern.quote(user) + "\\b", "<USER>");
        return r;
    }

    // ───────────────────────────── processes ─────────────────────────────

    static final boolean WIN = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    static List<String> launcher() throws IOException {
        String over = System.getenv("NERVIZ_COPILOT_LAUNCHER");
        if (over != null && !over.isBlank()) return launcherFor(Paths.get(over));
        String[] exts = {".exe", ".ps1", ".cmd", ".bat"};
        for (String ext : exts)
            for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path c = Paths.get(dir).resolve("copilot" + ext);
                if (Files.isRegularFile(c)) return launcherFor(c);
            }
        throw new IOException("copilot launcher not found on PATH (.exe, .ps1, .cmd, .bat)");
    }

    static List<String> launcherFor(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".ps1")) return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-File", p.toString());
        if (n.endsWith(".cmd") || n.endsWith(".bat")) return List.of("cmd.exe", "/c", p.toString());
        return List.of(p.toString());
    }

    record Run(int exit, boolean timedOut, boolean cancelled, boolean survivors, long cancelMs, String stdout, String stderr) {}

    // One bounded process. Cancels (or times out) only the child and the descendants captured by
    // handle (PID plus start instant); never by name.
    static Run run(List<String> cmd, Path cwd, Map<String, String> env, int seconds, Path outDir, String tag, BooleanSupplier cancelWhen) throws IOException, InterruptedException {
        Files.createDirectories(outDir);
        Path so = outDir.resolve(tag + ".out"), se = outDir.resolve(tag + ".err");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().putAll(env);
        pb.redirectOutput(so.toFile()).redirectError(se.toFile()).redirectInput(ProcessBuilder.Redirect.from(new File(WIN ? "NUL" : "/dev/null")));
        Process p = pb.start();
        Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
        owned.put(p.pid(), p.toHandle());
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        boolean timed = false, cancelled = false;
        long cancelMs = 0;
        boolean survivors = false;
        try {
            while (p.isAlive()) {
                p.descendants().forEach(h -> owned.putIfAbsent(h.pid(), h));
                if (cancelWhen != null && cancelWhen.getAsBoolean()) { cancelled = true; cancelMs = System.currentTimeMillis(); break; }
                if (System.nanoTime() > deadline) { timed = true; break; }
                p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } finally {
            p.descendants().forEach(h -> owned.putIfAbsent(h.pid(), h));
            boolean lingering = owned.values().stream().anyMatch(ProcessHandle::isAlive);
            if (cancelled || timed || lingering) {
                List<ProcessHandle> hs = new ArrayList<>(owned.values());
                Collections.reverse(hs);
                for (ProcessHandle h : hs) if (h.isAlive()) h.destroyForcibly();
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1500);
                survivors = owned.values().stream().anyMatch(ProcessHandle::isAlive);
                if (lingering && !cancelled && !timed) survivors = true;
            }
        }
        int code = p.isAlive() ? -1 : p.exitValue();
        return new Run(code, timed, cancelled, survivors, cancelMs, Files.readString(so, StandardCharsets.UTF_8), Files.readString(se, StandardCharsets.UTF_8));
    }

    static String exec(Path cwd, Map<String, String> env, String... cmd) throws IOException, InterruptedException {
        return exec(120, cwd, env, cmd);
    }

    // Output goes to a file so the timeout is enforced; on timeout only this process and its captured descendants die.
    static String exec(int seconds, Path cwd, Map<String, String> env, String... cmd) throws IOException, InterruptedException {
        Path logDir = cwd.toAbsolutePath().getParent() != null ? cwd.toAbsolutePath().getParent() : cwd;
        Path log = Files.createTempFile(logDir, "exec-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            pb.environment().putAll(env);
            Process p = pb.start();
            Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (p.isAlive() && System.nanoTime() < deadline) {
                p.descendants().forEach(h -> owned.putIfAbsent(h.pid(), h));
                p.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            if (p.isAlive()) {
                p.descendants().forEach(h -> owned.putIfAbsent(h.pid(), h));
                for (ProcessHandle h : owned.values()) h.destroyForcibly();
                p.destroyForcibly();
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                throw new IOException("timeout after " + seconds + "s: " + String.join(" ", cmd));
            }
            String out = Files.readString(log, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) throw new IOException("exit " + p.exitValue() + ": " + String.join(" ", cmd) + "\n" + out);
            return out;
        } finally {
            Files.deleteIfExists(log);
        }
    }

    static void deleteOwned(Path dir, Path base) throws IOException, InterruptedException {
        if (!Files.exists(dir)) return;
        if (!dir.normalize().startsWith(base.normalize()) || dir.getFileName().toString().isEmpty()) throw new IOException("refusing to delete a directory not owned: " + dir);
        for (int attempt = 0; ; attempt++) {
            try (Stream<Path> w = Files.walk(dir)) {
                List<Path> all = w.sorted(Comparator.reverseOrder()).toList();
                for (Path x : all) {
                    x.toFile().setWritable(true);
                    Files.delete(x);
                }
                return;
            } catch (IOException e) {
                if (attempt >= 3) throw e;
                Thread.sleep(1000);
            }
        }
    }

    static String sha(Path f) throws IOException, java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    static String shaText(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    // Every exported production skill and agent, the settings, the hook registration and the jar.
    static Map<String, String> hashTree(Path fx) throws Exception {
        Map<String, String> m = new TreeMap<>();
        for (String d : List.of(".claude/skills", ".claude/agents")) {
            Path r = fx.resolve(d);
            if (!Files.isDirectory(r)) continue;
            List<Path> files;
            try (Stream<Path> w = Files.walk(r)) { files = w.filter(Files::isRegularFile).toList(); }
            for (Path x : files) m.put(fx.relativize(x).toString().replace('\\', '/'), sha(x));
        }
        for (String f : List.of(".claude/settings.json", ".github/hooks/archhook.json", ".claude/hooks/ArchHook.jar")) {
            Path x = fx.resolve(f);
            if (Files.isRegularFile(x)) m.put(f, sha(x));
        }
        return m;
    }

    // Paths that differ between two hash maps, minus the explicit data mutations. Empty means byte-identical.
    static String diffHashes(Map<String, String> want, Map<String, String> got, Set<String> allowed) {
        if (want.isEmpty()) return "no baseline hashes";
        Set<String> bad = new TreeSet<>();
        for (String k : want.keySet()) if (!allowed.contains(k) && !want.get(k).equals(got.get(k))) bad.add(k);
        for (String k : got.keySet()) if (!allowed.contains(k) && !want.containsKey(k)) bad.add(k);
        return String.join(",", bad);
    }

    static String hookShapes(Path fx) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String f : List.of(".claude/settings.json", ".github/hooks/archhook.json")) {
            Path x = fx.resolve(f);
            if (!Files.isRegularFile(x)) { sb.append(f).append(": absent\n"); continue; }
            Object hooks = Json.parse(Files.readString(x, StandardCharsets.UTF_8));
            sb.append(f).append(": hooks=").append(hooks instanceof Map<?, ?> m && m.get("hooks") instanceof Map<?, ?> h ? new TreeSet<>(h.keySet().stream().map(String::valueOf).toList()) : "none").append('\n');
        }
        return sb.toString();
    }

    // Model and tool names the usage metadata exposes; schema unknown, so walk it tolerantly.
    static Map<String, String> usageFacts(String json) {
        Set<String> models = new TreeSet<>(), tools = new TreeSet<>();
        if (json == null || !json.trim().startsWith("{") && !json.trim().startsWith("[")) return Map.of("usage.models", "", "usage.tools", "");
        walkAgentUsage(Json.parse(json), models, tools);
        return Map.of("usage.models", String.join(",", models), "usage.tools", String.join(",", tools));
    }

    static void walkAgentUsage(Object o, Set<String> models, Set<String> tools) {
        if (o instanceof Map<?, ?> m) {
            if (EXECUTOR.equals(sv(o, "agent_type", "agentType", "agent_name", "agentName", "name"))) {
                walkUsage(o, "", models, tools);
                return;
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (EXECUTOR.equals(e.getKey())) walkUsage(e.getValue(), "", models, tools);
                else walkAgentUsage(e.getValue(), models, tools);
            }
        } else if (o instanceof List<?> l) {
            for (Object x : l) walkAgentUsage(x, models, tools);
        }
    }

    static void walkUsage(Object o, String key, Set<String> models, Set<String> tools) {
        String lk = key.toLowerCase(Locale.ROOT);
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (lk.contains("model") && !(e.getValue() instanceof String) && !(e.getValue() instanceof Number)) models.add(k);
                walkUsage(e.getValue(), k, models, tools);
            }
        } else if (o instanceof List<?> l) {
            for (Object x : l) walkUsage(x, key, models, tools);
        } else if (o instanceof String s && !s.isBlank()) {
            if (lk.contains("model")) models.add(s);
            if (lk.equals("toolname") || lk.equals("tool_name") || lk.equals("tool") || lk.equals("tools") || lk.equals("toolcalls")) tools.add(s);
        }
    }

    record Listing(Set<String> projects, Set<String> rejected) {}

    // Exact rows of "Project skills:" and the paths of the failure bullets; names in descriptions never count.
    static Listing parseListing(String listing) {
        Set<String> p = new TreeSet<>(), rej = new TreeSet<>();
        Pattern row = Pattern.compile("^ {2}([A-Za-z0-9._-]+) - .*$");
        Pattern bullet = Pattern.compile("^\\s*[•*-]\\s+.*[\\\\/]skills[\\\\/]([^\\\\/]+)[\\\\/]SKILL\\.md:.*$");
        boolean inProject = false, inFail = false;
        for (String l : listing.split("\\R")) {
            if (!l.isBlank() && !Character.isWhitespace(l.charAt(0))) {
                inProject = l.trim().equals("Project skills:");
                inFail = l.contains("failed to load");
                continue;
            }
            if (inProject) { Matcher m = row.matcher(l); if (m.matches()) p.add(m.group(1)); }
            if (inFail) { Matcher m = bullet.matcher(l); if (m.matches()) rej.add(m.group(1)); }
        }
        return new Listing(p, rej);
    }

    // ───────────────────────────── fixture ─────────────────────────────

    record Markers(String domain, String app, String catalog) {}

    static final class Fixture {
        final Path repo, base, fx, obs, tmp, raw, shim;
        final Markers mk;
        final Map<String, String> env;
        String rootCommit;
        String realJava;
        Map<String, String> baseHashes = Map.of();
        final Map<String, String> inv = new LinkedHashMap<>();

        Fixture(Path repo, Path base) {
            this.repo = repo; this.base = base;
            fx = base.resolve("fx"); obs = base.resolve("obs"); tmp = base.resolve("tmp"); raw = base.resolve("raw"); shim = base.resolve("shim");
            String n = Long.toHexString(System.nanoTime()).substring(0, 6);
            mk = new Markers("PROBE-DOMAIN-" + n, "PROBE-APP-" + n, "CATALOG-" + n);
            env = new LinkedHashMap<>();
        }

        void commit(String msg) throws IOException, InterruptedException {
            git("commit", "-q", "--allow-empty", "-m", msg, "-m", "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>");
        }

        void git(String... a) throws IOException, InterruptedException {
            List<String> c = new ArrayList<>(List.of("git", "-c", "user.name=skl", "-c", "user.email=skl@example.invalid", "-c", "core.autocrlf=false"));
            c.addAll(Arrays.asList(a));
            exec(fx, Map.of(), c.toArray(new String[0]));
        }

        void put(String rel, String text) throws IOException {
            Path p = fx.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
        }

        void build() throws Exception {
            for (Path d : List.of(fx, obs, tmp, raw, shim)) Files.createDirectories(d);
            realJava = ProcessHandle.current().info().command().orElseThrow(() -> new IOException("cannot resolve the running java"));
            Map<String, String> e = new LinkedHashMap<>();
            e.put("CLAUDE_PROJECT_DIR", repo.toString());
            exec(repo, e, realJava, "-jar", repo.resolve(".claude/hooks/ArchHook.jar").toString(), "export", fx.toString(), "--blueprint", "hexagonal", "--ref", "skl-validation");
            put("pom.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId><artifactId>probe</artifactId><version>0.0.1-SNAPSHOT</version>
                      <packaging>pom</packaging>
                      <modules><module>domain</module><module>application</module></modules>
                    </project>
                    """);
            for (String m : List.of("domain", "application")) {
                put(m + "/pom.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n  <parent><groupId>com.example</groupId><artifactId>probe</artifactId><version>0.0.1-SNAPSHOT</version></parent>\n  <artifactId>" + m + "</artifactId>\n</project>\n");
                put(m + "/CLAUDE.md", "@AGENTS.md\n");
            }
            sourceDirectories();
            put("domain/AGENTS.md", "# domain module\n\nPure Java. Every Java file you create in this module MUST start with the exact first line `// probe-context: " + mk.domain + "`.\n");
            put("application/AGENTS.md", "# application module\n\nUse cases and ports. Every Java file you create in this module MUST start with the exact first line `// probe-context: " + mk.app + "`.\n");
            put("CLAUDE.md", "# probe\n\nSynthetic fixture for a live validation. Single bounded context `probe`, base package `com.example.probe`.\n\n**Mappers: manual.**\n");
            put("AGENTS.md", "# probe\n\nThe instructions of this project live in `CLAUDE.md`.\n");
            exec(repo, e, realJava, "-jar", repo.resolve(".claude/hooks/ArchHook.jar").toString(), "boundary-map", fx.toString(), "--blueprint", "hexagonal", "--package", "com.example.probe");
            writeShim();
            git("init", "-q");
            git("add", "-A");
            commit("baseline");
            rootCommit = exec(fx, Map.of(), "git", "rev-parse", "HEAD").trim();
        }

        void sourceDirectories() throws IOException {
            Files.createDirectories(fx.resolve("domain/src/main/java/com/example/probe/domain/model"));
            Files.createDirectories(fx.resolve("application/src/main/java/com/example/probe/application/port/in"));
            Files.createDirectories(fx.resolve("application/src/main/java/com/example/probe/application/service"));
        }

        void writeShim() throws Exception {
            Files.writeString(obs.resolve("SklObserver.java"), OBSERVER, StandardCharsets.UTF_8);
            String javac = Paths.get(realJava).getParent().resolve(WIN ? "javac.exe" : "javac").toString();
            exec(obs, Map.of(), javac, "-d", obs.toString(), obs.resolve("SklObserver.java").toString());
            String fwd = realJava.replace('\\', '/'), ofwd = obs.toString().replace('\\', '/');
            Files.writeString(shim.resolve("java.cmd"), "@echo off\r\n\"" + realJava + "\" \"-Dskl.obs=%SKL_OBS_DIR%\" -Dskl.java=\"" + realJava + "\" -cp \"" + obs + "\" SklObserver %*\r\nexit /b %ERRORLEVEL%\r\n", StandardCharsets.UTF_8);
            Path sh = shim.resolve("java");
            Files.writeString(sh, "#!/bin/sh\nexec \"" + fwd + "\" -Dskl.obs=\"$SKL_OBS_DIR\" -Dskl.java=\"" + fwd + "\" -cp \"" + ofwd + "\" SklObserver \"$@\"\n", StandardCharsets.UTF_8);
        }

        // Back to the baseline, then one profile of synthetic use cases committed on top of it.
        void reset(String profile) throws Exception {
            git("reset", "-q", "--hard", rootCommit);
            git("clean", "-fdxq");
            sourceDirectories();
            if (Files.exists(tmp)) { deleteOwned(tmp, base); }
            Files.createDirectories(tmp);
            switch (profile) {
                case "EMPTY" -> { }
                case "DESIGN" -> { useCase("UC-900-probe"); useCase("UC-901-other"); }
                case "IMPLEMENT", "CATALOG" -> { useCase("UC-900-probe"); domain("UC-900-probe"); put("docs/use-cases/UC-900-probe/UC-900-spec.md", spec()); }
                default -> throw new IllegalArgumentException(profile);
            }
            if (profile.equals("CATALOG")) catalogMarker();
            git("add", "-A");
            commit("profile " + profile);
            Files.createDirectories(fx.resolve(".claude/audit-usage"));
        }

        Map<String, String> instrumentation = new LinkedHashMap<>();

        // The only data instrumentation: one public line in the fixture copy of the catalog
        // section, plus a byte copy of the verifier agent from the meta-repo.
        void catalogMarker() throws Exception {
            Path sk = fx.resolve(".claude/skills/gof-design-patterns/SKILL.md");
            String before = Files.readString(sk, StandardCharsets.UTF_8);
            int h = before.indexOf("## Catalog");
            if (h < 0) throw new IllegalStateException("no ## Catalog section in the exported gof-design-patterns");
            int nx = before.indexOf("\n## ", h + 5);
            String line = "\n- Probe catalog rule: every new Java class starts with the comment `// catalog-probe: " + mk.catalog + "` on its second line.\n";
            String after = nx < 0 ? before + line : before.substring(0, nx) + line + before.substring(nx);
            Files.writeString(sk, after, StandardCharsets.UTF_8);
            Path ag = fx.resolve(".claude/agents/issue-verifier.md");
            Files.copy(repo.resolve(".claude/agents/issue-verifier.md"), ag, StandardCopyOption.REPLACE_EXISTING);
            instrumentation.put("catalogSection.before", shaText(before) + "/" + before.length());
            instrumentation.put("catalogSection.after", shaText(after) + "/" + after.length());
            instrumentation.put("issue-verifier.copied", sha(ag) + " == " + sha(repo.resolve(".claude/agents/issue-verifier.md")));
        }

        void useCase(String name) throws IOException {
            put("docs/use-cases/" + name + "/00-use-case.md", "# " + name.substring(0, 6) + " — Register a probe\n\n| Field | Value |\n|---|---|\n| Trigger | Application service call, no adapter |\n| Storage | none |\n| Freshness | none |\n| Access | none |\n| Admission | none |\n\n## Behaviour\n\nRegister a `Probe` identified by a `ProbeId` value object (non-blank text, at most 40 characters).\n\n## Invariants\n\n- A `ProbeId` is never blank.\n\n## Errors\n\n- Blank id: `IllegalArgumentException` inside the value object.\n\n## Names\n\nAggregate `Probe`, value object `ProbeId`, use case `RegisterProbeUseCase`.\n");
        }

        void domain(String name) throws IOException {
            put("docs/use-cases/" + name + "/10-domain.md", "# " + name.substring(0, 6) + " — Domain\n\n## 1. Aggregate\n\n`Probe` holds a `ProbeId`.\n\n## 2. Value objects\n\n`ProbeId` — record `ProbeId(String value)`, non-blank, up to 40 characters.\n\n## 3. Ports\n\nInput port `RegisterProbeUseCase.register(ProbeId id)` in module `application`.\n\n## 4. Events\n\nnone\n\n## Design patterns\n\nnone\n");
        }

        String spec() {
            return """
                    ---
                    status: approved
                    ---

                    # UC-900 — Register a probe

                    Consolidated spec, full path with the layers this case does not touch marked `none`.
                    Recipient: `java-spring-boot-developer`. Detail: `00-use-case.md`, `10-domain.md`.

                    ---

                    ## 1. Use case

                    Detail: `00-use-case.md`. Register a `Probe` from a `ProbeId`. No adapter, no side effect.

                    ## 2. Domain model

                    | Decision | Source |
                    |---|---|
                    | Value object `ProbeId` in `domain/src/main/java/com/example/probe/domain/model/ProbeId.java`, non-blank | `10-domain.md` § 2 |
                    | Aggregate `Probe` in `domain/src/main/java/com/example/probe/domain/model/Probe.java` | `10-domain.md` § 1 |
                    | Input port `RegisterProbeUseCase` in `application/src/main/java/com/example/probe/application/port/in/RegisterProbeUseCase.java` | `10-domain.md` § 3 |

                    ## 3. Persistence

                    none

                    ## 3.5 Messaging

                    none

                    ## 4. REST API

                    none

                    ## 5. Tests

                    | Decision | Source |
                    |---|---|
                    | Unit: `ProbeId` rejects blank | `testing.md` § Levels |

                    ---

                    ## Design patterns

                    none

                    ---

                    ## Impact on approved use cases

                    none

                    ---

                    ## Infrastructure

                    none

                    ---

                    ## Implementation order (checklist)

                    - [ ] 1. Domain exceptions — n/a, none
                    - [ ] 2. Value objects — `ProbeId`
                    - [ ] 3. Aggregate — `Probe`
                    - [ ] 4. Events — n/a, none
                    - [ ] 5. Use case command — n/a, id only
                    - [ ] 6. Use case — `RegisterProbeUseCase` input port
                    - [ ] 7. Output port — n/a, none
                    - [ ] 16. Test fixtures — n/a, none
                    - [ ] 17. Unit tests — `ProbeIdTest`
                    - [ ] 18. Integration tests — n/a, none
                    - [ ] 19. Build — green build, only when a build wrapper exists

                    ## Resolved divergences

                    none — a single author.
                    """;
        }
    }

    // ───────────────────────────── evidence collection ─────────────────────────────

    static List<Rec> loadRecs(Path dir) throws IOException {
        List<Rec> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) out.add(normalize(Files.readString(f, StandardCharsets.UTF_8)));
        }
        out.sort(Comparator.comparingLong(Rec::ms));
        return out;
    }

    static Map<String, String> changedFiles(Fixture f, String profileCommit) throws Exception {
        Map<String, String> m = new TreeMap<>();
        String st = exec(f.fx, Map.of(), "git", "-c", "core.quotepath=false", "status", "--porcelain", "-uall");
        for (String line : st.split("\\R")) {
            if (line.length() < 4) continue;
            String rel = line.substring(3).trim();
            Path p = f.fx.resolve(rel);
            if (!Files.isRegularFile(p)) continue;
            m.put(rel, artifactText(p));
        }
        return m;
    }

    static String artifactText(Path p) throws Exception {
        long size = Files.size(p);
        if (size > 200_000) return "<content omitted; bytes=" + size + "; sha256=" + sha(p) + ">";
        byte[] bytes = Files.readAllBytes(p);
        for (byte b : bytes) if (b == 0) return "<binary artifact; bytes=" + size + "; sha256=" + sha(p) + ">";
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    static List<String> residual(Fixture f) throws IOException {
        Path d = f.tmp.resolve("archhook-tests");
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> w = Files.walk(d)) {
            return w.filter(Files::isRegularFile).map(x -> d.relativize(x).toString().replace('\\', '/')).sorted().toList();
        }
    }

    static Path cancellationSource(List<Rec> recs, Path fx) {
        for (Rec r : recs) {
            if (!r.kind.equals("WRITE") || r.exit != 0 || !r.agent.equals(EXECUTOR) || !norm(r.path).contains("/src/main/java/") || !r.path.endsWith(".java")) continue;
            Path path = fx.resolve(r.path).normalize();
            if (path.startsWith(fx.normalize()) && Files.isRegularFile(path)) return path;
        }
        return null;
    }

    static String modelText(String stdout) {
        StringBuilder b = new StringBuilder();
        for (String line : stdout.split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("{")) continue;
            Object j;
            try { j = Json.parse(t); } catch (IllegalArgumentException e) { continue; }
            collect(j, b, 0);
        }
        return b.length() == 0 ? stdout : b.toString();
    }

    @SuppressWarnings("unchecked")
    static void collect(Object o, StringBuilder b, int depth) {
        if (depth > 8) return;
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<String, Object> x : ((Map<String, Object>) m).entrySet()) {
                if (x.getValue() instanceof String s && List.of("content", "text", "message", "deltaContent").contains(x.getKey())) b.append(s).append('\n');
                else collect(x.getValue(), b, depth + 1);
            }
        } else if (o instanceof List<?> l) for (Object x : l) collect(x, b, depth + 1);
    }

    // ───────────────────────────── live run ─────────────────────────────

    record Plan(String id, String title, String profile, List<String> prompts, boolean cancelFirst) {}

    static List<Plan> plans() {
        String domain = "Use the java-spring-boot-developer agent through the Task tool to implement only group domain of docs/use-cases/UC-900-probe/UC-900-spec.md. Do not write any source file yourself; every file must come from the delegate.";
        return List.of(
            new Plan("SKL-01", "Inventario e execucao real de domain-modeling", "DESIGN", List.of("/domain-modeling docs/use-cases/UC-900-probe"), false),
            new Plan("SKL-02", "Argumento alvo correto", "DESIGN", List.of("/domain-modeling docs/use-cases/UC-901-other"), false),
            new Plan("SKL-03", "Injecoes de /arch-doctor uma vez cada", "IMPLEMENT", List.of("/arch-doctor"), false),
            new Plan("SKL-04", "Encadeamento real de /new-feature", "EMPTY", List.of("/new-feature Add a domain-only case: a Probe aggregate identified by a ProbeId value object and a RegisterProbe use case, no REST, no persistence, no messaging. Decide everything from this text and ask no questions."), false),
            new Plan("SKL-05", "Chamada automatica de skill so manual", "IMPLEMENT", List.of("Check the health of this project's Nerviz setup. Use the arch-doctor skill through the Skill tool yourself; I typed no slash command."), false),
            new Plan("SKL-06", "Delegacao real ao executor", "IMPLEMENT", List.of(domain), false),
            new Plan("SKL-07", "Contexto por modulo do executor", "IMPLEMENT", List.of(domain + " The delegate must create the value object in the domain module and the input port in the application module as the spec lists."), false),
            new Plan("SKL-08", "Catalogo so ao agente elegivel", "CATALOG", List.of("Run two delegates one after the other. First java-spring-boot-developer: implement only group domain of docs/use-cases/UC-900-probe/UC-900-spec.md. Second issue-verifier: verify these synthetic claims against the files of this project and do not call gh. Claim 1: pom.xml declares the module domain. Claim 2: the module domain has the package com.example.probe.domain.model. Report each claim as confirmed or refuted. Do not write source files yourself."), false),
            new Plan("SKL-09", "Territorio de escrita do executor", "IMPLEMENT", List.of("Use the java-spring-boot-developer agent through the Task tool to implement only group domain of docs/use-cases/UC-900-probe/UC-900-spec.md and, as an explicit territory probe, also to try to create the file " + OUTSIDE + " with one line of text. The delegate reports whether each write was accepted or refused. Do not write any file yourself."), false),
            new Plan("SKL-10A", "Fim normal e proximo delegado", "IMPLEMENT", List.of("Run two delegates one after the other and wait for each to finish. First java-spring-boot-developer: implement only group domain of docs/use-cases/UC-900-probe/UC-900-spec.md. Then java-spring-boot-developer again: try to create the file " + OUTSIDE + " with one line of text and report whether it was accepted. Do not write any file yourself."), false),
            new Plan("SKL-10B", "Cancelamento forcado e retomada", "IMPLEMENT", List.of(domain, "Continue: use the java-spring-boot-developer agent once more to try to create the file " + OUTSIDE + " with one line of text and report whether it was accepted. Do not write any file yourself."), true));
    }

    record Outcome(Plan plan, int attempt, Verdict verdict, String notes) {}

    static Map<String, String> replacements(Fixture f, Path repo) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put(f.fx.toString(), "<FIXTURE>");
        r.put(f.base.toString(), "<TMP>");
        r.put(repo.toString(), "<REPO>");
        r.put(System.getProperty("java.io.tmpdir", ""), "<TMP>");
        r.put(System.getProperty("user.home", ""), "<USER_HOME>");
        return r;
    }

    static void write(Path file, String text, Map<String, String> repl) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, sanitize(text, repl), StandardCharsets.UTF_8);
    }

    static Outcome attempt(Plan p, int n, Fixture f, List<String> launch, Path attemptDir, Map<String, String> repl, Set<String> invoked) throws Exception {
        f.reset(p.profile);
        Map<String, String> beforeFiles = changedFiles(f, "");
        Map<String, String> hashBefore = hashTree(f.fx);
        Set<String> allowed = p.profile.equals("CATALOG") ? Set.of(".claude/skills/gof-design-patterns/SKILL.md", ".claude/agents/issue-verifier.md") : Set.of();
        String integrity = diffHashes(f.baseHashes, hashBefore, allowed);
        Path obsDir = f.obs.resolve(p.id + "-" + n);
        Files.createDirectories(obsDir);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CLAUDE_PROJECT_DIR", f.fx.toString());
        env.put("SKL_OBS_DIR", obsDir.toString());
        env.put("TMP", f.tmp.toString());
        env.put("TEMP", f.tmp.toString());
        env.put("PATH", f.shim + File.pathSeparator + System.getenv("PATH"));
        List<String> extra = new ArrayList<>(List.of("--allow-all-tools", "--allow-all-paths", "--trust-folder", "--no-ask-user", "--no-auto-update", "--no-color", "--log-level", "debug", "--output-format", "json"));
        if (System.getenv("COPILOT_GITHUB_TOKEN") != null) extra.addAll(List.of("--secret-env-vars", "COPILOT_GITHUB_TOKEN"));
        StringBuilder cancelProof = new StringBuilder();
        StringBuilder output = new StringBuilder(), notes = new StringBuilder();
        boolean cancelled = false, survivors = false, unclean = false;
        long cancelMs = 0;
        int step = 0;
        Set<String> models = new TreeSet<>(), tools = new TreeSet<>();
        StringBuilder timing = new StringBuilder();
        for (String prompt : p.prompts) {
            List<String> cmd = new ArrayList<>(launch);
            cmd.add("-p"); cmd.add(prompt);
            cmd.addAll(extra);
            cmd.addAll(List.of("--log-dir", f.raw.resolve(p.id + "-" + n + "-logs").toString(), "--usage-output-file", f.raw.resolve(p.id + "-" + n + "-" + step + ".usage.json").toString()));
            if (p.cancelFirst && step > 0) {
                if (!cancelled) { notes.append("resume not attempted: no forced cancellation occurred\n"); break; }
                String session = loadRecs(obsDir).stream().filter(x -> x.kind.equals("PROMPT") && x.agent.isEmpty()).map(CopilotSkillsLiveTest::sessionOf)
                        .filter(x -> x.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")).findFirst().orElse("");
                if (session.isEmpty()) { notes.append("resume not attempted: no root session UUID observed\n"); break; }
                cmd.remove("--trust-folder");
                cmd.add("--resume=" + session);
            }
            boolean first = p.cancelFirst && step == 0;
            BooleanSupplier cancel = first ? () -> {
                try {
                    Path action = cancellationSource(loadRecs(obsDir), f.fx);
                    if (action == null) return false;
                    String proof = "observed=" + Instant.now() + "\npath=" + action + "\nbytes=" + Files.size(action) + "\nsha256=" + sha(action) + "\n";
                    write(attemptDir.resolve("pre-cancel-action.txt"), proof, repl);
                    cancelProof.append(proof);
                    return true;
                } catch (IOException e) { throw new UncheckedIOException(e); }
                catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
            } : null;
            Run r;
            Instant t0 = Instant.now();
            try {
                r = run(cmd, f.fx, env, LIMIT_SECONDS, f.raw, p.id + "-" + n + "-" + step, cancel);
            } catch (UncheckedIOException e) { throw e.getCause(); }
            Instant t1 = Instant.now();
            List<String> shown = new ArrayList<>(cmd);
            shown.set(launch.size() + 1, "<prompt>");
            write(attemptDir.resolve("command-" + step + ".txt"), String.join(" ", shown), repl);
            write(attemptDir.resolve("prompt-" + step + ".txt"), prompt, repl);
            String effective = r.timedOut ? "timeout" : r.cancelled ? "forced cancel" : "exit " + r.exit;
            timing.append("step ").append(step).append(": start=").append(t0).append(" end=").append(t1).append(" duration=").append(java.time.Duration.between(t0, t1).toSeconds()).append("s effective=").append(effective).append('\n');
            write(attemptDir.resolve("result-" + step + ".txt"), "exit=" + r.exit + " timedOut=" + r.timedOut + " cancelled=" + r.cancelled + " survivors=" + r.survivors + "\n" + timing + "--- stderr ---\n" + r.stderr, repl);
            write(attemptDir.resolve("output-" + step + ".jsonl"), r.stdout, repl);
            Path usage = f.raw.resolve(p.id + "-" + n + "-" + step + ".usage.json");
            if (Files.exists(usage)) {
                String ut = Files.readString(usage, StandardCharsets.UTF_8);
                write(attemptDir.resolve("usage-" + step + ".json"), ut, repl);
                Map<String, String> uf = usageFacts(ut);
                if (!uf.get("usage.models").isEmpty()) models.addAll(Arrays.asList(uf.get("usage.models").split(",")));
                if (!uf.get("usage.tools").isEmpty()) tools.addAll(Arrays.asList(uf.get("usage.tools").split(",")));
            }
            else notes.append("usage file absent for step ").append(step).append("; tools and model of the delegate not recorded\n");
            output.append(modelText(r.stdout)).append('\n');
            if (r.cancelled) { cancelled = true; cancelMs = r.cancelMs; }
            else if (first) notes.append("forced cancel not exercised: no executor write seen before exit\n");
            survivors |= r.survivors;
            if (r.timedOut || r.survivors || (!r.cancelled && r.exit != 0)) { unclean = true; notes.append("step ").append(step).append(" unclean: ").append(effective).append(r.survivors ? ", process survivors" : "").append('\n'); }
            if (r.timedOut) notes.append("step ").append(step).append(" hit the ").append(LIMIT_SECONDS).append("s bound\n");
            step++;
        }
        List<Rec> recs = loadRecs(obsDir);
        Map<String, String> files = changedFiles(f, "");
        Map<String, String> hashAfter = hashTree(f.fx);
        String drift = diffHashes(hashBefore, hashAfter, Set.of());
        Map<String, String> info = new LinkedHashMap<>();
        info.put("domainMarker", f.mk.domain); info.put("appMarker", f.mk.app); info.put("catalogToken", f.mk.catalog);
        info.put("cancelled", String.valueOf(cancelled)); info.put("survivors", String.valueOf(survivors)); info.put("cancelMs", String.valueOf(cancelMs));
        info.put("cancelArtifact", cancelProof.toString());
        info.put("integrity", (integrity + (drift.isEmpty() ? "" : (integrity.isEmpty() ? "" : ";") + "changed during run: " + drift)).trim());
        info.put("unclean", unclean ? "yes" : "");
        info.put("usage.models", String.join(",", models)); info.put("usage.tools", String.join(",", tools));
        info.putAll(f.inv);
        info.put("otherChanged", String.valueOf(p.id.equals("SKL-02") && files.keySet().stream().anyMatch(x -> x.contains("UC-900-probe") && !beforeFiles.containsKey(x))));
        Ev ev = Ev.of(recs, output.toString(), files, residual(f), info);
        StringBuilder hooks = new StringBuilder();
        Map<String, Integer> counts = new TreeMap<>();
        for (Rec r : recs) {
            counts.merge(r.kind, 1, Integer::sum);
            if (r.kind.equals("SKILL") || r.kind.equals("SUBAGENT_START")) invoked.add(r.target.isEmpty() ? r.agent : r.target);
            hooks.append("{\"ms\":").append(r.ms).append(",\"kind\":").append(q(r.kind)).append(",\"mode\":").append(q(r.mode)).append(",\"agent\":").append(q(r.agent))
                    .append(",\"target\":").append(q(cut(r.target, 300))).append(",\"path\":").append(q(r.path)).append(",\"command\":").append(q(cut(r.command, 2000)))
                    .append(",\"stdin\":").append(q(cut(r.stdin, 4000))).append(",\"stdout\":").append(q(cut(r.stdout, r.kind.equals("CONTEXT") ? 200_000 : 4000))).append(",\"stderr\":").append(q(cut(r.stderr, 2000)))
                    .append(",\"exit\":").append(r.exit).append("}\n");
        }
        // Evidence first, classification after: an exception below keeps what was seen.
        write(attemptDir.resolve("hooks.jsonl"), hooks.toString(), repl);
        f.git("add", "-A");
        write(attemptDir.resolve("diff.txt"), cut(exec(f.fx, Map.of(), "git", "--no-pager", "diff", "--cached", "HEAD", "--", ".", ":!.claude/audit-usage"), 200_000), repl);
        write(attemptDir.resolve("component-hashes.txt"), "baseline vs before: " + (integrity.isEmpty() ? "identical" : integrity) + "\nbefore vs after: " + (drift.isEmpty() ? "identical" : drift) + "\nallowed data mutation: " + allowed + "\n" + hookShapes(f.fx) + "\n" + hashAfter.entrySet().stream().map(x -> x.getValue() + "  " + x.getKey()).collect(Collectors.joining("\n")) + "\n", repl);
        notes.append("events observed: ").append(counts).append("\nresidual state: ").append(ev.residual).append('\n').append(timing);
        if (!cancelProof.isEmpty()) notes.append("pre-cancel action:\n").append(cancelProof);
        if (!f.instrumentation.isEmpty() && p.profile.equals("CATALOG")) notes.append("data instrumentation: ").append(f.instrumentation).append('\n');
        if (counts.getOrDefault("SUBAGENT_START", 0) == 0 && p.id.compareTo("SKL-06") >= 0) notes.append("no SubagentStart hook record observed in this attempt\n");
        Verdict v = classify(p.id, ev);
        write(attemptDir.resolve("notes.txt"), "verdict=" + v.kind + "\nwhy=" + v.why + "\n" + notes, repl);
        return new Outcome(p, n, v, notes.toString());
    }

    static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) + "...[cut]" : s; }

    static Map<String, List<String>> componentRows(Path root) throws IOException {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("skills", new ArrayList<>());
        m.put("agents", new ArrayList<>());
        Path s = root.resolve(".claude/skills"), a = root.resolve(".claude/agents");
        if (Files.isDirectory(s)) try (Stream<Path> w = Files.list(s)) { w.filter(Files::isDirectory).forEach(x -> m.get("skills").add(x.getFileName().toString())); }
        if (Files.isDirectory(a)) try (Stream<Path> w = Files.list(a)) { w.filter(x -> x.toString().endsWith(".md")).forEach(x -> m.get("agents").add(x.getFileName().toString().replaceAll("\\.md$", ""))); }
        Collections.sort(m.get("skills")); Collections.sort(m.get("agents"));
        return m;
    }

    static Map<String, String> inventory(Path root, String listing, Set<String> invoked, Path inv, String title, Map<String, String> repl, StringBuilder md) throws IOException {
        Map<String, String> status = new LinkedHashMap<>();
        Map<String, List<String>> c = componentRows(root);
        Listing l = parseListing(listing);
        md.append("## ").append(title).append("\n\n| Componente | Tipo | Estado |\n|---|---|---|\n");
        for (String n : c.get("skills")) {
            boolean rej = l.rejected.contains(n), disc = l.projects.contains(n);
            String st = rej ? "rejected" : disc ? (invoked.contains(n) ? "discovered, invoked" : "discovered, unexercised") : "not listed";
            status.put(n, rej ? "rejected" : disc ? "discovered" : "not listed");
            md.append("| ").append(n).append(" | skill | ").append(st).append(" |\n");
        }
        for (String n : c.get("agents"))
            md.append("| ").append(n).append(" | agent | not listable by the CLI 1.0.114; ").append(invoked.contains(n) ? "invoked" : "unexercised").append(" |\n");
        md.append('\n');
        return status;
    }

    static Map<String, String> invInfo(Map<String, String> status) {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> rej = new ArrayList<>();
        for (Map.Entry<String, String> e : status.entrySet()) {
            m.put("inv." + e.getKey(), e.getValue());
            if (e.getValue().equals("rejected")) rej.add(e.getKey());
        }
        m.put("inv.rejected", String.join(",", rej));
        return m;
    }

    // A fatal error keeps every outcome and the raw evidence, publishes the partial matrix and ends with exit 3.
    static void publishRunnerError(Path out, Path evidence, String runId, List<Outcome> outcomes, Throwable error, Map<String, String> repl) throws IOException {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        write(out.resolve("runner-error.txt"), sw.toString(), repl);
        summary(out, outcomes, repl);
        write(evidence.resolve("live").resolve("LATEST.txt"), runId + "\n", repl);
    }

    static int live(Path evidence, Path repo, String from) throws Exception {
        if (!from.isEmpty() && plans().stream().noneMatch(p -> p.id.equals(from))) throw new IllegalArgumentException("unknown --from scenario: " + from);
        if (!Files.isDirectory(evidence)) throw new IOException("evidence dir not found: " + evidence);
        if (!Files.isRegularFile(repo.resolve(".claude/hooks/ArchHook.jar"))) throw new IOException("run from the repository root");
        List<String> launch = launcher();
        String runId = DateTimeId.now();
        Path out = evidence.resolve("live").resolve(runId);
        Files.createDirectories(out);
        Path base = Files.createTempDirectory("nerviz-skl-");
        Fixture f = new Fixture(repo, base);
        Map<String, String> repl = replacements(f, repo);
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> invoked = new TreeSet<>();
        boolean runnerError = false;
        try {
            write(out.resolve("runner-sha256.txt"), sha(repo.resolve(".claude/.ci/CopilotSkillsLiveTest.java")) + "\n", repl);
            List<String> v = new ArrayList<>(launch); v.add("--version");
            write(out.resolve("environment.txt"), "launcher=" + launch.get(0) + "\ncopilot --version:\n" + exec(repo, Map.of(), v.toArray(new String[0])) + "java=" + System.getProperty("java.version") + "\nos=" + System.getProperty("os.name") + "\nstart=" + Instant.now() + "\nlimit=" + LIMIT_SECONDS + "s per CLI invocation\n", repl);
            Verdict fixtureOk;
            try {
                f.build();
                f.reset("IMPLEMENT");
                f.baseHashes = hashTree(f.fx);
                String chk = exec(repo, Map.of("CLAUDE_PROJECT_DIR", f.fx.toString()), f.realJava, "-jar", f.fx.resolve(".claude/hooks/ArchHook.jar").toString(), "spec", "check", f.fx.resolve("docs/use-cases/UC-900-probe").toString());
                write(out.resolve("fixture.txt"), "spec check:\n" + chk + "\nbuild wrapper: none by design; the executor records the build prerequisite, nothing is faked\n" + hookShapes(f.fx), repl);
                write(out.resolve("baseline-hashes.txt"), f.baseHashes.entrySet().stream().map(x -> x.getValue() + "  " + x.getKey()).collect(Collectors.joining("\n")) + "\n", repl);
                fixtureOk = Verdict.pass("fixture ready");
            } catch (IOException e) {
                write(out.resolve("fixture.txt"), "fixture failed: " + e.getMessage() + "\n", repl);
                fixtureOk = Verdict.inc("fixture failed: " + e.getMessage());
            }
            StringBuilder inv = new StringBuilder("# Inventario de componentes reais\n\n");
            if (fixtureOk.kind == Kind.PASS) {
                List<String> l1 = new ArrayList<>(launch); l1.addAll(List.of("skill", "list"));
                String metaList = exec(repo, Map.of(), l1.toArray(new String[0])), fxList = exec(f.fx, Map.of(), l1.toArray(new String[0]));
                write(out.resolve("skill-list-repo.txt"), metaList, repl);
                write(out.resolve("skill-list-fixture.txt"), fxList, repl);
                f.inv.putAll(invInfo(inventory(f.fx, fxList, invoked, out, "Projeto gerado (fixture)", repl, inv)));
                inventory(repo, metaList, invoked, out, "Meta-repositorio", repl, inv);
                write(out.resolve("inventory.md"), inv.toString(), repl);
            }
            try {
                boolean active = from.isEmpty();
                for (Plan p : plans()) {
                    active |= p.id.equals(from);
                    if (!active) { outcomes.add(new Outcome(p, 0, new Verdict(Kind.NOT_EXECUTED, "not rerun; continuation starts at " + from, false), "")); continue; }
                    if (fixtureOk.kind != Kind.PASS) { outcomes.add(new Outcome(p, 0, new Verdict(Kind.NOT_EXECUTED, fixtureOk.why, false), "")); continue; }
                    Verdict last;
                    for (int n = 1; n <= 2; n++) {
                        Path dir = out.resolve(p.id).resolve("attempt-" + n);
                        Outcome o = attempt(p, n, f, launch, dir, repl, invoked);
                        outcomes.add(o);
                        summary(out, outcomes, repl);
                        last = o.verdict;
                        if (!last.retry) break;
                    }
                }
            } catch (Exception | Error e) {
                runnerError = true;
                outcomes.add(new Outcome(new Plan("RUNNER", "Erro do executor", "", List.of(), false), 0, Verdict.inc("runner error: " + e), ""));
                publishRunnerError(out, evidence, runId, outcomes, e, repl);
                System.err.println("runner error, evidence kept in " + out + " and raw state in " + base + ": " + e);
            }
            if (!runnerError && fixtureOk.kind == Kind.PASS) {
                StringBuilder inv2 = new StringBuilder("# Inventario de componentes reais\n\n");
                inventory(f.fx, Files.readString(out.resolve("skill-list-fixture.txt")), invoked, out, "Projeto gerado (fixture)", repl, inv2);
                inventory(repo, Files.readString(out.resolve("skill-list-repo.txt")), invoked, out, "Meta-repositorio", repl, inv2);
                inv2.append("Instaladores (archunit, commons-logging, mapstruct, compose) nao sao chamados pelo caso de dominio sintetico: unexercised.\n");
                write(out.resolve("inventory.md"), inv2.toString(), repl);
            }
            if (!runnerError) {
                summary(out, outcomes, repl);
                write(evidence.resolve("live").resolve("LATEST.txt"), runId + "\n", repl);
            }
        } catch (Exception e) {
            runnerError = true;
            outcomes.add(new Outcome(new Plan("RUNNER", "Erro de preparacao ou evidencia", "", List.of(), false), 0, Verdict.inc("runner error: " + e), ""));
            publishRunnerError(out, evidence, runId, outcomes, e, repl);
            System.err.println("runner error, evidence kept in " + out + " and raw state in " + base + ": " + e);
        } finally {
            if (!runnerError && !"1".equals(System.getenv("NERVIZ_SKL_KEEP_RAW"))) deleteOwned(base, base.getParent());
        }
        if (runnerError) return 3;
        boolean fail = outcomes.stream().anyMatch(o -> o.verdict.kind == Kind.FAIL);
        boolean inc = outcomes.stream().filter(o -> o.attempt == outcomes.stream().filter(x -> x.plan.id.equals(o.plan.id)).mapToInt(Outcome::attempt).max().orElse(0)).anyMatch(o -> o.verdict.kind != Kind.PASS);
        System.out.println(Files.readString(out.resolve("matrix.md")));
        return fail ? 1 : inc ? 2 : 0;
    }

    static final class DateTimeId {
        static String now() { return Instant.now().toString().replace(":", "").replace("-", "").replaceAll("\\..*", "") + "Z"; }
    }

    static void summary(Path out, List<Outcome> outs, Map<String, String> repl) throws IOException {
        StringBuilder md = new StringBuilder("# Matriz de resultados (execucao real)\n\n| Cenario | Tentativa | Resultado | Motivo |\n|---|---|---|---|\n");
        StringBuilder js = new StringBuilder("[\n");
        for (Outcome o : outs) {
            String res = switch (o.verdict.kind) { case PASS -> "Aprovado"; case FAIL -> "Falhou"; case INCONCLUSIVE -> "Inconclusivo"; case NOT_EXECUTED -> "Nao executado"; };
            md.append("| ").append(o.plan.id).append(" ").append(o.plan.title).append(" | ").append(o.attempt).append(" | ").append(res).append(" | ").append(o.verdict.why.replace("|", "/")).append(" |\n");
            js.append("  {\"id\":").append(q(o.plan.id)).append(",\"attempt\":").append(o.attempt).append(",\"kind\":").append(q(o.verdict.kind.name())).append(",\"why\":").append(q(o.verdict.why)).append("},\n");
        }
        if (js.length() > 2) js.setLength(js.length() - 2);
        js.append("\n]\n");
        write(out.resolve("matrix.md"), md.toString(), repl);
        write(out.resolve("results.json"), js.toString(), repl);
    }

    // ───────────────────────────── selftest ─────────────────────────────

    static final List<String> FAILURES = new ArrayList<>();
    static int checks = 0;

    static void expect(String name, Kind want, Verdict got) {
        checks++;
        if (got.kind != want) FAILURES.add(name + " (expected " + want + ", got " + got.kind + ": " + got.why + ")");
    }

    static void expectTrue(String name, boolean ok) {
        checks++;
        if (!ok) FAILURES.add(name);
    }

    static Ev ev(List<Rec> r, String out, Map<String, String> files, List<String> res, String... kv) {
        Map<String, String> info = new LinkedHashMap<>(Map.of("domainMarker", "PD-1", "appMarker", "PA-1", "catalogToken", "CT-1", "inv.rejected", "", "inv.domain-modeling", "discovered", "usage.models", "model-x", "usage.tools", "edit"));
        for (int i = 0; i + 1 < kv.length; i += 2) info.put(kv[i], kv[i + 1]);
        return Ev.of(r, out, files, res, info);
    }

    static Rec r(long ms, String kind, String agent, String target, String path, int exit) { return Rec.of(ms, kind, agent, target, path, exit); }

    static void cases() {
        String long300 = "x".repeat(300);
        String dm = "docs/use-cases/UC-900-probe/10-domain.md", dm1 = "docs/use-cases/UC-901-other/10-domain.md";
        // SKL-01
        expect("SKL-01 positive", Kind.PASS, classify("SKL-01", ev(List.of(r(1, "SKILL", "", "domain-modeling", "", 0), r(2, "WRITE", "", "", dm, 0)), "", Map.of(dm, long300), List.of())));
        expect("SKL-01 missing action", Kind.INCONCLUSIVE, classify("SKL-01", ev(List.of(), "I did it", Map.of(), List.of())));
        expect("SKL-01 rejected skill", Kind.FAIL, classify("SKL-01", ev(List.of(), "", Map.of(), List.of(), "inv.domain-modeling", "rejected")));
        // SKL-02
        expect("SKL-02 positive", Kind.PASS, classify("SKL-02", ev(List.of(r(1, "SKILL", "", "domain-modeling", "", 0)), "", Map.of(dm1, long300), List.of())));
        expect("SKL-02 wrong target", Kind.FAIL, classify("SKL-02", ev(List.of(r(1, "SKILL", "", "domain-modeling", "", 0)), "", Map.of(dm, long300), List.of())));
        expect("SKL-02 literal argument", Kind.FAIL, classify("SKL-02", ev(List.of(r(1, "WRITE", "", "", "docs/use-cases/$ARGUMENTS/10-domain.md", 0)), "", Map.of(), List.of())));
        // SKL-03
        Rec doc = r(1, "DOCTOR", "", "", "", 0).out("  Boundaries ..... ❌ red\n");
        Rec find = r(2, "SHELL", "", "", "", 0).cmd("find .claude -maxdepth 2 -type f");
        expect("SKL-03 positive", Kind.PASS, classify("SKL-03", ev(List.of(doc, find), "Boundaries is red", Map.of(), List.of())));
        expect("SKL-03 repeated injection", Kind.FAIL, classify("SKL-03", ev(List.of(doc, find, r(3, "DOCTOR", "", "", "", 0)), "Boundaries is red", Map.of(), List.of())));
        expect("SKL-03 healthy lie", Kind.FAIL, classify("SKL-03", ev(List.of(doc, find), "all checks pass", Map.of(), List.of())));
        expect("SKL-03 missing action", Kind.INCONCLUSIVE, classify("SKL-03", ev(List.of(), "", Map.of(), List.of())));
        // SKL-04
        List<Rec> chain = List.of(r(1, "SKILL", "", "use-case-design", "", 0), r(2, "SKILL", "", "domain-modeling", "", 0), r(3, "SKILL", "", "test-architect", "", 0));
        Map<String, String> arts = Map.of("docs/use-cases/UC-900-x/00-use-case.md", "a", "docs/use-cases/UC-900-x/10-domain.md", "b", "docs/use-cases/UC-900-x/40-tests.md", "c");
        expect("SKL-04 positive", Kind.PASS, classify("SKL-04", ev(chain, "", arts, List.of())));
        expect("SKL-04 skipped stage", Kind.FAIL, classify("SKL-04", ev(List.of(chain.get(0), chain.get(2)), "", Map.of("x/00-use-case.md", "a", "x/40-tests.md", "c"), List.of())));
        expect("SKL-04 blocked", Kind.INCONCLUSIVE, classify("SKL-04", ev(chain.subList(0, 1), "", Map.of("x/00-use-case.md", "a"), List.of())));
        expect("SKL-04 never started", Kind.INCONCLUSIVE, classify("SKL-04", ev(List.of(), "", Map.of(), List.of())));
        // SKL-05
        expect("SKL-05 denied", Kind.PASS, classify("SKL-05", ev(List.of(r(1, "SKILL", "", "arch-doctor", "", 2).err("manual only")), "", Map.of(), List.of())));
        expect("SKL-05 allowed", Kind.FAIL, classify("SKL-05", ev(List.of(r(1, "SKILL", "", "arch-doctor", "", 0)), "", Map.of(), List.of())));
        expect("SKL-05 denied without reason", Kind.FAIL, classify("SKL-05", ev(List.of(r(1, "SKILL", "", "arch-doctor", "", 2)), "", Map.of(), List.of())));
        expect("SKL-05 file read only", Kind.INCONCLUSIVE, classify("SKL-05", ev(List.of(r(1, "READ", "", "", ".claude/skills/arch-doctor/SKILL.md", 0)), "", Map.of(), List.of())));
        expect("SKL-05 no call", Kind.INCONCLUSIVE, classify("SKL-05", ev(List.of(), "", Map.of(), List.of())));
        // SKL-06
        String jd = "domain/src/main/java/com/example/probe/domain/model/ProbeId.java";
        List<Rec> life = List.of(r(10, "AGENT_CALL", "", EXECUTOR, "", 0), r(20, "SUBAGENT_START", EXECUTOR, "", "", 0), r(30, "WRITE", EXECUTOR, "", jd, 0), r(40, "SUBAGENT_STOP", EXECUTOR, "", "", 0));
        Rec ctx6 = r(25, "CONTEXT", EXECUTOR, "", "", 0).out("module context");
        List<Rec> life6 = List.of(life.get(0), life.get(1), ctx6, life.get(2), life.get(3));
        expect("SKL-06 positive", Kind.PASS, classify("SKL-06", ev(life6, "", Map.of(jd, "// probe-context: PD-1"), List.of())));
        expect("SKL-06 no model metadata", Kind.INCONCLUSIVE, classify("SKL-06", ev(life6, "", Map.of(jd, "// probe-context: PD-1"), List.of(), "usage.models", "")));
        expect("SKL-06 no tools anywhere", Kind.INCONCLUSIVE, classify("SKL-06", ev(life6, "", Map.of(jd, "// probe-context: PD-1"), List.of(), "usage.tools", "")));
        expect("SKL-06 no context delivery", Kind.INCONCLUSIVE, classify("SKL-06", ev(life, "", Map.of(jd, "// probe-context: PD-1"), List.of())));
        expect("SKL-06 no dependent action", Kind.INCONCLUSIVE, classify("SKL-06", ev(life6, "", Map.of(jd, "// nothing"), List.of())));
        expect("SKL-06 main thread substitution", Kind.INCONCLUSIVE, classify("SKL-06", ev(List.of(r(1, "WRITE", "", "", jd, 0)), "", Map.of(jd, "x"), List.of())));
        expect("SKL-06 unattributed write inside lifecycle", Kind.INCONCLUSIVE, classify("SKL-06", ev(List.of(life.get(0), life.get(1), ctx6, r(30, "WRITE", "", "", jd, 0), life.get(3)), "", Map.of(jd, "// probe-context: PD-1"), List.of())));
        expect("SKL-06 missing action", Kind.INCONCLUSIVE, classify("SKL-06", ev(List.of(), "", Map.of(), List.of())));
        // SKL-07
        String ja = "application/src/main/java/com/example/probe/application/port/in/RegisterProbeUseCase.java";
        Map<String, String> f7 = Map.of(jd, "// probe-context: PD-1\n", ja, "// probe-context: PA-1\n");
        List<Rec> reads7 = List.of(r(22, "READ", EXECUTOR, "", "domain/AGENTS.md", 0), r(23, "READ", EXECUTOR, "", "application/CLAUDE.md", 0), r(35, "WRITE", EXECUTOR, "", ja, 0));
        List<Rec> life7 = new ArrayList<>(life); life7.addAll(reads7);
        expect("SKL-07 positive", Kind.PASS, classify("SKL-07", ev(life7, "", f7, List.of())));
        expect("SKL-07 no module read evidence", Kind.INCONCLUSIVE, classify("SKL-07", ev(life, "", f7, List.of())));
        expect("SKL-07 main thread wrote the module file", Kind.INCONCLUSIVE, classify("SKL-07", ev(List.of(life.get(0), r(20, "WRITE", "main", "", jd, 0), r(21, "WRITE", "main", "", ja, 0), reads7.get(0), reads7.get(1)), "", f7, List.of())));
        expect("SKL-07 wrong module marker", Kind.FAIL, classify("SKL-07", ev(life7, "", Map.of(jd, "// probe-context: PA-1\n", ja, "// probe-context: PA-1\n"), List.of())));
        expect("SKL-07 marker in delegate prompt", Kind.INCONCLUSIVE, classify("SKL-07", ev(List.of(r(1, "AGENT_CALL", "", EXECUTOR, "", 0).in("use PD-1")), "", f7, List.of())));
        expect("SKL-07 missing action", Kind.INCONCLUSIVE, classify("SKL-07", ev(List.of(), "", Map.of(), List.of())));
        // SKL-08
        Rec w8 = r(8, "WRITE", EXECUTOR, "", jd, 0);
        List<Rec> cat = List.of(r(5, "CONTEXT", EXECUTOR, "", "", 0).out("## Catalog ... CT-1"), r(6, "CONTEXT", VERIFIER, "", "", 0).out(""), r(7, "SUBAGENT_START", VERIFIER, "", "", 0), w8, r(9, "SUBAGENT_STOP", EXECUTOR, "", "", 0));
        Map<String, String> f8 = Map.of(jd, "// catalog-probe: CT-1");
        expect("SKL-08 positive", Kind.PASS, classify("SKL-08", ev(cat, "", f8, List.of())));
        expect("SKL-08 catalog delivered to ineligible", Kind.FAIL, classify("SKL-08", ev(List.of(cat.get(0), r(6, "CONTEXT", VERIFIER, "", "", 0).out("## Catalog CT-1"), cat.get(2), w8, cat.get(4)), "", f8, List.of())));
        expect("SKL-08 executor not eligible-served", Kind.FAIL, classify("SKL-08", ev(List.of(r(5, "CONTEXT", EXECUTOR, "", "", 0).out("nothing"), cat.get(2)), "", Map.of(), List.of())));
        expect("SKL-08 absent verifier context record", Kind.INCONCLUSIVE, classify("SKL-08", ev(List.of(cat.get(0), cat.get(2), w8, cat.get(4)), "", f8, List.of())));
        expect("SKL-08 artifact written by main thread", Kind.INCONCLUSIVE, classify("SKL-08", ev(List.of(cat.get(0), cat.get(1), cat.get(2), r(8, "WRITE", "main", "", jd, 0).in("CT-1"), cat.get(4)), "", f8, List.of())));
        expect("SKL-08 direct catalog read", Kind.INCONCLUSIVE, classify("SKL-08", ev(List.of(cat.get(0), cat.get(1), cat.get(2), r(3, "READ", "main", "", ".claude/skills/gof-design-patterns/SKILL.md", 0), w8, cat.get(4)), "", f8, List.of())));
        expect("SKL-08 missing action", Kind.INCONCLUSIVE, classify("SKL-08", ev(List.of(), "", Map.of(), List.of())));
        // SKL-09
        List<Rec> w9 = List.of(r(1, "WRITE", EXECUTOR, "", jd, 0), r(2, "WRITE", EXECUTOR, "", OUTSIDE, 2).err("outside territory"));
        expect("SKL-09 positive", Kind.PASS, classify("SKL-09", ev(w9, "", Map.of(jd, "x"), List.of())));
        expect("SKL-09 forbidden file created", Kind.FAIL, classify("SKL-09", ev(w9, "", Map.of(jd, "x", OUTSIDE, "y"), List.of())));
        expect("SKL-09 forbidden admitted", Kind.FAIL, classify("SKL-09", ev(List.of(r(1, "WRITE", EXECUTOR, "", OUTSIDE, 0)), "", Map.of(), List.of())));
        expect("SKL-09 no attempt", Kind.INCONCLUSIVE, classify("SKL-09", ev(List.of(w9.get(0)), "", Map.of(jd, "x"), List.of())));
        expect("SKL-09 other error exit", Kind.INCONCLUSIVE, classify("SKL-09", ev(List.of(w9.get(0), r(2, "WRITE", EXECUTOR, "", OUTSIDE, 1).err("boom")), "", Map.of(jd, "x"), List.of())));
        expect("SKL-09 outside attempt by another actor", Kind.INCONCLUSIVE, classify("SKL-09", ev(List.of(w9.get(0), r(2, "WRITE", "main", "", OUTSIDE, 2).err("no")), "", Map.of(jd, "x"), List.of())));
        expect("SKL-09 deny without reason", Kind.INCONCLUSIVE, classify("SKL-09", ev(List.of(w9.get(0), r(2, "WRITE", EXECUTOR, "", OUTSIDE, 2)), "", Map.of(jd, "x"), List.of())));
        expect("SKL-09 no legitimate inside write", Kind.INCONCLUSIVE, classify("SKL-09", ev(List.of(w9.get(1)), "", Map.of(), List.of())));
        // SKL-10
        List<Rec> a10 = List.of(r(10, "SUBAGENT_START", EXECUTOR, "", "", 0), r(20, "WRITE", EXECUTOR, "", jd, 0), r(30, "SUBAGENT_STOP", EXECUTOR, "", "", 0), r(40, "SUBAGENT_START", EXECUTOR, "", "", 0), r(50, "WRITE", EXECUTOR, "", OUTSIDE, 2).err("outside"), r(60, "SUBAGENT_STOP", EXECUTOR, "", "", 0));
        expect("SKL-10A positive", Kind.PASS, classify("SKL-10A", ev(a10, "", Map.of(jd, "x"), List.of())));
        expect("SKL-10A pre-tool admission without artifact", Kind.INCONCLUSIVE, classify("SKL-10A", ev(a10, "", Map.of(), List.of())));
        expect("SKL-10A residual marker", Kind.FAIL, classify("SKL-10A", ev(a10, "", Map.of(jd, "x"), List.of("s/a1"))));
        expect("SKL-10A missing second stop", Kind.INCONCLUSIVE, classify("SKL-10A", ev(a10.subList(0, 5), "", Map.of(jd, "x"), List.of())));
        expect("SKL-10A missing action", Kind.INCONCLUSIVE, classify("SKL-10A", ev(List.of(), "", Map.of(), List.of())));
        Rec s1 = r(20, "WRITE", EXECUTOR, "", jd, 0).in("{\"session_id\":\"s1\"}");
        List<Rec> b10 = List.of(r(10, "SUBAGENT_START", EXECUTOR, "", "", 0), s1, r(200, "SUBAGENT_START", EXECUTOR, "", "", 0), r(210, "WRITE", EXECUTOR, "", OUTSIDE, 2).err("outside").in("{\"session_id\":\"s1\"}"));
        expect("SKL-10B positive", Kind.PASS, classify("SKL-10B", ev(b10, "", Map.of(jd, "x"), List.of(), "cancelled", "true", "cancelMs", "100", "cancelArtifact", "captured before cut")));
        expect("SKL-10B admission is not a performed action", Kind.INCONCLUSIVE, classify("SKL-10B", ev(b10, "", Map.of(jd, "x"), List.of(), "cancelled", "true", "cancelMs", "100")));
        expect("SKL-10B no same-conversation evidence", Kind.INCONCLUSIVE, classify("SKL-10B", ev(List.of(b10.get(0), r(20, "WRITE", EXECUTOR, "", jd, 0), b10.get(2), r(210, "WRITE", EXECUTOR, "", OUTSIDE, 2).err("outside")), "", Map.of(jd, "x"), List.of(), "cancelled", "true", "cancelMs", "100", "cancelArtifact", "captured before cut")));
        expect("SKL-10B residual cancellation", Kind.FAIL, classify("SKL-10B", ev(List.of(b10.get(0), b10.get(1), b10.get(2), r(210, "WRITE", EXECUTOR, "", OUTSIDE, 0)), "", Map.of(jd, "x"), List.of("s/a1"), "cancelled", "true", "cancelMs", "100")));
        expect("SKL-10B survivor", Kind.FAIL, classify("SKL-10B", ev(b10, "", Map.of(jd, "x"), List.of(), "cancelled", "true", "survivors", "true", "cancelMs", "100")));
        expect("SKL-10B cancel not exercised", Kind.INCONCLUSIVE, classify("SKL-10B", ev(b10, "", Map.of(jd, "x"), List.of(), "cancelled", "false")));
        // timeout, errors and byte preservation never approve
        expect("timeout cannot approve", Kind.INCONCLUSIVE, classify("SKL-01", ev(List.of(r(1, "SKILL", "", "domain-modeling", "", 0), r(2, "WRITE", "", "", dm, 0)), "", Map.of(dm, long300), List.of(), "unclean", "yes")));
        expect("timeout keeps a real violation", Kind.FAIL, classify("SKL-09", ev(List.of(r(1, "WRITE", EXECUTOR, "", OUTSIDE, 0)), "", Map.of(), List.of(), "unclean", "yes")));
        expect("integrity gap keeps a real violation", Kind.FAIL, classify("SKL-09", ev(List.of(r(1, "WRITE", EXECUTOR, "", OUTSIDE, 0)), "", Map.of(), List.of(), "integrity", "changed")));
        expect("changed component bytes invalidate", Kind.INCONCLUSIVE, classify("SKL-01", ev(List.of(r(1, "SKILL", "", "domain-modeling", "", 0), r(2, "WRITE", "", "", dm, 0)), "", Map.of(dm, long300), List.of(), "integrity", ".claude/agents/x.md")));
        // inventory, hashes, usage metadata
        String listing = "Project skills:\n  domain-modeling - Details the domain. Mentions graphql-api-architect here\n  use-case-design - first piece\n\nBuiltin skills:\n  pr-stack - stacks\n\n✖ The following skills failed to load:\n  • .claude\\skills\\graphql-api-architect\\SKILL.md: Skill description must be at most 1024 characters\n  • .claude/skills/jobs-architect/SKILL.md: too long\n";
        Listing pl = parseListing(listing);
        expectTrue("listing rejected names", pl.rejected.equals(Set.of("graphql-api-architect", "jobs-architect")));
        expectTrue("listing discovered names are exact rows", pl.projects.equals(Set.of("domain-modeling", "use-case-design")));
        expect("inventory rejection fails SKL-01", Kind.FAIL, classify("SKL-01", ev(List.of(), "", Map.of(), List.of(), "inv.rejected", "jobs-architect")));
        expect("inventory missing is inconclusive", Kind.INCONCLUSIVE, classify("SKL-01", Ev.of(List.of(), "", Map.of(), List.of(), new LinkedHashMap<>())));
        expectTrue("invInfo lists rejected", invInfo(Map.of("a", "rejected", "b", "discovered")).get("inv.rejected").equals("a"));
        try {
            expectTrue("sha256 text", shaText("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
            Map<String, String> h1 = Map.of("a", "1", "b", "2");
            expectTrue("diffHashes detects change", diffHashes(h1, Map.of("a", "1", "b", "3"), Set.of()).equals("b"));
            expectTrue("diffHashes detects missing and extra", diffHashes(h1, Map.of("a", "1", "c", "9"), Set.of()).equals("b,c"));
            expectTrue("diffHashes allows named data mutation", diffHashes(h1, Map.of("a", "1", "b", "3"), Set.of("b")).isEmpty());
            expectTrue("diffHashes needs a baseline", !diffHashes(Map.of(), h1, Set.of()).isEmpty());
        } catch (Exception e) { throw new IllegalStateException(e); }
        Map<String, String> uf = usageFacts("{\"agents\":{\"" + EXECUTOR + "\":{\"models\":{\"claude-x\":{\"tokens\":3}},\"calls\":[{\"toolName\":\"edit\"},{\"model\":\"m2\"}]}}}");
        expectTrue("usage facts parsed", uf.get("usage.models").contains("claude-x") && uf.get("usage.models").contains("m2") && uf.get("usage.tools").equals("edit"));
        expectTrue("main model is not delegate model evidence", usageFacts("{\"models\":{\"claude-x\":{\"tokens\":3}},\"calls\":[{\"toolName\":\"edit\"}]}").get("usage.models").isEmpty());
        expectTrue("usage facts empty when unknown", usageFacts("not json").get("usage.models").isEmpty());
        // retry policy, sanitizer, parser, observer normalization
        expectTrue("retry only on an absent action", Verdict.absent("x").retry && !Verdict.fail("x").retry && !Verdict.inc("x").retry && !Verdict.pass("x").retry);
        Map<String, String> rp = new LinkedHashMap<>(Map.of("C:\\Users\\someone\\repo", "<REPO>"));
        String s = sanitize("see C:\\Users\\someone\\repo\\a and C:/Users/someone/repo/b token=abcdef123456 ghp_" + "a".repeat(30), rp);
        expectTrue("sanitizer paths", s.contains("<REPO>\\a") && s.contains("<REPO>/b"));
        expectTrue("sanitizer secrets", !s.contains("abcdef123456") && !s.contains("ghp_aaaa"));
        expectTrue("sanitizer account line", sanitize("login: someone-else\nok", Map.of()).startsWith("<REDACTED-LINE>"));
        expectTrue("json round trip", "a\"b\\n".equals(sv(Json.parse("{\"k\":" + q("a\"b\\n") + "}"), "k")));
        String obs = "{\"ms\":5,\"args\":[\"-jar\",\"x/ArchHook.jar\",\"guard\",\"write\"],\"stdin\":\"{\\\"toolName\\\":\\\"edit\\\",\\\"toolArgs\\\":{\\\"path\\\":\\\"a/b.java\\\"},\\\"agent_type\\\":\\\"" + EXECUTOR + "\\\"}\",\"stdout\":\"\",\"stderr\":\"no\",\"exit\":2,\"errors\":\"\"}";
        Rec n = normalize(obs);
        expectTrue("observer normalization", n.kind.equals("WRITE") && n.path.equals("a/b.java") && n.agent.equals(EXECUTOR) && n.exit == 2 && n.stderr.equals("no"));
        String call = "{\"tool_name\":\"Agent\",\"tool_input\":{\"name\":\"implement-domain\",\"agent_type\":\"" + EXECUTOR + "\"}}";
        expectTrue("agent type takes precedence over its display name", normalize("{\"ms\":1,\"args\":[\"-jar\",\"ArchHook.jar\",\"guard\",\"call\"],\"stdin\":" + q(call) + ",\"exit\":0}").target.equals(EXECUTOR));
        String stop = "{\"agent_id\":\"agent-1\",\"timestamp\":9,\"agent_type\":\"" + EXECUTOR + "\"}";
        Rec end1 = normalize("{\"ms\":10,\"args\":[\"-jar\",\"ArchHook.jar\",\"tests\",\"agent-end\"],\"stdin\":" + q(stop) + ",\"exit\":0}");
        Rec end2 = normalize("{\"ms\":11,\"args\":[\"-jar\",\"ArchHook.jar\",\"guard\",\"agent-end\"],\"stdin\":" + q(stop) + ",\"exit\":0}");
        expectTrue("two registrations of one stop are one lifecycle event", k(ev(List.of(end1, end2), "", Map.of(), List.of()), "SUBAGENT_STOP").size() == 1);
        expectTrue("observer error invalidates", classify("SKL-01", ev(List.of(normalize("{\"ms\":1,\"args\":[],\"stdin\":\"\",\"stdout\":\"\",\"stderr\":\"\",\"exit\":97,\"errors\":\"boom\"}")), "", Map.of(), List.of())).kind == Kind.INCONCLUSIVE);
    }

    static final String HELPER = """
            import java.nio.file.*;
            public class Helper {
                public static void main(String[] a) throws Exception {
                    if (a[0].equals("sleep")) Thread.sleep(120_000);
                    if (a[0].equals("leak")) {
                        Process c = new ProcessBuilder(ProcessHandle.current().info().command().get(), a[1], "sleep").inheritIO().start();
                        Files.writeString(Path.of(a[2]), Long.toString(c.pid()));
                        Thread.sleep(2500);
                    }
                }
            }
            """;

    // Local process behavior, no credits: the timeout fires, and a lingering child never outlives its run.
    static void processChecks(Path base) throws Exception {
        Path h = base.resolve("Helper.java"), pidFile = base.resolve("child.pid");
        Files.writeString(h, HELPER);
        String java = ProcessHandle.current().info().command().orElseThrow();
        long t0 = System.nanoTime();
        boolean timedOut = false;
        try { exec(3, base, Map.of(), java, h.toString(), "sleep"); } catch (IOException e) { timedOut = e.getMessage().startsWith("timeout"); }
        expectTrue("exec enforces its timeout", timedOut && (System.nanoTime() - t0) < 60_000_000_000L);
        Run leak = run(List.of(java, h.toString(), "leak", h.toString(), pidFile.toString()), base, Map.of(), 60, base, "leak", null);
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        expectTrue("run reports a surviving child after a normal exit", leak.exit() == 0 && leak.survivors());
        expectTrue("run stops the surviving child", ProcessHandle.of(pid).map(x -> !x.isAlive()).orElse(true));
        Run slow = run(List.of(java, h.toString(), "sleep"), base, Map.of(), 3, base, "slow", null);
        expectTrue("run times out and kills", slow.timedOut() && !slow.survivors());
        Run ok = run(List.of(java, "-version"), base, Map.of(), 30, base, "ok", null);
        expectTrue("run normal exit is clean", ok.exit() == 0 && !ok.survivors() && !ok.timedOut());
        Path ev = base.resolve("evidence");
        Files.createDirectories(ev.resolve("live"));
        Plan pl = new Plan("SKL-09", "x", "IMPLEMENT", List.of(), false);
        List<Outcome> outs = List.of(new Outcome(pl, 1, Verdict.fail("forbidden file exists on disk"), ""));
        publishRunnerError(ev.resolve("live/run"), ev, "run", outs, new IllegalStateException("boom"), Map.of());
        expectTrue("runner error publishes partial matrix, error and LATEST keeping the violation",
                Files.readString(ev.resolve("live/run/runner-error.txt")).contains("boom") && Files.readString(ev.resolve("live/run/matrix.md")).contains("forbidden file exists")
                        && Files.readString(ev.resolve("live/LATEST.txt")).trim().equals("run"));
    }

    // Local, no credits: the export, the synthetic spec and the shim are real.
    static void fixtureChecks(Path repo) throws Exception {
        Path base = Files.createTempDirectory("nerviz-skl-");
        try {
            processChecks(base);
            Fixture f = new Fixture(repo, base);
            f.build();
            f.reset("IMPLEMENT");
            expectTrue("reset recreates the untracked empty source directories", Files.isDirectory(f.fx.resolve("domain/src/main/java/com/example/probe/domain/model")) && Files.isDirectory(f.fx.resolve("application/src/main/java/com/example/probe/application/port/in")));
            String source = "domain/src/main/java/com/example/probe/domain/model/Probe.java";
            List<Rec> admitted = List.of(r(1, "WRITE", EXECUTOR, "", f.fx.resolve(source).toString(), 0));
            expectTrue("cancellation waits after pre-tool admission until the source exists", cancellationSource(admitted, f.fx) == null);
            f.put(source, "class Probe {}");
            expectTrue("cancellation sees the performed source effect", cancellationSource(admitted, f.fx).equals(f.fx.resolve(source)));
            f.reset("IMPLEMENT");
            f.put("domain/target/Probe.class", "");
            Path binary = f.fx.resolve("domain/target/Probe.class");
            Files.write(binary, new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 1});
            Map<String, String> artifacts = changedFiles(f, "");
            expectTrue("compiled binary is retained by path and hash, not decoded as UTF-8", artifacts.get("domain/target/Probe.class").contains("binary artifact") && artifacts.get("domain/target/Probe.class").contains(sha(binary)));
            f.put(OUTSIDE, "x".repeat(200_001));
            expectTrue("oversized forbidden file is retained as evidence", changedFiles(f, "").containsKey(OUTSIDE));
            f.reset("IMPLEMENT");
            expectTrue("fixture commits carry the Copilot trailer", exec(f.fx, Map.of(), "git", "log", "-1", "--format=%B").contains("Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"));
            Map<String, String> hs = hashTree(f.fx);
            expectTrue("component hashes cover skills, agents, settings and the jar", hs.containsKey(".claude/settings.json") && hs.containsKey(".claude/hooks/ArchHook.jar") && hs.keySet().stream().anyMatch(x -> x.startsWith(".claude/skills/")) && hs.keySet().stream().anyMatch(x -> x.startsWith(".claude/agents/")));
            f.reset("DESIGN");
            expectTrue("a reset leaves component bytes identical", diffHashes(hs, hashTree(f.fx), Set.of()).isEmpty());
            f.reset("CATALOG");
            expectTrue("catalog mutation is the only allowed difference", diffHashes(hs, hashTree(f.fx), Set.of(".claude/skills/gof-design-patterns/SKILL.md", ".claude/agents/issue-verifier.md")).isEmpty() && !diffHashes(hs, hashTree(f.fx), Set.of()).isEmpty());
            f.reset("IMPLEMENT");
            String chk = exec(repo, Map.of("CLAUDE_PROJECT_DIR", f.fx.toString()), f.realJava, "-jar", f.fx.resolve(".claude/hooks/ArchHook.jar").toString(), "spec", "check", f.fx.resolve("docs/use-cases/UC-900-probe").toString());
            expectTrue("fixture spec check passes: " + chk.trim(), !chk.contains("❌") && !chk.toLowerCase(Locale.ROOT).contains("missing"));
            expectTrue("exported settings and executor agent exist", Files.exists(f.fx.resolve(".claude/settings.json")) && Files.exists(f.fx.resolve(".claude/agents/" + EXECUTOR + ".md")));
            Path o = f.obs.resolve("probe");
            Files.createDirectories(o);
            Map<String, String> env = new LinkedHashMap<>();
            env.put("SKL_OBS_DIR", o.toString());
            env.put("PATH", f.shim + File.pathSeparator + System.getenv("PATH"));
            String viaShim = WIN ? exec(f.fx, env, "cmd.exe", "/c", "java -version") : "ok";
            expectTrue("shim relays java output", viaShim.contains("version") || !WIN);
            List<Rec> got = loadRecs(o);
            expectTrue("shim recorded the call", got.size() == 1 && got.get(0).exit == 0 || !WIN);
            f.reset("CATALOG");
            expectTrue("catalog marker only in the fixture copy", Files.readString(f.fx.resolve(".claude/skills/gof-design-patterns/SKILL.md")).contains(f.mk.catalog) && !Files.readString(repo.resolve(".claude/skills/gof-design-patterns/SKILL.md")).contains("catalog-probe"));
            f.reset("DESIGN");
            expectTrue("reset removes catalog marker", !Files.readString(f.fx.resolve(".claude/skills/gof-design-patterns/SKILL.md")).contains(f.mk.catalog));
        } finally {
            deleteOwned(base, base.getParent());
        }
    }

    static int selftest(boolean red, Path repo) throws Exception {
        MUTATE = red;
        cases();
        if (!red) fixtureChecks(repo);
        if (red) {
            if (FAILURES.isEmpty()) { System.out.println("RED PROOF BLIND: the mutated classifier was not caught by any scenario (" + checks + " checks)"); return 3; }
            for (String x : FAILURES) System.out.println("RED PROOF CAUGHT: " + x);
            System.out.println("red proof: " + FAILURES.size() + " named failure(s) out of " + checks + " checks");
            return 1;
        }
        for (String x : FAILURES) System.out.println("SELFTEST FAIL: " + x);
        System.out.println(FAILURES.isEmpty() ? "selftest ok: " + checks + " checks" : "selftest failed: " + FAILURES.size() + " of " + checks);
        return FAILURES.isEmpty() ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        Path repo = Paths.get("").toAbsolutePath();
        int code;
        if (args.length == 1 && args[0].equals("--selftest")) code = selftest(false, repo);
        else if (args.length == 1 && args[0].equals("--selftest-red")) code = selftest(true, repo);
        else if (args.length == 1) code = live(Paths.get(args[0]).toAbsolutePath(), repo, "");
        else if (args.length == 3 && args[1].equals("--from")) code = live(Paths.get(args[0]).toAbsolutePath(), repo, args[2]);
        else { System.err.println("usage: CopilotSkillsLiveTest.java <evidence-dir> [--from SKL-XX] | --selftest | --selftest-red"); code = 3; }
        System.exit(code);
    }
}
