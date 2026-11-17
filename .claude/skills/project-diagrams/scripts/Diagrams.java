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

    static final String CMD = "java .claude/skills/project-diagrams/scripts/Diagrams.java";
    static final String SKILL = ".claude/skills/project-diagrams";
    static final String OUT = "docs/diagrams";
    static final String EXT = ".claude/schemas/extensions.json";
    static final String SCHEMA = SKILL + "/references/view.schema.json";
    static final String HOOK = ".claude/hooks/ArchHook.java";

    static Path root;
    static boolean projectSource;
    static final Set<String> PROJECT_KINDS = Set.of("architecture", "blueprint", "usecase-map", "usecase-flow");

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
        String srcArg = null;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("--root")) {
                if (i + 1 >= a.length) return usage("--root needs a directory");
                rootArg = a[++i];
            } else if (a[i].equals("--source")) {
                if (i + 1 >= a.length || !(a[i + 1].equals("project") || a[i + 1].equals("harness"))) return usage("--source is project or harness");
                srcArg = a[++i];
            } else if (mode == null) mode = a[i];
            else names.add(a[i]);
        }
        if (mode == null) return usage("no mode");
        root = Paths.get(rootArg == null || rootArg.isBlank() ? "." : rootArg).toAbsolutePath().normalize();
        // default: the harness where extensions.json still has its `export` block (this repository), else the project
        if (srcArg == null) {
            Path ex = root.resolve(EXT);
            srcArg = Files.isRegularFile(ex) && get(Json.parse(Files.readString(ex, StandardCharsets.UTF_8)), "export") != null ? "harness" : "project";
        }
        projectSource = srcArg.equals("project");
        return switch (mode) {
            case "facts" -> modeFacts();
            case "validate" -> modeValidate(names);
            case "render" -> modeRender(names);
            case "check" -> modeCheck(names);
            case "legible" -> modeLegible(names);
            default -> usage("unknown mode '" + mode + "'");
        };
    }

    static int usage(String why) {
        System.err.println("❌ " + why + "\nUsage: " + CMD + " facts|validate|render|check [view...] | legible <file.svg> [--source project|harness] [--root <dir>]");
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
        Map<String, String> drawn = new TreeMap<>();
        for (String v : views) {
            Resolved r = resolve(v, facts, errs);
            if (r != null) drawn.putAll(renderAll(r, facts, idn));
        }
        if (!errs.isEmpty()) return report(errs);
        List<String> ill = legibilityOf(drawn);
        if (!ill.isEmpty()) return reportIllegible(ill);
        System.out.println("✅ " + views.size() + " view(s) valid; every fact id and selector resolves against " + facts.size() + " facts.");
        return 0;
    }

    static int modeRender(List<String> names) throws IOException {
        Map<String, Object> facts = extractFacts();
        Identity idn = Identity.load();
        List<String> errs = new ArrayList<>();
        if (projectSource) ensureProjectViews(names, facts);
        List<String> views = viewNames(names, errs);
        Map<String, String> files = new TreeMap<>();
        for (String v : views) {
            Resolved r = resolve(v, facts, errs);
            if (r != null) files.putAll(renderAll(r, facts, idn));
        }
        if (!errs.isEmpty()) return report(errs);
        List<String> ill = legibilityOf(files);
        if (!ill.isEmpty()) return reportIllegible(ill);
        for (var e : files.entrySet()) {
            Path p = root.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            write(p, e.getValue());
        }
        if (projectSource) {
            String mf = String.valueOf(dgBlock().get("inputs_manifest"));
            write(root.resolve(mf), inputsManifest());
            System.out.println("   " + mf + " — the inputs these views were drawn from");
        }
        System.out.println("✅ " + files.size() + " file(s) rendered from " + views.size() + " view(s):");
        for (String k : files.keySet()) System.out.println("   " + k);
        return 0;
    }

    /** `legible <file.svg>`: the four legibility findings of one SVG, whoever drew it. Exit 2 on a finding. */
    static int modeLegible(List<String> names) throws IOException {
        if (names.size() != 1) return usage("legible takes one .svg file");
        Identity.load();
        Path f = Paths.get(names.get(0));
        if (!Files.isRegularFile(f)) return usage(names.get(0) + " is not a file");
        List<String> ill = legibility(f.getFileName().toString(), norm(Files.readString(f, StandardCharsets.UTF_8)));
        if (!ill.isEmpty()) return reportIllegible(ill);
        System.out.println("✅ " + f.getFileName() + " has no label over a label, a shape's outline or a line, and none outside its viewBox.");
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
        List<String> ill = legibilityOf(fresh);
        if (!ill.isEmpty()) return reportIllegible(ill);
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
            String leaf = view.substring(view.lastIndexOf('/') + 1);
            String src = n.endsWith(".png") ? leaf + ".svg"
                    : (n.endsWith(".webm") || n.endsWith(".mp4")) ? leaf + ".animated.svg" : null;
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

    /** The legibility findings of every static SVG among the rendered files. */
    static List<String> legibilityOf(Map<String, String> files) {
        List<String> out = new ArrayList<>();
        for (var e : files.entrySet()) {
            String k = e.getKey();
            if (!k.endsWith(".svg") || k.endsWith(".animated.svg")) continue;
            out.addAll(legibility(k.substring(0, k.length() - 4), e.getValue()));
        }
        return out;
    }

    static int reportIllegible(List<String> ill) {
        System.err.println("❌ " + ill.size() + " legibility finding(s) — a label that collides cannot be read, whatever the facts say:");
        for (String s : ill) System.err.println("   " + s);
        System.err.println("Fix the layout (relayout, wrap, a wider gap), never by deleting a fact.");
        return 2;
    }

    static int report(List<String> errs) {
        System.err.println("❌ " + errs.size() + " problem(s):");
        for (String e : errs) System.err.println("   " + e);
        return 2;
    }

    /** A view argument of a project: architecture, blueprint, map, or a use case folder name. */
    static String projectView(String a) {
        return a.equals("map") ? "use-cases/map" : a.startsWith("UC-") ? "use-cases/" + a : a;
    }

    static List<String> viewNames(List<String> given, List<String> errs) throws IOException {
        if (!given.isEmpty()) {
            if (!projectSource) return given;
            List<String> out = new ArrayList<>();
            for (String g : given) out.add(projectView(g));
            return out;
        }
        Path d = root.resolve(OUT);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        if (projectSource) {
            try (Stream<Path> w = Files.walk(d, 3)) {
                for (Path p : w.sorted().collect(Collectors.toList()))
                    if (p.getFileName().toString().equals("view.json") && Files.isRegularFile(p))
                        out.add(d.relativize(p.getParent()).toString().replace('\\', '/'));
            }
            return out;
        }
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
        if (projectSource) return extractProject();
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
    // One quoted label, then `, label` repeats: a comma is required between two labels, so the
    // repetition has a single way to match and cannot backtrack exponentially (CodeQL java/redos).
    static final Pattern CASE = Pattern.compile("^(\\s*)case (\"[^\"]+\"(?:\\s*,\\s*\"[^\"]+\")*)\\s*->.*$");
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
            metrics = new Metrics(i);
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
        String name, kind, title, caption, skill, derive, style, uc;
        List<String> factIds = new ArrayList<>();
        List<RGroup> groups = new ArrayList<>();
        List<REdge> edges = new ArrayList<>();
        List<String> steps = new ArrayList<>(), emphasis = new ArrayList<>();
    }

    static Resolved resolve(String name, Map<String, Object> facts, List<String> errs) throws IOException {
        if (projectSource) return resolveProject(name, facts, errs);
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

        if (!v.containsKey("groups")) { errs.add(rel + ": a " + v.get("kind") + " view requires groups."); return null; }
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
        if (facts.containsKey(sel)) { out.add(sel); return out; }
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
        // project views: shape "plain" (rect), "pill", "none" (no outline); the texts and the icon drawn inside the same group; a badge is the one declared legibility exception
        List<Txt> inner = new ArrayList<>();
        String icoPath = "", icoFill = "", badge = ""; // badge: "" | "ok" | "bad"
        boolean icoStroke;
        int icoX, icoY, icoSize;
        double opacity = 1;
    }

    /** A line of free-standing text: x is its centre ("middle"), its left edge ("start") or its right edge ("end"), y its baseline. */
    static final class Txt {
        String id, text, anchor, color = "muted";
        int x, y, size, step = -1;
        double t = -1;
        boolean bold, mono;
    }

    static final class Ln {
        String id, from, to; // from/to: ids of boxes the line is tied to, null when it is not
        List<int[]> pts = new ArrayList<>();
        int step = -1;
        double t = -1;
        boolean dashed;
        String marker = "arrow"; // arrow | hollow | none
        String fan = "";         // edges that leave one source port may share their first segment: the same port id on each (declared, never by coordinates)
    }

    static final class Scene {
        int w, h;
        String title, caption;
        List<Box> lanes = new ArrayList<>(), nodes = new ArrayList<>();
        List<Ln> lines = new ArrayList<>();
        List<Txt> texts = new ArrayList<>();

        /** Every free text and every text drawn inside a box. */
        List<Txt> allTexts() {
            List<Txt> all = new ArrayList<>(texts);
            for (Box b : lanes) all.addAll(b.inner);
            for (Box b : nodes) all.addAll(b.inner);
            return all;
        }
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
        if (PROJECT_KINDS.contains(r.kind)) {
            try {
                return switch (r.kind) {
                    case "architecture" -> sceneArchitecture(r, facts, id);
                    case "blueprint" -> sceneBlueprint(r, facts, id);
                    case "usecase-map" -> sceneMap(r, facts, id);
                    default -> sceneFlow(r, facts, id);
                };
            } catch (IOException e) { throw new Fail(e.getMessage()); }
        }
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
                // the arrows from the step to its first chips start past the step's gate badges, which sit on its right edge (0211: no line over a glyph);
                // a tool label sits on its own arrow close to the chip, where the arrows of the other chips are furthest apart
                int sxl = sx + (s.badges.isEmpty() ? 0 : s.badges.size() * (BS + BG) + BG);
                if (multi) {
                    if (!firstChips.isEmpty()) {
                        Box c = firstChips.get(0);
                        double te = Double.MAX_VALUE;
                        for (int p = 0; p < P; p++) if (!s.phases.get(p).isEmpty()) { te = tp[p]; break; }
                        sc.lines.add(ln("entry:" + s.fid, s.fid, c.id, te, false, sxl, sy, c.x, c.y + c.h / 2));
                    }
                } else {
                    int top2 = Integer.MAX_VALUE;
                    for (Box c : firstChips) top2 = Math.min(top2, c.y + c.h / 2);
                    for (Box c : firstChips) {
                        int y2 = c.y + c.h / 2;
                        sc.lines.add(ln("entry:" + c.id, s.fid, c.id, tp[0], false, sxl, sy, c.x, y2));
                        if (!c.tool.isEmpty()) {
                            int lx = c.x - 6 - (int) Math.ceil(metrics.width(c.tool, DET, false, false) / 2);
                            boolean above = y2 == top2; // the uppermost arrow's label goes over it, every other one under its own
                            sc.texts.add(txt("tool:" + c.id, c.tool, lx, sy + (int) Math.round((double) (y2 - sy) * (lx - sxl) / (c.x - sxl)) + (above ? -10 : 17), DET, "middle", tp[0]));
                        }
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
                int gx = gutterBase + (k + 1) * LANE, cy = tm.y + tm.h / 2, ay = tg.y - (r.derive.equals("depends_on") ? RGV : RG) / 2, tx = tg.x + tg.w / 2 + 14; // beside the centre: the step's own arrow enters at the centre (0211: no two edges on one line)
                int ey = tg.shape.equals("diamond") ? tg.y + (int) Math.round(14.0 / (tg.w / 2.0) * (tg.h / 2.0)) : tg.y;
                sc.lines.add(ln("skip:" + tm.id, tm.id, tg.id, stepT.get(s.target), false, tm.x + tm.w, cy, gx, cy, gx, ay, tx, ay, tx, ey));
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
            c.detail.addAll(metrics.wrap(text, id.i("orchestrator", "chip", "w") - 20, 6, id.i("font", "detail"), false, false));
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
        String base = OUT + "/" + r.name + "/" + r.name.substring(r.name.lastIndexOf('/') + 1);
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
                .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"").append(id.s("line")).append("\"/></marker>")
                .append(sc.lines.stream().anyMatch(l -> l.marker.equals("hollow"))
                        ? "<marker id=\"hollow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto\"><path d=\"M1,1 L9,5 L1,9 z\" fill=\"" + id.s("background") + "\" stroke=\"" + id.s("line") + "\" stroke-width=\"1.2\"/></marker>" : "")
                .append("</defs>\n");
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
            o.append(l.fan.isEmpty() ? open("g", "edge-" + l.id, l.t, animated) : open("g", "edge-" + l.id, l.t, animated).replace(">\n", " data-fan=\"" + esc(l.fan) + "\">\n"));
            String tail = "\" stroke=\"" + id.s("line") + "\" stroke-width=\"" + num(id.d("stroke")) + "\"" + (l.dashed ? " stroke-dasharray=\"6 4\"" : "") + (l.marker.equals("none") ? "" : " marker-end=\"url(#" + (l.marker.equals("hollow") ? "hollow" : "arrow") + ")\"") + "/>\n";
            if (l.pts.size() == 2) {
                int[] a = l.pts.get(0), z = l.pts.get(1);
                o.append("<line x1=\"").append(a[0]).append("\" y1=\"").append(a[1]).append("\" x2=\"").append(z[0]).append("\" y2=\"").append(z[1]).append(tail);
            } else o.append("<polyline fill=\"none\" points=\"").append(pts(l.pts)).append(tail);
            o.append("</g>\n");
        }
        String mono = get(id.m, "orchestrator", "mono") instanceof String t ? t : "";
        for (Box b : sc.nodes) {
            if (b.shape.equals("badge")) {
                o.append("<g id=\"").append(sid(b.id)).append("\" data-badge=\"code-resolved\">\n");
                String col = b.badge.equals("ok") ? id.s("palette", "procedure", "stroke") : id.s("palette", "drift", "stroke");
                o.append("<circle cx=\"").append(b.x).append("\" cy=\"").append(b.y).append("\" r=\"").append(b.w).append("\" fill=\"").append(col).append("\"/>\n");
                int r = b.w;
                o.append("<path d=\"").append(b.badge.equals("ok")
                        ? "M" + (b.x - r / 2) + "," + b.y + " l" + (r / 3) + "," + (r / 3) + " l" + (r / 2) + "," + (-r / 2)
                        : "M" + (b.x - r / 3) + "," + (b.y - r / 3) + " l" + (2 * r / 3) + "," + (2 * r / 3) + " M" + (b.x + r / 3) + "," + (b.y - r / 3) + " l" + (-2 * r / 3) + "," + (2 * r / 3))
                        .append("\" fill=\"none\" stroke=\"").append(id.s("background")).append("\" stroke-width=\"2\"/>\n</g>\n");
                continue;
            }
            o.append(open("g", b.id, b.t, animated));
            if (b.shape.equals("plain") || b.shape.equals("pill") || b.shape.equals("none")) {
                if (!b.shape.equals("none"))
                    o.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w).append("\" height=\"").append(b.h)
                            .append("\" rx=\"").append(b.shape.equals("pill") ? b.h / 2 : b.rx >= 0 ? b.rx : id.i("radius")).append("\" fill=\"").append(b.fill).append("\" stroke=\"").append(b.stroke)
                            .append("\" stroke-width=\"").append(num(sw(b, id))).append('"').append(b.dashed ? " stroke-dasharray=\"6 4\"" : "")
                            .append(b.opacity < 1 ? " opacity=\"" + num(b.opacity) + "\"" : "").append("/>\n");
                icoEl(o, b, id);
                for (Txt t : b.inner) o.append(txtEl(t, id));
                o.append("</g>\n");
                continue;
            }
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
                for (Txt t : b.inner) o.append(txtEl(t, id));
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
            o.append(txtEl(t, id));
            o.append("</g>\n");
        }
        return o.append("</svg>\n").toString();
    }

    static String colorOf(String c, Identity id) { return c.startsWith("#") ? c : id.s(c); }

    /** One `<text>`: muted, regular, in the sans family unless the Txt says otherwise. */
    static String txtEl(Txt t, Identity id) {
        String mono = get(id.m, "orchestrator", "mono") instanceof String m ? m : "";
        return "<text x=\"" + t.x + "\" y=\"" + t.y + "\" font-size=\"" + t.size + "\"" + (t.bold ? " font-weight=\"600\"" : "") + " text-anchor=\"" + t.anchor + "\""
                + (t.mono ? " font-family=\"" + esc(mono) + "\"" : "") + " fill=\"" + colorOf(t.color, id) + "\">" + esc(t.text) + "</text>\n";
    }

    /** The icon of a box: one path of a 24-unit viewBox, scaled to its size, its bounding box declared for the legibility check. */
    static void icoEl(StringBuilder o, Box b, Identity id) {
        if (b.icoPath.isEmpty()) return;
        double k = b.icoSize / 24.0;
        o.append("<path data-box=\"").append(b.icoX).append(',').append(b.icoY).append(',').append(b.icoSize).append(',').append(b.icoSize)
                .append("\" transform=\"translate(").append(b.icoX).append(' ').append(b.icoY).append(") scale(").append(num(k)).append(")\" d=\"").append(b.icoPath).append('"')
                .append(b.icoStroke ? " fill=\"none\" stroke=\"" + b.icoFill + "\" stroke-width=\"" + num(1.5 / k) + "\" stroke-linecap=\"round\" stroke-linejoin=\"round\""
                        : " fill=\"" + b.icoFill + "\"").append(b.opacity < 1 ? " opacity=\"" + num(b.opacity) + "\"" : "").append("/>\n");
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
            if (b.shape.equals("badge")) {
                String col = b.badge.equals("ok") ? id.s("palette", "procedure", "stroke") : id.s("palette", "drift", "stroke");
                o.append(cell("n_" + sid(b.id), b.badge.equals("ok") ? "✓" : "✗", "ellipse;html=1;fontSize=10;fontColor=#FFFFFF;fontStyle=1;fillColor=" + col + ";strokeColor=" + col + ";", b.x - b.w, b.y - b.w, 2 * b.w, 2 * b.w));
                continue;
            }
            if (b.shape.equals("plain") || b.shape.equals("pill") || b.shape.equals("none")) {
                o.append(cell("n_" + sid(b.id), "", b.shape.equals("none") ? "rounded=0;html=1;fillColor=none;strokeColor=none;"
                        : "rounded=1;arcSize=" + (int) Math.round(100.0 * (b.shape.equals("pill") ? b.h / 2.0 : b.rx >= 0 ? b.rx : id.i("radius")) / Math.min(b.w, b.h)) + ";html=1;fillColor=" + (b.fill.equals("none") ? "none" : b.fill)
                        + ";strokeColor=" + b.stroke + ";strokeWidth=" + num(sw(b, id)) + (b.dashed ? ";dashed=1;dashPattern=6 4" : "") + (b.opacity < 1 ? ";opacity=" + (int) Math.round(b.opacity * 100) : "") + ";", b.x, b.y, b.w, b.h));
                if (!b.icoPath.isEmpty())
                    o.append(cell("i_" + sid(b.id), "", "shape=image;html=1;imageAspect=1;image=data:image/svg+xml," + java.util.Base64.getEncoder().encodeToString(iconSvg(b).getBytes(StandardCharsets.UTF_8)) + ";", b.icoX, b.icoY, b.icoSize, b.icoSize));
                continue;
            }
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
            o.append("        <mxCell id=\"e_").append(sid(l.id)).append("\" value=\"\" style=\"edgeStyle=none;html=1;").append(l.marker.equals("none") ? "endArrow=none;" : l.marker.equals("hollow") ? "endArrow=block;endFill=0;" : "endArrow=block;endFill=1;").append("strokeColor=").append(id.s("line"))
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
        for (Txt t : sc.allTexts()) {
            int tw = (int) Math.round(metrics.width(t.text, t.size, t.bold, t.mono)) + 2;
            int tx = t.anchor.equals("start") ? t.x : t.anchor.equals("end") ? t.x - tw : t.x - tw / 2;
            o.append(cell("t_" + sid(t.id), t.text, "text;html=1;align=" + (t.anchor.equals("start") ? "left" : t.anchor.equals("end") ? "right" : "center") + ";verticalAlign=middle;whiteSpace=nowrap;fontSize=" + t.size + ";fontFamily=" + (t.mono ? monoFace : font) + (t.bold ? ";fontStyle=1" : "")
                    + ";fontColor=" + colorOf(t.color, id) + ";", tx, t.y - t.size, tw, t.size + 4));
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
        Map<String, Object> files = new TreeMap<>();
        for (Box b : sc.nodes) {
            if (b.shape.equals("badge")) {
                Map<String, Object> e = base("badge-" + b.id, "ellipse", b.x - b.w, b.y - b.w, 2 * b.w, 2 * b.w, b.badge.equals("ok") ? id.s("palette", "procedure", "stroke") : id.s("palette", "drift", "stroke"),
                        b.badge.equals("ok") ? id.s("palette", "procedure", "stroke") : id.s("palette", "drift", "stroke"), 1.0, false);
                els.add(e);
                continue;
            }
            if (b.shape.equals("plain") || b.shape.equals("pill") || b.shape.equals("none")) {
                if (!b.shape.equals("none")) els.add(rect(b.id, b, id, b.emph, b.dashed));
                if (!b.icoPath.isEmpty()) {
                    String fid = "icon-" + hash31(b.id + b.icoPath);
                    Map<String, Object> e = base("img-" + b.id, "image", b.icoX, b.icoY, b.icoSize, b.icoSize, "transparent", "transparent", 1.0, false);
                    e.put("fileId", fid); e.put("status", "saved"); e.put("scale", List.of(1L, 1L));
                    els.add(e);
                    Map<String, Object> fe = new TreeMap<>();
                    fe.put("id", fid); fe.put("mimeType", "image/svg+xml"); fe.put("created", 1L); fe.put("lastRetrieved", 1L);
                    fe.put("dataURL", "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(iconSvg(b).getBytes(StandardCharsets.UTF_8)));
                    files.put(fid, fe);
                }
                continue;
            }
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
            e.put("endArrowhead", l.marker.equals("none") ? null : l.marker.equals("hollow") ? "triangle_outline" : "arrow");
            e.put("roundness", null);
            els.add(e);
        }
        for (Txt t : sc.allTexts()) {
            Map<String, Object> e = text(t.id, t.x, t.y - t.size, t.text, t.size, colorOf(t.color, id), t.anchor.equals("start") ? "left" : t.anchor.equals("end") ? "right" : "center", cw);
            if (t.mono) e.put("fontFamily", 3L);
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
        doc.put("files", files);
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
        boolean dia = b.shape.equals("diamond"), frame = b.shape.equals("frame") || "none".equals(b.fill);
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

    // ───────────────────────────── legibility ─────────────────────────────
    // Decision 0211 § Legibility. One advance-width table (visual-identity.md, `text`), never AWT:
    // the same bytes on every OS. `Metrics` measures; `Legibility.check` reads the SVG a render
    // produced — its own render model, one element per line — and names every collision.

    static final class Metrics {
        final double[] w = new double[95];
        final double scale, bold, mono, dflt;
        final Map<Character, Double> extra = new HashMap<>();
        final double lineHeight;

        Metrics(Identity id) {
            String s = id.s("text", "widths").trim();
            String[] p = s.split("\\s+");
            if (p.length != 95) throw new Fail("visual-identity.md text.widths holds " + p.length + " advance widths; ASCII 32..126 needs 95");
            for (int k = 0; k < 95; k++) w[k] = Double.parseDouble(p[k]);
            scale = id.d("text", "scale");
            bold = id.d("text", "bold");
            mono = id.d("text", "mono");
            dflt = id.d("text", "default");
            lineHeight = id.d("text", "line_height");
            Map<String, Object> ex = map(get(id.m, "text", "extra"));
            if (ex != null) for (var e : ex.entrySet()) extra.put(e.getKey().charAt(0), ((Number) e.getValue()).doubleValue());
        }

        /** Width in px of one line of text. */
        double width(String t, double size, boolean isBold, boolean isMono) {
            double sum = 0;
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                double a;
                if (isMono) a = mono;
                else if (c >= 32 && c <= 126) a = w[c - 32] * scale;
                else a = extra.getOrDefault(c, dflt) * scale;
                sum += a;
            }
            return sum / 1000.0 * size * (isBold && !isMono ? bold : 1.0);
        }

        /** Greedy word wrap of `t` to `max` px, at most `lines` lines; a word wider than `max` breaks at a camel-case boundary, then anywhere. The last line ends with an ellipsis when text is left. */
        List<String> wrap(String t, double max, int lines, double size, boolean isBold, boolean isMono) {
            List<String> out = new ArrayList<>();
            if (t == null || t.isBlank()) return out;
            Deque<String> words = new ArrayDeque<>();
            for (String wd : t.trim().split("\\s+")) words.add(wd);
            StringBuilder cur = new StringBuilder();
            while (!words.isEmpty()) {
                String wd = words.poll();
                String tryS = cur.length() == 0 ? wd : cur + " " + wd;
                if (width(tryS, size, isBold, isMono) <= max) { cur.setLength(0); cur.append(tryS); continue; }
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); words.addFirst(wd); continue; }
                // one word wider than a line: cut at the last camel-case (or any) boundary that fits
                int cut = -1, any = 1;
                for (int k = 1; k < wd.length(); k++) {
                    if (width(wd.substring(0, k), size, isBold, isMono) > max) break;
                    any = k;
                    char a = wd.charAt(k - 1), b = wd.charAt(k);
                    if ((Character.isLowerCase(a) || Character.isDigit(a)) && Character.isUpperCase(b) || a == '.' || a == '-' || a == '_' || a == '/') cut = k;
                }
                int at = cut > 0 ? cut : any;
                out.add(wd.substring(0, at));
                if (at < wd.length()) words.addFirst(wd.substring(at));
            }
            if (cur.length() > 0) out.add(cur.toString());
            if (out.size() > lines) {
                List<String> cutl = new ArrayList<>(out.subList(0, lines));
                String last = cutl.get(lines - 1);
                while (last.length() > 1 && width(last + "…", size, isBold, isMono) > max) last = last.substring(0, last.length() - 1);
                cutl.set(lines - 1, last + "…");
                return cutl;
            }
            return out;
        }
    }

    static Metrics metrics;

    static final class LText {
        String text, grp; double x0, y0, x1, y1; boolean badge;
        String show() { return "\"" + text + "\""; }
    }

    static final class LShape {
        String grp, kind; double x0, y0, x1, y1; double[] poly; boolean badge;
    }

    static final class LLine {
        String grp, fan = ""; List<double[]> pts = new ArrayList<>();
    }

    static final Pattern ATTR = Pattern.compile("([a-zA-Z0-9:-]+)=\"([^\"]*)\"");

    static Map<String, String> attrs(String tag) {
        Map<String, String> m = new HashMap<>();
        Matcher a = ATTR.matcher(tag);
        while (a.find()) m.put(a.group(1), a.group(2));
        return m;
    }

    static double dbl(Map<String, String> a, String k, double d) {
        try { return a.containsKey(k) ? Double.parseDouble(a.get(k)) : d; } catch (NumberFormatException e) { return d; }
    }

    static String unesc(String s) { return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&"); }

    /** The four findings of the detector, `view` named first; empty when the SVG is legible. */
    static List<String> legibility(String view, String svg) {
        List<LText> ts = new ArrayList<>();
        List<LShape> ss = new ArrayList<>();
        List<LLine> ls = new ArrayList<>();
        double W = 0, H = 0;
        String grp = "", bg = "", fan = "";
        boolean badge = false;
        for (String line : svg.split("\n")) {
            String t = line.trim();
            if (t.startsWith("<svg ")) { Map<String, String> a = attrs(t); W = dbl(a, "width", 0); H = dbl(a, "height", 0); continue; }
            if (t.startsWith("<g ")) { Map<String, String> a = attrs(t); grp = a.getOrDefault("id", ""); badge = a.containsKey("data-badge"); fan = a.getOrDefault("data-fan", ""); continue; }
            if (t.equals("</g>")) { grp = ""; badge = false; fan = ""; continue; }
            if (t.startsWith("<defs") || t.startsWith("<style") || t.startsWith("<title")) continue;
            if (t.startsWith("<rect ")) {
                Map<String, String> a = attrs(t);
                double x = dbl(a, "x", 0), y = dbl(a, "y", 0), w = dbl(a, "width", 0), h = dbl(a, "height", 0);
                if (x == 0 && y == 0 && w == W && h == H) continue;
                LShape s = new LShape(); s.grp = grp; s.kind = "rect"; s.x0 = x; s.y0 = y; s.x1 = x + w; s.y1 = y + h; s.badge = badge; ss.add(s);
            } else if (t.startsWith("<circle ")) {
                Map<String, String> a = attrs(t);
                double cx = dbl(a, "cx", 0), cy = dbl(a, "cy", 0), r = dbl(a, "r", 0);
                LShape s = new LShape(); s.grp = grp; s.kind = "rect"; s.x0 = cx - r; s.y0 = cy - r; s.x1 = cx + r; s.y1 = cy + r; s.badge = badge; ss.add(s);
            } else if (t.startsWith("<polygon ")) {
                Map<String, String> a = attrs(t);
                String[] p = a.getOrDefault("points", "").trim().split("[ ,]+");
                double[] poly = new double[p.length];
                for (int k = 0; k < p.length; k++) poly[k] = Double.parseDouble(p[k]);
                LShape s = new LShape(); s.grp = grp; s.kind = "poly"; s.poly = poly; s.badge = badge;
                s.x0 = Double.MAX_VALUE; s.y0 = Double.MAX_VALUE; s.x1 = -Double.MAX_VALUE; s.y1 = -Double.MAX_VALUE;
                for (int k = 0; k + 1 < poly.length; k += 2) { s.x0 = Math.min(s.x0, poly[k]); s.x1 = Math.max(s.x1, poly[k]); s.y0 = Math.min(s.y0, poly[k + 1]); s.y1 = Math.max(s.y1, poly[k + 1]); }
                ss.add(s);
            } else if (t.startsWith("<path ") && t.contains("data-box=")) {
                String[] b = attrs(t).get("data-box").split(",");
                LShape s = new LShape(); s.grp = grp; s.kind = "icon"; s.x0 = Double.parseDouble(b[0]); s.y0 = Double.parseDouble(b[1]);
                s.x1 = s.x0 + Double.parseDouble(b[2]); s.y1 = s.y0 + Double.parseDouble(b[3]); s.badge = badge; ss.add(s);
            } else if (t.startsWith("<line ")) {
                Map<String, String> a = attrs(t);
                LLine l = new LLine(); l.grp = grp; l.fan = fan;
                l.pts.add(new double[] { dbl(a, "x1", 0), dbl(a, "y1", 0) }); l.pts.add(new double[] { dbl(a, "x2", 0), dbl(a, "y2", 0) });
                ls.add(l);
            } else if (t.startsWith("<polyline ")) {
                String[] p = attrs(t).getOrDefault("points", "").trim().split("[ ,]+");
                LLine l = new LLine(); l.grp = grp; l.fan = fan;
                for (int k = 0; k + 1 < p.length; k += 2) l.pts.add(new double[] { Double.parseDouble(p[k]), Double.parseDouble(p[k + 1]) });
                ls.add(l);
            } else if (t.startsWith("<text ")) {
                Map<String, String> a = attrs(t);
                int gt = t.indexOf('>'), lt = t.lastIndexOf("</text>");
                if (gt < 0 || lt < gt) continue;
                String content = unesc(t.substring(gt + 1, lt));
                if (content.isEmpty()) continue;
                double size = dbl(a, "font-size", 13);
                boolean bold = dbl(a, "font-weight", 400) >= 600;
                String ff = a.getOrDefault("font-family", "").toLowerCase(Locale.ROOT);
                boolean mono = ff.contains("mono") || ff.contains("menlo");
                double w = metrics.width(content, size, bold, mono);
                double x = dbl(a, "x", 0), y = dbl(a, "y", 0);
                String anchor = a.getOrDefault("text-anchor", "start");
                LText e = new LText(); e.text = content; e.grp = grp; e.badge = badge;
                e.x0 = anchor.equals("middle") ? x - w / 2 : anchor.equals("end") ? x - w : x; e.x1 = e.x0 + w;
                e.y0 = y - size * 0.8; e.y1 = y + size * 0.25;
                ts.add(e);
            }
        }
        List<String> out = new ArrayList<>();
        String p = view + ": ";
        for (int i = 0; i < ts.size(); i++) {
            LText a = ts.get(i);
            if (a.x0 < -0.5 || a.y0 < -0.5 || a.x1 > W + 0.5 || a.y1 > H + 0.5)
                out.add(p + "OUT OF VIEWBOX " + a.show() + " spans " + r1(a.x0) + "," + r1(a.y0) + " to " + r1(a.x1) + "," + r1(a.y1) + " in a " + (int) W + "x" + (int) H + " viewBox");
            for (int j = i + 1; j < ts.size(); j++) {
                LText b = ts.get(j);
                if (a.x0 + 1 < b.x1 && b.x0 + 1 < a.x1 && a.y0 + 1 < b.y1 && b.y0 + 1 < a.y1)
                    out.add(p + "TEXT×TEXT " + a.show() + " × " + b.show() + " at " + r1(Math.max(a.x0, b.x0)) + "," + r1(Math.max(a.y0, b.y0)));
            }
        }
        for (LShape s : ss) {
            if (s.x0 < -0.5 || s.y0 < -0.5 || s.x1 > W + 0.5 || s.y1 > H + 0.5)
                out.add(p + "OUT OF VIEWBOX a shape of '" + s.grp + "' spans " + r1(s.x0) + "," + r1(s.y0) + " to " + r1(s.x1) + "," + r1(s.y1) + " in a " + (int) W + "x" + (int) H + " viewBox");
        }
        for (LText a : ts) {
            for (LShape s : ss) {
                if (a.badge) continue;
                boolean same = a.grp.equals(s.grp);
                if (same && s.kind.equals("icon")) continue;
                int in = 0;
                double[][] pts = { { a.x0, a.y0 }, { a.x1, a.y0 }, { a.x0, a.y1 }, { a.x1, a.y1 }, { (a.x0 + a.x1) / 2, a.y0 }, { (a.x0 + a.x1) / 2, a.y1 }, { a.x0, (a.y0 + a.y1) / 2 }, { a.x1, (a.y0 + a.y1) / 2 } };
                boolean hit;
                if (s.kind.equals("poly")) {
                    for (double[] q : pts) if (inPoly(s.poly, q[0], q[1])) in++;
                    hit = in > 0 || polyCrossesRect(s.poly, a.x0, a.y0, a.x1, a.y1);
                } else {
                    hit = a.x0 + 0.5 < s.x1 && s.x0 + 0.5 < a.x1 && a.y0 + 0.5 < s.y1 && s.y0 + 0.5 < a.y1;
                    if (hit) in = (a.x0 >= s.x0 - 0.5 && a.x1 <= s.x1 + 0.5 && a.y0 >= s.y0 - 0.5 && a.y1 <= s.y1 + 0.5) ? 8 : 1;
                }
                if (!hit) continue;
                boolean contained = in == 8;
                if (contained && same) continue;
                if (contained && !same && isContainer(s, ss)) continue;
                out.add(p + "TEXT×SHAPE " + a.show() + (contained ? " sits on the shape of '" + s.grp + "', not its own" : " crosses the outline of the shape of '" + s.grp + "'") + " at " + r1(Math.max(a.x0, s.x0)) + "," + r1(Math.max(a.y0, s.y0)));
            }
        }
        for (LText a : ts) {
            if (a.badge) continue;
            for (LLine l : ls) {
                for (int k = 0; k + 1 < l.pts.size(); k++) {
                    double[] u = l.pts.get(k), v = l.pts.get(k + 1);
                    if (segHitsRect(u[0], u[1], v[0], v[1], a.x0 - 1, a.y0 - 1, a.x1 + 1, a.y1 + 1)) {
                        out.add(p + "TEXT×LINE " + a.show() + " is crossed by '" + l.grp + "' at " + r1(Math.max(a.x0, Math.min(u[0], v[0]))) + "," + r1(Math.max(a.y0, Math.min(u[1], v[1]))));
                        break;
                    }
                }
            }
        }
        // two edges that run on one line: collinear and overlapping by more than 4 px, unless they share a declared source port
        for (int i = 0; i < ls.size(); i++)
            for (int j = i + 1; j < ls.size(); j++) {
                LLine a = ls.get(i), b = ls.get(j);
                if (a.grp.equals(b.grp) || (!a.fan.isEmpty() && a.fan.equals(b.fan)) || a.grp.startsWith("lifeline") || b.grp.startsWith("lifeline")) continue;
                String hit = null;
                for (int k = 0; k + 1 < a.pts.size() && hit == null; k++)
                    for (int m = 0; m + 1 < b.pts.size() && hit == null; m++) {
                        double[] u = a.pts.get(k), v = a.pts.get(k + 1), w = b.pts.get(m), z = b.pts.get(m + 1);
                        boolean ha = Math.abs(u[1] - v[1]) < 0.01, hb = Math.abs(w[1] - z[1]) < 0.01, va = Math.abs(u[0] - v[0]) < 0.01, vb = Math.abs(w[0] - z[0]) < 0.01;
                        if (ha && hb && Math.abs(u[1] - w[1]) < 2) {
                            double ov = Math.min(Math.max(u[0], v[0]), Math.max(w[0], z[0])) - Math.max(Math.min(u[0], v[0]), Math.min(w[0], z[0]));
                            if (ov > 4) hit = r1(ov) + " px at y " + r1(u[1]);
                        } else if (va && vb && Math.abs(u[0] - w[0]) < 2) {
                            double ov = Math.min(Math.max(u[1], v[1]), Math.max(w[1], z[1])) - Math.max(Math.min(u[1], v[1]), Math.min(w[1], z[1]));
                            if (ov > 4) hit = r1(ov) + " px at x " + r1(u[0]);
                        }
                    }
                if (hit != null) out.add(p + "LINE×LINE '" + a.grp + "' and '" + b.grp + "' run together for " + hit);
            }
        return out;
    }

    static String r1(double d) { return num(Math.round(d * 10) / 10.0); }

    /** A shape is a container when it fully holds a shape of another group. */
    static boolean isContainer(LShape s, List<LShape> all) {
        for (LShape o : all) {
            if (o == s || o.grp.equals(s.grp)) continue;
            if (o.x0 >= s.x0 - 0.5 && o.x1 <= s.x1 + 0.5 && o.y0 >= s.y0 - 0.5 && o.y1 <= s.y1 + 0.5) return true;
        }
        return false;
    }

    static boolean inPoly(double[] p, double x, double y) {
        boolean in = false;
        int n = p.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = p[2 * i], yi = p[2 * i + 1], xj = p[2 * j], yj = p[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    static boolean polyCrossesRect(double[] p, double x0, double y0, double x1, double y1) {
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (segHitsRect(p[2 * i], p[2 * i + 1], p[2 * j], p[2 * j + 1], x0, y0, x1, y1)) return true;
        }
        return false;
    }

    /** Liang–Barsky: does the segment touch the rectangle. */
    static boolean segHitsRect(double ax, double ay, double bx, double by, double x0, double y0, double x1, double y1) {
        double t0 = 0, t1 = 1, dx = bx - ax, dy = by - ay;
        double[] pp = { -dx, dx, -dy, dy }, qq = { ax - x0, x1 - ax, ay - y0, y1 - ay };
        for (int k = 0; k < 4; k++) {
            if (pp[k] == 0) { if (qq[k] < 0) return false; }
            else {
                double r = qq[k] / pp[k];
                if (pp[k] < 0) { if (r > t1) return false; if (r > t0) t0 = r; }
                else { if (r < t0) return false; if (r < t1) t1 = r; }
            }
        }
        return true;
    }

    // ───────────────────────────── project facts ─────────────────────────────
    // Decision 0211. What a generated project says about itself: the active blueprint's
    // packages.map, the types under src/main/java, the use case specs, docker-compose.yml, the
    // application*.yml keys and the migrations. Every list it reads — globs, roles, icons — is the
    // `diagrams` block of extensions.json (invariant 10), never a constant here.

    static Map<String, Object> dgBlock() throws IOException {
        Map<String, Object> d = map(get(readJson(EXT), "diagrams"));
        if (d == null) throw new Fail(EXT + " has no `diagrams` block — the globs, roles and icon map of --source project live there");
        return d;
    }

    static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        List<Object> l = list(o);
        if (l != null) for (Object x : l) out.add(String.valueOf(x));
        return out;
    }

    static Pattern globRe(String g) {
        StringBuilder b = new StringBuilder("^");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') { i++; if (i + 1 < g.length() && g.charAt(i + 1) == '/') { b.append("(?:.*/)?"); i++; /* `**` + `/` also matches no directory */ } else b.append(".*"); }
                else b.append("[^/]*");
            } else if (c == '?') b.append("[^/]");
            else b.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(b.append("$").toString());
    }

    /** Every file of the project that matches one of the globs, as sorted relative paths. */
    static List<String> filesMatching(List<String> globs) throws IOException {
        List<Pattern> ps = new ArrayList<>();
        for (String g : globs) ps.add(globRe(g));
        List<String> out = new ArrayList<>();
        Set<String> skip = new HashSet<>(strList(dgBlock().get("walk_exclude_dirs")));
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                return skip.contains(d.getFileName() == null ? "" : d.getFileName().toString()) && !d.equals(root) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                for (Pattern p : ps) if (p.matcher(rel).matches()) { out.add(rel); break; }
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(out);
        return out;
    }

    static String read(String rel) throws IOException {
        return norm(Files.readString(root.resolve(rel), StandardCharsets.UTF_8));
    }

    /** `docs/diagrams/.inputs.sha256`: one `<sha256>  <path>` per input, sorted by path. */
    static String inputsManifest() throws IOException {
        Map<String, Object> dg = dgBlock();
        StringBuilder sb = new StringBuilder();
        for (String f : filesMatching(inputGlobs(dg)))
            sb.append(sha256(read(f))).append("  ").append(f).append('\n');
        return sb.toString();
    }

    /** `diagrams.inputs` plus the active blueprint: `blueprint_dir` + `/` + `blueprint_glob`, kept apart in the data (an export residue marker). */
    static List<String> inputGlobs(Map<String, Object> dg) {
        List<String> g = new ArrayList<>(strList(dg.get("inputs")));
        g.add(blueprintDir(dg) + "/" + (dg.get("blueprint_glob") == null ? "*/*.yaml" : String.valueOf(dg.get("blueprint_glob"))));
        return g;
    }

    static String blueprintDir(Map<String, Object> dg) {
        Object d = dg.get("blueprint_dir");
        return d == null ? ".claude" + "/blueprints" : String.valueOf(d);
    }

    /** A flat dotted-path view of a YAML file's scalar keys: enough for application.yml and compose. */
    static Map<String, String> flatYaml(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();
        List<Integer> ind = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.replaceAll("\\s+#.*$", "");
            if (line.isBlank() || line.trim().startsWith("#") || line.trim().startsWith("- ")) continue;
            Matcher m = Pattern.compile("^(\\s*)([A-Za-z0-9_.\"'\\-/]+):\\s*(.*)$").matcher(line);
            if (!m.matches()) continue;
            int in = m.group(1).length();
            while (!ind.isEmpty() && ind.get(ind.size() - 1) >= in) { ind.remove(ind.size() - 1); path.remove(path.size() - 1); }
            String key = m.group(2).replaceAll("^[\"']|[\"']$", "");
            path.add(key); ind.add(in);
            String v = m.group(3).trim();
            if (!v.isEmpty() && !v.equals("|") && !v.equals(">")) out.put(String.join(".", path), v.replaceAll("^[\"']|[\"']$", ""));
            else out.put(String.join(".", path), "");
        }
        return out;
    }

    static final Pattern TYPE_DECL = Pattern.compile("(?m)^\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:(?:abstract|final|sealed|non-sealed|static)\\s+)*(class|interface|record|enum|@interface)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    static String stripJava(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ").replaceAll("(?m)^\\s*(import|package)\\s.*$", " ");
    }

    static Map<String, Object> extractProject() throws IOException {
        Map<String, Object> dg = dgBlock();
        Map<String, Object> f = new TreeMap<>();
        Map<String, Object> arch = map(dg.get("architecture"));
        if (arch == null) throw new Fail(EXT + " `diagrams` has no `architecture` block");
        // the active blueprint's packages.map
        Path bpDir = root.resolve(blueprintDir(dg));
        String bpFile = null;
        if (Files.isDirectory(bpDir)) {
            try (Stream<Path> s = Files.list(bpDir)) {
                for (Path p : s.sorted().collect(Collectors.toList())) {
                    try (Stream<Path> y = Files.list(p)) {
                        for (Path q : y.sorted().collect(Collectors.toList()))
                            if (q.getFileName().toString().endsWith(".yaml") && bpFile == null) bpFile = root.relativize(q).toString().replace('\\', '/');
                    } catch (IOException | RuntimeException e) { /* a file, not a directory */ }
                }
            }
        }
        if (bpFile == null) throw new Fail(blueprintDir(dg) + "/<id>/<id>.yaml is missing — the active blueprint's packages.map names the layers the architecture view draws");
        String bpId = bpFile.substring(bpFile.lastIndexOf('/') + 1, bpFile.length() - 5);
        List<String[]> pkgs = new ArrayList<>(); // key, dir
        boolean inPk = false, inMap = false;
        for (String l : read(bpFile).split("\n")) {
            if (l.startsWith("packages:")) { inPk = true; continue; }
            if (inPk && l.matches("^[A-Za-z].*")) break;
            if (inPk && l.trim().equals("map:")) { inMap = true; continue; }
            if (inMap) {
                Matcher m = Pattern.compile("^\\s{4}([A-Za-z0-9_.]+):\\s*\"?([A-Za-z0-9_.]+)\"?\\s*(#.*)?$").matcher(l);
                if (m.matches()) pkgs.add(new String[] { m.group(1), m.group(2).replace('.', '/') });
                else if (l.trim().startsWith("#") || l.isBlank()) continue;
                else if (!l.startsWith("    ")) break;
            }
        }
        if (pkgs.isEmpty()) throw new Fail(bpFile + " has no packages.map — the architecture view reads the layers from it");
        Path jroot = root.resolve("src/main/java");
        if (!Files.isDirectory(jroot)) throw new Fail("src/main/java is missing — the architecture view reads the types from it");
        // the base package: the directory that holds the map's relative directories
        String base = null;
        try (Stream<Path> w = Files.walk(jroot)) {
            List<Path> dirs = w.filter(Files::isDirectory).sorted(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(Path::toString)).collect(Collectors.toList());
            outer:
            for (String[] pk : pkgs)
                for (Path d : dirs) {
                    String rel = jroot.relativize(d).toString().replace('\\', '/');
                    if (rel.equals(pk[1]) || rel.endsWith("/" + pk[1])) { base = rel.equals(pk[1]) ? "" : rel.substring(0, rel.length() - pk[1].length() - 1); break outer; }
                }
        }
        if (base == null) throw new Fail("no directory of src/main/java matches the packages.map of " + bpFile + " (" + pkgs.get(0)[0] + " → " + pkgs.get(0)[1] + ") — fact 'pkg:" + pkgs.get(0)[0] + "' not found");
        // sources of every package, the longest directory wins
        pkgs.sort((a, b) -> Integer.compare(b[1].length(), a[1].length()));
        Map<String, String> typePkg = new TreeMap<>();
        Map<String, String> typeSrc = new TreeMap<>();
        Map<String, String> typeKind = new TreeMap<>();
        Map<String, String> typePath = new TreeMap<>();
        List<String> files = new ArrayList<>();
        try (Stream<Path> w = Files.walk(jroot)) { for (Path p : w.sorted().collect(Collectors.toList())) if (Files.isRegularFile(p) && p.toString().endsWith(".java")) files.add(jroot.relativize(p).toString().replace('\\', '/')); }
        Map<String, List<Object>> pkgTypes = new TreeMap<>();
        for (String[] pk : pkgs) pkgTypes.put(pk[0], new ArrayList<>());
        for (String rel : files) {
            String fn = rel.substring(rel.lastIndexOf('/') + 1);
            if (fn.equals("package-info.java") || fn.equals("module-info.java")) continue;
            String key = null;
            String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
            for (String[] pk : pkgs) {
                String want = base.isEmpty() ? pk[1] : base + "/" + pk[1];
                if (dir.equals(want) || dir.startsWith(want + "/")) { key = pk[0]; break; }
            }
            if (key == null) continue;
            String name = fn.substring(0, fn.length() - 5);
            String src = stripJava(Files.readString(jroot.resolve(rel), StandardCharsets.UTF_8));
            Matcher td = TYPE_DECL.matcher(src);
            String kind = "class";
            while (td.find()) if (td.group(2).equals(name)) { kind = td.group(1); break; }
            typePkg.put(name, key); typeSrc.put(name, src); typeKind.put(name, kind); typePath.put(name, "src/main/java/" + rel);
        }
        // specs: aggregates, cases
        Map<String, Object> ucs = specFacts(f, typePath.keySet());
        Set<String> aggregates = new TreeSet<>();
        for (Object o : ucs.values()) {
            Map<String, Object> u = map(o);
            for (String a : strList(u.get("aggregates"))) aggregates.add(a);
        }
        // roles: first match in the data, `implements_port` after the first pass
        List<Object> roles = list(arch.get("roles"));
        Map<String, String> role = new TreeMap<>();
        Map<String, List<String>> impls = new TreeMap<>();
        for (var e : typeSrc.entrySet()) {
            Matcher m = Pattern.compile("(?:class|record|enum)\\s+" + e.getKey() + "\\b[^{]*?\\bimplements\\s+([^{]+)\\{").matcher(e.getValue());
            List<String> l = new ArrayList<>();
            if (m.find()) for (String x : m.group(1).split(",")) { String n = x.trim().replaceAll("<.*", "").replaceAll("\\s.*", ""); if (!n.isEmpty()) l.add(n); }
            impls.put(e.getKey(), l);
        }
        for (int pass = 0; pass < 2; pass++)
            for (String n : typePkg.keySet()) {
                if (role.containsKey(n)) continue;
                for (Object ro : roles) {
                    Map<String, Object> r = map(ro);
                    boolean needsPort = Boolean.TRUE.equals(r.get("implements_port"));
                    if (needsPort && pass == 0) continue;
                    if (r.get("pkg") != null && !Pattern.compile((String) r.get("pkg")).matcher(typePkg.get(n)).find()) continue;
                    if (r.get("name") != null && !Pattern.compile((String) r.get("name")).matcher(n).matches()) continue;
                    if (r.get("kind") != null && !typeKind.get(n).equals(r.get("kind"))) continue;
                    if (Boolean.TRUE.equals(r.get("aggregate")) && !aggregates.contains(n)) continue;
                    if (needsPort && impls.get(n).stream().noneMatch(i -> "port".equals(role.get(i)))) continue;
                    role.put(n, (String) r.get("role"));
                    break;
                }
                if (pass == 1 && !role.containsKey(n)) role.put(n, "support");
            }
        List<String> names = new ArrayList<>(typePkg.keySet());
        for (String n : names) {
            Map<String, Object> t = fact("type", n, role.get(n), "role", role.get(n));
            t.put("pkg", typePkg.get(n));
            t.put("path", typePath.get(n));
            t.put("source_kind", typeKind.get(n));
            List<Object> uses = new ArrayList<>();
            for (String o : names) if (!o.equals(n) && Pattern.compile("\\b" + o + "\\b").matcher(typeSrc.get(n)).find()) uses.add("type:" + o);
            t.put("uses", uses);
            List<Object> im = new ArrayList<>();
            for (String i : impls.get(n)) if ("port".equals(role.get(i))) { im.add("type:" + i); f.put("impl:" + n + "." + i, fact("impl", n + " → " + i, "implements", "from", "type:" + n)); map(f.get("impl:" + n + "." + i)).put("to", "type:" + i); }
            t.put("implements", im);
            f.put("type:" + n, t);
            pkgTypes.get(typePkg.get(n)).add("type:" + n);
        }
        for (String[] pk : pkgs) {
            Map<String, Object> p = fact("pkg", pk[0], pkgTypes.get(pk[0]).size() + " types", "dir", pk[1]);
            p.put("types", pkgTypes.get(pk[0]));
            f.put("pkg:" + pk[0], p);
        }
        // drift: an aggregate a spec names that no type of src/ carries
        for (var e : ucs.entrySet()) {
            Map<String, Object> u = map(e.getValue());
            for (String a : strList(u.get("aggregates")))
                if (!typePkg.containsKey(a)) {
                    Map<String, Object> d = fact("drift", a, "spec only · " + u.get("id"), "uc", u.get("id"));
                    d.put("type", a);
                    f.put("drift:" + u.get("id") + "." + a, d);
                }
        }
        Map<String, Object> info = fact("project", bpId, base, "blueprint", bpId);
        info.put("base", base.replace('/', '.'));
        f.put("project:info", info);
        composeFacts(f, dg);
        return f;
    }

    /** `uc:*`, `bl:*`, `mermaid:*` — the specs under docs/use-cases/. Returns the uc facts by id. */
    static Map<String, Object> specFacts(Map<String, Object> f, Set<String> typeNames) throws IOException {
        Map<String, Object> dg = dgBlock();
        Map<String, Object> out = new TreeMap<>();
        Path uroot = root.resolve("docs/use-cases");
        if (!Files.isDirectory(uroot)) return out;
        List<String> allowed = strList(dg.get("mermaid_types"));
        List<Object> kinds = list(dg.get("trigger_kinds"));
        List<String> verbs = strList(dg.get("verbs"));
        List<String> reads = strList(dg.get("read_words"));
        List<Path> dirs;
        try (Stream<Path> s = Files.list(uroot)) { dirs = s.filter(Files::isDirectory).sorted().collect(Collectors.toList()); }
        // mermaid validity across every spec file, by file and line
        for (Path d : dirs) {
            try (Stream<Path> s = Files.list(d)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".md")).sorted().collect(Collectors.toList())) {
                    List<String> ls = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (int n = 0; n < ls.size(); n++)
                        if (ls.get(n).trim().equals("```mermaid")) {
                            String first = n + 1 < ls.size() ? ls.get(n + 1).trim() : "";
                            if (!allowed.contains(first))
                                throw new Fail(root.relativize(p).toString().replace('\\', '/') + ":" + (n + 2) + " — a mermaid block opens with '" + first + "'; a spec draws " + String.join(", ", allowed) + " only (@.claude/rules/spec-diagrams.md § Format)");
                        }
                }
            }
        }
        for (Path d : dirs) {
            String folder = d.getFileName().toString();
            Matcher fm = Pattern.compile("^(UC-\\d+)-(.+)$").matcher(folder);
            if (!fm.matches()) continue;
            String ucId = fm.group(1);
            List<Path> mds;
            try (Stream<Path> s = Files.list(d)) { mds = s.filter(x -> x.toString().endsWith(".md")).sorted().collect(Collectors.toList()); }
            Path main = null;
            for (Path p : mds) if (p.getFileName().toString().startsWith("00-")) { main = p; break; }
            if (main == null) continue;
            String mt = Files.readString(main, StandardCharsets.UTF_8).replace("\r\n", "\n");
            Matcher tm = Pattern.compile("(?m)^#\\s+UC-\\d+\\s+·\\s+(.+?)\\s*$").matcher(mt);
            String title = tm.find() ? tm.group(1) : folder;
            Map<String, String> rows = new LinkedHashMap<>();
            for (String l : mt.split("\n")) {
                Matcher rm = Pattern.compile("^\\|\\s*([A-Za-z ]+?)\\s*\\|\\s*(.+?)\\s*\\|\\s*$").matcher(l);
                if (rm.matches() && !rows.containsKey(rm.group(1))) rows.put(rm.group(1), rm.group(2));
            }
            Map<String, Object> u = fact("uc", ucId + "  " + title, folder, "id", ucId);
            u.put("title", title);
            u.put("folder", folder);
            String trig = rows.getOrDefault("Trigger", ""), side = rows.getOrDefault("Side effects", ""), ext = rows.getOrDefault("External calls", "");
            u.put("trigger", trig);
            u.put("side_effects", side);
            u.put("external", ext);
            // trigger kind: the keyword written first wins
            String tk = "";
            int best = Integer.MAX_VALUE;
            if (kinds != null) for (Object ko : kinds) {
                Map<String, Object> k = map(ko);
                for (String w : strList(k.get("match"))) {
                    Matcher km = Pattern.compile(w, Pattern.CASE_INSENSITIVE).matcher(trig);
                    if (km.find() && km.start() < best) { best = km.start(); tk = (String) k.get("label"); }
                }
            }
            u.put("trigger_kind", tk);
            // the aggregates its spec files name, and the Java files they cite
            List<Object> aggs = new ArrayList<>();
            List<String> cited = new ArrayList<>();
            for (Path p : mds) {
                String t = Files.readString(p, StandardCharsets.UTF_8);
                Matcher am = Pattern.compile("(?m)^\\|\\s*Aggregate\\s*\\|\\s*`([A-Z][A-Za-z0-9]*)`").matcher(t);
                while (am.find()) if (!aggs.contains(am.group(1))) aggs.add(am.group(1));
                Matcher jm = Pattern.compile("`([A-Za-z0-9_./-]+\\.java)`").matcher(t);
                while (jm.find()) if (!cited.contains(jm.group(1)) && !jm.group(1).contains("src/test") && !jm.group(1).matches(".*(Test|IT)\\.java")) cited.add(jm.group(1));
            }
            u.put("aggregates", aggs);
            // kebab-case names any spec file of the case writes (a topic is one when `topic:<name>` exists)
            Set<String> kebab = new TreeSet<>();
            for (Path p : mds) { Matcher km = Pattern.compile("(?<![A-Za-z0-9_/-])[a-z][a-z0-9]*(?:-[a-z0-9]+)+(?![A-Za-z0-9_/-])").matcher(Files.readString(p, StandardCharsets.UTF_8)); while (km.find()) kebab.add(km.group()); }
            u.put("kebab", new ArrayList<Object>(kebab));
            // spec only: the spec cites use case types (the role's name pattern) and src/ has none of them
            Pattern ucName = null;
            List<Object> roleList = list(get(dg, "architecture", "roles"));
            if (roleList != null) for (Object ro : roleList) if ("usecase".equals(map(ro).get("role")) && map(ro).get("name") != null) ucName = Pattern.compile((String) map(ro).get("name"));
            int citedUc = 0, foundUc = 0;
            for (String c : cited) {
                String simple = c.substring(c.lastIndexOf('/') + 1, c.length() - 5);
                if (ucName != null && ucName.matcher(simple).matches()) { citedUc++; if (typeNames.contains(simple)) foundUc++; }
            }
            u.put("spec_only", citedUc > 0 && foundUc == 0);
            // mermaid blocks of the main spec
            List<String> ls = Arrays.asList(mt.split("\n", -1));
            for (int n = 0; n < ls.size(); n++)
                if (ls.get(n).trim().equals("```mermaid")) {
                    String first = ls.get(n + 1).trim();
                    String kind = first.startsWith("sequenceDiagram") ? "sequence" : first.startsWith("flowchart") ? "flowchart" : null;
                    if (kind == null || f.containsKey("mermaid:" + ucId + ":" + kind)) continue;
                    StringBuilder sb = new StringBuilder();
                    int e;
                    for (e = n + 1; e < ls.size() && !ls.get(e).trim().equals("```"); e++) sb.append(ls.get(e)).append('\n');
                    Map<String, Object> mm = fact("mermaid", kind, root.relativize(main).toString().replace('\\', '/') + ":" + (n + 2), "uc", ucId);
                    mm.put("source", sb.toString());
                    f.put("mermaid:" + ucId + ":" + kind, mm);
                }
            u.put("touches_raw", side);
            // the verbs of the Side effects row
            List<Object> vs = new ArrayList<>();
            for (String v : verbs) if (Pattern.compile("\\b" + v + "\\b").matcher(side).find()) vs.add(v);
            if (vs.isEmpty()) for (String r : reads) if (Pattern.compile(r, Pattern.CASE_INSENSITIVE).matcher(side).find()) { vs.add("read"); break; }
            u.put("verbs", vs);
            f.put("uc:" + ucId, u);
            out.put(ucId, u);
        }
        // the backlog's active table
        Path bl = uroot.resolve("BACKLOG.md");
        if (Files.isRegularFile(bl)) {
            boolean active = true;
            for (String l : Files.readAllLines(bl, StandardCharsets.UTF_8)) {
                if (l.startsWith("## Retired")) active = false;
                Matcher bm = Pattern.compile("^\\|\\s*`(BL-\\d+)`\\s*\\|\\s*(.*?)\\s*\\|\\s*(.*?)\\s*\\|").matcher(l);
                if (active && bm.find()) {
                    String desc = bm.group(3).replaceAll("[`*]", "");
                    if (desc.length() > 44) desc = desc.substring(0, 44).replaceAll("\\s+\\S*$", "") + "…";
                    Map<String, Object> b = fact("bl", bm.group(1), desc, "deps", new ArrayList<>());
                    List<Object> deps = new ArrayList<>();
                    Matcher dm = Pattern.compile("(UC-\\d+|BL-\\d+)").matcher(bm.group(2));
                    while (dm.find()) deps.add(dm.group(1));
                    b.put("deps", deps);
                    f.put("bl:" + bm.group(1), b);
                }
            }
        }
        return out;
    }

    /** `service:*`, `topic:*`, `table:*`, `external:*` — docker-compose.yml, application*.yml, the migrations and the specs' External calls rows. */
    static void composeFacts(Map<String, Object> f, Map<String, Object> dg) throws IOException {
        List<String> devTools = strList(dg.get("dev_tool_services"));
        Map<String, Object> icons = map(dg.get("image_icons"));
        Map<String, String> yml = new LinkedHashMap<>();
        for (String y : filesMatching(List.of("src/main/resources/application*.yml"))) yml.putAll(flatYaml(read(y)));
        String appName = yml.getOrDefault("spring.application.name", root.getFileName().toString());
        Map<String, Object> app = map(f.get("project:info"));
        app.put("app_name", appName);
        if (!Files.isRegularFile(root.resolve("docker-compose.yml"))) return;
        // services: indent-2 keys under `services:`, their image, ports and the text of their body
        String comp = read("docker-compose.yml");
        String[] lines = comp.split("\n");
        boolean in = false;
        String cur = null;
        Map<String, StringBuilder> body = new LinkedHashMap<>();
        for (String l : lines) {
            if (l.startsWith("services:")) { in = true; continue; }
            if (in && l.matches("^[A-Za-z].*")) { in = false; cur = null; }
            if (!in) continue;
            Matcher sm = Pattern.compile("^  ([A-Za-z0-9_.-]+):\\s*$").matcher(l);
            if (sm.matches()) { cur = sm.group(1); body.put(cur, new StringBuilder()); continue; }
            if (cur != null && !l.trim().startsWith("#")) body.get(cur).append(l).append('\n');
        }
        if (body.isEmpty()) return;
        String appService = body.containsKey("app") ? "app" : body.keySet().stream().filter(k -> body.get(k).toString().contains("build:")).findFirst().orElse(null);
        List<String> tables = new ArrayList<>();
        long vmin = Long.MAX_VALUE, vmax = -1;
        for (String m : filesMatching(List.of("src/main/resources/db/migration/**/*.sql"))) {
            Matcher vm = Pattern.compile("/V(\\d+)__").matcher(m);
            if (vm.find()) { vmin = Math.min(vmin, Long.parseLong(vm.group(1))); vmax = Math.max(vmax, Long.parseLong(vm.group(1))); }
            Matcher cm = Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_.\"]+)").matcher(read(m));
            while (cm.find()) { String t = cm.group(1).replace("\"", ""); if (!tables.contains(t)) tables.add(t); }
        }
        for (String t : tables) f.put("table:" + t, fact("table", t, "table", "name", t));
        Map<String, Object> topics = new LinkedHashMap<>();
        for (var e : yml.entrySet()) {
            Matcher tm = Pattern.compile("(?:^|\\.)topics\\.([A-Za-z0-9_-]+)(?:\\.(.+))?$").matcher(e.getKey());
            if (!tm.find()) continue;
            Map<String, Object> t = map(topics.computeIfAbsent(tm.group(1), k -> new TreeMap<String, Object>()));
            if (tm.group(2) != null && !tm.group(2).contains(".")) t.put(tm.group(2), e.getValue());
        }
        for (var e : topics.entrySet()) {
            Map<String, Object> t = fact("topic", e.getKey(), "topic", "name", e.getKey());
            t.put("partitions", map(e.getValue()).getOrDefault("partitions", ""));
            f.put("topic:" + e.getKey(), t);
        }
        String appText = appService == null ? "" : body.get(appService).toString();
        for (var e : body.entrySet()) {
            String n = e.getKey(), b = e.getValue().toString();
            Matcher im = Pattern.compile("(?m)^\\s+image:\\s*(\\S+)").matcher(b);
            String image = im.find() ? im.group(1) : "";
            Map<String, Object> s = fact("service", n, image.isEmpty() ? "build" : image, "image", image);
            s.put("is_app", n.equals(appService));
            s.put("dev_tool", devTools.contains(n) || devTools.stream().anyMatch(d -> image.contains(d)));
            List<Object> ports = new ArrayList<>();
            Matcher pm = Pattern.compile("(?m)^\\s+-\\s*\"?[^\"\\n]*?:(\\d+)\"?\\s*$").matcher(b.replaceAll("(?s)environment:.*?(?=\\n    [a-z_]+:|$)", ""));
            while (pm.find()) ports.add(Long.parseLong(pm.group(1)));
            s.put("ports", ports);
            String slug = "", generic = "service";
            if (n.equals(appService)) { slug = (String) map(icons.get("@app")).get("slug"); generic = (String) map(icons.get("@app")).get("generic"); }
            else for (var ie : icons.entrySet()) {
                if (ie.getKey().startsWith("@")) continue;
                if (Pattern.compile("^" + Pattern.quote(ie.getKey()).replace("*", "\\E.*\\Q") + "$").matcher(image).matches()) { slug = (String) map(ie.getValue()).get("slug"); generic = (String) map(ie.getValue()).get("generic"); break; }
            }
            s.put("slug", slug);
            s.put("generic", generic);
            s.put("referenced", !n.equals(appService) && Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(n) + "(?::\\d+|/)|(?m)^\\s+" + Pattern.quote(n) + ":\\s*$").matcher(appText).find());
            List<Object> det = new ArrayList<>();
            if (!image.isEmpty()) det.add(image.replaceAll("^[^/]*/(.*/)?", "").replaceAll("^.*/", ""));
            if (slug.equals("postgresql")) {
                if (!tables.isEmpty()) det.add(String.join(" · ", tables));
                if (vmax >= 0) det.add("Flyway V" + vmin + (vmax > vmin ? "–V" + vmax : ""));
            }
            if (slug.equals("apachekafka") && !topics.isEmpty())
                for (var te : topics.entrySet()) {
                    Object pn = map(te.getValue()).get("partitions");
                    det.add(te.getKey() + (pn == null ? "" : " · " + pn + "p"));
                }
            if (n.equals(appService)) { det.clear(); det.add(appName + " · " + (ports.isEmpty() ? "" : ":" + ports.get(0))); }
            s.put("detail_lines", det);
            f.put("service:" + n, s);
        }
        // externals a spec names (External calls other than `none`), and the client of the published port
        Map<String, Object> ex = new TreeMap<>();
        for (var e : new ArrayList<>(f.entrySet())) {
            if (!e.getKey().startsWith("uc:")) continue;
            String t = String.valueOf(map(e.getValue()).get("external")).trim();
            if (t.isEmpty() || t.toLowerCase(Locale.ROOT).startsWith("none")) continue;
            Matcher bm = Pattern.compile("`([^`]+)`").matcher(t);
            String name = bm.find() ? bm.group(1) : t.split("\\s+—|,|;|\\.")[0].trim();
            if (name.length() > 40) name = name.substring(0, 40).trim();
            String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (!slug.isEmpty() && !f.containsKey("external:" + slug)) f.put("external:" + slug, fact("external", name, "external", "name", name));
        }
        if (appService != null && !map(f.get("service:" + appService)).get("ports").equals(new ArrayList<>()))
            f.put("external:client", fact("external", "API client", "HTTP", "name", "client"));
    }

    // ───────────────────────────── project layouts ─────────────────────────────
    // Decision 0211. Every label is measured (Metrics) before a box is sized, every gap is as wide
    // as the widest edge label it carries, every edge runs in a gutter and every label sits beside
    // its own edge — so the legibility check finds nothing by construction, and a fact it cannot
    // lay out legibly is a finding, never a silent overlap.

    /** A drawn node: its lines, its palette entry and where it ends up. */
    static final class N {
        String id, pal;
        List<String> head = new ArrayList<>(), det = new ArrayList<>();
        boolean dashed, pill, mid, mono, heavy, noBox;
        int monoN, x, y, w, h, ports = 0, size;
        String ico = "", icoFill = "";
        boolean icoStroke;
        double opacity = 1;
        Box box;
        List<RE> left = new ArrayList<>(), right = new ArrayList<>();
        double cy() { return y + h / 2.0; }
    }

    /** An edge between two nodes of adjacent columns: `a` is the left one; `rev` puts the arrowhead on `a`. */
    static final class RE {
        N a, b;
        String label = "", id;
        boolean dashed, hollow, rev, plain;
        int sy, ty, lane = -1;
    }

    /** Free caption lines of a column, stacked among its nodes. */
    static final class Cap {
        String text, color = "muted";
        boolean mono;
        int size;
        List<String> lines = new ArrayList<>();
        int y;
    }

    static final class Col {
        List<Object> items = new ArrayList<>(); // N | Cap
        int x, w;
        String title = "";
        int bottom;
    }

    static final class PL {
        final Scene sc = new Scene();
        final Identity id;
        final Metrics m;
        final int LBL, DET, PAD, GAP, LANE, PORT, GUT, MARGIN, TOP, MAXW, MINW, LHL, LHD;
        int uniq = 0;

        PL(Identity id) {
            this.id = id; this.m = metrics;
            LBL = id.i("font", "label"); DET = id.i("font", "detail");
            PAD = id.i("project", "pad"); GAP = id.i("project", "gap"); LANE = id.i("project", "lane_step"); PORT = id.i("project", "port_step");
            GUT = id.i("project", "col_gap_min"); MARGIN = id.i("margin"); TOP = id.i("top"); MAXW = id.i("project", "max_label_width"); MINW = id.i("project", "node_min_w");
            LHL = (int) Math.ceil(LBL * m.lineHeight); LHD = (int) Math.ceil(DET * m.lineHeight);
        }

        String pal(String key) {
            Object r = get(id.m, "roles", key);
            return r instanceof String t ? t : "neutral";
        }

        N node(String nid, String pal, String name, List<String> det) {
            N n = new N();
            n.id = nid; n.pal = pal;
            n.head = m.wrap(name, MAXW, id.i("project", "label_lines"), LBL, true, false);
            for (String d : det) n.det.addAll(m.wrap(d, MAXW, 2, DET, false, false));
            measure(n);
            return n;
        }

        void measure(N n) {
            double w = 0;
            for (String h : n.head) w = Math.max(w, m.width(h, LBL, true, false));
            for (int k = 0; k < n.det.size(); k++) w = Math.max(w, m.width(n.det.get(k), DET, false, n.mono && k < (n.monoN == 0 ? n.det.size() : n.monoN)));
            n.w = Math.max(MINW, (int) Math.ceil(w) + 2 * PAD);
            n.size = n.ico.isEmpty() ? 0 : id.i("project", "icon");
            n.h = 2 * PAD + (n.size > 0 ? n.size + 8 : 0) + n.head.size() * LHL + n.det.size() * LHD + (n.det.isEmpty() ? 0 : 2);
        }

        /** The Box and its texts, once x, y, w, h are final. */
        Box build(N n) {
            Box b = new Box();
            b.id = n.id; b.shape = n.noBox ? "none" : n.pill ? "pill" : "plain";
            b.x = n.x; b.y = n.y; b.w = n.w; b.h = n.h;
            b.fill = id.fill(n.pal); b.stroke = id.stroke(n.pal);
            b.dashed = n.dashed; b.emph = n.heavy; b.opacity = n.opacity;
            if (n.dashed) b.dash = "6 4";
            int cx = n.x + n.w / 2;
            int y = n.y + PAD;
            if (n.size > 0) {
                b.icoPath = n.ico; b.icoFill = n.icoFill; b.icoStroke = n.icoStroke; b.icoSize = n.size; b.icoX = cx - n.size / 2; b.icoY = y;
                y += n.size + 8;
            }
            boolean mid = n.mid || n.size > 0;
            int tx = mid ? cx : n.x + PAD;
            for (int k = 0; k < n.head.size(); k++) {
                Txt t = txt(n.id + "#h" + k, n.head.get(k), tx, y + (int) Math.round(LBL * 0.82) + k * LHL, LBL, mid ? "middle" : "start", -1);
                t.bold = true; t.color = "ink";
                b.inner.add(t);
            }
            y += n.head.size() * LHL + (n.det.isEmpty() ? 0 : 2);
            for (int k = 0; k < n.det.size(); k++) {
                Txt t = txt(n.id + "#d" + k, n.det.get(k), tx, y + (int) Math.round(DET * 0.82) + k * LHD, DET, mid ? "middle" : "start", -1);
                t.mono = n.mono && k < (n.monoN == 0 ? n.det.size() : n.monoN);
                b.inner.add(t);
            }
            n.box = b;
            sc.nodes.add(b);
            return b;
        }

        Txt free(String tid, String text, int x, int y, int size, String anchor, String color, boolean mono, boolean bold) {
            Txt t = txt(tid, text, x, y, size, anchor, -1);
            t.color = color; t.mono = mono; t.bold = bold;
            sc.texts.add(t);
            return t;
        }

        /** Lays `cols` out left to right, routes `edges` through the gutters, returns the bottom of the tallest column. */
        int columns(List<Col> cols, List<RE> edges, int top) {
            // port counts raise a node's height: a port every PORT px of its side
            for (RE e : edges) { e.a.right.add(e); e.b.left.add(e); }
            for (Col c : cols) for (Object o : c.items) if (o instanceof N n) {
                int need = (Math.max(n.left.size(), n.right.size()) + 1) * PORT;
                n.h = Math.max(n.h, need);
            }
            for (Col c : cols) {
                int w = 0;
                for (Object o : c.items) if (o instanceof N n) w = Math.max(w, n.w);
                c.w = Math.max(w, MINW);
                for (Object o : c.items) if (o instanceof N n) n.w = c.w;
            }
            // vertical stacking per column; the title caption first
            int bottom = top;
            for (Col c : cols) {
                int y = top;
                if (!c.title.isEmpty()) y += 22;
                for (Object o : c.items) {
                    if (o instanceof N n) { n.y = y; y += n.h + GAP; }
                    else if (o instanceof Cap cp) {
                        cp.lines = m.wrap(cp.text, c.w, 6, cp.size, false, cp.mono);
                        cp.y = y;
                        y += cp.lines.size() * (int) Math.ceil(cp.size * m.lineHeight) + 6;
                    }
                }
                c.bottom = y;
                bottom = Math.max(bottom, y);
            }
            // ports: distinct y per edge on each side, ordered by the far end's centre
            for (Col c : cols) for (Object o : c.items) if (o instanceof N n) {
                n.right.sort(Comparator.comparingDouble((RE e) -> e.b.cy()).thenComparing(e -> e.id));
                for (int k = 0; k < n.right.size(); k++) n.right.get(k).sy = n.y + (int) Math.round((k + 1) * n.h / (n.right.size() + 1.0));
                n.left.sort(Comparator.comparingDouble((RE e) -> e.a.cy()).thenComparing(e -> e.id));
                for (int k = 0; k < n.left.size(); k++) n.left.get(k).ty = n.y + (int) Math.round((k + 1) * n.h / (n.left.size() + 1.0));
            }
            // every run gets a channel of its own: the left box's ports sit on multiples of 8, the right box's on multiples of 8 plus 4,
            // so a first run and a last run of two edges are never on one y; a jog of 4 px or less is a straight edge at the target's y
            for (RE e : edges) {
                e.sy = Math.max(e.a.y + 6, Math.min(e.a.y + e.a.h - 6, (int) Math.round(e.sy / 8.0) * 8));
                e.ty = Math.max(e.b.y + 6, Math.min(e.b.y + e.b.h - 6, (int) Math.round((e.ty - 4) / 8.0) * 8 + 4));
                if (Math.abs(e.sy - e.ty) <= 4 && e.ty >= e.a.y + 4 && e.ty <= e.a.y + e.a.h - 4) e.sy = e.ty;
            }
            // gutters: lanes for the edges that bend, then the widest label
            int[] gw = new int[Math.max(0, cols.size() - 1)];
            for (int g = 0; g < gw.length; g++) {
                List<RE> es = new ArrayList<>();
                for (RE e : edges) if (cols.get(g).items.contains(e.a) && cols.get(g + 1).items.contains(e.b)) es.add(e);
                List<RE> up = new ArrayList<>(), down = new ArrayList<>();
                for (RE e : es) { if (e.ty < e.sy) up.add(e); else if (e.ty > e.sy) down.add(e); }
                up.sort(Comparator.comparingInt((RE e) -> e.ty).thenComparing(e -> e.id));
                down.sort(Comparator.comparingInt((RE e) -> -e.ty).thenComparing(e -> e.id));
                int k = 0;
                for (RE e : up) e.lane = k++;
                for (RE e : down) e.lane = k++;
                double lw = 0;
                for (RE e : es) if (!e.label.isEmpty()) lw = Math.max(lw, m.width(e.label, DET, false, false));
                gw[g] = Math.max(GUT, k * LANE + 16 + (lw > 0 ? (int) Math.ceil(lw) + 12 : 0));
            }
            int x = MARGIN;
            for (int g = 0; g < cols.size(); g++) {
                cols.get(g).x = x;
                x += cols.get(g).w + (g < gw.length ? gw[g] : 0);
            }
            sc.w = x + MARGIN;
            // emit boxes, captions, titles
            for (Col c : cols) {
                if (!c.title.isEmpty()) free("col-" + (uniq++), c.title, c.x, top + 8, DET, "start", "muted", false, true);
                for (Object o : c.items) {
                    if (o instanceof N n) { n.x = c.x; build(n); }
                    else if (o instanceof Cap cp) {
                        int lh = (int) Math.ceil(cp.size * m.lineHeight);
                        for (int k = 0; k < cp.lines.size(); k++)
                            free("cap-" + (uniq++), cp.lines.get(k), c.x, cp.y + (int) Math.round(cp.size * 0.82) + k * lh, cp.size, "start", cp.color, cp.mono, false);
                    }
                }
            }
            // edges
            for (int g = 0; g < gw.length; g++) {
                int gx0 = cols.get(g).x + cols.get(g).w, gx1 = cols.get(g + 1).x;
                int nl = 0;
                for (RE e : edges) if (cols.get(g).items.contains(e.a) && cols.get(g + 1).items.contains(e.b)) nl = Math.max(nl, e.lane + 1);
                for (RE e : edges) {
                    if (!(cols.get(g).items.contains(e.a) && cols.get(g + 1).items.contains(e.b))) continue;
                    Ln l = new Ln();
                    l.id = e.id; l.from = e.a.id; l.to = e.b.id; l.dashed = e.dashed;
                    l.marker = e.plain ? "none" : e.hollow ? "hollow" : "arrow";
                    List<int[]> pts = new ArrayList<>();
                    pts.add(new int[] { gx0, e.sy });
                    if (e.sy != e.ty) {
                        int lx = gx0 + 8 + e.lane * LANE;
                        pts.add(new int[] { lx, e.sy }); pts.add(new int[] { lx, e.ty });
                    }
                    pts.add(new int[] { gx1, e.ty });
                    if (e.rev) Collections.reverse(pts);
                    l.pts = pts;
                    sc.lines.add(l);
                    if (!e.label.isEmpty()) free("lbl-" + (uniq++), e.label, gx0 + 8 + nl * LANE + 6, e.ty - 5, DET, "start", "muted", false, false);
                }
            }
            return bottom;
        }

        /** Footer lines, the canvas height. */
        void finish(int bottom, List<String> legend) {
            int y = bottom + 8;
            for (String l : legend) {
                for (String line : m.wrap(l, sc.w - 2 * MARGIN, 3, DET, false, false)) {
                    free("legend-" + (uniq++), line, MARGIN, y + (int) Math.round(DET * 0.82), DET, "start", "muted", false, false);
                    y += LHD;
                }
            }
            sc.h = y + MARGIN;
        }
    }

    static Map<String, String> iconCache = new HashMap<>();

    /** The `d` of a skill icon (24-unit viewBox), `stroke:` prefixed when the file declares data-mode="stroke". */
    static String icon(String name) throws IOException {
        String k = name;
        if (iconCache.containsKey(k)) return iconCache.get(k);
        Path p = root.resolve(".claude/skills/project-diagrams/references/icons/" + name + ".svg");
        if (!Files.isRegularFile(p)) throw new Fail(".claude/skills/project-diagrams/references/icons/" + name + ".svg is missing — an icon the `diagrams.image_icons` map of " + EXT + " names");
        String t = Files.readString(p, StandardCharsets.UTF_8);
        Matcher dm = Pattern.compile("\\bd=\"([^\"]+)\"").matcher(t);
        StringBuilder d = new StringBuilder();
        while (dm.find()) d.append(d.length() > 0 ? " " : "").append(dm.group(1));
        String v = (t.contains("data-mode=\"stroke\"") ? "stroke:" : "") + d;
        iconCache.put(k, v);
        return v;
    }

    /** The table or topic a backticked token of a Side effects row names (`customers.status` names `customers`), or null. */
    static String touchId(Map<String, Object> facts, String tok) {
        for (String t : new String[] { tok, tok.split("\\.")[0] })
            for (String k : new String[] { "table:", "topic:" }) if (facts.containsKey(k + t)) return k + t;
        return null;
    }

    /** What a Side effects row touches: [fact id, the backticked text that names it] — a word of a backticked span (`UPDATE customers.status`) that is a table or topic, then the table whose name holds `outbox` when the row says "outbox" and exactly one does. `ambiguous` collects a row whose outbox is not one table. */
    static List<String[]> touches(Map<String, Object> facts, String side, List<String> ambiguous) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher bm = Pattern.compile("`([^`]+)`").matcher(side);
        while (bm.find()) for (String w : bm.group(1).split("\\s+")) {
            String tid = touchId(facts, w.replaceAll("[,;:.]+$", ""));
            if (tid != null && seen.add(tid)) out.add(new String[] { tid, bm.group(1) });
        }
        if (Pattern.compile("\\boutbox\\b", Pattern.CASE_INSENSITIVE).matcher(side).find() && out.stream().noneMatch(x -> x[0].startsWith("table:") && x[0].contains("outbox"))) {
            List<String> ob = new ArrayList<>();
            for (String k : facts.keySet()) if (k.startsWith("table:") && k.contains("outbox")) ob.add(k);
            if (ob.size() == 1) out.add(new String[] { ob.get(0), "" });
            else if (ambiguous != null) ambiguous.add("outbox");
        }
        return out;
    }

    static String camelize(String slug) {
        StringBuilder b = new StringBuilder();
        for (String w : slug.split("-")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    // ── kind: architecture ──

    static Scene sceneArchitecture(Resolved r, Map<String, Object> facts, Identity id) throws IOException {
        PL pl = new PL(id);
        Map<String, Object> arch = map(get(readJson(EXT), "diagrams", "architecture"));
        List<String> colTitles = strList(arch.get("columns"));
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        Set<String> cited = new HashSet<>(r.factIds);
        Map<String, N> byType = new LinkedHashMap<>();
        Col cIn = new Col(), cUse = new Col(), cDom = new Col(), cPort = new Col(), cOut = new Col();
        cIn.title = colTitles.get(0); cUse.title = colTitles.get(1); cDom.title = colTitles.get(2); cPort.title = colTitles.get(3); cOut.title = colTitles.get(4);
        List<String> types = new ArrayList<>();
        for (String k : facts.keySet()) if (k.startsWith("type:") && cited.contains(k)) types.add(k);
        types.sort(Comparator.comparing((String k) -> (String) map(facts.get(k)).get("pkg")).thenComparing(k -> k));
        Map<String, Integer> support = new TreeMap<>();
        for (String k : facts.keySet()) if (k.startsWith("type:")) { Map<String, Object> t = map(facts.get(k)); if ("support".equals(t.get("role"))) support.merge((String) t.get("pkg"), 1, Integer::sum); }
        String lastPkgIn = "", lastPkgOut = "";
        List<String> domainSupport = new ArrayList<>();
        for (String k : facts.keySet()) {
            if (!k.startsWith("type:")) continue;
            Map<String, Object> t = map(facts.get(k));
            if ("domain".equals(t.get("role"))) domainSupport.add((String) t.get("label"));
        }
        Set<String> implemented = new HashSet<>();
        for (String k : types) for (Object io : list(map(facts.get(k)).get("implements"))) implemented.add((String) io);
        for (String k : types) {
            Map<String, Object> t = map(facts.get(k));
            String role = (String) t.get("role"), name = (String) t.get("label"), pkg = (String) t.get("pkg");
            if (role.equals("port") && !implemented.contains(k)) continue; // a port nothing implements is a data type of the package
            Col c; String pal = pl.pal("role:" + role);
            switch (role) {
                case "inbound" -> c = cIn;
                case "usecase" -> c = cUse;
                case "port" -> c = cPort;
                case "outbound" -> c = cOut;
                case "aggregate", "event" -> c = cDom;
                default -> { continue; }
            }
            if ((c == cIn || c == cOut) && !pkg.equals(c == cIn ? lastPkgIn : lastPkgOut)) {
                Cap cp = new Cap(); cp.text = pkg; cp.mono = true; cp.size = pl.DET; c.items.add(cp);
                if (c == cIn) lastPkgIn = pkg; else lastPkgOut = pkg;
            }
            List<String> det = new ArrayList<>();
            if (role.equals("aggregate")) det.add("aggregate");
            if (role.equals("event")) det.add("event");
            N n = pl.node(k, pal, name, det);
            n.pill = role.equals("port"); n.heavy = role.equals("aggregate");
            c.items.add(n);
            byType.put(k, n);
        }
        for (String k : facts.keySet()) {
            if (!k.startsWith("drift:") || !cited.contains(k)) continue;
            Map<String, Object> d = map(facts.get(k));
            N n = pl.node(k, "drift", (String) d.get("type"), List.of("spec only · " + d.get("uc")));
            n.dashed = true;
            cDom.items.add(n);
        }
        if (!domainSupport.isEmpty()) {
            Cap cp = new Cap(); cp.size = pl.DET; cp.text = String.valueOf(arch.get("domain_caption")).replace("{names}", String.join(" · ", domainSupport)); cDom.items.add(cp);
        }
        // support types, one caption per adapter package that drew a box
        String sup = String.valueOf(arch.get("support_caption"));
        long totalSupport = support.entrySet().stream().filter(e -> e.getKey().startsWith("infrastructure") || e.getKey().startsWith("adapter") || e.getKey().startsWith("app")).mapToLong(Map.Entry::getValue).sum();
        if (totalSupport > 0) { Cap cp = new Cap(); cp.size = pl.DET; cp.text = sup.replace("{n}", String.valueOf(totalSupport)); cIn.items.add(cp); }
        List<RE> es = new ArrayList<>();
        for (String k : types) {
            Map<String, Object> t = map(facts.get(k));
            N a = byType.get(k);
            if (a == null) continue;
            String role = (String) t.get("role");
            for (Object uo : list(t.get("uses"))) {
                N b = byType.get((String) uo);
                if (b == null) continue;
                String brole = (String) map(facts.get((String) uo)).get("role");
                if (role.equals("inbound") && brole.equals("usecase")) es.add(edge(a, b, k + ">" + uo, false, false, false));
                else if (role.equals("usecase") && (brole.equals("aggregate") || brole.equals("event"))) es.add(edge(a, b, k + ">" + uo, false, false, false));
            }
            if (role.equals("outbound"))
                for (Object io : list(t.get("implements"))) {
                    N p = byType.get((String) io);
                    if (p != null) es.add(edge(p, a, k + ">" + io, true, true, true));
                }
        }
        int bottom = pl.columns(List.of(cIn, cUse, cDom, cPort, cOut), es, pl.TOP);
        pl.finish(bottom, strList(arch.get("legend")));
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        return pl.sc;
    }

    /** `rev`: the arrowhead sits on `a`, the left node (an outbound adapter implementing a port). */
    static RE edge(N a, N b, String eid, boolean dashed, boolean hollow, boolean rev) {
        RE e = new RE();
        e.a = a; e.b = b; e.id = eid; e.dashed = dashed; e.hollow = hollow; e.rev = rev;
        return e;
    }

    // ── kind: blueprint (C4 level 2) ──

    static Scene sceneBlueprint(Resolved r, Map<String, Object> facts, Identity id) throws IOException {
        PL pl = new PL(id);
        Map<String, Object> dg = dgBlock();
        Map<String, Object> icons = map(dg.get("image_icons"));
        Map<String, Object> proto = map(dg.get("protocols"));
        Map<String, Object> bp = map(dg.get("blueprint"));
        String style = r.style == null ? "brand" : r.style;
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        Set<String> cited = new HashSet<>(r.factIds);
        Col cL = new Col(), cApp = new Col(), cSvc = new Col();
        List<N> dev = new ArrayList<>();
        N app = null, client = null;
        List<N> externals = new ArrayList<>(), services = new ArrayList<>();
        List<RE> es = new ArrayList<>();
        Map<String, N> byId = new LinkedHashMap<>();
        for (String k : facts.keySet()) {
            if (!cited.contains(k)) continue;
            Map<String, Object> f = map(facts.get(k));
            if (k.startsWith("service:")) {
                boolean isApp = Boolean.TRUE.equals(f.get("is_app")), isDev = Boolean.TRUE.equals(f.get("dev_tool"));
                String slug = (String) f.get("slug"), generic = (String) f.get("generic");
                List<String> det = new ArrayList<>(strList(f.get("detail_lines")));
                N n = pl.node(k, isApp ? pl.pal("app") : isDev ? "neutral" : pl.pal("service"), (String) f.get("label"), det);
                n.mono = true; n.monoN = isApp ? 0 : 1;
                if (isApp) { n.mono = false; }
                n.dashed = isDev; n.heavy = isApp;
                styleNode(pl, n, style, slug, isApp ? "app" : generic, dg, id);
                if (isDev) n.opacity = 0.7;
                pl.measure(n);
                byId.put(k, n);
                if (isApp) app = n; else if (isDev) dev.add(n); else services.add(n);
            } else if (k.startsWith("external:")) {
                boolean cl = k.equals("external:client");
                List<String> det = cl ? List.of("HTTP") : List.of("external system");
                N n = pl.node(k, "neutral", (String) f.get("label"), cl ? List.of() : det);
                n.dashed = !cl;
                styleNode(pl, n, style, "", cl ? "client" : "external", dg, id);
                pl.measure(n);
                byId.put(k, n);
                if (cl) client = n; else externals.add(n);
            }
        }
        if (app == null) throw new Fail("the blueprint view needs the compose service that builds the application — docker-compose.yml has none (fact 'service:app' not found)");
        cL.title = String.valueOf(bp.get("col_left")); cApp.title = String.valueOf(bp.get("col_app")); cSvc.title = String.valueOf(bp.get("col_services"));
        if (client != null) cL.items.add(client);
        cL.items.addAll(externals);
        cApp.items.add(app);
        cSvc.items.addAll(services);
        if (client != null) { RE e = edge(client, app, "client>app", false, false, false); e.label = String.valueOf(proto.getOrDefault("@client", "")); es.add(e); }
        for (N x : externals) { RE e = edge(x, app, "app>" + x.id, true, false, true); es.add(e); }
        for (N s : services) {
            Map<String, Object> f = map(facts.get(s.id));
            if (!Boolean.TRUE.equals(f.get("referenced"))) continue;
            RE e = edge(app, s, "app>" + s.id, false, false, false);
            e.label = String.valueOf(proto.getOrDefault((String) f.get("slug"), ""));
            if (e.label.equals("null")) e.label = "";
            es.add(e);
        }
        int bottom = pl.columns(new ArrayList<>(cL.items.isEmpty() ? List.of(cApp, cSvc) : List.of(cL, cApp, cSvc)), es, pl.TOP);
        // the development tooling band, under the application column
        if (!dev.isEmpty()) {
            int y = bottom + 12;
            pl.free("band-title", String.valueOf(bp.get("band_dev")), cApp.x, y + 8, pl.DET, "start", "muted", false, true);
            y += 22;
            int x = cApp.x, rowH = 0;
            for (N n : dev) {
                n.x = x; n.y = y;
                pl.build(n);
                x += n.w + pl.GUT / 2; rowH = Math.max(rowH, n.h);
            }
            pl.sc.w = Math.max(pl.sc.w, x + pl.MARGIN);
            bottom = y + rowH + pl.GAP;
        }
        pl.finish(bottom, strList(bp.get("legend")));
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        return pl.sc;
    }

    /** Boxes: a rectangle with text. Icons and brand: the logo above the name, no rectangle. */
    static void styleNode(PL pl, N n, String style, String slug, String generic, Map<String, Object> dg, Identity id) throws IOException {
        if (style.equals("boxes")) return;
        n.noBox = true; n.mid = true;
        String brand = "";
        if (style.equals("brand") && !slug.isEmpty()) {
            String d = icon(slug);
            n.ico = d; n.icoStroke = false;
            Object c = get(id.m, "brand", slug);
            n.icoFill = c instanceof String s ? (s.startsWith("#") ? s : id.s(s)) : id.s("ink");
        } else {
            String d = icon("generic-" + generic);
            boolean st = d.startsWith("stroke:");
            n.ico = st ? d.substring(7) : d; n.icoStroke = st;
            n.icoFill = id.stroke(n.pal);
        }
    }

    // ── kind: use case map ──

    static Scene sceneMap(Resolved r, Map<String, Object> facts, Identity id) throws IOException {
        PL pl = new PL(id);
        Map<String, Object> mp = map(get(readJson(EXT), "diagrams", "map"));
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        Set<String> cited = new HashSet<>(r.factIds);
        Col cT = new Col(), cU = new Col(), cX = new Col();
        cT.title = String.valueOf(mp.get("col_trigger")); cU.title = String.valueOf(mp.get("col_case")); cX.title = String.valueOf(mp.get("col_touch"));
        Map<String, N> trig = new LinkedHashMap<>(), touch = new LinkedHashMap<>(), pub = new LinkedHashMap<>();
        List<String> ambiguous = new ArrayList<>();
        List<RE> es = new ArrayList<>();
        List<String> ucs = new ArrayList<>();
        for (String k : facts.keySet()) if (k.startsWith("uc:") && cited.contains(k)) ucs.add(k);
        Map<String, N> un = new LinkedHashMap<>();
        for (String k : ucs) {
            Map<String, Object> u = map(facts.get(k));
            boolean so = Boolean.TRUE.equals(u.get("spec_only"));
            N n = pl.node(k, so ? "drift" : pl.pal("uc"), u.get("id") + "  " + u.get("title"), so ? List.of(String.valueOf(mp.get("spec_only"))) : List.of());
            n.dashed = so;
            un.put(k, n); cU.items.add(n);
        }
        for (String k : ucs) {
            Map<String, Object> u = map(facts.get(k));
            String tk = (String) u.get("trigger_kind");
            if (tk != null && !tk.isEmpty()) {
                N t = trig.computeIfAbsent(tk, x -> pl.node("trigger:" + x, pl.pal("trigger"), x, List.of()));
                es.add(edge(t, un.get(k), "trigger:" + tk + ">" + k, false, false, false));
            }
            String side = String.valueOf(u.get("side_effects"));
            List<String> verbs = strList(u.get("verbs"));
            for (String[] tc : touches(facts, side, ambiguous)) {
                String tid = tc[0];
                N t = touch.computeIfAbsent(tid, x -> {
                    N tn = pl.node(x, pl.pal(x.startsWith("table:") ? "table" : "topic"), x.substring(x.indexOf(':') + 1), List.of(x.startsWith("table:") ? "table" : "topic"));
                    tn.mono = true; tn.monoN = 1;
                    return tn;
                });
                RE e = edge(un.get(k), t, k + ">" + tid, false, false, false);
                if (verbs.size() == 1 && !tc[1].isEmpty()) e.label = verbs.get(0);
                es.add(e);
                // the outbox is relayed to the topic the case's spec files name: the row says "relay" and a topic fact appears in a spec file
                if (tid.contains("outbox") && Pattern.compile("\\brelay\\b", Pattern.CASE_INSENSITIVE).matcher(side).find())
                    for (String kb : strList(u.get("kebab"))) if (facts.containsKey("topic:" + kb)) {
                        N pn = pub.computeIfAbsent("pub:" + kb, x -> { N tn = pl.node(x, pl.pal("topic"), kb, List.of("topic")); tn.mono = true; tn.monoN = 1; return tn; });
                        RE r2 = edge(t, pn, tid + ">" + kb, false, false, false);
                        r2.label = "relay";
                        if (es.stream().noneMatch(z -> z.id.equals(r2.id))) es.add(r2);
                    }
            }
        }
        cT.items.addAll(trig.values());
        cX.items.addAll(touch.values());
        List<Col> cols = new ArrayList<>(List.of(cT, cU, cX));
        if (!pub.isEmpty()) { Col cP = new Col(); cP.title = String.valueOf(mp.get("col_published")); cP.items.addAll(pub.values()); cols.add(cP); }
        int bottom = pl.columns(cols, es, pl.TOP);
        // the backlog, faded, under everything
        List<String> bls = new ArrayList<>();
        for (String k : facts.keySet()) if (k.startsWith("bl:") && cited.contains(k)) bls.add(k);
        if (!bls.isEmpty()) {
            int y = bottom + 12, w = pl.sc.w - 2 * pl.MARGIN;
            Box fr = new Box(); fr.id = "backlog"; fr.shape = "plain"; fr.fill = "none"; fr.stroke = id.stroke("neutral"); fr.dashed = true; fr.dash = "6 4"; fr.x = pl.MARGIN; fr.y = y; fr.w = w; fr.opacity = 0.8;
            int ty = y + pl.PAD;
            Txt hd = txt("backlog#t", String.valueOf(mp.get("backlog")), pl.MARGIN + pl.PAD, ty + (int) Math.round(pl.DET * 0.82), pl.DET, "start", -1);
            hd.bold = true; fr.inner.add(hd);
            ty += pl.LHD;
            for (String k : bls) {
                Map<String, Object> b = map(facts.get(k));
                String line = b.get("label") + "  " + b.get("detail") + (strList(b.get("deps")).isEmpty() ? "" : "  ← " + String.join(", ", strList(b.get("deps"))));
                for (String l : pl.m.wrap(line, w - 2 * pl.PAD, 2, pl.DET, false, true)) {
                    Txt t = txt("backlog#" + fr.inner.size(), l, pl.MARGIN + pl.PAD, ty + (int) Math.round(pl.DET * 0.82), pl.DET, "start", -1);
                    t.mono = true; fr.inner.add(t); ty += pl.LHD;
                }
            }
            fr.h = ty - y + pl.PAD;
            pl.sc.nodes.add(fr);
            bottom = y + fr.h + pl.GAP;
        }
        List<String> leg = new ArrayList<>(strList(mp.get("legend")));
        if (!ambiguous.isEmpty()) leg.add(String.valueOf(mp.get("outbox_caption")));
        pl.finish(bottom, leg);
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        return pl.sc;
    }

    // ── kind: one use case's flow — a sequence panel and a decision panel ──

    static final class SeqP { String id, alias; boolean actor; String label() { return alias != null ? alias : id; } }

    static final class SeqM {
        String from, to, text = "", side = "", of = "";
        boolean dashed, note;
        int num;
        List<String> lines = new ArrayList<>();
    }

    static String circled(int n) { return n >= 1 && n <= 20 ? String.valueOf((char) (0x2460 + n - 1)) : "(" + n + ")"; }

    static boolean typeLooking(String s) { return s.matches("[A-Z][A-Za-z0-9]+"); }

    /** ✓ when the participant is a type of src/, ✗ when it names one and none exists; prose names carry no badge. */
    static void badge(PL pl, Map<String, Object> facts, String name, boolean prose, int x, int y) {
        if (prose || !typeLooking(name)) return;
        Box b = new Box();
        b.id = "badge-" + (pl.uniq++); b.shape = "badge"; b.x = x; b.y = y; b.w = 8; b.h = 8;
        b.badge = facts.containsKey("type:" + name) ? "ok" : "bad";
        pl.sc.nodes.add(b);
    }

    /** The sequence panel; returns its bottom y. `x0` is its left edge, `y0` its top. */
    static int panelSequence(PL pl, Map<String, Object> facts, String src, int x0, int y0, int[] widthOut) {
        List<SeqP> ps = new ArrayList<>();
        List<SeqM> ms = new ArrayList<>();
        boolean auto = false;
        Map<String, SeqP> byId = new LinkedHashMap<>();
        int n = 0;
        for (String raw : src.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("sequenceDiagram") || l.startsWith("%%")) continue;
            if (l.equals("autonumber")) { auto = true; continue; }
            Matcher pm = Pattern.compile("^(actor|participant)\\s+(\\w+)(?:\\s+as\\s+(.+))?$").matcher(l);
            if (pm.matches()) {
                SeqP p = byId.computeIfAbsent(pm.group(2), k -> { SeqP q = new SeqP(); q.id = k; ps.add(q); return q; });
                p.actor = pm.group(1).equals("actor");
                if (pm.group(3) != null) p.alias = pm.group(3).trim();
                continue;
            }
            Matcher nm = Pattern.compile("^Note\\s+(right of|left of|over)\\s+(\\w+)(?:\\s*,\\s*\\w+)?\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(l);
            if (nm.matches()) {
                SeqM m = new SeqM(); m.note = true; m.side = nm.group(1).toLowerCase(Locale.ROOT).startsWith("left") ? "left" : "right"; m.of = nm.group(2); m.text = nm.group(3).trim();
                ms.add(m);
                byId.computeIfAbsent(m.of, k -> { SeqP q = new SeqP(); q.id = k; ps.add(q); return q; });
                continue;
            }
            Matcher mm = Pattern.compile("^(\\w+)\\s*(-->>|->>|-->|->|--x|-x)\\s*(\\w+)\\s*:\\s*(.*)$").matcher(l);
            if (mm.matches()) {
                SeqM m = new SeqM(); m.from = mm.group(1); m.to = mm.group(3); m.dashed = mm.group(2).startsWith("--"); m.text = mm.group(4).trim();
                if (auto) m.num = ++n;
                ms.add(m);
                for (String k : new String[] { m.from, m.to }) byId.computeIfAbsent(k, kk -> { SeqP q = new SeqP(); q.id = kk; ps.add(q); return q; });
            }
        }
        int P = ps.size();
        if (P == 0) { widthOut[0] = 0; return y0; }
        Metrics m = pl.m;
        int maxMsg = pl.id.i("project", "max_msg_width"), maxNote = pl.id.i("project", "max_note_width");
        // header boxes
        List<N> hs = new ArrayList<>();
        for (SeqP p : ps) {
            String role = facts.containsKey("type:" + p.id) ? (String) map(facts.get("type:" + p.id)).get("role") : null;
            String pal = role != null ? pl.pal("role:" + role) : (p.alias != null && p.alias.toLowerCase(Locale.ROOT).contains("adapter")) ? pl.pal("role:inbound") : "neutral";
            N h = new N(); h.id = "participant:" + p.id; h.pal = pal; h.mid = true; h.pill = p.actor && role == null && p.alias == null && false;
            h.head = m.wrap(p.label(), 150, 2, pl.LBL, true, false);
            double w = 0;
            for (String s : h.head) w = Math.max(w, m.width(s, pl.LBL, true, false));
            h.w = Math.max(88, (int) Math.ceil(w) + 2 * pl.PAD);
            h.h = 2 * pl.PAD + 2 * pl.LHL;
            hs.add(h);
        }
        // message and note lines, then the gap each lifeline pair needs
        double[] gap = new double[Math.max(1, P - 1)];
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < P; i++) idx.put(ps.get(i).id, i);
        double leftExtra = 0, rightExtra = 0;
        for (int i = 0; i + 1 < P; i++) gap[i] = (hs.get(i).w + hs.get(i + 1).w) / 2.0 + 24;
        for (SeqM s : ms) {
            if (s.note) {
                s.lines = m.wrap(s.text, maxNote, 3, pl.DET, false, false);
                double w = 0;
                for (String t : s.lines) w = Math.max(w, m.width(t, pl.DET, false, false));
                double need = w + 2 * 8 + 24;
                int i = idx.get(s.of);
                if (s.side.equals("right")) { if (i + 1 < P) gap[i] = Math.max(gap[i], need); else rightExtra = Math.max(rightExtra, need); }
                else { if (i > 0) gap[i - 1] = Math.max(gap[i - 1], need); else leftExtra = Math.max(leftExtra, need); }
            } else {
                String t = (s.num > 0 ? circled(s.num) + " " : "") + s.text;
                s.lines = m.wrap(t, maxMsg, 2, pl.DET, false, false);
                double w = 0;
                for (String q : s.lines) w = Math.max(w, m.width(q, pl.DET, false, false));
                int a = idx.get(s.from), b = idx.get(s.to);
                if (a == b) { if (a + 1 < P) gap[a] = Math.max(gap[a], w + 48); else rightExtra = Math.max(rightExtra, w + 48); }
                else gap[Math.min(a, b)] = Math.max(gap[Math.min(a, b)], w + 20);
            }
        }
        double[] cx = new double[P];
        cx[0] = x0 + leftExtra + hs.get(0).w / 2.0;
        for (int i = 1; i < P; i++) cx[i] = cx[i - 1] + gap[i - 1];
        int right = (int) Math.ceil(Math.max(cx[P - 1] + hs.get(P - 1).w / 2.0, cx[P - 1] + rightExtra));
        widthOut[0] = right - x0;
        // rows
        int hy = y0;
        int y = hy + hs.get(0).h + 14;
        List<Object[]> rows = new ArrayList<>();
        for (SeqM s : ms) {
            int nl = s.lines.size();
            if (s.note) { int h = nl * pl.LHD + 16; rows.add(new Object[] { s, y, h }); y += h + 14; }
            else { int h = nl * pl.LHD + 20; rows.add(new Object[] { s, y, h }); y += h + 6; }
        }
        int endY = y + 4;
        for (int i = 0; i < P; i++) {
            Ln l = new Ln(); l.id = "lifeline-" + ps.get(i).id; l.dashed = true; l.marker = "none";
            l.pts.add(new int[] { (int) Math.round(cx[i]), hy + hs.get(i).h }); l.pts.add(new int[] { (int) Math.round(cx[i]), endY });
            pl.sc.lines.add(l);
        }
        for (int i = 0; i < P; i++) {
            N h = hs.get(i); h.x = (int) Math.round(cx[i] - h.w / 2.0); h.y = hy;
            pl.build(h);
            SeqP p = ps.get(i);
            badge(pl, facts, p.id, p.alias != null, h.x + h.w, h.y);
        }
        int mi = 0;
        for (Object[] row : rows) {
            SeqM s = (SeqM) row[0];
            int ry = (Integer) row[1], rh = (Integer) row[2];
            if (s.note) {
                int i = idx.get(s.of);
                double w = 0;
                for (String t : s.lines) w = Math.max(w, m.width(t, pl.DET, false, false));
                N nb = new N(); nb.id = "note-" + (mi++); nb.pal = "neutral"; nb.det = s.lines; nb.w = (int) Math.ceil(w) + 16; nb.h = rh;
                nb.x = s.side.equals("right") ? (int) Math.round(cx[i]) + 8 : (int) Math.round(cx[i]) - 8 - nb.w; nb.y = ry;
                Box b = new Box();
                b.id = nb.id; b.shape = "plain"; b.x = nb.x; b.y = nb.y; b.w = nb.w; b.h = nb.h; b.fill = pl.id.fill("neutral"); b.stroke = pl.id.stroke("neutral"); b.rx = 4;
                for (int k = 0; k < s.lines.size(); k++) b.inner.add(txt(nb.id + "#" + k, s.lines.get(k), nb.x + 8, nb.y + 8 + (int) Math.round(pl.DET * 0.82) + k * pl.LHD, pl.DET, "start", -1));
                pl.sc.nodes.add(b);
            } else {
                int a = idx.get(s.from), bb = idx.get(s.to);
                int ay = ry + s.lines.size() * pl.LHD + 8;
                Ln l = new Ln(); l.id = "msg-" + (mi++); l.from = null; l.dashed = s.dashed;
                if (a == bb) {
                    int x = (int) Math.round(cx[a]);
                    l.pts.add(new int[] { x, ay - 4 }); l.pts.add(new int[] { x + 24, ay - 4 }); l.pts.add(new int[] { x + 24, ay + 8 }); l.pts.add(new int[] { x, ay + 8 });
                    for (int k = 0; k < s.lines.size(); k++) pl.free("m-" + (pl.uniq++), s.lines.get(k), x + 32, ry + (int) Math.round(pl.DET * 0.82) + k * pl.LHD, pl.DET, "start", "muted", false, false);
                } else {
                    l.pts.add(new int[] { (int) Math.round(cx[a]), ay }); l.pts.add(new int[] { (int) Math.round(cx[bb]), ay });
                    int lx = (int) Math.round(Math.min(cx[a], cx[bb])) + 8;
                    for (int k = 0; k < s.lines.size(); k++) pl.free("m-" + (pl.uniq++), s.lines.get(k), lx, ay - 8 - (s.lines.size() - 1 - k) * pl.LHD, pl.DET, "start", "muted", false, false);
                }
                pl.sc.lines.add(l);
            }
        }
        return endY;
    }

    static final class FNode { String id, text, kind; List<String> out = new ArrayList<>(), outLabel = new ArrayList<>(); int row = -1, col = -1; N n; }

    static Map<String, FNode> parseFlow(String src) {
        Map<String, FNode> nodes = new LinkedHashMap<>();
        Pattern def = Pattern.compile("^(\\(\\((.*?)\\)\\)|\\(\\[(.*?)\\]\\)|\\{(.*?)\\}|\\[(.*?)\\]|\\((.*?)\\))");
        Pattern edge = Pattern.compile("^\\s*(\\w+)(.*?)\\s*-->\\s*(?:\\|([^|]*)\\|\\s*)?(\\w+)(.*)$");
        for (String raw : src.split("\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("flowchart") || l.startsWith("%%")) continue;
            Matcher e = edge.matcher(l);
            if (e.matches()) {
                FNode a = def(nodes, e.group(1), e.group(2).trim(), def), b = def(nodes, e.group(4), e.group(5).trim(), def);
                a.out.add(b.id); a.outLabel.add(e.group(3) == null ? "" : e.group(3).trim());
                continue;
            }
            Matcher one = Pattern.compile("^(\\w+)(\\S.*)$").matcher(l);
            if (one.matches()) def(nodes, one.group(1), one.group(2).trim(), def);
        }
        return nodes;
    }

    static FNode def(Map<String, FNode> nodes, String id, String shape, Pattern def) {
        FNode f = nodes.computeIfAbsent(id, k -> { FNode x = new FNode(); x.id = k; x.text = k; x.kind = "process"; return x; });
        if (!shape.isEmpty()) {
            Matcher d = def.matcher(shape);
            if (d.find()) {
                String t;
                if (d.group(2) != null) { f.kind = "start"; t = d.group(2); }
                else if (d.group(3) != null) { f.kind = "terminal"; t = d.group(3); }
                else if (d.group(4) != null) { f.kind = "decision"; t = d.group(4); }
                else if (d.group(5) != null) { f.kind = "process"; t = d.group(5); }
                else { f.kind = "terminal"; t = d.group(6); }
                f.text = t.replaceAll("^\"|\"$", "").trim();
            }
        }
        return f;
    }

    /** The decision panel: a spine of decisions down, the exits to the right. Returns the bottom y. */
    static int panelFlow(PL pl, Map<String, Object> facts, String src, int x0, int y0, int[] widthOut) {
        Map<String, FNode> nodes = parseFlow(src);
        if (nodes.isEmpty()) { widthOut[0] = 0; return y0; }
        Metrics m = pl.m;
        FNode start = nodes.values().iterator().next();
        Set<String> leaf = new HashSet<>();
        for (FNode f : nodes.values()) if (f.out.isEmpty()) leaf.add(f.id);
        // rows and columns: the spine continues through the non-leaf child, the leaves exit to the right
        List<FNode> order = new ArrayList<>();
        int[] colRows = new int[16];
        Deque<Object[]> todo = new ArrayDeque<>();
        todo.add(new Object[] { start, 0, 0 });
        while (!todo.isEmpty()) {
            Object[] t = todo.poll();
            FNode f = (FNode) t[0];
            int row = (Integer) t[1], col = (Integer) t[2];
            if (f.row >= 0) continue;
            f.row = row; f.col = col; order.add(f);
            List<FNode> kids = new ArrayList<>();
            for (String o : f.out) kids.add(nodes.get(o));
            FNode cont = null;
            for (FNode k : kids) if (!leaf.contains(k.id) && k.row < 0) { cont = k; break; }
            if (cont == null) {
                // all children are leaves: the one declared last continues below, the others exit right
                FNode last = kids.get(kids.size() - 1);
                int ex = 1;
                for (FNode k : kids) if (k != last && k.row < 0) { k.row = row; k.col = col + ex++; order.add(k); }
                if (last.row < 0) { last.row = row + 1; last.col = col; order.add(last); }
            } else {
                int ex = 1;
                for (FNode k : kids) if (k != cont && k.row < 0) {
                    if (leaf.contains(k.id)) { k.row = row; k.col = col + ex++; order.add(k); }
                    else todo.add(new Object[] { k, row, col + ex++ });
                }
                todo.add(new Object[] { cont, row + 1, col });
            }
        }
        // sizes
        int maxCol = 0, maxRow = 0;
        for (FNode f : order) { maxCol = Math.max(maxCol, f.col); maxRow = Math.max(maxRow, f.row); }
        for (FNode f : order) {
            N n = new N(); n.id = "flow:" + f.id; n.mid = true;
            if (f.kind.equals("decision")) {
                n.pal = "drift";
                List<String> ls = m.wrap(f.text, 170, 3, pl.DET, false, false);
                double w = 0;
                for (String s : ls) w = Math.max(w, m.width(s, pl.DET, false, false));
                n.det = ls;
                int tw = (int) Math.ceil(w), th = ls.size() * pl.LHD;
                n.w = Math.max(120, (int) Math.ceil(tw / 0.45)); n.h = Math.max(64, (int) Math.ceil(th / 0.45));
            } else {
                n.pal = f.kind.equals("start") ? pl.pal("role:inbound") : f.kind.equals("terminal") && f.col == 0 ? pl.pal("role:usecase") : "neutral";
                n.pill = true;
                n.det = m.wrap(f.text, 230, 2, pl.DET, false, false);
                double w = 0;
                for (String s : n.det) w = Math.max(w, m.width(s, pl.DET, false, false));
                n.w = (int) Math.ceil(w) + 28; n.h = n.det.size() * pl.LHD + 14;
            }
            f.n = n;
        }
        int[] colW = new int[maxCol + 1], rowH = new int[maxRow + 1];
        for (FNode f : order) { colW[f.col] = Math.max(colW[f.col], f.n.w); rowH[f.row] = Math.max(rowH[f.row], f.n.h); }
        int vgap = 40, hgap = 72;
        int[] colX = new int[maxCol + 1], rowY = new int[maxRow + 1];
        int x = x0;
        for (int c = 0; c <= maxCol; c++) { colX[c] = x; x += colW[c] + hgap; }
        int y = y0;
        for (int r = 0; r <= maxRow; r++) { rowY[r] = y; y += rowH[r] + vgap; }
        widthOut[0] = x - hgap - x0;
        for (FNode f : order) {
            N n = f.n;
            n.x = colX[f.col] + (colW[f.col] - n.w) / 2; n.y = rowY[f.row] + (rowH[f.row] - n.h) / 2;
            Box b = new Box();
            b.id = n.id; b.x = n.x; b.y = n.y; b.w = n.w; b.h = n.h; b.fill = pl.id.fill(n.pal); b.stroke = pl.id.stroke(n.pal);
            b.shape = f.kind.equals("decision") ? "diamond" : "pill";
            if (f.kind.equals("decision")) b.sw = 0;
            int cx = n.x + n.w / 2, ty = n.y + (n.h - n.det.size() * pl.LHD) / 2;
            for (int k = 0; k < n.det.size(); k++) {
                Txt t = txt(n.id + "#" + k, n.det.get(k), cx, ty + (int) Math.round(pl.DET * 0.82) + k * pl.LHD, pl.DET, "middle", -1);
                t.color = "ink"; b.inner.add(t);
            }
            pl.sc.nodes.add(b);
        }
        // edges
        for (FNode f : order) {
            for (int k = 0; k < f.out.size(); k++) {
                FNode t = nodes.get(f.out.get(k));
                if (t.n == null) continue;
                Ln l = new Ln(); l.id = "flowedge-" + f.id + "-" + t.id;
                int fx = f.n.x + f.n.w / 2, fy = f.n.y + f.n.h / 2;
                String lab = f.outLabel.get(k);
                if (t.row == f.row && t.col > f.col) {
                    int a = f.n.x + f.n.w, bx = t.n.x;
                    l.pts.add(new int[] { a, fy }); l.pts.add(new int[] { bx, (t.n.y + t.n.h / 2) });
                    if (!lab.isEmpty()) pl.free("fl-" + (pl.uniq++), lab, a + 6, fy - 5, pl.DET, "start", "muted", false, false);
                } else {
                    int tx = t.n.x + t.n.w / 2;
                    l.pts.add(new int[] { fx, f.n.y + f.n.h }); l.pts.add(new int[] { tx, t.n.y });
                    if (fx != tx) { l.pts.clear(); l.pts.add(new int[] { fx, f.n.y + f.n.h }); l.pts.add(new int[] { fx, t.n.y - 14 }); l.pts.add(new int[] { tx, t.n.y - 14 }); l.pts.add(new int[] { tx, t.n.y }); }
                    if (!lab.isEmpty()) pl.free("fl-" + (pl.uniq++), lab, fx + 8, f.n.y + f.n.h + 16, pl.DET, "start", "muted", false, false);
                }
                pl.sc.lines.add(l);
            }
        }
        return y - vgap;
    }

    static Scene sceneFlow(Resolved r, Map<String, Object> facts, Identity id) throws IOException {
        PL pl = new PL(id);
        Map<String, Object> fl = map(get(readJson(EXT), "diagrams", "flow"));
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        Set<String> cited = new HashSet<>(r.factIds);
        String uc = r.uc;
        Map<String, Object> seq = map(facts.get("mermaid:" + uc + ":sequence")), flow = map(facts.get("mermaid:" + uc + ":flowchart"));
        if (!cited.contains("mermaid:" + uc + ":sequence")) seq = null;
        if (!cited.contains("mermaid:" + uc + ":flowchart")) flow = null;
        int[] w1 = new int[1], w2 = new int[1];
        int y = pl.TOP;
        int width = 0;
        if (seq != null) {
            pl.free("cap-seq", String.valueOf(fl.get("sequence_caption")), pl.MARGIN, y + 8, pl.DET, "start", "muted", false, true);
            y = panelSequence(pl, facts, (String) seq.get("source"), pl.MARGIN, y + 22, w1) + 24;
            width = Math.max(width, w1[0]);
        }
        if (flow != null) {
            if (seq != null) {
                Ln d = new Ln(); d.id = "divider"; d.marker = "none"; d.pts.add(new int[] { pl.MARGIN, y - 12 }); d.pts.add(new int[] { pl.MARGIN + Math.max(width, 480), y - 12 });
                pl.sc.lines.add(d);
                y += 12;
            }
            pl.free("cap-flow", String.valueOf(fl.get("flow_caption")), pl.MARGIN, y + 8, pl.DET, "start", "muted", false, true);
            y = panelFlow(pl, facts, (String) flow.get("source"), pl.MARGIN, y + 30, w2) + 16;
            width = Math.max(width, w2[0]);
        }
        if (seq == null && flow == null) {
            // no mermaid block: the header chain, trigger → adapter → use case → what it touches
            Map<String, Object> u = map(facts.get("uc:" + uc));
            Col c0 = new Col(), c1 = new Col(), c2 = new Col(), c3 = new Col();
            List<RE> es = new ArrayList<>();
            N t = pl.node("chain:trigger", pl.pal("role:inbound"), String.valueOf(u.get("trigger_kind")).isEmpty() ? "trigger" : (String) u.get("trigger_kind"), List.of());
            c0.items.add(t);
            String ucType = camelize(((String) u.get("folder")).replaceFirst("^UC-\\d+-", "")) + "UseCase";
            N uct = pl.node("type:" + ucType, pl.pal("role:usecase"), ucType, List.of());
            c2.items.add(uct);
            List<N> adapters = new ArrayList<>();
            for (String k : facts.keySet()) {
                if (!k.startsWith("type:")) continue;
                Map<String, Object> tf = map(facts.get(k));
                if ("inbound".equals(tf.get("role")) && strList(tf.get("uses")).contains("type:" + ucType)) { N a = pl.node(k, pl.pal("role:inbound"), (String) tf.get("label"), List.of()); adapters.add(a); c1.items.add(a); }
            }
            List<String> verbs = strList(u.get("verbs"));
            for (String[] tc : touches(facts, String.valueOf(u.get("side_effects")), null)) {
                // the side effect itself, as the spec writes it: `UPDATE customers.status`
                N x = pl.node("side:" + tc[0], pl.pal(tc[0].startsWith("table:") ? "table" : "topic"), tc[1].isEmpty() ? tc[0].substring(tc[0].indexOf(':') + 1) : tc[1], List.of());
                x.mono = true;
                c3.items.add(x);
                es.add(edge(uct, x, "uc>" + tc[0], false, false, false));
            }
            if (adapters.isEmpty()) es.add(edge(t, uct, "t>uc", false, false, false));
            else for (N a : adapters) { es.add(edge(t, a, "t>" + a.id, false, false, false)); es.add(edge(a, uct, a.id + ">uc", false, false, false)); }
            List<Col> cols = new ArrayList<>();
            cols.add(c0);
            if (!c1.items.isEmpty()) cols.add(c1);
            cols.add(c2);
            if (!c3.items.isEmpty()) cols.add(c3);
            if (c1.items.isEmpty()) { /* trigger and use case are adjacent */ }
            int bottom = pl.columns(cols, es, y);
            for (Col c : cols) for (Object o : c.items) if (o instanceof N n && n.id.startsWith("type:")) badge(pl, facts, n.id.substring(5), false, n.x + n.w, n.y);
            y = bottom;
            pl.free("fallback", String.valueOf(fl.get("fallback_caption")), pl.MARGIN, y + 14, pl.DET, "start", "muted", false, false);
            y += 24;
            width = pl.sc.w - 2 * pl.MARGIN;
        }
        pl.sc.w = Math.max(pl.sc.w, width + 2 * pl.MARGIN);
        pl.sc.h = y + pl.MARGIN;
        pl.sc.title = r.title; pl.sc.caption = r.caption;
        return pl.sc;
    }

    // ───────────────────────────── project views ─────────────────────────────

    /** Default views: what `/project-diagrams` with no argument draws, and a view.json for every name asked for that has none. */
    static void ensureProjectViews(List<String> names, Map<String, Object> facts) throws IOException {
        List<String> want = new ArrayList<>();
        if (names.isEmpty()) {
            want.addAll(List.of("architecture", "blueprint", "use-cases/map"));
        } else for (String n : names) want.add(projectView(n));
        Map<String, Object> info = map(facts.get("project:info"));
        String app = (String) info.getOrDefault("app_name", root.getFileName().toString());
        boolean hasUc = facts.keySet().stream().anyMatch(k -> k.startsWith("uc:"));
        boolean hasSvc = facts.keySet().stream().anyMatch(k -> k.startsWith("service:"));
        for (String name : want) {
            Path vj = root.resolve(OUT).resolve(name).resolve("view.json");
            if (Files.isRegularFile(vj)) continue;
            Map<String, Object> v = new TreeMap<>();
            List<Object> cite = new ArrayList<>();
            String tail = name.substring(name.lastIndexOf('/') + 1);
            if (name.equals("architecture")) {
                v.put("kind", "architecture"); v.put("title", app + " · architecture"); v.put("caption", "blueprint " + info.get("label") + " · base package " + info.get("base"));
                cite.add("type:*"); cite.add("pkg:*");
                if (facts.keySet().stream().anyMatch(k -> k.startsWith("drift:"))) cite.add("drift:*");
            } else if (name.equals("blueprint")) {
                if (!hasSvc) { System.out.println("ℹ️  no docker-compose.yml with services — the blueprint view is skipped"); continue; }
                v.put("kind", "blueprint"); v.put("title", app + " · containers"); v.put("style", "brand");
                cite.add("service:*");
                if (facts.keySet().stream().anyMatch(k -> k.startsWith("external:"))) cite.add("external:*");
            } else if (name.equals("use-cases/map")) {
                if (!hasUc) { System.out.println("ℹ️  no docs/use-cases/ — the use case map and the flow views are skipped"); continue; }
                v.put("kind", "usecase-map"); v.put("title", app + " · use cases");
                cite.add("uc:*");
                if (facts.keySet().stream().anyMatch(k -> k.startsWith("bl:"))) cite.add("bl:*");
                if (facts.keySet().stream().anyMatch(k -> k.startsWith("table:"))) cite.add("table:*");
                if (facts.keySet().stream().anyMatch(k -> k.startsWith("topic:"))) cite.add("topic:*");
            } else if (name.startsWith("use-cases/UC-")) {
                Matcher um = Pattern.compile("^(UC-\\d+)-").matcher(tail);
                String uc = um.find() ? um.group(1) : "";
                if (!facts.containsKey("uc:" + uc)) { System.out.println("ℹ️  no use case " + tail + " under docs/use-cases/ — skipped"); continue; }
                v.put("kind", "usecase-flow"); v.put("uc", uc);
                v.put("title", map(facts.get("uc:" + uc)).get("id") + " · " + map(facts.get("uc:" + uc)).get("title"));
                cite.add("uc:" + uc);
                for (String m : new String[] { "sequence", "flowchart" }) if (facts.containsKey("mermaid:" + uc + ":" + m)) cite.add("mermaid:" + uc + ":" + m);
                for (String k : facts.keySet()) if (k.startsWith("table:") || k.startsWith("topic:")) cite.add(k);
                for (String k : facts.keySet()) if (k.startsWith("type:")) cite.add(k);
            } else continue;
            v.put("facts", cite);
            Files.createDirectories(vj.getParent());
            write(vj, Json.write(v));
        }
    }

    static Resolved resolveProject(String name, Map<String, Object> facts, List<String> errs) throws IOException {
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
        Resolved r = new Resolved();
        r.name = name; r.kind = (String) v.get("kind"); r.title = (String) v.get("title");
        r.caption = v.get("caption") instanceof String c ? c : "";
        r.style = v.get("style") instanceof String st ? st : null;
        r.uc = v.get("uc") instanceof String u ? u : null;
        int before = errs.size();
        if (!PROJECT_KINDS.contains(r.kind)) { errs.add(rel + " $.kind: a project view is architecture, blueprint, usecase-map or usecase-flow; '" + r.kind + "' draws the harness (--source harness)."); return null; }
        List<Object> fl = list(v.get("facts"));
        if (fl == null || fl.isEmpty()) { errs.add(rel + " $.facts: a " + r.kind + " view cites the facts it draws. Fix: list fact ids or selectors such as \"type:*\"."); return null; }
        Set<String> ids = new LinkedHashSet<>();
        for (int k = 0; k < fl.size(); k++) ids.addAll(expand((String) fl.get(k), facts, rel + " $.facts[" + k + "]", errs));
        r.factIds.addAll(ids);
        if (r.kind.equals("usecase-flow")) {
            if (r.uc == null) errs.add(rel + " $.uc: a usecase-flow view names its use case. Fix: \"uc\": \"UC-NNN\".");
            else if (!facts.containsKey("uc:" + r.uc)) errs.add(rel + " $.uc: " + missing("uc:" + r.uc, facts));
        }
        if (r.style != null && !r.kind.equals("blueprint")) errs.add(rel + " $.style: only the blueprint view has a style.");
        return errs.size() > before ? null : r;
    }

    /** One icon as a standalone SVG: the image the drawio and Excalidraw files embed. */
    static String iconSvg(Box b) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"" + b.icoPath + "\"" + (b.icoStroke
                ? " fill=\"none\" stroke=\"" + b.icoFill + "\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"" : " fill=\"" + b.icoFill + "\"") + "/></svg>";
    }
}
