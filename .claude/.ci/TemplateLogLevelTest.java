///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves no Java exemplar writes a log line at a level `logging.md` § Format and level
// does not give it — `DEBUG` and `TRACE` only when a use case or a request asks, and a request
// refused before the use case logged at `WARN` without the exception.
//
// Why this test exists: every `DEBUG` line `bank-app` carried came from a template or from a
// rule that allowed it — five rejections in `ApiExceptionHandler`, two in
// `SecurityExceptionHandler`, three empty job passes (decision 0157). At `DEBUG` a rejection is
// invisible in production; the unreadable-body handler also passed the parser's exception, whose
// message can echo the body, harmless only while the line was off. The executor copies an
// exemplar's shape, so either one left in a template ships into every project. A template is
// never a case that asked for `DEBUG`, so here the rule has no exception.
//
// What it holds, each failure named by file and line:
//   - no exemplar body calls `.debug(` or `.trace(`, or guards on `isDebugEnabled` /
//     `isTraceEnabled`;
//   - every `@ExceptionHandler` for a request refused before the use case — the types in
//     REJECTIONS — logs at `WARN`, and never passes the exception (`e`) or its message.
//
// The leading comment block of each exemplar and every comment line are skipped: prose may name
// a level. Out of scope: `claude-code-architect-designer/templates/`, whose Java exemplar is the
// shape of an `ArchHook.java` mode, not of generated code.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class TemplateLogLevelTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final String OUT_OF_SCOPE = "claude-code-architect-designer/templates/";

    static final Pattern BELOW_INFO = Pattern.compile("\\.(debug|trace)\\s*\\(|\\bis(Debug|Trace)Enabled\\b");

    static final Set<String> REJECTIONS = Set.of(
            "HttpMessageNotReadableException", "MethodArgumentNotValidException",
            "MethodArgumentTypeMismatchException", "MissingIdempotencyKeyException",
            "InvalidApiVersionException", "QuotaExceededException",
            "AuthenticationException", "AccessDeniedException");

    static final Pattern HANDLER = Pattern.compile("@ExceptionHandler\\(\\{?([^)}]*)\\}?\\)");
    static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(\\w+)\\s*\\(");
    static final Pattern EXCEPTION_ARG = Pattern.compile(",\\s*e\\s*\\)\\s*;|\\be\\.(getMessage|getLocalizedMessage)\\(\\)");

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().endsWith(".java.example"))
                    .filter(p -> !SKILLS.relativize(p).toString().replace('\\', '/').contains(OUT_OF_SCOPE))
                    .sorted().toList();
        }

        List<String> violations = new ArrayList<>();
        int handlers = 0;
        for (Path file : exemplars) {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String code = blankComments(text);
            String rel = file.toString().replace('\\', '/');

            Matcher below = BELOW_INFO.matcher(code);
            while (below.find()) {
                violations.add(rel + ":" + lineOf(text, below.start()) + "  `" + below.group().trim()
                        + "` — DEBUG/TRACE only when a use case or a request asks, never in a template");
            }

            Matcher h = HANDLER.matcher(code);
            while (h.find()) {
                boolean rejection = Arrays.stream(h.group(1).split(","))
                        .map(t -> t.trim().replace(".class", ""))
                        .anyMatch(REJECTIONS::contains);
                if (!rejection) continue;
                handlers++;
                int open = code.indexOf('{', h.end());
                int close = matchingBrace(code, open);
                String body = code.substring(open, close + 1);
                int line = lineOf(text, h.start());

                List<String> levels = new ArrayList<>();
                Matcher call = LOG_CALL.matcher(body);
                while (call.find()) {
                    levels.add(call.group(1));
                    int end = body.indexOf(';', call.end());
                    if (EXCEPTION_ARG.matcher(body.substring(call.start(), end + 1)).find()) {
                        violations.add(rel + ":" + lineOf(text, open + call.start())
                                + "  a refused request's log line passes the exception or its message — its text"
                                + " can echo the payload; log method, path and the reason's category");
                    }
                }
                if (!levels.contains("warn")) {
                    violations.add(rel + ":" + line + "  handler for " + h.group(1).trim()
                            + " has no log.warn — a request refused before the use case is logged at WARN");
                }
            }
        }

        if (!violations.isEmpty()) {
            violations.forEach(v -> System.out.println("  ✗ " + v));
            System.out.println("❌ " + violations.size() + " log line(s) at the wrong level in exemplars."
                    + " logging.md § Format and level.");
            System.exit(1);
        }
        System.out.println("✅ " + exemplars.size() + " Java exemplars write no DEBUG/TRACE; " + handlers
                + " rejection handlers log at WARN without the exception.");
    }

    /** The text with every comment replaced by spaces, newlines kept, so offsets and lines still match. */
    static String blankComments(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        boolean inString = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                i++;
            } else if (c == '"') {
                inString = true;
                i++;
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
        for (int k = from; k < to; k++) if (out.charAt(k) != '\n') out.setCharAt(k, ' ');
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
