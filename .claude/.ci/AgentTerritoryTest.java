///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.java guard` keeps every subagent inside the territory its class
// declares in `agent_classes`, keyed on the `agent_type` the PreToolUse payload carries, and
// that an open skill phase neither widens nor narrows it.
//
// Why this test exists: until `agent_classes`, each agent file documented a prose
// `**Writes:**` / `**Does not write:**` contract — archunit-installer promising not to touch
// docker-compose.yml, commons-logging-installer promising to stay inside its own package —
// and the guard granted all four an unconditional bypass. Nothing enforced any of it. The
// promises are data now, and "deny by default" is exactly the kind of claim that rots
// silently: it holds until one `write_allow` entry is widened by accident.
//
// Uses the repository's real extensions.json, so what is under test is production data, not a
// fixture that could drift from it. Needs no Docker and no project: `guard` reads the JSON and
// one state file in the OS temp dir.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class AgentTerritoryTest {

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        String session = "ci-agent-territory-" + System.nanoTime();

        int failures = 0;

        // ── installer · archunit-installer ───────────────────────────────────
        failures += write(hook, session, "archunit-installer",
                "app/src/test/java/com/acme/ArchitectureTest.java",
                0, "archunit-installer writes its one test class");

        failures += write(hook, session, "archunit-installer",
                "src/test/java/com/acme/ArchitectureTest.java",
                0, "…in a single-module project too, with no module prefix");

        failures += write(hook, session, "archunit-installer", "pom.xml",
                0, "archunit-installer edits the root POM");

        failures += write(hook, session, "archunit-installer", "app/pom.xml",
                0, "…and a module POM");

        // The prose promise this class turns into enforcement.
        failures += write(hook, session, "archunit-installer", "docker-compose.yml",
                2, "archunit-installer is refused docker-compose.yml");

        // Wider than its job, and previously allowed by the blanket executor bypass.
        failures += write(hook, session, "archunit-installer", "src/main/java/com/acme/Order.java",
                2, "archunit-installer is refused main source");

        // ── installer · commons-logging-installer ────────────────────────────
        failures += write(hook, session, "commons-logging-installer",
                "src/main/java/com/acme/commons/logging/LogExecution.java",
                0, "commons-logging-installer writes its own package");

        // modular-monolith names the same package `shared.logging` — the glob matches the leaf
        // segment on purpose, since no blueprint guarantees either name.
        failures += write(hook, session, "commons-logging-installer",
                "shared/src/main/java/com/acme/shared/logging/LogMask.java",
                0, "the leaf-segment glob covers the modular-monolith package");

        failures += write(hook, session, "commons-logging-installer",
                "src/main/resources/META-INF/spring/"
                        + "org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                0, "commons-logging-installer writes the imports file");

        // The installer ships the unit tests of what it installs (decision 0098): its test
        // territory is the mirror of its main one — the `logging` package, nothing else.
        failures += write(hook, session, "commons-logging-installer",
                "src/test/java/com/acme/commons/logging/aspect/LogExecutionAspectTest.java",
                0, "commons-logging-installer writes the tests of its own package");

        failures += write(hook, session, "commons-logging-installer",
                "src/test/java/com/acme/order/OrderTest.java",
                2, "commons-logging-installer is refused src/test outside its package");

        // ── installer · compose-service-installer ────────────────────────────
        // Runs docker-architect inside an implement run (decision 0137). Every write the skill
        // makes there is judged by this agent_type, so the territory must cover each path
        // skill_classes.build gives docker-architect — a narrower list refuses the skill's own
        // writes mid-run.
        for (String f : new String[]{"docker-compose.yml", "docker-compose.override.yml",
                "docker/init/01-create-db.sql", "docker/caddy/Caddyfile", "Dockerfile",
                ".env", ".env.example", ".gitignore", ".dockerignore"}) {
            failures += write(hook, session, "compose-service-installer", f,
                    0, "compose-service-installer writes " + f + " — docker-architect's territory");
        }

        failures += write(hook, session, "compose-service-installer",
                "src/main/resources/application.yml",
                2, "compose-service-installer is refused application config");

        failures += write(hook, session, "compose-service-installer", "pom.xml",
                2, "compose-service-installer is refused the POM, the class default it replaces");

        failures += write(hook, session, "compose-service-installer",
                "docs/use-cases/UC-001-register-customer/UC-001-spec.md",
                2, "compose-service-installer is refused the spec it reads");

        // ── executor ─────────────────────────────────────────────────────────
        failures += write(hook, session, "java-spring-boot-developer",
                "src/main/java/com/acme/Order.java",
                0, "the executor writes main source");

        failures += write(hook, session, "java-spring-boot-developer",
                "docs/use-cases/UC-001-register-customer/UC-001-spec.md",
                0, "the executor reaches the spec line it closes");

        failures += write(hook, session, "java-spring-boot-developer", "docker-compose.yml",
                2, "the executor is refused docker-compose.yml");

        // ── driver ───────────────────────────────────────────────────────────
        failures += write(hook, session, "project-initializer", "docker-compose.yml",
                0, "the driver writes the whole tree of a new project");

        // ── verifier · issue-verifier ────────────────────────────────────────
        // Reads a public issue — text nobody here wrote. An empty territory is the whole point
        // (decision 0103): an instruction hidden in the body must not reach a hook or a norm,
        // whichever tool it is spelled through.
        failures += write(hook, session, "issue-verifier", ".claude/settings.json",
                2, "issue-verifier is refused .claude/settings.json");

        failures += write(hook, session, "issue-verifier", "CLAUDE.md",
                2, "issue-verifier is refused CLAUDE.md");

        failures += bash(hook, session, "issue-verifier",
                "cat > .claude/rules/naming.md <<'EOF'\\nx\\nEOF",
                2, "issue-verifier is refused a heredoc into .claude/rules/");

        // Layer 2 materializes a generated project in a scratch directory: outside the
        // repository, so the guard has no say.
        failures += bash(hook, session, "issue-verifier",
                "echo x > /tmp/issue-verifier-scratch/probe.txt",
                0, "issue-verifier writes its scratch outside the repository");

        // ── the phase does not reach a classed agent ─────────────────────────
        failures += run(hook, "prompt", session,
                "{\"session_id\":\"%s\",\"prompt\":\"/new-feature add a use case\"}",
                0, "an orchestrator phase is open");

        failures += write(hook, session, "archunit-installer",
                "app/src/test/java/com/acme/ArchitectureTest.java",
                0, "the open design phase does not narrow the agent's territory");

        failures += write(hook, session, "archunit-installer", "docker-compose.yml",
                2, "nor widen it");

        // The phase whose territory is .claude/** — the designer's — is the one an issue would
        // most want to borrow. It does not reach the verifier.
        failures += run(hook, "prompt", session,
                "{\"session_id\":\"%s\",\"prompt\":\"/claude-code-architect-designer x\"}",
                0, "the meta phase is open");

        failures += write(hook, session, "issue-verifier", ".claude/hooks/ArchHook.java",
                2, "the meta phase does not widen issue-verifier's empty territory");

        // An agent no class lists has nothing else describing it: the caller's phase applies.
        failures += write(hook, session, "general-purpose", "docker-compose.yml",
                2, "an unclassed agent falls back to the caller's phase");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — the territory guard is NOT"
                    + " enforcing what agent_classes declares.");
            System.exit(1);
        }
        System.out.println("✅ Agent territory enforced: deny by default per agent_type,"
                + " independent of any open skill phase, with an unclassed agent falling back"
                + " to the caller's.");
    }

    /** One `guard write` as the given subagent. */
    static int write(Path hook, String session, String agent, String file,
                     int wanted, String label) throws Exception {
        return run(hook, "write", session,
                "{\"session_id\":\"%s\",\"agent_type\":\"" + agent + "\","
                        + "\"tool_input\":{\"file_path\":\"" + file + "\"}}",
                wanted, label);
    }

    /** One `guard bash` as the given subagent; `command` is already JSON-escaped. */
    static int bash(Path hook, String session, String agent, String command,
                    int wanted, String label) throws Exception {
        return run(hook, "bash", session,
                "{\"session_id\":\"%s\",\"agent_type\":\"" + agent + "\","
                        + "\"tool_input\":{\"command\":\"" + command + "\"}}",
                wanted, label);
    }

    /** Runs one `guard <phase>` with the given stdin and checks the exit code. */
    static int run(Path hook, String phase, String session, String jsonTemplate,
                   int wanted, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", hook.toString(), "guard", phase);
        pb.environment().put("CLAUDE_PROJECT_DIR", Paths.get("").toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().write(jsonTemplate.formatted(session)
                .getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();

        if (exit == wanted) {
            System.out.println("✅ " + label + " (exit " + exit + ")");
            return 0;
        }
        System.out.println("❌ " + label + " — expected exit " + wanted + ", got " + exit);
        System.out.println(out);
        return 1;
    }
}
