# Comandos para o `/claude-code-architect-designer` — lessons-learned-022

Derivados de `../lessons-learned-022.md` e `resultados.pt-br.md`. A ordem segue a dependência: o 1
destrava os outros, porque sem ele nenhum hook roda no Copilot. Rode cada comando numa sessão nova,
porque o `settings.json` só é relido no startup.

## 1. Forma dos hooks: exec bloqueia tudo no Copilot CLI (D0)

```
/claude-code-architect-designer Forma 7. Em .claude/lessons-learned/lessons-learned-022.md § 2 (evidência D0), os hooks em forma exec ("command": "java" + "args") negam todo Edit, Shell e Read no Copilot CLI 1.0.98. O Copilot ignora "args", roda só "java", sai com 1 e nega a chamada com "hook errored". A forma shell (java -jar "$CLAUDE_PROJECT_DIR/.claude/hooks/ArchHook.jar" guard bash) passou no mesmo prompt, e CLAUDE_PROJECT_DIR estava definido em 7 de 7 capturas. Decida entre: (a) forma shell em todos os hooks, (b) um arquivo de hooks por runtime em .github/hooks/ (payload camelCase, § 6), ou (c) um launcher que funcione nos dois. Pese contra o motivo do _comment do settings.json, que é o quoting no Windows. Restrições: o Claude Code não muda de comportamento, e um hook sob o Copilot sai com 0 num payload que não consegue ler. Isso vale para .claude/settings.json e para project-bootstrap/templates/settings.json.example. Registre em decisions/ e atualize schema se a forma esperada mudar.
```

## 2. `guard write`, `check`, `format` e `audit file` não enxergam o `apply_patch` (D1, D2, D4)

```
/claude-code-architect-designer Forma 8 (modo do ArchHook.java). lessons-learned-022 § 3: o Copilot edita só via apply_patch, e o hook recebe tool_name "Edit" com tool_input em STRING de patch (*** Begin Patch / *** Add File: / *** Update File: / *** Delete File: / *** Move to:), sem file_path. Com isso guard write, check, format e audit file saem com 0: spec approved editada, import proibido e arquivo sem formatar passam. O caminho chega absoluto ou relativo à raiz. Também falta ler o envelope camelCase de .github/hooks (toolName/toolArgs, tool "apply_patch", "view" com "path") e o do VS Code (apply_patch.input, read_file.filePath), caso o comando 1 escolha .github/hooks. Projete a extração de caminhos e do conteúdo das linhas "+" como adição ao parse atual: o payload do Claude Code com file_path continua igual. Payload ilegível sai com 0. Use os payloads reais de lessons-learned-022/captures/ como fixtures dos testes do modo. Rebuild do jar e registro em decisions/.
```

## 3. O motivo da negação some no `PreToolUse` (D3, D4)

```
/claude-code-architect-designer Forma 8. lessons-learned-022 § 4: no Copilot CLI, um preToolUse que sai com 2 nega a chamada, mas o modelo vê só "Denied by preToolUse hook: hook exited with code 2". Todo o stderr do guard se perde, e no Stop a mensagem chega. Ainda não foi testado se uma saída JSON no stdout (decision/reason, ou o formato hookSpecificOutput.permissionDecisionReason) é repassada. Primeiro escreva um probe mínimo, um hook sentinela que nega PROBE_DENY emitindo JSON no stdout, para eu rodar no Copilot CLI e no Claude Code, com o roteiro de captura. Não mude o guard antes do resultado. Depois do resultado, proponha como guard/check emitem o motivo nos dois runtimes sem mudar o exit code do Claude Code.
```

## 4. `disable-model-invocation` trava o usuário no `/new-feature`, e as skills manuais ficam contidas só por instrução (B4, B5)

```
/claude-code-architect-designer lessons-learned-022 § 5. No Copilot CLI, "/new-feature <pedido>" com disable-model-invocation: true não inicia: o modelo responde "digite /new-feature" em loop. Sem o flag, inicia e encadeia use-case-design pela tool "skill". /arch-doctor, com o mesmo flag e sem argumento, inicia. Os dois candidatos (argumento vs. tamanho do SKILL.md: 62.673 bytes contra 45 KB do seguinte) não foram isolados. O Copilot não representa o flag (/skills mostra só userInvocable/invokesModel). Por isso arch-adopt, new-feature-implement e git-publish ficam contidos só por texto. (1) Desenhe o experimento que isola a causa (new-feature encurtado; arch-doctor com argumento) para eu rodar. (2) Decida se o flag fica no new-feature e qual guarda determinística substitui o flag sob o Copilot nas skills manuais que escrevem ou publicam. Candidatos: um guard que recusa a fase da skill sem um prompt de usuário com /<nome>, ou permissions.ask. Registre em decisions/ e respeite a decision 0007 (skills do pipeline sem o flag).
```

## 5. Injeção `` !`…` `` não é executada pelo runtime (B2)

```
/claude-code-architect-designer lessons-learned-022 § 5 (B2). No Copilot CLI, o corpo de /arch-doctor chega com as injeções !`…` literais, e o modelo roda os comandos via bash. Isso custa tool calls, passa pelos hooks de Bash e, numa sessão, rodou cada comando duas vezes. Faça um inventário de toda injeção !`…` em .claude/skills/ que trava a skill quando não é expandida. Decida se cada skill ganha uma linha de fallback no corpo ("se este bloco chegou literal, rode X uma vez") ou se fica como está. Ancore a decisão em docs/pt-br/13-github-copilot.md e atualize esse documento com o fato observado.
```

## 6. Proteção de segredos inexistente no Copilot (E1)

```
/claude-code-architect-designer Forma 7. lessons-learned-022 § 8: o deny Read(./**/*.env) não tem equivalente em arquivo no Copilot, e "wc -l .probe/e1.env" rodou sem negação. Avalie se o guard (bash e read) deve negar leitura de caminhos de @.claude/rules/secrets.md como hook, valendo nos dois runtimes, e com que lista (dados em extensions.json, invariante 10). Use o payload Read do Copilot com "path", não "file_path" (§ 6). Pese o falso positivo (.env.example) e o fato de que, sem o comando 3, a negação chega muda ao modelo. Registre em decisions/.
```

## 7. Defeitos do projeto gerado, independentes do Copilot (§ 9)

```
/claude-code-architect-designer lessons-learned-022 § 9, três defeitos para corrigir aqui na origem. (1) O frontmatter de agents/mapstruct-installer.md é YAML inválido para parser estrito ("mapping values are not allowed in this context", por causa de ": " em `**Mappers: manual.**` sem aspas). Corrija esse arquivo e faça o ArchHook.java schema recusar todo frontmatter que um parser YAML estrito rejeitaria, em agents, skills e rules. (2) Um FQN sem import (@org.springframework.stereotype.Component em domain/model) escapa do check e do doctor gate, que só leem linhas import. Decida se esses dois modos passam a varrer nomes qualificados. (3) ./mvnw verify aceita import proibido, e só o job de CI recusa. Corrija a frase "only the build is guaranteed" em project-bootstrap/templates/root.CLAUDE.md.example ou leve a checagem ao build local. Registro em decisions/.
```

## 8. Reescrever "Under another agent" e o doc de portabilidade (A5, B5, B6, F1, C1)

Rode este por último, depois que os comandos 1 a 4 forem decididos.

```
/claude-code-architect-designer lessons-learned-022 §§ 6-7 contrariaram hipóteses: o Copilot CLI lê .claude/rules/ (A5), tem a tool "skill" e encadeia (B5), ask_user aceita opções e texto livre (B6), ${VAR} expande em env e args do .mcp.json (F1), e os hooks rodam em forma shell, mas cegos ao apply_patch e mudos na negação. Ele ignora effort e omitClaudeMd nos agents (C1). Reescreva a seção "Under another agent" de project-bootstrap/templates/root.CLAUDE.md.example com o que as decisões dos itens anteriores deixaram de pé. Atualize docs/pt-br/13-github-copilot.md e a fase 8 de roadmap.md, e liste como não verificados A2, A3, A4, C2, C3, D5, E2, H, I e o VS Code com chat.useClaudeHooks.
```

## Fora do escopo do designer

Os testes não verificados (A2/A3, C2/C3, D5, E2, VS Code com `chat.useClaudeHooks`) pedem outra rodada
do roteiro 15, não design.
