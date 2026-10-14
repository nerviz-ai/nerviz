///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the MapStruct mapper exemplars stay inside `.claude/rules/mapping.md`, and that
// every skill whose exemplars carry a manual mapper also carries its MapStruct sibling.
//
// Why this test exists: the executor copies an exemplar's shape into every project whose root
// CLAUDE.md says `**Mappers: MapStruct.**`, and the installer converts existing mappers after them.
// A forbidden construct in an exemplar — `expression = "java(...)"`, an `INSTANCE` field, a
// `componentModel` repeated on the annotation — ships into every one of those projects; a skill
// with only the manual exemplar leaves the executor improvising the MapStruct one
// (decision 0160).
//
// What it holds, each failure named by file and line, over every
// `.claude/skills/*/templates/*MapStructMapper.java.example`, comments blanked first:
//   - none of § Forbidden: `expression =`, `defaultExpression`, `Mappers.getMapper`, an `INSTANCE`
//     field, `@Context`, `@BeforeMapping`, `@AfterMapping`, `NullValuePropertyMappingStrategy`,
//     `unmappedTargetPolicy`, `defaultValue`, `java.util.Date`; nor `componentModel` or
//     `injectionStrategy`, which the build's processor options own;
//   - at least one `@Mapper` interface, and none of them `public`;
// and over every other `*.java.example` under `.claude/skills/*/templates/`: a class or interface
// named `*Mapper` means that skill's `templates/` holds a `*MapStructMapper.java.example`.
//
// Offline and in milliseconds. Whether the exemplars compile is MapStructWiringTest's
// (`templates.yml`), which builds them against a real project.

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class MapperTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final String SUFFIX = "MapStructMapper.java.example";
    static final Map<String, String> FORBIDDEN = new LinkedHashMap<>();
    static {
        FORBIDDEN.put("\\bexpression\\s*=", "`expression =` — a @Named method or a `uses =` class");
        FORBIDDEN.put("\\bdefaultExpression\\b", "`defaultExpression` — a @Named method");
        FORBIDDEN.put("\\bMappers\\s*\\.\\s*getMapper\\b", "`Mappers.getMapper` — constructor injection");
        FORBIDDEN.put("\\bINSTANCE\\b", "an `INSTANCE` field — constructor injection");
        FORBIDDEN.put("@Context\\b", "`@Context` — the adapter looks the value up and passes it");
        FORBIDDEN.put("@(Before|After)Mapping\\b", "`@BeforeMapping`/`@AfterMapping` — a default method or @SubclassMapping");
        FORBIDDEN.put("\\bNullValuePropertyMappingStrategy\\b", "`NullValuePropertyMappingStrategy` — only on a PATCH method, never in an exemplar's general shape");
        FORBIDDEN.put("\\bunmappedTargetPolicy\\b", "`unmappedTargetPolicy` — the build sets it to ERROR, nothing lowers it");
        FORBIDDEN.put("\\bdefaultValue\\s*=", "`defaultValue` — a default is the domain's or the DTO validation's");
        FORBIDDEN.put("\\bjava\\.util\\.Date\\b", "`java.util.Date` — java.time");
        FORBIDDEN.put("\\bcomponentModel\\b", "`componentModel` — the build's processor option owns it");
        FORBIDDEN.put("\\binjectionStrategy\\b", "`injectionStrategy` — the build's processor option owns it");
    }
    static final Pattern MAPPER_INTERFACE = Pattern.compile("(?m)^(public\\s+)?interface\\s+(\\w+)");
    static final Pattern MANUAL_MAPPER = Pattern.compile("\\b(class|interface)\\s+\\w*Mapper\\b");

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();
        int exemplars = 0;
        Set<Path> skillsWithManual = new TreeSet<>();
        Set<Path> skillsWithMapStruct = new TreeSet<>();

        for (Path file : templates()) {
            String name = file.getFileName().toString();
            Path skill = file.getParent().getParent();
            String code = blankComments(Files.readString(file, StandardCharsets.UTF_8));
            if (name.endsWith(SUFFIX)) {
                exemplars++;
                skillsWithMapStruct.add(skill);
                for (Map.Entry<String, String> f : FORBIDDEN.entrySet()) {
                    Matcher m = Pattern.compile(f.getKey()).matcher(code);
                    while (m.find()) failures.add(file + ":" + line(code, m.start()) + ": " + f.getValue());
                }
                if (!code.contains("@Mapper")) failures.add(file + ": no @Mapper interface");
                Matcher i = MAPPER_INTERFACE.matcher(code);
                while (i.find()) {
                    if (i.group(1) != null) failures.add(file + ":" + line(code, i.start())
                            + ": `public interface " + i.group(2) + "` — a mapper is package-private, beside its only callers");
                }
            } else if (MANUAL_MAPPER.matcher(code).find()) {
                skillsWithManual.add(skill);
            }
        }
        if (exemplars == 0) failures.add("no *" + SUFFIX + " under " + SKILLS);
        for (Path skill : skillsWithManual) {
            if (!skillsWithMapStruct.contains(skill)) failures.add(skill.resolve("templates")
                    + ": an exemplar carries a manual *Mapper and no *" + SUFFIX + " sits beside it");
        }

        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("❌ " + f));
            System.exit(1);
        }
        System.out.println("✅ " + exemplars + " MapStruct mapper exemplars hold .claude/rules/mapping.md § Forbidden and are package-private; "
                + skillsWithManual.size() + " skills with a manual mapper exemplar each carry the MapStruct one.");
    }

    static List<Path> templates() throws Exception {
        try (Stream<Path> s = Files.walk(SKILLS)) {
            return s.filter(p -> p.getParent().getFileName().toString().equals("templates"))
                    .filter(p -> p.getFileName().toString().endsWith(".java.example"))
                    .sorted().collect(Collectors.toList());
        }
    }

    /** Comments become spaces, keeping every offset and line break; strings stay — an annotation attribute lives in one. */
    static String blankComments(String s) {
        StringBuilder out = new StringBuilder(s);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '"') j += s.charAt(j) == '\\' ? 2 : 1;
                i = j + 1;
            } else if (s.startsWith("//", i)) {
                int j = s.indexOf('\n', i);
                if (j < 0) j = s.length();
                for (int k = i; k < j; k++) out.setCharAt(k, ' ');
                i = j;
            } else if (s.startsWith("/*", i)) {
                int j = s.indexOf("*/", i + 2);
                j = j < 0 ? s.length() : j + 2;
                for (int k = i; k < j; k++) if (s.charAt(k) != '\n') out.setCharAt(k, ' ');
                i = j;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    static int line(String s, int offset) {
        int n = 1;
        for (int i = 0; i < offset; i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}
