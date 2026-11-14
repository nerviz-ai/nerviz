# `/claude-code-architect-designer` — decide e cria extensões do `.claude/`

Fonte primária: `.claude/skills/claude-code-architect-designer/SKILL.md`,
`.claude/skills/claude-code-architect-designer/references/decision-matrix.md`,
`.claude/skills/claude-code-architect-designer/references/frontmatter-fields.md`,
`.claude/skills/claude-code-architect-designer/references/mcp-fields.md`,
`.claude/skills/claude-code-architect-designer/references/ci-coverage.md`,
`.claude/skills/claude-code-architect-designer/references/mod-events.md`.

## O que faz

Decide **qual das nove formas** de extensão do Claude Code resolve um cenário
concreto — skill auto-invocável, skill manual, subagent, rule, seção do `CLAUDE.md`,
servidor MCP (compartilhado ou por agent), hook (registro ou modo novo do
`ArchHook.java`), regra de `permissions`, ou um mod que desenha na interface o que os hooks
decidem — e só escreve o arquivo depois de aprovação explícita. Uma décima resposta, legítima e a mais barata, é **não criar nada**: ou já
existe peça cobrindo o cenário, ou um CLI já resolve (`gh`, `psql`, `aws` — § 2.1 da
matriz de decisão), ou um modo que o `ArchHook.java` já tem roda a checagem e só falta
registrá-lo.

Invocação manual apenas (`disable-model-invocation: true`) — o modelo nunca decide
sozinho criar uma nova skill, agent ou rule.

```
/claude-code-architect-designer [cenário em uma frase]
```

## Por que é skill e não rule

Interview → classifica → propõe → escreve é procedimento de múltiplos passos — vira
rule quebraria o invariante 1 do `@CLAUDE.md` (`rules/` é folha: uma rule nunca cita
skill, agent ou comando). Uma rule que explicasse quando criar skills e agents estaria
citando skills e agents dentro de si mesma.

## As nove formas

| # | Forma | Arquivo |
|---|---|---|
| 1 | Skill auto-invocável | `.claude/skills/<name>/SKILL.md` |
| 2 | Skill de invocação manual (`/name`) | mesmo arquivo, com `disable-model-invocation: true` |
| 3 | Subagent | `.claude/agents/<name>.md` |
| 4 | Rule | `.claude/rules/<name>.md` |
| 5 | Seção do `CLAUDE.md` | `CLAUDE.md` na raiz |
| 6a | Servidor MCP, compartilhado | `.mcp.json` |
| 6b | Servidor MCP, só um agent | `mcpServers:` no frontmatter daquele agent |
| 7a | Hook para a sessão inteira | bloco `hooks` de `.claude/settings.json` |
| 7b | Hook só enquanto uma skill ou agent roda | `hooks:` no frontmatter daquele arquivo |
| 7c | O executável por trás de 7a/7b | modo novo em `.claude/hooks/ArchHook.java` |
| 8 | Proibição dura ou permissão permanente | `permissions.deny` / `permissions.allow` |
| 9 | Mod — desenha na interface o que 7 e 8 já decidem | `.claude/mods/<name>/` |

6b não é um quarto motivo pra existir agent — é o motivo 2 da § 5 da matriz de decisão
(restringir tools) aplicado a uma conexão externa em vez de uma tool nativa. Um agent só
existe pelos três motivos já em `references/decision-matrix.md` § 5; 6b só responde
quais tools ele ganha depois que isso já foi decidido.

7c não é um gatilho — é o que a 7a ou a 7b invoca, e só existe quando **nenhum** modo que
o hook já tem cobre a checagem. Uma 7a que reusa `check`, `format`, `schema`, `tests`,
`guard`, `audit` ou `compose` é o caso comum e não escreve uma linha de Java.

As formas 7 e 8 são o lado **garantia** da § 1 da matriz: executam independentemente do
que o modelo decide. Isso as torna a resposta sempre que a regra precisa valer sempre
(invariante 6 do `@CLAUDE.md`) e, ao mesmo tempo, a coisa mais cara desta lista de se
errar — um hook que bloqueia dispara em todo evento que casa, para todo mundo, inclusive
quando está errado. Por isso as duas exigem aprovação **e** registro em `decisions/`,
sem exceção, e o relatório final avisa que `settings.json` só é lido no startup da
sessão: um hook que se acredita ativo e está silenciosamente ausente é pior que hook
nenhum.

A Forma 9 fica **em cima** dessa linha, nunca atravessando (decisão 0131). Um mod é
TypeScript rodando dentro do processo do Claude Code que pode desenhar — faixa acima do
prompt, painel, `/comando` que não gasta turno, diálogo que segura uma tool call — mas não
carrega em `claude -p`, VS Code, sessão cloud nem sob `--safe-mode`, então nunca é a
garantia. Mostra ou segura o que uma Forma 7 ou 8 embaixo dele já decide, e pega toda
resposta de `java -jar .claude/hooks/ArchHook.jar`, nunca reescrevendo a regra. O que ele
pode escutar, chamar e iniciar é o bloco `mods` do `extensions.json`; o mapa é
`references/mod-events.md`. Também sempre ganha registro em `decisions/`, e fica neste
repositório — nada em `.claude/mods/` vai pro projeto gerado. Dois são entregues.
`nerviz-cockpit` desenha a fase de skill aberta e o território acima do prompt,
`/nerviz-doctor` num painel, e segura uma recusa do `guard` atrás de dois botões (deixar o
Claude se ajustar, ou parar o turno) — a recusa vale de qualquer jeito. `nerviz-config` mostra
num painel (`/nerviz-config`) o que um projeto configura no próprio `.claude/` e só muda uma
configuração por `ArchHook.jar config set|unset`, depois de um diálogo.

## Fora de escopo

- **`~/.claude/settings.json` e `.claude/settings.local.json`.** Configuração pessoal e
  de máquina: não é versionada, não é compartilhada, não é preocupação desta skill. Um
  hook que o time não recebe não é enforcement, é hábito de uma pessoa.
- **Um segundo arquivo de hook.** A Forma 7c é um modo dentro de `ArchHook.java`, nunca
  `.claude/hooks/<Outro>.java`. O enforcement fica num único executável que o projeto
  gerado recebe inteiro pelo modo `export`; um segundo arquivo teria de ser copiado,
  registrado e mantido em sincronia à parte — e o primeiro a sair de sincronia falha em
  silêncio. Um mod da Forma 9 não é um: não impõe nada e pergunta tudo ao `ArchHook.jar`.
- **Mod como garantia, ou mod no projeto gerado.** A regra que precisa valer sempre
  continua Forma 7 ou 8; mods ficam aqui (0131).
- **A skill nativa `plugin-authoring` e `~/.claude/dev-mods/`.** Mesmo motivo do
  `skill-creator`: um mod genérico num diretório pessoal, sem conhecer o bloco `mods` nem a
  garantia sobre a qual ele precisa ficar.
- **`.claude/commands/`.** Nunca — invariante 4, comandos viraram skills com
  `disable-model-invocation`.
- **Blueprints.** Arquitetura é dado, não extensão (`@.claude/blueprints/_schema.md`).
- **MCP onde um CLI já resolve, ou uma tool específica que nunca pode ser chamada.**
  Matriz de decisão § 2.1: `gh`/`psql`/`aws`/etc. vence um servidor novo, e esta skill diz
  isso e não propõe nada. Uma tool que nunca pode rodar é `permissions.deny` em
  `mcp__<server>__<tool>` — igual a qualquer outra ação proibida, propõe e para, não
  escreve a regra.
- **`~/.claude.json`, MCP de escopo `local`/`user`.** Servidores pessoais ou
  experimentais (`claude mcp add` sem `--scope project`) não são versionados e não são
  preocupação desta skill — ela só escreve o que o time compartilha.
- **Plugin `skill-creator`.** Desabilitado de propósito em `.claude/settings.json`: gera
  skills genéricas, sem conhecimento dos invariantes deste repo.

## Diagrama de sequência

```mermaid
sequenceDiagram
    actor U as Usuário
    participant CMD as skill: claude-code-architect-designer
    participant DM as decision-matrix.md
    participant FM as frontmatter-fields.md
    participant FS as sistema de arquivos

    U->>CMD: /claude-code-architect-designer "cenário em 1 frase"
    Note over CMD: injeção dinâmica lista skills/agents/rules/decisions ANTES do modelo ver o texto
    CMD->>FS: ls .claude/skills .claude/agents .claude/rules .claude/decisions
    FS-->>CMD: inventário atual

    rect rgb(235,235,245)
    Note over CMD,U: Fase 1 · Interview
    CMD->>DM: lê matriz e pitfalls antes de perguntar
    CMD->>U: AskUserQuestion — até 4 perguntas por chamada, 2-3 chamadas
    U-->>CMD: respostas dos eixos aplicáveis (17 no total, 11-13 só para MCP, 14-16 só para hook, 17 respondido pela própria skill)
    end

    rect rgb(235,245,235)
    Note over CMD: Fase 2 · Classifica
    CMD->>DM: aplica tabela de decisão (§ 2, e § 2.1 se o gatilho é sistema externo)
    CMD->>CMD: roda os 11 invariantes do CLAUDE.md como veto
    end

    rect rgb(245,240,225)
    Note over CMD,U: Fase 3 · Propõe — nada é escrito
    CMD-->>U: 2-4 opções ordenadas por score + tabela de referências
    end

    opt Fase 3.5 · só se houve escolha real
        CMD->>FS: .claude/decisions/NNNN-<slug>.md (rascunho, sem State)
    end

    Note over CMD,U: para aqui — espera aprovação explícita

    U->>CMD: aprova opção N
    rect rgb(250,230,230)
    Note over CMD: Fase 4 · Escreve
    CMD->>FM: confirma campos nativos do frontmatter
    CMD->>FS: gera a partir de templates/*.example
    CMD->>FS: seção "## Why this is <form>" no corpo
    CMD->>FS: propaga — CLAUDE.md, 00-index.md, extensions.json (skill_classes/agent_classes/export), settings.json, conforme a forma
    CMD->>FS: CI — teste em .claude/.ci/ + step no workflow, ou nomeia o check que já cobre
    CMD->>FS: java claude plugin validate .claude/skills
    end
    CMD-->>U: Fase 5 · relatório — arquivos criados/alterados, decisão, validate, linha de CI, aviso de restart
```

## Fase 1 · Interview

**Regra de entrada: nenhuma proposta sem interview.** Classificar a partir de uma frase
produz a peça errada, e peça errada custa mais que nenhuma peça — fica em contexto toda
sessão, ou nunca dispara.

Vinte eixos, cada um elimina formas candidatas. Sem resposta em um eixo, a decisão
está adivinhando:

| # | Eixo | O que decide |
|---|---|---|
| 1 | Sintoma concreto — que erro se repete, que prompt é colado de novo | Se há caso, ou é antecipação |
| 2 | Gatilho — `/comando`, decisão do modelo, tocar um arquivo, evento de runtime, ou alcançar um sistema externo | Formas 1 · 2 · 4 · 6 · fora de escopo |
| 3 | Frequência — toda sessão, semanal, raro | Sempre carregado vs sob demanda |
| 4 | Território — quais globs de arquivo, ou nenhum | `paths` na forma 4; `paths` na forma 1 |
| 5 | Natureza — fato declarativo ou sequência de passos | Formas 4/5 vs 1/2/3 |
| 6 | Isolamento — saída verbosa, tools a restringir, modelo diferente | Forma 3, e só ela |
| 7 | Obrigatoriedade — pode falhar às vezes, ou é build/segurança/compliance | Formas 7 · 8 vs todo o resto |
| 8 | Destino — só este repo, também o projeto gerado, ou ambos | O bloco `export` de `@.claude/schemas/extensions.json` |
| 9 | Integração — o que lê, o que escreve, com qual peça existente colide | Conflito de ownership |
| 10 | Custo de errar | minutos ou dias | Peso no score |
| 11 | Um CLI já resolve (`gh`, `psql`, `aws`, `kubectl`, `sentry-cli`)? | Elimina a Forma 6 antes mesmo dela ser considerada — matriz de decisão § 2.1 |
| 12 | Formato da credencial — OAuth, token estático, script de header dinâmico, ou nenhuma; só leitura ou leitura/escrita | Forma 6a vs 6b vs `permissions.deny`; se precisa de `oauth`/`headersHelper` |
| 13 | Destino — só o `.mcp.json` deste repo, só o template do projeto gerado, ou ambos | Qual(is) arquivo(s) a Forma 6a escreve; propagação na Fase 4 passo 7 |
| 14 | Evento do ciclo de vida — o que exatamente acabou de acontecer: uma tool call, um prompt, o fim de um turno, o início da sessão | A chave de evento da Forma 7, e se aquele evento sequer **lê** um `matcher` — `references/hook-events.md` |
| 15 | Reação — observar e reportar, injetar contexto, ou bloquear | O exit code, e Forma 7 vs Forma 8: uma chamada que **nunca** pode acontecer é `permissions.deny`, mais barato que um hook que sobe um processo pra recusar |
| 16 | Modo existente — o `ArchHook.java` já roda essa checagem? | Forma 7a sozinha vs 7a + 7c. `java .claude/hooks/ArchHook.java doctor` lista os modos em uso |
| 17 | Cobertura de CI — qual job já prova que a peça faz o que afirma, o que fica sem prova, e em qual pipeline roda o check que falta | O item CI de toda opção da Fase 3 e o passo de CI da Fase 4 — `references/ci-coverage.md` |
| 18 | Superfície — a pessoa precisa ver só no terminal e no Desktop, ou também em `claude -p`, VS Code, sessão cloud | Forma 9 vs `systemMessage`/`additionalContext` de um hook; nunca Forma 9 sozinha quando precisa aparecer em todo lugar |
| 19 | Garantia embaixo — qual settings hook ou regra de `permissions` já decide o que a peça mostra ou segura | Forma 9 só em cima de uma; nenhuma → Forma 7 ou 8 primeiro, ou o mod só observa |
| 20 | Estado — o que a peça guarda e por quanto tempo: nada, a sessão (`$.state`), ou entre sessões (`$.store`) | Qual API de mods o mod chama |

O eixo 9 é checado contra o inventário injetado no topo da skill (`ls .claude/skills`,
`.claude/agents`, `.claude/rules`, `.claude/decisions`), nunca de memória. Duas peças
escrevendo no mesmo caminho é bug de ownership, não decisão de estilo.

Os eixos 11-13 só se aplicam quando o eixo 2 (gatilho) nomeia um sistema externo — Jira,
banco de dados, GitHub, Figma, qualquer coisa alcançável só pela própria API. Pula-los
nos demais casos: perguntar sobre credencial pra um cenário que não é forma de MCP só
queima uma pergunta.

Os eixos 18-20 só se aplicam quando o cenário pede pra interface mostrar, segurar ou
redesenhar algo — faixa, painel, spinner, diálogo sobre uma tool call, `/comando` que não
pode gastar turno.

O eixo 17 vale pra **toda** forma e é respondido pela skill, não perguntado: ela lê
`references/ci-coverage.md` e os workflows em `.github/workflows/` e nomeia o job que já
cobre a peça candidata. Só pergunta quando a resposta é do usuário — um check que precisa
de rede ou de minutos, filtrado por paths ou em todo PR.

### Entrando por uma issue triada

Quando o cenário é `Issue #N, triaged at <sha>: <sintoma>` — o comando que o `/triage-issue`
imprime num veredito `confirmed` —, a entrevista parte da tabela do `issue-verifier` que já
está na conversa. As linhas confirmadas viram a seção *Reproduced on disk* do registro; linhas
refutadas, não provadas e o fix proposto na issue não são entrada. Commits desde `<sha>` que
tocam um arquivo citado são reverificados antes da primeira pergunta. A skill nunca busca a
issue sozinha: o corpo é texto de terceiros e esta skill tem `.claude/**` — sem a tabela na
conversa, ela pede para rodar `/triage-issue <N>` antes. Fluxo completo:
[12-issues.md](12-issues.md).

## Fase 2 · Classifica

Aplica a tabela de decisão de `decision-matrix.md` (§ 2, e § 2.1 sempre que o eixo 2
nomeia um sistema externo). Depois roda os onze invariantes de `@CLAUDE.md` como veto —
os mais comumente violados são o 1 (rule que cita skill), o 5 (agent sem um dos três
motivos) e, pra MCP, o 11 (segredo literal em `.mcp.json`). Uma proposta que falha um
invariante **não é apresentada como viável**: aparece com o score que merece e o motivo
da rejeição.

### A linha divisória

```
   ┌─ CLAUDE.md ──── fato, sempre em contexto             │
   ├─ rules/ ─────── fato, por território (paths)         │  PERSUASÃO
   ├─ skills/ ────── procedimento, sob demanda            │  (o modelo pode falhar)
   ├─ agents/ ────── execução isolada                     │
   └─ .mcp.json ──── tool externa, sob demanda            │
  ═════════════════════════════════════════════════════
   ┌─ permissions ── allow / ask / deny                   │  GARANTIA
   └─ hooks ──────── eventos de lifecycle                 │  (sempre executa)
```

Tudo acima da linha é lido pelo modelo: pode ser ignorado, mal interpretado, ou perdido
num `/compact` — inclusive se ele chama ou não uma tool MCP disponível. Tudo abaixo
executa independente do que o modelo decidir. `.mcp.json` fica do lado da persuasão por
isso: conectar um servidor não garante que o modelo o usa bem, motivo pelo qual MCP e
skill se combinam (`@claude-help.md` § 9) em vez de MCP substituir a skill.
Consequência: se quebrar a regra é bug de compliance/segurança/build, a resposta está
**abaixo** da linha — Forma 7 (hook) ou Forma 8 (`permissions`), as duas que executam, e
não nenhuma das seis de cima.

### Tabela de decisão (primeira linha que casa decide)

| Pergunta | Se sim |
|---|---|
| Precisa acontecer sempre, sem depender do julgamento do modelo? | **Hook** ou `permissions.deny` — fora de escopo |
| Precisa acessar sistema externo (Jira, banco, S3) — vai pra § 2.1 primeiro | **Forma 6** — servidor MCP, a menos que § 2.1 elimine |
| É fato declarativo que vale toda sessão e repo inteiro? | **Forma 5** — seção do `CLAUDE.md` |
| É fato declarativo que só vale para parte dos arquivos? | **Forma 4** — rule com `paths` |
| É procedimento de múltiplos passos, ou referência longa e rara? | **Forma 1 ou 2** — skill |
| … e usuário quer disparar na mão, ou tem efeito colateral? | **Forma 2** — `disable-model-invocation: true` |
| … e deve disparar sozinho pela descrição, sem o usuário pedir? | **Forma 1** |
| Precisa de contexto isolado, tools restritas, ou modelo diferente? | **Forma 3** — subagent, e mesmo assim ver § 5 da matriz |
| A pessoa precisa **ver** ou **agir sobre** algo que os hooks já decidem? | **Forma 9** — mod; a § 2.3 da matriz devolve pra Forma 7/8 quando a regra precisa valer onde nenhum mod carrega, ou quando uma linha de stderr já diz tudo |

Nenhuma linha casa → resposta é **não criar nada**.

### Sub-tabela § 2.1 — qual forma de MCP, e se é MCP mesmo

De cima pra baixo, mesma regra: **a primeira linha que casa decide**. Fica dentro da
linha "sistema externo" acima porque o check CLI-first precisa rodar antes da Forma 6
ser sequer considerada, não depois.

| Pergunta | Se sim |
|---|---|
| Um CLI já resolve (`gh`, `psql`, `aws`, `kubectl`, `sentry-cli`)? | **Não criar nada** — `Bash` + uma linha em `permissions.allow`. `@claude-help.md` § 9 chama isso de alternativa mais barata; é o jeito mais econômico em contexto de falar com um serviço externo, e o modelo já sabe usar |
| A tool nunca pode ser chamada, de jeito nenhum? | `permissions.deny` em `mcp__<server>__<tool>` — fora de escopo |
| Só um agent precisa dela? | **Forma 6b** — `mcpServers` no frontmatter daquele agent |
| O time inteiro precisa, sem segredo literal no arquivo? | **Forma 6a** — `.mcp.json`, credencial via `${VAR}`, `oauth`, ou `headersHelper` |
| É pessoal ou experimental, não do time? | `claude mcp add --scope local` — esta skill não escreve isso; não é versionado |
| O servidor já existe e o modelo só usa mal? | **Forma 1** — uma skill documentando como usar bem as tools dele, não servidor novo |

Nenhuma linha casa → **não criar nada**. Servidor sem chamada observada é peso morto
desde a primeira sessão: só o nome já custa contexto no startup.

### Forma 1 vs Forma 2

Mesmo arquivo, uma linha de diferença. Regra prática deste repo:

- **Escreve arquivos no projeto do usuário e é disparada por decisão dele → Forma 2.**
  Ex.: `arch-doctor`, `init-project`, `project-bootstrap`, `gof-design-patterns`, e esta
  própria skill.
- **Parte de um pipeline que outra peça encadeia → Forma 1**, mesmo escrevendo
  arquivos. `disable-model-invocation` esconde a skill do modelo, e o que o modelo não
  vê o orquestrador não chama. As cinco peças de `/new-feature` são assim desde D17
  (`@.claude/decisions/0007-pipeline-skills-invocation.md`).

### Forma 3 — os três motivos, e só esses

1. **Preservar contexto** — exploração verbosa fica no subagent, só o resumo volta.
2. **Restringir tools** — um reviewer com `tools: Read, Grep, Glob` não escreve.
3. **Controlar custo ou capacidade** — `model: haiku` para triagem, `opus` onde falhar
   é caro.

Nenhum se aplica → é skill. Este é o anti-padrão #1, e o mais caro: mais uma peça pra
manter, nenhum ganho. Contra-teste: se a interview com o usuário é o coração da tarefa,
se o contexto cabe num `references/` da própria skill, ou se a saída final é curta de
qualquer jeito — qualquer "sim" aponta pra skill, não agent. Precedentes neste repo:
`init-project` → `project-initializer`, `new-feature`/`project-initializer` →
`git-publish` (este último rejeitado como agent: a interview de confirmação com o
usuário é o coração da tarefa — ver `@.claude/decisions/0034-git-publish-skill.md`).

**Corolário — `mcpServers` no frontmatter de um agent é o motivo 2, nunca um quarto
motivo.** Dar a um agent seu próprio servidor MCP é "restringir tools" aplicado a uma
conexão externa em vez de uma nativa: o resto da sessão não carrega os nomes das tools
desse servidor em contexto, e nenhum outro agent alcança ele. Não libera uma categoria
nova de agent — o mesmo teste de três motivos acima ainda decide se o agent deveria
existir; `mcpServers` só responde *quais* tools ele ganha depois que o agent já está
justificado.

### Forma 4 vs Forma 5

| | Forma 5 (`CLAUDE.md`) | Forma 4 (`rules/`) |
|---|---|---|
| Custo | Todo prompt, toda sessão | Só quando um `paths` casa |
| Escopo | Repo inteiro | Território de arquivo |
| Sobrevive a `/compact` | Sim, reinjetado | Recarrega ao tocar os arquivos |
| Meta de tamanho | < 200 linhas no total | Um tema por arquivo |

Duas vias de carregamento pra Forma 4, a primeira preferível: `paths` (auto-carrega,
não depende de ninguém lembrar) e citação explícita por caminho, pra rules
cross-cutting que glob nenhum captura.

## Fase 3 · Propõe — nada é escrito

Duas a quatro opções, ordenadas por score, sempre incluindo a hipótese "não criar nada"
quando defensável. Cada opção nesta forma, nesta ordem:

1. **Título** — nome de arquivo proposto, kebab-case
2. **Motivador** — o eixo da interview que justifica
3. **Prós**
4. **Contras** — inclui o invariante que tensiona, se houver
5. **Score 0-10** — rubrica abaixo
6. **Visual** — árvore de arquivo ou grafo ASCII de quem chama quem
7. **CI** — a resposta do eixo 17 pra esta opção, uma de três: o job e o step existentes
   que já a provam (o caso comum — só o `schema` cobre frontmatter, classe, seções e
   entrada de `export` de uma skill); o teste ou step novo que ela precisa, com arquivo,
   workflow e job; ou "nada testável, porque …". "Depois" não é resposta

E no final, uma **tabela de referências**: para cada decisão, a fonte concreta
(`@claude-help.md` § N, `@CLAUDE.md` invariante N, ou arquivo do repo que serve de
precedente). Afirmação sobre o runtime sem fonte é decoração — corta.

### Rubrica de score (0-10)

Oito critérios, peso igual, 1.25 pontos cada. Arredonda pro inteiro mais próximo e
mostra o que custou pontos:

| # | Critério | Perde ponto quando |
|---|---|---|
| 1 | Adequação de forma | Tabela da § 2 (ou § 2.1 pra MCP) aponta pra outra forma |
| 2 | Conformidade com invariantes | Tensiona um dos onze; violar um trava o score em ≤ 4 |
| 3 | Custo de contexto | Conhecimento raramente usado fica sempre carregado — pra Forma 6, os nomes das tools de um servidor MCP carregam em todo startup, use a sessão ou não |
| 4 | Enforcement | Depende de persuasão onde garantia estava disponível |
| 5 | Custo de manutenção | Adiciona peça ou indireção sem ganho proporcional |
| 6 | Precedente no repo | Nenhuma forma similar já em uso; design novo |
| 7 | Propagação completa | Não fecha routing, `00-index`, o manifesto `export`, `skill_classes`/`agent_classes`, a resposta de CI de `ci-coverage.md`, ou registro de decisão da § 9 |
| 8 | Superfície de confiança | Concede capacidade maior do que a tarefa precisa — servidor MCP que lê arquivos e chama API arbitrária, agent com `permissionMode: bypassPermissions`, skill com `allowed-tools` sem escopo onde uma regra mais estreita bastaria |
| 9 | Custo de rodar sempre | Frequência do evento × custo por disparo, e se `if`/`matcher` estreita antes de subir processo. Um `PostToolUse` sem filtro paga uma JVM em toda edição do repo; um `SessionStart` paga uma vez. Perde ponto também quando o hook bloqueia (exit 2) num julgamento que às vezes vai estar errado — aí o custo não é milissegundo, é uma pessoa travada |

Violar invariante trava o score em **≤ 4**, independente do resto. Score **≥ 8** é
recomendação; **5 a 7** é viável com ressalva escrita; **≤ 4** só aparece pra registrar
por que foi rejeitado.

## Fase 3.5 · Salva o rascunho da decisão

O que a Fase 3 produziu — opções, scores, alternativas rejeitadas, tabela de
referências — evapora no fim da sessão. Sem registro, a mesma interview de vinte eixos
recomeça do zero daqui a seis meses.

**Quando salvar arquivo.** Só se pelo menos um for verdade:

- Duas ou mais opções pontuaram ≥ 5 — houve escolha real.
- A opção de maior score tensiona um invariante do `@CLAUDE.md`.
- Eixo 8 respondeu "ambos" — a peça também vai pro projeto gerado.
- Eixo 13 respondeu "ambos" — o servidor MCP é declarado no `.mcp.json` deste repo e no
  template do `project-bootstrap`. A duplicação é deliberada (invariante 9), e sem
  registro ninguém daqui a seis meses distingue isso de drift.
- A opção aprovada é Forma 7c ou Forma 8 — **sempre**, sem exceção. Um modo novo no
  `ArchHook.java` é infraestrutura: não tem corpo markdown pra carregar uma seção
  `## Why this is <form>`, muda o que toda sessão passa a exigir, e quem ler depois sem
  conseguir reconstruir por que bloqueia vai apagá-lo na primeira vez que ele incomodar.
  Uma linha de `permissions.deny` é uma linha de JSON com o mesmo problema e nenhum lugar
  pra se explicar.

Nenhum desses → nenhum arquivo é salvo. A justificativa vive na seção `## Why this is
<form>` do arquivo que a Fase 4 cria, e isso basta.

**Como.** Gera a partir de `templates/decision.md.example` para
`.claude/decisions/NNNN-<slug>.md`, `NNNN` = maior número existente + 1, lido do
inventário injetado no topo — nunca de memória. Salva com **todas** as opções e sem a
linha `State`: a decisão ainda não foi tomada.

**Para aqui. Espera aprovação explícita.** "Ficou bom" não é aprovação de qual opção.

Se o usuário rejeita tudo, fecha o rascunho com `State: rejected — no option approved`
e para. Não apaga: o valor está em evitar repetir a mesma interview.

## Fase 4 · Escreve — só depois de aprovação

1. Gera a partir do exemplar em `templates/` que casa com a forma aprovada. Pra Forma
   6a, a referência de formato é o próprio `templates/mcp.json.example` desta skill:
   funde o servidor novo no `.mcp.json` alvo — a raiz deste repo, e/ou
   `project-bootstrap/templates/mcp.json.example`, conforme o eixo 13 — nunca
   sobrescreve servidor já declarado lá. Escreve também um doc no formato
   `templates/mcp-setup.md.example` (o `MCP-SETUP.md` deste repo, ou o do projeto,
   conforme o eixo 13) listando as variáveis de ambiente que o servidor novo precisa, se
   houver.
2. Frontmatter: só campos nativos, lista em `references/frontmatter-fields.md`. Campo
   inventado é silenciosamente ignorado pelo runtime — parece comportamento, é
   decoração. Pros campos de um servidor da Forma 6a, a lista equivalente é
   `references/mcp-fields.md` — mesma disciplina, o bloco `mcp` de
   `.claude/schemas/extensions.json` é quem de fato é dono.
3. Nenhum `metadata:` no frontmatter. Ownership, `reads`, handoff vão na seção
   `## Contract` do corpo.
4. Boilerplate de código vai para `templates/<name>.example` dentro da skill que o
   emite, nunca colado no corpo — invariante 3.
5. **Nenhum segredo literal, nunca, em um `.mcp.json` que esta skill escreve ou edita**
   — invariante 11. Só `${VAR}` / `${VAR:-default}`, `oauth`, ou `headersHelper`. Se o
   eixo 12 da interview nomeou um token estático, escreve o placeholder `${VAR}` e passa
   o nome da variável pro doc de setup do passo 1 — nunca o valor, nem "temporariamente".
6. **Seção `## Why this is <form>` no corpo do arquivo criado, sempre.** Três frases: a
   forma escolhida, o eixo da interview que motivou, e a forma rejeitada mais próxima
   com o motivo. É o único registro que viaja com o arquivo — sobrevive a quem nunca
   leu `.claude/decisions/`, e à cópia pro projeto gerado. Precedente:
   `.claude/agents/project-initializer.md`, seção "Why this is an agent and not a
   skill". Pra Forma 5, e pro `.mcp.json` da Forma 6a (JSON puro, sem lugar pra prosa),
   não há corpo onde colocar: a justificativa vive só no registro de decisão, e se não
   houver registro, na mensagem de commit. Forma 6b tem corpo — é o mesmo arquivo do
   agent cuja seção "Why this is an agent" já cobre isso; adiciona uma linha nomeando
   qual servidor e por que fica restrito só àquele agent.
7. **Propaga.** Arquivo novo que ninguém referencia não é encontrado:

   | Você criou | Também atualiza |
   |---|---|
   | Skill | Tabela de routing do `@CLAUDE.md`, **e** uma classe em `skill_classes` de `@.claude/schemas/extensions.json`: a lista `skills`, um `overrides.<skill>.write_allow` quando o default da classe não serve, e a linha `**Class:** <c>` no corpo. O `schema` falha pelo nome de uma skill sem classe, e cobra as seções que a classe exige |
   | Skill de desenvolvimento (válida dentro do projeto gerado) | `export.skills.include` de `@.claude/schemas/extensions.json` — o `schema` falha numa skill que não está nem em `include` nem em `exclude` |
   | Rule | `@.claude/rules/00-index.md` (tabela de rules escritas; remove de "planned"). Toda rule viaja por default; uma rule com território de pacote precisa também de entrada em `export.derived_paths`, e o `schema` falha sem ela |
   | Agent | Tabela de routing do `@CLAUDE.md`, se invocável por nome; `export.agents.include` ou `exclude`; **e** uma classe em `agent_classes` de `@.claude/schemas/extensions.json`: a lista `agents`, um `overrides.<agent>.write_allow` quando o default da classe não serve, a linha `**Class:** <c>` no corpo, e `**Executor:** yes` quando a classe concede `executor: true`. O `schema` cobra as seções e os campos de frontmatter que a classe exige — `model` e `tools` sempre |
   | Seção do `CLAUDE.md` | Nada mais — mas confirma que o total continua abaixo de ~200 linhas |
   | Servidor MCP, só este repo (eixo 13 = "meta-repo") | `.mcp.json` na raiz; o doc de setup; linha de routing do `@CLAUDE.md`, se nenhuma já cobre |
   | Servidor MCP, também o projeto gerado (eixo 13 = "ambos") | Tudo acima, **mais** `project-bootstrap/templates/mcp.json.example` e seu próprio doc de setup — ambos já nomeados em `export.optional_copy`, então não há mais nada a ligar |
   | Hook, só este repo (eixo 8 = "meta-repo") | `.claude/settings.json`; parte 2 de `docs/pt-br/11-pitfalls.md` e `docs/en/11-pitfalls.md`, se o hook bloqueia algo que um leitor chamaria de bug — nunca `@CLAUDE.md` § Known pitfalls, que guarda só o que morde sem hook por trás (decisão 0090) |
   | Hook, também o projeto gerado (eixo 8 = "ambos") | Tudo acima, **mais** `project-bootstrap/templates/settings.json.example`. Um modo Forma 7c não precisa de nada além: o modo `export` copia `ArchHook.java` e `schemas/extensions.json` inteiros |
   | Modo de hook (Forma 7c) | A tabela de modos do `@CLAUDE.md` § Commands, e o relatório do `doctor` se o modo tem estado que vale reportar |
   | Regra de `permissions` (Forma 8) | `.claude/settings.json`; o template do projeto gerado quando o eixo 8 = "ambos". Um `deny` também entra na parte 2 de `docs/pt-br/11-pitfalls.md` e `docs/en/11-pitfalls.md` — uma tool que recusa em silêncio parece tool quebrada |

   Skill de criação (só útil antes do projeto existir) vai em `export.skills.exclude`,
   como `project-bootstrap` e `init-project`. Isso é declarado explicitamente no
   relatório.

   **Delegação, e só neste caso.** Se o eixo 8 ou o eixo 13 respondeu "ambos", a
   propagação cresce — o bloco `export` mais o `templates/` de `project-bootstrap`.
   Aí, delega **só este passo 7, nenhum outro** pro agent genérico com
   `model: sonnet`, passando o caminho do registro da Fase 3.5 e a lista exata de
   arquivos a tocar. São edições mecânicas de tabela com destino fixado por escrito.
   Sem registro salvo, não delega: o subagent não vê a conversa, e a interview é o que
   justifica cada linha. **Nunca delega propagação de Forma 7 ou Forma 8**, mesmo com
   eixo 8 "ambos": o segundo arquivo é um registro de hook que executa, e um `if` ou
   `matcher` errado copiado pro template do projeto gerado embarca em todo projeto criado
   depois.

   Passos 1 a 6, e o passo 8, **nunca** são delegados. Escrever a `description` decide se
   a skill dispara, e o `## Contract` decide ownership — isso é design, não transcrição.
   Pra MCP a mesma divisão vale: qual servidor adicionar, o formato da credencial, e o
   destino são design; copiar uma entrada de `.mcp.json` já aprovada pra um segundo
   arquivo é a única parte mecânica.

8. **Cobertura de CI — escreve o check que o item CI da opção aprovada nomeou.** O mapa
   de o que já cobre cada forma, e de onde entra um check novo, é
   `references/ci-coverage.md`:

   - **Um check existente já cobre** — não escreve nada; nomeia o job e o step no registro.
   - **Um teste existente precisa de um caso** — o caso entra naquele
     `.claude/.ci/<Name>Test.java` (classe nova no `SkillTerritoryTest`, agent novo no
     `AgentTerritoryTest`, grafia nova no `BashGuardTest`).
   - **Teste novo** — `.claude/.ci/<Name>Test.java`, Java single-file como os vizinhos,
     disparando `java -jar .claude/hooks/ArchHook.jar` quando exercita um modo (decisão
     0084). Registrado como step do job que o mapa indica em `validate.yml`; um check que
     precisa de rede ou de minutos vai no `templates.yml` filtrado por paths, com os paths
     nas **duas** listas de gatilho (decisão 0099).
   - **Uma afirmação sobre arquivos que o `extensions.json` consegue carregar como dado** —
     estende o `schema`, não um `grep` em YAML: step de YAML nunca roda dentro de um
     projeto gerado.
   - **Eixo 8 = "ambos"** — também `project-bootstrap/templates/ci.yml.example` e
     `ci-gradle.yml.example`, e **só** com o que existe dentro do projeto gerado: nada de
     `.claude/.ci/` viaja.
   - **Nada testável** — o motivo vai em `## CI coverage` do registro.

   Prova o check antes de reportar: verde na árvore atual, depois vermelho uma vez com o
   defeito injetado, falhando pelo nome. Teste que nunca falhou não prova nada. **Nunca é
   delegado**, nem com eixo 8 "ambos": quais casos o teste tem é design.

   Motivo: os guard modes das decisões 0063–0077 rodaram em toda sessão sem CI até a 0084,
   e dois defeitos de template só foram pegos à mão até a 0099 — nos dois casos o CI chegou
   numa revisão depois da peça. Design: `.claude/decisions/0102-ci-coverage-in-architect-designer.md`.
9. Se salvou rascunho na Fase 3.5, promove: preenche `Decision`, `State` (aprovado por
   quem, em que data), a tabela `Propagation` com os arquivos que os passos 7 e 8 tocaram,
   e `## CI coverage` com o resultado do passo 8 — job e step que rodam o check, e as
   execuções verde e vermelha.
10. Roda `claude plugin validate .claude/skills` e reporta a saída sem reescrevê-la.
    **Não cobre `.mcp.json`** — roda `java .claude/hooks/ArchHook.java schema` também,
    sempre que o passo 1 tocou um `.mcp.json`. Se o passo 8 escreveu ou mudou um teste,
    roda ele de novo aqui — `java .claude/.ci/<Name>Test.java`.

## Fase 5 · Relatório

Arquivos criados, arquivos alterados, caminho do registro de decisão (ou a frase
explicando por que não houve), a saída de `validate`, a **linha de CI** — o job e o step
que cobrem a peça, o teste que o passo 8 escreveu com as execuções verde e vermelha, ou o
motivo de nada ser testável —, e o aviso de restart se tocou `settings.json` — só é lido
no início da sessão.

## Campos de frontmatter — resumo por forma

Fonte completa: `references/frontmatter-fields.md`, derivada de
`.claude/schemas/extensions.json` (dono único da lista — `ArchHook.java schema` é quem
lê e bloqueia).

### Skill

| Campo | Para quê |
|---|---|
| `name` | Identificador. Minúsculo e hífens |
| `description` | **Decide se a skill é invocada.** Caso de uso concreto primeiro, frases-gatilho depois. Truncado em 1536 caracteres na listagem |
| `when_to_use` | Frases-gatilho extras |
| `argument-hint` | Dica de autocomplete, ex. `"[usecase-name]"` |
| `arguments` | Nomes posicionais, pra substituição `$name` |
| `disable-model-invocation` | `true` = só o usuário invoca, via `/name`. Pra efeitos colaterais |
| `user-invocable` | `false` = só o modelo invoca; some do menu `/` |
| `allowed-tools` | Pré-aprova tools **durante o turno** que invoca a skill |
| `disallowed-tools` | Remove tools do pool enquanto a skill está ativa |
| `model` · `effort` | Override de modelo/effort enquanto a skill está ativa |
| `paths` | Globs que limitam ativação automática |
| `context: fork` | Roda a skill num subagent isolado |
| `agent` | Qual tipo de subagent usar com `context: fork` |
| `background` | Com `fork`, `false` = espera o resultado no mesmo turno |
| `hooks` | Hooks registrados ao invocar a skill |

O **nome da pasta** vira o comando: `.claude/skills/arch-doctor/` → `/arch-doctor`. O
`name` do frontmatter segue a pasta; divergir é confusão garantida no diagnóstico.

### Subagent

| Campo | Obrigatório | Para quê |
|---|---|---|
| `name` | ✅ | Minúsculo e hífens. Não pode conter `:` |
| `description` | ✅ | Quando delegar. Curto — soma das descriptions tem teto de 15k tokens |
| `tools` | ➖ | Lista separada por vírgula. Sem o campo, herda tudo |
| `disallowedTools` | ➖ | Remove da lista herdada. Aceita `mcp__*` |
| `model` | ➖ | `sonnet`, `opus`, `haiku`, ID completo, ou `inherit` |
| `permissionMode` | ➖ | `default`, `acceptEdits`, `auto`, `dontAsk`, `bypassPermissions`, `plan` |
| `maxTurns` | ➖ | Turnos máximos antes de parar |
| `skills` | ➖ | Skills pré-carregadas **por completo** no início — nunca uma com `disable-model-invocation: true`, que é pulada sem aviso (decisão 0077) |
| `mcpServers` | ➖ | MCP servers só pra este subagent |
| `hooks` | ➖ | Hooks só enquanto o subagent roda |
| `memory` | ➖ | `user`, `project`, ou `local` |
| `background` | ➖ | `true` mantém em background |
| `effort` | ➖ | `low` … `max` |
| `isolation` | ➖ | `worktree` = roda em git worktree isolada |
| `color` | ➖ | Cor de exibição |

Atenção ao **camelCase** aqui (`disallowedTools`, `permissionMode`, `maxTurns`) contra
o **kebab-case** de skills (`disallowed-tools`, `disable-model-invocation`). Trocar as
convenções produz campo silenciosamente ignorado — o modo de falha mais caro desta
lista.

### Rule

| Campo | Para quê |
|---|---|
| `paths` | Globs que fazem a rule auto-carregar quando os arquivos casados são tocados |
| `status` | `active` vale agora · `draft` é proposta, não aplica · `deprecated` só pra ler código antigo |

Rule sem `paths` **não** é "só por citação": carrega no launch, em toda sessão. Toda rule
declara `paths`, com o glob mais estreito que a contém — Java é `**/src/**/*.java`, nunca
`**/*.java`, que casa com `.claude/hooks/ArchHook.java`. A citação explícita
(`@.claude/rules/<file>.md`) vem por cima, no design antes do arquivo existir.

### `CLAUDE.md`

Sem frontmatter. Só conteúdo. `@path/file.md` importa outro arquivo — mas o import
carrega no início da sessão, então **não economiza contexto**: é organização, não
otimização.

### O que nunca vai em frontmatter

**`metadata:`** e tudo que ele carregava antes — ownership, `reads`, handoff,
contratos. Não é campo nativo: custa tokens toda invocação e não obriga nada. Nesse
repo esse conteúdo vive na seção `## Contract` do **corpo** do arquivo, onde o modelo
lê como instrução.

## Anti-padrões — rejeitados na Fase 2

| # | Anti-padrão | Sinal | Redireciona pra |
|---|---|---|---|
| 1 | Agent onde skill bastava | Nenhum dos três motivos da Forma 3 | Skill |
| 2 | Prosa onde tinha que ser hook | "Sempre rode X antes de terminar" repetido | Hook — fora de escopo |
| 3 | `CLAUDE.md` obeso | Passou de ~200 linhas | Extrai pra `rules/` com `paths` |
| 4 | Rule que fala de skills | Quebra invariante 1 | Move o procedimento pra skill |
| 5 | Rule duplicada | Mesmo tema em dois arquivos | Dono único + citação |
| 6 | `commands/` novo | Invariante 4 | Skill + `disable-model-invocation` |
| 7 | Frontmatter inventado | Campo que o runtime ignora | `frontmatter-fields.md` |
| 8 | Código no corpo da skill | Boilerplate colado em markdown | `templates/*.example` |
| 9 | Peça construída por antecipação | Sem sintoma no eixo 1 da interview | Não criar nada |
| 10 | Skill de criação copiada pro projeto gerado | Fora do passo 6.7 | Deixa de fora, e diz isso |
| 11 | MCP onde um CLI já resolve | `gh`/`psql`/`aws`/etc. já instalado | Não criar nada + `permissions.allow` (§ 2.1) |
| 12 | Segredo literal no `.mcp.json` | Token, chave ou senha escrito em `headers`/`env` | `${VAR}`, `${VAR:-default}`, `oauth`, ou `headersHelper` — invariante 11 |
| 13 | Servidor MCP sem uso observado | Sem sintoma no eixo 1 da interview; o nome sozinho já custa contexto em todo startup | Não criar nada |
| 14 | Mesmo servidor duplicado entre escopos sem motivo de destino | Mesmo nome no `.mcp.json` e no `mcpServers` de um agent, ou tanto no `.mcp.json` deste repo quanto no template do projeto, sem uma resposta "ambos" do eixo 13 por trás | Destino único, ou registra o "ambos" na decisão |

## Exemplo de invocação (fictício)

```
/claude-code-architect-designer o modelo esquece de rodar os testes de contrato antes de reportar a feature pronta
```

Interview (resumida — perguntas reais via `AskUserQuestion`, 2-3 chamadas):

| Eixo | Resposta do usuário |
|---|---|
| 1. Sintoma | Aconteceu 3x no `/new-feature`: reporta "pronto" sem `./mvnw verify` ter rodado |
| 2. Gatilho | Fim do pipeline `/new-feature`, não um comando novo |
| 3. Frequência | Toda vez que `/new-feature` termina |
| 4. Território | `**/adapter/in/rest/**`, `**/domain/**` — onde teste de contrato mora |
| 5. Natureza | Passo de procedimento ("antes de reportar, rode X"), não fato declarativo |
| 6. Isolamento | Não precisa de tools restritas nem modelo diferente |
| 7. Obrigatoriedade | Pode falhar — é convenção de processo, não build gate |
| 8. Destino | Só este repo — é sobre como a skill `new-feature` se comporta |
| 9. Integração | Toca o corpo de `.claude/skills/new-feature/SKILL.md` |
| 10. Custo de errar | Baixo-médio — feature "pronta" sem prova de que passa |

Classificação: eixo 7 responde "não, pode falhar" → não é hook. Eixo 5 é procedimento,
não fato → não é rule nem `CLAUDE.md`. É correção de um passo que já existe dentro de
uma skill existente, não peça nova.

Proposta devolvida (resumo):

1. **"Não criar nada — editar `new-feature/SKILL.md`"** — Score 9. Motivador: eixo 5 +
   9 (peça já existe). O passo final da skill já reporta "pronto"; falta uma linha
   explícita "rode `./mvnw verify` antes de declarar pronto, cole a saída". Prós: zero
   peça nova, zero propagação. Contras: nenhum.
2. **"rule `contract-tests-gate.md`"** — Score 3. Motivador: eixo 4 (território
   existe). Contras: quebra invariante 1 se mencionar a skill `new-feature` pra dizer
   quando rodar; sem mencionar, vira regra solta sem gatilho de execução — persuasão
   fraca pra algo que devia ser passo de pipeline.

Com score 9 vs 3, e sem "escolha real" (uma opção só claramente viável), a Fase 3.5 não
salva registro — a Fase 4 edita `new-feature/SKILL.md` direto e a seção `## Why this is
<form>` nem se aplica (edição, não peça nova).

## Contract da skill

**Lê** `@claude-help.md`, `@CLAUDE.md` (os onze invariantes), `@docs/pt-br/11-pitfalls.md`
(toda armadilha silenciosa, do runtime e do repositório), `@.claude/rules/00-index.md`,
`@.claude/blueprints/_schema.md` quando a decisão toca blueprints, e o inventário
injetado no topo. Lê o `references/` desta própria skill antes de classificar — a
matriz é deliberadamente não embutida no corpo.

**Escreve** `.claude/skills/**`, `.claude/agents/**`, `.claude/rules/**`, o
`CLAUDE.md` raiz **deste repositório**, e o `.mcp.json` na raiz deste repo (Forma 6a).
Pro item CI da forma aprovada (passo 8 da Fase 4), também os testes em `.claude/.ci/**`,
os jobs de `.github/workflows/**`, as linhas correspondentes de `docs/*/07-ci-validate.md`
e, com eixo 8 "ambos", `project-bootstrap/templates/ci.yml.example` e
`ci-gradle.yml.example`. Só depois de aprovação explícita.

**Também escreve** `.claude/decisions/NNNN-<slug>.md` — e este é o único caminho que
toca *antes* da aprovação, como rascunho da Fase 3.5. É dona exclusiva do diretório:
nenhuma outra peça escreve lá, e nada dentro dele é rule.

**Não escreve** `.claude/settings.json`, `.claude/hooks/**`, `~/.claude.json`, nem código Java de projeto. Não cria `.claude/commands/`. **Nunca
escreve um segredo literal** no `.mcp.json` — invariante 11; uma credencial estática do
eixo 12 vira placeholder `${VAR}` mais uma linha no doc de setup, nunca um valor.

**Delega** no máximo o passo 7 da Fase 4 (propagação), e só quando o eixo 8 ou o eixo 13
é "ambos" e um registro de decisão foi salvo. Classificar, propor, escrever o corpo e
escrever o check de CI (passo 8) ficam sempre nesta thread — o subagent não recebe a conversa, e a interview é o coração
da tarefa.

**Fica fora do projeto gerado.** É skill de criação, como `project-bootstrap` e
`init-project`: quem clona um projeto já gerado não tem extensões pra desenhar. O mesmo
vale pra `.claude/decisions/` — registra decisões sobre este meta-repositório.

## Referências

| Onde ver mais | O quê |
|---|---|
| `.claude/skills/claude-code-architect-designer/SKILL.md` | Corpo completo da skill, as cinco fases |
| `.claude/skills/claude-code-architect-designer/references/decision-matrix.md` | Tabela de decisão completa (§ 2 e § 2.1), rubrica de score, anti-padrões |
| `.claude/skills/claude-code-architect-designer/references/frontmatter-fields.md` | Lista completa de campos nativos por tipo de arquivo |
| `.claude/skills/claude-code-architect-designer/references/mcp-fields.md` | Campos reconhecidos de um servidor em `.mcp.json` (Forma 6a/6b) |
| `.claude/skills/claude-code-architect-designer/references/ci-coverage.md` | Eixo 17 — o que já prova cada forma no CI, e onde entra um check novo |
| `@.claude/decisions/0102-ci-coverage-in-architect-designer.md` | Decisão que trouxe a cobertura de CI pra dentro do procedimento |
| `.claude/skills/claude-code-architect-designer/templates/` | Exemplares usados na Fase 4 — `SKILL.md.example`, `SKILL.command.md.example`, `agent.md.example`, `rule.md.example`, `claude-md-section.md.example`, `decision.md.example`, `mcp.json.example`, `mcp-setup.md.example` |
| `.claude/decisions/README.md` | Regras do diretório de registros de decisão |
| `@CLAUDE.md` | Os onze invariantes usados como veto na Fase 2 |
| `@.claude/decisions/0033-mcp-in-architect-designer.md` | Decisão que trouxe MCP (Forma 6a/6b) pra dentro desta skill |
| `@.claude/decisions/0034-git-publish-skill.md` | Exemplo de decisão Forma 1 recente, com a ressalva Forma1-vs-Forma2 do D17 aplicada a um cenário novo |
| [01-tipos-de-arquivo.md](01-tipos-de-arquivo.md) | Como cada forma funciona segundo o runtime do Claude Code |
