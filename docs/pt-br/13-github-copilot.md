# GitHub Copilot — o que do `.claude/` ele lê, o que precisa de tradução, o que não existe lá

Esta página responde a uma pergunta só: **um projeto gerado pelo Nerviz, aberto por quem usa
GitHub Copilot em vez de Claude Code, recebe o quê?** Ela é a base da fase 8 do `roadmap.md`
(projeto gerado multi-agente) e a entrada do `/claude-code-architect-designer` quando ele
desenhar o `AGENTS.md`. Ela registra fatos; não decide nada. A origem do problema está em
`.claude/lessons-learned/lessons-learned-019.md`.

Escopo: **o projeto gerado**. O gerador (`/init-project`, `project-bootstrap`,
`claude-code-architect-designer`, `arch-adopt`) continua só Claude Code, por decisão
registrada no `roadmap.md` § Multi-agent.

Tudo aqui foi lido na documentação oficial em **2026-10-11** (fontes no fim). O Copilot muda
mensalmente: quem for usar uma linha desta página numa decisão reconfere a fonte dela antes.
O que a documentação não afirma está marcado **não verificado** — e, por regra deste
repositório, uma garantia não verificada conta como ausente.

---

## 1. Vocabulário: as superfícies do Copilot

"Copilot" é cinco runtimes com leitores diferentes. Uma afirmação sobre o Copilot sem dizer
qual superfície está incompleta.

| Superfície | O que é | Lê o quê do repositório |
|---|---|---|
| **Copilot CLI** | Terminal, equivalente direto do Claude Code. GA desde 2026-02 | Mais do que todas as outras: instruções, `AGENTS.md`, `CLAUDE.md`, `.claude/CLAUDE.md`, `.claude/skills/`, hooks de `.claude/settings.json`, `.mcp.json`, agents, skills, plugins |
| **Copilot cloud agent** (antes "coding agent") | Roda num runner do GitHub Actions a partir de uma issue ou PR; sem usuário presente | `AGENTS.md`, `CLAUDE.md` da raiz, `.github/copilot-instructions.md`, `.github/instructions/`, `.github/agents/`, skills, **só** `.github/hooks/*.json`; MCP só pela UI de settings do repositório |
| **VS Code** (chat, agent mode) | Extensão; hoje com dois harnesses, "Local agent" (sendo removido) e "Agent Host" | Instruções, `AGENTS.md` (raiz; aninhado experimental), `CLAUDE.md`, skills de `.claude/skills/` e `~/.claude/skills/`, agents, hooks (`.github/hooks/`, `.claude/settings.json` via `chat.useClaudeHooks`), `.vscode/mcp.json` e `.mcp.json` |
| **Code review** | Revisão automática de PR | `.github/copilot-instructions.md`, `AGENTS.md`, `CLAUDE.md`, `.github/instructions/` com `excludeAgent`, skills |
| **Chat no GitHub.com, JetBrains, Visual Studio, Xcode, Eclipse** | Chat no site e nas IDEs | `.github/copilot-instructions.md`; `AGENTS.md` só pelo cloud agent; agents e skills em preview |

Para o Nerviz, o CLI é a superfície que mais importa: é o único Copilot em que o `ArchHook`
chega a rodar.

---

## 2. Como cada peça funciona no Copilot

### 2.1 Instruções sempre ativas

- **`.github/copilot-instructions.md`** — um arquivo, sem frontmatter, lido por todas as
  superfícies. Entra em toda requisição feita no contexto do repositório. Nenhum limite de
  tamanho documentado; a orientação oficial é "até 1.000 linhas" e "instruções muito longas
  podem ser ignoradas em parte".
- **`AGENTS.md`** — raiz e **aninhados**: "o `AGENTS.md` mais próximo na árvore de diretórios
  tem precedência". Lido pelo cloud agent, code review, VS Code chat (raiz por padrão;
  aninhados atrás de `chat.useNestedAgentsMdFiles`, experimental, que só **lista** os
  arquivos para o agente abrir) e CLI. **Não** lido pelo chat do GitHub.com nem pelo Visual
  Studio.
- **`CLAUDE.md` e `GEMINI.md`** — o cloud agent aceita "um único `CLAUDE.md` ou `GEMINI.md`
  na raiz" como alternativa ao `AGENTS.md`. O CLI lê `CLAUDE.md` **e** `.claude/CLAUDE.md`.
  O VS Code lê `CLAUDE.md`, `.claude/CLAUDE.md`, `~/.claude/CLAUDE.md` e `CLAUDE.local.md`
  (`chat.useClaudeMdFile`, padrão `true`). **Nenhuma superfície documenta ler um `CLAUDE.md`
  de módulo** (aninhado): o aninhamento que o Copilot conhece é o do `AGENTS.md`.
- **Imports `@caminho`** — só o **CLI** expande, e só dentro de `.github/copilot-instructions.md`,
  `AGENTS.md` e `CLAUDE.md`; recursivo; caminho relativo dentro do repositório; "não expandido
  em `GEMINI.md` nem em `*.instructions.md`". Cloud agent e VS Code: nada documentado — no
  VS Code um link Markdown só carrega o alvo com `chat.includeReferencedInstructions`,
  padrão `false`.
- **Merge e precedência** — "todos os conjuntos relevantes são enviados juntos": pessoal >
  repositório > organização; dentro do repositório, path-specific > repo-wide > agent. O CLI
  "combina", remove duplicatas idênticas e "não define ordem geral de precedência". A
  orientação oficial dos dois lados é a mesma: não escrever instruções conflitantes.

### 2.2 Normas por caminho — `.github/instructions/*.instructions.md`

Equivalente do `paths` das `rules/`. Frontmatter: `applyTo` (globs separados por vírgula numa
**string**, não lista YAML: `"**/*.java,**/*.kt"`) e `excludeAgent` (`"code-review"` ou
`"cloud-agent"`). O VS Code aceita ainda `name` e `description`. O arquivo inteiro entra quando
o glob casa com um arquivo que o agente lê, cria ou edita; `applyTo: "**"` vale sempre.
No GitHub.com só o cloud agent e o code review honram; nas IDEs, o chat também; o CLI também.
**`.claude/rules/` não é lido por nenhuma superfície.**

### 2.3 Skills — `SKILL.md`

Mesma especificação (agentskills.io). Diretórios do projeto: `.github/skills/`,
**`.claude/skills/`**, `.agents/skills/` — todas as superfícies. Pessoais: `~/.copilot/skills/`,
`~/.agents/skills/`; `~/.claude/skills/` só na documentação do VS Code.

Frontmatter que a documentação do GitHub nomeia: `name` (obrigatório, kebab, igual ao
diretório), `description` (obrigatório), `license`, `allowed-tools` (pré-aprova ferramentas:
"sem a confirmação a cada vez"). O VS Code documenta ainda `disable-model-invocation`,
`user-invocable`, `argument-hint` e `context: fork` (experimental). Limites: `name` ≤ 64,
`description` ≤ 1024; "nome com caractere inválido faz a skill falhar em silêncio".
Comportamento diante de frontmatter desconhecido: **não documentado** — no Claude Code
também é silêncio, e é por isso que o `schema` existe.

Invocação: o modelo decide pela `description` ("não garante que invoque"), ou o usuário digita
`/nome texto`. O texto depois do nome é **anexado ao prompt**; não existe `$ARGUMENTS`. Não
existe injeção `` !`comando` ``: o corpo chega literal.

### 2.4 Agents — `.github/agents/*.agent.md`

Frontmatter oficial: `name`, `description` (obrigatório), `tools` (lista ou string; omitido ou
`["*"]` = todas; `[]` = nenhuma; "nomes não reconhecidos são ignorados"), `model` (só nas
IDEs; o GitHub.com ignora), `target` (`vscode` | `github-copilot`), `disable-model-invocation`,
`user-invocable` (`false` = só como subagente), `mcp-servers` (GitHub.com), `metadata`. O VS Code
soma `agents` (allow-list de subagentes), `handoffs`, `hooks`. O CLI soma
`include-custom-instructions: true` — **por padrão um subagente não recebe as instruções do
repositório**. Corpo ≤ 30.000 caracteres. Para o GitHub.com, o arquivo precisa estar na
branch padrão. Os antigos `.github/chatmodes/*.chatmode.md` foram renomeados para isto.

Diretórios: `.github/agents/` (projeto), `~/.copilot/agents/` (usuário), `/agents/` no
`.github` ou `.github-private` da organização. **`.claude/agents/` não aparece na documentação
do CLI nem do cloud agent** (uma nota do VS Code cita ler `.claude/agents` no formato Claude —
não verificado fora do VS Code).

Delegação pelo modelo: existe no CLI ("pode escolher usar um dos seus custom agents … num
subagente com janela de contexto própria") e no VS Code (`runSubagent`); **não** no GitHub.com.
O CLI traz subagentes nativos (Explore, Task, General-purpose, Code-review, Plan), `/fleet`
para paralelismo, `/plan` e plugins (`copilot plugin install`, que lê `.claude-plugin/plugin.json`).

### 2.5 Hooks

"Suportados em duas superfícies: Copilot CLI e Copilot cloud agent." O VS Code tem hooks
próprios (`chat.useHooks`) e lê o formato Claude com `chat.useClaudeHooks`.

- **Onde**: `.github/hooks/*.json` (`{"version":1,"hooks":{…}}`). O cloud agent lê **só** isso,
  e só da branch padrão — "não carrega `settings.json`". O CLI lê também `.github/copilot/settings.json`,
  `~/.copilot/hooks/`, política de máquina em `/etc/github-copilot/policy.d/` e **"os arquivos
  cross-tool `.claude/settings.json` e `.claude/settings.local.json` do repositório"**.
- **Eventos (camelCase)**: `sessionStart`, `sessionEnd`, `userPromptSubmitted`,
  `userPromptTransformed`, `preToolUse`, `postToolUse`, `postToolUseFailure`,
  `permissionRequest`, `agentStop`, `subagentStart`, `subagentStop`, `preCompact`,
  `notification`, `errorOccurred`. No cloud agent, `notification` nunca dispara,
  `permissionRequest` é no-op e `preCompact` só com `trigger: "auto"`.
- **Dois formatos de payload, escolhidos pelo nome do evento.** Em camelCase: `sessionId`,
  `timestamp`, `cwd`, `toolName`, `toolArgs` (string JSON). Em **PascalCase** (`PreToolUse`,
  "como nos plugins do Claude Code"): `hook_event_name`, `session_id`, `cwd`, `tool_name`,
  `tool_input`, com os **nomes de ferramenta do Claude** (`Bash`, `Read`, `Write`, `Edit`,
  `Grep`, `Glob`, `AskUserQuestion`, `Agent`; `Task` aceito no matcher) e a semântica de matcher
  do Claude. `tool_input` é tipado como `unknown`, "argumentos da ferramenta, parseados de JSON
  quando possível" — **o formato interno (`command`, `file_path`, `content`) não está
  documentado**. `SubagentStart` e `StopFailure` **não têm alias PascalCase**.
- **Observado no CLI 1.0.98 (lessons-learned-022 § 6).** O `tool_input` do `Bash` traz `command`;
  o `Read` traz `path`, não `file_path`. O CLI nunca manda `Write`: toda escrita é `apply_patch`,
  entregue como `tool_name: "Edit"` com o `tool_input` sendo a **string** do patch (`*** Begin Patch`,
  `*** Add File:` / `*** Update File:` / `*** Delete File:` / `*** Move to:`), caminho absoluto ou
  relativo à raiz. Desde a decisão 0169, `guard write`, `check`, `format`, `schema` e `audit file`
  traduzem esse patch nos payloads do Claude. O VS Code (`tool_input.input`) e o camelCase
  (`toolArgs`) não são lidos: nenhum registro os entrega hoje.
- **Bloqueio**: stdout com `{"permissionDecision":"deny","permissionDecisionReason":"…"}`
  (plano, não aninhado em `hookSpecificOutput` — a forma aninhada do Claude Code não está
  documentada); `exit 2` sempre nega, "mesmo que o stdout diga allow"; qualquer saída não
  zero nega (fail-closed); timeout libera (fail-open). No cloud agent, `ask` vira `deny`.
  `agentStop`/`subagentStop` aceitam `decision: "block"`.
- **Observado no CLI 1.0.98 (lessons-learned-022, `evidence/D3b-deny-reason.txt`).** No `exit 2` do
  `preToolUse`, o stderr não chega ao modelo, que vê só `hook exited with code 2`. O objeto plano no
  stdout chega como `Denied by preToolUse hook: <motivo>`. Com `hookSpecificOutput` no mesmo stdout,
  volta a mensagem genérica. No `exit 0`, o Copilot também lê a forma aninhada, e `decision: "block"`
  **não** nega. No `postToolUse`, o stderr vira aviso de tela, e `additionalContext` não chega ao modelo.
  Só `decision: "block"` chega, como `Tool result blocked: <motivo>`, e substitui o resultado da tool.
  Desde a decisão 0170, toda recusa do `ArchHook` com `exit 2` repete o stderr no stdout, no formato que
  `settings.deny_output` do `extensions.json` define por evento.
- **Contexto**: `additionalContext` em `sessionStart`, `subagentStart` ("prefixado à
  primeira mensagem do subagente"), `postToolUse` e `notification`.
- **Declaração**: `type` (`command` padrão; `http`; `prompt` só CLI), `bash`/`powershell`/
  `command`, `exec` + `args` (só CLI, sem shell), `cwd`, `env`, `timeoutSec`, `matcher` (regex,
  `^(?:…)$`). `$CLAUDE_PROJECT_DIR`: **não documentado**.

### 2.6 MCP

- **CLI**: `~/.copilot/mcp-config.json`; no repositório, **`.mcp.json`** (procurado do cwd até
  a raiz; vence) e `.github/mcp.json`; aceita o wrapper `mcpServers` ou o mapa direto; carrega
  só depois de o usuário confirmar a confiança na pasta. **Não lê `.vscode/mcp.json`**
  (chave `servers`). Interpolação `${VAR}` em `env`/`headers`: **não documentada** — o texto
  oficial escreve os valores literais.
- **VS Code**: `.vscode/mcp.json` (`servers`, `inputs` com `${input:id}`); também lê `.mcp.json`.
- **Cloud agent**: só pela UI (Settings → Copilot → MCP servers), JSON `mcpServers`, segredos em
  Actions secrets com prefixo `COPILOT_MCP_*`, referenciados como `${VAR:-default}`. Nenhum
  arquivo no repositório.

### 2.7 Permissões

- **CLI**: `--allow-tool`/`--deny-tool` com a gramática `shell(git:*)`, `write(README.md)`,
  `url(https://*.github.com)`; `--allow-all-tools`, `--yolo`; `/permissions`; aprovações salvas
  em `~/.copilot/permissions-config.json`. "Deny sempre vence, mesmo com `--allow-all`." Chave
  `permissions.deny` em arquivo do repositório: **não documentada**.
- **Enterprise**: `managed-settings.json` com `permissions.deny|ask|allow` e regras
  `Bash(...)`/`Shell(...)`, `Read(...)`, `Edit(...)`/`Write(...)`, `Domain(...)` — a mesma
  gramática do `settings.json` do Claude Code — honradas por CLI, VS Code, app e cloud agent.
- **VS Code**: `chat.tools.terminal.autoApprove` e afins; `false` só força a pergunta; bloquear
  exige hook `PreToolUse` com `deny`.
- **Cloud agent**: firewall com allowlist (inclui `repo1.maven.org`, `services.gradle.org`,
  `plugins.gradle.org`, Docker Hub); vale só para processos do Bash, não para MCP nem para o
  `copilot-setup-steps.yml`.

### 2.8 Ambiente do cloud agent e transcrições

- `.github/workflows/copilot-setup-steps.yml`: um job chamado `copilot-setup-steps`, só
  `steps`, `permissions`, `runs-on`, `services`, `snapshot`, `timeout-minutes` (≤ 59); Ubuntu
  x64 ou Windows; branch padrão. É onde se instala o JDK e se aquece o `./mvnw`.
- Transcrição: CLI em `~/.copilot/session-state/<id>/events.jsonl` (formato próprio; contagem
  de tokens **não verificada**). Cloud agent: só na UI. Hooks recebem `transcriptPath` em
  `agentStop`, `subagentStop`, `preCompact`.

---

## 3. Funciona igual — sem transformação

| Peça do projeto gerado | Como chega ao Copilot | Superfícies |
|---|---|---|
| `CLAUDE.md` da raiz | Lido como está, igual a um `AGENTS.md` | CLI, cloud agent, VS Code |
| `.claude/skills/<nome>/SKILL.md` com `name` e `description` | Mesmo arquivo, mesmo diretório, mesma invocação por `/nome` ou pela descrição | Todas |
| `allowed-tools` nas skills | Mesmo campo, mesma semântica (pré-aprovação) | CLI, cloud agent |
| Hooks `PreToolUse`, `PostToolUse`, `UserPromptSubmit`, `Stop`, `SessionEnd`, `PreCompact`, `SubagentStop`, `PostToolUseFailure` de `.claude/settings.json` | Lidos do mesmo arquivo, com os nomes de ferramenta do Claude no `tool_name` e `exit 2` = deny | **CLI** (e VS Code com `chat.useClaudeHooks`); **nunca** o cloud agent |
| `.mcp.json` com `mcpServers` | Mesmo arquivo, mesma chave | CLI, VS Code |
| Gramática `Bash(...)`, `Read(...)`, `Edit(...)` de `permissions.deny` | A mesma, mas só em `managed-settings.json` da empresa — não no arquivo do projeto | Todas, via política |
| Sensores de build: `ArchHook.java doctor gate` no CI (varre todo `.java` atrás de import proibido — decisão 0140), JaCoCo, Checkstyle, `lombok.config`, grafo de módulos no POM, e o ArchUnit depois que o setup do `test-architect` o instala | O build não sabe qual agente escreveu o código | Todas |
| Specs em `docs/use-cases/`, `rules/*.md` abertos por citação | Qualquer agente abre um arquivo citado por caminho | Todas |
| Plugins com `.claude-plugin/plugin.json` | `copilot plugin install` lê o manifesto do Claude | CLI |

---

## 4. Funciona parecido — precisa de tradução ou transformação

| Claude Code | Copilot | O que muda | Superfícies |
|---|---|---|---|
| `CLAUDE.md` **de módulo** (`module.CLAUDE.md.example`) | `AGENTS.md` aninhado ("o mais próximo tem precedência") | Nome do arquivo. Um `CLAUDE.md` aninhado é invisível para o Copilot | Cloud agent, CLI; VS Code só lista |
| `@caminho` dentro do `CLAUDE.md` | `@caminho` idêntico | Só o CLI expande. No cloud agent o import é texto morto; o que ele precisa ler tem de estar no próprio arquivo ou ser citado como caminho a abrir | CLI |
| `.claude/rules/*.md` com `paths: [...]` (lista YAML) | `.github/instructions/*.instructions.md` com `applyTo: "a,b"` (string) | Diretório, sufixo, campo, tipo do valor. `status` não existe. Precisa ser **derivado** do `paths` — mesmo mecanismo de `export.derived_paths` — nunca uma segunda cópia à mão (invariante 2) | Cloud agent, code review, IDEs, CLI |
| `disable-model-invocation: true` nas skills manuais | Mesmo campo | Documentado só no VS Code. No CLI, `/skills info` não o mostra (só `userInvocable`, `invokesModel`), e com ele `/new-feature <pedido>` não iniciou (lessons-learned-022 § 5, causa em isolamento — decisão 0171). O que segura o modelo no CLI é o `guard call`: recusa a chamada `skill` a uma skill manual que o prompt do turno não abriu com `/<nome>` | VS Code sim; CLI pelo `guard call`; cloud nada |
| `context: fork` | `context: fork` | Experimental, só VS Code (hoje nenhuma skill deste repo usa) | VS Code |
| `argument-hint` + `$ARGUMENTS` (18 skills) | `argument-hint` (só exibição) e texto anexado ao prompt | A linha `$ARGUMENTS` fica literal no corpo; o argumento chega como texto solto depois do prompt | Todas |
| `.claude/agents/*.md` com `tools`, `model` | `.github/agents/*.agent.md` com `tools`, `model` | Diretório e sufixo. `model: sonnet`/`opus` são aliases do Claude; o Copilot usa ids próprios e o GitHub.com ignora `model`. `tools: Read, Write, Edit, Bash` — "nomes não reconhecidos são ignorados": verificar o nome de cada ferramenta na superfície | CLI, cloud agent, VS Code |
| `omitClaudeMd: true` (2 agents) | `include-custom-instructions: true` (invertido) | O padrão do Copilot CLI é **omitir**: `java-spring-boot-developer`, que hoje recebe o `CLAUDE.md`, passaria a não receber nada sem o campo | CLI |
| `Agent` tool com `subagent_type` (8 usos em skills) | Delegação automática ou `task`; nome do agente na descrição | Não há chamada explícita por nome garantida; o modelo "pode escolher" | CLI, VS Code; não no GitHub.com |
| `SubagentStart` hook → `hookSpecificOutput.additionalContext` | `subagentStart` (só camelCase) → `additionalContext` plano, matcher em `agentName` | O catálogo de padrões injetado pelo `ArchHook.java context subagent` só dispara se registrado em camelCase, em `.github/hooks/*.json` ou via `.claude/settings.json` com nome camelCase; o payload traz `agentName`, não `agent_type` | CLI; cloud agent |
| `hookSpecificOutput.permissionDecision` (saída aninhada do Claude) | `permissionDecision` no topo | No `exit 2`, só o objeto plano leva o motivo: o aninhado derruba o motivo no Copilot e troca o stderr pelo motivo no Claude Code. Desde a decisão 0170, toda recusa do `ArchHook` escreve o objeto plano no `PreToolUse` e `decision: "block"` no `PostToolUse`, sem mudar o `exit 2` nem o stderr | CLI; cloud agent não verificado |
| `${CLAUDE_PROJECT_DIR}` nos `args` dos hooks e em 15 skills | `args` ignorado; a variável existe no ambiente do hook (7 de 7 capturas, lessons-learned-022 § 2), não documentada | A forma exec rodava um `java` sem argumentos, exit 1, toda chamada negada. Desde a decisão 0168 todo hook é forma shell, `java -jar "${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar" <modo>`, que o shell Unix do CLI expande. **Não verificado no Windows:** lá o `command` vira `powershell`, que não lê `${CLAUDE_PROJECT_DIR}` como variável de ambiente | CLI |
| `tool_input.command`, `tool_input.file_path`, `tool_input.content` (o que `guard` lê) | `tool_input: unknown` | Nomes do Claude no `tool_name`, formato do `tool_input` não documentado. É a lacuna que decide se o `guard` precisa de adaptador de entrada (passo 5 da fase 8) | CLI |
| `.vscode/mcp.json` (`servers`, `inputs`) | `.mcp.json` (`mcpServers`) | Dois formatos; o CLI não lê o do VS Code | — |
| `${VAR}` em `.mcp.json` | `${input:id}` (VS Code), `COPILOT_MCP_*` + `${VAR:-default}` (cloud, UI), literal (CLI) | A invariante 11 (nenhum segredo literal) só tem mecanismo garantido no VS Code e na UI do cloud agent | — |
| `.claude/settings.json` → `permissions.deny` | `--deny-tool` por sessão ou `managed-settings.json` da empresa | Não há `deny` sempre ativo em arquivo do projeto | — |

---

## 5. O Claude Code tem e o Copilot não

| Peça | Efeito no projeto gerado sob Copilot |
|---|---|
| Injeção `` !`comando` `` no corpo da skill (14 skills, 2 a 4 por arquivo) | O texto `` !`java .claude/hooks/ArchHook.jar doctor` `` chega literal. Toda skill que descobre estado por injeção (`arch-doctor`, `init-project`, as nove de design que leem a spec) perde o estado |
| `$ARGUMENTS` interpolado | Fica literal; o argumento chega solto |
| Tool `Skill` (26 usos: uma skill encadeando outra, `/new-feature` orquestrando o pipeline) | Não existe. O encadeamento de design skills depende do modelo "decidir" abrir a próxima |
| `AskUserQuestion` com opções estruturadas (50 usos) | O CLI mapeia `ask_user`; o **cloud agent não tem usuário** — a pergunta fica sem resposta e o run segue ou para |
| `SubagentStart` em PascalCase, `StopFailure`, `PermissionDenied` | Sem evento equivalente; registros ignorados em silêncio |
| `permissions.deny` no `settings.json` do projeto | Sem equivalente em arquivo do repositório |
| `hooks` **dentro** do frontmatter de skill ou agent | Só o VS Code documenta `hooks` em agent (preview) |
| `CLAUDE.md` de módulo, `CLAUDE.local.md` por módulo | Só o aninhamento de `AGENTS.md` existe |
| Mods (`.claude/mods/`, `nerviz-cockpit`) | Só Claude Code |
| Trilha de auditoria lida de `~/.claude/projects/*.jsonl` (`audit`, `GENESIS.md`, `/audit-usage`) | Transcrição do CLI tem outro formato e outro caminho; tokens não verificados. Cloud agent: nenhum arquivo |
| Fases de skill no `guard` (território por classe, keyed em `tool_input.skill` do evento `Skill`) | Sem evento `Skill`, nenhuma fase abre: o `guard` só poderia julgar por `agent_type`, que no Copilot chega como `agentName` |
| `effort`, `permissionMode`, `maxTurns`, `skills`, `memory`, `isolation` no agent | Sem equivalente documentado |

---

## 6. O Copilot tem e o Claude Code não

| Peça | Uso possível pelo Nerviz |
|---|---|
| **Cloud agent**: roda a partir de uma issue, sem usuário, num runner do Actions | Um `/new-feature-implement` acionado por issue — se o pre-flight e as quatro findings puderem rodar sem `AskUserQuestion` |
| **Code review** com instruções e `excludeAgent: "cloud-agent"` | Normas de `rules/` aplicadas na revisão de PR de qualquer autor, humano inclusive — o que o `guard` nunca vê (`lessons-learned-019` § 5.4) |
| `.github/instructions/` honrado pelo **code review** | A derivação de `rules/` → `.instructions.md` serve a dois leitores de uma vez |
| `include-custom-instructions` (opt-in por agent) | Decidir por agent se ele recebe o contexto raiz |
| `errorOccurred`, `userPromptTransformed`, `permissionRequest` como eventos de hook | Observar o que o Claude Code só mostra na UI |
| `modifiedArgs` em `preToolUse` | Reescrever um comando em vez de bloqueá-lo |
| `type: "http"` e `type: "prompt"` em hooks | Hook sem processo local |
| `managed-settings.json` com allow/deny de MCP por `serverUrl`, `serverCommand`, `serverName` | Política de organização sobre `.mcp.json` |
| `copilot-setup-steps.yml` + firewall por allowlist | Ambiente reproduzível do agente, declarado no repositório |
| `/fleet`, `--fleet` | Paralelismo de subagentes nativo no CLI |
| Leitura de `CLAUDE.md`, `GEMINI.md`, `.claude-plugin/plugin.json`, `.claude/settings.json`, `.claude/skills/`, `.mcp.json` | O Copilot CLI foi escrito para ler o `.claude/` de um projeto. A recíproca não existe: o Claude Code não lê `.github/agents/`, `.github/instructions/` nem `.github/hooks/` |

---

## 7. Garantias do Nerviz, superfície por superfície

"Garantido" aqui tem o sentido do `CLAUDE.md`, invariante 6: um hook ou um `deny` que roda
sempre. "Orientado" é prosa que o modelo lê e pode seguir. "Nada" é um arquivo inerte.

| Garantia no Claude Code | Copilot CLI | Cloud agent | VS Code | Por quê |
|---|---|---|---|---|
| Fronteira arquitetural no build (`doctor gate` varrendo todo `.java`; Checkstyle, JaCoCo; ArchUnit depois de instalado) | garantido | garantido | garantido | Vive no build, não no agente. Desde a decisão 0140 o `doctor gate` recusa import proibido desde o primeiro commit; antes só contava as regras |
| Contexto raiz (`CLAUDE.md`) | orientado | orientado | orientado | Lido nativamente |
| Contexto por módulo (`<módulo>/AGENTS.md`, importado pelo `CLAUDE.md` de uma linha) | orientado | orientado | orientado só com `chat.useNestedAgentsMdFiles` | Decisão 0139 |
| Normas por caminho (`rules/*.md` + `paths`) | nada, salvo citação aberta pelo modelo | nada | nada | `.claude/rules/` não é lido; exige derivação para `.instructions.md` |
| Skills de design com estado por injeção `` !` `` | degradado: corpo chega, estado não | degradado | degradado | Injeção não existe |
| Skill manual (`disable-model-invocation`) não invocada pelo modelo | garantido pelo `guard call` na chamada `skill`; ler o `SKILL.md` por `view` e seguir continua possível | nada | orientado | Decisão 0171; fail-open se o `UserPromptSubmit` não rodar |
| Território de escrita do `guard` por fase de skill | nada | nada | nada | Sem evento `Skill` |
| Território de escrita do `guard` por agent (`agent_type`) | não verificado: hook roda, payload difere (`agentName`, `tool_input` sem formato documentado) | nada (não lê `settings.json`) | não verificado (`chat.useClaudeHooks`) | Passo 5 da fase 8 |
| `guard bash` (força push, escrita fora do território) | não verificado: hook roda; depende de `tool_input.command` | nada | não verificado | Idem |
| Spec aprovada congelada | garantido no hook (`guard write` lê o `apply_patch`, decisão 0169; `guard bash` lê `command`), e a negação leva o motivo no stdout desde a decisão 0170 (lessons-learned-022 § 4) | nada | não verificado | É o mesmo `guard` |
| `compose gate` | não verificado (hook roda, `exit 2` nega) | nada | não verificado | Mesmo hook |
| Catálogo de padrões em `SubagentStart` | nada, salvo registro camelCase | nada, salvo `.github/hooks/*.json` camelCase | nada | Sem alias PascalCase |
| Executor `java-spring-boot-developer` com `tools` restritas | parcial: precisa de `.agent.md`; `model` com id próprio; `include-custom-instructions` | parcial, sem `model` | parcial | Formato de agent diferente; `.claude/agents/` não verificado |
| `permissions.deny` (`.env`, `.pem`, `git push --force`) | nada em arquivo; `--deny-tool` por sessão | nada | nada | Só política de empresa |
| Trilha de auditoria e `GENESIS.md` | nada | nada | nada | Transcrição em outro formato |
| Segredo nunca literal em `.mcp.json` | não verificado (interpolação não documentada) | garantido pela UI, se usada | garantido por `${input:}` | — |

Leitura honesta da tabela, que o projeto gerado diz na seção `## Under another agent` do `CLAUDE.md` raiz — o arquivo que toda superfície lê (decisão 0139): **sob
Copilot, o que continua garantido é o build**. Território de escrita, spec congelada, catálogo
em `SubagentStart`, `deny` de arquivos sensíveis e auditoria são Claude Code — e no CLI
viram "talvez", até que o passo 5 da fase 8 prove o payload.

---

## 8. Fatos que amarram o desenho do `AGENTS.md`

Não são decisões. São o que a documentação fixa, para o `/claude-code-architect-designer` não
redescobrir.

1. **Claude Code, Copilot CLI, cloud agent e VS Code leem o `CLAUDE.md` da raiz.** Só o Claude
   Code e o Copilot CLI expandem `@AGENTS.md` dentro dele. Um `CLAUDE.md` que só importa o
   `AGENTS.md` deixa o cloud agent com dois arquivos e um import morto.
2. **Claude Code lê `AGENTS.md` nativamente desde a v2.1.277, mas só quando não há `CLAUDE.md`
   no caminho** (`claude-help.md` § AGENTS.md). Com os dois, lê só o `CLAUDE.md`. A forma
   oficialmente preferida para compartilhar é `@AGENTS.md` dentro do `CLAUDE.md`, não symlink.
3. **O único aninhamento que atravessa os dois runtimes é o `AGENTS.md` aninhado, e no Claude
   Code só por import.** Um `AGENTS.md` de módulo sozinho **não** é lido pelo Claude Code
   quando a raiz tem um `CLAUDE.md` — a documentação deixa o caso em aberto; o Claude Code
   2.1.296 não o carregou num Read. Um `CLAUDE.md` de módulo contendo só `@AGENTS.md` o carrega
   num Read, Edit ou Write sob o módulo, dentro de subagent também (reproduzido em 2026-10-11).
   O Copilot nunca lê `CLAUDE.md` aninhado. Daí a forma da decisão 0139: o `AGENTS.md` do
   módulo é o dono, e o `CLAUDE.md` ao lado só o importa.
4. **O Copilot lê `.claude/skills/` como está**, mas nenhuma `disable-model-invocation` é
   garantida fora do VS Code. No CLI, a chamada chega aos hooks como `tool_name: "skill"`
   (minúsculo), e o `guard call` recusa a de uma skill manual que o prompt do turno não abriu —
   inclusive `arch-adopt`, que reescreve o `.claude/` (decisão 0171). No cloud agent, nada.
5. **`.claude/rules/` não existe para o Copilot.** Até a derivação do passo 3, a única via é a
   citação por caminho dentro do `AGENTS.md`.
6. **Hooks de `.claude/settings.json` rodam no Copilot CLI, e só lá.** O que o `AGENTS.md`
   promete sobre `guard` tem de distinguir CLI de cloud agent.
7. **O cloud agent não tem usuário.** Qualquer fluxo que passe por `AskUserQuestion` ou por
   duas confirmações (`git-publish`) não termina lá.
8. **Quem soma e quem escolhe.** O Copilot CLI e o VS Code somam todos os arquivos de
   instrução, sem ordem de precedência. O cloud agent lê "`AGENTS.md`, `CLAUDE.md` ou
   `GEMINI.md`"; qual vence quando a raiz tem dois não é documentado. O VS Code não expande
   `@caminho` — só link Markdown, e só com `chat.includeReferencedInstructions` (padrão
   `false`). O chat do GitHub.com, do Visual Studio, do JetBrains, do Xcode e do Eclipse lê só
   `.github/copilot-instructions.md`.

---

## Fontes (lidas em 2026-10-11)

Instruções e `AGENTS.md`
- https://docs.github.com/en/copilot/how-tos/configure-custom-instructions/add-repository-instructions
- https://docs.github.com/en/copilot/reference/custom-instructions-support
- https://docs.github.com/en/copilot/concepts/prompting/response-customization
- https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-custom-instructions
- https://docs.github.com/en/copilot/reference/customization-cheat-sheet
- https://github.blog/changelog/2025-08-28-copilot-coding-agent-now-supports-agents-md-custom-instructions
- https://github.blog/changelog/2025-11-12-copilot-code-review-and-coding-agent-now-support-agent-specific-instructions/
- https://code.visualstudio.com/docs/copilot/customization/custom-instructions
- https://code.visualstudio.com/docs/copilot/customization/prompt-files
- https://code.visualstudio.com/docs/copilot/reference/copilot-settings

Skills e agents
- https://docs.github.com/en/copilot/concepts/agents/about-agent-skills
- https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills
- https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/create-skills
- https://github.blog/changelog/2025-12-18-github-copilot-now-supports-agent-skills/
- https://agentskills.io/specification
- https://code.visualstudio.com/docs/copilot/customization/agent-skills
- https://docs.github.com/en/copilot/reference/custom-agents-configuration
- https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/create-custom-agents-for-cli
- https://docs.github.com/en/copilot/how-tos/copilot-cli/use-copilot-cli/invoke-custom-agents
- https://code.visualstudio.com/docs/copilot/customization/custom-agents
- https://code.visualstudio.com/docs/copilot/agents/subagents
- https://github.com/github/awesome-copilot

Hooks, MCP, permissões, ambiente
- https://docs.github.com/en/copilot/reference/hooks-configuration
- https://docs.github.com/en/copilot/how-tos/use-copilot-agents/coding-agent/use-hooks
- https://code.visualstudio.com/docs/copilot/customization/hooks
- https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers
- https://docs.github.com/en/copilot/how-tos/use-copilot-agents/coding-agent/extend-coding-agent-with-mcp
- https://code.visualstudio.com/docs/agents/reference/mcp-configuration
- https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference
- https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-programmatic-reference
- https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-config-dir-reference
- https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-plugin-reference
- https://docs.github.com/en/enterprise-cloud@latest/copilot/reference/enterprise-administrators/enterprise-managed-settings
- https://github.blog/changelog/2026-08-07-mcp-allowlists-in-enterprise-managed-settings
- https://docs.github.com/en/copilot/how-tos/use-copilot-agents/coding-agent/customize-the-agent-environment
- https://docs.github.com/en/copilot/how-tos/use-copilot-agents/coding-agent/customize-the-agent-firewall
- https://docs.github.com/en/copilot/reference/allowlist-reference
- https://github.blog/changelog/2026-02-25-github-copilot-cli-is-now-generally-available/

Lado Claude Code: `claude-help.md` (raiz deste repositório), `.claude/schemas/extensions.json`
(`types`, `settings.hook_events`, `injections`, `arguments`, `export`).
