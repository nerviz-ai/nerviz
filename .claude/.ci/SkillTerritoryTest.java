///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves `ArchHook.java guard` keeps a skill inside the territory its class
// declares in `skill_classes`, refuses a build-class Skill call mid-design, sums territories
// when a skill chains another of its own class, and restricts nothing while no phase is open.
//
// Why this test exists: the failure it guards against was invisible in review. A design run
// wrote a `schema-registry` service into docker-compose.yml, inside a diff everybody read as
// a spec — the file is not under `src/**`, which is all the old denylist forbade
// (lessons-learned-012 §§ 12, 13). The territory is deny-by-default now, and "deny by
// default" is exactly the kind of claim that rots silently: it holds until one `write_allow`
// entry is widened by accident.
//
// Uses the repository's real extensions.json, so what is under test is production data, not
// a fixture that could drift from it. Needs no Docker and no project: `guard` reads the JSON
// and one state file in the OS temp dir.

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class SkillTerritoryTest {

    public static void main(String[] args) throws Exception {
        Path hook = Paths.get(".claude/hooks/ArchHook.jar").toAbsolutePath();
        String session = "ci-territory-" + System.nanoTime();

        int failures = 0;

        // No phase open: a person editing a file by hand is never blocked.
        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"docker-compose.yml\"}}",
                0, "no phase open — nothing restricted");

        // `/new-feature` opens an orchestrator phase.
        failures += run(hook, "prompt", session,
                "{\"session_id\":\"%s\",\"prompt\":\"/new-feature add a use case\"}",
                0, "prompt opens the orchestrator phase");

        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"docs/use-cases/UC-001-x/00-use-case.md\"}}",
                0, "inside the territory — allowed");

        // The regression this file exists for.
        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"docker-compose.yml\"}}",
                2, "compose file outside the territory — blocked");

        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                2, "src/ outside the territory — blocked");

        // A subagent's write is judged by its own class, never by the caller's phase.
        // The territory of each agent is AgentTerritoryTest's subject; here the point is
        // only that an open skill phase does not reach it.
        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"agent_type\":\"java-spring-boot-developer\","
                        + "\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                0, "agent_type wins over the open phase");

        // A build-class skill is unreachable from inside a design run.
        failures += run(hook, "call", session,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"docker-architect\"}}",
                2, "build-class Skill call refused mid-design");

        // A design-class skill is reachable, and narrows the phase to its own territory.
        failures += run(hook, "call", session,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"persistence-architect\"}}",
                0, "design-class Skill call opens its own phase");

        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"docs/lessons-learned/notes.md\"}}",
                2, "the callee's narrower territory is what applies");

        // An Agent call leaves the phase alone. It used to delete it, which silently
        // unrestricted the CALLER for the rest of the turn — the subagent never needed that,
        // since its own writes carry `agent_type`.
        failures += run(hook, "call", session,
                "{\"session_id\":\"%s\",\"tool_name\":\"Agent\","
                        + "\"tool_input\":{\"subagent_type\":\"java-spring-boot-developer\"}}",
                0, "Agent call is not refused");

        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                2, "the caller's own phase survives the Agent call");

        // A new prompt is what ends it.
        failures += run(hook, "prompt", session,
                "{\"session_id\":\"%s\",\"prompt\":\"thanks\"}",
                0, "a plain prompt closes the phase");

        failures += run(hook, "write", session,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                0, "phase closed — nothing restricted again");

        // A same-class chain sums territories. `build` gives every skill its own override, so
        // keeping only the caller's — the rule before decision 0092 — refused arch-adopt's
        // chained sonarqube-setup the very writes it exists for, and sonarqube-setup's chained
        // docker-architect the compose file.
        String chain = "ci-territory-chain-" + System.nanoTime();
        failures += run(hook, "prompt", chain,
                "{\"session_id\":\"%s\",\"prompt\":\"/arch-adopt\"}",
                0, "prompt opens arch-adopt's phase");

        failures += run(hook, "write", chain,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"pom.xml\"}}",
                2, "pom.xml is not arch-adopt's — blocked");

        failures += run(hook, "call", chain,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"sonarqube-setup\"}}",
                0, "same-class Skill call joins the phase");

        failures += run(hook, "write", chain,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"pom.xml\"}}",
                0, "the callee's territory is added — pom.xml allowed");

        failures += run(hook, "write", chain,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\".claude/rules/x.md\"}}",
                0, "the caller's territory survives the join");

        failures += run(hook, "write", chain,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"docker-compose.yml\"}}",
                2, "a path neither skill owns stays blocked");

        failures += run(hook, "call", chain,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"docker-architect\"}}",
                0, "a third same-class skill joins too");

        failures += run(hook, "write", chain,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"docker-compose.yml\"}}",
                0, "compose allowed once docker-architect joined");

        failures += run(hook, "write", chain,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                2, "the union is still deny-by-default outside it");

        // transport-security-setup (decision 0122): a file-level territory. The configuration, the
        // one filter class, the tests and the root CLAUDE.md — never another class, never a key
        // file under resources, never the Dockerfile or compose, which are docker-architect's
        // and become writable only once it joins.
        String transport = "ci-territory-transport-" + System.nanoTime();
        failures += run(hook, "prompt", transport,
                "{\"session_id\":\"%s\",\"prompt\":\"/transport-security-setup\"}",
                0, "prompt opens transport-security-setup's phase");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"src/main/resources/application.yml\"}}",
                0, "application.yml — allowed");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"app/src/main/java/com/x/config/web/HstsHeaderFilter.java\"}}",
                0, "the HSTS filter, in a module — allowed");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"src/main/java/com/x/config/web/OtherConfig.java\"}}",
                2, "any other main class — blocked");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"src/main/resources/tls/server.key\"}}",
                2, "a key file under resources — blocked");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"src/test/java/com/x/TransportSecurityIT.java\"}}",
                0, "the transport IT — allowed");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"CLAUDE.md\"}}",
                0, "the Transport paragraph — allowed");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"Dockerfile\"}}",
                2, "Dockerfile is docker-architect's — blocked");

        failures += run(hook, "call", transport,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"docker-architect\"}}",
                0, "docker-architect joins the phase");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"Dockerfile\"}}",
                0, "Dockerfile allowed once docker-architect joined");

        failures += run(hook, "write", transport,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"docker/caddy/Caddyfile\"}}",
                0, "the Caddyfile — docker-architect's docker/**");

        // docker-architect's database files (decision 0124): a database service reads its
        // password from the untracked `.env`, with `.env.example` beside it and `.env` ignored
        // by git and by the Docker build context. The datasource defaults in application.yml
        // stay outside its territory — they belong to whoever writes the application's config.
        String docker = "ci-territory-docker-" + System.nanoTime();
        failures += run(hook, "prompt", docker,
                "{\"session_id\":\"%s\",\"prompt\":\"/docker-architect\"}",
                0, "prompt opens docker-architect's phase");
        for (String f : new String[] {".env", ".env.example", ".gitignore", ".dockerignore"}) {
            failures += run(hook, "write", docker,
                    "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"" + f + "\"}}",
                    0, f + " — docker-architect's, step 4.5");
        }
        failures += run(hook, "write", docker,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"src/main/resources/application.yml\"}}",
                2, "application.yml is not docker-architect's — blocked");

        // `report` (decision 0100): the class default is the whole territory, with no
        // override. sonar-lessons runs a full build and publishes outward — the one file it
        // may leave behind is its lessons-learned, never the build file or code.
        String report = "ci-territory-report-" + System.nanoTime();
        failures += run(hook, "prompt", report,
                "{\"session_id\":\"%s\",\"prompt\":\"/sonar-lessons\"}",
                0, "prompt opens sonar-lessons' report phase");

        failures += run(hook, "write", report,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"docs/lessons-learned/sonar-001.md\"}}",
                0, "docs/lessons-learned/ — allowed");

        failures += run(hook, "write", report,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"pom.xml\"}}",
                2, "pom.xml is not a report's — blocked");

        failures += run(hook, "write", report,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                2, "src/ is not a report's — blocked");

        // harness-lessons' override (decision 0188): in this repository its file is history
        // about `.claude/`, beside the run reports — never in the public docs/ tree. The glob is
        // `harness-*.md`, so a run report next to it stays out of reach.
        String harness = "ci-territory-harness-" + System.nanoTime();
        failures += run(hook, "prompt", harness,
                "{\"session_id\":\"%s\",\"prompt\":\"/harness-lessons\"}",
                0, "prompt opens harness-lessons' report phase");

        failures += run(hook, "write", harness,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\".claude/lessons-learned/harness-002.md\"}}",
                0, ".claude/lessons-learned/harness-*.md — allowed by the override");

        failures += run(hook, "write", harness,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\".claude/lessons-learned/lessons-learned-026.md\"}}",
                2, "a run report beside it is not harness-lessons' — blocked");

        failures += run(hook, "write", report,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\".claude/lessons-learned/harness-002.md\"}}",
                2, "the override is harness-lessons' alone — sonar-lessons blocked");

        // `blocked_during_design`: a report is a full build, so a design run cannot reach it.
        String reportMidDesign = "ci-territory-report-design-" + System.nanoTime();
        failures += run(hook, "prompt", reportMidDesign,
                "{\"session_id\":\"%s\",\"prompt\":\"/new-feature add a use case\"}",
                0, "prompt opens the orchestrator phase");

        failures += run(hook, "call", reportMidDesign,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"sonar-lessons\"}}",
                2, "report-class Skill call refused mid-design");

        // A classed agent's Skill call is judged by the agent's class, not the caller's phase
        // (decision 0126, issue #99). project-initializer runs under `/init-project` — class
        // `orchestrator`, `design_phase` — and chains two build-class skills through `Skill`.
        // Refusing those calls blocked the generation. Opening the callee's phase would also
        // have overwritten the main thread's phase with a wider one.
        String agentChain = "ci-territory-agent-chain-" + System.nanoTime();
        failures += run(hook, "prompt", agentChain,
                "{\"session_id\":\"%s\",\"prompt\":\"/init-project --name x\"}",
                0, "prompt opens init-project's orchestrator phase");

        failures += run(hook, "call", agentChain,
                "{\"session_id\":\"%s\",\"agent_type\":\"project-initializer\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"transport-security-setup\"}}",
                0, "classed agent's build-class Skill call admitted mid-orchestrator");

        failures += run(hook, "write", agentChain,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"CLAUDE.md\"}}",
                2, "the main thread keeps init-project's empty territory — not the callee's");

        failures += run(hook, "call", agentChain,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"sonarqube-setup\"}}",
                2, "the same call from the main thread is still refused");

        failures += run(hook, "call", agentChain,
                "{\"session_id\":\"%s\",\"agent_type\":\"general-purpose\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"sonarqube-setup\"}}",
                2, "an unclassed agent still falls back to the phase");

        // `/new-feature-implement` (decision 0135): an orchestrator with an empty territory. Its
        // main thread chains the executor and the installers and writes nothing itself — not
        // even under docs/use-cases/, which `/new-feature`, the same class, may write. The name
        // shares `/new-feature` as a prefix, so this also proves the prompt resolves to the
        // implement skill and not the design one.
        String implement = "ci-territory-implement-" + System.nanoTime();
        failures += run(hook, "prompt", implement,
                "{\"session_id\":\"%s\",\"prompt\":\"/new-feature-implement UC-001-x\"}",
                0, "prompt opens new-feature-implement's orchestrator phase");

        failures += run(hook, "write", implement,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"docs/use-cases/UC-002-y/00-use-case.md\"}}",
                2, "docs/use-cases/ is not the implement thread's — blocked");

        failures += run(hook, "write", implement,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                2, "src/ from the implement thread itself — blocked");

        failures += run(hook, "write", implement,
                "{\"session_id\":\"%s\",\"agent_type\":\"java-spring-boot-developer\","
                        + "\"tool_input\":{\"file_path\":\"src/main/java/A.java\"}}",
                0, "the executor's write under the implement phase — its own class");

        failures += run(hook, "call", implement,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"docker-architect\"}}",
                2, "build-class Skill call refused mid-implement");

        failures += run(hook, "call", implement,
                "{\"session_id\":\"%s\",\"tool_name\":\"Skill\","
                        + "\"tool_input\":{\"skill\":\"test-architect\"}}",
                0, "the pre-flight's test-architect setup call admitted");

        // The implement run adds the spec's `materialize` services through an agent (decision
        // 0137): the same docker-architect call the main thread is refused above passes from
        // compose-service-installer, and the skill's write lands in the agent's territory.
        failures += run(hook, "call", implement,
                "{\"session_id\":\"%s\",\"agent_type\":\"compose-service-installer\","
                        + "\"tool_name\":\"Skill\",\"tool_input\":{\"skill\":\"docker-architect\"}}",
                0, "compose-service-installer's docker-architect call admitted mid-implement");

        failures += run(hook, "write", implement,
                "{\"session_id\":\"%s\",\"agent_type\":\"compose-service-installer\","
                        + "\"tool_input\":{\"file_path\":\"docker-compose.yml\"}}",
                0, "…and its compose write, judged by its own class");

        failures += run(hook, "write", implement,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"docker-compose.yml\"}}",
                2, "the implement thread itself still may not write compose");

        // The meta class writes the README's skill list and counts when it adds a piece (0144 to
        // 0162 did), and nothing else at the repository's root but CLAUDE.md and .mcp.json.
        // Before decision 0163 the README was outside it, and the edit went in unguarded.
        // class `media` (decision 0190): diagrams of this `.claude/` under docs/diagrams/, and no
        // spec folder — `.drawio`/`.excalidraw` beside a spec stay refused (decision 0143).
        String media = "ci-territory-media-" + System.nanoTime();
        failures += run(hook, "prompt", media,
                "{\"session_id\":\"%s\",\"prompt\":\"/nerviz-diagrams\"}",
                0, "prompt opens nerviz-diagrams' media phase");

        failures += run(hook, "write", media,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"docs/diagrams/layers/layers.svg\"}}",
                0, "docs/diagrams/** — allowed for class media");

        failures += run(hook, "write", media,
                "{\"session_id\":\"%s\",\"tool_input\":"
                        + "{\"file_path\":\"docs/use-cases/UC-001-x/a.drawio\"}}",
                2, "a diagram beside a spec is not media's — blocked");

        String meta = "ci-territory-meta-" + System.nanoTime();
        failures += run(hook, "prompt", meta,
                "{\"session_id\":\"%s\",\"prompt\":\"/claude-code-architect-designer x\"}",
                0, "prompt opens the meta phase");

        failures += run(hook, "write", meta,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"README.md\"}}",
                0, "README.md is meta's — allowed");

        failures += run(hook, "write", meta,
                "{\"session_id\":\"%s\",\"tool_input\":{\"file_path\":\"CONTRIBUTING.md\"}}",
                2, "another root file stays blocked");

        if (failures > 0) {
            System.err.println("❌ " + failures + " case(s) failed — the territory guard is NOT"
                    + " enforcing what skill_classes declares.");
            System.exit(1);
        }
        System.out.println("✅ Skill territory enforced: deny by default while a phase is open,"
                + " agent_type judged by its own class on writes and Skill calls,"
                + " build-class calls refused mid-design,"
                + " same-class chains summing territories, report-class runs held to"
                + " docs/lessons-learned/ (harness-lessons also its own .claude/lessons-learned/harness-*.md),"
                + " media-class runs held to docs/diagrams/**,"
                + " and no restriction with no phase open.");
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
