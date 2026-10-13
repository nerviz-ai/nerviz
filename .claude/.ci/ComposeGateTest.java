///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.jar compose gate` BLOCKS (exit 2) a compose file whose service
// publishes a port to the host while advertising only its compose-network name, stays silent
// with no compose file, and never blocks twice in one Stop.
//
// Why this test exists: the defect it gates shipped on day one — a `kafka` block publishing
// 9092:9092 next to `KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092`, unreachable from
// every host client, while the healthcheck and Testcontainers both stayed green
// (lessons-learned-014 § 13, decision 0064). ComposeTagTest covers the report form; this is
// the exit code and the registration's contract, which the report form does not have.
//
// Why no Docker: the advertised-address check reads the file, and `composeReport` computes it
// before the first `docker` call. On a runner WITH Docker the declared service is also "not
// running", which blocks too — so the fixed case asserts on the advertised-address line, not
// just on exit 2, and the host-advertised variant asserts only that the line is absent.
//
// The login half of question 5 (decision 0124) is the opposite contract: a warning in the
// report form (`compose`), absent from the gate, never printing a password value.
//
// Question 5 (decision 0110, issue #66) has the same shape and gets the same treatment: the
// OTLP collector published no host port, so `./mvnw spring-boot:run` failed every export
// while the gate stayed silent, and projects older than 0046 never set the metrics endpoint
// on `app`. Each side is a blocked case plus its fixed variant, and two cases guard the false
// positives the check must not raise: relaxed binding (`SPRING_DATASOURCE_URL` overriding
// `${DB_URL:…}`) and Kafka's host-first default on its EXTERNAL port.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ComposeGateTest {

    static final String UNREACHABLE = "no address the host can resolve";
    static final String HOST_RUN = "the application run on the host cannot reach it";
    static final String CONTAINER = "is the application itself";

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();

        String broken = """
                services:
                  kafka:
                    image: apache/kafka:3.8.0
                    ports:
                      - "9092:9092"
                    environment:
                      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
                """;
        String fixed = """
                services:
                  kafka:
                    image: apache/kafka:3.8.0
                    ports:
                      - "9092:9092"
                    environment:
                      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
                """;

        int failures = 0;
        failures += expect(hook, broken, null, UNREACHABLE, "{}", 2, true,
                "published but advertised as kafka:9092 — blocked");
        failures += expect(hook, broken, null, UNREACHABLE, "{\"stop_hook_active\":true}", 0, false,
                "stop_hook_active — does not block twice");
        failures += expect(hook, fixed, null, UNREACHABLE, "{}", -1, false,
                "advertised on localhost — the address check stays quiet");
        failures += expect(hook, null, null, UNREACHABLE, "{}", 0, false, "no compose file — silent");

        String otlpYml = """
                management:
                  otlp:
                    tracing:
                      endpoint: ${OTLP_ENDPOINT:http://localhost:4318/v1/traces}
                    metrics:
                      export:
                        url: ${OTLP_METRICS_ENDPOINT:http://localhost:4318/v1/metrics}
                """;
        String bothVars = """
                      OTLP_ENDPOINT: http://otel-collector:4318/v1/traces
                      OTLP_METRICS_ENDPOINT: http://otel-collector:4318/v1/metrics
                """;
        String collectorUnpublished = """
                  otel-collector:
                    image: otel/opentelemetry-collector-contrib:0.160.0
                """;
        String collectorPublished = collectorUnpublished + """
                    ports:
                      - "${OTLP_HTTP_PORT:-4318}:4318"
                """;
        failures += expect(hook, withApp(bothVars, collectorUnpublished), otlpYml, HOST_RUN, "{}", 2,
                true, "collector publishes no port, defaults point at localhost:4318 — blocked");
        failures += expect(hook, withApp(bothVars, collectorPublished), otlpYml, HOST_RUN, "{}", -1,
                false, "collector published on a variable host port — host-run line absent");
        failures += expect(hook, withApp("      OTLP_ENDPOINT: http://otel-collector:4318/v1/traces\n",
                        collectorPublished), otlpYml, CONTAINER, "{}", 2, true,
                "app sets only OTLP_ENDPOINT — the metrics endpoint blocked");
        failures += expect(hook, withApp(bothVars, collectorPublished), otlpYml, CONTAINER, "{}", -1,
                false, "app sets both endpoints — container line absent");

        String datasourceYml = """
                spring:
                  datasource:
                    url: ${DB_URL:jdbc:postgresql://localhost:5432/app}
                """;
        String postgres = """
                  postgres:
                    image: postgres:17-alpine
                    ports:
                      - "5432:5432"
                """;
        failures += expect(hook, withApp("      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app\n",
                        postgres), datasourceYml, HOST_RUN + "|" + CONTAINER, "{}", -1, false,
                "SPRING_DATASOURCE_URL overrides ${DB_URL:…} by relaxed binding — quiet");

        String kafkaYml = """
                spring:
                  kafka:
                    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:29092}
                """;
        String kafka = """
                  kafka:
                    image: apache/kafka:3.8.0
                    ports:
                      - "9092:9092"
                      - "29092:29092"
                    environment:
                      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:29092
                """;
        failures += expect(hook, withApp("      KAFKA_BOOTSTRAP_SERVERS: kafka:9092\n", kafka), kafkaYml,
                HOST_RUN + "|" + CONTAINER, "{}", -1, false,
                "Kafka host-first default on its EXTERNAL port — quiet");

        // Decision 0124 made both Kafka host ports variables. The advertised address then holds
        // `${KAFKA_EXTERNAL_PORT:-29092}`, and splitting it at its last `:` read the `:-` inside
        // the interpolation — a correct broker reported as unreachable.
        String kafkaVarYml = """
                spring:
                  kafka:
                    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:${KAFKA_EXTERNAL_PORT:29092}}
                """;
        String kafkaVar = """
                  kafka:
                    image: apache/kafka:3.8.0
                    ports:
                      - "${KAFKA_PORT:-9092}:9092"
                      - "${KAFKA_EXTERNAL_PORT:-29092}:29092"
                    environment:
                      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:${KAFKA_EXTERNAL_PORT:-29092}
                """;
        failures += expect(hook, withApp("      KAFKA_BOOTSTRAP_SERVERS: kafka:9092\n", kafkaVar),
                kafkaVarYml, UNREACHABLE + "|" + HOST_RUN + "|" + CONTAINER, "{}", -1, false,
                "Kafka on variable host ports, advertised through the same variable — quiet");

        // The login half of question 5 (decision 0124, lessons-learned-021): a WARNING in the
        // report form, never a block and never a gate line. Generated application.yml defaulted
        // to `bankingapp`/`bankingapp`/empty while the service created `appdb`/`app`, and the
        // host-and-port half passed it. The password literal below must never reach the output.
        String oldYml = """
                spring:
                  datasource:
                    url: ${DB_URL:jdbc:postgresql://localhost:5432/bankingapp}
                    username: ${DB_USERNAME:bankingapp}
                    password: ${DB_PASSWORD:}
                """;
        String oldPostgres = """
                  postgres:
                    image: postgres:16-alpine
                    environment:
                      POSTGRES_DB: ${POSTGRES_DB:-appdb}
                      POSTGRES_USER: ${POSTGRES_USER:-app}
                      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-s3cr3t-ci}
                    ports:
                      - "5432:5432"
                """;
        String oldApp = withApp("""
                      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/appdb
                      DB_USERNAME: ${POSTGRES_USER:-app}
                      DB_PASSWORD: ${POSTGRES_PASSWORD:-s3cr3t-ci}
                """, oldPostgres);
        failures += expectReport(hook, oldApp, oldYml, "connects to database `bankingapp`", true,
                "host run defaults to another database than the service creates — warned");
        failures += expectReport(hook, oldApp, oldYml, "logs in as `bankingapp`", true,
                "host run defaults to another user than the service creates — warned");
        failures += expectReport(hook, oldApp, oldYml, "requires a password", true,
                "service requires a password, host run has an empty default — warned");
        failures += expectReport(hook, oldApp, oldYml, "s3cr3t-ci", false,
                "the password value never reaches the output");
        failures += expectReport(hook, oldApp, oldYml, "service `app` connects|service `app` logs in"
                        + "|and service `app` has none", false,
                "the container side, wired to the service's own values — no warning");
        failures += expect(hook, oldApp, oldYml, "login:", "{}", -1, false,
                "a login warning never reaches the gate");

        String newYml = """
                spring:
                  config:
                    import: optional:file:.env[.properties]
                  datasource:
                    url: ${DB_URL:jdbc:postgresql://localhost:${DB_PORT:5432}/${DB_NAME:banking_app}}
                    username: ${DB_USERNAME:banking_app}
                    password: ${DB_PASSWORD}
                """;
        String newPostgres = """
                  postgres:
                    image: postgres:16-alpine
                    environment:
                      POSTGRES_DB: ${DB_NAME:-banking_app}
                      POSTGRES_USER: ${DB_USERNAME:-banking_app}
                      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
                    ports:
                      - "${DB_PORT:-5432}:5432"
                """;
        String newApp = withApp("""
                      DB_URL: jdbc:postgresql://postgres:5432/${DB_NAME:-banking_app}
                      DB_USERNAME: ${DB_USERNAME:-banking_app}
                      DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD in .env}
                """, newPostgres);
        failures += expectReport(hook, newApp, newYml, "login:|" + HOST_RUN + "|" + CONTAINER, false,
                "one DB_* convention on both sides, variable port — no warning, no placeholder line");
        failures += expectReport(hook, newApp.replace("postgres:5432/${DB_NAME:-banking_app}",
                        "postgres:5432/other"), newYml, "service `app` connects to database `other`",
                true, "app service points at another database than the service creates — warned");

        // The MongoDB pair (decision 0147): one URI carries user and password, and the password
        // placeholder has no default — the URI must still be read, not discarded. The template's
        // convention is quiet; a host default naming another user is warned; a URI with no
        // password against a service that requires one is warned; never in the gate.
        String mongoYml = """
                spring:
                  mongodb:
                    uri: ${MONGO_URI:mongodb://${MONGO_USERNAME:shop_app}:${MONGO_PASSWORD}@localhost:${MONGO_PORT:27017}/${MONGO_DATABASE:shop_app}?authSource=admin&directConnection=true}
                """;
        String mongoService = """
                  mongo:
                    image: mongo:8.0
                    environment:
                      MONGO_INITDB_ROOT_USERNAME: ${MONGO_USERNAME:-shop_app}
                      MONGO_INITDB_ROOT_PASSWORD: ${MONGO_PASSWORD:?set MONGO_PASSWORD in .env}
                    ports:
                      - "${MONGO_PORT:-27017}:27017"
                """;
        String mongoApp = withApp("""
                      MONGO_URI: mongodb://${MONGO_USERNAME:-shop_app}:${MONGO_PASSWORD}@mongo:27017/${MONGO_DATABASE:-shop_app}?authSource=admin
                """, mongoService);
        failures += expectReport(hook, mongoApp, mongoYml, "login:|" + HOST_RUN + "|" + CONTAINER, false,
                "MongoDB: the template's MONGO_* convention on both sides — no warning, no placeholder line");
        failures += expectReport(hook, mongoApp, mongoYml.replace("${MONGO_USERNAME:shop_app}", "${MONGO_USERNAME:other_app}"),
                "the host run (defaults in src/main/resources/application.yml) logs in as `other_app`", true,
                "MongoDB: a host default naming another user than the service creates — warned");
        failures += expectReport(hook, mongoApp, mongoYml.replace(":${MONGO_PASSWORD}@", "@"),
                "requires a password", true,
                "MongoDB: a URI with no password against a service that requires one — warned");
        failures += expect(hook, mongoApp, mongoYml.replace("${MONGO_USERNAME:shop_app}", "${MONGO_USERNAME:other_app}"),
                "login:", "{}", -1, false, "MongoDB: a login warning never reaches the gate");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — `compose gate` did not"
                    + " block or stay quiet where it should.");
            System.exit(1);
        }
        System.out.println("✅ compose gate: unreachable published service blocked without Docker,"
                + " host and container sides of each placeholder checked, a datasource login that"
                + " disagrees with its service warned and never blocked, silent with nothing to check.");
    }

    /** A compose file with a built `app` service carrying {@code env}, then {@code others}. */
    static String withApp(String env, String others) {
        return "services:\n  app:\n    build: .\n    ports:\n      - \"8080:8080\"\n"
                + "    environment:\n" + env + others;
    }

    /**
     * {@code wanted} -1 means the exit code depends on the runner's Docker and is not checked.
     * {@code needle} is one or more `|`-separated phrases; the line counts as present when any
     * of them is in the output.
     */
    static int expect(Path hook, String compose, String appYml, String needle, String stdin,
                      int wanted, boolean wantLine, String label) throws Exception {
        return run(hook, new String[] {"compose", "gate"}, compose, appYml, needle, stdin, wanted,
                wantLine, label);
    }

    /** The report form, `compose` alone: where warnings are printed. It never exits non-zero. */
    static int expectReport(Path hook, String compose, String appYml, String needle,
                            boolean wantLine, String label) throws Exception {
        return run(hook, new String[] {"compose"}, compose, appYml, needle, "", -1, wantLine, label);
    }

    static int run(Path hook, String[] mode, String compose, String appYml, String needle,
                   String stdin, int wanted, boolean wantLine, String label) throws Exception {
        Path tmp = Files.createTempDirectory("archhook-gate-ci");
        Path schemas = tmp.resolve(".claude/schemas");
        Files.createDirectories(schemas);
        Files.copy(Paths.get(".claude/schemas/extensions.json"), schemas.resolve("extensions.json"));
        if (compose != null) Files.writeString(tmp.resolve("docker-compose.yml"), compose);
        if (appYml != null) {
            Path res = tmp.resolve("src/main/resources");
            Files.createDirectories(res);
            Files.writeString(res.resolve("application.yml"), appYml);
        }

        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                ProcessHandle.current().info().command().orElse("java"), "-jar", hook.toString()));
        cmd.addAll(java.util.List.of(mode));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile());
        pb.environment().put("CLAUDE_PROJECT_DIR", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();

        boolean line = java.util.Arrays.stream(needle.split("\\|")).anyMatch(out::contains);
        String why = wanted >= 0 && code != wanted ? "expected exit " + wanted + ", got " + code
                : line != wantLine ? "line `" + needle + "` " + (line ? "present" : "absent")
                : null;
        if (why == null) {
            System.out.println("✅ " + label);
            return 0;
        }
        System.out.println("❌ " + label + " — " + why);
        System.out.println(out);
        return 1;
    }
}
