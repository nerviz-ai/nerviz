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

    // The step contract (references/step-contract.md): every skill a view names carries
    // `### <id> · <Name>` headings under `## Procedure`, each followed by a `**Runs:**` line.
    static final Pattern STEP_H = Pattern.compile("^### ([0-9]+[a-z]?|[a-z][a-z-]*) · (.+?)\\s*$");
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
        String alt = "(?:" + String.join("|", gates) + ")";
        String tail = "(?:skill|agent|cmd):[A-Za-z0-9._-]+";
        String ref = "(?:self|" + tail + ")(?:>" + tail + ")*";
        Pattern runs = Pattern.compile("^\\*\\*Runs:\\*\\* (" + ref + "(?:, " + ref + ")*)(?: · \\*\\*Depends on:\\*\\* (" + STEP_ID + "(?:, " + STEP_ID + ")*))?( · \\*\\*Conditional:\\*\\* yes)?"
                + "(?: · \\*\\*Gate:\\*\\* (" + alt + "(?:, " + alt + ")*))?\\s*$");
        Pattern hands = Pattern.compile("^\\*\\*Hands over:\\*\\* (`[^`]+`(?:, `[^`]+`)*)\\s*$");
        List<Object> nat = list(get(ext, "types", "skill", "native_commands"));
        for (String sk : viewSkills()) {
            String rel = ".claude/skills/" + sk + "/SKILL.md";
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p)) throw new Fail(rel + " is missing — a view under " + OUT + "/ names this skill, and its steps are parsed from it");
            List<String> ls = Files.readAllLines(p, StandardCharsets.UTF_8);
            int start = -1, end = ls.size();
            boolean fence = false;
            for (int n = 0; n < ls.size(); n++) {
                String l = ls.get(n);
                if (l.trim().startsWith("```")) { fence = !fence; continue; }
                if (fence) continue;
                if (start < 0) { if (l.startsWith("## Procedure")) start = n + 1; }
                else if (l.startsWith("## ")) { end = n; break; }
            }
            if (start < 0) throw new Fail(rel + " — no `## Procedure` heading; the steps of " + sk + " are read from it " + CONTRACT);
            List<Map<String, Object>> steps = new ArrayList<>();
            List<Integer> at = new ArrayList<>();
            Map<String, Object> hand = null;
            fence = false;
            for (int n = start; n < end; n++) {
                String l = ls.get(n);
                if (l.trim().startsWith("```")) { fence = !fence; continue; }
                if (fence) continue;
                if (l.startsWith("### Step "))
                    throw new Fail(rel + ":" + (n + 1) + " — '### Step <id>: …' is the retired grammar; write '### <id> · <Name>' and move 'depends on' into the **Runs:** line " + CONTRACT);
                if (l.startsWith("**Hands over:**"))
                    throw new Fail(rel + ":" + (n + 1) + " — **Hands over:** must be the next line after a step's **Runs:** line " + CONTRACT);
                if (!l.startsWith("### ") || !l.contains(" · ")) continue;
                Matcher h = STEP_H.matcher(l);
                if (!h.matches())
                    throw new Fail(rel + ":" + (n + 1) + " — step heading does not match '### <id> · <Name>' (id: digits and one optional letter, or lowercase words joined by hyphens): " + l + "\n   " + CONTRACT);
                String id = h.group(1), name = h.group(2).trim();
                int r = nextLine(ls, n + 1, end);
                if (r < 0 || !ls.get(r).startsWith("**Runs:**"))
                    throw new Fail(rel + ":" + (n + 1) + " — step " + id + " has no **Runs:** line under '" + l.trim() + "' " + CONTRACT);
                Matcher m = runs.matcher(ls.get(r));
                if (!m.matches())
                    throw new Fail(rel + ":" + (r + 1) + " — step " + id + ": **Runs:** does not match '**Runs:** <ref>[, <ref>] [· **Depends on:** <ids>] [· **Conditional:** yes] [· **Gate:** <" + String.join("|", gates) + ">[, …]]': " + ls.get(r) + "\n   " + CONTRACT);
                List<Object> chains = new ArrayList<>();
                for (String c : m.group(1).split(", ")) {
                    List<Object> chain = new ArrayList<>();
                    for (String seg : c.split(">")) {
                        String fid = seg.equals("self") ? "skill:" + sk : seg;
                        if (!f.containsKey(fid))
                            throw new Fail(rel + ":" + (r + 1) + " — step " + id + " runs '" + seg + "', which is no fact (nearby: " + nearby(fid, f) + ") " + CONTRACT);
                        chain.add(fid);
                    }
                    chains.add(chain);
                }
                List<String> deps = new ArrayList<>();
                if (m.group(2) != null) deps.addAll(Arrays.asList(m.group(2).split(", ")));
                List<Object> gs = new ArrayList<>();
                if (m.group(4) != null) gs.addAll(Arrays.asList(m.group(4).split(", ")));
                Map<String, Object> st = stepFact(sk, id, name, deps, m.group(3) != null, steps.size());
                st.put("runs", chains);
                st.put("gates", gs);
                steps.add(st);
                at.add(r + 1);
                int s = nextLine(ls, r + 1, end);
                if (s >= 0 && ls.get(s).startsWith("**Hands over:**")) {
                    Matcher hm = hands.matcher(ls.get(s));
                    if (!hm.matches())
                        throw new Fail(rel + ":" + (s + 1) + " — **Hands over:** does not match '**Hands over:** `<cmd>`[, `<cmd>`]': " + ls.get(s) + "\n   " + CONTRACT);
                    if (hand != null) throw new Fail(rel + ":" + (s + 1) + " — a second **Hands over:** line; a Procedure has at most one " + CONTRACT);
                    List<Object> cmds = new ArrayList<>();
                    for (String c : hm.group(1).split(", ")) {
                        String lit = c.substring(1, c.length() - 1);
                        if (lit.startsWith("/")) {
                            String nm = lit.substring(1).split("\\s+")[0];
                            if (!f.containsKey("skill:" + nm) && (nat == null || !nat.contains(nm)))
                                throw new Fail(rel + ":" + (s + 1) + " — '" + lit + "' is no skill or native command (a directory of .claude/skills/, or an entry of types.skill.native_commands in " + EXT + ") " + CONTRACT);
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
                    n = s;
                } else n = r;
            }
            if (steps.isEmpty())
                throw new Fail(rel + " — no '### <id> · <Name>' step heading under '## Procedure'; a view of " + sk + " has nothing to draw " + CONTRACT);
            Set<String> ids = new HashSet<>();
            for (var s : steps) ids.add((String) s.get("id"));
            for (int k = 0; k < steps.size(); k++)
                for (Object d : list(steps.get(k).get("depends_on"))) {
                    String did = ((String) d).substring(("step:" + sk + ":").length());
                    if (!ids.contains(did))
                        throw new Fail(rel + ":" + at.get(k) + " — step " + steps.get(k).get("id") + " depends on '" + did + "', which is no step heading " + CONTRACT);
                }
            for (var s : steps) f.put("step:" + sk + ":" + s.get("id"), s);
            if (hand != null) f.put("handoff:" + sk, hand);
        }
    }

    static int nextLine(List<String> ls, int from, int end) {
        for (int k = from; k < end; k++) if (!ls.get(k).isBlank()) return k;
        return -1;
    }

    static String nearby(String fid, Map<String, Object> f) {
        String kind = fid.substring(0, fid.indexOf(':') + 1);
        return f.keySet().stream().filter(k -> k.startsWith(kind)).limit(6).collect(Collectors.joining(", "));
    }

    static Map<String, Object> stepFact(String skill, String id, String name, List<String> deps, boolean cond, int order) {
        Map<String, Object> m = new TreeMap<>();
        m.put("kind", "step");
        m.put("skill", skill);
        m.put("id", id);
        m.put("name", name);
        m.put("label", id.equals("consolidation") ? "Consolidation" : id + " · " + name);
        m.put("detail", cond ? "conditional" : "");
        m.put("conditional", cond);
        m.put("order", (long) order);
        List<Object> d = new ArrayList<>();
        for (String x : deps) d.add("step:" + skill + ":" + x);
        m.put("depends_on", d);
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
        String name, kind, title, caption, skill;
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
        boolean stepped = r.kind.equals("pipeline") || r.kind.equals("orchestrator");
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
                String how = (String) e.get("derive"), want = r.kind.equals("orchestrator") ? "sequence" : "depends_on";
                if (!how.equals(want)) errs.add(ep + ": a " + r.kind + " view derives its edges by '" + want + "', not '" + how + "'. Fix: {\"derive\":\"" + want + "\"}.");
                else r.edges.addAll(deriveEdges(r, facts, how));
                continue;
            }
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
        Set<String> edgeIds = new LinkedHashSet<>();
        for (REdge e : r.edges) edgeIds.add(e.id());
        for (String key : new String[] { "steps", "emphasis" }) {
            List<Object> l = list(v.get(key));
            if (l == null) continue;
            for (int k = 0; k < l.size(); k++) {
                String id = (String) l.get(k);
                boolean ok = nodeIds.contains(id) || groupIds.contains(id) || (key.equals("steps") && edgeIds.contains(id))
                        || (key.equals("steps") && r.kind.equals("orchestrator") && id.equals("handoff:" + r.skill) && facts.containsKey(id));
                if (!ok) errs.add(rel + " $." + key + "[" + k + "]: '" + id + "' is not a node, group" + (key.equals("steps") ? " or edge" : "") + " of the view. Fix: remove it or cite an id the view has.");
                (key.equals("steps") ? r.steps : r.emphasis).add(id);
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

    /** `sequence`: each node to the next, in order. `depends_on`: edges among the view's nodes, with the edges a longer path already implies removed. */
    static List<REdge> deriveEdges(Resolved r, Map<String, Object> facts, String how) {
        List<String> nodes = r.groups.stream().flatMap(g -> g.nodes.stream()).collect(Collectors.toList());
        if (how.equals("sequence")) {
            List<REdge> seq = new ArrayList<>();
            for (int k = 1; k < nodes.size(); k++) {
                REdge e = new REdge();
                e.from = nodes.get(k - 1); e.to = nodes.get(k); e.evidence = nodes.get(k);
                seq.add(e);
            }
            return seq;
        }
        Set<String> in = new HashSet<>(nodes);
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (String n : nodes) {
            List<String> d = new ArrayList<>();
            List<Object> l = list(map(facts.get(n)).get("depends_on"));
            if (l != null) for (Object o : l) if (in.contains(o)) d.add((String) o);
            deps.put(n, d);
        }
        List<REdge> out = new ArrayList<>();
        for (String n : nodes) for (String d : deps.get(n)) {
            boolean implied = false;
            for (String o : deps.get(n)) if (!o.equals(d) && reaches(o, d, deps)) { implied = true; break; }
            if (implied) continue;
            REdge e = new REdge();
            e.from = d; e.to = n; e.evidence = n;
            out.add(e);
        }
        return out;
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

    // ───────────────────────────── scene ─────────────────────────────

    static final class Box {
        String id, label, role, fill, stroke, groupId, tool = ""; // tool: the label of the arrow that reaches a chip
        List<String> detail = new ArrayList<>();
        // A multi-line box (orchestrator view): bold `head` lines, then muted `detail` lines,
        // the first `monoN` of them in the monospace family; `mono` sets the head's.
        List<String> head = new ArrayList<>();
        int x, y, w, h, step = -1, rx = -1, monoN;
        boolean lane, dashed, emph, mono;
    }

    /** A line of free-standing text: x is its centre (anchor "middle") or its left edge ("start"), y its baseline. */
    static final class Txt {
        String id, text, anchor;
        int x, y, size, step = -1;
    }

    static final class Ln {
        String id, from, to;
        int x1, y1, x2, y2, step = -1;
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

        if (r.kind.equals("pipeline")) {
            List<String> ns = r.groups.stream().flatMap(g -> g.nodes.stream()).collect(Collectors.toList());
            Map<String, Integer> depth = new HashMap<>();
            for (String n : ns) depthOf(n, ns, facts, depth);
            int rows = depth.values().stream().max(Integer::compare).orElse(0) + 1;
            List<List<String>> byRow = new ArrayList<>();
            for (int k = 0; k < rows; k++) byRow.add(new ArrayList<>());
            for (String n : ns) byRow.get(depth.get(n)).add(n);
            int maxN = byRow.stream().mapToInt(List::size).max().orElse(1);
            int maxW = maxN * NW + (maxN - 1) * G;
            sc.w = maxW + 2 * M;
            for (int k = 0; k < rows; k++) {
                int rw = byRow.get(k).size() * NW + (byRow.get(k).size() - 1) * G;
                int x0 = M + (maxW - rw) / 2;
                for (int c = 0; c < byRow.get(k).size(); c++) {
                    String n = byRow.get(k).get(c);
                    Box b = nodeBox(n, facts, id);
                    b.x = x0 + c * (NW + G);
                    b.y = TOP + k * (NH + id.i("row_gap"));
                    b.w = NW; b.h = NH;
                    sc.nodes.add(b);
                    byId.put(n, b);
                }
            }
            sc.h = TOP + rows * NH + (rows - 1) * id.i("row_gap") + M;
        } else if (r.kind.equals("orchestrator")) {
            orchLayout(sc, r, facts, id, byId, stepOf);
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
        }
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
            if (a.y + a.h <= b.y) { l.x1 = a.x + a.w / 2; l.y1 = a.y + a.h; l.x2 = b.x + b.w / 2; l.y2 = b.y; }
            else if (b.y + b.h <= a.y) { l.x1 = a.x + a.w / 2; l.y1 = a.y; l.x2 = b.x + b.w / 2; l.y2 = b.y + b.h; }
            else if (a.x < b.x) { l.x1 = a.x + a.w; l.y1 = a.y + a.h / 2; l.x2 = b.x; l.y2 = b.y + b.h / 2; }
            else { l.x1 = a.x; l.y1 = a.y + a.h / 2; l.x2 = b.x + b.w; l.y2 = b.y + b.h / 2; }
            l.dashed = b.dashed;
            l.step = stepOf.getOrDefault(l.id, -1);
            sc.lines.add(l);
        }
        return sc;
    }

    // ── orchestrator: one row per step, top to bottom; to its right the chain of who runs it ──

    static final int HP = 16, DP = 13; // line pitch of a multi-line box's head and detail

    /** Height of the text block of a multi-line box. */
    static int blockH(Box b, int lbl) {
        return lbl + HP * (Math.max(1, b.head.size()) - 1) + (b.detail.isEmpty() ? 0 : 15 + DP * (b.detail.size() - 1)) + 3;
    }

    static int headBase(Box b, int k, int lbl) { return b.y + (b.h - blockH(b, lbl)) / 2 + lbl + HP * k; }

    static int detBase(Box b, int k, int lbl) { return b.y + (b.h - blockH(b, lbl)) / 2 + lbl + HP * (b.head.size() - 1) + 15 + DP * k; }

    /** Smallest box height that holds the text block, on the grid, at least `min`. */
    static int fit(Box b, int lbl, int min, int grid) { return Math.max(min, up(blockH(b, lbl) + 16, grid)); }

    static Txt txt(String tid, String text, int x, int y, int size, String anchor, int step) {
        Txt t = new Txt();
        t.id = tid; t.text = text; t.x = x; t.y = y; t.size = size; t.anchor = anchor; t.step = step;
        return t;
    }

    static Ln ln(String from, String to, int x1, int y1, int x2, int y2, boolean dashed, int step) {
        Ln l = new Ln();
        l.id = from + ">" + to; l.from = from; l.to = to;
        l.x1 = x1; l.y1 = y1; l.x2 = x2; l.y2 = y2; l.dashed = dashed; l.step = step;
        return l;
    }

    static void orchLayout(Scene sc, Resolved r, Map<String, Object> facts, Identity id, Map<String, Box> byId, Map<String, Integer> stepOf) {
        int NH = id.i("node", "h"), M = id.i("margin"), TOP = id.i("top"), GRID = id.i("grid");
        int LBL = id.i("font", "label"), DET = id.i("font", "detail");
        double cw = id.d("font", "char_width");
        int SW = id.i("orchestrator", "step", "w"), CW = id.i("orchestrator", "chip", "w"), CMIN = id.i("orchestrator", "chip", "min_h"), CG = id.i("orchestrator", "chip", "gap");
        int AL = id.i("orchestrator", "arrow"), BS = id.i("orchestrator", "badge", "size"), BG = id.i("orchestrator", "badge", "gap"), RG = id.i("orchestrator", "row_gap");
        String mono = id.s("orchestrator", "mono");
        List<String> ns = r.groups.stream().flatMap(g -> g.nodes.stream()).collect(Collectors.toList());
        int maxGates = 0, maxChain = 1;
        for (String n : ns) {
            maxGates = Math.max(maxGates, list(map(facts.get(n)).get("gates")).size());
            for (Object c : list(map(facts.get(n)).get("runs"))) maxChain = Math.max(maxChain, list(c).size());
        }
        int strip = maxGates == 0 ? 0 : maxGates * (BS + BG) + BG;
        int x0 = M + SW + strip + AL;
        int y = TOP;
        Box last = null;
        Set<String> used = new TreeSet<>();
        for (String n : ns) {
            Map<String, Object> sf = map(facts.get(n));
            int st = stepOf.getOrDefault(n, -1);
            Box sb = nodeBox(n, facts, id);
            sb.head = wrap(sb.label, (int) ((SW - 16) / (LBL * cw)));
            sb.w = SW;
            sb.h = fit(sb, LBL, NH, GRID);
            // chips: one chain per row of the step, one chip per segment
            List<List<Box>> chains = new ArrayList<>();
            int total = 0;
            for (int ci = 0; ci < list(sf.get("runs")).size(); ci++) {
                List<Box> chain = new ArrayList<>();
                List<Object> segs = list(list(sf.get("runs")).get(ci));
                for (int si = 0; si < segs.size(); si++) {
                    Box c = chip((String) segs.get(si), r.skill, facts, id, cw);
                    c.id = "chip:" + n + ":" + ci + "." + si;
                    c.w = CW;
                    c.h = fit(c, LBL, CMIN, GRID);
                    c.step = st;
                    chain.add(c);
                }
                chains.add(chain);
                total += chain.stream().mapToInt(c -> c.h).max().orElse(0) + (ci > 0 ? CG : 0);
            }
            int rowH = Math.max(sb.h, total);
            sb.x = M;
            sb.y = y + (rowH - sb.h) / 2;
            sc.nodes.add(sb);
            byId.put(n, sb);
            int sy = sb.y + sb.h / 2, cy = y + (rowH - total) / 2;
            for (int ci = 0; ci < chains.size(); ci++) {
                List<Box> chain = chains.get(ci);
                int chainH = chain.stream().mapToInt(c -> c.h).max().orElse(0);
                Box prev = null;
                for (int si = 0; si < chain.size(); si++) {
                    Box c = chain.get(si);
                    c.x = x0 + si * (CW + AL);
                    c.y = cy + (chainH - c.h) / 2;
                    sc.nodes.add(c);
                    byId.put(c.id, c);
                    int x1 = prev == null ? sb.x + sb.w : prev.x + prev.w, y1 = prev == null ? sy : prev.y + prev.h / 2, y2 = c.y + c.h / 2;
                    sc.lines.add(ln(prev == null ? n : prev.id, c.id, x1, y1, c.x, y2, false, st));
                    String tool = c.tool;
                    if (!tool.isEmpty()) {
                        int lx = c.x - AL / 2;
                        int ly = y1 + (int) Math.round((double) (y2 - y1) * (lx - x1) / (c.x - x1)) - 6;
                        sc.texts.add(txt("tool:" + c.id, tool, lx, ly, DET, "middle", st));
                    }
                    prev = c;
                }
                cy += chainH + CG;
            }
            // gate badges, one per gate word, on the step's right edge
            List<Object> gs = list(sf.get("gates"));
            for (int k = 0; k < gs.size(); k++) {
                String w = (String) gs.get(k);
                used.add(w);
                Box g = badge(w, id);
                g.id = "badge:" + n + ":" + k;
                g.x = sb.x + sb.w + BG + k * (BS + BG);
                g.y = sy - BS / 2;
                g.step = st;
                sc.nodes.add(g);
            }
            y += rowH + RG;
            last = sb;
        }
        // the printed handoff, dashed, last
        Map<String, Object> hf = map(facts.get("handoff:" + r.skill));
        if (hf != null && last != null) {
            Box hb = nodeBox("handoff:" + r.skill, facts, id);
            hb.dashed = true;
            hb.head = List.of(hb.label);
            hb.detail = new ArrayList<>();
            for (Object c : list(hf.get("commands"))) hb.detail.add((String) c);
            hb.monoN = hb.detail.size();
            hb.detail.add(id.s("orchestrator", "handoff_caption"));
            hb.x = M; hb.w = SW; hb.y = y;
            hb.h = fit(hb, LBL, NH, GRID);
            sc.nodes.add(hb);
            byId.put(hb.id, hb);
            Box after = byId.get((String) hf.get("after"));
            if (after != null)
                sc.lines.add(ln(after.id, hb.id, after.x + after.w / 2, after.y + after.h, hb.x + hb.w / 2, hb.y, true, stepOf.getOrDefault(hb.id, -1)));
            y += hb.h + RG;
        }
        // a legend of the gate words this view uses, in the vocabulary's order
        int lx = M;
        int ly = y + 8;
        for (String w : gateWords(id)) {
            if (!used.contains(w)) continue;
            Box g = badge(w, id);
            g.id = "legend:" + w;
            g.x = lx; g.y = ly;
            sc.nodes.add(g);
            sc.texts.add(txt("legend-text:" + w, w, lx + BS + 6, ly + BS / 2 + DET / 2 - 1, DET, "start", -1));
            lx += BS + 6 + (int) Math.round(w.length() * DET * cw) + 24;
        }
        sc.w = Math.max(x0 + maxChain * CW + (maxChain - 1) * AL, lx) + M;
        sc.h = ly + (used.isEmpty() ? -8 : BS) + M;
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
        boolean self = fid.equals("skill:" + orchestrator);
        Box c = new Box();
        c.label = (String) f.get("label");
        c.head.add(c.label);
        if ("skill".equals(kind) && "fork".equals(f.get("context"))) c.head.add(id.s("orchestrator", "fork_marker"));
        c.mono = "cmd".equals(kind);
        c.tool = self ? id.s("orchestrator", "tools", "self")
                : id.s("orchestrator", "tools", "skill".equals(kind) && "fork".equals(f.get("context")) ? "skill_fork" : kind);
        c.role = id.role(fid, f);
        c.fill = id.fill(c.role);
        c.stroke = id.stroke(c.role);
        if (self) c.detail.add(id.s("orchestrator", "self_detail"));
        if (f.get("write_allow") instanceof List<?>)
            c.detail.addAll(wrap(territory(list(f.get("write_allow"))), (int) ((id.i("orchestrator", "chip", "w") - 16) / (id.i("font", "detail") * cw))));
        return c;
    }


    static int depthOf(String n, List<String> in, Map<String, Object> facts, Map<String, Integer> memo) {
        if (memo.containsKey(n)) return memo.get(n);
        int d = 0;
        List<Object> l = list(map(facts.get(n)).get("depends_on"));
        if (l != null) for (Object o : l) if (in.contains(o)) d = Math.max(d, depthOf((String) o, in, facts, memo) + 1);
        memo.put(n, d);
        return d;
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

    static String svg(Scene sc, Identity id, boolean animated) {
        int LBL = id.i("font", "label"), DET = id.i("font", "detail");
        double rev = id.d("animation", "reveal_seconds"), stepS = id.d("animation", "step_seconds");
        StringBuilder o = new StringBuilder();
        o.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        o.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(sc.w).append("\" height=\"").append(sc.h)
                .append("\" viewBox=\"0 0 ").append(sc.w).append(' ').append(sc.h).append("\" font-family=\"").append(esc(id.s("font", "family"))).append("\">\n");
        o.append("<title>").append(esc(sc.title)).append("</title>\n");
        o.append("<defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto-start-reverse\">")
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
            o.append(open("g", b.id, b.step, animated, stepS));
            o.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h)
                    .append("\" rx=\"").append(id.i("radius")).append("\" fill=\"").append(b.fill).append("\" fill-opacity=\"0.45\" stroke=\"").append(b.stroke)
                    .append("\" stroke-width=\"").append(num(b.emph ? id.d("emphasis_stroke") : id.d("stroke"))).append("\"/>\n");
            int pad = id.i("lane", "pad");
            o.append("<text x=\"").append(b.x + pad).append("\" y=\"").append(b.y + pad + LBL).append("\" font-size=\"").append(LBL)
                    .append("\" font-weight=\"700\" fill=\"").append(id.s("ink")).append("\">").append(esc(b.label)).append("</text>\n");
            for (int k = 0; k < b.detail.size(); k++)
                o.append("<text x=\"").append(b.x + pad).append("\" y=\"").append(b.y + pad + LBL + 16 + k * 15).append("\" font-size=\"").append(DET)
                        .append("\" fill=\"").append(id.s("muted")).append("\">").append(esc(b.detail.get(k))).append("</text>\n");
            o.append("</g>\n");
        }
        for (Ln l : sc.lines) {
            o.append(open("g", "edge-" + l.id, l.step, animated, stepS));
            o.append("<line x1=\"").append(l.x1).append("\" y1=\"").append(l.y1).append("\" x2=\"").append(l.x2).append("\" y2=\"").append(l.y2)
                    .append("\" stroke=\"").append(id.s("line")).append("\" stroke-width=\"").append(num(id.d("stroke"))).append('"')
                    .append(l.dashed ? " stroke-dasharray=\"6 4\"" : "").append(" marker-end=\"url(#arrow)\"/>\n");
            o.append("</g>\n");
        }
        String mono = get(id.m, "orchestrator", "mono") instanceof String t ? t : "";
        for (Box b : sc.nodes) {
            o.append(open("g", b.id, b.step, animated, stepS));
            if (!b.head.isEmpty()) {
                o.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h)
                        .append("\" rx=\"").append(b.rx >= 0 ? b.rx : id.i("radius")).append("\" fill=\"").append(b.fill).append("\" stroke=\"").append(b.stroke)
                        .append("\" stroke-width=\"").append(num(b.emph ? id.d("emphasis_stroke") : id.d("stroke"))).append('"')
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
                    .append("\" stroke-width=\"").append(num(b.emph ? id.d("emphasis_stroke") : id.d("stroke"))).append('"')
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
            o.append(open("g", t.id, t.step, animated, stepS));
            o.append("<text x=\"").append(t.x).append("\" y=\"").append(t.y).append("\" font-size=\"").append(t.size).append("\" text-anchor=\"").append(t.anchor)
                    .append("\" fill=\"").append(id.s("muted")).append("\">").append(esc(t.text)).append("</text>\n");
            o.append("</g>\n");
        }
        return o.append("</svg>\n").toString();
    }

    static String open(String tag, String id, int step, boolean animated, double stepS) {
        String a = "<" + tag + " id=\"" + sid(id) + "\"";
        if (animated && step >= 0) a += " class=\"r\" style=\"animation-delay:" + String.format(Locale.ROOT, "%.2f", step * stepS) + "s\"";
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
                    + ";strokeWidth=" + num(b.emph ? id.d("emphasis_stroke") : id.d("stroke")) + ";", b.x, b.y, b.w, b.h));
        }
        String monoFace = get(id.m, "orchestrator", "mono") instanceof String t ? t.split(",")[0].trim().replace("'", "") : "";
        for (Box b : sc.nodes) {
            if (!b.head.isEmpty()) {
                String face = "<font face=\"" + monoFace + "\">";
                String hv = b.head.stream().map(h -> b.mono ? face + esc(h) + "</font>" : esc(h)).collect(Collectors.joining("<br/>"));
                StringBuilder dv = new StringBuilder();
                for (int k = 0; k < b.detail.size(); k++)
                    dv.append("<br/><font style=\"font-size:").append(id.i("font", "detail")).append("px\" color=\"").append(id.s("muted")).append('"')
                            .append(k < b.monoN ? " face=\"" + monoFace + "\"" : "").append('>').append(esc(b.detail.get(k))).append("</font>");
                o.append(cell("n_" + sid(b.id), "<b>" + hv + "</b>" + dv, "rounded=1;arcSize=" + (int) Math.round(100.0 * (b.rx >= 0 ? b.rx : id.i("radius")) / b.h) + ";html=1;whiteSpace=wrap;fontSize=" + id.i("font", "label") + ";fontFamily=" + font
                        + ";fontColor=" + id.s("ink") + ";fillColor=" + b.fill + ";strokeColor=" + b.stroke + ";strokeWidth=" + num(b.emph ? id.d("emphasis_stroke") : id.d("stroke"))
                        + (b.dashed ? ";dashed=1;dashPattern=6 4" : "") + ";", b.x, b.y, b.w, b.h));
                continue;
            }
            String v = "<b>" + esc(b.label) + "</b>" + b.detail.stream().map(d -> "<br/><font style=\"font-size:" + id.i("font", "detail") + "px\" color=\"" + id.s("muted") + "\">" + esc(d) + "</font>").collect(Collectors.joining());
            o.append(cell("n_" + sid(b.id), v, "rounded=1;arcSize=" + (int) Math.round(100.0 * id.i("radius") / b.h) + ";html=1;whiteSpace=wrap;fontSize=" + id.i("font", "label") + ";fontFamily=" + font
                    + ";fontColor=" + id.s("ink") + ";fillColor=" + b.fill + ";strokeColor=" + b.stroke + ";strokeWidth=" + num(b.emph ? id.d("emphasis_stroke") : id.d("stroke"))
                    + (b.dashed ? ";dashed=1;dashPattern=6 4" : "") + ";", b.x, b.y, b.w, b.h));
        }
        for (Ln l : sc.lines) {
            String src = (sc.lanes.stream().anyMatch(b -> b.id.equals(l.from)) ? "g_" : "n_") + sid(l.from);
            String dst = (sc.lanes.stream().anyMatch(b -> b.id.equals(l.to)) ? "g_" : "n_") + sid(l.to);
            o.append("        <mxCell id=\"e_").append(sid(l.id)).append("\" value=\"\" style=\"edgeStyle=none;html=1;endArrow=block;endFill=1;strokeColor=").append(id.s("line"))
                    .append(";strokeWidth=").append(num(id.d("stroke"))).append(l.dashed ? ";dashed=1" : "").append(";\" edge=\"1\" parent=\"1\" source=\"").append(src).append("\" target=\"").append(dst)
                    .append("\">\n          <mxGeometry relative=\"1\" as=\"geometry\"/>\n        </mxCell>\n");
        }
        for (Txt t : sc.texts) {
            int tw = (int) Math.round(t.text.length() * t.size * id.d("font", "char_width"));
            o.append(cell("t_" + sid(t.id), t.text, "text;html=1;align=" + (t.anchor.equals("start") ? "left" : "center") + ";verticalAlign=middle;whiteSpace=nowrap;fontSize=" + t.size + ";fontFamily=" + font
                    + ";fontColor=" + id.s("muted") + ";", t.anchor.equals("start") ? t.x : t.x - tw / 2, t.y - t.size, tw, t.size + 4));
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
            Map<String, Object> e = base("edge-" + l.id, "arrow", l.x1, l.y1, Math.abs(l.x2 - l.x1), Math.abs(l.y2 - l.y1), id.s("line"), "transparent", id.d("stroke"), l.dashed);
            e.put("points", List.of(List.of(0L, 0L), List.of((long) (l.x2 - l.x1), (long) (l.y2 - l.y1))));
            e.put("lastCommittedPoint", null);
            e.put("startBinding", null);
            e.put("endBinding", null);
            e.put("startArrowhead", null);
            e.put("endArrowhead", "arrow");
            e.put("roundness", null);
            els.add(e);
        }
        for (Txt t : sc.texts) {
            Map<String, Object> e = text(t.id, t.x, t.y - t.size, t.text, t.size, id.s("muted"), t.anchor.equals("start") ? "left" : "center", cw);
            if (!t.anchor.equals("start")) center(e, t.x);
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
        Map<String, Object> e = base(eid, "rectangle", b.x, b.y, b.w, b.h, b.stroke, b.fill, emph ? id.d("emphasis_stroke") : id.d("stroke"), dashed);
        Map<String, Object> r = new TreeMap<>();
        r.put("type", 3L);
        e.put("roundness", r);
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
