# `/init-project` — criar um projeto do zero

Fonte primária: `.claude/skills/init-project/SKILL.md`,
`.claude/agents/project-initializer.md`, `.claude/skills/project-bootstrap/SKILL.md`.

## O que faz

Transforma um diretório novo, ao lado do clone do Nerviz, em um projeto Spring Boot com arquitetura declarada,
boundaries executáveis e build verde — **sem código de negócio**. Nenhum exemplo, seed
ou aggregate de demonstração: o bootstrap gera estrutura, e a primeira feature vem do
pipeline `/new-feature`, de uma spec real
(`.claude/decisions/0011-bootstrap-without-business-code.md`, citado em
`project-bootstrap/SKILL.md`).

## Onde o projeto nasce

A sessão roda num clone deste repositório, e o projeto é gerado ao lado dele, em
`../<artifactId>` (`project-bootstrap/SKILL.md` § Where the project is born). O `/init-project`,
o `project-bootstrap` e o catálogo de blueprints ficam no clone; o projeto recebe o próprio
`.claude/` do `ArchHook.java export`, que se recusa a escrever no próprio clone. Todo comando de
shell roda como `(cd "<project>" && …)`, e toda skill encadeada — `transport-security-setup`,
`sonarqube-setup`, `git-publish` e, por meio delas, `docker-architect` — recebe
`project: <path>` no contexto. O desenvolvimento começa numa sessão nova, aberta no projeto
gerado, a única que carrega o `settings.json` e os hooks dele.

O Claude Code pede permissão antes de escrever fora do diretório em que a sessão começou. Para
aprovar a geração de uma vez, criar o diretório e adicioná-lo à sessão antes do comando:
`mkdir ../my-api` e depois `/add-dir ../my-api`. Um diretório vazio passa na checagem de
existência.

## Por que é uma skill que delega para um agent

`init-project/SKILL.md` é `disable-model-invocation: true` — ritual manual, nunca
disparado pelo modelo sozinho. Ela mesma não faz o trabalho: delega via `Agent tool`
para `project-initializer`, que existe como agent por dois dos três motivos válidos
(`CLAUDE.md` § Invariant 5) — geração produz saída verbosa que não deveria encher a
conversa, e roda com um conjunto restrito de tools. `project-initializer` usa
`model: sonnet` com `effort: high`: o procedimento é dirigido pelo blueprint, pelo
Initializr e pelos templates, não por julgamento (decisão 0078).

`project-initializer`, por sua vez, **não reimplementa o procedimento** — ele segue
`.claude/skills/project-bootstrap/SKILL.md` passo a passo. A skill `project-bootstrap`
tem `disable-model-invocation` ausente (portanto invocável pelo modelo também), mas na
prática só é acionada por este caminho e por `/new-feature` quando um projeto ainda
não existe.

## Diagrama de sequência

```mermaid
sequenceDiagram
    actor U as Usuário
    participant CMD as skill: init-project
    participant AG as agent: project-initializer
    participant BOOT as skill: project-bootstrap
    participant INIT as Spring Initializr
    participant BP as blueprints/*.yaml
    participant RULES as rules/*.md
    participant HOOK as ArchHook.java + settings.json

    U->>CMD: /init-project [--blueprint hexagonal] [--groupId ...]
    CMD->>CMD: checa pom.xml/build.gradle já existente em ../<artifactId>
    alt projeto já existe
        CMD-->>U: reporta e sugere /new-feature
    else ../<artifactId> novo ou vazio
        CMD->>U: entrevista inteira via AskUserQuestion (blueprint, build, features, coordenadas, TLS, Sonar — só o que faltar)
        U-->>CMD: respostas
        CMD->>AG: Agent tool com todas as respostas (contexto isolado, tools restritos, sem AskUserQuestion, model sonnet)
        AG->>BOOT: segue o procedimento definido
        BOOT->>BP: lista .claude/blueprints/*/*.yaml dinamicamente
        BOOT->>BOOT: valida blueprint (6 regras — CLAUDE.md § _schema.md)
        BOOT->>INIT: curl start.spring.io/starter.tgz (versões reais, nunca de memória)
        INIT-->>BOOT: starter.tgz (pom.xml, mvnw, Application.java)
        BOOT->>BOOT: restrutura por módulo (multi-module) ou packages (single-module)
        BOOT->>BOOT: gera Checkstyle, lombok.config, logback, Dockerfile, docker-compose
        BOOT->>HOOK: ArchHook.java export — rules (paths a partir de packages.map), skills de dev, agents, hook + jar, extensions.json, settings.json
        HOOK->>RULES: lê todas as rules, escreve em <projeto>/.claude/rules/
        BOOT->>BOOT: ./mvnw clean verify + teste de boundary + teste de lombok.config
        BOOT-->>AG: build PASSED ou FAILED
        AG-->>CMD: relatório no formato fixo (§ Output contract)
        CMD-->>U: relatório, sem reescrever
        CMD->>HOOK: ArchHook.java audit genesis <projeto> <sessão> — Started, Finished e tokens no GENESIS.md
        opt projeto criado
            CMD->>CMD: Skill tool → git-publish (project: <caminho>)
        end
    end
```

## Passos do procedimento (`project-bootstrap`, resumo)

| # | Passo | O que grava |
|---|---|---|
| 1 | Seleciona o blueprint | — |
| 2 | Valida o blueprint | — |
| 3 | Gera a base via Spring Initializr | `pom.xml`, `mvnw`, `Application.java` |
| 4 | Reestrutura por módulo/packages | `*/pom.xml`, `package-info.java` |
| 4.6 | Gera Checkstyle | `config/checkstyle/checkstyle.xml` e `checkstyle-test.xml` |
| 4.7 | Materializa packages + config de features ativas | `package-info.java`, `application-actuator.yml`, `db/migration/` vazio |
| 4.8 | Gera `lombok.config` | `lombok.config` na raiz |
| 4.9 | Gera `logback-spring.xml` | `<módulo-main>/src/main/resources/logback-spring.xml` |
| 4.10 | Gera Dockerfile + docker-compose base | `Dockerfile`, `docker-compose.yml` (só serviço `app`) |
| 5 | Gera o mapa de boundaries | `.claude/forbidden-imports.txt` |
| 6 | Gera `CLAUDE.md` raiz + por módulo | `CLAUDE.md`, `*/CLAUDE.md` |
| 6.5 | Gera CI | `.github/workflows/build.yml` |
| 6.6 | Escreve o `.claude/` do projeto num comando só — `ArchHook.java export <projeto> --blueprint <id>`, dirigido pelo manifesto `export` de `extensions.json` | Toda rule (`paths` de pacote reescritos a partir de `packages.map`); `.claude/skills/{ai-architect,arch-adopt,arch-doctor,audit-usage,docker-architect,domain-modeling,git-publish,graphql-api-architect,cache-architect,gof-design-patterns,jobs-architect,messaging-architect,new-feature,persistence-architect,rest-api-architect,test-architect,use-case-design}/**`; `.claude/agents/{java-spring-boot-developer,archunit-installer,commons-logging-installer}.md`; `ArchHook.java` + `ArchHook.jar`, `extensions.json`, `settings.json` (com `guard`, `audit` e `context` ligados); o blueprint ativo; `.claude/audit-usage/`; `.claude/.arch-provenance.json`; `.mcp.json` + `MCP-SETUP.md` só se um servidor foi desenhado para o projeto gerado |
| 7 | Nada a rodar — o que o enforcement recém-instalado faz, para o relatório final | — |
| 7.5 | Decide a segurança de transporte — encadeia `transport-security-setup`, que pergunta onde o TLS termina e o profile local ou a borda local | linhas de transporte no `application.yml`, o filtro de HSTS quando não há cadeia de segurança, o IT de transporte, o parágrafo `**Transport:` do `CLAUDE.md` raiz; o serviço `edge` ou a porta do `Dockerfile`, via `docker-architect` |
| 8 | Verifica | `./mvnw clean verify`, teste de boundary, teste de `lombok.config`, teste de autonomia |
| 8.4 | Configura o SonarQube — encadeia `sonarqube-setup`, que pergunta se já existe servidor | scanner + properties `sonar.*` no build file raiz; step de CI para servidor externo; serviço `sonarqube` no compose (via `docker-architect`) caso contrário |
| 8.5 | Gera o README do projeto | `README.md` (inglês) + `README.pt-br.md` |
| 8.6 | Grava o registro de gênese da trilha, com Started, Finished e tokens como placeholders que o `/init-project` preenche depois com `audit genesis` | `.claude/audit-usage/GENESIS.md` |

O passo 8 é o único portão de qualidade: se o build falhar, **corrige antes de
reportar** — um bootstrap que entrega build vermelho não está terminado.

## Exemplo de invocação (fictício)

```
/init-project --blueprint hexagonal --groupId com.acme --name pedidos-api --build maven --bounded-context orders
```

Como `--blueprint`, `--groupId`, `--name` e `--bounded-context` já vieram nos argumentos,
o `/init-project` não pergunta de novo sobre eles — só entrevista o que falta, na sessão
principal e antes de delegar: build (já veio, também pula), **features**, TLS e Sonar
(as perguntas de `transport-security-setup` e `sonarqube-setup`), no máximo quatro perguntas
por chamada. O agent não pergunta nada. Features só são perguntadas onde o blueprint as marca
`false`: o `hexagonal` já liga REST, JPA+Flyway, OpenAPI, Testcontainers e Actuator, então a
única pergunta oferece Kafka e SQS. Suponha que o usuário não marca nenhuma.

O **contexto delimitado** (`--bounded-context`) é uma pergunta própria, com o artifactId
como default. É um fato do projeto, não uma resposta por
caso de uso: ele é o primeiro segmento de todo nome de tópico Kafka, e escolhê-lo dentro
de um caso de uso daria a um sistema dois namespaces. Por isso é perguntado uma vez aqui
e escrito no `CLAUDE.md` da raiz do projeto gerado, mesmo quando não há mensageria.

## Relatório de saída (exemplo, formato fixo de `project-bootstrap/SKILL.md`)

```
✅ Project pedidos-api created — blueprint hexagonal

Modules:
  domain                    → depends on []
  application                → depends on [domain]
  adapters/adapter-in-rest       → depends on [application, domain]
  adapters/adapter-out-persistence → depends on [application, domain]
  bootstrap                  → depends on [*]

Versions (resolved by the Initializr): Java 21 · Spring Boot 3.4.1 · maven
Active features: rest, validation, persistence-jpa, uuid-v7, flyway, openapi,
  testcontainers, actuator, observability
Business code: none — by design. 9 package-info.java written
Boundaries: 9 rules in .claude/forbidden-imports.txt — blocking verified ✓
Checkstyle: config/checkstyle/checkstyle.xml — plugin 3.5.0 · tool 10.20.2, validate phase · checkstyle-test.xml over src/test
Spotless: check bound to the build (verify / check) — formatting and unused imports, main and test
Lombok: lombok.config at the root — @Data and @Setter stop compilation
ArchUnit: to be installed — `test-architect` skill, setup mode (delegates to `archunit-installer`, see Next steps)
Coverage: JaCoCo generates a report; the 80%/70% gate comes in with `test-architect`
Self-contained: 19 rules + 20 skills + 3 agents + ArchHook.java + extensions.json written by `ArchHook.java export` — no dead paths ✓
Audit trail: .claude/audit-usage/ active — one report per skill or agent invocation from now on, by `/command` or by the model. GENESIS.md records this run itself, in tokens
Docker: Dockerfile + docker-compose.yml — app, postgres (persistence-jpa), otel-collector (observability) — extend with `docker-architect` for anything a future use case adds
Observability UI: none — the collector exports to `debug`. Run `/docker-architect` to add Jaeger or Grafana + Tempo + Prometheus
Transport: topology A edge — HTTP/2 on, HSTS by Spring Security, ForwardedHeadersIT
MCP: none — no server designed for this project yet
Build: PASSED
Docs: README.md (English, default) + README.pt-br.md — origin, blueprint, stack, skills/agents, this report

Next steps:
  1. /use-case-design <first-use-case-name>
  2. Install the architecture tests (ArchUnit) and wire up the coverage gate with the
     test-architect skill as soon as business classes exist. Until then boundaries
     are guaranteed by the check hook at edit time (inside Claude Code) and by CI's
     doctor gate, which sweeps every source file against .claude/forbidden-imports.txt
     (whoever wrote it), and by the POMs' depends_on (in the build) — only if
     layout: multi-module.
```

Como o build passou, o `/init-project` roda `ArchHook.java audit genesis`, que preenche
Started, Finished e tokens do `GENESIS.md` a partir das transcrições da sessão, e só
depois encadeia `git-publish` — o primeiro commit já leva o registro preenchido. Nada é
commitado ou enviado sem os dois portões de confirmação de `git-publish` — ver
[03-new-feature.md § Nota operacional](03-new-feature.md) e
`@.claude/decisions/0034-git-publish-skill.md`.

> Os números de versão (Java, Spring Boot, Checkstyle, plugin) são **sempre resolvidos
> em runtime** contra o Spring Initializr e o Maven Central — nunca escritos de
> memória (`CLAUDE.md` § Invariant 8). Os valores acima são ilustrativos para este
> exemplo fictício, não uma promessa de versão fixa.

## O que acontece depois

O projeto gerado é **autocontido** (`CLAUDE.md` § Invariant 9): quem clona
`pedidos-api` não tem Nerviz na máquina. Tudo que o `CLAUDE.md` do projeto
cita já foi copiado para dentro dele — rules, skills de desenvolvimento, os três agents
executores, `ArchHook.java` e o seu `ArchHook.jar`, `extensions.json`. As skills de criação
(`project-bootstrap`, `init-project`, `claude-code-architect-designer`), o agent
`project-initializer` e o **catálogo** de blueprints ficam de fora de propósito: só fazem
sentido antes do projeto existir. O blueprint **ativo** viaja — o stamp de proveniência
registra o id dele, e `/arch-adopt` precisa resolvê-lo na próxima atualização.

A partir da primeira sessão aberta dentro do projeto, o hook `audit` grava um relatório
por invocação de skill ou agent em `.claude/audit-usage/` — o `GENESIS.md` do passo 8.6
é o único registro que não vem do hook, e diz isso no cabeçalho. Ver
[08-audit-usage.md](08-audit-usage.md).

A partir daqui, o fluxo natural é `/new-feature` — ver
[03-new-feature.md](03-new-feature.md).
