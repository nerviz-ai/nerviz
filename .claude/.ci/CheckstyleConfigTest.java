///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the two Checkstyle configs `project-bootstrap` writes into every project
// parse, and enforce what they claim — `checkstyle.xml.example` over production code,
// `checkstyle-test.xml.example` over `src/test`. A restricted identifier (`record`,
// `permits`) must fail both; a clean file must pass both. The test config also fails a
// throw-assertion lambda that makes more than one call (testing.md § Names and shape,
// decision 0113), and passes every one-call shape. The main config fails a string literal
// repeated past Sonar S1192's threshold, and only the main config (decision 0120).
//
// Why this test exists: the first version of the `IllegalIdentifierName` module relied on
// Checkstyle's default `format`, which a documentation page said rejects `record`. On the
// Checkstyle the projects actually pin (14.x) the default rejects `var` only: the module
// passed a test tree holding four `record` variables with 0 violations, and nothing in this
// repository could notice — the config is a template, it never runs here
// (lessons-learned-017 § 8, decision 0097). Asserting on real Checkstyle output is the only
// check that reads the value the way the build will.
//
// Why the latest release and not a pinned one: `project-bootstrap` resolves the Checkstyle
// version from Maven Central at generation time, never from memory (@CLAUDE.md invariant 8),
// so a new project gets the current release. This test resolves it the same way. The
// self-contained `-all` jar comes from the project's GitHub release, the artifact Checkstyle
// publishes for command-line use; Maven Central carries only the thin jar.

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class CheckstyleConfigTest {

    static final Path TEMPLATES = Paths.get(".claude/skills/project-bootstrap/templates");
    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] args) throws Exception {
        String version = latestCheckstyle();
        Path jar = download(version);
        Path work = Files.createTempDirectory("checkstyle-config-test");

        Path mainConfig = copy(TEMPLATES.resolve("checkstyle.xml.example"), work.resolve("checkstyle.xml"));
        Path testConfig = copy(TEMPLATES.resolve("checkstyle-test.xml.example"), work.resolve("checkstyle-test.xml"));

        // Written under src/ so the main config's exclusion filter (target/, generated-sources/)
        // does not skip them. Each one respects every size and naming limit of the main config,
        // so the only violation it can raise is the one it exists for.
        Path src = Files.createDirectories(work.resolve("src/example"));
        Path clean = write(src.resolve("Clean.java"), """
                package example;

                /** Holds one value. */
                public final class Clean {

                    private final int received;

                    public Clean(int received) {
                        this.received = received;
                    }

                    public int received() {
                        return received;
                    }
                }
                """);
        Path record = write(src.resolve("RecordName.java"), """
                package example;

                /** Names a local after a restricted identifier. */
                public final class RecordName {

                    private RecordName() {
                    }

                    public static int count() {
                        var record = 1;
                        return record;
                    }
                }
                """);
        Path permits = write(src.resolve("PermitsName.java"), """
                package example;

                /** Names a parameter after a restricted identifier. */
                public final class PermitsName {

                    private PermitsName() {
                    }

                    public static int twice(int permits) {
                        return permits + permits;
                    }
                }
                """);

        // code-quality.md § Comments: Javadoc is the only comment in production code. One
        // file per form the main config must reject, named by the check that owns it, and one
        // file holding every form it must let through — the two exceptions plus the string
        // literals a careless regex would read as comments (decision 0108).
        Map<String, Path> comments = new LinkedHashMap<>();
        comments.put("LineComment", write(src.resolve("LineComment.java"), """
                package example;

                /** Holds one value. */
                public final class LineComment {

                    private LineComment() {
                    }

                    public static int one() {
                        // explains what the next line does
                        return 1;
                    }
                }
                """));
        comments.put("TrailingComment", write(src.resolve("TrailingComment.java"), """
                package example;

                /** Holds one value. */
                public final class TrailingComment {

                    private TrailingComment() {
                    }

                    public static int one() {
                        return 1; // explains the value
                    }
                }
                """));
        comments.put("BlockComment", write(src.resolve("BlockComment.java"), """
                package example;

                /** Holds one value. */
                public final class BlockComment {

                    private BlockComment() {
                    }

                    public static int one() {
                        /* explains the value */
                        return 1;
                    }
                }
                """));
        comments.put("InvalidJavadocPosition", write(src.resolve("JavadocInBody.java"), """
                package example;

                /** Holds one value. */
                public final class JavadocInBody {

                    private JavadocInBody() {
                    }

                    public static int one() {
                        /** Explains the value. */
                        return 1;
                    }
                }
                """));
        comments.put("TodoComment", write(src.resolve("TodoInJavadoc.java"), """
                package example;

                /** Holds one value. */
                public final class TodoInJavadoc {

                    private TodoInJavadoc() {
                    }

                    /** TODO return the real value. */
                    public static int one() {
                        return 1;
                    }
                }
                """));
        Path allowed = write(src.resolve("AllowedComments.java"), """
                package example;

                /** Every comment form code-quality.md § Comments allows, and strings that only look like comments. */
                public final class AllowedComments {

                    private static final String URL = "http://example.com/a//b";
                    private static final String PATTERN = "/api/*";

                    private AllowedComments() {
                        // no instances: constants only
                    }

                    /** Joins the two constants. */
                    public static String joined() {
                        return URL + PATTERN; // NOSONAR
                    }

                    // spotless:off
                    /** Does nothing on purpose. */
                    public static void noop() {
                        // nothing to do: a hook point kept for subclasses of the caller
                    }
                    // spotless:on
                }
                """);

        // testing.md § Names and shape: the lambda of a throw assertion makes exactly one call.
        // One file per assertion name and per shape the test config must reject, and one file
        // holding every shape it must let through — a constructor under test, a method
        // reference, a one-statement block, and a lambda that is not a throw assertion's
        // (decision 0113).
        Map<String, Path> throwLambdas = new LinkedHashMap<>();
        throwLambdas.put("TwoCalls", throwLambda(src, "TwoCalls",
                "assertThatThrownBy(() -> service.handle(Command.of(1)));"));
        throwLambdas.put("NewArgument", throwLambda(src, "NewArgument",
                "assertThatThrownBy(() -> service.handle(new Command(1)));"));
        throwLambdas.put("QualifiedAssertion", throwLambda(src, "QualifiedAssertion",
                "Assertions.assertThatThrownBy(() -> service.handle(Command.of(1)));"));
        throwLambdas.put("IsThrownBy", throwLambda(src, "IsThrownBy",
                "assertThatExceptionOfType(InvalidEmail.class).isThrownBy(() -> Email.of(raw.trim()));"));
        throwLambdas.put("BlockOfTwo", throwLambda(src, "BlockOfTwo",
                "assertThatCode(() -> { service.open(); service.close(); }).doesNotThrowAnyException();"));
        throwLambdas.put("CatchThrowable", throwLambda(src, "CatchThrowable",
                "catchThrowable(() -> Money.of(Amount.of(-1)));"));
        throwLambdas.put("CatchThrowableOfType", throwLambda(src, "CatchThrowableOfType",
                "catchThrowableOfType(InvalidMoney.class, () -> new Money(Amount.of(-1)));"));
        throwLambdas.put("CatchException", throwLambda(src, "CatchException",
                "catchException(() -> service.handle(Command.of(1)));"));
        throwLambdas.put("ChainedCall", throwLambda(src, "ChainedCall",
                "assertThrows(NotFound.class, () -> repository.find(id).orElseThrow());"));
        throwLambdas.put("AssertThrowsExactly", throwLambda(src, "AssertThrowsExactly",
                "assertThrowsExactly(NotFound.class, () -> service.handle(Command.of(1)));"));
        Path oneCall = throwLambda(src, "OneCall", """
                assertThatThrownBy(() -> new Money(-1)).isInstanceOf(InvalidMoney.class);
                        assertThatThrownBy(() -> service.handle(command)).hasMessage(code.name());
                        assertThatExceptionOfType(InvalidEmail.class).isThrownBy(() -> Email.of(invalid));
                        assertThrows(NotFound.class, () -> service.handle(command));
                        assertThatThrownBy(service::handle);
                        assertThatCode(() -> { service.handle(command); }).doesNotThrowAnyException();
                        commands.forEach(c -> { service.handle(c); audit.log(c); });""");

        // code-quality.md: no magic strings — S1192's numbers. A 5-character literal three times in
        // one production file is flagged; twice, a 4-character literal, and literals inside
        // annotations are not. The test config must not flag it: S1192 skips test files
        // (decision 0120).
        Path repeatedLiteral = write(src.resolve("RepeatedLiteral.java"), """
                package example;

                /** Repeats one literal three times. */
                public final class RepeatedLiteral {

                    private RepeatedLiteral() {
                    }

                    public static String first() {
                        return "Invalid request";
                    }

                    public static String second() {
                        return "Invalid request";
                    }

                    public static String third() {
                        return "Invalid request";
                    }
                }
                """);
        Path allowedLiterals = write(src.resolve("AllowedLiterals.java"), """
                package example;

                import java.util.List;

                /** Repetitions S1192 lets through, so the build must too. */
                public final class AllowedLiterals {

                    private AllowedLiterals() {
                    }

                    public static List<String> twice() {
                        return List.of("Invalid request", "Invalid request");
                    }

                    public static List<String> shortLiteral() {
                        return List.of("none", "none", "none");
                    }

                    @SuppressWarnings("unchecked")
                    public static Object first(Object value) {
                        return value;
                    }

                    @SuppressWarnings("unchecked")
                    public static Object second(Object value) {
                        return value;
                    }

                    @SuppressWarnings("unchecked")
                    public static Object third(Object value) {
                        return value;
                    }
                }
                """);

        // error-handling.md § A caught exception is never silent (decision 0153): an empty catch
        // fails with or without a comment; a catch that logs, rethrows or answers passes.
        Map<String, Path> emptyCatches = new LinkedHashMap<>();
        emptyCatches.put("CommentedCatch", write(src.resolve("CommentedCatch.java"), """
                package example;

                /** Parses one number. */
                public final class CommentedCatch {

                    private CommentedCatch() {
                    }

                    /** Parses the value. */
                    public static int parse(String value) {
                        int parsed = 0;
                        try {
                            parsed = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            // answered as zero by decision of the spec
                        }
                        return parsed;
                    }
                }
                """));
        emptyCatches.put("BareCatch", write(src.resolve("BareCatch.java"), """
                package example;

                /** Parses one number. */
                public final class BareCatch {

                    private BareCatch() {
                    }

                    /** Parses the value. */
                    public static int parse(String value) {
                        int parsed = 0;
                        try {
                            parsed = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                        }
                        return parsed;
                    }
                }
                """));
        Path handledCatch = write(src.resolve("HandledCatch.java"), """
                package example;

                import java.util.Optional;
                import java.util.logging.Logger;

                /** Every handler error-handling.md admits. */
                public final class HandledCatch {

                    private static final Logger LOG = Logger.getLogger("example");

                    private HandledCatch() {
                    }

                    /** The exception is the call's answer. */
                    public static Optional<Integer> parse(String value) {
                        try {
                            return Optional.of(Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            return Optional.empty();
                        }
                    }

                    /** Recovered, and logged. */
                    public static int parseOrZero(String value) {
                        try {
                            return Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            LOG.warning("unreadable number");
                            return 0;
                        }
                    }

                    /** Interrupt restored, then translated. */
                    public static void pause(long millis) {
                        try {
                            Thread.sleep(millis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted", e);
                        }
                    }
                }
                """);

        int failures = 0;
        for (Path config : List.of(mainConfig, testConfig)) {
            String name = config.getFileName().toString();
            failures += expectClean(jar, config, clean, name);
            failures += expectRestricted(jar, config, record, "record", name);
            failures += expectRestricted(jar, config, permits, "permits", name);
        }
        String mainName = mainConfig.getFileName().toString();
        for (Map.Entry<String, Path> form : comments.entrySet()) {
            failures += expectFlagged(jar, mainConfig, form.getValue(), form.getKey(), mainName,
                    "code-quality.md § Comments: Javadoc is the only comment.");
        }
        failures += expectClean(jar, mainConfig, allowed, mainName);
        failures += expectFlagged(jar, mainConfig, repeatedLiteral, "MultipleStringLiterals", mainName,
                "code-quality.md: no magic strings — a literal of 5+ characters, 3+ times in one file.");
        failures += expectClean(jar, mainConfig, allowedLiterals, mainName);
        for (Path file : emptyCatches.values()) {
            failures += expectFlagged(jar, mainConfig, file, "EmptyCatchBlock", mainName,
                    "error-handling.md § A caught exception is never silent: a comment is not a handler.");
        }
        failures += expectClean(jar, mainConfig, handledCatch, mainName);
        // Main only: a test may catch and ignore on purpose, so the light config must not flag it.
        failures += expectClean(jar, testConfig, emptyCatches.get("CommentedCatch"), testConfig.getFileName().toString());
        failures += expectClean(jar, testConfig, repeatedLiteral, testConfig.getFileName().toString());
        // Main only: test code holds § Comments by review, so the light config must not flag it.
        failures += expectClean(jar, testConfig, comments.get("LineComment"), testConfig.getFileName().toString());
        String testName = testConfig.getFileName().toString();
        for (Path file : throwLambdas.values()) {
            failures += expectFlagged(jar, testConfig, file, "OneCallInThrowLambda", testName,
                    "testing.md § Names and shape: the lambda of a throw assertion makes exactly one call.");
        }
        failures += expectClean(jar, testConfig, oneCall, testName);

        if (failures > 0) {
            System.out.println("❌ " + failures + " Checkstyle config assertion(s) failed (Checkstyle " + version + ").");
            System.exit(1);
        }
        System.out.println("✅ Both Checkstyle configs parse on Checkstyle " + version
                + ", pass a clean file, and reject `record` and `permits` as names;"
                + " checkstyle.xml rejects every comment but Javadoc and its two exceptions,"
                + " a literal repeated past S1192's threshold, and an empty catch, commented or not;"
                + " checkstyle-test.xml rejects a throw-assertion lambda with more than one call.");
    }

    static int expectFlagged(Path jar, Path config, Path file, String check, String configName, String rule)
            throws Exception {
        Run run = checkstyle(jar, config, file);
        if (run.exit != 0 && run.out.contains("[" + check + "]")) {
            System.out.println("  ✓ " + configName + " rejects " + file.getFileName() + " with " + check);
            return 0;
        }
        System.out.println("  ✗ " + configName + " should reject " + file.getFileName() + " with " + check
                + " — exit " + run.exit + ". " + rule);
        System.out.println(indent(run.out));
        return 1;
    }

    static int expectClean(Path jar, Path config, Path file, String configName) throws Exception {
        Run run = checkstyle(jar, config, file);
        if (run.exit == 0 && !run.out.contains("[ERROR]")) {
            System.out.println("  ✓ " + configName + " passes " + file.getFileName());
            return 0;
        }
        System.out.println("  ✗ " + configName + " should pass " + file.getFileName() + " — exit " + run.exit);
        System.out.println(indent(run.out));
        return 1;
    }

    static int expectRestricted(Path jar, Path config, Path file, String name, String configName) throws Exception {
        Run run = checkstyle(jar, config, file);
        boolean flagged = run.exit != 0
                && run.out.contains("[IllegalIdentifierName]")
                && run.out.contains("'" + name + "'");
        if (flagged) {
            System.out.println("  ✓ " + configName + " rejects `" + name + "` in " + file.getFileName());
            return 0;
        }
        System.out.println("  ✗ " + configName + " should reject `" + name + "` in " + file.getFileName()
                + " with IllegalIdentifierName — exit " + run.exit
                + ". A default `format` lets it through: keep the explicit value.");
        System.out.println(indent(run.out));
        return 1;
    }

    static Path throwLambda(Path dir, String name, String statements) throws IOException {
        return write(dir.resolve(name + "Test.java"), """
                package example;

                class %sTest {

                    void run() {
                        %s
                    }
                }
                """.formatted(name, statements));
    }

    record Run(int exit, String out) {}

    static Run checkstyle(Path jar, Path config, Path file) throws Exception {
        Process p = new ProcessBuilder(javaBin(), "-jar", jar.toString(), "-c", config.toString(), file.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out);
    }

    static String latestCheckstyle() throws Exception {
        String metadata = get("https://repo1.maven.org/maven2/com/puppycrawl/tools/checkstyle/maven-metadata.xml");
        Matcher m = Pattern.compile("<release>([^<]+)</release>").matcher(metadata);
        if (!m.find()) throw new IllegalStateException("no <release> in Checkstyle's maven-metadata.xml");
        return m.group(1);
    }

    static Path download(String version) throws Exception {
        Path jar = Files.createTempFile("checkstyle-" + version + "-all", ".jar");
        String url = "https://github.com/checkstyle/checkstyle/releases/download/checkstyle-"
                + version + "/checkstyle-" + version + "-all.jar";
        HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofFile(jar));
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " for " + url);
        return jar;
    }

    static String get(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    static Path copy(Path from, Path to) throws IOException {
        return Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    static Path write(Path file, String content) throws IOException {
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    static String javaBin() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    static String indent(String s) {
        return s.lines().map(l -> "      " + l).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
