# Tipos de arquivo — mecânica de runtime

Este documento explica, para cada tipo de peça usada em `.claude/`, o que ela é, para
que serve, quando entra em contexto, e quem a invoca — o modelo ou o usuário. Toda
afirmação sobre comportamento do runtime cita a seção correspondente de
`claude-help.md` (raiz deste repositório), que é a fonte única sobre como o Claude Code
funciona neste projeto.

---

## Skill

**Onde mora:** `.claude/skills/<nome>/SKILL.md`, mais `templates/` e `references/`
opcionais dentro da mesma pasta.

**O que é:** um arquivo markdown com instruções que vira parte do repertório do
Claude — "a mais flexível das extensões" (`claude-help.md` § 5). O nome da pasta vira o
comando: `.claude/skills/arch-doctor/SKILL.md` cria `/arch-doctor`.

**Propósito:** empacotar um procedimento — uma checklist ou um fluxo que, de outra
forma, seria colado repetidamente no chat. Neste repositório, cada skill de design
(`use-case-design`, `domain-modeling`, `persistence-architect`, `rest-api-architect`,
`graphql-api-architect`, `security-architect`, `http-client-architect`, `messaging-architect`, `jobs-architect`,
`cache-architect`, `ai-architect`, `search-architect`, `test-architect`) é dona de uma única camada da spec de uma feature; `project-bootstrap`
é dona do procedimento de 8 passos que gera um projeto do zero.

**Quando entra em contexto:** só quando invocada. Ao contrário de um `CLAUDE.md`, o
corpo de uma skill não carrega no início da sessão — carrega no turno em que é usada, e
o conteúdo renderizado entra como mensagem e **permanece nos turnos seguintes**; o
Claude não relê o arquivo depois (`claude-help.md` § Content lifecycle). É por isso que
uma skill deve escrever instrução permanente ("sempre faça X ao longo desta tarefa"), e
não um passo único.

**Quem invoca — modelo ou usuário:**

| Frontmatter | Efeito | Exemplo neste repo |
|---|---|---|
| (nenhum campo de controle) | O modelo decide, a partir da `description`, se a skill é relevante | `project-bootstrap` — description lista gatilhos como "scaffolding", "new Spring project"; `git-publish` — auto-invocável, mas o efeito colateral (commit/push) fica atrás de dois portões `AskUserQuestion` no corpo, não da frontmatter — mesmo padrão de D17 |
| `disable-model-invocation: true` | Só o usuário invoca, digitando `/nome` | `init-project`, `new-feature`, `new-feature-implement`, `arch-doctor` — os comandos documentados aqui são side-effect-heavy e **não** disparam sozinhos |
| `user-invocable: false` | Só o modelo invoca — conhecimento de fundo, sem comando visível | Não usado neste repositório hoje |

Os três comandos centrais deste documento são todos `disable-model-invocation: true` —
decisão deliberada: gerar um projeto ou rodar a pipeline de feature tem efeito
colateral grande demais para disparar por inferência da conversa.

**Campos de frontmatter reconhecidos** (fonte única: `.claude/schemas/extensions.json`,
validado por `ArchHook.java schema` — `CLAUDE.md` § Invariant 10):

```
name, description, when_to_use, argument-hint, arguments,
disable-model-invocation, user-invocable, allowed-tools, disallowed-tools,
model, effort, paths, context, agent, background, hooks, shell
```

Um campo fora dessa lista (por exemplo `metadata:`) não gera erro — o runtime **ignora
silenciosamente** campos desconhecidos, e `claude plugin validate` deixa passar também
([11-pitfalls.md](11-pitfalls.md)). É `ArchHook.java schema` quem bloqueia.

**Injeção dinâmica de contexto:** a sintaxe `` !`comando` `` roda um comando **antes**
do conteúdo chegar ao modelo e substitui pelo resultado — é o que faz `init-project`
listar blueprints disponíveis dinamicamente (`` !`find .claude/blueprints ...` ``) e
`arch-doctor` embutir a saída real de `ArchHook.java doctor` no corpo da skill
(`claude-help.md` § Dynamic context injection). Um comando que falha (exit ≠ 0) aborta a
invocação inteira — por isso [11-pitfalls.md](11-pitfalls.md) alerta sobre pipes dentro de
`allowed-tools: Bash(comando:*)`: cada segmento do pipe precisa da própria regra, ou o
comando inteiro é bloqueado antes de rodar.

**`context: fork`:** roda a skill num subagent isolado, sem ver o histórico da
conversa — o corpo da skill vira o prompt do subagent (`claude-help.md` §
`context: fork`). Nenhuma skill deste repositório usa esse campo hoje: as skills daqui
preferem delegar via `Agent tool` explicitamente para um `.claude/agents/*.md` nomeado,
quando isolamento é necessário (ver § Agent abaixo).

**Classe da skill — a parte que o runtime não conhece.** O runtime não tem nenhuma noção
de "que arquivos esta skill pode escrever" nem de "que seções o corpo dela precisa ter".
Aqui isso é dado, no bloco `skill_classes` de `.claude/schemas/extensions.json`: cada
skill pertence a exatamente uma das sete classes, e a classe declara duas coisas.

| Classe | Skills | Território de escrita |
|---|---|---|
| `design` | as 9 que escrevem parciais de caso de uso | a pasta do UC + `BACKLOG.md` |
| `orchestrator` | `new-feature`; `new-feature-implement`; `init-project` | `docs/**` do caso de uso; `new-feature-implement` e `init-project` não escrevem nada (delegam) |
| `build` | `project-bootstrap`, `docker-architect`, `arch-adopt`, `gof-design-patterns`, `sonarqube-setup`, `transport-security-setup` | um override por skill — a árvore toda, só o compose, só `.claude/`, só `src/`, só o build file raiz e `.github/workflows/`, ou o `application*.yml` do módulo principal + o filtro de HSTS + `src/test/**` + o `CLAUDE.md` raiz |
| `observer` | `arch-doctor`, `audit-usage` | nada |
| `meta` | `claude-code-architect-designer` | `.claude/**`, `CLAUDE.md`, `.mcp.json`, `docs/**`, `.github/**` |
| `ops` | `git-publish`, `triage-issue` | nada — o efeito é `git`, ou um comentário numa issue deste repo, via Bash |
| `report` | `sonar-lessons`, `report-issue` | `docs/lessons-learned/**` — e, só no `report-issue` e depois de confirmação, uma issue fora da árvore, via `gh` |

`ArchHook.java schema` cobra a estrutura (classe declarada no corpo com `**Class:** <c>`,
seções obrigatórias presentes) e `guard` cobra o território, com exit 2 em qualquer
escrita fora dele. Uma skill que não está em nenhuma classe não tem território — e o
`schema` falha pelo nome dela. Detalhes em
[08-audit-usage.md § Hook `guard`](08-audit-usage.md).

A classe também fixa o modelo: todo `SKILL.md` declara `model`, dentro dos
`allowed_models` da classe — `design` e `meta` só `opus`, `observer` e `ops` só `sonnet` —
e `schema` reprova uma skill sem ele (`.claude/decisions/0081-skill-model-required-per-class.md`).

---

## Rule

**Onde mora:** `.claude/rules/<nome>.md`.

**O que é:** uma norma — "o que deve ser verdade", nunca um procedimento. `CLAUDE.md` §
Invariant 1 é categórico: *"a norma nunca menciona uma skill, um agent, ou um comando.
Se precisar, é um procedimento e pertence a uma skill."*

**Propósito:** fixar um padrão que se aplica sempre que certos arquivos são tocados —
convenção de nomes (`naming.md`), taxonomia de exceção (`error-handling.md`), limites de
Clean Code (`code-quality.md`), e assim por diante. Cada norma tem **um único dono**
(`CLAUDE.md` § Invariant 2) — quem precisa dela cita o path, nunca copia o conteúdo.

**Quando entra em contexto:** dois mecanismos, e os dois valem para toda rule
(`.claude/rules/00-index.md` § How a rule enters context):

1. **Auto-loading via `paths`** — a rule declara globs no frontmatter, e entra em
   contexto sozinha quando um arquivo correspondente é tocado. Sem depender de alguém
   lembrar de citá-la.
2. **Citação explícita** — quem desenha algo que a rule governa, antes do arquivo
   existir, cita o path.

Sem `paths`, a rule carrega **na mesma prioridade que o `CLAUDE.md` do projeto**, ou
seja, toda sessão (`claude-help.md` § 4). Por isso toda rule declara `paths`, com o glob
mais estreito que a contém — `00-index.md` carrega em `.claude/rules/**`, e Java é
`**/src/**/*.java`, nunca `**/*.java` (que casa com `.claude/hooks/ArchHook.java`).
`architecture-ddd.md` tem `paths` de exemplo: o `export` troca pelos `architecture_paths`
do blueprint ativo.

**Quem invoca:** ninguém invoca uma rule — ela **carrega**, automaticamente (por
`paths`) ou por citação. Não existe comando `/rule-name`. É por isso que a tabela de
`00-index.md` lista, para cada rule, "Verificado por" em vez de "Invocado por": uma rule
que precisa de verificação mecânica aponta para um hook, um plugin de build
(Checkstyle) ou um teste (ArchUnit) — nunca para si mesma.

**O papel de cada rule**, onde ela termina e qual verificação a segura:
[14-rules.md](14-rules.md).

**Frontmatter reconhecido** (`.claude/schemas/extensions.json`):

```
status (obrigatório: active | draft | deprecated), paths (opcional)
```

Nenhum outro campo é permitido em uma rule — o schema para esse tipo é o mais estrito
dos três.

---

## Agent (subagent)

**Onde mora:** `.claude/agents/<nome>.md`.

**O que é:** um assistente especializado que roda em **janela de contexto isolada**,
com prompt de sistema, acesso a tools e model opcionalmente próprios
(`claude-help.md` § 7). O frontmatter define os metadados; o corpo markdown vira o
**system prompt** do subagent.

**Propósito — e quando NÃO virar um agent:** `CLAUDE.md` § Invariant 5 fixa o critério:
um agent só se justifica por um dos três motivos — preservar contexto (saída verbosa
que não deveria poluir a conversa principal), restringir tools, ou trocar de model. Se
nenhum se aplica, a peça é uma skill.

Os cinco agents deste repositório documentam explicitamente qual motivo aplica, na
própria seção `## Why this is an agent` (ou `## Why this is Form 3`):

| Agent | Contexto | Tools | Model |
|---|---|---|---|
| `project-initializer` | Saída verbosa da extração do `starter.tgz`, POMs, build output | `Read, Write, Edit, Bash, Glob, Grep, Skill` — restrito. `Skill` invoca o `project-bootstrap` e as skills que ele encadeia. Sem `AskUserQuestion`: o agent roda em background, onde a tool não existe, então o `/init-project` faz a entrevista inteira antes (decisão 0123) | `sonnet` + `effort: high` — o procedimento é dirigido pelo blueprint, pelo Initializr e pelos templates (decisão 0078) |
| `java-spring-boot-developer` | Spec completo substitui a entrevista — o agent só executa | `Read, Write, Edit, Bash` — só lê spec/templates, só escreve em `src/` | `sonnet`, `effort: high` — gerar ~19 passos de código compilável |
| `archunit-installer` | Modo setup da `test-architect` não tem entrevista — `curl` no Maven Central e até três builds `./mvnw` ficariam permanentes na conversa principal se rodassem inline | `Read, Write, Edit, Bash` — só dentro do projeto | `sonnet`, `effort: medium` — traduzir pacotes do exemplar para o layout real do blueprint e diagnosticar falha de regra ArchUnit exige julgamento, não só execução mecânica |
| `commons-logging-installer` | Mesma forma do `archunit-installer`: traduz treze exemplares de logging/máscara para o package real, edita um POM, compila até ficar verde — onze escritas e um log de build que não precisam voltar ao contexto do `/new-feature` que o disparou | `Read, Write, Edit, Bash` — só dentro de `commons.logging` e do POM correspondente | `sonnet`, `effort: medium` |
| `compose-service-installer` | O `/new-feature-implement` precisa adicionar o serviço que a spec decidiu no design, e a thread dele não pode: o guard recusa a chamada de `Skill` de classe `build` ali e a admite vinda de um agent com classe. Rodar o `docker-architect` aqui mantém o corpo de ~420 linhas dele fora da thread do implement | `Read, Write, Edit, Glob, Grep, Bash, Skill` — só dentro de `docker-compose*.yml`, `docker/**`, `Dockerfile`, `.env`, `.env.example`, `.gitignore`, `.dockerignore` | `opus`, `effort: medium` — o pin do `docker-architect`, mantido fora dos turnos seguintes, `git-publish` incluído |
| `issue-verifier` | Lê o corpo de uma issue pública — texto de terceiros — longe da thread que tem território de escrita em `.claude/` | `Read, Grep, Glob, Bash` — sem `Write`/`Edit`; a classe `verifier` tem território vazio, então `guard` recusa toda escrita no repo, Bash incluso | `opus`, `effort: high` — refutar a narrativa do relato é o trabalho todo (decisão 0103) |

Cada um pertence a uma **classe**, em `extensions.json` → `agent_classes`, e é a classe que
diz qual é a forma do corpo, quais campos de frontmatter ele deve, se ele escreve (`executor`)
e **quais caminhos**:

| Classe | Agents | Território (`write_allow`) |
|---|---|---|
| `driver` | `project-initializer` | `**` — escreve a árvore de um projeto que ainda não existe |
| `executor` | `java-spring-boot-developer` | `src/**`, os POMs (só para uma dependência que a spec declara), e o seu `UC-*-spec.md` — onde a regra de pasta congelada admite só o fechamento do `status:` e os toggles do checklist |
| `installer` | `archunit-installer`, `commons-logging-installer`, `compose-service-installer` | `overrides` por agent: POMs + `ArchitectureTest.java`/`TestcontainersConfiguration.java` no primeiro; POMs + `**/logging/**` (main e test) + `META-INF/spring/*.imports` no segundo; no terceiro, os paths que o `docker-architect` escreve — `docker-compose*.yml`, `docker/**`, `Dockerfile`, `.env`, `.env.example`, `.gitignore`, `.dockerignore` |
| `verifier` | `issue-verifier` | nada — `executor: false`; o scratch da reprodução em projeto gerado fica fora do repo |

O território vale por `agent_type`, que o payload `PreToolUse` de toda escrita de subagent já
carrega — então nenhuma fase de skill aberta o alarga nem o estreita. Antes disso os quatro
tinham bypass irrestrito, e a promessa em prosa de cada arquivo (`**Does not write:**
docker-compose.yml`) não era verificada por nada (ver
[08-audit-usage.md](08-audit-usage.md)).

**Quando entra em contexto:** só quando invocado — nunca automaticamente por
`paths` (agents não têm esse campo). **Não vê** o histórico da conversa principal, nem
a memória automática da sessão principal, nem o que foi lido antes (`claude-help.md` §
What the subagent sees). Vê: o próprio system prompt, a hierarquia de `CLAUDE.md`, git
status, a mensagem de delegação, skills pré-carregadas via o campo `skills:` do seu próprio
frontmatter, e o que um hook de `SubagentStart` entrega como `additionalContext` — é assim que
`java-spring-boot-developer` recebe o catálogo de `gof-design-patterns` num projeto gerado:
uma skill com `disable-model-invocation: true` não pode ser pré-carregada via `skills:`
(decisão 0077).

**Quem invoca:** sempre outra peça do sistema, nunca o usuário diretamente por
`/nome-do-agent` — não existe esse comando. Aqui, é sempre uma skill que delega via
`Agent tool`: `init-project` delega para `project-initializer`, `new-feature-implement` delega
para `java-spring-boot-developer` quando `/new-feature-implement UC-NNN-slug` implementa uma spec
aprovada — três vezes, uma por grupo de blocos, `test-architect` delega para
`archunit-installer` no modo setup (sem argumento), e o pre-flight de `new-feature-implement` delega
para `commons-logging-installer` quando `commons` está vazio. Depois que o grupo `tests` termina verde e
antes do `git-publish`, `new-feature-implement` delega para `compose-service-installer` quando a spec tem
uma linha `materialize` no `## Infrastructure`.

**Fork — um caso diferente de agent:** um *fork* herda a conversa inteira em vez de
começar do zero (`claude-help.md` § Forks). Só o resultado final volta à conversa
principal; as chamadas de tool internas ficam fora do seu contexto. Nenhum dos agents
deste repositório é um fork — todos recebem apenas a mensagem de delegação, de
propósito, porque o ponto é justamente **não** herdar o ruído da conversa de design.

**Campos de frontmatter reconhecidos** (`.claude/schemas/extensions.json`, `case: camel`):

```
name, description, tools, disallowedTools, model, permissionMode,
maxTurns, skills, mcpServers, hooks, memory, background, effort,
isolation, color, omitClaudeMd, initialPrompt, experimental
```

**Arquivos silenciosamente ignorados:** um agent sem `name`, com `---` fora da
primeira linha, com `name` começando com `-` ou contendo `:`, sem `description`, ou com
YAML inválido — o Claude Code simplesmente pula o arquivo, sem erro visível
(`claude-help.md` § Files silently ignored). É por isso que este repositório valida com
`java .claude/hooks/ArchHook.java schema`, que é estrito onde o runtime é silencioso.

---

## `CLAUDE.md`

**Onde mora:** raiz do repositório (ou `.claude/CLAUDE.md`), com hierarquia adicional
em `~/.claude/CLAUDE.md` (usuário) e `./CLAUDE.local.md` (pessoal, fora do git).

**O que é:** o arquivo de memória de projeto — instruções que o time versiona e que
todo mundo (humano ou modelo) lê. `claude-help.md` § 3 descreve a hierarquia completa;
todos os níveis são **aditivos**, não se substituem.

**Propósito neste repositório:** índice e roteamento, não manual. `CLAUDE.md` § How a
rule enters context e a própria seção `## Routing` deste arquivo funcionam como uma
tabela "se a tarefa envolve X, vá para Y" — a norma em si mora na skill ou na rule
citada, nunca dentro do `CLAUDE.md`.

**Quando entra em contexto:** sempre, no início da sessão — arquivos no diretório
atual **e em todo diretório acima dele** carregam no startup; arquivos em
subdiretórios carregam sob demanda, quando o Claude lê algo lá dentro
(`claude-help.md` § File hierarchy). Diferente de uma skill, não há como um `CLAUDE.md`
carregar "só quando necessário" — é sempre pago, por isso a meta de manter abaixo de
200 linhas (`claude-help.md` § Writing an effective CLAUDE.md).

**Quem invoca:** ninguém — carrega automaticamente. O comando `/init` gera um
`CLAUDE.md` inicial a partir do código existente; `/memory` lista e abre os arquivos de
memória (nativos do Claude Code, distintos deste repositório).

**Duas versões relevantes aqui:**

| `CLAUDE.md` | Papel |
|---|---|
| Raiz deste repositório | Documenta o meta-repositório — invariantes de arquitetura do `.claude/`, tabela de roteamento para as skills daqui |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | Molde do `CLAUDE.md` que `project-bootstrap` escreve **dentro do projeto gerado** — dados diferentes (nome, módulos, versões resolvidas), mesma disciplina de tamanho e de citar por path |

`CLAUDE.md` § Known pitfalls é explícito: *"o `CLAUDE.md` na raiz de um projeto gerado
não é este arquivo."* Confundir os dois é o erro mais comum ao editar este repositório.

---

## Hook

**Onde mora:** `.claude/settings.json` (configuração) + `.claude/hooks/ArchHook.java`
(lógica), compilado no `.claude/hooks/ArchHook.jar` commitado que toda entrada executa.

**O que é:** um script disparado em eventos do ciclo de vida — "determinístico:
sempre acontece no evento, independente do que o modelo decide. Essa é a diferença
entre pedir e garantir" (`claude-help.md` § 8). É a única peça deste sistema que o
modelo **não pode** optar por pular.

**Propósito:** tudo que precisa valer sempre, sem depender de o modelo lembrar —
`CLAUDE.md` § Invariant 6: *"se uma regra precisa valer sempre, é um hook ou
`permissions.deny` — não prosa em markdown."* `ArchHook.java` tem onze modos, e toda
entrada lança o `.claude/hooks/ArchHook.jar` pré-compilado a partir dele (`java -jar`):

| Modo | Evento | Bloqueia? | O que faz | Ligado aqui? |
|---|---|---|---|---|
| `check` | `PostToolUse` (Write\|Edit\|MultiEdit\|NotebookEdit, `if: Edit(**/*.java)`) | Sim (exit 2) | Imports proibidos (via `.claude/forbidden-imports.txt`) + compilação incremental do módulo tocado | Sim |
| `format` | `PostToolUse` (mesmo matcher e `if`) | Nunca | `spotless:apply` no módulo tocado | Sim |
| `tests` | `Stop` · `SubagentStart`/`SubagentStop` (`agent-start`, `agent-end`) | Sim (exit 2) | Roda `tests.stop_maven_args` — `verify -DskipITs -Djacoco.skip=true` — nos módulos alterados desde `HEAD` (decisão 0142); adia, sem bloquear, enquanto um subagent escritor da sessão roda (decisão 0116) | Sim |
| `schema` | `PreToolUse` + `PostToolUse` (em `.claude/**/*.md`, `.mcp.json`, `settings.json`) + `Stop` | Sim (exit 2) | Valida frontmatter de skills/agents/rules, o **corpo** de cada `SKILL.md` contra a classe dela (`skill_classes`), cada agent contra `agent_classes`, os campos de `.mcp.json` com scan de segredos em `headers`/`env`, as entradas de hook de `settings.json`, e o manifesto `export` contra o disco | Sim |
| `guard` | `UserPromptSubmit` (`prompt`) + `PreToolUse` (Skill\|Agent\|Task → `call`; Write\|Edit\|MultiEdit\|NotebookEdit → `write`; Bash → `bash`) + `Stop` (`sweep`) | Sim (exit 2) | Cada skill escreve só o território da própria classe, cada subagent só o território de `agent_classes` (allowlist, deny por default); skill de classe `build` é inalcançável durante um run de design; pasta de spec `approved`/`implemented`/`implemented-blocked` é congelada, exceto o fechamento do status, os toggles do checklist e `CHANGELOG.md`; escritas de shell que ele consegue ler literalmente passam pelas mesmas checagens, e force push é recusado; no `Stop`, o que o turno mudou em disco é varrido contra as mesmas regras | Sim, nos dois |
| `audit` | 11 eventos do ciclo de vida | Nunca | Trilha de execução de toda skill e agent: relatório Markdown por invocação + ledgers | Só no projeto gerado — liga pela existência de `.claude/audit-usage/` |
| `compose` | Manual; dobrado em `doctor`; `compose gate` no `Stop` | O gate: sim (exit 2) | Todo serviço do compose está `running`; nenhum container alheio publica uma porta que este projeto declara (aviso, não bloqueio, enquanto nenhum container deste projeto existe); nenhuma tag de `image:` divergindo da que `src/test` fixa; nenhuma porta publicada anunciada só pelo nome da rede do compose; todo `${VAR:default}` que aponta para um serviço do compose vale no host (o default chega numa porta publicada) e no container (o `app` seta a variável) | Sim, nos dois |
| `context` | `SubagentStart` (`context subagent`) | Nunca | Entrega as seções do catálogo de `gof-design-patterns` que `subagent_context` nomeia a todo agent cuja classe declara `pattern_catalog: true`, como `additionalContext` antes do primeiro turno | Só no projeto gerado |
| `doctor` | Manual (`/arch-doctor`); `gate` no CI do projeto gerado | Só `gate`, exit 1 | Diagnóstico do setup na máquina; `gate` falha numa linha vermelha listada em `doctor.gate.labels` | Sim |
| `export` | Manual (invocado por `/arch-adopt`) | Nunca | Escreve o `.claude/` de um projeto-alvo a partir do manifesto `export`, transformado para o blueprint ativo, e grava o stamp de proveniência | Sim — ver [10-arch-adopt.md](10-arch-adopt.md) |
| `build` | `PostToolUse` numa edição de `ArchHook.java`; manual | Nunca | Reconstrói `ArchHook.jar` a partir do fonte sob o major do JDK que `hook_build.javac_feature` fixa; `build --verify` (rodado pelo CI) confere que o jar commitado é byte a byte o que o fonte compila | Só neste repo |

Os modos `guard` e `audit` estão detalhados em [08-audit-usage.md](08-audit-usage.md).
Toda lista que o hook lê — campos reconhecidos, skills excluídas da auditoria, padrões
de redação, caminhos guardados — é dado em `extensions.json`, nunca constante no Java.

**Quando entra em contexto:** hooks não "entram em contexto" como texto — rodam como
**processo externo** (aqui, `java -jar .claude/hooks/ArchHook.jar <modo>`), e o que volta para o modelo é
saída estruturada (stderr vira a razão do bloqueio) ou JSON (`claude-help.md` §
Structured JSON output). O campo `if` em cada entrada de `settings.json` filtra por
regra de permissão **antes** de instanciar a JVM — sem isso, cada `Write` pagaria o
custo de subir uma JVM à toa (comentário `_comment` em `.claude/settings.json`).

**Quem invoca:** o runtime, automaticamente, no evento configurado — nunca o modelo
nem o usuário diretamente. O único jeito de rodar `ArchHook.java` "manualmente" é via
`java .claude/hooks/ArchHook.java doctor` no terminal, ou indiretamente pela skill
`arch-doctor`, que embute essa chamada via `` !`comando` `` no próprio corpo (ver §
Skill acima).

**Forma shell, não exec:** o `settings.json` escreve cada hook como uma string só,
`java -jar "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar" <modo>`, sem `args`. O GitHub
Copilot CLI lê o mesmo arquivo, roda só o `command` e ignora `args`, então a forma exec negava
toda chamada lá. O Claude Code substitui o placeholder entre aspas e roda a string em `sh -c`,
Git Bash ou PowerShell (`claude-help.md` § Configuration). Nenhum bit de execução entra nisso,
e é por isso que não há `chmod` em nenhum passo de `project-bootstrap`.

**Códigos de saída:**

| Código | Comportamento |
|---|---|
| `0` | Sucesso — se stdout for JSON válido, é interpretado |
| `2` | **Bloqueia a ação** (em eventos que suportam bloqueio) — stderr vira a razão mostrada ao modelo |
| Outro | Erro não-bloqueante — a ação prossegue |

Em `PostToolUse` o exit `2` não desfaz a ação (a tool já rodou), mas o stderr é
mostrado ao Claude — é assim que `check` reporta uma violação de boundary depois que o
arquivo já foi escrito: a escrita aconteceu, mas o modelo é avisado para corrigir.

**Onde configurar:** `.claude/settings.json` (deste projeto, versionado) é o único
lugar usado aqui — não há `~/.claude/settings.json` pessoal nem
`.claude/settings.local.json` específicos deste fluxo (`claude-help.md` § Where to
configure lista as demais opções, incluindo hooks por frontmatter de skill/subagent,
não usados neste repositório). No projeto gerado, o mesmo arquivo é escrito por
`project-bootstrap` a partir de `templates/settings.json.example`, com `audit` e `context`
ligados a mais.

---

## Resumo comparativo

| | Skill | Rule | Agent | `CLAUDE.md` | Hook |
|---|---|---|---|---|---|
| Formato | `SKILL.md` + pasta | `.md` solto | `.md` solto | `.md` solto | `.json` + script |
| Contém | Procedimento | Norma | System prompt + config | Índice/roteamento | Lógica determinística |
| Entra em contexto | Sob invocação | Auto (`paths`) ou sempre | Sob invocação, isolado | Sempre, no startup | Nunca como texto — roda como processo |
| Invocado por | Modelo e/ou usuário (`disable-model-invocation`) | Ninguém — carrega | Outra skill/agent, via Agent tool | Ninguém — carrega | Runtime, no evento |
| Pode citar | Rules, blueprints, outras skills, agents | Nada (folha) | Rules, skills que segue | Skills, rules, agents (por path) | Nada — é código |
| Bloqueia algo? | Não, por si só | Não, por si só | Não, por si só | Não, por si só | Sim, com exit 2 |
