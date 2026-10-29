///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// nerviz-diagrams: diagrams of this repository's own `.claude/`, derived from its data.
//
//   facts     extract stable facts from extensions.json, the SKILL.md of every skill a view
//             names (its steps), ArchHook.java's dispatch and the tree on disk, write
//             docs/diagrams/facts.json (gitignored)
//   validate  every docs/diagrams/<view>/view.json against references/view.schema.json, every
//             fact id and selector against facts extracted NOW
//   render    write <view>.drawio, <view>.excalidraw, <view>.svg and <view>.animated.svg
//   check     validate, re-render in memory, compare byte for byte with the committed files,
//             and each dist/ file's .source-sha256 with the SVG it came from. CI runs it.
//
// JDK 21+, no dependency, no network. Project dir: --root <dir>, else $CLAUDE_PROJECT_DIR,
// else the working directory. Exit 0 ok, 2 a finding, 1 a usage error.
// Design: .claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md and
//         .claude/decisions/0192-nerviz-diagrams-draws-the-orchestrators.md

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class Diagrams {

    static final String CMD = "java .claude/skills/nerviz-diagrams/scripts/Diagrams.java";
    static final String SKILL = ".claude/skills/nerviz-diagrams";
    static final String OUT = "docs/diagrams";
    static final String EXT = ".claude/schemas/extensions.json";
    static final String SCHEMA = SKILL + "/references/view.schema.json";
    static final String HOOK = ".claude/hooks/ArchHook.java";

    static Path root;

    static final class Fail extends RuntimeException {
        Fail(String m) { super(m); }
    }

    // ───────────────────────────── main ─────────────────────────────

    public static void main(String[] a) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        int code;
        try {
            code = run(a);
        } catch (Fail f) {
            System.err.println("❌ " + f.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    static int run(String[] a) throws Exception {
        String mode = null;
        String rootArg = System.getenv("CLAUDE_PROJECT_DIR");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("--root")) {
                if (i + 1 >= a.length) return usage("--root needs a directory");
                rootArg = a[++i];
            } else if (mode == null) mode = a[i];
            else names.add(a[i]);
        }
        if (mode == null) return usage("no mode");
        root = Paths.get(rootArg == null || rootArg.isBlank() ? "." : rootArg).toAbsolutePath().normalize();
        return switch (mode) {
            case "facts" -> modeFacts();
            case "validate" -> modeValidate(names);
            case "render" -> modeRender(names);
            case "check" -> modeCheck(names);
            default -> usage("unknown mode '" + mode + "'");
        };
    }

    static int usage(String why) {
        System.err.println("❌ " + why + "\nUsage: " + CMD + " facts|validate|render|check [view...] [--root <dir>]");
        return 1;
    }

    // ───────────────────────────── modes ─────────────────────────────

    static int modeFacts() throws IOException {
        Map<String, Object> facts = extractFacts();
        Path out = root.resolve(OUT).resolve("facts.json");
        Files.createDirectories(out.getParent());
        write(out, Json.write(factsDoc(facts)));
        System.out.println("✅ " + facts.size() + " facts written to " + OUT + "/facts.json (gitignored)");
        return 0;
    }

    static int modeValidate(List<String> names) throws IOException {
        Map<String, Object> facts = extractFacts();
        Identity idn = Identity.load();
        List<String> errs = new ArrayList<>();
        List<String> views = viewNames(names, errs);
        for (String v : views) resolve(v, facts, errs);
        if (!errs.isEmpty()) return report(errs);
        System.out.println("✅ " + views.size() + " view(s) valid; every fact id and selector resolves against " + facts.size() + " facts.");
        return 0;
    }

    static int modeRender(List<String> names) throws IOException {
        Map<String, Object> facts = extractFacts();
        Identity idn = Identity.load();
        List<String> errs = new ArrayList<>();
        List<String> views = viewNames(names, errs);
        Map<String, String> files = new TreeMap<>();
        for (String v : views) {
            Resolved r = resolve(v, facts, errs);
            if (r != null) files.putAll(renderAll(r, facts, idn));
        }
        if (!errs.isEmpty()) return report(errs);
        for (var e : files.entrySet()) {
            Path p = root.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            write(p, e.getValue());
        }
        System.out.println("✅ " + files.size() + " file(s) rendered from " + views.size() + " view(s):");
        for (String k : files.keySet()) System.out.println("   " + k);
        return 0;
    }

    static int modeCheck(List<String> names) throws IOException {
        Map<String, Object> facts = extractFacts();
        Identity idn = Identity.load();
        List<String> errs = new ArrayList<>();
        List<String> views = viewNames(names, errs);
        Map<String, String> fresh = new TreeMap<>();
        for (String v : views) {
            Resolved r = resolve(v, facts, errs);
            if (r != null) fresh.putAll(renderAll(r, facts, idn));
        }
        if (!errs.isEmpty()) return report(errs);
        List<String> stale = new ArrayList<>();
        for (var e : fresh.entrySet()) {
            Path p = root.resolve(e.getKey());
            if (!Files.isRegularFile(p)) { stale.add(e.getKey() + " — missing"); continue; }
            if (!norm(Files.readString(p, StandardCharsets.UTF_8)).equals(e.getValue()))
                stale.add(e.getKey() + " — differs from a fresh render (hand-edited, or the data it cites changed)");
        }
        for (String v : views) checkDist(v, stale);
        if (!stale.isEmpty()) {
            System.err.println("❌ " + stale.size() + " committed diagram file(s) do not match the data they cite:");
            for (String s : stale) System.err.println("   " + s);
            System.err.println("Never edit a rendered file. Edit docs/diagrams/<view>/view.json if the view is wrong, then run:");
            System.err.println("   " + CMD + " render");
            return 2;
        }
        System.out.println("✅ " + fresh.size() + " rendered file(s) of " + views.size() + " view(s) equal a fresh render; dist/ hashes match their SVG.");
        return 0;
    }

    static void checkDist(String view, List<String> stale) throws IOException {
        Path dist = root.resolve(OUT).resolve(view).resolve("dist");
        if (!Files.isDirectory(dist)) return;
        List<Path> fs;
        try (Stream<Path> s = Files.list(dist)) { fs = s.sorted().collect(Collectors.toList()); }
        for (Path f : fs) {
            String n = f.getFileName().toString();
            if (n.endsWith(".source-sha256") || !Files.isRegularFile(f)) continue;
            String rel = OUT + "/" + view + "/dist/" + n;
            String src = n.endsWith(".png") ? view + ".svg"
                    : (n.endsWith(".webm") || n.endsWith(".mp4")) ? view + ".animated.svg" : null;
            if (src == null) { stale.add(rel + " — a dist file must be .png, .webm or .mp4"); continue; }
            Path side = dist.resolve(n + ".source-sha256");
            Path svg = root.resolve(OUT).resolve(view).resolve(src);
            if (!Files.isRegularFile(side)) { stale.add(rel + " — no " + n + ".source-sha256 beside it"); continue; }
            if (!Files.isRegularFile(svg)) { stale.add(rel + " — its source " + src + " is missing"); continue; }
            String want = sha256(norm(Files.readString(svg, StandardCharsets.UTF_8)));
            String have = Files.readString(side, StandardCharsets.UTF_8).trim();
            if (!want.equals(have))
                stale.add(rel + " — .source-sha256 is not the SHA-256 of " + src + " (re-run export.mjs " + view + ")");
        }
    }

    static int report(List<String> errs) {
        System.err.println("❌ " + errs.size() + " problem(s):");
        for (String e : errs) System.err.println("   " + e);
        return 2;
    }

    static List<String> viewNames(List<String> given, List<String> errs) throws IOException {
        if (!given.isEmpty()) return given;
        Path d = root.resolve(OUT);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.sorted().collect(Collectors.toList()))
                if (Files.isRegularFile(p.resolve("view.json"))) out.add(p.getFileName().toString());
        }
        return out;
    }

    static String norm(String s) { return s.replace("\r\n", "\n"); }

    static void write(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static int hash31(String s) {
        byte[] h;
        try { h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return ((h[0] & 0x7f) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
    }

    // ───────────────────────────── JSON ─────────────────────────────

    static final class Json {
        final String s;
        int i;

        Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != text.length()) throw j.err("trailing content");
            return v;
        }

        IllegalArgumentException err(String m) {
            int line = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
            return new IllegalArgumentException(m + " at line " + line);
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw err("expected a key");
                String k = str();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                i++; ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw err("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (i >= s.length()) throw err("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }

        String str() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);
                }
            }
        }

        Object num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("unexpected '" + s.charAt(i) + "'");
            String t = s.substring(st, i);
            if (t.matches("-?\\d+")) return Long.parseLong(t);
            return Double.parseDouble(t);
        }

        // Writer: sorted keys, two-space indent, whole numbers without a fraction, "\n" only.
        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            w(sb, v, 0);
            return sb.append('\n').toString();
        }

        @SuppressWarnings("unchecked")
        static void w(StringBuilder sb, Object v, int d) {
            if (v == null) sb.append("null");
            else if (v instanceof String t) q(sb, t);
            else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) sb.append(v);
            else if (v instanceof Double x) sb.append(Diagrams.num(x));
            else if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) { sb.append("{}"); return; }
                sb.append("{\n");
                int n = 0;
                for (String k : new TreeSet<>((Set<String>) m.keySet())) {
                    sb.append("  ".repeat(d + 1));
                    q(sb, k);
                    sb.append(": ");
                    w(sb, m.get(k), d + 1);
                    if (++n < m.size()) sb.append(',');
                    sb.append('\n');
                }
                sb.append("  ".repeat(d)).append('}');
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) { sb.append("[]"); return; }
                sb.append("[\n");
                for (int k = 0; k < l.size(); k++) {
                    sb.append("  ".repeat(d + 1));
                    w(sb, l.get(k), d + 1);
                    if (k < l.size() - 1) sb.append(',');
                    sb.append('\n');
                }
                sb.append("  ".repeat(d)).append(']');
            } else throw new IllegalStateException("cannot write " + v.getClass());
        }

        static void q(StringBuilder sb, String t) {
            sb.append('"');
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            sb.append('"');
        }
    }

    /** A number with at most two decimals, none when whole. Locale-free. */
    static String num(double d) {
        if (d == Math.rint(d)) return Long.toString((long) d);
        return new BigDecimal(d).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : null; }

    static Object get(Object o, String... path) {
        for (String k : path) {
            Map<String, Object> m = map(o);
            if (m == null) return null;
            o = m.get(k);
        }
        return o;
    }

    static Map<String, Object> readJson(String rel) throws IOException {
        Path p = root.resolve(rel);
        if (!Files.isRegularFile(p)) throw new Fail(rel + " is missing — the project directory is " + root + " (--root, $CLAUDE_PROJECT_DIR or the working directory)");
        try {
            Map<String, Object> m = map(Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
            if (m == null) throw new Fail(rel + " is not a JSON object");
            return m;
        } catch (IllegalArgumentException e) {
            throw new Fail(rel + " is not valid JSON: " + e.getMessage());
        }
    }

    // ───────────────────────────── facts ─────────────────────────────

    static Map<String, Object> extractFacts() throws IOException {
        Map<String, Object> ext = readJson(EXT);
        Map<String, Object> f = new TreeMap<>();
        Path cl = root.resolve(".claude");

        // layers: every visible directory of .claude/, plus the two root files that are layers
        if (Files.isDirectory(cl)) {
            try (Stream<Path> s = Files.list(cl)) {
                for (Path p : s.sorted().collect(Collectors.toList())) {
                    String n = p.getFileName().toString();
                    if (!Files.isDirectory(p) || n.startsWith(".")) continue;
                    int c = children(p);
                    f.put("layer:" + n, fact("layer", n, c + (c == 1 ? " entry" : " entries"), "count", (long) c));
                }
            }
        }
        long hooks = 0;
        if (Files.isRegularFile(root.resolve(".claude/settings.json")))
            hooks = map(get(readJson(".claude/settings.json"), "hooks")) == null ? 0 : map(get(readJson(".claude/settings.json"), "hooks")).size();
        f.put("layer:settings", fact("layer", "settings.json", hooks + " hook events", "count", hooks));
        long servers = 0;
        if (Files.isRegularFile(root.resolve(".mcp.json"))) {
            Map<String, Object> srv = map(get(readJson(".mcp.json"), "mcpServers"));
            servers = srv == null ? 0 : srv.size();
        }
        f.put("layer:mcp", fact("layer", ".mcp.json", servers + (servers == 1 ? " server" : " servers"), "count", servers));

        // skill classes and skills
        Map<String, String> skillClass = new TreeMap<>();
        Map<String, Object> sc = map(get(ext, "skill_classes", "classes"));
        long order = 0;
        if (sc != null) for (var e : sc.entrySet()) {
            Map<String, Object> c = map(e.getValue());
            List<Object> sk = list(c.get("skills"));
            List<Object> members = new ArrayList<>();
            if (sk != null) for (Object o : sk) { members.add("skill:" + o); skillClass.put((String) o, e.getKey()); }
            List<Object> wa = list(c.get("write_allow"));
            Map<String, Object> fc = fact("skillclass", e.getKey(), territory(wa), "write_allow", wa == null ? new ArrayList<>() : wa);
            fc.put("members", members);
            fc.put("summary", members.size() + (members.size() == 1 ? " skill" : " skills"));
            fc.put("order", order++);
            fc.put("design_phase", Boolean.TRUE.equals(c.get("design_phase")));
            fc.put("blocked_during_design", Boolean.TRUE.equals(c.get("blocked_during_design")));
            f.put("class:" + e.getKey(), fc);
        }
        Set<String> included = new TreeSet<>();
        List<Object> inc = list(get(ext, "export", "skills", "include"));
        if (inc != null) for (Object o : inc) included.add((String) o);
        Path sd = root.resolve(".claude/skills");
        if (Files.isDirectory(sd)) {
            try (Stream<Path> s = Files.list(sd)) {
                for (Path p : s.sorted().collect(Collectors.toList())) {
                    String n = p.getFileName().toString();
                    if (!Files.isRegularFile(p.resolve("SKILL.md"))) continue;
                    Map<String, Object> fs = fact("skill", n, skillClass.getOrDefault(n, "unclassified"), "class", skillClass.getOrDefault(n, "unclassified"));
                    fs.put("exported", included.contains(n));
                    fs.put("context", orElse(frontmatter(p.resolve("SKILL.md"), "context"), "inline"));
                    fs.put("model", frontmatter(p.resolve("SKILL.md"), "model"));
                    fs.put("write_allow", effective(sc, skillClass.get(n), n));
                    f.put("skill:" + n, fs);
                }
            }
        }

        // agent classes and agents
        Map<String, String> agentClass = new TreeMap<>();
        Map<String, Object> ac = map(get(ext, "agent_classes", "classes"));
        order = 0;
        if (ac != null) for (var e : ac.entrySet()) {
            Map<String, Object> c = map(e.getValue());
            List<Object> ag = list(c.get("agents"));
            List<Object> members = new ArrayList<>();
            if (ag != null) for (Object o : ag) { members.add("agent:" + o); agentClass.put((String) o, e.getKey()); }
            List<Object> wa = list(c.get("write_allow"));
            Map<String, Object> fc = fact("agentclass", e.getKey(), territory(wa), "write_allow", wa == null ? new ArrayList<>() : wa);
            fc.put("members", members);
            fc.put("summary", members.size() + (members.size() == 1 ? " agent" : " agents"));
            fc.put("order", order++);
            fc.put("executor", Boolean.TRUE.equals(c.get("executor")));
            f.put("agentclass:" + e.getKey(), fc);
        }
        Path ad = root.resolve(".claude/agents");
        if (Files.isDirectory(ad)) {
            try (Stream<Path> s = Files.list(ad)) {
                for (Path p : s.sorted().collect(Collectors.toList())) {
                    String n = p.getFileName().toString();
                    if (!n.endsWith(".md")) continue;
                    n = n.substring(0, n.length() - 3);
                    Map<String, Object> fa = fact("agent", n, agentClass.getOrDefault(n, "unclassified"), "class", agentClass.getOrDefault(n, "unclassified"));
                    fa.put("model", frontmatter(p, "model"));
                    fa.put("executor", ac != null && agentClass.get(n) != null && Boolean.TRUE.equals(get(ac, agentClass.get(n), "executor")));
                    fa.put("write_allow", effective(ac, agentClass.get(n), n));
                    f.put("agent:" + n, fa);
                }
            }
        }

        extractCmds(f);
        extractSteps(f, ext);
        return f;
    }

    static String orElse(String v, String d) { return v.isEmpty() ? d : v; }

    /** One scalar of a file's frontmatter, empty when the file declares none. */
    static String frontmatter(Path file, String key) throws IOException {
        List<String> ls = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (ls.isEmpty() || !ls.get(0).trim().equals("---")) return "";
        for (int k = 1; k < ls.size(); k++) {
            String l = ls.get(k);
            if (l.trim().equals("---")) break;
            if (l.startsWith(key + ":")) return l.substring(key.length() + 1).trim().replaceAll("^[\"']|[\"']$", "");
        }
        return "";
    }

    /** The territory a piece really has: its override in the class when there is one, else the class's. */
    static List<Object> effective(Map<String, Object> classes, String cls, String name) {
        Map<String, Object> c = classes == null || cls == null ? null : map(classes.get(cls));
        if (c == null) return new ArrayList<>();
        List<Object> ov = list(get(c, "overrides", name, "write_allow"));
        List<Object> wa = ov != null ? ov : list(c.get("write_allow"));
        return wa == null ? new ArrayList<>() : wa;
    }

    static int children(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> {
                String n = p.getFileName().toString();
                return !n.startsWith(".") && !n.startsWith("_");
            }).count();
        }
    }

    static Map<String, Object> fact(String kind, String label, String detail, String extraKey, Object extra) {
        Map<String, Object> m = new TreeMap<>();
        m.put("kind", kind);
        m.put("label", label);
        m.put("detail", detail);
        m.put(extraKey, extra);
        return m;
    }

    static String territory(List<Object> wa) {
        if (wa == null || wa.isEmpty()) return "writes nothing in the repository";
        return wa.stream().map(String::valueOf).collect(Collectors.joining("  "));
    }

    // The step contract (references/step-contract.md): every skill a view names carries steps —
    // `### <id> · <Name>` headings under `## Procedure`, and `## `/`### ` headings elsewhere that
    // a `**Runs:**` line follows — each with a field block (Runs, Then runs, Stops when, Skips when, Hands over).
    static final Pattern STEP_H = Pattern.compile("^(#{2,3}) ([0-9]+[a-z]?|[a-z][a-z-]*) · (.+?)\\s*$");
    static final List<String> FIELDS = List.of("**Runs:**", "**Then runs:**", "**Stops when:**", "**Skips when:**", "**Hands over:**");
    static final Pattern CASE = Pattern.compile("^(\\s*)case ((?:\"[^\"]+\"\\s*,?\\s*)+)->.*$");
    static final String STEP_ID = "(?:[0-9]+[a-z]?|[a-z][a-z-]*)";
    static final String CONTRACT = "(references/step-contract.md)";

    /** Skills whose steps are parsed: the union of every view's `skill` field. */
    static Set<String> viewSkills() throws IOException {
        Set<String> out = new TreeSet<>();
        Path d = root.resolve(OUT);
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.sorted().collect(Collectors.toList())) {
                Path vj = p.resolve("view.json");
                if (!Files.isRegularFile(vj)) continue;
                try {
                    if (get(Json.parse(Files.readString(vj, StandardCharsets.UTF_8)), "skill") instanceof String sk) out.add(sk);
                } catch (IllegalArgumentException e) { /* `validate` reports the view; the facts do not need it */ }
            }
        }
        return out;
    }

    /** The gate vocabulary — view.schema.json `$defs.gate.enum`, never a constant here. */
    static List<String> gateWords() throws IOException {
        List<Object> en = list(get(readJson(SCHEMA), "$defs", "gate", "enum"));
        if (en == null || en.isEmpty()) throw new Fail(SCHEMA + " has no $defs.gate.enum — the gate vocabulary of the step contract lives there");
        return en.stream().map(String::valueOf).collect(Collectors.toList());
    }

    /** The gate words that branch — view.schema.json `$defs.branchGate.enum`: each takes one **Stops when:** line. */
    static List<String> branchGateWords() {
        try {
            List<Object> en = list(get(readJson(SCHEMA), "$defs", "branchGate", "enum"));
            if (en == null || en.isEmpty()) throw new Fail(SCHEMA + " has no $defs.branchGate.enum — the gate words that branch live there");
            return en.stream().map(String::valueOf).collect(Collectors.toList());
        } catch (IOException e) { throw new Fail("cannot read " + SCHEMA + ": " + e.getMessage()); }
    }

    /** `cmd:<mode>` — every mode of ArchHook.java's dispatch, read from its layout: the `switch (mode) {` right after `try {`. */
    static void extractCmds(Map<String, Object> f) throws IOException {
        Path p = root.resolve(HOOK);
        if (!Files.isRegularFile(p)) throw new Fail(HOOK + " is missing — the `cmd:*` facts are the modes of its dispatch");
        List<String> ls = Files.readAllLines(p, StandardCharsets.UTF_8);
        int sw = -1;
        String prev = "";
        for (int n = 0; n < ls.size() && sw < 0; n++) {
            String t = ls.get(n).trim();
            if (t.equals("switch (mode) {") && prev.equals("try {")) sw = n;
            if (!t.isEmpty()) prev = t;
        }
        int swIndent = sw < 0 ? 0 : indent(ls.get(sw));
        int caseIndent = -1;
        int found = 0;
        for (int n = sw + 1; sw >= 0 && n < ls.size(); n++) {
            String l = ls.get(n);
            if (l.isBlank()) continue;
            if (indent(l) == swIndent && l.trim().startsWith("}")) break;
            Matcher m = CASE.matcher(l);
            if (!m.matches()) continue;
            if (caseIndent < 0) caseIndent = indent(l);
            if (indent(l) != caseIndent) continue;
            Matcher q = Pattern.compile("\"([^\"]+)\"").matcher(m.group(2));
            while (q.find()) {
                f.put("cmd:" + q.group(1), fact("cmd", "ArchHook " + q.group(1), "", "mode", q.group(1)));
                found++;
            }
        }
        if (found == 0)
            throw new Fail(HOOK + " — no `case \"<mode>\"` found in the first line `switch (mode) {` that follows a line `try {`; the `cmd:*` facts are read from that layout."
                    + "\n   Fix the dispatch, or the rule in " + SKILL + "/scripts/Diagrams.java (extractCmds) and " + SKILL + "/references/step-contract.md in the same change.");
    }

    static int indent(String l) {
        int k = 0;
        while (k < l.length() && l.charAt(k) == ' ') k++;
        return k;
    }

    /** `step:<skill>:<id>` and `handoff:<skill>` for every skill a view names, per the step contract. */
    static void extractSteps(Map<String, Object> f, Map<String, Object> ext) throws IOException {
        List<String> gates = gateWords();
        List<String> branchWords = branchGateWords();
        Map<String, Object> schema = readJson(SCHEMA);
        String alt = "(?:" + String.join("|", gates) + ")";
        String tail = "(?:skill|agent|cmd):[A-Za-z0-9._-]+";
        String ref = "(?:self|" + tail + ")(?:>" + tail + ")*\\??";
        String phase = ref + "(?:, " + ref + ")*";
        String ids = STEP_ID + "(?:, " + STEP_ID + ")*";
        Pattern runs = Pattern.compile("^\\*\\*Runs:\\*\\* (" + phase + ")(?: · \\*\\*Depends on:\\*\\* (" + ids + "))?(?: · \\*\\*Reads:\\*\\* (" + ids + "))?( · \\*\\*Conditional:\\*\\* yes)?"
                + "(?: · \\*\\*Gate:\\*\\* (" + alt + "(?:, " + alt + ")*))?(?: · \\*\\*Note:\\*\\* (.+?))?\\s*$");
        Pattern then = Pattern.compile("^\\*\\*Then runs:\\*\\* (" + phase + ")(?: · \\*\\*Note:\\*\\* (.+?))?\\s*$");
        Pattern stops = Pattern.compile("^\\*\\*Stops when:\\*\\* (.+?) · \\*\\*Says:\\*\\* (.+?)\\s*$");
        Pattern skips = Pattern.compile("^\\*\\*Skips when:\\*\\* (.+?) · \\*\\*To:\\*\\* (" + STEP_ID + ") · \\*\\*Says:\\*\\* (.+?)\\s*$");
        Pattern hands = Pattern.compile("^\\*\\*Hands over:\\*\\* (`[^`]+`(?:, `[^`]+`)*)\\s*$");
        List<Object> nat = list(get(ext, "types", "skill", "native_commands"));
        for (String sk : viewSkills()) {
            String rel = ".claude/skills/" + sk + "/SKILL.md";
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p)) throw new Fail(rel + " is missing — a view under " + OUT + "/ names this skill, and its steps are parsed from it");
            List<String> ls = Files.readAllLines(p, StandardCharsets.UTF_8);
            boolean[] fenced = new boolean[ls.size()];
            boolean fence = false;
            for (int n = 0; n < ls.size(); n++) {
                if (ls.get(n).trim().startsWith("```")) { fence = !fence; fenced[n] = true; continue; }
                fenced[n] = fence;
            }
            int start = -1, end = ls.size();
            for (int n = 0; n < ls.size(); n++) {
                if (fenced[n]) continue;
                String l = ls.get(n);
                if (start < 0) { if (l.startsWith("## Procedure")) start = n + 1; }
                else if (l.startsWith("## ")) { end = n; break; }
            }
            if (start < 0) throw new Fail(rel + " — no `## Procedure` heading; the steps of " + sk + " are read from it " + CONTRACT);
            List<Map<String, Object>> steps = new ArrayList<>();
            List<List<Integer>> blocks = new ArrayList<>();
            List<Integer> headAt = new ArrayList<>();
            Set<Integer> consumed = new HashSet<>();
            Map<String, Object> hand = null;
            for (int n = 0; n < ls.size(); n++) {
                if (fenced[n]) continue;
                String l = ls.get(n);
                boolean inScope = n >= start && n < end;
                if (inScope && l.startsWith("### Step "))
                    throw new Fail(rel + ":" + (n + 1) + " — '### Step <id>: …' is the retired grammar; write '### <id> · <Name>' and move 'depends on' into the **Runs:** line " + CONTRACT);
                if (!(l.startsWith("## ") || l.startsWith("### ")) || !l.contains(" · ")) continue;
                int r = nextLine(ls, n + 1, ls.size());
                boolean hasRuns = r >= 0 && ls.get(r).startsWith("**Runs:**");
                if (!(inScope && l.startsWith("### ")) && !hasRuns) continue; // outside the Procedure a heading is a step only when a Runs line follows it
                Matcher h = STEP_H.matcher(l);
                if (!h.matches())
                    throw new Fail(rel + ":" + (n + 1) + " — step heading does not match '### <id> · <Name>' (id: digits and one optional letter, or lowercase words joined by hyphens): " + l + "\n   " + CONTRACT);
                String id = h.group(2), name = h.group(3).trim();
                if (!hasRuns) throw new Fail(rel + ":" + (n + 1) + " — step " + id + " has no **Runs:** line under '" + l.trim() + "' " + CONTRACT);
                List<Integer> blk = new ArrayList<>();
                for (int k = n + 1; ; k++) {
                    k = nextLine(ls, k, ls.size());
                    if (k < 0 || fenced[k] || fieldOf(ls.get(k)) == null) break;
                    blk.add(k);
                }
                consumed.addAll(blk);
                Matcher m = runs.matcher(ls.get(r));
                if (!m.matches())
                    throw new Fail(rel + ":" + (r + 1) + " — step " + id + ": **Runs:** does not match '**Runs:** <phase> [· **Depends on:** <ids>] [· **Reads:** <ids>] [· **Conditional:** yes] [· **Gate:** <" + String.join("|", gates) + ">[, …]] [· **Note:** <note>]': " + ls.get(r) + "\n   " + CONTRACT);
                List<Object> phases = new ArrayList<>();
                List<Object> flat = new ArrayList<>();
                phases.add(phaseOf(m.group(1), m.group(6), sk, id, f, rel, r + 1, flat));
                List<Object> brs = new ArrayList<>();
                List<Integer> brLine = new ArrayList<>();
                List<Integer> phLine = new ArrayList<>();
                phLine.add(r + 1);
                for (int bi = 1; bi < blk.size(); bi++) {
                    int li = blk.get(bi);
                    String text = ls.get(li);
                    String fld = fieldOf(text);
                    if (fld.equals("**Runs:**")) throw new Fail(rel + ":" + (li + 1) + " — step " + id + " has a second **Runs:** line; the following phases are **Then runs:** lines " + CONTRACT);
                    if (fld.equals("**Then runs:**")) {
                        Matcher tm = then.matcher(text);
                        if (!tm.matches()) throw new Fail(rel + ":" + (li + 1) + " — step " + id + ": **Then runs:** does not match '**Then runs:** <phase> [· **Note:** <note>]': " + text + "\n   " + CONTRACT);
                        phases.add(phaseOf(tm.group(1), tm.group(2), sk, id, f, rel, li + 1, flat));
                        phLine.add(li + 1);
                    } else if (fld.equals("**Stops when:**") || fld.equals("**Skips when:**")) {
                        boolean skip = fld.equals("**Skips when:**");
                        Matcher bm = (skip ? skips : stops).matcher(text);
                        if (!bm.matches()) throw new Fail(rel + ":" + (li + 1) + " — step " + id + ": " + fld + " does not match '" + (skip ? "**Skips when:** <condition> · **To:** <id> · **Says:** <line>[; <line>]" : "**Stops when:** <condition> · **Says:** <line>[; <line>]") + "': " + text + "\n   " + CONTRACT);
                        String cond = bm.group(1), says = bm.group(skip ? 3 : 2);
                        limit(schema, "branchCondition", cond, rel, li + 1, id, fld);
                        List<Object> parts = new ArrayList<>(Arrays.asList(says.split("; ")));
                        for (Object part : parts) limit(schema, "branchLine", (String) part, rel, li + 1, id, fld);
                        if (parts.size() > 2) throw new Fail(rel + ":" + (li + 1) + " — step " + id + " " + fld + " **Says:** has " + parts.size() + " parts. Fix: " + get(schema, "$defs", "branchLine", "x-fix") + " " + CONTRACT);
                        Map<String, Object> br = new TreeMap<>();
                        br.put("kind", skip ? "skip" : "stop");
                        br.put("after_phase", (long) (phases.size() - 1));
                        br.put("condition", cond);
                        br.put("says", parts);
                        br.put("to", skip ? bm.group(2) : "");
                        brs.add(br);
                        brLine.add(li + 1);
                    } else { // **Hands over:**
                        if (bi != blk.size() - 1) throw new Fail(rel + ":" + (li + 1) + " — **Hands over:** must be the last line of the field block " + CONTRACT);
                        Matcher hm = hands.matcher(text);
                        if (!hm.matches())
                            throw new Fail(rel + ":" + (li + 1) + " — **Hands over:** does not match '**Hands over:** `<cmd>`[, `<cmd>`]': " + text + "\n   " + CONTRACT);
                        if (hand != null) throw new Fail(rel + ":" + (li + 1) + " — a second **Hands over:** line; a skill has at most one " + CONTRACT);
                        List<Object> cmds = new ArrayList<>();
                        for (String c : hm.group(1).split(", ")) {
                            String lit = c.substring(1, c.length() - 1);
                            if (lit.startsWith("/")) {
                                String nm = lit.substring(1).split("\\s+")[0];
                                if (!f.containsKey("skill:" + nm) && (nat == null || !nat.contains(nm)))
                                    throw new Fail(rel + ":" + (li + 1) + " — '" + lit + "' is no skill or native command (a directory of .claude/skills/, or an entry of types.skill.native_commands in " + EXT + ") " + CONTRACT);
                            }
                            cmds.add(lit);
                        }
                        hand = new TreeMap<>();
                        hand.put("kind", "handoff");
                        hand.put("skill", sk);
                        hand.put("label", "Hands over");
                        hand.put("detail", "");
                        hand.put("commands", cmds);
                        hand.put("after", "step:" + sk + ":" + id);
                    }
                }
                List<String> deps = m.group(2) == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(m.group(2).split(", ")));
                List<String> reads = m.group(3) == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(m.group(3).split(", ")));
                List<Object> gs = new ArrayList<>();
                if (m.group(5) != null) gs.addAll(Arrays.asList(m.group(5).split(", ")));
                Map<String, Object> st = stepFact(sk, id, name, deps, reads, m.group(4) != null, steps.size());
                st.put("runs", flat);
                st.put("gates", gs);
                st.put("phases", phases);
                st.put("branches", brs);
                steps.add(st);
                blocks.add(brLine);
                headAt.add(r + 1);
                st.put("_phaseLines", phLine);
            }
            for (int n = 0; n < ls.size(); n++)
                if (!fenced[n] && !consumed.contains(n) && fieldOf(ls.get(n)) != null)
                    throw new Fail(rel + ":" + (n + 1) + " — " + fieldOf(ls.get(n)) + " must be in the field block right under a step heading " + CONTRACT);
            if (steps.isEmpty())
                throw new Fail(rel + " — no '### <id> · <Name>' step heading with a **Runs:** line; a view of " + sk + " has nothing to draw " + CONTRACT);
            Map<String, Integer> order = new HashMap<>();
            for (var s : steps) order.put((String) s.get("id"), ((Number) s.get("order")).intValue());
            for (int k = 0; k < steps.size(); k++) {
                Map<String, Object> s = steps.get(k);
                String sid = (String) s.get("id");
                int at = headAt.get(k);
                for (String key : new String[] { "depends_on", "reads" })
                    for (Object d : list(s.get(key))) {
                        String did = ((String) d).substring(("step:" + sk + ":").length());
                        String verb = key.equals("reads") ? "reads" : "depends on";
                        if (!order.containsKey(did))
                            throw new Fail(rel + ":" + at + " — step " + sid + " " + verb + " '" + did + "', which is no step heading " + CONTRACT);
                        if (order.get(did) >= k)
                            throw new Fail(rel + ":" + at + " — step " + sid + " " + verb + " '" + did + "', which is written after it — the written order is the execution order " + CONTRACT);
                    }
                // the branch gate words and the Stops when lines pair one to one, in order
                List<String> words = new ArrayList<>();
                for (Object g : list(s.get("gates"))) if (branchWords.contains(g)) words.add((String) g);
                List<Object> brs = list(s.get("branches"));
                List<Integer> bl = blocks.get(k);
                long nStops = brs.stream().filter(b -> !"skip".equals(map(b).get("kind"))).count();
                if (words.size() != nStops)
                    throw new Fail(rel + ":" + at + " — step " + sid + " has " + words.size() + " branch gate word(s) (" + String.join(", ", words) + ") and " + nStops + " **Stops when:** line(s) " + CONTRACT);
                int wi = 0;
                for (int bi = 0; bi < brs.size(); bi++) {
                    Map<String, Object> b = map(brs.get(bi));
                    if ("skip".equals(b.get("kind"))) {
                        String to = (String) b.get("to");
                        if (!order.containsKey(to) || order.get(to) <= k)
                            throw new Fail(rel + ":" + bl.get(bi) + " — step " + sid + " skips to '" + to + "', which is no later step " + CONTRACT);
                        b.put("to", "step:" + sk + ":" + to);
                    } else b.put("kind", words.get(wi++));
                }
                List<Object> phases = list(s.get("phases"));
                @SuppressWarnings("unchecked") List<Integer> phLine = (List<Integer>) s.remove("_phaseLines");
                for (int pi = 0; pi < phases.size(); pi++) {
                    String note = (String) map(phases.get(pi)).get("note");
                    if (note.isEmpty()) continue;
                    if (phases.size() == 1) throw new Fail(rel + ":" + phLine.get(pi) + " — a note names a phase; step " + sid + " has one " + CONTRACT);
                    limit(schema, "phaseNote", note, rel, phLine.get(pi), sid, "**Note:**");
                }
            }
            for (var s : steps) f.put("step:" + sk + ":" + s.get("id"), s);
            if (hand != null) f.put("handoff:" + sk, hand);
        }
    }

    /** The field a line opens, or null. */
    static String fieldOf(String l) {
        for (String fld : FIELDS) if (l.startsWith(fld)) return fld;
        return null;
    }

    /** One phase: its chains (`ref(>ref)*` with an optional `?`), every segment resolved to a fact. */
    static Map<String, Object> phaseOf(String text, String note, String sk, String id, Map<String, Object> f, String rel, int line, List<Object> flat) {
        List<Object> chains = new ArrayList<>();
        for (String c : text.split(", ")) {
            boolean cond = c.endsWith("?");
            if (cond) c = c.substring(0, c.length() - 1);
            List<Object> chain = new ArrayList<>();
            for (String seg : c.split(">")) {
                String fid = seg.equals("self") ? "skill:" + sk : seg;
                if (!f.containsKey(fid))
                    throw new Fail(rel + ":" + line + " — step " + id + " runs '" + seg + "', which is no fact (nearby: " + nearby(fid, f) + ") " + CONTRACT);
                chain.add(fid);
            }
            Map<String, Object> ch = new TreeMap<>();
            ch.put("refs", chain);
            ch.put("conditional", cond);
            chains.add(ch);
            flat.add(chain);
        }
        Map<String, Object> ph = new TreeMap<>();
        ph.put("chains", chains);
        ph.put("note", note == null ? "" : note);
        return ph;
    }

    /** A text a branch or a phase prints, held to the limit its `$defs` entry of view.schema.json states; that entry's x-fix is the message. */
    static void limit(Map<String, Object> schema, String def, String value, String rel, int line, String id, String field) {
        List<String> out = new ArrayList<>();
        sv(schema, map(get(schema, "$defs", def)), value, "$", out);
        if (!out.isEmpty()) throw new Fail(rel + ":" + line + " — step " + id + " " + field + " " + out.get(0) + "\n   " + CONTRACT);
    }

    static int nextLine(List<String> ls, int from, int end) {
        for (int k = from; k < end; k++) if (!ls.get(k).isBlank()) return k;
        return -1;
    }

    static String nearby(String fid, Map<String, Object> f) {
        String kind = fid.substring(0, fid.indexOf(':') + 1);
        return f.keySet().stream().filter(k -> k.startsWith(kind)).limit(6).collect(Collectors.joining(", "));
    }

    static Map<String, Object> stepFact(String skill, String id, String name, List<String> deps, List<String> reads, boolean cond, int order) {
        Map<String, Object> m = new TreeMap<>();
        m.put("kind", "step");
        m.put("skill", skill);
        m.put("id", id);
        m.put("name", name);
        m.put("label", id.matches("[0-9]+[a-z]?") ? id + " · " + name : name);
        m.put("detail", cond ? "conditional" : "");
        m.put("conditional", cond);
        m.put("order", (long) order);
        List<Object> d = new ArrayList<>();
        for (String x : deps) d.add("step:" + skill + ":" + x);
        m.put("depends_on", d);
        List<Object> rd = new ArrayList<>();
        for (String x : reads) rd.add("step:" + skill + ":" + x);
        m.put("reads", rd);
        return m;
    }

    static Map<String, Object> factsDoc(Map<String, Object> facts) {
        Map<String, Object> doc = new TreeMap<>();
        doc.put("schema", 1L);
        doc.put("facts", facts);
        return doc;
    }

    // ───────────────────────────── identity ─────────────────────────────

    static final class Identity {
        Map<String, Object> m;

        static Identity load() throws IOException {
            String rel = SKILL + "/references/visual-identity.md";
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p)) throw new Fail(rel + " is missing");
            String t = norm(Files.readString(p, StandardCharsets.UTF_8));
            int a = t.indexOf("```json\n");
            int b = a < 0 ? -1 : t.indexOf("\n```", a + 8);
            if (a < 0 || b < 0) throw new Fail(rel + " has no ```json block");
            Identity i = new Identity();
            try { i.m = map(Json.parse(t.substring(a + 8, b))); }
            catch (IllegalArgumentException e) { throw new Fail(rel + " — the json block is invalid: " + e.getMessage()); }
            return i;
        }

        int i(String... path) {
            Object o = get(m, path);
            if (!(o instanceof Number n)) throw new Fail("visual-identity.md json block lacks the number " + String.join(".", path));
            return n.intValue();
        }

        double d(String... path) {
            Object o = get(m, path);
            if (!(o instanceof Number n)) throw new Fail("visual-identity.md json block lacks the number " + String.join(".", path));
            return n.doubleValue();
        }

        String s(String... path) {
            Object o = get(m, path);
            if (!(o instanceof String t)) throw new Fail("visual-identity.md json block lacks the string " + String.join(".", path));
            return t;
        }

        String role(String id, Map<String, Object> fact) {
            Object r = get(m, "roles", id);
            if (r == null && fact != null) r = get(m, "roles", String.valueOf(fact.get("kind")));
            return r instanceof String t ? t : "neutral";
        }

        String fill(String role) { return s("palette", role, "fill"); }

        String stroke(String role) { return s("palette", role, "stroke"); }
    }

    // ───────────────────────────── view: schema ─────────────────────────────

    static void sv(Map<String, Object> top, Map<String, Object> s, Object v, String path, List<String> out) {
        String ref = (String) s.get("$ref");
        if (ref != null) {
            Map<String, Object> t = map(get(top, "$defs", ref.substring("#/$defs/".length())));
            sv(top, t, v, path, out);
            return;
        }
        String fix = s.get("x-fix") instanceof String x ? " Fix: " + x + "." : "";
        Object type = s.get("type");
        if (type != null && !typeOk((String) type, v)) {
            out.add(path + ": expected " + type + "." + fix);
            return;
        }
        List<Object> en = list(s.get("enum"));
        if (en != null && !en.contains(v)) { out.add(path + ": '" + v + "' is not one of " + en + "." + fix); return; }
        if (v instanceof String t) {
            if (s.get("minLength") instanceof Number n && t.length() < n.intValue()) out.add(path + ": must not be empty." + fix);
            if (s.get("pattern") instanceof String p && !Pattern.compile(p).matcher(t).find())
                out.add(path + ": '" + t + "' does not match " + p + "." + fix);
        }
        if (v instanceof Map<?, ?>) {
            Map<String, Object> o = map(v);
            Map<String, Object> props = map(s.get("properties"));
            List<Object> req = list(s.get("required"));
            if (req != null) for (Object r : req) if (!o.containsKey(r)) out.add(path + ": missing required property '" + r + "'." + fix);
            if (Boolean.FALSE.equals(s.get("additionalProperties")))
                for (String k : o.keySet()) if (props == null || !props.containsKey(k))
                    out.add(path + ": unknown property '" + k + "'." + fix);
            if (props != null) for (var e : props.entrySet()) if (o.containsKey(e.getKey()))
                sv(top, map(e.getValue()), o.get(e.getKey()), path + "." + e.getKey(), out);
        }
        if (v instanceof List<?>) {
            List<Object> l = list(v);
            if (s.get("minItems") instanceof Number n && l.size() < n.intValue()) out.add(path + ": needs at least " + n + " item(s)." + fix);
            Map<String, Object> it = map(s.get("items"));
            if (it != null) for (int k = 0; k < l.size(); k++) sv(top, it, l.get(k), path + "[" + k + "]", out);
        }
        List<Object> one = list(s.get("oneOf"));
        if (one != null) {
            int ok = 0;
            for (Object sub : one) { List<String> tmp = new ArrayList<>(); sv(top, map(sub), v, path, tmp); if (tmp.isEmpty()) ok++; }
            if (ok != 1) out.add(path + (ok == 0 ? ": matches none of the allowed shapes." : ": matches several of the allowed shapes.") + fix);
        }
    }

    static boolean typeOk(String t, Object v) {
        return switch (t) {
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Long;
            default -> true;
        };
    }

    // ───────────────────────────── view: resolve ─────────────────────────────

    static final class RGroup {
        String id, labelFact, labelCaption;
        List<String> nodes = new ArrayList<>();
    }

    static final class REdge {
        String from, to, evidence;
        String id() { return from + ">" + to; }
    }

    static final class Resolved {
        String name, kind, title, caption, skill, derive;
        List<RGroup> groups = new ArrayList<>();
        List<REdge> edges = new ArrayList<>();
        List<String> steps = new ArrayList<>(), emphasis = new ArrayList<>();
    }

    static Resolved resolve(String name, Map<String, Object> facts, List<String> errs) throws IOException {
        String rel = OUT + "/" + name + "/view.json";
        Path p = root.resolve(rel);
        if (!Files.isRegularFile(p)) { errs.add(rel + ": missing."); return null; }
        Map<String, Object> v;
        try { v = map(Json.parse(Files.readString(p, StandardCharsets.UTF_8))); }
        catch (IllegalArgumentException e) { errs.add(rel + ": invalid JSON — " + e.getMessage() + "."); return null; }
        if (v == null) { errs.add(rel + ": must be a JSON object."); return null; }
        Map<String, Object> schema = readJson(SCHEMA);
        List<String> se = new ArrayList<>();
        sv(schema, schema, v, "$", se);
        if (!se.isEmpty()) { for (String e : se) errs.add(rel + " " + e); return null; }

        int before = errs.size();
        Resolved r = new Resolved();
        r.name = name;
        r.kind = (String) v.get("kind");
        r.title = (String) v.get("title");
        r.caption = v.get("caption") instanceof String c ? c : "";
        r.skill = v.get("skill") instanceof String sk ? sk : null;
        boolean stepped = r.kind.equals("orchestrator");
        if (stepped) {
            if (r.skill == null) {
                errs.add(rel + ": a " + r.kind + " view requires skill. Fix: add \"skill\": \"<name>\", the skill whose steps it draws (it carries the step contract, references/step-contract.md).");
                return null;
            }
            if (!facts.containsKey("skill:" + r.skill)) { errs.add(rel + " $.skill: " + missing("skill:" + r.skill, facts)); return null; }
            if (facts.keySet().stream().noneMatch(k -> k.startsWith("step:" + r.skill + ":"))) {
                errs.add(rel + " $.skill: '" + r.skill + "' has no step facts. Fix: its SKILL.md must carry the step contract (references/step-contract.md).");
                return null;
            }
        }
        Set<String> nodeIds = new LinkedHashSet<>(), groupIds = new LinkedHashSet<>();
        List<Object> gs = list(v.get("groups"));
        for (int gi = 0; gi < gs.size(); gi++) {
            Map<String, Object> g = map(gs.get(gi));
            RGroup rg = new RGroup();
            rg.id = (String) g.get("id");
            String gp = rel + " $.groups[" + gi + "]";
            if (!groupIds.add(rg.id)) errs.add(gp + ": group id '" + rg.id + "' is used twice. Fix: give each group its own id.");
            Map<String, Object> lab = map(g.get("label"));
            rg.labelFact = (String) lab.get("fact");
            rg.labelCaption = (String) lab.get("caption");
            if (rg.labelFact != null && !facts.containsKey(rg.labelFact)) errs.add(gp + ".label: " + missing(rg.labelFact, facts));
            List<Object> ns = list(g.get("nodes"));
            for (int ni = 0; ni < ns.size(); ni++) {
                Map<String, Object> n = map(ns.get(ni));
                String np = gp + ".nodes[" + ni + "]";
                List<String> ids;
                if (n.get("fact") instanceof String f) {
                    if (!facts.containsKey(f)) { errs.add(np + ": " + missing(f, facts)); continue; }
                    ids = List.of(f);
                } else ids = expand((String) n.get("from"), facts, np, errs);
                for (String id : ids) {
                    if (!nodeIds.add(id)) errs.add(np + ": node '" + id + "' appears twice in the view. Fix: list each fact once.");
                    else rg.nodes.add(id);
                }
            }
            r.groups.add(rg);
        }
        List<Object> es = list(v.get("edges"));
        if (es != null) for (int ei = 0; ei < es.size(); ei++) {
            Map<String, Object> e = map(es.get(ei));
            String ep = rel + " $.edges[" + ei + "]";
            if (e.containsKey("derive")) {
                if (!stepped) errs.add(ep + ": a " + r.kind + " view has no steps to derive edges from. Fix: list edges as {\"from\",\"to\",\"evidence\"}.");
                else r.derive = (String) e.get("derive");
                continue;
            }
            if (stepped) { errs.add(ep + ": an orchestrator view takes {\"derive\":\"sequence\"} or {\"derive\":\"depends_on\"} only; the steps' own field lines say the rest."); continue; }
            REdge re = new REdge();
            re.from = (String) e.get("from");
            re.to = (String) e.get("to");
            re.evidence = (String) e.get("evidence");
            for (String end : new String[] { re.from, re.to })
                if (!nodeIds.contains(end) && !groupIds.contains(end))
                    errs.add(ep + ": '" + end + "' is neither a node nor a group of the view. Fix: use a fact id of a node or a group id.");
            if (re.evidence != null && !facts.containsKey(re.evidence)) errs.add(ep + ".evidence: " + missing(re.evidence, facts));
            r.edges.add(re);
        }
        if (stepped && r.derive == null)
            errs.add(rel + " $.edges: an orchestrator view needs {\"derive\":\"sequence\"} (one column, the chips of who runs each step beside it) or {\"derive\":\"depends_on\"} (rows, the dependencies drawn).");
        if (stepped && v.containsKey("steps"))
            errs.add(rel + " $.steps: the animation follows the written order of " + r.skill + "; remove steps.");
        if (stepped && v.containsKey("emphasis"))
            errs.add(rel + " $.emphasis: an orchestrator view takes no emphasis — the badges and the diamonds say it; remove emphasis.");
        if (!stepped) {
            Set<String> edgeIds = new LinkedHashSet<>();
            for (REdge e : r.edges) edgeIds.add(e.id());
            for (String key : new String[] { "steps", "emphasis" }) {
                List<Object> l = list(v.get(key));
                if (l == null) continue;
                for (int k = 0; k < l.size(); k++) {
                    String id = (String) l.get(k);
                    boolean ok = nodeIds.contains(id) || groupIds.contains(id) || (key.equals("steps") && edgeIds.contains(id));
                    if (!ok) errs.add(rel + " $." + key + "[" + k + "]: '" + id + "' is not a node, group" + (key.equals("steps") ? " or edge" : "") + " of the view. Fix: remove it or cite an id the view has.");
                    (key.equals("steps") ? r.steps : r.emphasis).add(id);
                }
            }
        }
        if (stepped)
            for (String id : nodeIds)
                if (!id.startsWith("step:" + r.skill + ":"))
                    errs.add(rel + ": a " + r.kind + " view draws the steps of " + r.skill + " only; '" + id + "' is a " + map(facts.get(id)).get("kind")
                            + " fact. Fix: select {\"from\":\"step:" + r.skill + ":*\"}.");
        return errs.size() > before ? null : r;
    }

    static String missing(String id, Map<String, Object> facts) {
        String kind = id.contains(":") ? id.substring(0, id.indexOf(':') + 1) : id;
        List<String> near = facts.keySet().stream().filter(k -> k.startsWith(kind)).limit(6).collect(Collectors.toList());
        return "fact '" + id + "' not found — a piece was renamed or removed. Fix: update the view to a current id" + (near.isEmpty() ? "" : " (e.g. " + String.join(", ", near) + ")") + ".";
    }

    static List<String> expand(String sel, Map<String, Object> facts, String where, List<String> errs) {
        List<String> out = new ArrayList<>();
        if (sel.endsWith("*")) {
            String pre = sel.substring(0, sel.length() - 1);
            List<String> ids = facts.keySet().stream().filter(k -> k.startsWith(pre)).collect(Collectors.toList());
            ids.sort(Comparator.comparingLong((String k) -> map(facts.get(k)).get("order") instanceof Number n ? n.longValue() : 0L).thenComparing(k -> k));
            out.addAll(ids);
        } else if (sel.contains(".")) {
            String base = sel.substring(0, sel.indexOf('.')), field = sel.substring(sel.indexOf('.') + 1);
            Map<String, Object> f = map(facts.get(base));
            if (f == null) { errs.add(where + ": selector '" + sel + "': " + missing(base, facts)); return out; }
            List<Object> l = list(f.get(field));
            if (l == null || !l.stream().allMatch(o -> o instanceof String s && facts.containsKey(s))) {
                errs.add(where + ": selector '" + sel + "' does not yield a list of fact ids (field '" + field + "' of " + base + "). Fix: select a list field such as members.");
                return out;
            }
            for (Object o : l) out.add((String) o);
        } else {
            if (!facts.containsKey(sel)) errs.add(where + ": selector '" + sel + "': " + missing(sel, facts));
            else out.add(sel);
            return out;
        }
        if (out.isEmpty()) errs.add(where + ": selector '" + sel + "' matches no fact. Fix: the data it reads is empty or renamed; cite another selector.");
        return out;
    }

    // ───────────────────────────── scene ─────────────────────────────

    static final class Box {
        String id, label, role, fill, stroke, groupId, tool = ""; // tool: the label of the arrow that reaches a chip
        List<String> detail = new ArrayList<>();
        // A multi-line box (orchestrator view): bold `head` lines, then muted `detail` lines,
        // the first `monoN` of them in the monospace family; `mono` sets the head's.
        List<String> head = new ArrayList<>();
        int x, y, w, h, step = -1, rx = -1, monoN;
        boolean lane, dashed, emph, mono;
        String shape = "rect", dash = ""; // rect | diamond | frame (dotted, no fill) | swatch (a legend sample, no text)
        double sw;                        // stroke width; 0 = the identity's
        double t = -1;                    // reveal time in seconds; negative = visible from the start
    }

    /** A line of free-standing text: x is its centre ("middle"), its left edge ("start") or its right edge ("end"), y its baseline. */
    static final class Txt {
        String id, text, anchor;
        int x, y, size, step = -1;
        double t = -1;
    }

    static final class Ln {
        String id, from, to; // from/to: ids of boxes the line is tied to, null when it is not
        List<int[]> pts = new ArrayList<>();
        int step = -1;
        double t = -1;
        boolean dashed;
    }

    static final class Scene {
        int w, h;
        String title, caption;
        List<Box> lanes = new ArrayList<>(), nodes = new ArrayList<>();
        List<Ln> lines = new ArrayList<>();
        List<Txt> texts = new ArrayList<>();
    }

    static List<String> wrap(String t, int max) {
        List<String> out = new ArrayList<>();
        if (t == null || t.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        for (String w : t.trim().split("\\s+")) {
            if (cur.length() > 0 && cur.length() + 1 + w.length() > max) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static int up(int v, int grid) { return (v + grid - 1) / grid * grid; }

    static Scene scene(Resolved r, Map<String, Object> facts, Identity id) {
        Scene sc = new Scene();
        sc.title = r.title;
        sc.caption = r.caption;
        int NW = id.i("node", "w"), NH = id.i("node", "h"), G = id.i("gap"), M = id.i("margin"), TOP = id.i("top"), GRID = id.i("grid");
        Map<String, Box> byId = new LinkedHashMap<>();
        Map<String, Integer> stepOf = new HashMap<>();
        for (int k = 0; k < r.steps.size(); k++) stepOf.putIfAbsent(r.steps.get(k), k);

        if (r.kind.equals("orchestrator")) {
            new Orch(sc, r, facts, id, byId).layout();
        } else {
            int PAD = id.i("lane", "pad"), HW = id.i("lane", "header_w"), COLS = id.i("lane", "cols"), LG = id.i("lane", "gap");
            int most = r.groups.stream().mapToInt(g -> g.nodes.size()).max().orElse(1);
            int cols = Math.max(1, Math.min(COLS, most));
            int laneW = HW + PAD + cols * NW + (cols - 1) * G + PAD;
            int maxChars = (int) ((HW - 2 * PAD) / (id.i("font", "detail") * id.d("font", "char_width")));
            sc.w = laneW + 2 * M;
            int y = TOP;
            for (RGroup g : r.groups) {
                Map<String, Object> lf = g.labelFact == null ? null : map(facts.get(g.labelFact));
                Box lane = new Box();
                lane.id = g.id; lane.lane = true; lane.groupId = g.id;
                lane.label = lf != null ? (String) lf.get("label") : g.labelCaption;
                String det = lf == null ? "" : (r.kind.equals("territory") || !(lf.get("summary") instanceof String)) ? String.valueOf(lf.get("detail")) : (String) lf.get("summary");
                lane.detail = wrap(det, maxChars);
                String first = g.nodes.isEmpty() ? g.labelFact : g.nodes.get(0);
                String fid = lf != null ? g.labelFact : first;
                lane.role = id.role(fid, lf != null ? lf : map(facts.get(first)));
                lane.fill = id.fill(lane.role); lane.stroke = id.stroke(lane.role);
                lane.emph = r.emphasis.contains(g.id);
                int rows = (g.nodes.size() + cols - 1) / cols;
                int nodesH = rows == 0 ? 0 : rows * NH + (rows - 1) * G;
                int headH = PAD + 16 + lane.detail.size() * 15 + PAD;
                int laneH = up(Math.max(2 * PAD + nodesH, headH), GRID);
                lane.x = M; lane.y = y; lane.w = laneW; lane.h = laneH;
                sc.lanes.add(lane);
                byId.put(g.id, lane);
                int off = (laneH - nodesH) / 2;
                for (int k = 0; k < g.nodes.size(); k++) {
                    Box b = nodeBox(g.nodes.get(k), facts, id);
                    b.groupId = g.id;
                    b.x = M + HW + PAD + (k % cols) * (NW + G);
                    b.y = y + off + (k / cols) * (NH + G);
                    b.w = NW; b.h = NH;
                    sc.nodes.add(b);
                    byId.put(b.id, b);
                }
                y += laneH + LG;
            }
            sc.h = y - LG + M;
            for (Box b : sc.nodes) {
                if (r.emphasis.contains(b.id)) b.emph = true;
                Integer s = stepOf.get(b.id);
                Integer gs = b.groupId == null ? null : stepOf.get(b.groupId);
                if (s != null && gs != null) b.step = Math.min(s, gs); else if (s != null) b.step = s; else if (gs != null) b.step = gs;
            }
            for (Box b : sc.lanes) b.step = stepOf.getOrDefault(b.id, -1);
            for (REdge e : r.edges) {
                Box a = byId.get(e.from), b = byId.get(e.to);
                Ln l = new Ln();
                l.id = e.id(); l.from = e.from; l.to = e.to;
                int[] p, q;
                if (a.y + a.h <= b.y) { p = new int[] { a.x + a.w / 2, a.y + a.h }; q = new int[] { b.x + b.w / 2, b.y }; }
                else if (b.y + b.h <= a.y) { p = new int[] { a.x + a.w / 2, a.y }; q = new int[] { b.x + b.w / 2, b.y + b.h }; }
                else if (a.x < b.x) { p = new int[] { a.x + a.w, a.y + a.h / 2 }; q = new int[] { b.x, b.y + b.h / 2 }; }
                else { p = new int[] { a.x, a.y + a.h / 2 }; q = new int[] { b.x + b.w, b.y + b.h / 2 }; }
                l.pts.add(p); l.pts.add(q);
                l.dashed = b.dashed;
                l.step = stepOf.getOrDefault(l.id, -1);
                sc.lines.add(l);
            }
            // the stepped reveal of the other kinds: the n-th entry of `steps` appears at n × step_seconds
            double stepS = id.d("animation", "step_seconds");
            for (Box b : sc.lanes) if (b.step >= 0) b.t = b.step * stepS;
            for (Box b : sc.nodes) if (b.step >= 0) b.t = b.step * stepS;
            for (Ln l : sc.lines) if (l.step >= 0) l.t = l.step * stepS;
        }
        return sc;
    }

    // ── orchestrator: the skill's steps from the root, in the written order ──

    static final int HP = 16, DP = 13; // line pitch of a multi-line box's head and detail

    /** Height of the text block of a multi-line box. */
    static int blockH(Box b, int lbl) {
        return lbl + HP * (Math.max(1, b.head.size()) - 1) + (b.detail.isEmpty() ? 0 : 15 + DP * (b.detail.size() - 1)) + 3;
    }

    static int headBase(Box b, int k, int lbl) { return b.y + (b.h - blockH(b, lbl)) / 2 + lbl + HP * k; }

    static int detBase(Box b, int k, int lbl) { return b.y + (b.h - blockH(b, lbl)) / 2 + lbl + HP * (b.head.size() - 1) + 15 + DP * k; }

    /** Smallest box height that holds the text block, on the grid, at least `min`. */
    static int fit(Box b, int lbl, int min, int grid) { return Math.max(min, up(blockH(b, lbl) + 16, grid)); }

    static Txt txt(String tid, String text, int x, int y, int size, String anchor, double t) {
        Txt e = new Txt();
        e.id = tid; e.text = text; e.x = x; e.y = y; e.size = size; e.anchor = anchor; e.t = t;
        return e;
    }

    static Ln ln(String lid, String from, String to, double t, boolean dashed, int... xy) {
        Ln l = new Ln();
        l.id = lid; l.from = from; l.to = to; l.t = t; l.dashed = dashed;
        for (int k = 0; k < xy.length; k += 2) l.pts.add(new int[] { xy[k], xy[k + 1] });
        return l;
    }

    /** What a step draws, read from its facts. */
    static final class Chain {
        List<String> segs = new ArrayList<>();
        boolean cond;
    }

    static final class Br {
        String kind, cond, to;
        int after;
        List<String> says = new ArrayList<>();
    }

    static final class Info {
        String fid;
        Map<String, Object> f;
        List<List<Chain>> phases = new ArrayList<>(); // the chains that delegate; `self` alone is no chain
        List<String> notes = new ArrayList<>();
        List<Br> brs = new ArrayList<>();
        List<String> badges = new ArrayList<>();      // the gate words that stay on the step: the branch words go to their terminal
        boolean diamond;
        double t;
        Box box;
    }

    /** Boxes, lines and texts laid out relative to a row, moved into place once the row's height is known. */
    static final class Frag {
        List<Box> boxes = new ArrayList<>();
        List<Ln> lines = new ArrayList<>();
        List<Txt> texts = new ArrayList<>();

        void shift(int dy) {
            for (Box b : boxes) b.y += dy;
            for (Ln l : lines) for (int[] p : l.pts) p[1] += dy;
            for (Txt t : texts) t.y += dy;
        }
    }

    static final class Skip {
        Box term;
        String target;
        Skip(Box term, String target) { this.term = term; this.target = target; }
    }

    static final class Orch {
        final Scene sc;
        final Resolved r;
        final Map<String, Object> facts;
        final Identity id;
        final Map<String, Box> byId;
        final int NH, M, TOP, GRID, LBL, DET, SW, CW, CMIN, CG, AL, BS, BG, RG, RGV, LANE, TW, TH, TG, PG, CAPH, PAD, NW, G, DH, DHT, VH;
        final double cw, STEP_S, PHASE_S, BRANCH_S, DELEG_S;
        final List<String> branchWords;
        int maxRight;
        final Set<String> used = new TreeSet<>();
        boolean anyDiamond, anyCond;
        final List<Skip> skips = new ArrayList<>();
        final Map<String, Double> stepT = new HashMap<>();

        Orch(Scene sc, Resolved r, Map<String, Object> facts, Identity id, Map<String, Box> byId) {
            this.sc = sc; this.r = r; this.facts = facts; this.id = id; this.byId = byId;
            NH = id.i("node", "h"); NW = id.i("node", "w"); G = id.i("gap"); M = id.i("margin"); TOP = id.i("top"); GRID = id.i("grid");
            LBL = id.i("font", "label"); DET = id.i("font", "detail"); cw = id.d("font", "char_width");
            SW = id.i("orchestrator", "step", "w"); CW = id.i("orchestrator", "chip", "w"); CMIN = id.i("orchestrator", "chip", "min_h"); CG = id.i("orchestrator", "chip", "gap");
            AL = id.i("orchestrator", "arrow"); BS = id.i("orchestrator", "badge", "size"); BG = id.i("orchestrator", "badge", "gap");
            RG = id.i("orchestrator", "row_gap"); RGV = id.i("row_gap"); LANE = id.i("orchestrator", "routing", "lane_gap");
            TW = id.i("orchestrator", "terminal", "w"); TH = id.i("orchestrator", "terminal", "h"); TG = id.i("orchestrator", "terminal", "gap");
            PG = id.i("orchestrator", "phase", "gap"); CAPH = id.i("orchestrator", "phase", "caption_h"); PAD = id.i("orchestrator", "phase", "parallel", "pad");
            DH = id.i("orchestrator", "decision", "h"); DHT = id.i("orchestrator", "decision", "h_tall"); VH = id.i("orchestrator", "decision", "verdict_h");
            STEP_S = id.d("animation", "step_seconds"); PHASE_S = id.d("animation", "phase_seconds");
            BRANCH_S = id.d("animation", "branch_seconds"); DELEG_S = id.d("animation", "delegate_seconds");
            branchWords = branchGateWords();
        }

        // ── facts → what to draw ──

        List<Info> infos() {
            List<Info> out = new ArrayList<>();
            for (String n : r.groups.stream().flatMap(g -> g.nodes.stream()).collect(Collectors.toList())) {
                Info s = new Info();
                s.fid = n;
                s.f = map(facts.get(n));
                boolean anyDelegate = false;
                for (Object po : list(s.f.get("phases"))) {
                    Map<String, Object> p = map(po);
                    List<Chain> cs = new ArrayList<>();
                    for (Object co : list(p.get("chains"))) {
                        Map<String, Object> c = map(co);
                        Chain ch = new Chain();
                        for (Object seg : delegates(list(c.get("refs")), r.skill)) ch.segs.add((String) seg);
                        ch.cond = Boolean.TRUE.equals(c.get("conditional"));
                        if (!ch.segs.isEmpty()) { cs.add(ch); anyDelegate = true; }
                    }
                    s.phases.add(cs);
                    s.notes.add(String.valueOf(p.get("note")));
                }
                for (Object bo : list(s.f.get("branches"))) {
                    Map<String, Object> b = map(bo);
                    Br br = new Br();
                    br.kind = (String) b.get("kind");
                    br.cond = (String) b.get("condition");
                    br.to = (String) b.get("to");
                    br.after = ((Number) b.get("after_phase")).intValue();
                    for (Object o : list(b.get("says"))) br.says.add((String) o);
                    s.brs.add(br);
                }
                for (Object g : list(s.f.get("gates"))) if (!branchWords.contains(g)) s.badges.add((String) g);
                s.diamond = !anyDelegate && s.brs.size() == 1;
                out.add(s);
            }
            return out;
        }

        // ── boxes ──

        void add(Box b) {
            sc.nodes.add(b);
            byId.put(b.id, b);
            maxRight = Math.max(maxRight, b.x + b.w);
        }

        void commit(Frag fr) {
            for (Box b : fr.boxes) add(b);
            sc.lines.addAll(fr.lines);
            sc.texts.addAll(fr.texts);
        }

        Box rootBox(int x, int y, int w) {
            Map<String, Object> f = map(facts.get("skill:" + r.skill));
            Box b = new Box();
            b.id = "root:" + r.skill;
            b.label = r.skill;
            b.head.add(r.skill);
            b.detail.add(id.s("orchestrator", "self_detail"));
            b.detail.addAll(rootTerritory(list(f.get("write_allow")), (int) ((w - 16) / (DET * cw))));
            b.role = id.role("skill:" + r.skill, f);
            b.fill = id.fill(b.role);
            b.stroke = id.stroke(b.role);
            b.x = x; b.y = y; b.w = w;
            b.h = fit(b, LBL, NH, GRID);
            return b;
        }

        /** The territory of the orchestrator, collapsed to the directories and files it writes: `src/**` is `src/`. */
        List<String> rootTerritory(List<Object> wa, int width) {
            if (wa == null || wa.isEmpty()) return wrap(territory(wa), width);
            String any = id.s("orchestrator", "territory_any");
            LinkedHashSet<String> dirs = new LinkedHashSet<>(), files = new LinkedHashSet<>();
            for (Object g : wa) {
                String s = String.valueOf(g);
                while (s.startsWith("**/")) s = s.substring(3);
                String[] seg = s.split("/", -1);
                StringBuilder b = new StringBuilder();
                for (int k = 0; k < seg.length; k++) {
                    if (seg[k].contains("*")) break;
                    b.append(seg[k]);
                    if (k < seg.length - 1) b.append('/');
                }
                String e = b.toString();
                if (e.isEmpty()) dirs.add(any);
                else if (e.endsWith("/")) dirs.add(e);
                else files.add(e);
            }
            List<String> all = new ArrayList<>(dirs);
            all.addAll(files);
            int maxLines = id.i("orchestrator", "root", "max_lines");
            String more = id.s("orchestrator", "root", "more");
            List<List<String>> lines = new ArrayList<>();
            List<String> cur = new ArrayList<>();
            int placed = 0;
            for (String e : all) {
                if (!cur.isEmpty() && String.join(" ", cur).length() + 1 + e.length() > width) {
                    lines.add(cur);
                    cur = new ArrayList<>();
                    if (lines.size() == maxLines) break;
                }
                cur.add(e);
                placed++;
            }
            if (lines.size() < maxLines && !cur.isEmpty()) lines.add(cur);
            int hidden = all.size() - placed;
            if (hidden > 0) {
                List<String> last = lines.get(lines.size() - 1);
                while (last.size() > 1 && String.join(" ", last).length() + 1 + more.replace("{n}", String.valueOf(hidden)).length() > width) {
                    last.remove(last.size() - 1);
                    hidden++;
                }
                last.add(more.replace("{n}", String.valueOf(hidden)));
            }
            return lines.stream().map(l -> String.join(" ", l)).collect(Collectors.toList());
        }

        Box stepBox(Info s, int w) {
            Box b = nodeBox(s.fid, facts, id);
            b.head = wrap(b.label, (int) ((w - 16) / (LBL * cw)));
            b.w = w;
            b.h = fit(b, LBL, NH, GRID);
            if (b.dashed) anyCond = true;
            return b;
        }

        /** The step that is its own check: its name over the condition, in a diamond. */
        Box stepDiamond(Info s, int x, int y) {
            Box d = nodeBox(s.fid, facts, id);
            decide(d);
            d.head = wrap(d.label, (int) (SW * 0.6 / (LBL * cw)));
            d.detail = List.of(s.brs.get(0).cond);
            d.w = SW;
            d.h = d.head.size() + 1 > 2 ? DHT : DH;
            d.x = x; d.y = y;
            return d;
        }

        Box verdict(String vid, Br b, int w, int h) {
            Box d = new Box();
            d.id = vid;
            decide(d);
            d.head = wrap(b.cond, (int) (w * 0.6 / (LBL * cw)));
            d.w = w; d.h = h;
            return d;
        }

        void decide(Box d) {
            d.shape = "diamond";
            d.dashed = false;
            d.fill = id.s("orchestrator", "decision", "fill");
            d.stroke = id.s("orchestrator", "decision", "stroke");
            d.sw = id.d("orchestrator", "decision", "stroke_width");
            anyDiamond = true;
        }

        /** The yes exit of a diamond: edge, terminal, its badge; a skip registers its rejoin path, drawn once every step has a place. */
        void exit(Frag fr, Box d, Br b, double td, String owner, int bi) {
            double tt = td + BRANCH_S;
            int cy = d.y + d.h / 2, tx = d.x + d.w + TG;
            Box tm = new Box();
            tm.id = "term:" + owner + ":" + bi;
            boolean skip = b.kind.equals("skip");
            tm.head.add(skip ? id.s("orchestrator", "terminal", "skip", "title").replace("{target}", String.valueOf(map(facts.get(b.to)).get("name")))
                    : id.s("orchestrator", "terminal", b.kind, "title"));
            tm.detail.addAll(b.says);
            tm.fill = skip ? id.s("orchestrator", "terminal", "skip", "fill") : id.s("orchestrator", "gates", b.kind, "fill");
            tm.stroke = skip ? id.s("orchestrator", "terminal", "skip", "stroke") : id.s("orchestrator", "gates", b.kind, "stroke");
            tm.x = tx; tm.y = cy - TH / 2; tm.w = TW; tm.h = TH; tm.t = tt;
            fr.boxes.add(tm);
            fr.lines.add(ln("yes:" + owner + ":" + bi, d.id, tm.id, tt, false, d.x + d.w, cy, tx, cy));
            fr.texts.add(txt("yes-label:" + owner + ":" + bi, id.s("orchestrator", "branch_labels", "yes"), (d.x + d.w + tx) / 2, cy - 8, DET, "middle", tt));
            if (skip) skips.add(new Skip(tm, b.to));
            else {
                Box g = badge(b.kind, id);
                g.id = "tbadge:" + owner + ":" + bi;
                g.x = tx + TW + BG; g.y = cy - BS / 2; g.t = tt;
                used.add(b.kind);
                fr.boxes.add(g);
            }
        }

        // ── flow ──

        /** Where the flow leaves a box: `no` marks the bottom of a diamond. */
        static final class Out {
            String id;
            int x, y;
            boolean no;
            Out(String id, int x, int y, boolean no) { this.id = id; this.x = x; this.y = y; this.no = no; }
        }

        void flow(Out from, Box to, int tx, int ty, double t) {
            String lid = from.id + ">" + to.id;
            sc.lines.add(ln("flow:" + lid, from.id, to.id, t, false, from.x, from.y, tx, ty));
            if (from.no) sc.texts.add(txt("no-label:" + lid, id.s("orchestrator", "branch_labels", "no"), from.x + 10, from.y + 17, DET, "start", t));
        }

        // ── the two layouts ──

        void layout() {
            if (r.derive.equals("depends_on")) rows(); else column();
        }

        List<int[]> items(Info s) {
            List<int[]> it = new ArrayList<>();
            for (int p = 0; p < s.phases.size(); p++) {
                it.add(new int[] { 0, p });
                for (int bi = 0; bi < s.brs.size(); bi++) if (s.brs.get(bi).after == p) it.add(new int[] { 1, bi });
            }
            return it;
        }

        /** One row per step, in the written order; the chips of who runs it to its right, phase under phase. */
        void column() {
            List<Info> st = infos();
            int strip = 0, lstrip = 0;
            for (Info s : st) {
                int n = s.badges.size();
                int w = n == 0 ? 0 : n * (BS + BG) + BG;
                if (s.diamond) lstrip = Math.max(lstrip, w); else strip = Math.max(strip, w);
            }
            int stepX = M + lstrip, cx = stepX + SW / 2, x0 = stepX + SW + strip + AL;
            Box root = rootBox(stepX, TOP, SW);
            add(root);
            int y = TOP + root.h + RG;
            Out prev = new Out(root.id, cx, root.y + root.h, false);
            double t = 0;
            int xc = x0 + CW / 2;
            for (Info s : st) {
                double ts = t;
                stepT.put(s.fid, ts);
                if (s.diamond) {
                    Box d = stepDiamond(s, stepX, y);
                    d.t = ts;
                    add(d);
                    s.box = d;
                    flow(prev, d, cx, y, ts);
                    diamondBadges(s, d, ts);
                    Frag fx = new Frag();
                    exit(fx, d, s.brs.get(0), ts, s.fid, 0);
                    commit(fx);
                    prev = new Out(d.id, cx, y + d.h, true);
                    y += d.h + RG;
                    t += STEP_S;
                    continue;
                }
                List<int[]> items = items(s);
                int P = s.phases.size();
                double[] tp = new double[P], tv = new double[s.brs.size()];
                for (int k = 0; k < items.size(); k++) {
                    int[] it = items.get(k);
                    if (it[0] == 0) tp[it[1]] = ts + k * PHASE_S; else tv[it[1]] = ts + k * PHASE_S;
                }
                Box sb = stepBox(s, SW);
                s.box = sb;
                // the chip column: phases, and the verdicts that fall between them
                Frag fr = new Frag();
                List<Box> firstChips = new ArrayList<>();
                int prevBottom = -1;
                boolean prevVerdict = false, multi = P >= 2;
                for (int p = 0; p < P; p++) {
                    List<Chain> chains = s.phases.get(p);
                    if (!chains.isEmpty()) {
                        boolean par = multi && chains.size() >= 2;
                        int pt = prevBottom < 0 ? 0 : prevBottom + PG;
                        int chipsTop = pt + (multi ? CAPH : 0);
                        int cy = chipsTop + (par ? PAD : 0), widest = 0;
                        List<Box> chips = new ArrayList<>();
                        List<Ln> arrows = new ArrayList<>();
                        List<Txt> tools = new ArrayList<>();
                        for (int ci = 0; ci < chains.size(); ci++) {
                            Chain ch = chains.get(ci);
                            List<Box> row = new ArrayList<>();
                            int chainH = 0;
                            for (int si = 0; si < ch.segs.size(); si++) {
                                Box c = chip(ch.segs.get(si), r.skill, facts, id, cw);
                                c.id = "chip:" + s.fid + ":" + p + "." + ci + "." + si;
                                c.w = CW;
                                c.h = fit(c, LBL, CMIN, GRID);
                                c.dashed = ch.cond;
                                c.t = tp[p] + si * DELEG_S;
                                chainH = Math.max(chainH, c.h);
                                row.add(c);
                            }
                            if (ch.cond) anyCond = true;
                            for (int si = 0; si < row.size(); si++) {
                                Box c = row.get(si);
                                c.x = x0 + si * (CW + AL);
                                c.y = cy + (chainH - c.h) / 2;
                                if (si > 0) {
                                    Box pc = row.get(si - 1);
                                    int y1 = pc.y + pc.h / 2, y2 = c.y + c.h / 2, x1 = pc.x + pc.w;
                                    arrows.add(ln("seg:" + pc.id + ">" + c.id, pc.id, c.id, c.t, false, x1, y1, c.x, y2));
                                    if (!c.tool.isEmpty()) {
                                        int lx = c.x - AL / 2;
                                        tools.add(txt("tool:" + c.id, c.tool, lx, y1 + (int) Math.round((double) (y2 - y1) * (lx - x1) / (c.x - x1)) - 6, DET, "middle", c.t));
                                    }
                                }
                            }
                            Box f0 = row.get(0);
                            if (multi && !f0.tool.isEmpty()) tools.add(txt("tool:" + f0.id, f0.tool, x0 - 6 - (par ? PAD : 0), f0.y + 13, DET, "end", tp[p]));
                            firstChips.add(f0);
                            chips.addAll(row);
                            widest = Math.max(widest, row.size() * CW + (row.size() - 1) * AL);
                            cy += chainH + CG;
                        }
                        int blockBottom = cy - CG + (par ? PAD : 0);
                        if (par) {
                            Box fb = new Box();
                            fb.id = "frame:" + s.fid + ":" + p;
                            fb.shape = "frame";
                            fb.stroke = id.s("orchestrator", "phase", "parallel", "stroke");
                            fb.dash = id.s("orchestrator", "phase", "parallel", "dash");
                            fb.rx = id.i("orchestrator", "phase", "parallel", "radius");
                            fb.sw = id.d("stroke");
                            fb.x = x0 - PAD; fb.y = chipsTop; fb.w = widest + 2 * PAD; fb.h = blockBottom - chipsTop; fb.t = tp[p];
                            fr.boxes.add(fb);
                        }
                        fr.boxes.addAll(chips);
                        fr.lines.addAll(arrows);
                        fr.texts.addAll(tools);
                        if (multi) {
                            String note = s.notes.get(p);
                            String cap = (p + 1) + (note.isEmpty() ? "" : " · " + note) + (chains.size() >= 2 ? " · " + id.s("orchestrator", "phase", "parallel_caption") : "");
                            fr.texts.add(txt("cap:" + s.fid + ":" + p, cap, xc + 16, pt + 11, DET, "start", tp[p]));
                        }
                        if (prevBottom >= 0) {
                            fr.lines.add(ln("phase:" + s.fid + ":" + p, null, null, tp[p], false, xc, prevBottom, xc, chipsTop));
                            if (prevVerdict) fr.texts.add(txt("no-label:" + s.fid + ":" + p, id.s("orchestrator", "branch_labels", "no"), xc - 8, prevBottom + 16, DET, "end", tp[p]));
                        }
                        prevBottom = blockBottom;
                        prevVerdict = false;
                    }
                    for (int bi = 0; bi < s.brs.size(); bi++) {
                        Br b = s.brs.get(bi);
                        if (b.after != p || p == P - 1) continue;
                        int top = prevBottom < 0 ? 0 : prevBottom + PG;
                        Box d = verdict("verdict:" + s.fid + ":" + bi, b, CW, VH);
                        d.x = x0; d.y = top; d.t = tv[bi];
                        fr.boxes.add(d);
                        if (prevBottom >= 0) {
                            fr.lines.add(ln("phase:" + s.fid + ":v" + bi, null, d.id, tv[bi], false, xc, prevBottom, xc, top));
                            if (prevVerdict) fr.texts.add(txt("no-label:" + s.fid + ":v" + bi, id.s("orchestrator", "branch_labels", "no"), xc - 8, prevBottom + 16, DET, "end", tv[bi]));
                        }
                        exit(fr, d, b, tv[bi], s.fid, bi);
                        prevBottom = top + VH;
                        prevVerdict = true;
                    }
                }
                int H = Math.max(prevBottom, 0);
                int rowH = Math.max(sb.h, H);
                sb.x = stepX; sb.y = y + (rowH - sb.h) / 2; sb.t = ts;
                fr.shift(y + (rowH - H) / 2);
                int sy = sb.y + sb.h / 2, sx = sb.x + sb.w;
                add(sb);
                commit(fr);
                // the arrows from the step to its first chips
                if (multi) {
                    if (!firstChips.isEmpty()) {
                        Box c = firstChips.get(0);
                        double te = Double.MAX_VALUE;
                        for (int p = 0; p < P; p++) if (!s.phases.get(p).isEmpty()) { te = tp[p]; break; }
                        sc.lines.add(ln("entry:" + s.fid, s.fid, c.id, te, false, sx, sy, c.x, c.y + c.h / 2));
                    }
                } else for (Box c : firstChips) {
                    int y2 = c.y + c.h / 2;
                    sc.lines.add(ln("entry:" + c.id, s.fid, c.id, tp[0], false, sx, sy, c.x, y2));
                    if (!c.tool.isEmpty()) {
                        int lx = c.x - AL / 2;
                        sc.texts.add(txt("tool:" + c.id, c.tool, lx, sy + (int) Math.round((double) (y2 - sy) * (lx - sx) / (c.x - sx)) - 6, DET, "middle", tp[0]));
                    }
                }
                for (int k = 0; k < s.badges.size(); k++) {
                    used.add(s.badges.get(k));
                    Box g = badge(s.badges.get(k), id);
                    g.id = "badge:" + s.fid + ":" + k;
                    g.x = sx + BG + k * (BS + BG);
                    g.y = sy - BS / 2;
                    g.t = ts;
                    add(g);
                }
                flow(prev, sb, cx, sb.y, ts);
                prev = new Out(sb.id, cx, sb.y + sb.h, false);
                int bottom = y + rowH;
                // a verdict after the last phase sits under the step, in the step column
                for (int bi = 0; bi < s.brs.size(); bi++) {
                    Br b = s.brs.get(bi);
                    if (b.after != P - 1) continue;
                    int vy = bottom + RG;
                    Box d = verdict("verdict:" + s.fid + ":" + bi, b, SW, DH);
                    d.x = stepX; d.y = vy; d.t = tv[bi];
                    add(d);
                    flow(prev, d, cx, vy, tv[bi]);
                    Frag fx = new Frag();
                    exit(fx, d, b, tv[bi], s.fid, bi);
                    commit(fx);
                    prev = new Out(d.id, cx, vy + d.h, true);
                    bottom = vy + d.h;
                }
                y = bottom + RG;
                t += STEP_S + (Math.max(1, items.size()) - 1) * PHASE_S;
            }
            finish(y, t, stepX, SW);
        }

        void diamondBadges(Info s, Box d, double ts) {
            for (int k = 0; k < s.badges.size(); k++) {
                used.add(s.badges.get(k));
                Box g = badge(s.badges.get(k), id);
                g.id = "badge:" + s.fid + ":" + k;
                g.x = d.x - BG - BS - k * (BS + BG);
                g.y = d.y + d.h / 2 - BS / 2;
                g.t = ts;
                add(g);
            }
        }

        /** Rows in the written order, centred on one axis: a step shares a row until it needs something the row holds. */
        void rows() {
            List<Info> st = infos();
            Map<String, Integer> pos = new HashMap<>();
            for (int k = 0; k < st.size(); k++) pos.put(st.get(k).fid, k);
            // what each step waits for: its Depends on, else the step written before it
            Map<String, List<String>> deps = new LinkedHashMap<>();
            for (int k = 0; k < st.size(); k++) {
                List<String> d = new ArrayList<>();
                for (Object o : list(st.get(k).f.get("depends_on"))) d.add((String) o);
                if (d.isEmpty() && k > 0) d.add(st.get(k - 1).fid);
                deps.put(st.get(k).fid, d);
            }
            for (Info s : st)
                if (!s.diamond && !s.brs.isEmpty())
                    throw new Fail("view " + r.name + ": step " + s.f.get("id") + " of " + r.skill + " carries a branch and runs more than `self`; a depends_on view draws a branch only as the diamond of a step that runs `self` alone, with exactly one branch (references/step-contract.md)");
            // a step sits one row below the deepest step it depends on, beside the others of that depth in
            // the written order; a branch step keeps a row of its own, so nothing written after it climbs above it
            Map<String, Integer> rowOf = new HashMap<>();
            int last = -1, floor = 0;
            for (Info s : st) {
                int k = floor;
                for (String d : deps.get(s.fid)) k = Math.max(k, rowOf.get(d) + 1);
                if (!s.brs.isEmpty()) k = Math.max(k, last + 1);
                rowOf.put(s.fid, k);
                last = Math.max(last, k);
                if (!s.brs.isEmpty()) floor = k + 1;
            }
            List<List<Info>> rows = new ArrayList<>();
            for (int k = 0; k <= last; k++) rows.add(new ArrayList<>());
            for (Info s : st) rows.get(rowOf.get(s.fid)).add(s);
            // edges: depends_on minus what a longer path implies; the first step hangs from the root
            List<String[]> edges = new ArrayList<>();
            for (Info s : st) for (String d : deps.get(s.fid)) {
                boolean implied = false;
                for (String o : deps.get(s.fid)) if (!o.equals(d) && reaches(o, d, deps)) { implied = true; break; }
                if (!implied) edges.add(new String[] { d, s.fid });
            }
            List<String[]> lanes = new ArrayList<>();
            for (String[] e : edges) if (rowOf.get(e[1]) - rowOf.get(e[0]) > 1) lanes.add(e);
            lanes.sort(Comparator.<String[]>comparingInt(e -> rowOf.get(e[0])).thenComparingInt(e -> rowOf.get(e[1])));
            int lstrip = 0;
            for (Info s : st) if (s.diamond && !s.badges.isEmpty()) lstrip = Math.max(lstrip, s.badges.size() * (BS + BG) + BG);
            // sizes
            int[] rowW = new int[rows.size()], rowH = new int[rows.size()];
            for (int k = 0; k < rows.size(); k++) {
                for (Info s : rows.get(k)) {
                    Box b;
                    if (s.diamond) b = stepDiamond(s, 0, 0); else b = stepBox(s, NW);
                    s.box = b;
                    rowW[k] += b.w + (rowW[k] == 0 ? 0 : G);
                    rowH[k] = Math.max(rowH[k], b.h);
                }
            }
            int left = SW / 2 + lstrip, half = 0;
            for (int w : rowW) half = Math.max(half, (w + 1) / 2);
            left = Math.max(left, half);
            int gutter = lanes.isEmpty() ? 0 : (lanes.size() + 1) * LANE;
            int cx = M + gutter + left;
            Box root = rootBox(cx - SW / 2, TOP, SW);
            add(root);
            int[] rowY = new int[rows.size()];
            int y = TOP + root.h + RG;
            for (int k = 0; k < rows.size(); k++) { rowY[k] = y; y += rowH[k] + (k < rows.size() - 1 ? RGV : 0); }
            // place, in the written order, the times following it
            double t = 0;
            for (int k = 0; k < rows.size(); k++) {
                int x = cx - rowW[k] / 2;
                for (Info s : rows.get(k)) {
                    Box b = s.box;
                    b.x = x; b.y = rowY[k] + (rowH[k] - b.h) / 2; b.t = t;
                    x += b.w + G;
                    stepT.put(s.fid, t);
                    add(b);
                    if (s.diamond) {
                        diamondBadges(s, b, t);
                        Frag fx = new Frag();
                        exit(fx, b, s.brs.get(0), t, s.fid, 0);
                        commit(fx);
                    } else {
                        int sy = b.y + b.h / 2;
                        for (int g = 0; g < s.badges.size(); g++) {
                            used.add(s.badges.get(g));
                            Box bg = badge(s.badges.get(g), id);
                            bg.id = "badge:" + s.fid + ":" + g;
                            bg.x = b.x + b.w + BG + g * (BS + BG);
                            bg.y = sy - BS / 2;
                            bg.t = t;
                            add(bg);
                        }
                    }
                    t += STEP_S;
                }
            }
            // the edges, now that every step has a place
            Box first = st.get(0).box;
            sc.lines.add(ln("flow:" + root.id + ">" + first.id, root.id, first.id, 0, false, cx, root.y + root.h, first.x + first.w / 2, first.y));
            for (String[] e : edges) {
                Info a = st.get(pos.get(e[0])), b = st.get(pos.get(e[1]));
                double te = stepT.get(e[1]);
                int ra = rowOf.get(e[0]), rb = rowOf.get(e[1]);
                int sx = a.box.x + a.box.w / 2, sy = a.box.y + a.box.h, tx = b.box.x + b.box.w / 2, ty = b.box.y;
                String lid = a.box.id + ">" + b.box.id;
                if (rb - ra == 1) sc.lines.add(ln("flow:" + lid, a.box.id, b.box.id, te, false, sx, sy, tx, ty));
                else {
                    int lane = lanes.indexOf(e);
                    int lx = M + lane * LANE, c1 = rowY[ra] + rowH[ra] + RGV / 4, c2 = rowY[rb] - RGV / 2;
                    sc.lines.add(ln("flow:" + lid, a.box.id, b.box.id, te, false, sx, sy, sx, c1, lx, c1, lx, c2, tx, c2, tx, ty));
                }
                if (a.diamond) sc.texts.add(txt("no-label:" + lid, id.s("orchestrator", "branch_labels", "no"), sx + 10, sy + 17, DET, "start", te));
            }
            finish(y + RG, t, cx - SW / 2, SW);
        }

        static boolean reaches(String from, String target, Map<String, List<String>> deps) {
            Deque<String> q = new ArrayDeque<>(List.of(from));
            Set<String> seen = new HashSet<>();
            while (!q.isEmpty()) {
                String c = q.poll();
                if (!seen.add(c)) continue;
                for (String d : deps.getOrDefault(c, List.of())) { if (d.equals(target)) return true; q.add(d); }
            }
            return false;
        }

        // ── the end of every layout: the printed handoff, the skip paths, the legend, the size ──

        void finish(int y, double t, int hx, int hw) {
            Box lastBox = null;
            Map<String, Object> hf = map(facts.get("handoff:" + r.skill));
            if (hf != null) {
                Box after = byId.get((String) hf.get("after"));
                Box hb = nodeBox("handoff:" + r.skill, facts, id);
                hb.dashed = true;
                hb.head = List.of(hb.label);
                hb.detail = new ArrayList<>();
                for (Object c : list(hf.get("commands"))) hb.detail.add((String) c);
                hb.monoN = hb.detail.size();
                hb.detail.add(id.s("orchestrator", "handoff_caption"));
                hb.x = hx; hb.w = hw; hb.y = y;
                hb.h = fit(hb, LBL, NH, GRID);
                hb.t = t;
                add(hb);
                if (after != null)
                    sc.lines.add(ln("flow:" + after.id + ">" + hb.id, after.id, hb.id, t, true, after.x + after.w / 2, after.y + after.h, hb.x + hb.w / 2, hb.y));
                y += hb.h + RG;
                lastBox = hb;
            }
            // each skip: from its terminal, down the right gutter, above its target, to the target's centre
            int k = 0;
            int gutterBase = maxRight;
            for (Skip s : skips) {
                Box tg = byId.get(s.target);
                Box tm = s.term;
                int gx = gutterBase + (k + 1) * LANE, cy = tm.y + tm.h / 2, ay = tg.y - (r.derive.equals("depends_on") ? RGV : RG) / 2, tx = tg.x + tg.w / 2;
                sc.lines.add(ln("skip:" + tm.id, tm.id, tg.id, stepT.get(s.target), false, tm.x + tm.w, cy, gx, cy, gx, ay, tx, ay, tx, tg.y));
                k++;
            }
            // the legend: the badges in use, then the diamond, then the dashed box
            int lx = M, ly = y + 8;
            boolean any = false;
            for (String w : gateWords(id)) {
                if (!used.contains(w)) continue;
                Box g = badge(w, id);
                g.id = "legend:" + w;
                g.x = lx; g.y = ly;
                sc.nodes.add(g);
                sc.texts.add(txt("legend-text:" + w, w, lx + BS + 6, ly + BS / 2 + DET / 2 - 1, DET, "start", -1));
                lx += BS + 6 + (int) Math.round(w.length() * DET * cw) + 24;
                any = true;
            }
            if (anyDiamond) {
                Box g = new Box();
                g.id = "legend:decision"; g.shape = "diamond";
                g.fill = id.s("orchestrator", "decision", "fill"); g.stroke = id.s("orchestrator", "decision", "stroke"); g.sw = id.d("stroke");
                g.x = lx; g.y = ly; g.w = BS; g.h = BS;
                sc.nodes.add(g);
                String w = id.s("orchestrator", "legend", "decision");
                sc.texts.add(txt("legend-text:decision", w, lx + BS + 6, ly + BS / 2 + DET / 2 - 1, DET, "start", -1));
                lx += BS + 6 + (int) Math.round(w.length() * DET * cw) + 24;
                any = true;
            }
            if (anyCond) {
                Box g = new Box();
                g.id = "legend:conditional"; g.shape = "swatch"; g.dashed = true; g.dash = "6 4";
                g.fill = id.fill("procedure"); g.stroke = id.stroke("procedure"); g.sw = id.d("stroke"); g.rx = BS / 4;
                g.x = lx; g.y = ly; g.w = BS; g.h = BS;
                sc.nodes.add(g);
                String w = id.s("orchestrator", "legend", "conditional");
                sc.texts.add(txt("legend-text:conditional", w, lx + BS + 6, ly + BS / 2 + DET / 2 - 1, DET, "start", -1));
                lx += BS + 6 + (int) Math.round(w.length() * DET * cw) + 24;
                any = true;
            }
            int right = Math.max(maxRight + skips.size() * LANE, lx);
            right = Math.max(right, (int) Math.ceil(sc.caption.length() * id.i("font", "caption") * cw));
            sc.w = right + M;
            sc.h = ly + (any ? BS : -8) + M;
        }
    }

    /** The segments of a chain the orchestrator delegates to: its own leading `self` is the root, not a chip. */
    static List<Object> delegates(List<Object> chain, String skill) {
        return !chain.isEmpty() && chain.get(0).equals("skill:" + skill) ? chain.subList(1, chain.size()) : chain;
    }

    /** The gate words the identity styles, in the order of view.schema.json's vocabulary. */
    static List<String> gateWords(Identity id) {
        try { return gateWords(); } catch (IOException e) { throw new Fail("cannot read " + SCHEMA + ": " + e.getMessage()); }
    }

    static Box badge(String word, Identity id) {
        int BS = id.i("orchestrator", "badge", "size");
        Box g = new Box();
        g.label = word;
        g.head = List.of(id.s("orchestrator", "gates", word, "glyph"));
        g.fill = id.s("orchestrator", "gates", word, "fill");
        g.stroke = id.s("orchestrator", "gates", word, "stroke");
        g.role = "gate";
        g.w = BS; g.h = BS; g.rx = BS / 2;
        return g;
    }

    /** A chip of one chain segment: its name, and under it the territory its executor really has. */
    static Box chip(String fid, String orchestrator, Map<String, Object> facts, Identity id, double cw) {
        Map<String, Object> f = map(facts.get(fid));
        String kind = String.valueOf(f.get("kind"));
        Box c = new Box();
        c.label = (String) f.get("label");
        c.head.add(c.label);
        if ("skill".equals(kind) && "fork".equals(f.get("context"))) c.head.add(id.s("orchestrator", "fork_marker"));
        c.mono = "cmd".equals(kind);
        c.tool = id.s("orchestrator", "tools", "skill".equals(kind) && "fork".equals(f.get("context")) ? "skill_fork" : kind);
        c.role = id.role(fid, f);
        c.fill = id.fill(c.role);
        c.stroke = id.stroke(c.role);
        if (f.get("write_allow") instanceof List<?>) {
            List<Object> wa = list(f.get("write_allow"));
            String text = wa.size() == 1 && "**".equals(wa.get(0)) ? id.s("orchestrator", "territory_any") : territory(wa);
            c.detail.addAll(wrap(text, (int) ((id.i("orchestrator", "chip", "w") - 16) / (id.i("font", "detail") * cw))));
        }
        return c;
    }

    static Box nodeBox(String fid, Map<String, Object> facts, Identity id) {
        Map<String, Object> f = map(facts.get(fid));
        Box b = new Box();
        b.id = fid;
        b.label = (String) f.get("label");
        String d = f.get("detail") instanceof String s ? s : "";
        if (!d.isBlank() && !"skillclass".equals(f.get("kind")) && !"agentclass".equals(f.get("kind"))) b.detail = List.of(d);
        b.role = id.role(fid, f);
        b.fill = id.fill(b.role);
        b.stroke = id.stroke(b.role);
        b.dashed = Boolean.TRUE.equals(f.get("conditional"));
        return b;
    }

    // ───────────────────────────── render ─────────────────────────────

    static Map<String, String> renderAll(Resolved r, Map<String, Object> facts, Identity id) {
        Scene sc = scene(r, facts, id);
        String base = OUT + "/" + r.name + "/" + r.name;
        Map<String, String> m = new TreeMap<>();
        m.put(base + ".svg", svg(sc, id, false));
        m.put(base + ".animated.svg", svg(sc, id, true));
        m.put(base + ".drawio", drawio(sc, r.name, id));
        m.put(base + ".excalidraw", excalidraw(sc, id));
        return m;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String sid(String s) { return s.replaceAll("[^A-Za-z0-9_-]", "_"); }

    // ── SVG (static and animated: one builder, the animation is a class and a delay) ──

    static double sw(Box b, Identity id) { return b.sw > 0 ? b.sw : b.emph ? id.d("emphasis_stroke") : id.d("stroke"); }

    static String pts(List<int[]> p) {
        return p.stream().map(q -> q[0] + "," + q[1]).collect(Collectors.joining(" "));
    }

    static String svg(Scene sc, Identity id, boolean animated) {
        int LBL = id.i("font", "label"), DET = id.i("font", "detail");
        double rev = id.d("animation", "reveal_seconds");
        StringBuilder o = new StringBuilder();
        o.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        o.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(sc.w).append("\" height=\"").append(sc.h)
                .append("\" viewBox=\"0 0 ").append(sc.w).append(' ').append(sc.h).append("\" font-family=\"").append(esc(id.s("font", "family"))).append("\">\n");
        o.append("<title>").append(esc(sc.title)).append("</title>\n");
        o.append("<defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto\">")
                .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"").append(id.s("line")).append("\"/></marker></defs>\n");
        if (animated) {
            o.append("<style>\n@keyframes show{from{opacity:0}to{opacity:1}}\n.r{animation:show ")
                    .append(num(rev)).append("s ease both}\n</style>\n");
        }
        o.append("<rect width=\"").append(sc.w).append("\" height=\"").append(sc.h).append("\" fill=\"").append(id.s("background")).append("\"/>\n");
        o.append("<text x=\"").append(id.i("margin")).append("\" y=\"").append(id.i("margin") + id.i("font", "title")).append("\" font-size=\"")
                .append(id.i("font", "title")).append("\" font-weight=\"700\" fill=\"").append(id.s("ink")).append("\">").append(esc(sc.title)).append("</text>\n");
        if (!sc.caption.isBlank())
            o.append("<text x=\"").append(id.i("margin")).append("\" y=\"").append(id.i("margin") + id.i("font", "title") + 24).append("\" font-size=\"")
                    .append(id.i("font", "caption")).append("\" fill=\"").append(id.s("muted")).append("\">").append(esc(sc.caption)).append("</text>\n");
        for (Box b : sc.lanes) {
            o.append(open("g", b.id, b.t, animated));
            o.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h)
                    .append("\" rx=\"").append(id.i("radius")).append("\" fill=\"").append(b.fill).append("\" fill-opacity=\"0.45\" stroke=\"").append(b.stroke)
                    .append("\" stroke-width=\"").append(num(sw(b, id))).append("\"/>\n");
            int pad = id.i("lane", "pad");
            o.append("<text x=\"").append(b.x + pad).append("\" y=\"").append(b.y + pad + LBL).append("\" font-size=\"").append(LBL)
                    .append("\" font-weight=\"700\" fill=\"").append(id.s("ink")).append("\">").append(esc(b.label)).append("</text>\n");
            for (int k = 0; k < b.detail.size(); k++)
                o.append("<text x=\"").append(b.x + pad).append("\" y=\"").append(b.y + pad + LBL + 16 + k * 15).append("\" font-size=\"").append(DET)
                        .append("\" fill=\"").append(id.s("muted")).append("\">").append(esc(b.detail.get(k))).append("</text>\n");
            o.append("</g>\n");
        }
        for (Ln l : sc.lines) {
            o.append(open("g", "edge-" + l.id, l.t, animated));
            String tail = "\" stroke=\"" + id.s("line") + "\" stroke-width=\"" + num(id.d("stroke")) + "\"" + (l.dashed ? " stroke-dasharray=\"6 4\"" : "") + " marker-end=\"url(#arrow)\"/>\n";
            if (l.pts.size() == 2) {
                int[] a = l.pts.get(0), z = l.pts.get(1);
                o.append("<line x1=\"").append(a[0]).append("\" y1=\"").append(a[1]).append("\" x2=\"").append(z[0]).append("\" y2=\"").append(z[1]).append(tail);
            } else o.append("<polyline fill=\"none\" points=\"").append(pts(l.pts)).append(tail);
            o.append("</g>\n");
        }
        String mono = get(id.m, "orchestrator", "mono") instanceof String t ? t : "";
        for (Box b : sc.nodes) {
            o.append(open("g", b.id, b.t, animated));
            if (b.shape.equals("frame") || b.shape.equals("swatch")) {
                o.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h)
                        .append("\" rx=\"").append(b.rx).append("\" fill=\"").append(b.shape.equals("frame") ? "none" : b.fill).append("\" stroke=\"").append(b.stroke).append("\" stroke-width=\"").append(num(sw(b, id)))
                        .append("\" stroke-dasharray=\"").append(b.dash).append("\"/>\n</g>\n");
                continue;
            }
            if (!b.head.isEmpty() || b.shape.equals("diamond")) {
                if (b.shape.equals("diamond"))
                    o.append("<polygon points=\"").append(b.x + b.w / 2).append(',').append(b.y).append(' ').append(b.x + b.w).append(',').append(b.y + b.h / 2).append(' ')
                            .append(b.x + b.w / 2).append(',').append(b.y + b.h).append(' ').append(b.x).append(',').append(b.y + b.h / 2)
                            .append("\" fill=\"").append(b.fill).append("\" stroke=\"").append(b.stroke).append("\" stroke-width=\"").append(num(sw(b, id)))
                            .append("\" stroke-linejoin=\"round\"/>\n");
                else
                    o.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h)
                            .append("\" rx=\"").append(b.rx >= 0 ? b.rx : id.i("radius")).append("\" fill=\"").append(b.fill).append("\" stroke=\"").append(b.stroke)
                            .append("\" stroke-width=\"").append(num(sw(b, id))).append('"')
                            .append(b.dashed ? " stroke-dasharray=\"6 4\"" : "").append("/>\n");
                int mx = b.x + b.w / 2;
                for (int k = 0; k < b.head.size(); k++)
                    o.append("<text x=\"").append(mx).append("\" y=\"").append(headBase(b, k, LBL)).append("\" font-size=\"").append(LBL)
                            .append("\" font-weight=\"600\" text-anchor=\"middle\"").append(b.mono ? " font-family=\"" + esc(mono) + "\"" : "")
                            .append(" fill=\"").append(id.s("ink")).append("\">").append(esc(b.head.get(k))).append("</text>\n");
                for (int k = 0; k < b.detail.size(); k++)
                    o.append("<text x=\"").append(mx).append("\" y=\"").append(detBase(b, k, LBL)).append("\" font-size=\"").append(DET)
                            .append("\" text-anchor=\"middle\"").append(k < b.monoN ? " font-family=\"" + esc(mono) + "\"" : "")
                            .append(" fill=\"").append(id.s("muted")).append("\">").append(esc(b.detail.get(k))).append("</text>\n");
                o.append("</g>\n");
                continue;
            }
            o.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h)
                    .append("\" rx=\"").append(id.i("radius")).append("\" fill=\"").append(b.fill).append("\" stroke=\"").append(b.stroke)
                    .append("\" stroke-width=\"").append(num(sw(b, id))).append('"')
                    .append(b.dashed ? " stroke-dasharray=\"6 4\"" : "").append("/>\n");
            boolean two = !b.detail.isEmpty();
            int cx = b.x + b.w / 2;
            o.append("<text x=\"").append(cx).append("\" y=\"").append(b.y + b.h / 2 + (two ? -2 : 5)).append("\" font-size=\"").append(LBL)
                    .append("\" font-weight=\"600\" text-anchor=\"middle\" fill=\"").append(id.s("ink")).append("\">").append(esc(b.label)).append("</text>\n");
            if (two)
                o.append("<text x=\"").append(cx).append("\" y=\"").append(b.y + b.h / 2 + 14).append("\" font-size=\"").append(DET)
                        .append("\" text-anchor=\"middle\" fill=\"").append(id.s("muted")).append("\">").append(esc(b.detail.get(0))).append("</text>\n");
            o.append("</g>\n");
        }
        for (Txt t : sc.texts) {
            o.append(open("g", t.id, t.t, animated));
            o.append("<text x=\"").append(t.x).append("\" y=\"").append(t.y).append("\" font-size=\"").append(t.size).append("\" text-anchor=\"").append(t.anchor)
                    .append("\" fill=\"").append(id.s("muted")).append("\">").append(esc(t.text)).append("</text>\n");
            o.append("</g>\n");
        }
        return o.append("</svg>\n").toString();
    }

    /** A group tag; with `animated` and a reveal time `t` (seconds, negative = always visible) it carries the animation class and delay. */
    static String open(String tag, String id, double t, boolean animated) {
        String a = "<" + tag + " id=\"" + sid(id) + "\"";
        if (animated && t >= 0) a += " class=\"r\" style=\"animation-delay:" + String.format(Locale.ROOT, "%.2f", t) + "s\"";
        return a + ">\n";
    }

    // ── draw.io ──

    static String drawio(Scene sc, String view, Identity id) {
        String font = id.s("font", "family").split(",")[0].trim().replace("'", "");
        StringBuilder o = new StringBuilder();
        o.append("<mxfile host=\"nerviz-diagrams\" version=\"1\">\n");
        o.append("  <diagram id=\"").append(sid(view)).append("\" name=\"").append(esc(sc.title)).append("\">\n");
        o.append("    <mxGraphModel dx=\"0\" dy=\"0\" grid=\"1\" gridSize=\"").append(id.i("grid")).append("\" guides=\"1\" tooltips=\"1\" connect=\"1\" arrows=\"1\" fold=\"1\" page=\"1\" pageScale=\"1\" pageWidth=\"")
                .append(sc.w).append("\" pageHeight=\"").append(sc.h).append("\" background=\"").append(id.s("background")).append("\" math=\"0\" shadow=\"0\">\n");
        o.append("      <root>\n        <mxCell id=\"0\"/>\n        <mxCell id=\"1\" parent=\"0\"/>\n");
        o.append(cell("title", "<b>" + esc(sc.title) + "</b>" + (sc.caption.isBlank() ? "" : "<br/><font style=\"font-size:" + id.i("font", "caption") + "px\" color=\"" + id.s("muted") + "\">" + esc(sc.caption) + "</font>"),
                "text;html=1;align=left;verticalAlign=top;whiteSpace=wrap;fontSize=" + id.i("font", "title") + ";fontFamily=" + font + ";fontColor=" + id.s("ink") + ";",
                id.i("margin"), id.i("margin") - 8, sc.w - 2 * id.i("margin"), 56));
        for (Box b : sc.lanes) {
            String v = "<b>" + esc(b.label) + "</b>" + b.detail.stream().map(d -> "<br/><font style=\"font-size:" + id.i("font", "detail") + "px\" color=\"" + id.s("muted") + "\">" + esc(d) + "</font>").collect(Collectors.joining());
            o.append(cell("g_" + sid(b.id), v, "rounded=1;arcSize=4;html=1;whiteSpace=wrap;align=left;verticalAlign=top;spacingLeft=" + id.i("lane", "pad") + ";spacingTop=" + (id.i("lane", "pad") - 4)
                    + ";fontSize=" + id.i("font", "label") + ";fontFamily=" + font + ";fontColor=" + id.s("ink") + ";fillColor=" + b.fill + ";opacity=70;strokeColor=" + b.stroke
                    + ";strokeWidth=" + num(sw(b, id)) + ";", b.x, b.y, b.w, b.h));
        }
        String monoFace = get(id.m, "orchestrator", "mono") instanceof String t ? t.split(",")[0].trim().replace("'", "") : "";
        Set<String> cells = new HashSet<>();
        for (Box b : sc.lanes) cells.add("g_" + sid(b.id));
        for (Box b : sc.nodes) cells.add("n_" + sid(b.id));
        for (Box b : sc.nodes) {
            if (b.shape.equals("frame") || b.shape.equals("swatch")) {
                o.append(cell("n_" + sid(b.id), "", "rounded=1;arcSize=" + (int) Math.round(100.0 * b.rx / Math.min(b.w, b.h)) + ";html=1;fillColor=" + (b.shape.equals("frame") ? "none" : b.fill) + ";strokeColor=" + b.stroke
                        + ";strokeWidth=" + num(sw(b, id)) + ";dashed=1;dashPattern=" + b.dash + ";", b.x, b.y, b.w, b.h));
                continue;
            }
            if (b.shape.equals("diamond")) {
                String hv = b.head.stream().map(h -> esc(h)).collect(Collectors.joining("<br/>"));
                String dv = b.detail.stream().map(d -> "<br/><font style=\"font-size:" + id.i("font", "detail") + "px\" color=\"" + id.s("muted") + "\">" + esc(d) + "</font>").collect(Collectors.joining());
                o.append(cell("n_" + sid(b.id), hv.isEmpty() ? "" : "<b>" + hv + "</b>" + dv, "rhombus;html=1;whiteSpace=wrap;fontSize=" + id.i("font", "label") + ";fontFamily=" + font
                        + ";fontColor=" + id.s("ink") + ";fillColor=" + b.fill + ";strokeColor=" + b.stroke + ";strokeWidth=" + num(sw(b, id)) + ";", b.x, b.y, b.w, b.h));
                continue;
            }
            if (!b.head.isEmpty()) {
                String face = "<font face=\"" + monoFace + "\">";
                String hv = b.head.stream().map(h -> b.mono ? face + esc(h) + "</font>" : esc(h)).collect(Collectors.joining("<br/>"));
                StringBuilder dv = new StringBuilder();
                for (int k = 0; k < b.detail.size(); k++)
                    dv.append("<br/><font style=\"font-size:").append(id.i("font", "detail")).append("px\" color=\"").append(id.s("muted")).append('"')
                            .append(k < b.monoN ? " face=\"" + monoFace + "\"" : "").append('>').append(esc(b.detail.get(k))).append("</font>");
                o.append(cell("n_" + sid(b.id), "<b>" + hv + "</b>" + dv, "rounded=1;arcSize=" + (int) Math.round(100.0 * (b.rx >= 0 ? b.rx : id.i("radius")) / b.h) + ";html=1;whiteSpace=wrap;fontSize=" + id.i("font", "label") + ";fontFamily=" + font
                        + ";fontColor=" + id.s("ink") + ";fillColor=" + b.fill + ";strokeColor=" + b.stroke + ";strokeWidth=" + num(sw(b, id))
                        + (b.dashed ? ";dashed=1;dashPattern=6 4" : "") + ";", b.x, b.y, b.w, b.h));
                continue;
            }
            String v = "<b>" + esc(b.label) + "</b>" + b.detail.stream().map(d -> "<br/><font style=\"font-size:" + id.i("font", "detail") + "px\" color=\"" + id.s("muted") + "\">" + esc(d) + "</font>").collect(Collectors.joining());
            o.append(cell("n_" + sid(b.id), v, "rounded=1;arcSize=" + (int) Math.round(100.0 * id.i("radius") / b.h) + ";html=1;whiteSpace=wrap;fontSize=" + id.i("font", "label") + ";fontFamily=" + font
                    + ";fontColor=" + id.s("ink") + ";fillColor=" + b.fill + ";strokeColor=" + b.stroke + ";strokeWidth=" + num(sw(b, id))
                    + (b.dashed ? ";dashed=1;dashPattern=6 4" : "") + ";", b.x, b.y, b.w, b.h));
        }
        for (Ln l : sc.lines) {
            String src = l.from == null ? null : (sc.lanes.stream().anyMatch(b -> b.id.equals(l.from)) ? "g_" : "n_") + sid(l.from);
            String dst = l.to == null ? null : (sc.lanes.stream().anyMatch(b -> b.id.equals(l.to)) ? "g_" : "n_") + sid(l.to);
            boolean tied = src != null && dst != null && cells.contains(src) && cells.contains(dst);
            o.append("        <mxCell id=\"e_").append(sid(l.id)).append("\" value=\"\" style=\"edgeStyle=none;html=1;endArrow=block;endFill=1;strokeColor=").append(id.s("line"))
                    .append(";strokeWidth=").append(num(id.d("stroke"))).append(l.dashed ? ";dashed=1" : "").append(";\" edge=\"1\" parent=\"1\"");
            if (tied) o.append(" source=\"").append(src).append("\" target=\"").append(dst).append('"');
            if (tied && l.pts.size() == 2) o.append(">\n          <mxGeometry relative=\"1\" as=\"geometry\"/>\n        </mxCell>\n");
            else {
                o.append(">\n          <mxGeometry relative=\"1\" as=\"geometry\">\n");
                if (!tied) {
                    int[] a = l.pts.get(0), z = l.pts.get(l.pts.size() - 1);
                    o.append("            <mxPoint x=\"").append(a[0]).append("\" y=\"").append(a[1]).append("\" as=\"sourcePoint\"/>\n");
                    o.append("            <mxPoint x=\"").append(z[0]).append("\" y=\"").append(z[1]).append("\" as=\"targetPoint\"/>\n");
                }
                if (l.pts.size() > 2) {
                    o.append("            <Array as=\"points\">\n");
                    for (int k = 1; k < l.pts.size() - 1; k++)
                        o.append("              <mxPoint x=\"").append(l.pts.get(k)[0]).append("\" y=\"").append(l.pts.get(k)[1]).append("\"/>\n");
                    o.append("            </Array>\n");
                }
                o.append("          </mxGeometry>\n        </mxCell>\n");
            }
        }
        for (Txt t : sc.texts) {
            int tw = (int) Math.round(t.text.length() * t.size * id.d("font", "char_width"));
            int tx = t.anchor.equals("start") ? t.x : t.anchor.equals("end") ? t.x - tw : t.x - tw / 2;
            o.append(cell("t_" + sid(t.id), t.text, "text;html=1;align=" + (t.anchor.equals("start") ? "left" : t.anchor.equals("end") ? "right" : "center") + ";verticalAlign=middle;whiteSpace=nowrap;fontSize=" + t.size + ";fontFamily=" + font
                    + ";fontColor=" + id.s("muted") + ";", tx, t.y - t.size, tw, t.size + 4));
        }
        o.append("      </root>\n    </mxGraphModel>\n  </diagram>\n</mxfile>\n");
        return o.toString();
    }

    static String cell(String cid, String value, String style, int x, int y, int w, int h) {
        return "        <mxCell id=\"" + cid + "\" value=\"" + esc(value) + "\" style=\"" + esc(style) + "\" vertex=\"1\" parent=\"1\">\n          <mxGeometry x=\"" + x + "\" y=\"" + y
                + "\" width=\"" + w + "\" height=\"" + h + "\" as=\"geometry\"/>\n        </mxCell>\n";
    }

    // ── Excalidraw ──

    static String excalidraw(Scene sc, Identity id) {
        List<Object> els = new ArrayList<>();
        int LBL = id.i("font", "label"), DET = id.i("font", "detail");
        double cw = id.d("font", "char_width");
        els.add(text("title", id.i("margin"), id.i("margin"), sc.title, id.i("font", "title"), id.s("ink"), "left", cw));
        if (!sc.caption.isBlank()) els.add(text("caption", id.i("margin"), id.i("margin") + id.i("font", "title") + 12, sc.caption, id.i("font", "caption"), id.s("muted"), "left", cw));
        for (Box b : sc.lanes) {
            els.add(rect("g_" + b.id, b, id, b.emph, false));
            els.add(text("g_" + b.id + "#label", b.x + id.i("lane", "pad"), b.y + id.i("lane", "pad"), b.label, LBL, id.s("ink"), "left", cw));
            if (!b.detail.isEmpty())
                els.add(text("g_" + b.id + "#detail", b.x + id.i("lane", "pad"), b.y + id.i("lane", "pad") + LBL + 6, String.join("\n", b.detail), DET, id.s("muted"), "left", cw));
        }
        for (Box b : sc.nodes) {
            els.add(rect(b.id, b, id, b.emph, b.dashed));
            if (b.shape.equals("frame") || b.shape.equals("swatch")) continue;
            if (!b.head.isEmpty()) {
                int top = b.y + (b.h - blockH(b, LBL)) / 2;
                Map<String, Object> hd = text(b.id + "#label", b.x, top, String.join("\n", b.head), LBL, id.s("ink"), "center", cw);
                if (b.mono) hd.put("fontFamily", 3L);
                center(hd, b.x + b.w / 2);
                els.add(hd);
                if (!b.detail.isEmpty()) {
                    Map<String, Object> dt = text(b.id + "#detail", b.x, top + HP * b.head.size() - 1, String.join("\n", b.detail), DET, id.s("muted"), "center", cw);
                    if (b.monoN > 0) dt.put("fontFamily", 3L);
                    center(dt, b.x + b.w / 2);
                    els.add(dt);
                }
                continue;
            }
            if (b.shape.equals("diamond")) continue;
            boolean two = !b.detail.isEmpty();
            Map<String, Object> t = text(b.id + "#label", b.x, b.y + (two ? 10 : (b.h - (int) Math.round(LBL * 1.25)) / 2), b.label, LBL, id.s("ink"), "center", cw);
            center(t, b.x + b.w / 2);
            els.add(t);
            if (two) {
                Map<String, Object> d = text(b.id + "#detail", b.x, b.y + 10 + (int) Math.round(LBL * 1.25) + 4, b.detail.get(0), DET, id.s("muted"), "center", cw);
                center(d, b.x + b.w / 2);
                els.add(d);
            }
        }
        for (Ln l : sc.lines) {
            int x0 = l.pts.get(0)[0], y0 = l.pts.get(0)[1], minX = x0, maxX = x0, minY = y0, maxY = y0;
            List<Object> rel = new ArrayList<>();
            for (int[] p : l.pts) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                rel.add(List.of((long) (p[0] - x0), (long) (p[1] - y0)));
            }
            Map<String, Object> e = base("edge-" + l.id, "arrow", x0, y0, maxX - minX, maxY - minY, id.s("line"), "transparent", id.d("stroke"), l.dashed);
            e.put("points", rel);
            e.put("lastCommittedPoint", null);
            e.put("startBinding", null);
            e.put("endBinding", null);
            e.put("startArrowhead", null);
            e.put("endArrowhead", "arrow");
            e.put("roundness", null);
            els.add(e);
        }
        for (Txt t : sc.texts) {
            Map<String, Object> e = text(t.id, t.x, t.y - t.size, t.text, t.size, id.s("muted"), t.anchor.equals("start") ? "left" : t.anchor.equals("end") ? "right" : "center", cw);
            if (t.anchor.equals("middle")) center(e, t.x);
            else if (t.anchor.equals("end")) e.put("x", (long) (t.x - (Long) e.get("width")));
            els.add(e);
        }
        Map<String, Object> app = new TreeMap<>();
        app.put("viewBackgroundColor", id.s("background"));
        app.put("gridSize", null);
        Map<String, Object> doc = new TreeMap<>();
        doc.put("type", "excalidraw");
        doc.put("version", 2L);
        doc.put("source", "nerviz-diagrams");
        doc.put("elements", els);
        doc.put("appState", app);
        doc.put("files", new TreeMap<String, Object>());
        return Json.write(doc);
    }

    static Map<String, Object> base(String eid, String type, int x, int y, int w, int h, String stroke, String fill, double sw, boolean dashed) {
        Map<String, Object> e = new TreeMap<>();
        e.put("id", sid(eid));
        e.put("type", type);
        e.put("x", (long) x); e.put("y", (long) y); e.put("width", (long) w); e.put("height", (long) h);
        e.put("angle", 0L);
        e.put("strokeColor", stroke);
        e.put("backgroundColor", fill);
        e.put("fillStyle", "solid");
        e.put("strokeWidth", sw == Math.rint(sw) ? (Object) (long) sw : (Object) sw);
        e.put("strokeStyle", dashed ? "dashed" : "solid");
        e.put("roughness", 0L);
        e.put("opacity", 100L);
        e.put("groupIds", new ArrayList<>());
        e.put("frameId", null);
        e.put("roundness", null);
        e.put("seed", (long) hash31("seed:" + eid));
        e.put("version", 1L);
        e.put("versionNonce", (long) hash31("nonce:" + eid));
        e.put("isDeleted", false);
        e.put("boundElements", null);
        e.put("updated", 1L);
        e.put("link", null);
        e.put("locked", false);
        return e;
    }

    static Map<String, Object> rect(String eid, Box b, Identity id, boolean emph, boolean dashed) {
        boolean dia = b.shape.equals("diamond"), frame = b.shape.equals("frame");
        Map<String, Object> e = base(eid, dia ? "diamond" : "rectangle", b.x, b.y, b.w, b.h, b.stroke, frame ? "transparent" : b.fill, sw(b, id), dashed);
        if (frame) e.put("strokeStyle", "dotted");
        if (!dia) {
            Map<String, Object> r = new TreeMap<>();
            r.put("type", 3L);
            e.put("roundness", r);
        }
        return e;
    }

    static Map<String, Object> text(String eid, int x, int y, String t, int size, String color, String align, double cw) {
        String[] ls = t.split("\n");
        int w = 0;
        for (String l : ls) w = Math.max(w, (int) Math.round(l.length() * size * cw));
        int h = (int) Math.round(ls.length * size * 1.25);
        Map<String, Object> e = base(eid, "text", x, y, w, h, color, "transparent", 1.0, false);
        e.put("text", t);
        e.put("originalText", t);
        e.put("fontSize", (long) size);
        e.put("fontFamily", 2L);
        e.put("textAlign", align);
        e.put("verticalAlign", "top");
        e.put("containerId", null);
        e.put("lineHeight", 1.25);
        e.put("autoResize", true);
        return e;
    }

    static void center(Map<String, Object> t, int cx) {
        t.put("x", (long) (cx - ((Long) t.get("width")) / 2));
    }
}
