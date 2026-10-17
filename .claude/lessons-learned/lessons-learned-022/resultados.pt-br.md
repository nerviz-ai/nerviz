# Resultados — roteiro 15 (Copilot vs Claude Code) — probe-single

Consolidado para o `/claude-code-architect-designer`. Cada linha aponta a evidência em
`.probe/evidencias/`. O que não foi observado conta como ausente: um teste sem evidência fica
`não verificado`.

## Versões

| Ferramenta | Valor |
|---|---|
| Claude Code | 2.1.296 |
| Copilot CLI | 1.0.98 |
| VS Code + Copilot Chat | não registrado (só a captura de payload do § 1.4) |
| JDK | OpenJDK 21.0.11 |
| Projeto | `probe-single`, blueprint `hexagonal-single-module`, commit `1ae493d` (tag `probe-baseline`) |
| Provenance Nerviz | ref `working-tree`, 2026-10-16T16:54:55Z |
| Data | 2026-10-16 |

## Método e desvios

- **Dois projetos.** `probe-single` é o original, com os hooks em forma exec. `probe-single-shellform` é uma
  cópia com os 28 hooks do `.claude/settings.json` convertidos para forma shell. Sem essa conversão, nenhum
  teste de escrita ou de shell roda no Copilot CLI (ver D0). Os testes B, C, D, E e F rodaram na cópia.
- **`probe-multi` não foi gerado.** Por isso A2 e A3 não rodaram.
- **Sem remote.** A4, H e I não rodaram.
- **E1 com arquivo falso.** Os dois projetos têm um `.env` real, que não foi usado no teste. O `.env` da cópia
  foi apagado depois que o E1 deu ausente.
- **F1 com servidor falso.** O projeto não tem `.mcp.json` nem template, então foi criado na cópia um servidor
  `probe-env` falso, removido depois do teste.
- **G1 com `-DskipITs`.** Os testes de integração não exercitam boundary.
- **Replays offline.** D1, D3 e D4 também foram medidos passando ao `ArchHook.jar` o payload exato que o
  Copilot entrega. Isso foi necessário porque o modelo recusou ao vivo, por instrução, antes de o hook rodar.

## Planilha

| ID | Superfície | Claude Code | Copilot | Resultado | Evidência |
|---|---|---|---|---|---|
| D0 | CLI | hooks sobem | forma exec: `Denied by preToolUse hook from "repo settings" (hook errored)` em todo Edit, Shell e até Read; forma shell: tudo passa | **ausente (exec) / igual (shell)** | `D0-copilot-cli.txt`, `D0-diagnostico.txt` |
| A1 | CLI | responde sem Read | responde certo, sem nenhuma tool call | **igual** | `A1-copilot-cli.txt` |
| A5 | CLI | `paths` carrega a norma | `Discovered <norma> from .claude/rules/`, lida ativamente (14 normas num run) | **diferente** | `D1-replay.txt`, `D4-replay.txt` |
| B1 | CLI | 25 skills | 25/25 com check verde (relato do usuário) | **igual** | `B1-copilot-cli.txt` |
| B2 | CLI | runtime executa `` !`…` `` | corpo chega literal; o modelo roda os comandos via `bash` | **degradado** | `B2-copilot-cli.txt` |
| B3 | CLI | `$ARGUMENTS` substituído | o texto chega ao modelo (substituído ou anexado: indistinguível) | **igual / diferente** | `B3-copilot-cli.txt` |
| B4 | CLI | o modelo não invoca skill manual | nenhuma skill manual invocada pela tool `skill`; o corpo do `arch-adopt` foi lido por `view`; o flag não existe nos dados do Copilot | **degradado** | `B4-copilot-cli.txt` |
| B5 | CLI | `/new-feature` encadeia via `Skill` | com o flag: não inicia; sem o flag: inicia, encadeia `use-case-design` pela tool `skill` e escreve `UC-901-.../00-use-case.md` no formato certo | **ausente (com flag) / igual (sem flag)** | `B5-copilot-cli.txt`, `B5-UC-901-00-use-case.md` |
| B6 | CLI | `AskUserQuestion` | tool `ask_user` com `requestedSchema`: `oneOf` (opções) ou `string` (texto livre) | **igual** | `D1-replay.txt`, `B4-copilot-cli.txt` |
| C1 | CLI | 5 agents | `/agent`: 4 registrados; `mapstruct-installer` falha no YAML; `effort` e `omitClaudeMd` ignorados | **degradado** | `C1-copilot-cli.txt` |
| C2 | CLI | delega ao executor | — | **não verificado** (sem spec `approved`) | — |
| C3 | CLI | `SubagentStart` entrega o catálogo | — | **não verificado** | — |
| D1 | CLI | `check` exit 2 no import proibido | replay: `check` exit 0 para `apply_patch`; ao vivo o modelo recusou por instrução | **ausente (replay) / não verificado (ao vivo)** | `D1-replay.txt` |
| D2 | CLI | `format` reformata | não rodou; o Spotless acusou no `Stop` | **ausente** | `D1-replay.txt` |
| D3 | CLI | `guard bash` nega `-f` e `--force` | replay: guard exit 2 no payload do Copilot; sentinela: exit 2 nega, mas o motivo some (`hook exited with code 2`); ao vivo o modelo recusou por instrução | **diferente** | `D3-claude-code.txt`, `D3-copilot-cli.txt` |
| D4 | CLI | `guard write` e `guard bash` negam | edição via `apply_patch` passa; o `guard sweep` acusa no `Stop` e não reverte. `>>` via shell: negado sem motivo | **degradado (edição) / diferente (shell)** | `D4-replay.txt` |
| D5 | CLI | território de fase | — | **não verificado** | — |
| D6 | CLI | 5 hooks de `Stop` | `schema`, `guard sweep` e testes (`verify -DskipITs`) rodam, e a mensagem chega ao usuário | **igual** | `D1-replay.txt`, `D4-replay.txt`, `B4-copilot-cli.txt` |
| E1 | CLI | `deny` nega Read e `cat` em `*.env` | `wc -l .probe/e1.env` roda sem negação; só a escolha do modelo protege o conteúdo | **ausente** | `E1-claude-code.txt` |
| E2 | CLI | `git push` pede confirmação | — (nenhum push chegou a rodar) | **não verificado** | — |
| F1 | CLI | — | `.mcp.json` lido; `${VAR}` expande em `env` e em `args`; variável indefinida fica literal | **igual** | `F1-copilot-cli.txt` |
| G1 | todas | — | import proibido: só o `doctor gate` (CI) recusa, `./mvnw verify` passa; nome qualificado (FQN) passa em tudo | **igual** (não depende do autor) | `G1-build.txt` |
| A2, A3 | CLI | — | — | **não verificado** (sem `probe-multi`) | — |
| A4, H, I | web / cloud | — | — | **não verificado** (sem remote) | — |

## Payload (§ 1.4)

| Ferramenta | Claude Code | Copilot CLI, hook do `settings.json` (alias PascalCase) | Copilot CLI, `.github/hooks` (camelCase) | VS Code, `.github/hooks` |
|---|---|---|---|---|
| Envelope | `session_id`, `tool_name`, `tool_input`, `hook_event_name` | `session_id`, `tool_name`, `tool_input`, `hook_event_name: "PreToolUse"` | `sessionId`, `toolName`, `toolArgs`, sem nome de evento; `timestamp` em epoch ms | snake_case, `hook_event_name: "PreToolUse"` |
| Bash | `Bash` · `command`, `description` | `Bash` · `command`, `description` | `bash` · `command`, `description` | `run_in_terminal` · `command`, `explanation`, `goal`, `mode` |
| Write | `Write` · `file_path`, `content` | `Edit` · **string** de patch (`*** Add File: <caminho>`) | `apply_patch` · **string** de patch | `apply_patch` · `input` (string), `explanation` |
| Edit | `Edit` · `file_path`, `old_string`, `new_string`, `replace_all` | `Edit` · **string** de patch (`*** Update File:`) | `apply_patch` · **string** de patch | `apply_patch` · `input` (string) |
| Read | `Read` · `file_path` | `Read` · `path` | `view` · `path` | `read_file` · `filePath`, `startLine`, `endLine` |
| SubagentStart | `agent_type` | não capturado | não capturado | não capturado |

- **Caminho do patch.** Veio às vezes absoluto, às vezes relativo à raiz.
- **`chat.useClaudeHooks` desligado.** Com o padrão, o VS Code não leu o `.claude/settings.local.json`. O run
  com a opção ligada não foi feito.

## Achados para o `/claude-code-architect-designer`

1. **Hooks em forma exec bloqueiam tudo no Copilot CLI (D0).**
   - O Copilot ignora `args` e roda só `command`. Exemplos: `java` sem argumentos sai com 1, e `sh` lendo o
     JSON do stdin sai com 127.
   - Toda saída diferente de zero num `preToolUse` nega a chamada, com `hook errored`.
   - **Prova com controle:** no mesmo CLI e com o mesmo prompt, a forma exec nega Edit e Shell, e a forma shell
     (`java -jar "$CLAUDE_PROJECT_DIR/.claude/hooks/ArchHook.jar" guard bash`) deixa tudo passar.
   - `CLAUDE_PROJECT_DIR` **está definido** no ambiente do hook, em 7 de 7 capturas.
   - O `_comment` do `settings.json` justifica a forma exec por causa do quoting no Windows. A decisão tem esse
     custo e precisa ser pesada contra ele.
2. **`guard write`, `check` e `format` não veem edições do Copilot (D1, D2, D4).**
   - O Copilot só edita com `apply_patch`, e no hook ele chega como `Edit` com `tool_input` em **string**, não em
     objeto. Não existe `file_path`.
   - Os três hooks saem com 0, então spec congelada editada, import proibido escrito e arquivo sem formatar passam.
   - **Proposta:** extrair os caminhos das linhas `*** Add File:` e `*** Update File:` (e `*** Delete File:`), que
     podem ser absolutos ou relativos à raiz, e o conteúdo das linhas `+`.
3. **O motivo da negação some no `PreToolUse` (D3, D4).**
   - O stderr do hook não chega ao modelo, que só vê `hook exited with code 2` e não sabe o que corrigir.
   - No `Stop`, a mensagem chega.
   - **A testar:** se uma saída em JSON no stdout (`decision` e `reason`) é repassada.
4. **`guard sweep` detecta e não impede (D4).** No `Stop`, a escrita já aconteceu e o agente não reverte, mesmo
   recebendo `Revert them, or report them and stop`. Com o item 2 em aberto, ele é a única rede para edições.
5. **`disable-model-invocation: true` bloqueia o próprio usuário no `/new-feature` (B5).**
   - Com o flag, `/new-feature <pedido>` não inicia: o modelo responde "digite `/new-feature`", em loop.
   - Sem o flag, inicia e encadeia.
   - O `/arch-doctor`, com o mesmo flag e sem argumento, inicia. A causa exata (argumento ou tamanho) não foi
     isolada.
   - Nos dados do Copilot (`/skills`), o flag não existe: só `userInvocable: true` e `invokesModel: true`.
6. **A contenção das skills manuais é só por instrução (B4).**
   - O modelo leu o corpo do `arch-adopt` por `view` e parou pelo texto ("manual only") e pela trava de worktree
     sujo.
   - Num worktree limpo, só a instrução fica no caminho.
7. **Injeção `` !`…` `` não é executada pelo runtime (B2).**
   - O modelo roda os comandos via `bash`, o que custa tool calls e passa pelos hooks de Bash.
   - Combinado com o item 1, no projeto original esses comandos seriam negados.
   - O `/arch-doctor` às vezes vira chamada da tool `skill` e às vezes injeta o corpo direto, e numa sessão rodou
     os comandos duas vezes.
8. **Hooks só são relidos em sessão nova.** No D3, run 3, o hook adicionado não valeu na sessão já aberta.
9. **Proteção de segredos não existe no Copilot (E1).** O `deny` `Read(./**/*.env)` não tem equivalente em arquivo,
   e o shell acessa `*.env` livremente.
10. **Hipóteses do roteiro contrariadas pela observação:**
    - **A5:** o Copilot CLI **lê** `.claude/rules/`.
    - **B5:** existe a tool `skill`, e o encadeamento funciona.
    - **B6:** `ask_user` aceita opções e texto livre.
    - **F1:** `${VAR}` expande no CLI.
    - **D3:** no Claude Code, `--force` é recusado pelo `guard bash` antes do `deny`, então o `deny` não fica isolado.
11. **Campos de agent ignorados (C1).** O Copilot ignora `effort` nos 4 agents e `omitClaudeMd` nos 2 installers.
    Esses installers recebem o `CLAUDE.md` inteiro.

## Achados para o `/report-issue` (projeto gerado, independente do Copilot)

1. **O frontmatter do `mapstruct-installer.md` é YAML inválido.**
   - A `description` sem aspas contém `": "` em `` `**Mappers: manual.**` ``.
   - Um parser estrito (Copilot; Ruby Psych) falha com `mapping values are not allowed in this context`.
   - O Claude Code aceita porque é leniente.
   - **Correção:** aspas ou bloco `>`.
   - Vale checar todos os frontmatters gerados com um parser estrito.
2. **Nome qualificado (FQN) escapa da checagem de boundary (D1, G1).**
   - `@org.springframework.stereotype.Component` sem `import` passa pelo `check`, pelo `doctor gate` (CI
     `architectural boundaries`) e pelo `./mvnw verify`.
   - Os dois primeiros só leem linhas `import`.
   - Sem o ArchUnit instalado, o build não tem regra de boundary.
3. **O build Maven não recusa import proibido (G1).** Só o job de CI recusa. A frase do `CLAUDE.md` sobre o
   que "o build" recusa vale para o CI, não para o `./mvnw verify` local.

## Não verificado

- **A2 e A3:** falta o `probe-multi`.
- **A4, H e I:** falta o remote.
- **C2 e C3:** falta uma spec `approved` e um `/new-feature-implement` no Copilot.
- **D5, E2 e o run do VS Code com `chat.useClaudeHooks: true`:** não foram executados.
- **D1 e D3 ao vivo:** o modelo recusou por instrução antes de o hook rodar. A conclusão vem do replay.
