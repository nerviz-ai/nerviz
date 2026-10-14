///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the Kafka consumer exemplars leave retry and commit to the container, as
// `.claude/rules/messaging.md` § Retry and DLQ and § Configuration decide, and that a lost race is
// the one conflict they retry (`.claude/rules/error-handling.md` § Shape of the base classes).
//
// Why this test exists: every trap here is silent. A listener that loops and sleeps holds the
// partition with no stop handling and dead-letters a record on an interrupt; an `ack.acknowledge()`
// forgotten on one branch leaves an offset to a later record's commit; a classification keyed on
// `errorCode()` cannot be expressed in the non-blocking shape; an optimistic-lock failure translated
// into a plain `ConflictException` is dead-lettered unretried; a DLQ under the framework's `-dlt`
// suffix is a topic nobody provisions; and a `@LogExecution` an exemplar writes unconditionally does
// not compile in a project without the commons package. A generated run copied the first one into
// `bank-app` (0159). The executor copies an exemplar's shape, so one of these left in a template
// ships into every project.
//
// What it holds, each failure named by file:
//   - no `.java.example` holding a `@KafkaListener` takes an `Acknowledgment`, calls `acknowledge`,
//     `Thread.sleep`, or loops `while (true)`; no yml exemplar sets `ack-mode: manual`;
//   - a listener exemplar that writes `@LogExecution` states, in its EXEMPLAR header, the
//     condition "ONLY IF the commons logging classes are installed" — the header, because a body
//     carries no comment but Javadoc (`TemplateCommentsTest`);
//   - the blocking exemplar writes the classification once — `DomainException` not retryable,
//     `ConcurrentUpdateException` retryable — applies it to the `DefaultErrorHandler`, never reads
//     `errorCode()` through a backoff function, dead-letters to `<topic>.dlq` with the `errorCode`
//     header, and names a conversion failure `MALFORMED` through Spring Kafka's `ConversionException`;
//   - the non-blocking exemplar declares no `include`/`exclude` on `@RetryableTopic`, applies the same
//     classification to the destination resolver, suffixes `.dlq` and retry topics by index, never
//     starts the framework's DLQ consumer, runs its delays on a `RetryTopicSchedulerWrapper`, and
//     does not also use `@EnableKafkaRetryTopic`;
//   - `ConcurrentUpdateException` extends `ConflictException` with no state, and every persistence
//     exemplar that catches `OptimisticLockingFailureException` throws it — no `*_CONCURRENT_UPDATE`
//     code is ever thrown as a plain `ConflictException`.
//
// Offline and in milliseconds. Whether the imports resolve is `validate` › `exemplar-imports`'s job;
// whether the shapes run was proven against a real Initializr project and a Kafka broker (0159 §
// Verified).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class MessagingRetryTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path MESSAGING = SKILLS.resolve("messaging-architect/templates");
    static final Path BLOCKING = MESSAGING.resolve("KafkaListenerErrorHandler.java.example");
    static final Path NON_BLOCKING = MESSAGING.resolve("KafkaRetryableTopicListener.java.example");
    static final Path CONSUMER = MESSAGING.resolve("KafkaConsumerAdapter.java.example");
    static final Path CONCURRENT = SKILLS.resolve("domain-modeling/templates/ConcurrentUpdateException.java.example");
    static final Path PERSISTENCE = SKILLS.resolve("persistence-architect/templates");

    static final String MARKER = "ONLY IF the commons logging classes are installed";
    static final Pattern RETRYABLE_TOPIC = Pattern.compile("@RetryableTopic\\((.*?)\\)\\s*\\n\\s*@KafkaListener", Pattern.DOTALL);
    static final Pattern OPTIMISTIC_CATCH = Pattern.compile("catch \\(OptimisticLockingFailureException \\w+\\) \\{\\s*throw new (\\w+)\\(");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (Path required : List.of(BLOCKING, NON_BLOCKING, CONSUMER, CONCURRENT)) {
            if (!Files.isRegularFile(required)) {
                System.out.println("❌ Missing exemplar " + rel(required));
                System.exit(1);
            }
        }
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".example"))
                    .sorted().toList();
        }
        for (Path file : exemplars) {
            String name = file.getFileName().toString();
            String rel = rel(file);
            String raw = read(file);
            if (name.endsWith(".java.example")) {
                String code = code(raw);
                if (code.contains("@KafkaListener")) listener(rel, raw, code);
                if (Pattern.compile("new ConflictException\\(\"\\w*CONCURRENT_UPDATE\"").matcher(code).find()) {
                    fail(rel, "throws a *_CONCURRENT_UPDATE code as a plain ConflictException — a lost race must be ConcurrentUpdateException, or the listener dead-letters it unretried");
                }
                Matcher catches = OPTIMISTIC_CATCH.matcher(code);
                while (catches.find()) {
                    if (!catches.group(1).equals("ConcurrentUpdateException")) {
                        fail(rel, "translates OptimisticLockingFailureException into " + catches.group(1) + " — expected ConcurrentUpdateException");
                    }
                }
            } else if (name.matches(".*\\.(yml|yaml|properties)\\.example")
                    && Pattern.compile("(?m)^\\s*ack-mode:\\s*manual").matcher(raw.replaceAll("(?m)#.*$", "")).find()) {
                fail(rel, "sets ack-mode: manual — the container commits after the listener returns; manual only for a hand-off to another thread");
            }
        }
        blocking();
        nonBlocking();
        concurrentUpdate();
        documentTransactions();
        if (!failures.isEmpty()) {
            System.out.println("❌ Kafka consumer exemplars retry or commit in the listener, or misclassify a lost race — "
                    + failures.size() + " problem(s):");
            failures.forEach(f -> System.out.println("  " + f));
            System.out.println("Norm: .claude/rules/messaging.md § Retry and DLQ · design: .claude/decisions/0159-typed-concurrent-update-and-kafka-retry-modes.md");
            System.exit(1);
        }
        System.out.println("✅ Kafka consumer exemplars leave retry and commit to the container, classify by type once for both"
                + " retry shapes, retry a lost race, and dead-letter to <topic>.dlq.");
    }

    static void listener(String rel, String raw, String code) {
        if (code.contains("Acknowledgment") || code.contains(".acknowledge(")) {
            fail(rel, "a listener takes an Acknowledgment — the container commits after the method returns");
        }
        if (code.contains("Thread.sleep") || Pattern.compile("while\\s*\\(\\s*true\\s*\\)").matcher(code).find()) {
            fail(rel, "a listener sleeps or loops — retry belongs to the container's error handling");
        }
        if (Pattern.compile("(?m)^\\s*@LogExecution\\b").matcher(code).find() && !header(raw).contains(MARKER)) {
            fail(rel, "writes @LogExecution without the `" + MARKER + "` condition in its EXEMPLAR header — it does not compile where commons logging is absent");
        }
    }

    /** The leading comment block — the EXEMPLAR header. */
    static String header(String raw) {
        Matcher m = Pattern.compile("(?s)\\A\\s*/\\*.*?\\*/").matcher(raw);
        return m.find() ? m.group().replaceAll("\\s*\\n\\s*\\*\\s*", " ").replaceAll("\\s+", " ") : "";
    }

    static void blocking() throws Exception {
        String rel = rel(BLOCKING);
        String text = code(read(BLOCKING));
        require(rel, text, "addNotRetryableExceptions(DomainException.class", "does not mark the domain families not retryable");
        require(rel, text, "addRetryableExceptions(ConcurrentUpdateException.class)", "does not retry ConcurrentUpdateException — a lost race would be dead-lettered");
        require(rel, text, "ListenerFailureClassification.applyTo(errorHandler)", "does not apply the shared classification to the DefaultErrorHandler");
        require(rel, text, "DLQ_SUFFIX = \".dlq\"", "does not dead-letter to <topic>.dlq");
        require(rel, text, "MonitoredDeadLetterPublisher::sameTopicDlq", "does not route to <topic>.dlq on the source partition");
        require(rel, text, "addHeadersFunction(MonitoredDeadLetterPublisher::errorCodeHeader)", "does not put the errorCode header on the DLQ record");
        require(rel, text, "cause instanceof ConversionException", "misses Spring Kafka's ConversionException — a malformed payload would be reported as RETRIES_EXHAUSTED");
        if (text.contains("setBackOffFunction")) {
            fail(rel, "classifies through a backoff function — the non-blocking shape has none; classify by type");
        }
        if (text.contains("errorCode().")) {
            fail(rel, "branches on errorCode() — the classification is by type");
        }
    }

    static void nonBlocking() throws Exception {
        String rel = rel(NON_BLOCKING);
        String text = code(read(NON_BLOCKING));
        Matcher m = RETRYABLE_TOPIC.matcher(text);
        if (!m.find()) {
            fail(rel, "has no @RetryableTopic right above a @KafkaListener");
        } else {
            String attributes = m.group(1);
            for (String forbidden : List.of("include", "exclude", "includeNames", "excludeNames")) {
                if (Pattern.compile("\\b" + forbidden + "\\s*=").matcher(attributes).find()) {
                    fail(rel, "@RetryableTopic sets `" + forbidden + "` — what retries is decided once, on the resolver");
                }
            }
            for (String needed : List.of("dltTopicSuffix = \".dlq\"", "TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE", "autoStartDltHandler = \"false\"")) {
                if (!attributes.contains(needed)) fail(rel, "@RetryableTopic misses `" + needed + "`");
            }
        }
        require(rel, text, "ListenerFailureClassification.applyTo((DefaultDestinationTopicResolver) resolver)", "does not apply the shared classification to the retry-topic resolver");
        require(rel, text, "setDeadLetterPublisherCreator(", "does not publish the DLQ through MonitoredDeadLetterPublisher");
        require(rel, text, "setHeadersFunction(MonitoredDeadLetterPublisher::errorCodeHeader)", "does not add the errorCode header back after the factory replaces it");
        require(rel, text, "RetryTopicSchedulerWrapper", "runs the retry delays on a shared scheduler");
        if (text.contains("@EnableKafkaRetryTopic")) {
            fail(rel, "uses @EnableKafkaRetryTopic next to a RetryTopicConfigurationSupport subclass — the support registers twice");
        }
        if (text.contains("Acknowledgment")) {
            fail(rel, "the listener takes an Acknowledgment");
        }
    }

    static void concurrentUpdate() throws Exception {
        String rel = rel(CONCURRENT);
        String text = code(read(CONCURRENT));
        require(rel, text, "class ConcurrentUpdateException extends ConflictException", "is not a ConflictException — every 409 mapping must cover it unchanged");
        if (Pattern.compile("(?m)^\\s+(private|protected|final|[A-Z]\\w*)\\s+\\w+\\s*;").matcher(text).find()) {
            fail(rel, "declares a field — a named subclass adds no state");
        }
    }

    static void documentTransactions() throws Exception {
        Path file = PERSISTENCE.resolve("DocumentTransactions.java.example");
        if (!Files.isRegularFile(file)) return;
        require(rel(file), code(read(file)), "throw new ConcurrentUpdateException(", "reports spent transaction retries as something other than ConcurrentUpdateException");
    }

    static void require(String rel, String text, String needle, String why) {
        if (!text.contains(needle)) fail(rel, why + " (expected `" + needle.strip() + "`)");
    }
    static void fail(String rel, String message) {
        failures.add(rel + " — " + message);
    }
    static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }
    static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
    static String rel(Path file) {
        return file.toString().replace('\\', '/');
    }
}
