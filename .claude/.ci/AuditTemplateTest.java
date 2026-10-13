///usr/bin/env java --source 21 "$0" "$@" ; exit $?
//
// CI test: proves the auditing exemplars of persistence-architect and test-architect carry every
// decision `.claude/rules/persistence.md` § Auditing makes explicit, that no other exemplar
// reaches Envers or Spring Data's history, and that every blueprint keeps them out of its inner
// layers.
//
// Why this test exists: every Envers default and every auditing shortcut fails late and quietly —
// a bag writes a duplicate once in the history, a `NOT NULL` on an `_aud` column passes
// validation and breaks the first delete, an `Instant` revision timestamp breaks every update's
// commit under the validity strategy, a prune by the revision's own timestamp deletes the version
// still in force, an `AuditorAware` returning empty records no actor, a rolled-back test sees no
// history and passes. Each was reproduced against a real Postgres when the exemplars were written
// (0149 § CI coverage); the executor copies an exemplar's shape, so one of them left in a
// template ships into every project that audits an aggregate.
//
// What it holds, each failure named by file:
//   - every `List` collection of an `@Audited` entity carries `@OrderColumn` or `@CollectionId`;
//   - the stamps embeddable keeps both `created` columns `updatable = false` and has no setter,
//     and the shared assigned-id superclass carries no stamp;
//   - the revision entity numbers revisions with a `Long`/`long`, stamps them with a `long`, and
//     its `allocationSize` equals the migration's `INCREMENT BY`;
//   - the actor resolver throws on a missing authentication and never returns an empty auditor;
//   - no exemplar enables auditing on a `@SpringBootApplication` class, uses Hibernate's own
//     `org.hibernate.annotations.Audited`, or audits the outbox or the idempotency store;
//   - the Envers yml selects the validity strategy with the revision-end timestamp and never
//     stores data at delete;
//   - the audit migration creates `revinfo`, and every `_aud` table has nullable non-key columns,
//     `revend` and `revend_tstmp`, and an index on each;
//   - the prune deletes by `revend_tstmp`;
//   - the audit IT commits — no `@DataJpaTest`, no `@Transactional` — and pins its image through
//     `DockerImageName.parse`;
//   - no exemplar outside persistence-architect's and the audit IT imports Envers or Spring Data's
//     history;
//   - every `domain`/`core` module that declares `forbidden_imports` forbids `org.hibernate..`,
//     and every `application` module forbids Envers, Spring Data's history and its auditing
//     annotations.
//
// Offline and in milliseconds. Whether the exemplars compile is `templates` › JavaTemplatesTest's
// job; whether they run was checked against a real engine when they were written (0149).

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

public class AuditTemplateTest {

    static final Path SKILLS = Paths.get(".claude/skills");
    static final Path PERSISTENCE = SKILLS.resolve("persistence-architect/templates");
    static final Path ENTITY = PERSISTENCE.resolve("AuditedEntity.java.example");
    static final Path REVISION = PERSISTENCE.resolve("AuditRevision.java.example");
    static final Path CONFIG = PERSISTENCE.resolve("AuditingConfig.java.example");
    static final Path HISTORY = PERSISTENCE.resolve("AuditHistoryAdapter.java.example");
    static final Path JPA_ENTITY = PERSISTENCE.resolve("JpaEntity.java.example");
    static final Path SQL = PERSISTENCE.resolve("V1__create_audit_tables.sql.example");
    static final Path YML = PERSISTENCE.resolve("application-audit.yml.example");
    static final Path AUDIT_IT = SKILLS.resolve("test-architect/templates/AuditHistoryIT.java.example");
    static final List<Path> SHARED_STORES = List.of(
            PERSISTENCE.resolve("OutboxEventStore.java.example"),
            PERSISTENCE.resolve("IdempotencyKeyStore.java.example"),
            PERSISTENCE.resolve("MongoSharedCollections.java.example"));
    static final Path BLUEPRINTS = Paths.get(".claude/blueprints");

    static final Pattern HISTORY_IMPORT = Pattern.compile(
            "^import (static )?(org\\.hibernate\\.envers|org\\.springframework\\.data\\.history|org\\.springframework\\.data\\.envers)\\.",
            Pattern.MULTILINE);
    static final Pattern BLOCK = Pattern.compile("(?m)^// --- (\\S+\\.java)");
    static final Pattern FIELD = Pattern.compile("((?:\\s*@[^\\n]+\\n)+)\\s*(?:private |protected |public )?([\\w<>, ]+?)\\s+(\\w+)\\s*(?:=[^;]*)?;");

    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        List<Path> exemplars;
        try (Stream<Path> s = Files.walk(SKILLS)) {
            exemplars = s.filter(p -> p.toString().replace('\\', '/').contains("/templates/"))
                    .filter(p -> p.toString().endsWith(".java.example"))
                    .sorted().toList();
        }

        boundary(exemplars);
        auditedCollections();
        stamps();
        revision();
        resolver();
        noAuditingOnApplicationClass(exemplars);
        sharedStoresNotAudited();
        yml();
        migration();
        prune();
        auditIt();
        blueprints();

        if (!failures.isEmpty()) {
            System.out.println("❌ " + failures.size() + " auditing exemplar defect(s):");
            failures.forEach(f -> System.out.println("   " + f));
            System.exit(1);
        }
        System.out.println("✅ Auditing exemplars: bags, stamps, revision types, actor, Envers properties, "
                + "audit migration, prune, committing IT and the blueprints' inner layers hold (" + exemplars.size()
                + " exemplars scanned).");
    }

    static void boundary(List<Path> exemplars) {
        for (Path file : exemplars) {
            boolean owned = file.startsWith(PERSISTENCE) || file.equals(AUDIT_IT);
            if (!owned && HISTORY_IMPORT.matcher(text(file)).find()) {
                fail(file, "imports Envers or Spring Data's history outside persistence-architect's exemplars (persistence.md § Boundary)");
            }
            if (code(text(file)).contains("org.hibernate.annotations.Audited")) {
                fail(file, "uses Hibernate's own incubating @Audited — the history is Envers' (persistence.md § Auditing)");
            }
        }
    }

    static void auditedCollections() {
        int audited = 0;
        for (Map.Entry<String, String> block : blocks(code(text(ENTITY))).entrySet()) {
            if (!block.getValue().contains("@Audited")) continue;
            audited++;
            Matcher field = FIELD.matcher(block.getValue());
            while (field.find()) {
                String annotations = field.group(1);
                if (field.group(2).trim().startsWith("List<")
                        && (annotations.contains("@OneToMany") || annotations.contains("@ManyToMany") || annotations.contains("@ElementCollection"))
                        && !annotations.contains("@OrderColumn") && !annotations.contains("@CollectionId")) {
                    fail(ENTITY, block.getKey() + "." + field.group(3)
                            + " is a List with no order column on an audited entity — a bag, whose duplicate the history writes once");
                }
            }
        }
        if (audited == 0) fail(ENTITY, "no @Audited entity — the history exemplar is gone");
    }

    static void stamps() {
        String stampsBlock = blocks(code(text(ENTITY))).getOrDefault("shared/audit/AuditStamps.java", "");
        if (stampsBlock.isEmpty()) {
            fail(ENTITY, "no shared/audit/AuditStamps.java block");
        } else {
            for (String created : List.of("@CreatedDate", "@CreatedBy")) {
                Matcher m = Pattern.compile(Pattern.quote(created) + "\\s*\\n\\s*@Column\\(([^)]*)\\)").matcher(stampsBlock);
                if (!m.find() || !m.group(1).contains("updatable = false")) {
                    fail(ENTITY, created + " column is updatable — a merge could overwrite who and when created");
                }
            }
            if (stampsBlock.contains("@Setter") || Pattern.compile("\\bvoid set[A-Z]").matcher(stampsBlock).find()) {
                fail(ENTITY, "AuditStamps has a setter — the auditing listener is the only writer");
            }
        }
        String superclass = blocks(code(text(JPA_ENTITY))).getOrDefault("shared/AssignedIdEntity.java", "");
        if (Pattern.compile("@(CreatedDate|CreatedBy|LastModifiedDate|LastModifiedBy|Embedded)").matcher(superclass).find()) {
            fail(JPA_ENTITY, "AssignedIdEntity carries a stamp — every table would take one shape");
        }
    }

    static void revision() {
        String code = code(text(REVISION));
        Matcher number = Pattern.compile("@RevisionNumber[^;]*?\\b(\\w+)\\s+\\w+;").matcher(code);
        if (!number.find() || !List.of("Long", "long").contains(number.group(1))) {
            fail(REVISION, "the revision number is not a Long — an int overflows into an AuditException that rolls the write back");
        }
        Matcher timestamp = Pattern.compile("@RevisionTimestamp[^;]*?\\b(\\w+)\\s+\\w+;").matcher(code);
        if (!timestamp.find() || !timestamp.group(1).equals("long")) {
            fail(REVISION, "the revision timestamp is not a long — the validity strategy casts it to Long and fails every update's commit");
        }
        Matcher allocation = Pattern.compile("allocationSize\\s*=\\s*(\\d+)").matcher(code);
        Matcher increment = Pattern.compile("(?i)CREATE SEQUENCE revinfo_seq[^;]*INCREMENT BY (\\d+)").matcher(text(SQL));
        if (!allocation.find() || !increment.find() || !allocation.group(1).equals(increment.group(1))) {
            fail(REVISION, "allocationSize differs from the migration's INCREMENT BY — the startup refuses the mapping");
        }
    }

    static void resolver() {
        String resolver = blocks(code(text(CONFIG))).getOrDefault("shared/audit/AuditorResolver.java", "");
        if (resolver.isEmpty()) {
            fail(CONFIG, "no shared/audit/AuditorResolver.java block");
            return;
        }
        if (!resolver.contains("throw new IllegalStateException")) {
            fail(CONFIG, "AuditorResolver does not refuse a missing authentication — an audited write would record no actor");
        }
        if (code(text(CONFIG)).contains("Optional.empty()")) {
            fail(CONFIG, "an auditor resolves to Optional.empty() — the actor is never null (persistence.md § Auditing)");
        }
    }

    static void noAuditingOnApplicationClass(List<Path> exemplars) {
        for (Path file : exemplars) {
            for (Map.Entry<String, String> block : blocks(code(text(file))).entrySet()) {
                String body = block.getValue();
                if (body.contains("@SpringBootApplication") && body.matches("(?s).*@Enable(Jpa|Mongo)Auditing.*")) {
                    fail(file, block.getKey() + " enables auditing on the application class — every slice without the store fails");
                }
            }
        }
    }

    static void sharedStoresNotAudited() {
        for (Path store : SHARED_STORES) {
            if (code(text(store)).contains("@Audited")) {
                fail(store, "audits a shared technical table — the outbox, idempotency keys and scheduling tables are never audited");
            }
        }
    }

    static void yml() {
        String yml = code(text(YML)).replaceAll("(?m)^\\s*#.*$", "");
        if (!yml.contains("org.hibernate.envers.audit_strategy: org.hibernate.envers.strategy.internal.ValidityAuditStrategy")) {
            fail(YML, "does not select the validity strategy");
        }
        if (!yml.contains("org.hibernate.envers.audit_strategy_validity_store_revend_timestamp: true")) {
            fail(YML, "does not store the revision-end timestamp — the prune by age has nothing to read");
        }
        if (yml.contains("store_data_at_delete: true")) {
            fail(YML, "stores data at delete — a second copy of personal data the previous version already holds");
        }
    }

    static void migration() {
        String sql = text(SQL).replaceAll("(?m)--.*$", "");
        if (!Pattern.compile("(?i)CREATE TABLE revinfo\\b").matcher(sql).find()) fail(SQL, "creates no revinfo table");
        Matcher table = Pattern.compile("(?is)CREATE TABLE (\\w+_aud)\\s*\\((.*?)\\n\\);").matcher(sql);
        int tables = 0;
        while (table.find()) {
            tables++;
            String name = table.group(1);
            for (String line : table.group(2).split("\n")) {
                String column = line.trim();
                if (column.isEmpty() || column.toUpperCase(Locale.ROOT).startsWith("PRIMARY KEY")) continue;
                String columnName = column.split("\\s+")[0];
                if (!List.of("id", "rev").contains(columnName) && column.toUpperCase(Locale.ROOT).contains("NOT NULL")) {
                    fail(SQL, name + "." + columnName + " is NOT NULL — a delete revision keeps the id only, and the first delete fails");
                }
            }
            for (String column : List.of("revend", "revend_tstmp")) {
                if (!Pattern.compile("(?m)^\\s*" + column + "\\s").matcher(table.group(2)).find()) {
                    fail(SQL, name + " has no " + column + " column — the validity strategy needs it");
                }
                if (!Pattern.compile("(?i)CREATE INDEX \\w+ ON " + name + " \\(" + column + "\\)").matcher(sql).find()) {
                    fail(SQL, name + " has no index on " + column + " — Hibernate creates none, and every write and the prune read by it");
                }
            }
        }
        if (tables == 0) fail(SQL, "creates no _aud table");
    }

    static void prune() {
        String adapter = blocks(code(text(HISTORY))).getOrDefault("shared/audit/AuditHistoryRetentionAdapter.java", "");
        if (adapter.isEmpty()) {
            fail(HISTORY, "no shared/audit/AuditHistoryRetentionAdapter.java block");
        } else if (!adapter.contains("revend_tstmp < :cutoff")) {
            fail(HISTORY, "the prune does not delete by revend_tstmp — by the revision's own timestamp it deletes the version in force");
        }
    }

    static void auditIt() {
        String it = code(text(AUDIT_IT));
        if (it.contains("@DataJpaTest") || it.contains("@Transactional")) {
            fail(AUDIT_IT, "rolls its writes back — the history is written at commit, so the test would see none");
        }
        if (!it.contains("DockerImageName.parse(")) {
            fail(AUDIT_IT, "pins its image without DockerImageName.parse — the compose tag check cannot compare it");
        }
    }

    static void blueprints() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(BLUEPRINTS)) {
            files = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path file : files) {
            String text = text(file);
            Matcher domain = Pattern.compile("(?ms)^  - id: (domain|core)\\n(.*?)(?=^  - id: |^[a-z]|^  #)").matcher(text);
            if (domain.find() && domain.group(2).contains("forbidden_imports") && !domain.group(2).contains("\"org.hibernate..\"")) {
                fail(file, "lets the " + domain.group(1) + " module import org.hibernate..");
            }
            Matcher application = Pattern.compile("(?ms)^  - id: application\\n(.*?)(?=^  - id: |^[a-z]|^  #)").matcher(text);
            if (application.find() && application.group(1).contains("forbidden_imports")) {
                for (String prefix : List.of("org.hibernate.envers..", "org.springframework.data.history..", "org.springframework.data.annotation..")) {
                    if (!application.group(1).contains("\"" + prefix + "\"")) {
                        fail(file, "lets the application module import " + prefix + " — the history is read through a port");
                    }
                }
            }
        }
    }

    static Map<String, String> blocks(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = BLOCK.matcher(text);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start(), m.end()});
            names.add(m.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : text.length();
            out.put(names.get(i), text.substring(starts.get(i)[1], end));
        }
        return out;
    }

    static void fail(Path file, String message) {
        failures.add(rel(file) + " — " + message);
    }

    /** The text without its comments, `// --- ` block markers kept: a header that names a call does not make it. */
    static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//(?! --- ).*$", "");
    }

    static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (Exception missing) {
            failures.add(rel(file) + " — missing");
            return "";
        }
    }

    static String rel(Path file) {
        return file.toString().replace('\\', '/');
    }
}
