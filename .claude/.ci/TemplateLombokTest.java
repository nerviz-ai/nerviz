///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves no Java exemplar writes by hand what `lombok.md` § Generated boilerplate says
// Lombok writes — the logger, a constructor that only assigns, the private modifier on every field.
//
// Why this test exists: `lombok.md` once listed `@RequiredArgsConstructor` as allowed and never
// named `@Slf4j`, so every template author chose, and 75 of 76 plain-assignment constructors and
// every logger in the templates were written by hand — 73 files also left out the `@FieldDefaults`
// the same norm already required (decision 0158). The executor copies an exemplar's shape, so a
// hand-written one ships into every project.
//
// What it holds, each failure named by file and line, over production-source exemplars:
//   - no `LoggerFactory.getLogger` outside a comment — `@Slf4j`;
//   - no class whose only constructor assigns each of its `final` fields from a parameter of the
//     same name and nothing else, with unannotated parameters of distinct types — that is
//     `@RequiredArgsConstructor`;
//   - a class or enum whose instance fields are all `private` carries `@FieldDefaults`, one that
//     carries it writes no access modifier on an instance field, and one with no instance field
//     does not carry it. Static fields are not read: Lombok's `@FieldDefaults` never touches
//     them, so each keeps the modifier it was written with.
// In `gof-design-patterns/templates/` and `domain-modeling/templates/` the exemplar shows a
// pattern's wiring or the model, so the constructor is kept written there: the constructor check
// holds only for a `@Service` class, and `@RequiredArgsConstructor` on any other class fails.
// Test-source exemplars (`test-architect/templates/`, any `*Test`, `*Tests`, `*IT`) import no
// Lombok at all: the Gradle build gives it to the main compilation only.
//
// Comments, string and character literals are blanked before reading, keeping every offset.
// Out of scope: `claude-code-architect-designer/templates/`, whose Java exemplar is the shape of
// an `ArchHook.java` mode, not of generated code.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class TemplateLombokTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final String OUT_OF_SCOPE = "claude-code-architect-designer/templates/";
    static final List<String> WRITTEN_CONSTRUCTOR_DIRS =
            List.of("gof-design-patterns/templates/", "domain-modeling/templates/");

    static final Pattern TYPE_DECL = Pattern.compile("(?<![.\\w@])(class|enum|record|interface)\\s+([A-Z]\\w*)");
    static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(\\s*\\((?:[^()]|\\([^()]*\\))*\\))?");
    static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", "final",
            "transient", "volatile");
    static final Set<String> ACCESS = Set.of("public", "protected", "private");
    static final Pattern ASSIGNMENT = Pattern.compile("this\\.(\\w+)\\s*=\\s*(\\w+)");

    record Field(String name, Set<String> modifiers, boolean initialized) {}
    record Member(String header, int headerStart, String block) {}

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().endsWith(".java.example"))
                    .filter(p -> !rel(p).contains(OUT_OF_SCOPE))
                    .sorted().toList();
        }

        List<String> violations = new ArrayList<>();
        int production = 0, types = 0;
        for (Path file : exemplars) {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String code = blankCommentsAndLiterals(text);
            String rel = file.toString().replace('\\', '/');

            if (isTestSource(file)) {
                Matcher m = Pattern.compile("\\bimport\\s+lombok\\.").matcher(code);
                while (m.find()) {
                    violations.add(rel + ":" + lineOf(text, m.start()) + "  Lombok in a test source — the Gradle"
                            + " build gives it to the main compilation only; write it by hand");
                }
                continue;
            }
            production++;
            boolean writtenConstructors = WRITTEN_CONSTRUCTOR_DIRS.stream().anyMatch(rel(file)::contains);

            Matcher logger = Pattern.compile("LoggerFactory\\s*\\.\\s*getLogger\\s*\\(").matcher(code);
            while (logger.find()) {
                violations.add(rel + ":" + lineOf(text, logger.start())
                        + "  a hand-written logger — @Slf4j (lombok.md § Generated boilerplate)");
            }

            Matcher decl = TYPE_DECL.matcher(code);
            while (decl.find()) {
                String kind = decl.group(1);
                if (!kind.equals("class") && !kind.equals("enum")) continue;
                String name = decl.group(2);
                int open = code.indexOf('{', decl.end());
                if (open < 0) continue;
                int close = matchingBrace(code, open);
                String prefix = prefixOf(code, decl.start());
                int line = lineOf(text, decl.start());
                types++;

                List<Field> fields = new ArrayList<>();
                List<Member> constructors = new ArrayList<>();
                readMembers(code, open, close, kind.equals("enum"), name, fields, constructors);

                boolean fieldDefaults = Pattern.compile("@(lombok\\.experimental\\.)?FieldDefaults\\b").matcher(prefix).find();
                boolean makeFinal = fieldDefaults && Pattern.compile("makeFinal\\s*=\\s*true").matcher(prefix).find();
                boolean service = Pattern.compile("@(org\\.springframework\\.stereotype\\.)?Service\\b").matcher(prefix).find();
                boolean requiredArgs = Pattern.compile("@(lombok\\.)?RequiredArgsConstructor\\b").matcher(prefix).find();

                List<Field> instance = fields.stream().filter(f -> !f.modifiers().contains("static")).toList();
                if (!instance.isEmpty() && !fieldDefaults
                        && instance.stream().allMatch(f -> f.modifiers().contains("private"))) {
                    violations.add(rel + ":" + line + "  " + name + ": every instance field is private and the type"
                            + " has no @FieldDefaults (lombok.md § @FieldDefaults)");
                }
                if (fieldDefaults && instance.stream().anyMatch(f -> f.modifiers().stream().anyMatch(ACCESS::contains))) {
                    violations.add(rel + ":" + line + "  " + name + ": @FieldDefaults and a written access modifier"
                            + " on an instance field — one style per type (lombok.md § @FieldDefaults)");
                }
                if (fieldDefaults && instance.isEmpty()) {
                    violations.add(rel + ":" + line + "  " + name + ": @FieldDefaults on a type with no instance field"
                            + " — it never touches a static one, so it does nothing here");
                }

                if (writtenConstructors && requiredArgs && !service) {
                    violations.add(rel + ":" + line + "  " + name + ": @RequiredArgsConstructor in a GoF or"
                            + " domain-model exemplar — its constructor is the point of the exemplar, keep it written");
                }
                if (writtenConstructors && !service) continue;
                if (constructors.size() != 1) continue;
                Member ctor = constructors.get(0);
                if (onlyAssigns(ctor, name, fields, makeFinal)) {
                    violations.add(rel + ":" + lineOf(text, ctor.headerStart()) + "  " + name + ": a constructor"
                            + " that only assigns its final fields — @RequiredArgsConstructor"
                            + " (lombok.md § Generated boilerplate)");
                }
            }
        }

        if (!violations.isEmpty()) {
            violations.forEach(v -> System.out.println("  ✗ " + v));
            System.out.println("❌ " + violations.size() + " hand-written Lombok boilerplate line(s) in exemplars."
                    + " lombok.md § Generated boilerplate.");
            System.exit(1);
        }
        System.out.println("✅ " + production + " production Java exemplars (" + types + " classes and enums) write"
                + " no logger, no plain-assignment constructor and no private modifier Lombok writes;"
                + " test exemplars import no Lombok.");
    }

    static String rel(Path p) {
        return SKILLS.relativize(p).toString().replace('\\', '/');
    }

    static boolean isTestSource(Path p) {
        String name = p.getFileName().toString().replace(".java.example", "");
        return rel(p).startsWith("test-architect/templates/")
                || name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT");
    }

    /** True when the constructor's body is only `this.x = x;`, one per parameter, over final fields. */
    static boolean onlyAssigns(Member ctor, String type, List<Field> fields, boolean makeFinal) {
        Matcher h = Pattern.compile("\\b" + type + "\\s*\\((.*)\\)\\s*$", Pattern.DOTALL).matcher(ctor.header());
        if (!h.find()) return false;
        String params = h.group(1).strip();
        if (params.isEmpty() || params.contains("@")) return false;

        List<String> paramNames = new ArrayList<>();
        Set<String> paramTypes = new HashSet<>();
        for (String p : splitTopLevel(params)) {
            String[] parts = p.strip().replaceAll("^final\\s+", "").split("\\s+(?=\\w+$)");
            if (parts.length != 2) return false;
            if (!paramTypes.add(parts[0].replaceAll("\\s+", ""))) return false;
            paramNames.add(parts[1]);
        }

        String[] statements = ctor.block().strip().split(";");
        List<String> assigned = new ArrayList<>();
        for (String s : statements) {
            if (s.isBlank()) continue;
            Matcher a = ASSIGNMENT.matcher(s.strip());
            if (!a.matches() || !a.group(1).equals(a.group(2))) return false;
            assigned.add(a.group(1));
        }
        if (!new HashSet<>(assigned).equals(new HashSet<>(paramNames)) || assigned.size() != paramNames.size()) {
            return false;
        }
        Map<String, Field> byName = fields.stream().collect(Collectors.toMap(Field::name, f -> f, (a, b) -> a));
        return assigned.stream().allMatch(n -> {
            Field f = byName.get(n);
            return f != null && !f.modifiers().contains("static")
                    && (f.modifiers().contains("final") || makeFinal);
        });
    }

    /** Fields and constructors declared directly in the body between `open` and `close`. */
    static void readMembers(String code, int open, int close, boolean isEnum, String type,
                            List<Field> fields, List<Member> constructors) {
        int start = open + 1;
        int paren = 0;
        boolean constantsPending = isEnum;
        for (int i = open + 1; i < close; i++) {
            char c = code.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '{' && paren == 0) {
                String header = code.substring(start, i);
                int end = matchingBrace(code, i);
                if (topLevelEquals(header)) {
                    i = end;
                    continue;
                }
                String stripped = stripAnnotations(header).strip();
                if (stripped.matches("(?s)(?:(?:public|protected|private)\\s+)?" + type + "\\s*\\(.*\\)\\s*")) {
                    constructors.add(new Member(header, start + leadingSpace(code, start, i),
                            code.substring(i + 1, end)));
                }
                i = end;
                start = end + 1;
            } else if (c == ';' && paren == 0) {
                String statement = code.substring(start, i);
                start = i + 1;
                if (constantsPending) {
                    constantsPending = false;
                    continue;
                }
                Field f = asField(statement);
                if (f != null) fields.add(f);
            }
        }
    }

    static int leadingSpace(String code, int from, int to) {
        int k = from;
        while (k < to && Character.isWhitespace(code.charAt(k))) k++;
        return k - from;
    }

    static Field asField(String statement) {
        String s = stripAnnotations(statement).strip();
        if (s.isEmpty()) return null;
        int eq = indexOfTopLevelEquals(s);
        String declaration = eq < 0 ? s : s.substring(0, eq);
        if (declaration.contains("(")) return null;
        String[] tokens = declaration.strip().split("\\s+");
        Set<String> modifiers = new HashSet<>();
        int k = 0;
        while (k < tokens.length && MODIFIERS.contains(tokens[k])) modifiers.add(tokens[k++]);
        if (tokens.length - k < 2) return null;
        return new Field(tokens[tokens.length - 1], modifiers, eq >= 0);
    }

    static String stripAnnotations(String s) {
        return ANNOTATION.matcher(s).replaceAll(" ");
    }

    static boolean topLevelEquals(String header) {
        return indexOfTopLevelEquals(stripAnnotations(header)) >= 0;
    }

    static int indexOfTopLevelEquals(String s) {
        int paren = 0, angle = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '<') angle++;
            else if (c == '>') angle--;
            else if (c == '=' && paren == 0 && angle <= 0
                    && (i + 1 >= s.length() || s.charAt(i + 1) != '=')
                    && (i == 0 || "!<>=".indexOf(s.charAt(i - 1)) < 0)) return i;
        }
        return -1;
    }

    static List<String> splitTopLevel(String params) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                out.add(params.substring(start, i));
                start = i + 1;
            }
        }
        out.add(params.substring(start));
        return out;
    }

    /** The annotations and modifiers written before a type declaration, back to the previous `;`, `{` or `}`. */
    static String prefixOf(String code, int declStart) {
        int depth = 0;
        int i = declStart - 1;
        for (; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            else if (depth == 0 && (c == ';' || c == '{' || c == '}')) break;
        }
        return code.substring(i + 1, declStart);
    }

    /** The text with every comment and the inside of every literal replaced by spaces, newlines kept. */
    static String blankCommentsAndLiterals(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("\"\"\"", i)) {
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? text.length() : end;
                blank(out, i + 3, end);
                i = end + 3;
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                char quote = text.charAt(i);
                int j = i + 1;
                while (j < text.length() && text.charAt(j) != quote && text.charAt(j) != '\n') {
                    if (text.charAt(j) == '\\') j++;
                    j++;
                }
                blank(out, i + 1, Math.min(j, text.length()));
                i = j + 1;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                end = end < 0 ? text.length() : end;
                blank(out, i, end);
                i = end;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? text.length() : end + 2;
                blank(out, i, end);
                i = end;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    static void blank(StringBuilder out, int from, int to) {
        for (int k = from; k < to && k < out.length(); k++) if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
    }

    static int matchingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        throw new IllegalStateException("unbalanced braces after offset " + open);
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
