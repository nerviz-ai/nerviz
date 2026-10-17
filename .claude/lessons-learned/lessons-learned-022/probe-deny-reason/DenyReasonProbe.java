import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sentinel hook for lessons-learned-022 § 4: which channel carries a denial's reason to the model,
 * under Claude Code and under the GitHub Copilot CLI.
 *
 * <p>Registered for {@code PreToolUse} ({@code pre}) and {@code PostToolUse} ({@code post}) with
 * {@code matcher: "Bash"}. It acts only when the payload holds {@code PROBE_PRE_<variant>} or
 * {@code PROBE_POST_<variant>} for its own event, and answers in the shape that variant names. Every
 * text it emits carries one marker per channel ({@code M_STDERR}, {@code M_STDOUT_TEXT},
 * {@code M_FLAT_REASON}, {@code M_NESTED_REASON}, {@code M_LEGACY_REASON}, {@code M_ADDCTX_FLAT},
 * {@code M_ADDCTX_NESTED}), so the transcript says which channel reached the model.
 *
 * <p>Exits 0 on anything else, an unreadable payload included: Copilot denies on every non-zero exit.
 * Logs each run to {@code .probe/deny-probe.log}, the raw payload to {@code .probe/deny-probe-payload.jsonl},
 * and the names — never the values — of the environment variables that could tell the runtimes apart.
 *
 * <p>An experiment, not a mode of {@code ArchHook.java}: it exists until the result is recorded, then
 * the design lands in {@code guard} and {@code check}. Script: {@code roteiro.pt-br.md} beside it.
 */
public class DenyReasonProbe {

    static final String RULE = " The rule and the way out would be named here.";

    public static void main(String[] args) {
        int exit = 0;
        try {
            String event = args.length > 0 ? args[0] : "?";
            String in = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            Path dir = Path.of(System.getenv().getOrDefault("CLAUDE_PROJECT_DIR", System.getProperty("user.dir")), ".probe");
            Files.createDirectories(dir);
            append(dir.resolve("deny-probe-payload.jsonl"), event + "\t" + in.strip());

            Matcher m = Pattern.compile("PROBE_(PRE|POST)_([A-Z0-9]+(?:_[A-Z0-9]+)*)").matcher(in);
            String token = m.find() ? m.group(1) + "_" + m.group(2) : "none";
            boolean mine = token.startsWith(event.toUpperCase() + "_");

            Answer a = mine ? answer(event, m.group(2)) : new Answer(null, null, 0);
            if (a.stdout != null) System.out.println(a.stdout);
            if (a.stderr != null) System.err.println(a.stderr);
            exit = Math.max(a.exit, 0);

            append(dir.resolve("deny-probe.log"), Instant.now() + " event=" + event + " token=" + token
                    + " acted=" + (mine && a.exit >= 0) + " exit=" + exit + " stdout=" + a.stdout
                    + " env=" + runtimeEnvNames());
        } catch (Exception e) {
            exit = 0;
        }
        System.exit(exit);
    }

    record Answer(String stdout, String stderr, int exit) {}

    static Answer answer(String event, String variant) {
        String stderr = "probe " + variant + ": M_STDERR." + RULE;
        String flat = "\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"probe " + variant + ": M_FLAT_REASON." + RULE + "\"";
        String nested = "\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\","
                + "\"permissionDecisionReason\":\"probe " + variant + ": M_NESTED_REASON." + RULE + "\"}";
        String legacy = "\"decision\":\"block\",\"reason\":\"probe " + variant + ": M_LEGACY_REASON." + RULE + "\"";
        if ("pre".equals(event)) {
            return switch (variant) {
                case "E2_STDERR" -> new Answer(null, stderr, 2);
                case "E2_TEXT" -> new Answer("probe " + variant + ": M_STDOUT_TEXT." + RULE, stderr, 2);
                case "E2_FLAT" -> new Answer("{" + flat + "}", stderr, 2);
                case "E2_NESTED" -> new Answer("{" + nested + "}", stderr, 2);
                case "E2_BOTH" -> new Answer("{" + flat + "," + nested + "}", stderr, 2);
                case "E0_FLAT" -> new Answer("{" + flat + "}", null, 0);
                case "E0_NESTED" -> new Answer("{" + nested + "}", null, 0);
                case "E0_BOTH" -> new Answer("{" + flat + "," + nested + "}", null, 0);
                case "E0_LEGACY" -> new Answer("{" + legacy + "}", null, 0);
                default -> new Answer(null, null, -1);
            };
        }
        String post = "{" + legacy + ",\"additionalContext\":\"probe " + variant + ": M_ADDCTX_FLAT." + RULE + "\","
                + "\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"probe " + variant
                + ": M_ADDCTX_NESTED." + RULE + "\"}}";
        return switch (variant) {
            case "E2_STDERR" -> new Answer(null, stderr, 2);
            case "E2_JSON" -> new Answer(post, stderr, 2);
            case "E0_JSON" -> new Answer(post, null, 0);
            default -> new Answer(null, null, -1);
        };
    }

    /** Names only: a value may be a token. */
    static String runtimeEnvNames() {
        TreeSet<String> names = new TreeSet<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().matches("(?i).*(COPILOT|GITHUB|GH_|CLAUDE|ANTHROPIC|VSCODE|TERM_PROGRAM).*")) names.add(e.getKey());
        }
        return names.toString();
    }

    static void append(Path file, String line) throws Exception {
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
