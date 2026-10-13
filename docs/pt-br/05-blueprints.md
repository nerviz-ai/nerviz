# Blueprints de arquitetura

Fonte primária: `.claude/blueprints/_schema.md`.

## O que é um blueprint

Um blueprint é **dado**, não código nem instrução de procedimento — um YAML que
descreve uma arquitetura de projeto Spring Boot por completo: módulos, dependências
entre eles, packages, features habilitadas, e os globs que a rule
`architecture-ddd.md` vai usar para se auto-carregar dentro do projeto gerado. É a
folha "laranja" do diagrama em [00-visao-geral.md](00-visao-geral.md) — como uma rule,
nunca chama ninguém; diferente de uma rule, é lido programaticamente por
`project-bootstrap`, não carregado como instrução em prosa.

Cada arquitetura mora em `.claude/blueprints/<id>/<id>.yaml`. Uma pasta sem `.yaml`
seria uma arquitetura **ainda não escrita**, e a skill a ignoraria na listagem da
entrevista — hoje todas as sete têm YAML. Os prós, contras e "quando escolher" de cada
uma estão em `.claude/blueprints/README.md` (e `README.pt-br.md`).

| Blueprint | Layout | Resumo |
|---|---|---|
| `hexagonal` | multi-module | Ports como interfaces, adapters de entrada e saída em módulos próprios |
| `clean-architecture-single-module` | single-module | domain/application/infrastructure como packages; boundary só por ArchUnit |
| `clean-architecture-multi-module` | multi-module | Mesmas camadas como módulos Maven; o compilador impõe a direção |
| `layered` | single-module | controller/service/repository, vocabulário clássico de tutorial Spring Boot em vez do port/adapter do hexagonal |
| `modular-monolith` | single-module | Vários bounded contexts (Spring Modulith), cada um com camadas domain/application/adapter completas dentro do próprio `internal`; isolamento entre módulos verificado por `ApplicationModules.verify()`, não por ArchUnit |
| `onion` | multi-module | Application Core (Palermo): domain model + domain services num único módulo `domain`, `persistence`/`presentation` como anéis externos independentes entre si |
| `vertical-slice` | single-module | Um package autocontido por caso de uso (`features.<feature>.<usecase>`, padrão REPR); só o agregado atravessa a fronteira, num kernel `domain` compartilhado |
| `custom-template` | — | Ponto de partida para criar o seu, não um blueprint usável diretamente |

## Quem lê o YAML e quando

`project-bootstrap`, passo 2 (`.claude/skills/project-bootstrap/SKILL.md` § 2 ·
Validate the blueprint), lê o arquivo e valida campo a campo antes de qualquer geração.
Sem defaults implícitos: se um campo obrigatório falta, a geração para ali — nunca
segue com um valor inventado.

## Campos do contrato

| Campo | Obrigatório | Tipo | Descrição |
|---|---|---|---|
| `id` | ✅ | string | kebab-case, igual ao nome do arquivo |
| `name` | ✅ | string | Nome legível, mostrado na entrevista |
| `description` | ✅ | string | Uma frase |
| `when_to_choose` | ✅ | string[] | Critérios objetivos de seleção |
| `trade_offs` | ✅ | string[] | Custos honestos — um blueprint sem trade-offs está mal escrito |
| `build.layout` | ✅ | `multi-module` \| `single-module` | |
| `build.tool` | ✅ | `maven` \| `gradle` | Sobrescrevível na inicialização |
| `modules[]` | ✅ | lista | Ver § `modules[]` abaixo |
| `packages.base` | ✅ | string | Template, ex.: `{{groupId}}.{{artifactName}}` |
| `packages.map` | ✅ | mapa | Papel lógico → sufixo de package. Um papel terminado em `.scheduling` é onde `jobs-architect` põe todo trigger de job — sem ele, a skill para e nomeia a linha que falta. Um papel terminado em `.cache` é onde `cache-architect` põe todo decorator de port e a configuração do cache, com a mesma parada. Um papel terminado em `.ai` é onde `ai-architect` põe todo adapter sobre um modelo ou um vector store — o único lugar onde o Spring AI pode aparecer — com a mesma parada. Um papel terminado em `.graphql` é onde `graphql-api-architect` põe todo resolver, seus records e a configuração do GraphQL — o único lugar onde o Spring for GraphQL pode aparecer — com a mesma parada. Um papel terminado em `.search` é onde `search-architect` põe o documento de busca, o bootstrap do índice, o adapter de consulta e o rebuild — o único lugar onde o Spring Data Elasticsearch ou OpenSearch pode aparecer — com a mesma parada |
| `architecture_paths` | ✅ | string[] | Globs que identificam o código coberto por `rules/architecture-ddd.md`. Vão **verbatim** para o `paths` do arquivo que o bootstrap gera em `<projeto>/.claude/rules/architecture-ddd.md` — não são inferidos de `modules[].path` |
| `dependency_rules.direction` | ✅ | `inward` \| `explicit` | |
| `dependency_rules.forbidden[]` | ✅ | lista | `{from, to[], reason}` |
| `templates` | ✅ | mapa | Papel → path do template |
| `features` | ✅ | mapa de bool | Quais dependências e módulos o projeto leva — **não controla geração de código**, ver seção abaixo |

### `modules[]`

| Campo | Obrigatório | Descrição |
|---|---|---|
| `id` | ✅ | Identificador único |
| `path` | ✅ | Path relativo à raiz |
| `depends_on` | ✅ | Lista de `id`s, `[]`, ou `["*"]` (para o módulo bootstrap) |
| `forbidden_imports` | ➖ | Prefixos proibidos. Se presente, gera um `AGENTS.md` local, mais o `CLAUDE.md` de uma linha que o importa |
| `feature` | ➖ | O módulo só é gerado se a feature estiver ativa |
| `contains_main` | ➖ | Exatamente um módulo com `true` |

## O que `features` decide — e o que não decide

Uma feature ativa é **uma dependência e a configuração dela**. Quatro efeitos, todos
dentro de `project-bootstrap`:

| Onde | Efeito |
|---|---|
| Passo 3 | Entra no `-d dependencies=` do Spring Initializr |
| Passo 4 | Em `multi-module`, decide para qual POM de módulo a dependência vai |
| `modules[].feature` | O módulo só é gerado se a feature estiver ativa |
| Passo 4.7 | Configuração: `application-actuator.yml`, o diretório `db/migration` |

**Não decide código.** Uma feature ativa não gera entidade, controller, use case ou
migration — o bootstrap não escreve código de negócio. Essas classes vêm depois, da
feature real, via as skills donas de cada forma. `archunit` é o caso extremo: nem é
dependência do bootstrap, é dado que a skill `test-architect` lê depois — no modo
setup, repassado ao agent `archunit-installer`, que é quem de fato traduz `packages.map`
para as regras ArchUnit.

Corolário para quem escreve um blueprint: adicionar uma feature significa adicionar
uma linha em `project-bootstrap`'s `dependency-catalog.md`, não uma amostra de código.

## Regras de validação (as 6 que `project-bootstrap` roda no passo 2)

1. O grafo de `depends_on` é acíclico.
2. Exatamente **um** módulo tem `contains_main: true`.
3. Toda `feature` referenciada por um módulo existe em `features`.
4. Todo `templates.<papel>` aponta para um arquivo existente.
5. Em `layout: single-module`, `modules` tem um único elemento, e
   `forbidden_imports` é aplicado por package, não por módulo.
6. `architecture_paths` existe e não está vazio.

**Não há validador externo, nada a instalar** — esse template assume só o
JDK, git e curl — o Maven wrapper vem no `starter.tgz` do Initializr. O árbitro final é
o build: um blueprint com boundaries mal declaradas produz POMs que não compilam. Falha
mais tarde do que um validador faria, mas falha de forma limpa e sem instalar nada.

## Exemplo real — `hexagonal.yaml` (resumo)

```yaml
id: hexagonal
name: Hexagonal (Ports & Adapters)
build:
  layout: multi-module
  tool: maven
architecture_paths:
  - "domain/**/*.java"
  - "application/**/*.java"
  - "adapters/**/*.java"
  - "bootstrap/**/*.java"
modules:
  - id: domain
    path: domain
    depends_on: []
    forbidden_imports: ["org.springframework..", "jakarta.persistence..", ...]
  - id: application
    path: application
    depends_on: [domain]
  - id: adapter-in-rest
    path: adapters/adapter-in-rest
    depends_on: [application, domain]
    feature: rest
  - id: bootstrap
    path: bootstrap
    depends_on: ["*"]
    contains_main: true
dependency_rules:
  direction: inward
  forbidden:
    - from: domain
      to: [application, "adapters/*", bootstrap]
      reason: "The domain does not know who uses it"
features:
  rest: true
  persistence-jpa: true
  uuid-v7: true
  flyway: true
  testcontainers: true
  actuator: true
  observability: true
  archunit: true
```

Cada regra em `dependency_rules.forbidden` vira, no projeto gerado, uma linha de
`.claude/forbidden-imports.txt` — é isso que dá dentes ao `ArchHook.java check`
(ver [01-tipos-de-arquivo.md § Hook](01-tipos-de-arquivo.md#hook)).

## Como criar um blueprint customizado

```bash
mkdir -p .claude/blueprints/my-style
cp .claude/blueprints/custom-template/custom.template.yaml .claude/blueprints/my-style/my-style.yaml
$EDITOR .claude/blueprints/my-style/my-style.yaml
```

`custom-template/custom.template.yaml` já vem com todos os campos obrigatórios
preenchidos com placeholders comentados — dois módulos (`core`, `app`), uma regra de
`forbidden`, e o bloco `features` completo com os valores mais comuns como ponto de
partida:

```yaml
id: custom
name: "My architecture"
build:
  layout: multi-module
  tool: maven
architecture_paths:
  - "core/**/*.java"
  - "app/**/*.java"
modules:
  - id: core
    path: core
    depends_on: []
    forbidden_imports: ["org.springframework.."]
  - id: app
    path: app
    depends_on: [core]
    contains_main: true
dependency_rules:
  direction: inward
  forbidden:
    - from: core
      to: [app]
      reason: "The core does not know who uses it"
features:
  rest: true
  validation: true
  persistence-jpa: false
  testcontainers: true
  actuator: true
  # ...
```

A validação é a checklist de 6 regras acima, rodada pelo agent no passo 2 de
`project-bootstrap` — a mesma validação que qualquer blueprint nativo passa. Não há
etapa separada de "publicar" um blueprint: assim que o `.yaml` existe e valida, ele
aparece na listagem dinâmica que `init-project` mostra na entrevista
(`` !`find .claude/blueprints -mindepth 2 -maxdepth 2 -name '*.yaml' ...` ``).

**Nenhuma skill, agent ou comando precisa mudar para adicionar uma arquitetura.** Se
você perceber que precisa mudar um deles, o design está quebrado — conserte o design,
não o blueprint (`_schema.md`, última linha).

## Onde colocar material de apoio

- Específico de **uma** arquitetura (artigos, notas) → `.claude/blueprints/<id>/references/`
- Transversal a **várias** arquiteturas → `.claude/blueprints/references/`
