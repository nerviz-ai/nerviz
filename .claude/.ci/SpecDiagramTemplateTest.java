///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves every Mermaid diagram in a spec template names only what the same template's
// text already defines, opens with a diagram type `spec-diagrams.md` allows, and — for a
// sequence diagram — carries one message per step of the numbered list it is drawn from.
//
// Why this test exists: a spec template is the only source of a spec's shape (0115), so a
// diagram that drifts from its own text there ships into every spec generated from it. The norm
// that a diagram adds no name and no fact is `.claude/rules/spec-diagrams.md` (0143); this holds
// the exemplars to it. It proves the templates only — nothing under `.claude/.ci/` travels into a
// generated project, where the norm stays a norm.
//
// What counts as a name: every token with an uppercase letter (`User`, `confirmEmail`,
// `ACTIVE`), every participant id and alias, the field names inside a `class { }` body, and the
// first word of a state transition's label. Lowercase prose on a message is not a name.
//
// Offline and in milliseconds: no Mermaid renderer. Whether GitHub draws the block is not proven
// here — the three allowed types are the ones it renders.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class SpecDiagramTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");

    static final Set<String> ALLOWED = Set.of("sequenceDiagram", "classDiagram", "stateDiagram-v2");

    static final Map<String, List<String>> REQUIRED = Map.of(
            "use-case-design/templates/use-case-spec.md.example", List.of("sequenceDiagram"),
            "domain-modeling/templates/domain-spec.md.example", List.of("classDiagram", "stateDiagram-v2"));

    static final Set<String> KEYWORDS = Set.of("Note", "LR", "RL", "TB", "BT", "TD");

    static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    static final Pattern MESSAGE = Pattern.compile("^\\s*\\S+?\\s*(-->>|->>|--x|-x|--\\)|-\\)|-->|->)");
    static final Pattern PARTICIPANT = Pattern.compile("^\\s*(participant|actor)\\s+(\\S+)(\\s+as\\s+(.+))?$");
    static final Pattern TRANSITION_LABEL = Pattern.compile("-->.*?:\\s*([A-Za-z_][A-Za-z0-9_]*)");
    static final Pattern STEP = Pattern.compile("^\\d+\\.\\s");

    record Block(int line, List<String> body) {}

    public static void main(String[] args) throws Exception {
        List<Path> templates;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            templates = s.filter(p -> p.toString().endsWith(".md.example")).sorted().toList();
        }
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Path file : templates) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = SKILLS.relativize(file).toString().replace('\\', '/');
            List<Block> blocks = blocks(lines);
            List<String> required = REQUIRED.getOrDefault(rel, List.of());
            if (blocks.isEmpty() && required.isEmpty()) continue;
            String text = outside(lines);
            Set<String> types = new HashSet<>();
            for (Block b : blocks) {
                checked++;
                String where = rel + ":" + b.line();
                String type = b.body().isEmpty() ? "" : b.body().get(0).trim();
                types.add(type);
                if (!ALLOWED.contains(type)) {
                    failures.add(where + "  opens with '" + type + "', not one of " + ALLOWED);
                    continue;
                }
                for (String name : names(type, b.body())) {
                    if (!present(text, name)) {
                        failures.add(where + "  '" + name + "' is in the diagram and nowhere in the text");
                    }
                }
                if (type.equals("sequenceDiagram")) {
                    long messages = b.body().stream().filter(l -> MESSAGE.matcher(l).find()).count();
                    long steps = steps(lines, b.line());
                    if (messages != steps) {
                        failures.add(where + "  " + messages + " messages for " + steps
                                + " numbered steps above it — one message per step");
                    }
                }
            }
            for (String t : required) {
                if (!types.contains(t)) failures.add(rel + "  has no " + t + " block");
            }
        }
        if (checked == 0) {
            System.out.println("❌ No mermaid block in any *.md.example under " + SKILLS + " — run from the repository root.");
            System.exit(1);
        }
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  ✗ " + f));
            System.out.println("❌ " + failures.size() + " spec template diagram problem(s). A diagram names only what"
                    + " its file's text defines and adds no fact (.claude/rules/spec-diagrams.md): put the name in the"
                    + " text first, or take it out of the diagram.");
            System.exit(1);
        }
        System.out.println("✅ " + checked + " spec template diagrams name only what their text defines.");
    }

    static List<Block> blocks(List<String> lines) {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).trim().equals("```mermaid")) continue;
            List<String> body = new ArrayList<>();
            int start = i + 1;
            for (i++; i < lines.size() && !lines.get(i).trim().equals("```"); i++) {
                if (!lines.get(i).isBlank()) body.add(lines.get(i));
            }
            out.add(new Block(start, body));
        }
        return out;
    }

    static String outside(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String l : lines) {
            if (!in && l.trim().equals("```mermaid")) { in = true; continue; }
            if (in && l.trim().equals("```")) { in = false; continue; }
            if (!in) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    static Set<String> names(String type, List<String> body) {
        Set<String> out = new LinkedHashSet<>();
        boolean inClass = false;
        for (String raw : body.subList(1, body.size())) {
            String l = raw.trim();
            Matcher p = PARTICIPANT.matcher(l);
            if (p.matches()) {
                out.add(p.group(2));
                if (p.group(4) != null) out.add(p.group(4).trim());
            }
            if (type.equals("classDiagram")) {
                if (l.startsWith("class ") && l.endsWith("{")) { inClass = true; }
                else if (l.equals("}")) { inClass = false; }
                else if (inClass && !l.startsWith("<<")) {
                    String[] parts = l.split("\\s+");
                    out.add(parts[parts.length - 1].replaceAll("[^A-Za-z0-9_]", ""));
                }
            }
            if (type.equals("stateDiagram-v2")) {
                Matcher t = TRANSITION_LABEL.matcher(l);
                if (t.find()) out.add(t.group(1));
            }
            Matcher m = TOKEN.matcher(l);
            while (m.find()) {
                String tok = m.group();
                if (!tok.equals(tok.toLowerCase(Locale.ROOT)) && !KEYWORDS.contains(tok)
                        && !ALLOWED.contains(tok) && !tok.equals("stateDiagram")) {
                    out.add(tok);
                }
            }
        }
        out.remove("");
        return out;
    }

    static boolean present(String text, String name) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(text).find();
    }

    static long steps(List<String> lines, int blockLine) {
        long n = 0;
        for (int i = blockLine - 2; i >= 0; i--) {
            String l = lines.get(i);
            if (l.startsWith("## ")) break;
            if (STEP.matcher(l).find()) n++;
        }
        return n;
    }
}
