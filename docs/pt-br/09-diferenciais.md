# Diferenciais — o que isto é, quem são os vizinhos e o que é de fato único

Fonte primária deste repositório: o próprio `.claude/` (`hooks/ArchHook.java`,
`blueprints/*.yaml`, `skills/*/SKILL.md`, `schemas/extensions.json`),
`.github/workflows/validate.yml` e `CLAUDE.md` § Invariants.

O mapeamento foi feito em **2026-10-02**. De cada projeto lemos o README público e a árvore
do repositório; estrelas e data do último push vieram da API do GitHub nesse dia. Produtos
comerciais são descritos pela documentação do fornecedor. O que não conseguimos verificar
está marcado. Nas tabelas, "—" quer dizer *não encontrado no README ou na árvore pública*,
não *comprovadamente ausente*.

## Que categoria é esta

O rótulo que melhor encaixa é **agent harness especializado em uma stack**. Três termos
circulam em 2026, e o repositório fica na interseção deles:

- **Agent harness / harness engineering.** É o enquadramento *agente = modelo + harness*.
  O harness tem guias, que orientam o modelo antes de agir (normas, skills, specs), e
  sensores, que verificam o que ele fez (hooks, testes, o build). Aqui, `rules/`,
  `skills/`, `blueprints/` e as specs de caso de uso são os guias. `ArchHook`, ArchUnit, o
  build incremental e o CI são os sensores.
- **Spec-driven development (SDD).** O `/new-feature` é *spec-first*: um caso de uso por
  execução, consolidado numa spec, aprovado e congelado antes de qualquer código — e o
  código sai de uma segunda execução, `/new-feature-implement UC-NNN-slug`, numa sessão limpa.
- **Gerador de projetos.** O `/init-project` gera via Spring Initializr, na linha do
  JHipster e do Seed4J. Aqui, porém, a geração é o primeiro passo, não o produto.

O que **não** é: um assistente de código (o assistente é o Claude Code; este repositório não
traz modelo); um framework de agentes como LangGraph, CrewAI ou Spring AI (esses constroem
agentes, este restringe um); um template estático (nada é clonado, nenhuma versão é fixada).

## Os vizinhos

### 1 · Spring e Java sobre agentes de código

| Projeto | ★ (2026-10-02) | O que entrega | Mais perto de nós em | O que não faz (até onde achamos) |
|---|---|---|---|---|
| [loiane/specs-driven-development-spring-angular](https://github.com/loiane/specs-driven-development-spring-angular) | 61 | Toolkit SDD para Spring Boot + Angular, em Claude Code, Copilot e Windsurf. Fluxo `/spec → /plan → /build → /test → /validate → /review → /ship`, agents por papel, harness Maven de qualidade (ArchUnit, PIT, JaCoCo, SpotBugs, OWASP), agent de onboarding para projeto existente | **O mais próximo no geral.** Pipeline de spec, mais hooks bash que impõem escopo: `enforce-files-in-scope.sh` bloqueia edição fora dos arquivos da tarefa ativa, e `block-impl-without-failing-test.sh` só libera implementação com um teste falhando | Não gera projeto nem oferece arquitetura selecionável. O escopo vem da tarefa ativa, não de uma classe por skill. Hooks exigem bash e `jq`. Sem design por camada para segurança, clientes HTTP de saída, mensageria ou jobs. Mecanismo de atualização não encontrado |
| [jabrena/plinth](https://github.com/jabrena/plinth) | 442 | "AI-native Java enterprise SDLC": muitas skills, agents e comandos, workflow baseado em OpenSpec, integração com Jira/GitHub/Azure DevOps. Spring Boot, Quarkus, Micronaut; Cursor, Claude Code, Codex, Copilot | Pipeline de spec em escala; amplitude de conhecimento Java | — geração por blueprint; — hooks de território de escrita |
| [a-pavithraa/springboot-skills-marketplace](https://github.com/a-pavithraa/springboot-skills-marketplace) | 78 | Plugin para Claude Code / Codex. A skill `creating-springboot-projects` entrevista, usa o Spring Initializr e gera uma de várias arquiteturas progressivas (Layered, Modular Monolith, Tomato, DDD+Hexagonal) | **O mais próximo na geração.** Baseado no Initializr, com escolha de arquitetura | Arquiteturas são prosa dentro de uma skill, não dado que um hook lê. — pipeline de spec, — hooks, — export para o projeto |
| [jdubois/dr-jskill](https://github.com/jdubois/dr-jskill) | 341 | Agent Skill do criador do JHipster que gera aplicações Spring Boot a partir do start.spring.io, com banco, Docker e front-end | Geração via Initializr, defaults opinativos | Uma opinião só, sem escolha de arquitetura. — pipeline de spec, — enforcement |
| [piomin/claude-ai-spring-boot](https://github.com/piomin/claude-ai-spring-boot) | 1.303 | Template para clonar: `CLAUDE.md`, agents, skills | O mais popular do nicho | Template estático, versões fixadas. — hooks, — pipeline. Último push em 2026-04-29 |
| [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills) | 292 | Skills de convenção Spring Boot | Conhecimento de Spring | Não gera nada, não verifica nada |
| [giuseppe-trisciuoglio/developer-kit](https://github.com/giuseppe-trisciuoglio/developer-kit) | 351 | Marketplace de plugins multilinguagem, com um plugin Java grande (agents, comandos, skills) | Amplitude | Biblioteca, não gerador nem enforcer |
| [ryu-qqq/claude-spring-standards](https://github.com/ryu-qqq/claude-spring-standards) · [altmemy/claude-code-templates](https://github.com/altmemy/claude-code-templates) | 0 · 25 | Template hexagonal fixo com skills e hooks · agents por papel com hooks genéricos | Template + hooks | Uma arquitetura fixa; os hooks formatam ou bloqueiam comando perigoso, mas não leem a arquitetura |

### 2 · Geradores Java clássicos, agora com front-end de IA

| Projeto | ★ | O que é | Diferença |
|---|---|---|---|
| [JHipster](https://github.com/jhipster/generator-jhipster) + [jhipster-mcp](https://github.com/jhipster/jhipster-mcp) | 22k | Gerador determinístico centrado em entidades. O servidor MCP deixa um agente escrever JDL e acionar o CLI | A IA aciona um gerador. Nada governa o código que o agente escreve depois |
| [Seed4J](https://github.com/seed4j/seed4j) (sucessor do JHipster Lite) | 618 | Gerador hexagonal modular que, de propósito, não gera código de negócio; um servidor MCP da comunidade o expõe | O mais próximo em espírito em "estrutura, não negócio". Sem pipeline de agente nem enforcement na escrita |
| [Bootify](https://bootify.io) | comercial | Gerador web vendido como alternativa "AI-first" ao JHipster, com servidor MCP | Não verificado além da página de marketing |

### 3 · Frameworks SDD e harnesses agnósticos de linguagem

| Projeto | ★ | O que é | Diferença |
|---|---|---|---|
| [GitHub Spec Kit](https://github.com/github/spec-kit) | 140k | CLI `specify`: constitution → specify → plan → tasks → implement, para 30+ agentes, com caminho de upgrade | O toolkit SDD de referência. Gates são checklists de prompt; agnóstico de stack |
| [OpenSpec](https://github.com/Fission-AI/OpenSpec) | 71k | SDD leve com propostas de mudança e deltas de spec, para código existente; `openspec update` | Evita de propósito gates rígidos de fase — o oposto de uma spec congelada |
| [BMAD-METHOD](https://github.com/bmad-code-org/BMAD-METHOD) | 54k | Personas ágeis (analista, PM, arquiteto, dev) do PRD às stories | Cobre descoberta de produto; o enforcement é por prompt |
| [obra/superpowers](https://github.com/obra/superpowers) | 294k | Framework de skills e metodologia (brainstorm → plan → TDD) em vários harnesses | O maior do espaço; metodologia, não governança de stack |
| [Spec Kitty](https://github.com/spec-kitty/spec-kitty) | 1,7k | SDD com um "Charter" de governança, lanes de work packages, gates de review/accept, `spec-kitty upgrade` | **O mais próximo em governança** do workflow; sem especialização de stack |
| [Pilot Shell](https://github.com/maxritter/pilot-shell) | 2,1k | "Context and harness engineering" para Claude Code e Codex: hooks, quality gates, atualizações | **O mais próximo em enforcement**; sem especialização de stack |
| [rails_ai_agents](https://github.com/ThibautBaissac/rails_ai_agents) | 665 | Skills, agents, regras e hooks específicos de Rails, mais um kit SDD | A mesma ideia em outra stack |
| [AWS Kiro](https://kiro.dev) | comercial | IDE com specs (requirements → design → tasks), arquivos de steering e hooks por evento | O produto comercial mais próximo. IDE proprietária, sem blueprints de stack. Não verificamos se os hooks dele bloqueiam uma escrita antes de ela acontecer |
| Factory Spec Mode | comercial | A fase de planejamento é só leitura até a aprovação, garantido pelo runtime | Guarda de fase parecida, dentro de um produto fechado |

Mais duas coisas dão contexto. O [AGENTS.md](https://agents.md) é um formato de arquivo de
contexto suportado pela maioria dos agentes de código e mantido pela Linux Foundation; é um
padrão, não um concorrente. E as estrelas nesse espaço vão para o simples: templates e
pacotes de skills chegam a centenas ou milhares, enquanto toolkits com enforcement pesado
continuam pequenos.

## O que é de fato único aqui

Nenhum projeto encontrado reúne tudo o que segue. Cada item cita o vizinho que chega mais
perto, para a afirmação poder ser conferida.

### 1 · Arquitetura é dado, e uma declaração tem três efeitos

Nove blueprints (`hexagonal`, `hexagonal-single-module`, `clean-architecture-multi-module`,
`clean-architecture-single-module`, `layered`, `onion`, `onion-single-module`, `vertical-slice`,
`modular-monolith`) mais o `custom-template`. Cada um é um YAML com módulos, `depends_on`,
`forbidden_imports`, `packages.map` e `trade_offs` obrigatórios. O `forbidden_imports` de
um módulo alimenta, sem cópia manual:

1. o `CLAUDE.md` do módulo, para o modelo **saber**;
2. `.claude/forbidden-imports.txt`, para o hook `check` **bloquear** a escrita (`exit 2`);
3. o `depends_on` dos POMs, para o **compilador** recusar o import nos layouts multi-module.

Os globs das normas (`paths`) são reescritos a partir do `packages.map` na geração, então
cada norma carrega para os pacotes que o blueprint de fato tem. Adicionar uma arquitetura
não toca skill, agent nem prompt, e o job de CI `new blueprint doesn't touch prompts`
confere isso a cada push.

*Mais perto:* a-pavithraa oferece escolha de arquitetura, mas como prosa dentro de uma
skill. Seed4J gera estrutura hexagonal de forma determinística, mas nada lê a arquitetura
depois que um agente começa a escrever.

### 2 · Geração pelo Initializr, depois um projeto autocontido

O `/init-project` chama o `start.spring.io` em tempo de execução, reestrutura o resultado
conforme o blueprint e exporta para o projeto tudo o que o projeto vai citar. Versões nunca
vêm da memória (invariante 8), e o CI falha se alguma for escrita como fato. Nenhum código
de negócio é gerado. O projeto não precisa deste repositório depois (invariante 9).

*Mais perto:* a-pavithraa e dr-jskill também usam o Initializr. Nenhum dos dois exporta um
harness que continua governando o código depois.

### 3 · Território de escrita como dado, imposto por um hook

Cada skill está em exatamente uma de sete classes (`design`, `orchestrator`, `build`,
`observer`, `meta`, `ops`, `report`). Cada agent está em uma de quatro (`driver`,
`executor`, `installer`, `verifier`). A classe, em `schemas/extensions.json`, declara os
caminhos que ela pode escrever, e o `guard` recusa todo o resto, negando por padrão:

- via `Write`/`Edit`;
- via as formas de escrita em shell que ele consegue ler (`>`, `tee`, `sed -i`,
  `sh -c "…"`);
- e de novo no `Stop`, contra o que o git vê de alterado no turno.

Um subagent é julgado pela própria classe, seja qual for a fase que o chamador deixou
aberta. Uma skill da classe `build` não pode nem ser chamada durante uma execução de
design. Cada uma dessas regras entrou depois que uma execução real a violou. A primeira: uma
execução de design escreveu um serviço no `docker-compose.yml`, arquivo que nenhuma denylist
tinha listado.

*Mais perto:* o `enforce-files-in-scope.sh` da loiane impõe escopo por tarefa ativa, uma
ideia genuinamente parecida. As diferenças: aqui o território é por classe e está em dado,
escritas via shell e a varredura de fim de turno são cobertas, e a implementação é um único
arquivo Java testado em três sistemas operacionais, em vez de bash com `jq`.

### 4 · Pipeline de spec com um dono por camada, e spec aprovada congelada

O `/new-feature` desenha um caso de uso por execução: `use-case-design` →
`domain-modeling` → `rest-api-architect` → `graphql-api-architect` (condicional) → `security-architect` (condicional) →
`http-client-architect` (condicional) → `ai-architect` (condicional) → `search-architect` (condicional) →
`messaging-architect` (condicional) →
`jobs-architect` (condicional) → `persistence-architect` → `cache-architect` (condicional) →
`test-architect`. Cada skill é
dona de um parcial e decide os
design patterns da sua camada. Os parciais são consolidados em `UC-NNN-spec.md`, cujo status
vai de `draft → approved → implemented` (ou `implemented-blocked`). Depois que a spec é
aprovada, o `guard` congela a pasta, exceto a linha de status, a marcação de checklists e o
`CHANGELOG.md`. Só então o executor `java-spring-boot-developer` escreve em `src/`.

*Mais perto:* loiane e plinth têm pipelines SDD cientes de Spring, e Spec Kit e OpenSpec são
as referências genéricas. Nenhum tem etapas de design dedicadas a Spring Security, clientes
HTTP de saída, Kafka e jobs agendados. Nenhum congela a spec aprovada com um hook.

### 5 · Atualização do harness dentro do projeto, com proveniência e migrações

O `/arch-adopt` instala este `.claude/` num projeto que nunca foi gerado aqui, ou atualiza
um que está atrasado. Recusa worktree sujo e escreve pelo modo `export`, a partir de um
manifesto que é dado. Grava `.claude/.arch-provenance.json` com o hash de cada arquivo
escrito, para o `/arch-doctor` apontar os arquivos editados à mão **antes** que a próxima
atualização os sobrescreva. Numa atualização, imprime as notas de `migrations` das mudanças
de convenção que o projeto ainda não viu; nunca as executa.

*Mais perto:* Spec Kit, OpenSpec, BMAD e Spec Kitty têm comando de upgrade. Não achamos
nenhum que detecte edição local por hash antes de sobrescrever, nem que traga notas para o
código que uma convenção antiga deixou para trás.

### 6 · Trilha de execução determinística, por execução

Num projeto gerado, o hook `audit` escreve um relatório Markdown por invocação de skill ou
agent. Ele traz tokens billable e o modelo por peça (sem contagem dupla), árvore de encadeamento com
durações, arquivos tocados, permissões pedidas, ferramentas que falharam e o `HEAD` antes e
depois. Os prompts são redigidos. Dois ledgers alimentam o `/audit-usage`. O relatório custa
zero token para ser produzido e sobrevive à sessão morrer.

*Mais perto:* nenhum encontrado entre os vizinhos acima.

### 7 · O harness valida a si mesmo

As onze invariantes do `CLAUDE.md` são verificadas no CI, não só escritas:

- `rules/` é folha, e cada norma tem um dono.
- Nenhum código numa norma, e nada de `commands/`.
- Nenhuma versão fixada, e nenhuma dependência fora do ecossistema Java.
- O manifesto de export bate com o disco.
- `ArchHook.java schema` valida o frontmatter contra os campos que o runtime reconhece (o
  runtime ignora campo desconhecido sem dizer nada), as seções de corpo que cada classe
  exige e o `.mcp.json`, com varredura de segredos.
- Todo `import` de todo exemplar `.java.example` resolve contra um classpath de uma
  requisição real ao `start.spring.io`.

*Mais perto:* nenhum encontrado. Os vizinhos validam o código gerado, não os próprios
arquivos de instrução.

### 8 · Enforcement em um único arquivo Java, um formato de comando

O `ArchHook.java` tem onze modos: `check`, `format`, `tests`, `schema`, `guard`, `audit`,
`compose`, `context`, `doctor`, `export`, `build`. Os hooks rodam o `ArchHook.jar`
pré-compilado e versionado, como uma string de shell por hook, que o Claude Code e o GitHub
Copilot CLI rodam: sem script de shell, sem `chmod`, igual em Linux, macOS e Windows. O CI exige que o jar seja, byte a byte, o que o fonte compila sob o JDK fixado. Os
testes em `.claude/.ci/` injetam cada violação e exigem o bloqueio, nos três sistemas
operacionais. A tabela de modos está no [README](../../README.md#enforcement-one-java-file-one-command-shape).

*Mais perto:* todo vizinho com hooks que encontramos usa scripts bash.

### 9 · Peças menores que somam

Nenhuma é única sozinha. Juntas, nenhum vizinho as reúne:

| Preocupação | Onde vive |
|---|---|
| `Idempotency-Key` desde o primeiro endpoint, via AOP e tabela compartilhada | `rest-api-architect` + `persistence-architect` |
| Log estruturado com mascaramento de dado sensível | `commons-logging-installer`, oferecido no pre-flight do `/new-feature-implement` |
| Collector OTLP com traces e métricas; Jaeger ou Grafana + Tempo + Prometheus | `docker-architect` |
| Diagnóstico de compose: container "de pé" que não responde, porta presa por projeto vizinho, default que o host não alcança | `ArchHook.java compose` |
| Transporte decidido uma vez por projeto, antes de qualquer endpoint: borda, direto ou mutual TLS, HTTP/2 ligado, HSTS com um único escritor | `transport-security-setup` |
| SonarQube ou SonarCloud configurado; issues do Sonar rastreadas até o template do `.claude/` que as causou | `sonarqube-setup`, `sonar-lessons` |
| Issues abertas a partir de um projeto só com evidência e sem nada do código do projeto; cada afirmação conferida no `HEAD` antes de desenhar uma correção | `report-issue`, `triage-issue` + `issue-verifier` |
| Uma meta-ferramenta que decide a forma da próxima extensão (skill, agent, regra, hook, permissão, servidor MCP, ou nada) e registra a decisão | `claude-code-architect-designer` + `decisions/` |
| Segredos: `permissions.deny` em `*.env`, `*.pem`, `application-prod.yml`; varredura de segredos no `.mcp.json` | `settings.json` + `ArchHook.java schema` |

## Tabela comparativa

Representantes de cada família, como encontrados em 2026-10-02. ✅ sim · ◐ parcial · — não
encontrado.

| Capacidade | Este repo | loiane SDD | plinth | a-pavithraa | Spec Kit / OpenSpec | Spec Kitty / Pilot Shell |
|---|---|---|---|---|---|---|
| Gera o projeto via Initializr, versões ao vivo | ✅ | — (template) | — | ✅ | — | — |
| Várias arquiteturas para escolher | ✅ 7 + custom, como dado | — | — | ◐ como prosa | — | — |
| Fronteira de arquitetura bloqueada na escrita | ✅ hook + compilador + ArchUnit | ◐ ArchUnit no build | — | — | — | — |
| Pipeline de spec em várias fases | ✅ por camada | ✅ | ✅ | — | ✅ | ✅ |
| Escopo de escrita imposto por hook | ✅ por classe, como dado | ✅ por tarefa | — | — | — (gates de prompt) | ◐ gates de workflow e qualidade |
| Spec aprovada congelada por hook | ✅ | — | — | — | — | ◐ gates de accept |
| Hooks sem script de shell, testados em 3 SOs | ✅ Java | — bash + `jq` | — | — | — | — |
| Trilha de tokens e encadeamento por execução | ✅ | — | — | — | — | — |
| Valida os próprios arquivos de instrução | ✅ | — | — | — | — | — |
| Atualização em projeto existente | ✅ com proveniência + notas de migração | ◐ onboarding | — | ◐ reinstalar plugin | ✅ upgrade/update | ✅ upgrade |
| Roda em agentes além do Claude Code | — | ✅ | ✅ | ◐ Codex | ✅ | ◐ |
| Amplitude além de Spring Boot servlet | — | ◐ + Angular | ✅ Quarkus, Micronaut | — | ✅ qualquer | ✅ qualquer |

## O que não é diferencial — dito com honestidade

- **Spec-driven development em si.** É a categoria dominante de 2026, com o Spec Kit em 140k
  estrelas. O específico aqui é o design por camada para Spring e a spec congelada, não a
  ideia de escrever a spec primeiro.
- **Hooks que impõem escopo, como ideia.** O toolkit da loiane e o Pilot Shell também têm. O
  específico aqui é território como dado de classe, cobertura de shell, a varredura e o
  runtime multiplataforma.
- **Geração via Initializr.** a-pavithraa e dr-jskill também fazem.
- **Amplitude de conhecimento Java.** plinth e developer-kit cobrem mais frameworks e mais
  temas. Este repositório vai fundo numa stack só: Spring Boot no stack servlet, Maven ou
  Gradle, sem WebFlux, sem front-end.
- **Portabilidade.** Hooks, `paths`, `disable-model-invocation` e `context: fork` são do
  runtime do Claude Code. Nada aqui roda em Codex, Cursor ou Copilot, enquanto a maioria dos
  vizinhos roda em vários agentes.
- **Custo.** O pipeline completo é caro por desenho. Numa execução real do `/new-feature`
  para um agregado de dois campos, só o executor levou 203 turnos e releu 58,8M tokens de
  cache. A trilha de auditoria existe para
  medir isso; ver [03-new-feature.md § Disciplina de custo](03-new-feature.md).
- **Adoção.** O rigor tem curva de aprendizado, e o projeto é novo e pequeno. Templates e
  pacotes de skills mais simples são muito mais populares.
- **Sem afiliação com a Anthropic.** "Claude" no nome segue a prática do ecossistema.

## Fontes

Todos os repositórios foram conferidos em 2026-10-02: README, árvore, estrelas e último push.

- Spring e Java sobre agentes: [loiane/specs-driven-development-spring-angular](https://github.com/loiane/specs-driven-development-spring-angular) ·
  [jabrena/plinth](https://github.com/jabrena/plinth) ·
  [a-pavithraa/springboot-skills-marketplace](https://github.com/a-pavithraa/springboot-skills-marketplace) ·
  [jdubois/dr-jskill](https://github.com/jdubois/dr-jskill) ·
  [piomin/claude-ai-spring-boot](https://github.com/piomin/claude-ai-spring-boot) ·
  [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills) ·
  [giuseppe-trisciuoglio/developer-kit](https://github.com/giuseppe-trisciuoglio/developer-kit) ·
  [ryu-qqq/claude-spring-standards](https://github.com/ryu-qqq/claude-spring-standards) ·
  [altmemy/claude-code-templates](https://github.com/altmemy/claude-code-templates)
- Geradores: [jhipster/generator-jhipster](https://github.com/jhipster/generator-jhipster) ·
  [jhipster/jhipster-mcp](https://github.com/jhipster/jhipster-mcp) ·
  [seed4j/seed4j](https://github.com/seed4j/seed4j) · [bootify.io](https://bootify.io)
- SDD e harnesses: [github/spec-kit](https://github.com/github/spec-kit) ·
  [Fission-AI/OpenSpec](https://github.com/Fission-AI/OpenSpec) ·
  [bmad-code-org/BMAD-METHOD](https://github.com/bmad-code-org/BMAD-METHOD) ·
  [obra/superpowers](https://github.com/obra/superpowers) ·
  [spec-kitty/spec-kitty](https://github.com/spec-kitty/spec-kitty) ·
  [maxritter/pilot-shell](https://github.com/maxritter/pilot-shell) ·
  [ThibautBaissac/rails_ai_agents](https://github.com/ThibautBaissac/rails_ai_agents) ·
  [kiro.dev](https://kiro.dev) · [agents.md](https://agents.md)
- Nome da categoria: artigo de Birgitta Böckeler sobre ferramentas de spec-driven
  development, na série "Exploring Gen AI" do [martinfowler.com](https://martinfowler.com/articles/exploring-gen-ai/sdd-3-tools.html).
