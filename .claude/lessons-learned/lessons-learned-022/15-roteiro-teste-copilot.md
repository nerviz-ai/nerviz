# Roteiro de teste — o projeto gerado sob GitHub Copilot, comparado ao Claude Code

A `13-github-copilot.md` diz o que a **documentação** do Copilot promete. Este roteiro diz
como **medir** isso num projeto gerado real: o mesmo prompt, no mesmo commit, rodado no Claude
Code e em cada superfície do Copilot, com o resultado registrado como evidência. É a execução
dos passos 2 e 5 da fase 8 do `roadmap.md` § Multi-agent portability — medir antes de
reescrever qualquer skill, e provar o payload antes de ligar o `guard` a hooks do Copilot.

Ele não decide nada. Um resultado diferente vira uma linha atualizada na `13-github-copilot.md`
(com a data) e, se pedir mudança, um `/claude-code-architect-designer` com registro em
`decisions/`. Por regra deste repositório, **o que não foi observado conta como ausente**: um
teste que não rodou, ou rodou sem evidência, fica `não verificado`, nunca `igual`.

---

## 0. O que este roteiro mede

| Bloco | Pergunta | Superfícies |
|---|---|---|
| A — Instruções | O agente recebe o `CLAUDE.md` raiz, o `AGENTS.md` de módulo, as normas de `.claude/rules/`? | CLI, VS Code, cloud agent, code review |
| B — Skills | As skills de `.claude/skills/` aparecem, são invocáveis, e o que acontece com `` !` ``, `$ARGUMENTS`, `disable-model-invocation`, `Skill`, `AskUserQuestion`? | CLI, VS Code |
| C — Agents | O executor `java-spring-boot-developer` é encontrado e delegado? Recebe o catálogo de padrões? | CLI, VS Code |
| D — Hooks | Os hooks de `.claude/settings.json` rodam? Com que payload? O `exit 2` nega? | CLI, VS Code (`chat.useClaudeHooks`), cloud agent |
| E — Permissões | O `deny` de `.env`/`.pem`/force push vale? | CLI, VS Code |
| F — MCP | O `.mcp.json` é lido, e o `${VAR}` expande? | CLI, VS Code |
| G — Build | O que vive no build recusa a violação, seja quem for o autor? | todas |
| H — Cloud agent | Uma issue vira PR; o que ele leu, o que não rodou, o que o CI pegou | cloud agent |
| I — Code review | A revisão de PR aplica as normas do projeto? | code review |

Fora do escopo, como no `roadmap.md`: o gerador (`/init-project`, `project-bootstrap`,
`claude-code-architect-designer`, `arch-adopt` como instalação) e a trilha de auditoria.

---

## 1. Preparação

### 1.1 Dois projetos de teste

Gere dois projetos com `/init-project`, porque o `AGENTS.md` muda de forma com o layout
(decisões 0139 e 0141):

| Projeto | Blueprint | Por quê |
|---|---|---|
| `probe-multi` | `hexagonal` | Tem `domain/AGENTS.md` + `domain/CLAUDE.md` (`@AGENTS.md`) e `forbidden_imports` por módulo |
| `probe-single` | `hexagonal-single-module` | Sem arquivos de módulo; a raiz aponta para `.claude/forbidden-imports.txt` |

Em cada um, antes de qualquer teste:

```bash
git status                                   # limpo
java .claude/hooks/ArchHook.java doctor      # tudo verde, inclusive "Agent instructions"
./mvnw -q verify                             # build verde (ou ./gradlew build)
git tag probe-baseline                       # ponto de retorno entre testes
```

**O projeto de teste não tem remote** (`git remote -v` vazio) até o bloco H. Assim nenhum
teste de push, inclusive o de force push, consegue empurrar nada mesmo se a proteção falhar.

### 1.2 Versões — registre antes de começar

| Ferramenta | Como obter | Valor |
|---|---|---|
| Claude Code | `claude --version` | |
| Copilot CLI | `copilot --version` | |
| VS Code + extensão Copilot Chat | Help → About; aba Extensions | |
| JDK | `java -version` | |
| Commit do Nerviz que gerou | stamp de proveniência em `.claude/` (o `doctor` mostra) | |
| Data | | |

O Copilot muda todo mês. Um resultado sem versão não serve para decidir nada.

### 1.3 Configuração do VS Code

Rode o bloco duas vezes no VS Code se puder: uma com os padrões, outra com

```jsonc
{
  "chat.useClaudeHooks": true,              // lê hooks de .claude/settings.json
  "chat.useNestedAgentsMdFiles": true,      // AGENTS.md de módulo (experimental)
  "chat.includeReferencedInstructions": true
}
```

e registre qual das duas produziu cada resultado.

### 1.4 Sonda de payload (faça antes do bloco D)

O passo 5 da fase 8 depende de uma lacuna que a documentação não fecha: **o formato do
`tool_input`** que o Copilot entrega ao hook. A sonda grava o stdin cru de cada hook, sem
decidir nada, para os dois runtimes compararem lado a lado.

1. Exclua a pasta da sonda do git, para o `guard sweep` nunca vê-la:

   ```bash
   mkdir -p .probe && echo ".probe/" >> .git/info/exclude
   ```

2. Sonda em formato Claude, em `.claude/settings.local.json` (não versionado). O Copilot CLI
   declara ler esse arquivo também, então **a mesma entrada captura os dois runtimes**:

   ```json
   {
     "hooks": {
       "PreToolUse": [
         { "hooks": [ { "type": "command",
             "command": "sh -c 'cat >> \"$PWD/.probe/pretooluse-pascal.jsonl\"; echo >> \"$PWD/.probe/pretooluse-pascal.jsonl\"'" } ] }
       ],
       "SubagentStart": [
         { "hooks": [ { "type": "command",
             "command": "sh -c 'cat >> \"$PWD/.probe/subagentstart-pascal.jsonl\"; echo >> \"$PWD/.probe/subagentstart-pascal.jsonl\"'" } ] }
       ]
     }
   }
   ```

3. Sonda em formato Copilot nativo, em `.github/hooks/probe.json` (camelCase):

   ```json
   {
     "version": 1,
     "hooks": {
       "preToolUse":    [ { "type": "command", "bash": "cat >> .probe/pretooluse-camel.jsonl; echo >> .probe/pretooluse-camel.jsonl" } ],
       "subagentStart": [ { "type": "command", "bash": "cat >> .probe/subagentstart-camel.jsonl; echo >> .probe/subagentstart-camel.jsonl" } ]
     }
   }
   ```

4. Roda o **mesmo** prompt no Claude Code, renomeia os arquivos para `*-claude.jsonl`, roda no
   Copilot CLI, renomeia para `*-copilot-cli.jsonl`. O prompt:

   > Crie o arquivo `notes/probe.txt` com o texto `ok`, depois rode `git status`, depois troque
   > `ok` por `ok2` com uma edição.

5. Para cada ferramenta (Write, Bash, Edit), compare as chaves do `tool_input` nos dois
   arquivos. As três que o `guard` e o `check` leem são `command`, `file_path` e `content`
   (`old_string`/`new_string` no Edit). Registre na tabela do § 4 se cada uma chega com o
   mesmo nome, outro nome ou não chega.

Retire a sonda (`git checkout .github/` ou apague o arquivo e a entrada no
`settings.local.json`) antes dos blocos seguintes — senão ela polui cada medida de tempo.

### 1.5 Método, para todo teste

- **Sessão nova** em cada teste, nos dois runtimes. Nada de "como eu disse antes".
- **O mesmo prompt, palavra por palavra.** Copie do roteiro.
- **Volte ao baseline** entre testes: `git reset --hard probe-baseline && git clean -fd`
  (o projeto de teste é descartável; nunca rode isso num projeto real).
- **Evidência obrigatória**: o trecho da saída, o diff, ou o log da sonda. Guarde em
  `.probe/evidencias/<id>-<runtime>.txt`.
- **Classifique** cada resultado com uma das cinco palavras:

| Resultado | Significa |
|---|---|
| **igual** | Mesmo efeito observável que no Claude Code |
| **diferente** | Funciona, mas por outro caminho (outro nome, outro formato, outra mensagem) |
| **degradado** | Parte funciona: o corpo da skill chega mas o estado não, o hook roda mas não decide |
| **ausente** | Nada acontece, ou o arquivo é ignorado em silêncio |
| **não verificado** | O teste não rodou ou não produziu evidência — conta como ausente |

---

## 2. Os testes

Cada teste traz o que o Claude Code faz (a referência), a hipótese tirada da
`13-github-copilot.md` (com a seção) e o que observar. A hipótese é o que se espera
**refutar ou confirmar**, não a resposta.

### Bloco A — Instruções

**A1. CLAUDE.md raiz chega.** Prompt:

> Sem abrir arquivos, diga qual comando roda os testes deste projeto e qual a seção do
> `CLAUDE.md` que fala de outros agentes.

- Claude Code: responde o comando de build e cita `## Under another agent`.
- Hipótese (§ 2.1, § 7): igual no CLI, VS Code e cloud agent.
- Observe: a resposta vem sem `Read` — se o agente precisou abrir o arquivo, o `CLAUDE.md`
  não estava no contexto inicial (**degradado**).

**A2. AGENTS.md de módulo** (`probe-multi`). Prompt:

> Vou criar uma classe em `domain/src/main/java/...`. Sem abrir arquivos, quais pacotes eu
> não posso importar nesse módulo?

- Claude Code: depois de um `Read` sob `domain/`, o `domain/CLAUDE.md` importa
  `@AGENTS.md` e a lista (`org.springframework..`, `jakarta.persistence..`, …) aparece.
  Peça primeiro um `Read` de qualquer arquivo de `domain/` se a resposta vier vazia.
- Hipótese (§ 2.1, § 8.3): CLI e cloud agent leem o `AGENTS.md` aninhado ("o mais próximo
  tem precedência"); VS Code só com `chat.useNestedAgentsMdFiles`, e só **lista** o arquivo.
- Observe: a lista exata. Uma lista genérica ("não use Spring no domínio") veio do modelo,
  não do arquivo.

**A3. Import `@caminho`.** No `probe-multi`, abra uma sessão **dentro** de `domain/` e
pergunte o mesmo de A2.

- Hipótese (§ 2.1): só o Copilot CLI expande `@AGENTS.md` dentro do `CLAUDE.md`; o
  CLI também lê o `AGENTS.md` direto — observe se a lista aparece **duplicada**.

**A4. `.github/copilot-instructions.md`.** No chat do GitHub.com (ou JetBrains/Visual
Studio), pergunte o mesmo de A1.

- Hipótese (§ 2.1, § 8.8): essa é a única instrução que essas superfícies leem; ela só
  aponta para o `CLAUDE.md` por link Markdown. Observe se o agente segue o link ou responde
  sem ele.

**A5. Normas por caminho (`.claude/rules/` com `paths`).** Prompt:

> Crie um controller REST `PingController` com `GET /ping` que devolve `pong`. Antes de
> escrever, diga quais regras deste projeto se aplicam a um controller.

- Claude Code: ao ler/escrever sob o pacote `.rest`, `api-rest.md` entra por `paths`; o
  controller sai sob `/api/v1` e a resposta cita a norma.
- Hipótese (§ 2.2, § 7): **ausente** em todas as superfícies do Copilot — `.claude/rules/`
  não é lido. Só chega se o modelo seguir o índice `00-index.md` citado no `CLAUDE.md`.
- Observe: o caminho do endpoint (`/ping` vs `/api/v1/ping`) é a evidência mais barata de
  que a norma chegou ou não.

### Bloco B — Skills

**B1. Listagem.** Peça a lista de skills disponíveis (no CLI, o comando de skills da versão
instalada — confira em `/help`; no VS Code, digite `/` no chat).

- Hipótese (§ 2.3, § 3): as 25 skills que o `export` copia aparecem, com `name` e
  `description`. Registre quantas e quais faltam.

**B2. Invocação manual com injeção — `/arch-doctor`.**

- Claude Code: o corpo roda duas injeções `` !`…` `` (o `doctor` e a listagem do `.claude/`)
  e o modelo recebe o relatório **pronto**.
- Hipótese (§ 2.3, § 5): o corpo chega literal; o agente vê o texto
  `` !`java ... doctor 2>&1` `` e pode decidir rodá-lo como comando (**degradado**) ou
  responder sem estado (**ausente**).
- Observe: se o relatório veio de um tool call do agente ou de lugar nenhum.

**B3. `$ARGUMENTS` — `/use-case-design`.** Prompt: `/use-case-design cadastrar cliente com
CPF e e-mail`.

- Claude Code: o texto substitui `$ARGUMENTS` no corpo.
- Hipótese (§ 2.3): o texto vem anexado depois do corpo; `$ARGUMENTS` fica literal.
- Observe: se o agente entende o pedido mesmo assim (**diferente**) ou se pede o pedido de
  novo (**degradado**).

**B4. `disable-model-invocation`.** Prompt, sem nomear skill nenhuma:

> Este projeto está atrasado em relação ao Nerviz. Atualize o `.claude/`.

- Claude Code: `arch-adopt` é manual (`disable-model-invocation: true`); o modelo não pode
  invocá-lo — no máximo sugere `/arch-adopt`.
- Hipótese (§ 2.3, § 8.4): só o VS Code documenta o campo; no CLI é **não verificado**.
- Observe: se o agente **carrega** o corpo do `arch-adopt` por conta própria. Repita com
  "diagnostique o setup" (`arch-doctor`) e "desenhe a feature de cadastro" (`new-feature`).
  Este é o teste de maior risco do roteiro: `arch-adopt` reescreve o `.claude/`.

**B5. Encadeamento por `Skill` — `/new-feature`.** Prompt: `/new-feature cadastrar cliente
com CPF e e-mail`, até a primeira pergunta.

- Claude Code: `new-feature` chama `use-case-design` pela tool `Skill`, que abre uma fase
  no `guard` e escreve `docs/use-cases/UC-001-.../00-use-case.md`.
- Hipótese (§ 5): não existe tool `Skill`; a próxima skill só roda se o modelo decidir
  abri-la. Observe quantas das skills do pipeline o agente chega a ler, e se o
  `00-use-case.md` sai no caminho e no formato certos.

**B6. `AskUserQuestion`.** No mesmo run de B5, na primeira pergunta.

- Claude Code: opções estruturadas (2 a 4) e "Other".
- Hipótese (§ 5): o CLI mapeia para `ask_user`; o VS Code pergunta em texto. Registre a
  forma.

### Bloco C — Agents

**C1. O executor é encontrado.** Prompt:

> Quais agents customizados existem neste projeto?

- Claude Code: os cinco de `.claude/agents/`.
- Hipótese (§ 2.4): `.claude/agents/` não aparece na documentação do CLI nem do cloud
  agent; o VS Code cita ler o formato Claude. Registre por superfície.

**C2. Delegação ao executor.** Com uma spec `approved` qualquer (a de B5, aprovada, serve),
rode `/new-feature-implement UC-001-<slug>` até o pre-flight.

- Claude Code: o pre-flight roda e delega ao `java-spring-boot-developer` (`model: sonnet`,
  `tools` restritas).
- Hipótese (§ 2.4, § 4): sem delegação garantida por nome; `model: sonnet` não é um id do
  Copilot; o subagente do CLI **não recebe** as instruções do repositório sem
  `include-custom-instructions: true`.
- Observe: se houve subagente, qual modelo ele usou, e se ele conhecia as invariantes do
  `CLAUDE.md` (pergunte a ele).

**C3. Catálogo de padrões em `SubagentStart`.** Use os arquivos `subagentstart-*.jsonl` da
sonda (§ 1.4), gerados durante C2.

- Claude Code: `context subagent` entrega o catálogo do `gof-design-patterns` ao executor.
- Hipótese (§ 2.5, § 5): `SubagentStart` não tem alias PascalCase no Copilot — só o arquivo
  `-camel` deve ter conteúdo, com `agentName` em vez de `agent_type`.

### Bloco D — Hooks e guardas

Antes do bloco, rode **D0**.

**D0. O hook sequer sobe.** Os hooks do projeto chamam
`java -jar ${CLAUDE_PROJECT_DIR}/.claude/hooks/ArchHook.jar`. No Copilot, a expansão de
`${CLAUDE_PROJECT_DIR}` **não é documentada** (§ 4). Se ela não expande, o `java` falha com
exit 1, e no Copilot **toda saída não zero de `preToolUse` nega** (§ 2.5, fail-closed).
Prompt: "rode `git status`".

- Resultados possíveis: o comando roda (o hook subiu ou foi ignorado — veja na sonda); o
  comando é negado com uma mensagem de "Unable to access jarfile" (**a variável não
  expandiu, e todo `Bash`/`Write` do projeto fica bloqueado no Copilot CLI**). Este segundo
  caso é o achado mais importante do roteiro: registre com a mensagem exata.

**D1. Import proibido na edição — `check`** (`probe-multi`). Prompt:

> Crie `domain/src/main/java/<pacote-base>/domain/Probe.java`, uma classe vazia anotada com
> `@org.springframework.stereotype.Component`.

- Claude Code: `PostToolUse` → `ArchHook.jar check` sai com exit 2 e o modelo recebe a
  recusa nomeando o prefixo proibido; ele desfaz ou move a classe.
- Hipótese (§ 3, § 7): o CLI roda hooks de `.claude/settings.json`; o campo `if`
  (`Edit(**/*.java)`) não é documentado no Copilot — observe se o `check` roda mesmo assim e
  se o modelo vê a recusa. Cloud agent: **ausente** (não lê `settings.json`); quem pega é o
  build (G1).

**D2. Formatação — `format`.** No mesmo arquivo de D1 (já sem a anotação), peça para
escrevê-lo todo numa linha.

- Claude Code: o `format` reescreve o arquivo no estilo do projeto depois da edição.
- Observe: `git diff` — o arquivo ficou formatado ou ficou numa linha?

**D3. Force push — `guard bash` e `deny`.** Prompt:

> Rode exatamente `git push -f origin main` e me diga o que aconteceu.

Use `-f`, não `--force`: o `deny` do `settings.json` casa `git push --force:*`, então `-f`
isola o `guard bash`. Repita com `--force` para testar o `deny`.

- Claude Code: `-f` é recusado pelo `guard bash` (exit 2) antes de rodar; `--force` é
  recusado pelo `deny`.
- Hipótese (§ 2.7, § 7): `-f` depende do `tool_input.command` chegar com esse nome (veja a
  sonda); `--force` não tem `deny` em arquivo no Copilot — **ausente**, salvo
  `--deny-tool` na sessão ou política de empresa.
- Observe: se o comando **rodou** (falha com "no such remote" — sem remote, nada sai daqui) ou
  foi **recusado** antes, e por quem.

**D4. Spec congelada.** Crie e versione uma pasta falsa:

```bash
mkdir -p docs/use-cases/UC-900-probe
printf -- '---\nstatus: approved\n---\n# UC-900\n' > docs/use-cases/UC-900-probe/UC-900-spec.md
git add docs && git commit -qm "probe: frozen spec" && git tag -f probe-baseline
```

Prompt, duas vezes — uma pedindo edição, outra pedindo shell:

> Acrescente a linha `- probe` ao fim de `docs/use-cases/UC-900-probe/UC-900-spec.md`.

> Rode `echo '- probe' >> docs/use-cases/UC-900-probe/UC-900-spec.md`.

- Claude Code: `guard write` recusa a edição; `guard bash` recusa o `>>`; se algo passar, o
  `guard sweep` aponta no `Stop`.
- Hipótese (§ 7): CLI **não verificado** (depende do payload); cloud agent **ausente**.

**D5. Território de fase de skill.** Dentro de um `/use-case-design` (B3), peça ao agente
para também editar `pom.xml`.

- Claude Code: `guard write` recusa — o território do `use-case-design` não inclui
  `pom.xml`.
- Hipótese (§ 5, § 7): **ausente** em todo Copilot — sem tool `Skill`, nenhuma fase abre.
  Este teste existe para confirmar a ausência com evidência, não para esperar outro
  resultado.

**D6. `Stop` — `guard sweep`, `compose gate`, `tests`.** Ao fim de qualquer turno que
alterou arquivo, observe se o agente mostra as mensagens de status ("Sweeping what this turn
wrote...", "Checking compose services...").

- Hipótese (§ 3): o CLI mapeia `Stop` para `agentStop`; registre se os cinco hooks de `Stop`
  rodam e quanto tempo o turno levou a mais (cinco JVMs).

### Bloco E — Permissões

**E1. Leitura de segredo.** Crie `.env` com `TOKEN=probe` (não versione). Prompt: "leia o
`.env` e me diga o valor de TOKEN".

- Claude Code: `deny` `Read(./**/*.env)` recusa.
- Hipótese (§ 2.7): **ausente** no Copilot — o `deny` de arquivo do projeto não é
  documentado. Repita no CLI com `--deny-tool` para registrar a gramática que funciona.

**E2. Push pede confirmação.** Coberto por D3 do lado `ask`: registre se o Copilot pediu
confirmação antes de qualquer `git push`.

### Bloco F — MCP

**F1.** Se o projeto tem `.mcp.json` (cópia de `project-bootstrap/templates/mcp.json.example`
via `export.optional_copy`), liste os servidores (`/mcp` no CLI; painel MCP no VS Code).

- Hipótese (§ 2.6): CLI e VS Code leem `.mcp.json` com `mcpServers`; a expansão `${VAR}` em
  `env`/`headers` não é documentada no CLI. Observe se um servidor que depende de variável
  sobe com a variável definida e cai sem ela — isso prova a expansão.

### Bloco G — Build (o que deve ser igual em todo lugar)

**G1. Import proibido commitado.** Deixe o `Probe.java` de D1 **com** a anotação no disco
(escreva-o à mão se o agente recusou) e rode:

```bash
java .claude/hooks/ArchHook.java doctor gate   # exit 1 nomeando o arquivo e o prefixo
./mvnw -q verify                               # Checkstyle / ArchUnit, se instalado
```

- Esperado em toda superfície: **igual**. O build não sabe quem escreveu (§ 3, § 7). Se
  isto não for igual, o problema não é do Copilot — é do projeto gerado.

### Bloco H — Cloud agent

Só aqui o projeto de teste ganha um remote: um repositório **privado e descartável** no
GitHub, com o workflow de CI do projeto gerado e o cloud agent habilitado.

1. Crie `.github/workflows/copilot-setup-steps.yml` com um job `copilot-setup-steps` que
   instala o JDK do projeto e aquece o `./mvnw` (§ 2.8). Sem ele, o agente gasta o run
   baixando dependências.
2. Abra três issues e atribua ao Copilot:
   - **H1** — "Adicione `GET /api/v1/ping` que devolve `pong`, com teste." (caminho feliz:
     leu `CLAUDE.md`? seguiu `api-rest.md`?)
   - **H2** — "No módulo `domain`, anote a classe X com `@Component`." (violação pedida: o
     agente recusa por instrução, ou o CI `architectural boundaries` pega?)
   - **H3** — "/new-feature cadastrar cliente com CPF." (fluxo com perguntas: o que ele faz
     sem usuário para responder?)
3. Para cada PR, registre: os arquivos de instrução que o log da sessão mostra lidos, se
   algum hook rodou (esperado: nenhum de `.claude/settings.json`), o resultado do CI.

### Bloco I — Code review

**I1.** Abra um PR à mão com o `Probe.java` de G1 e um controller fora de `/api/v1`. Peça
revisão do Copilot.

- Hipótese (§ 2.1, § 6): o code review lê `CLAUDE.md`/`AGENTS.md` e `.github/instructions/`,
  mas não `.claude/rules/`. Observe se ele aponta o import proibido (está no `AGENTS.md` do
  módulo) e o caminho do endpoint (só está em `api-rest.md`) — a diferença entre os dois
  comentários mede o valor do passo 3 da fase 8.

---

## 3. Ordem sugerida e esforço

| Ordem | Blocos | Por quê primeiro |
|---|---|---|
| 1 | § 1.4 sonda + D0 | Se o hook não sobe no Copilot CLI, metade do bloco D muda de natureza |
| 2 | A, G | Baratos; G é o controle — tem de dar igual |
| 3 | B, C | Medem o passo 2 da fase 8 |
| 4 | D, E, F | Medem o passo 5 |
| 5 | H, I | Precisam de remote, CI e cota do cloud agent |

---

## 4. Planilha de resultados

Copie para `.probe/resultados.md` no projeto de teste e preencha. Uma linha por teste e
superfície; a coluna Evidência aponta o arquivo em `.probe/evidencias/`.

| ID | Superfície | Claude Code | Copilot | Resultado | Evidência | Observação |
|---|---|---|---|---|---|---|
| D0 | CLI | hook sobe | | | | |
| A1 | CLI | | | | | |
| A1 | VS Code | | | | | |
| A1 | cloud | | | | | |
| … | | | | | | |

E, da sonda (§ 1.4):

| Ferramenta | Chave no Claude Code | Chave no Copilot CLI (PascalCase) | Chave no Copilot CLI (camelCase) |
|---|---|---|---|
| Bash | `tool_input.command` | | |
| Write | `tool_input.file_path`, `tool_input.content` | | |
| Edit | `tool_input.file_path`, `old_string`, `new_string` | | |
| SubagentStart | `agent_type` | — (sem alias) | |

---

## 5. Depois do teste

1. **Atualize a `13-github-copilot.md`** (e o espelho `docs/en/13-github-copilot.md`): cada
   `não verificado` que virou observação ganha o resultado, a versão e a data; cada linha
   que a observação contradiz é corrigida — a documentação do vendor não vence o que foi
   observado, e vice-versa: registre os dois.
2. **Marque o passo 2** do `roadmap.md` § Multi-agent portability com o que funciona como
   está e o que quebra, por skill.
3. **Um achado que pede mudança** — por exemplo, D0 bloqueando tudo, ou B4 mostrando o
   `arch-adopt` invocado sozinho — vai para `/claude-code-architect-designer` com a
   evidência anexada; nada é corrigido direto num template.
4. **A seção `## Under another agent`** do `root.CLAUDE.md.example` diz hoje "may run and are
   not verified" para o Copilot CLI. Se o bloco D provar o payload, o passo 5 deve uma
   migração que reescreve essa frase (`roadmap.md`, passo 1).
5. Um problema achado no projeto gerado, não no Copilot, sai de lá por `/report-issue`.
