///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the executor's block files and the table that names them agree.
// `java-spring-boot-developer` reads one file per block from
// `.claude/skills/new-feature-implement/references/blocks/`, told which by its § Blocks table.
// A block id added to `spec_checklist` with no file, a file the table never names, a table row
// whose file is gone, or a block pasted back into the agent body all leave the executor
// reading nothing or everything, and no other check notices. Decision 0199.
//
// Reads the repository files as they are; the minimal JSON scan (ids of `spec_checklist.blocks`)
// mirrors what the neighbouring tests do, with no dependency.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class ExecutorBlocksTest {

    static final Path AGENT = Paths.get(".claude/agents/java-spring-boot-developer.md");
    static final Path BLOCKS = Paths.get(".claude/skills/new-feature-implement/references/blocks");
    static final Path EXTENSIONS = Paths.get(".claude/schemas/extensions.json");

    public static void main(String[] args) throws Exception {
        for (Path required : List.of(AGENT, EXTENSIONS)) {
            if (!Files.isRegularFile(required)) {
                System.out.println("FAIL missing " + required);
                System.exit(1);
            }
        }
        String json = Files.readString(EXTENSIONS, StandardCharsets.UTF_8);
        String agent = Files.readString(AGENT, StandardCharsets.UTF_8);
        List<String> failures = new ArrayList<>();

        // (a) one file per spec_checklist block id, plus the two always-run blocks
        List<String> ids = new ArrayList<>(List.of("domain", "tests"));
        int at = json.indexOf("\"spec_checklist\"");
        int from = at < 0 ? -1 : json.indexOf("\"blocks\"", at);
        if (from < 0) {
            failures.add("extensions.json has no spec_checklist.blocks");
        } else {
            int end = json.indexOf("\n    ]", from);
            if (end < 0) end = json.length();
            Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([a-z0-9-]+)\"").matcher(json.substring(from, end));
            while (m.find()) ids.add(m.group(1));
        }
        for (String id : ids) {
            if (!Files.isRegularFile(BLOCKS.resolve(id + ".md"))) {
                failures.add("block id `" + id + "` has no file " + BLOCKS.resolve(id + ".md"));
            }
        }

        // the table of § Blocks
        Set<String> named = new TreeSet<>();
        boolean inSection = false;
        for (String line : agent.split("\n", -1)) {
            if (line.startsWith("### Blocks")) { inSection = true; continue; }
            if (inSection && line.startsWith("#")) break;
            if (inSection && line.startsWith("|")) {
                Matcher m = Pattern.compile("`([a-z0-9-]+\\.md)`").matcher(line);
                if (m.find()) named.add(m.group(1));
            }
        }
        if (named.isEmpty()) failures.add("the agent body has no § Blocks table naming a block file");

        // (b) every file on disk is named by the table
        if (Files.isDirectory(BLOCKS)) {
            try (Stream<Path> files = Files.list(BLOCKS)) {
                for (Path f : files.sorted().collect(Collectors.toList())) {
                    String name = f.getFileName().toString();
                    if (!named.contains(name)) {
                        failures.add("file `" + name + "` exists but the agent's § Blocks table does not name it");
                    }
                }
            }
        } else {
            failures.add("missing directory " + BLOCKS);
        }

        // (c) every file the table names exists
        for (String name : named) {
            if (!Files.isRegularFile(BLOCKS.resolve(name))) {
                failures.add("§ Blocks names `" + name + "` but " + BLOCKS.resolve(name) + " does not exist");
            }
        }

        // (d) no block pasted back into the body
        String[] lines = agent.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("### Block ")) {
                failures.add("agent body line " + (i + 1) + " has the heading `" + lines[i] + "`: blocks live in " + BLOCKS);
            }
        }

        if (failures.isEmpty()) {
            System.out.println("OK " + ids.size() + " block ids, " + named.size() + " files named, none in the agent body");
            return;
        }
        failures.forEach(f -> System.out.println("FAIL " + f));
        System.exit(1);
    }
}
