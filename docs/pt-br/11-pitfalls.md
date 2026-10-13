# Pitfalls

Fonte primária: `@claude-help.md`, `.claude/schemas/extensions.json` (blocos `settings`,
`injections`, `arguments`, `types`, `guard` e `compose`), o que
`java .claude/hooks/ArchHook.java schema` verifica, e os registros de decisão que cada item
cita.

## Por que esta página existe

Cada item abaixo é uma armadilha que falha **em silêncio**, ou que um hook recusa com uma
mensagem que o leitor já precisa entender. A página tem duas partes:

- **Parte 1 — o runtime do Claude Code.** Vale para qualquer projeto que use skills, agents,
  hooks ou MCP: nada falha, nada avisa, e a peça simplesmente não faz o que o autor acredita
  que ela faz.
- **Parte 2 — este repositório.** Fatos deste `.claude/`: territórios de escrita, pastas de
  spec congeladas, quem é dono do `pom.xml` e do outbox, o que `guard sweep` e `compose gate`
  cobrem e o que não cobrem.

As duas moravam em `@CLAUDE.md` § Known pitfalls, que é lido em toda sessão. A parte 1 saiu
na decisão `0061`, a parte 2 na decisão `0090`. Nenhuma precisa estar sempre em contexto:
são necessárias enquanto alguém projeta ou edita uma peça do `.claude/`, ou escreve sobre
uma — que é quando `claude-code-architect-designer` lê esta página. A maior parte da parte 2
também está atrás de `guard`, `schema` ou `compose gate`, cuja mensagem de bloqueio nomeia a
regra e a correção. `@CLAUDE.md` mantém só os três fatos que mordem em qualquer sessão sem
hook nenhum por trás, e aponta para cá com uma linha na tabela de routing.

**Um pitfall novo entra aqui, nas duas línguas — nunca de volta no `@CLAUDE.md`.** Foi esse
caminho que reencheu a seção entre a `0061` e a `0090`.

O que **não** está aqui: os campos de frontmatter que o runtime reconhece, que são dados com
dono único em `.claude/schemas/extensions.json` e estão descritos em
[01-tipos-de-arquivo.md](01-tipos-de-arquivo.md).

## Parte 1 · Runtime do Claude Code

### Skills

**Uma skill não pode ter o nome de um comando nativo.** O nome da pasta vira o comando, e
`/doctor`, `/init`, `/context`, `/memory` já existem no runtime. É por isso que a skill de
diagnóstico deste repositório se chama `arch-doctor`. Sombrear um comando nativo não gera
erro — executa o comando errado. `ArchHook.java schema` reprova a pasta pelo nome, contra a
lista `types.skill.native_commands` de `.claude/schemas/extensions.json` — que precisa
crescer quando o runtime ganha comando novo.

**`$ARGUMENTS` no corpo de uma skill é interpolado em toda ocorrência**, não apenas sob
`## Target`. Uma frase que *fala sobre* o argumento chega ao modelo com o valor real dentro
dela: o `"invoque test-architect com $ARGUMENTS vazio (modo setup)"` do `/new-feature`
chegou como `"com UC-003-initiate-kyc-verification vazio (modo setup)"` — uma ordem para usar
o modo setup nomeando o argumento que significa modo design. Escreva "o argumento" ou "o
alvo acima"; `ArchHook.java schema` rejeita o literal, lendo `arguments` de
`.claude/schemas/extensions.json`.

**`allowed-tools` com `Bash(comando:*)` verifica cada segmento do pipe separadamente.** Uma
injeção `` !`a | b | c` `` no corpo precisa de regra para `a`, `b` e `c`; falte uma e o
comando inteiro é bloqueado antes de rodar. Escreva injeções como um comando único
(`ls .claude/skills`, não `find … | sed | sort`). Só afeta skills que restringem Bash:
`allowed-tools: Bash` sem filtro deixa o pipeline inteiro passar — e por isso
`ArchHook.java schema` o recusa, salvo com uma linha `**Unfiltered Bash:** <motivo>` no
`## Contract` (decisão 0076).

**Uma injeção `` !`…` `` de frontmatter roda no shell persistente da sessão, no cwd que ele
tiver naquele momento.** Um `cd` para o diretório de uma skill em uma chamada `Bash`
contamina toda injeção de toda skill invocada depois, e um `test -f` relativo passa a
reportar como ausente um arquivo que existe. Leia um template com `Read` em caminho
absoluto, nunca `cd` + `cat`. Toda injeção deste repositório resolve caminhos a partir de
`"${CLAUDE_PROJECT_DIR:-.}"`, e `ArchHook.java schema` bloqueia quem não faz isso — uma
injeção genuinamente independente de cwd precisa de uma regex em
`injections.exempt_patterns`.

**Uma skill com `disable-model-invocation: true` listada no `skills:` de um agent não é
pré-carregada — e nada avisa.** O agent sobe sem ela e o corpo que diz "catálogo pré-carregado"
passa a mandar aplicar algo que o modelo nunca recebeu. Foi o caso de `gof-design-patterns` (então `java-patterns`) em
`java-spring-boot-developer`. Para entregar conteúdo de uma skill manual a um agent, o caminho
é injetar no `SubagentStart` (`ArchHook.java context subagent`, decisão 0077), não o
`skills:`.

**O `model` e o `effort` de uma skill só valem quando ela é digitada como `/nome`.** Quando
o modelo invoca a skill pela ferramenta `Skill`, o turno continua no modelo e no esforço de
quem chamou, sem aviso. A documentação oficial promete o override nos dois caminhos. Medido
no Claude Code 2.1.280 sobre 18 invocações:
- `/arch-doctor` digitado trocou para `sonnet-5`;
- o `git-publish` encadeado pelo `/new-feature` ficou em `opus-5-5` todas as vezes.

As skills de design só rodam em Opus porque o `/new-feature`, digitado, já fixou Opus. Chamada
direto numa sessão em Sonnet, uma delas roda em Sonnet. Bug aberto em
[anthropics/claude-code#98898](https://github.com/anthropics/claude-code/issues/98898), o
mesmo da #79664, que foi fechada por inatividade. Até a correção, não conte com o `model` de
uma skill que só é encadeada. Antes de concluir que um pin vale, confira o `message.model` no
transcript. À parte disso, em auto mode um `model` não suportado também é ignorado; esse
comportamento é documentado. Design: `.claude/decisions/0118-skill-model-pin-audit-tail-and-bsd-sed.md`.

**Tudo que o modelo deve obedecer mora no corpo do arquivo**, nunca no frontmatter.
`metadata.*` foi removido de skills e agents: ownership, `reads`, `handoff` e contratos vivem
na seção `## Contract` do corpo. Não devolva `metadata:` a um `SKILL.md` — custa tokens em
toda invocação e não impõe nada.

**Uma skill pessoal com o nome de uma skill do projeto a substitui, sem aviso.** O Claude Code
resolve um nome como enterprise > pessoal (`~/.claude/skills/`) > projeto (`.claude/skills/`):
uma `~/.claude/skills/use-case-design/` roda no lugar da do pipeline, em `/use-case-design` e
na invocação automática. A linha `Skill shadowing` do `/arch-doctor` nomeia cada colisão; nada
no projeto pode recusar o diretório pessoal. Skill de plugin não faz isso — tem namespace
(`/plugin:skill`) —, mas disputa o gatilho pela `description`.

**Um plugin bloqueado aqui parece quebrado.** `enabledPlugins` traz `false` para os plugins de
`settings.blocked_plugins` (`superpowers`, `mattpocock-skills`): instalados no nível de usuário,
não carregam neste projeto nem no projeto gerado, e nada avisa. É de propósito — suas skills
competem com o pipeline pela invocação automática (decisão 0138). Para usar um mesmo assim,
religue-o só para você em `.claude/settings.local.json`; tirar a linha do `settings.json`
falha o `schema`.

### Frontmatter e validação

**O runtime ignora frontmatter desconhecido, em silêncio.** Um campo inventado é decoração,
não comportamento. Lista dos campos nativos em `@claude-help.md`.

**`claude plugin validate` não valida campos.** Aceita `metadata:`, aceita camelCase numa
skill e aceita um campo inventado, sempre com `✔ Validation passed`. Não olha
`.claude/agents/`, `.claude/rules/` nem `.claude/settings.json`. Pega YAML malformado, e nada
mais. Quem valida campos é `java .claude/hooks/ArchHook.java schema`.

### Hooks

**`.claude/settings.json` só é lido no início da sessão.** Editar hooks no meio da sessão
não tem efeito — é preciso reiniciar o `claude`.

**Um hook falha silenciosamente de quatro formas, nenhuma delas um erro.** Nome de evento
inexistente — nunca dispara. `matcher` em um evento que não lê matcher — não filtra nada.
Pipe ou `&&` dentro de `"command"` — vira parte do nome do arquivo (forma exec: `command` é o
binário, `args` são os argumentos). Um modo que lança exceção — sai 0 pelo catch do `main`,
e parece ter passado. `schema` pega as três primeiras, a partir do bloco `settings` de
`.claude/schemas/extensions.json`; a quarta, só rodando o modo à mão.

**Um `if` com caminho só casa via `Edit(...)` ou `Read(...)`.** `Edit` cobre toda ferramenta
nativa que escreve arquivo, `Write` incluído; um `"if": "Write(.claude/**/*.md)"` parece um
filtro e não filtra nada. Fonte: `docs/pt-br/claude-code-docs/07-settings-permissoes-e-seguranca.md`.

**Só os modos de protocolo de hook do `ArchHook.java` leem stdin** — `check`, `format`,
`tests`, `schema`, `audit`, `guard`, `context` e `compose gate`. Invocar um deles à mão sem
`</dev/null` bloqueia até algo fechar o stdin, sem saída: um comando que parece pendurado, não
falho. `export`, `doctor`, `build` e o `compose` puro são invocados por pessoas e não leem nada.

### `AskUserQuestion`

**Rejeita uma pergunta com menos de 2 opções, e rejeita o lote inteiro com ela:**
`InputValidationError ... "too_small" ... path: ["questions",1,"options"]`. Uma pergunta com
uma opção não é pergunta — decida, e registre a decisão onde a resposta iria. O lote também
tem limite superior de 4 perguntas por chamada.

**Rejeita uma pergunta com mais de 4 opções, do mesmo jeito:**
`InputValidationError ... "too_big", "maximum": 4 ... path: ["questions",0,"options"]`. Sete
escolhas não são uma pergunta: pergunte só o que ainda está em aberto — o resto já está decidido
em algum lugar, como o `features: true` de um blueprint — ou ofereça quatro e deixe as outras
para o Other. Vale o mesmo para uma chamada com mais de 4 perguntas: as seguintes vão na
próxima chamada. O projeto gerado carrega este mesmo pitfall
no seu próprio `CLAUDE.md`, vindo de
`project-bootstrap/templates/root.CLAUDE.md.example`.

### MCP

**`.mcp.json` só é lido no início da sessão**, igual ao `settings.json`. Adicionar ou editar
um servidor no meio da sessão não tem efeito até reiniciar o `claude`.

**Um servidor de escopo project em `.mcp.json` precisa de aprovação humana uma única vez**, na
primeira vez que carrega (`claude mcp list` mostra os pendentes). Esse prompt é a fronteira
de confiança que um repositório clonado não pode pular — nunca contorne com
`enableAllProjectMcpServers`.

**A precedência de servidores é `local > project > user`, silenciosamente.** Um servidor
pessoal com o mesmo nome do servidor do time em `.mcp.json` sombreia o do time — sem aviso de
nenhum dos lados.

**Um `${VAR}` não definido em `.mcp.json` não falha a geração.** O servidor carrega com o
texto literal `${VAR}` e falha ao conectar em runtime. `claude mcp list` mostra o aviso de
variável ausente; a validação de schema de frontmatter não.

### `CLAUDE.md` e `AGENTS.md`

**Um `AGENTS.md` de módulo sozinho é invisível ao Claude Code enquanto a raiz tem um
`CLAUDE.md`.** A documentação diz que o Claude Code lê `AGENTS.md` quando não há `CLAUDE.md` "no
caminho", e deixa em aberto se o da raiz conta; o Claude Code 2.1.296 não carregou o do módulo
num Read. O que carrega é um `CLAUDE.md` no módulo contendo só `@AGENTS.md` — num Read, Edit ou
Write sob o módulo, dentro de subagent também. O modo `claude-md-and-agents-md` resolveria, mas
é ignorado em settings de projeto. Num projeto gerado, a linha `Agent instructions` do
`/arch-doctor` nomeia o módulo sem o import. Design:
`.claude/decisions/0139-agents-md-root-pointer-module-owner.md`.

## Parte 2 · Este repositório

### Território de escrita e os guards

**Uma skill escreve só no território da sua classe, e `guard` bloqueia o resto com exit 2.**
Negação por padrão; a mensagem nomeia a classe e o seu `write_allow`, e a correção é o dado
(`skill_classes` em `.claude/schemas/extensions.json`), nunca uma nova tentativa. Uma rodada
de design só escreve docs e não pode nem *chamar* uma skill de classe `build` —
`docker-architect` inclusive: a rodada de design continua sem poder chamá-la, mas pergunta: quando falta o serviço de
compose, `persistence-architect` e `messaging-architect` registram a resposta no parcial, e a
rodada de implement adiciona cada linha `materialize` por meio do `compose-service-installer`. Sem fase de skill aberta, o território é livre,
e é por isso que editar um arquivo à mão nunca é bloqueado.

**Uma skill que encadeia outra da mesma classe soma o território dela; de outra classe, troca.**
Mesma classe — `arch-adopt` → `sonarqube-setup` → `docker-architect`, todas `build` — e a fase
guarda as três, então cada uma escreve os próprios caminhos e quem chamou continua escrevendo
depois que a chamada volta. Outra classe — `/new-feature` chamando uma skill de design — e a
fase se estreita para a chamada, que é o que mantém um passo de design só em docs. A regra
mantinha só o território de quem chamou numa chamada da mesma classe, supondo que uma classe
é um território; `build` dá um território a cada skill, e o `sonarqube-setup` encadeado pelo
`arch-adopt` teve o `pom.xml` recusado. Design:
`.claude/decisions/0092-guard-same-class-chain-sums-territories.md`.

**A escrita de um subagent é julgada por `agent_classes`, não pela fase de quem o chamou.**
Toda escrita carrega o seu `agent_type`, e `guard` confere o caminho contra o `write_allow`
do próprio agent, então uma fase de design aberta nem amplia nem estreita o território dele.
Um agent que nenhuma classe lista cai de volta na fase de quem chamou — nada mais descreve o
que ele pode escrever.

**O enforcement deixou de depender da tool, mas ainda não é do tamanho do filesystem.** Até
o lessons-learned-014 § 1, todo hook casava pelo nome de uma tool, então um heredoc, um
`sed -i` ou um `tee` passavam por `guard`, `check`, `format`, `audit` e `schema` do mesmo
jeito — exatamente as formas que uma instrução do host para preferir `Bash` a `Write`/`Edit`
produz. `guard bash` (`PreToolUse`, matcher `Bash`) agora lê o comando procurando as formas
de escrita de `guard.bash_write_shapes` e aplica as checagens de território e de pasta
congelada a todo alvo que consegue ler literalmente. **O que ele deliberadamente não faz:**
um alvo com `$`, crase ou glob, ou que cai fora do repositório, é pulado sem aviso, e `check`
e `format` ficam fora do `Bash` — ArchUnit e `spotless:apply` já rechecam os dois, e um
matcher `PostToolUse` ali pagaria uma JVM a cada `ls`. A afirmação verdadeira é estreita:
**dentro do Claude Code, via `Write`/`Edit`/`MultiEdit`/`NotebookEdit`, e via as formas de
shell que a lista nomeia.** Design: `.claude/decisions/0063-bash-write-enforcement.md`.

**`guard sweep` cobre os dois guards de escrita no `Stop`, e ele é do tamanho do git.** Ele
compara a working tree com a baseline que `guard prompt` tira no `UserPromptSubmit`, então
uma árvore já suja antes do turno não é reportada, e roda as mesmas checagens de território e
de pasta congelada sobre o que mudou — não importa qual tool escreveu. Dois limites antes de
lê-lo como cobertura total: é **detecção, não prevenção** (a escrita já aconteceu), e um
caminho que o git ignora nunca aparece em `git status --porcelain` e nunca é varrido — o que,
neste repositório, é só o que o `.gitignore` ainda lista, já que `.claude/decisions/` e
`.claude/lessons-learned/` são versionados desde 2026-09-28. O fechamento do `status:` de uma
spec e os toggles `[ ]` → `[x]` são admitidos comparando com `git show HEAD:`, já que o sweep
não tem `old_string` para ler. Caminhos em `guard.sweep_exempt` são pulados — hoje `.claude/audit-usage/**`, a trilha versionada que o hook `audit` grava em paralelo com a baseline; os guards de tool-time continuam recusando escrita do modelo ali. **Um caminho que `guard write` ou `guard bash` já admitiu no turno também é pulado:** ele foi julgado contra a fase e o status da spec do próprio momento, e julgá-lo de novo no `Stop` contra a fase aberta ali — o território vazio do `git-publish` depois do `/new-feature` — ou contra uma pasta aprovada depois marcava a própria spec, os parciais e as escritas do executor da rodada (issue #74). O que o sweep julga são as escritas que nenhum guard de tool-time viu, contra a fase aberta no `Stop`; um caminho admitido antes e reescrito depois por uma grafia que nenhum guard lê é pulado junto. Design: `.claude/decisions/0065-guard-sweep-on-stop.md`, isenção `0105`, caminhos admitidos `0114`.

**Um push sempre pede confirmação, um force push nunca roda, e o `Bash` de uma skill é
restrito.** `git push` e `gh repo create` ficam em `permissions.ask`, que é avaliado antes de
qualquer allow — então o prompt aparece até dentro de `git-publish`, depois do gate dela.
`guard bash` recusa force push em toda grafia que consegue ler (`-f`, `-uf`,
`--force-with-lease`, `+ref`, `git -C … push`, `sh -c "…"`), listadas em `guard.force_push`;
a antiga linha de `deny` só pegava `--force`. Uma skill cujo `allowed-tools` nomeia `Bash` sem
filtro falha no `schema`, a menos que o seu `## Contract` traga uma linha
`**Unfiltered Bash:**` dizendo por quê — quatro trazem (as que rodam build, rede e docker); as
demais listam prefixos, e um comando de injeção que falta nessa lista aborta a skill. Design:
`.claude/decisions/0076-bash-scope-and-force-push-guard.md`.

**Comentar, editar, fechar uma issue e `gh api` sempre pedem confirmação, neste repo.** As
quatro formas ficam em `permissions.ask` de `.claude/settings.json` — o prompt aparece até
depois do sim do `/triage-issue`, e é a mesma confirmação que você vê se o corpo de uma issue
convencer o `issue-verifier` a tentar publicar algo. Não é ferramenta quebrada: o texto de uma
issue pública é de terceiros. Só aqui; o projeto gerado não tem essas linhas. Design:
`.claude/decisions/0103-issue-filing-and-skeptical-triage.md`.

### Classes e modelos

**Toda skill declara `model`, dentro do conjunto da sua classe.** `schema` reprova um
`SKILL.md` sem ele, ou com um valor fora de `skill_classes.classes.<c>.allowed_models`
(`design` e `meta` só `opus`, `observer` e `ops` só `sonnet`). O `model` de uma skill vale
pelo resto do turno, não só pela skill — é por isso que um modelo fixado sozinho não é motivo
para ser agent, e por isso que uma skill com modelo mais baixo disparada no meio do turno é o
caso a vigiar. **Hoje isso vale só para a skill digitada:** encadeada pela ferramenta `Skill`,
ela roda no modelo de quem a chamou (Parte 1 § Skills, #98898). Design: `.claude/decisions/0081-skill-model-required-per-class.md`.

### Specs de caso de uso

**A pasta de um caso de uso implementado fica congelada, exceto por três escritas:** a linha
`status:` da spec movida ao longo de `guard.status_transitions`, um toggle de checklist nela,
e `UC-NNN/CHANGELOG.md` — a escrita que a consolidação do `/new-feature` *exige* para toda
mudança que uma linha de impacto faz. Os basenames isentos são dados
(`guard.frozen_exempt_basenames`), casados diretamente sob a pasta: `notes/CHANGELOG.md`
continua congelado.

**A spec tem três estados fechados, e o checklist é marcado antes de o status fechar.**
`approved` fecha em `implemented`, ou em `implemented-blocked` quando a rodada deixou um caso
de uso aprovado inalcançável de ponta a ponta — o código está em disco e verde, e um
`Satisfied by` que a spec nomeou ainda não existe. Os dois voltam para `approved`, que é a
saída que uma rodada fechada por engano não tinha. Um toggle `[ ]` → `[x]` é admitido **nos
três** (`guard.checklist_toggle_statuses`): ele é monotônico, e exigir `approved` já congelou
23 caixas desmarcadas para sempre. A ordem continua sendo contrato do executor, não do hook: a
linha de status é a última escrita que ele faz na pasta. Design:
`.claude/decisions/0066-spec-state-machine.md`.

### Compose

**`compose gate` roda a checagem de compose sem ninguém pedir, e bloqueia.** O step 7 do
`docker-architect` já chamava `ArchHook.java compose` de "não opcional", e ele nunca tinha
sido rodado contra um projeto: um bloco `kafka` publicando 9092 enquanto anunciava só
`kafka:9092` saiu no primeiro dia e ficou inalcançável para todo cliente do host até um caso
de uso precisar dele. O gate é o mesmo `composeReport()` — uma definição só de saudável,
compartilhada com o `doctor` — silencioso enquanto saudável, exit 2 com as linhas que falharam
caso contrário. Um serviço parado de propósito também bloqueia o stop; isso é um gate, não um
bug. Design: `.claude/decisions/0064-compose-gate-on-stop.md`. A exceção é um container de
outro projeto numa das portas de host declaradas: enquanto nenhum container deste projeto
existe (`docker compose ps -a` não lista nada), ela vira um aviso que `compose` e `doctor`
mostram e o gate não bloqueia, porque uma stack que ninguém subiu ainda não tem o que bindar.
Assim que existe qualquer container do projeto, parado ou `created` inclusive, bloqueia como
antes. Issue #107, `.claude/decisions/0129-compose-gate-foreign-port-warns-without-our-stack.md`.

**Um healthcheck que passa não prova nada sobre alcance a partir do host** — ele roda dentro
do container, onde `localhost` é o próprio serviço. Um serviço que publica uma porta no host
enquanto anuncia só o seu nome na rede do compose (`KAFKA_ADVERTISED_LISTENERS:
PLAINTEXT://kafka:9092` ao lado de `ports: "9092:9092"`) não é alcançável por nenhum cliente
do host, e nem o healthcheck nem o Testcontainers percebem — o Testcontainers monta os
próprios listeners. `ArchHook.java compose` lê o arquivo procurando isso
(`compose.advertised_env_suffixes`); um serviço que não anuncia nada não afirma nada e é
deixado em paz.

**Um healthcheck que chama `localhost` dentro da imagem `caddy:2.11.6-alpine` nunca fica
saudável.** O `wget http://localhost:2019` do busybox resolve `localhost`
primeiro em IPv6, para `::1`, e a API de admin escuta só em `127.0.0.1` — a conexão é
recusada e o healthcheck falha mesmo com o proxy bem. O serviço de borda do
`docker-architect` nomeia `127.0.0.1` explicitamente no healthcheck, nunca `localhost`.
Design: `.claude/decisions/0122-transport-security-setup-and-secrets.md`.

**Todo `${VAR:default}` que aponta para um serviço do compose vale em dois lugares.** O
default serve o `./mvnw spring-boot:run` no host: precisa ser `localhost` numa porta que o
serviço **publica**. A variável no `environment:` do `app` serve o container: sem ela,
`localhost` lá dentro é a própria aplicação. O collector OTLP não publicou porta da 0046 até
a issue #66, e todo export do host falhou com o `compose` dizendo saudável. Agora a pergunta 5
do `compose` lê os dois lados, e o gate bloqueia um projeto antigo logo depois do
`/arch-adopt`. A saída é o prompt da migration `otlp-host-run`. Se outro projeto segura a
4318, use `OTLP_HTTP_PORT` no `.env` e a mesma porta em `OTLP_ENDPOINT`/`OTLP_METRICS_ENDPOINT`
no host. Design: `.claude/decisions/0110-otlp-host-first-collector.md`.

**Um socket que abre não é um login que funciona.** A pergunta 5 compara host e porta, então
`jdbc:postgresql://localhost:5432/bankingapp` passou contra um serviço `postgres` que criou
`appdb` para o usuário `app`, e o run no host morreu no login com o `compose` dizendo
saudável (lessons-learned-021). O datasource e o serviço de banco agora leem um único conjunto
de variáveis — `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_PORT` — com o nome do artifact como
default nos dois lados, e a senha só no `.env` não versionado que o compose lê e o
`application.yml` importa. `compose` e `doctor` mostram uma divergência de banco ou usuário,
ou a falta de senha onde o serviço exige uma, como **aviso**: o gate nunca bloqueia por isso,
porque um projeto pode apontar o datasource para outro banco de propósito. Nunca imprime o
valor de uma senha. Projetos gerados antes recebem o prompt da migration
`datasource-env-convention` do `/arch-adopt`. Design:
`.claude/decisions/0124-datasource-defaults-one-convention-checked-by-compose.md`.

**A imagem do banco aplica `DB_NAME`, `DB_USERNAME` e `DB_PASSWORD` só num volume vazio.**
Depois do primeiro start, o volume nomeado mantém o banco e o usuário antigos, enquanto
`docker exec … env` mostra os valores novos — a checagem a que todo mundo recorre mente.
Só `docker compose down -v` aplica os valores novos, e apaga os dados. Nada checa isso; os
templates de serviço e o `CLAUDE.md` raiz gerado dizem.

**`grep -A2 "^services:" docker-compose.yml` não é a lista de serviços.** Ele lê duas linhas
e para, perdendo serviços declarados mais abaixo e reportando os filhos de `volumes:` como
serviços. Toda peça que precisa dessa lista — a injeção e o step 3 do `docker-architect`, o
step 9 do `messaging-architect`, o step 9 do `persistence-architect` — usa o one-liner `awk`
limitado ao bloco `services:`. Um retrato errado convida a recriar um serviço que já existe.

### Segurança de transporte

**Um SSL bundle com arquivo ausente derruba o startup mesmo com `server.ssl.enabled:
false`.** O `sslBundleRegistry` carrega todo bundle de forma antecipada, então desligar o
TLS na configuração não impede o Boot de tentar ler o arquivo. Por isso o bundle da
topologia B vive dentro de um documento YAML ativado pela expressão de profile
`"!local & !docker & (!test | tls)"`, nunca atrás de uma flag que deixa o documento ativo
sem arquivo nenhum por trás. Design:
`.claude/decisions/0122-transport-security-setup-and-secrets.md`.

**Atrás de uma borda sem `server.forward-headers-strategy`, o HSTS nunca é escrito e nada
reporta isso.** A aplicação acredita que toda requisição chegou em HTTP puro, então a
precondição do header nunca é satisfeita — um header de segurança ausente, em silêncio,
não um erro em lugar nenhum. Design: `0122`.

**O `HstsHeaderWriter` do Spring Security só escreve o header quando ele está ausente — um
filtro que continua depois que o Spring Security chega sobrescreve a cadeia em silêncio.**
É por isso que o `HstsHeaderFilter` do `transport-security-setup` é
`@ConditionalOnMissingClass(SecurityFilterChain)` e se afasta no instante em que o starter
de segurança é adicionado, em vez de os dois escreverem o header na mesma resposta. Design:
`0122`.

**Com a topologia B, todo `@SpringBootTest` precisa rodar com `@ActiveProfiles("test")`, ou
o contexto passa a subir com TLS ligado e falha lendo `/etc/tls`.** A falha aparece como um
arquivo ausente lá no fundo do stack trace, não como uma mensagem clara de "profile
errado" — o step 5 do `transport-security-setup` adiciona a anotação em todo
`@SpringBootTest` existente que encontra sem ela. Design: `0122`.

**Os testes de integração de transporte geram o próprio certificado em runtime com o
`keytool` do JDK — nenhum arquivo de chave é commitado, nem para teste.** Um certificado
"só para teste" commitado acaba confiável em algum lugar que não deveria
(`@.claude/rules/secrets.md`). Design: `0122`.

### Donos dentro de uma rodada de feature

**O `pom.xml` tem exatamente um escritor numa rodada de feature: o executor, para uma
dependência que a spec declara** (o § 7 do parcial de mensageria, ou o equivalente de
persistência). As skills de design só escrevem docs, `/new-feature` escreve a spec, e os dois
installers são donos só do próprio setup. Qualquer outra coisa no arquivo — um plugin, uma
property, um bump de versão — é reportada, nunca escrita.

**Fora de uma rodada de feature, as linhas `sonar.*` do build file raiz são do
`sonarqube-setup`.** Ele escreve o plugin do scanner e as properties uma vez — encadeado pelo
step 8.4 do `project-bootstrap` ou pelo `arch-adopt` — e para numa segunda execução quando
o scanner já está lá. O serviço `sonarqube` do compose continua sendo do `docker-architect`, e
o token não fica em arquivo nenhum: o scanner lê `SONAR_TOKEN` do ambiente. Design:
`.claude/decisions/0091-sonarqube-setup-skill.md`.

**Fora de uma rodada de feature, o transporte é decidido uma vez pelo
`transport-security-setup`, e dali se divide em quatro.** A topologia, as linhas de TLS do
`application.yml`, o escritor do HSTS e o IT de transporte são território da própria skill
de setup — encadeada pelo step 7.5 do `project-bootstrap` ou pelo step 7.5 do
`arch-adopt`, e pulada numa nova execução quando o `CLAUDE.md` raiz já carrega um
parágrafo `**Transport:`. O serviço `edge`, o Caddyfile dele e a porta exposta do
`Dockerfile` continuam do `docker-architect`, chamado de dentro da skill de setup. O TLS
de saída — um certificado de cliente ou uma CA privada para uma chamada que este serviço
faz — é do `http-client-architect`, nunca da skill de setup. O mecanismo de autenticação
por certificado de cliente da topologia C fica adiado para o `security-architect`,
desenhado só quando a regra de acesso de um caso de uso nomeia um chamador máquina.
Design: `.claude/decisions/0122-transport-security-setup-and-secrets.md`.

**O outbox tem três donos, divididos por pergunta.** `persistence-architect`: a tabela, as
colunas, a claim query, o batch size, o teto de tentativas, a janela de retenção e o
statement do prune. `messaging-architect`: se o caso precisa de um, a garantia de entrega, e a
passada do relay com o lado do broker. `jobs-architect`: quando qualquer coisa roda — o
intervalo de poll do relay, o liga/desliga, o número de réplicas que a claim precisa
suportar, e o job de prune. Uma linha de § 6 nomeando colunas é divergência, e uma decisão de
coluna ou de claim que derrubaria uma garantia declarada para o pipeline em vez de ser
resolvida por precedência (`.claude/decisions/0087-jobs-architect-skill.md`).

**Cache precisa de uma tolerância que o negócio declarou, e mora num pacote só.** O
`cache-architect` só roda quando a linha `Freshness` de `00-caso-de-uso.md` diz quão velha uma
resposta pode ser — um spec aprovado antes dessa linha existir nunca o dispara, e "a query
parece lenta" não é tolerância. O cache é um decorator do port de saída no pacote `.cache` do
blueprint, registrado como o bean `@Primary` do port pelo nome do bean delegado: um
`@Cacheable` num adapter de persistência ou num service está fora de `rules/caching.md`, um
escritor que contorna o port (um `UPDATE` em massa, um job, outro serviço) não invalida, e
`sync = true` junto de `unless` falha no startup. O second-level cache do Hibernate fica
desligado; cache HTTP é do `rest-api-architect` (`.claude/decisions/0144-cache-architect-skill.md`).

**Um modelo ou um vector store é um port só, e quatro parciais são donas das suas peças.** O
`ai-architect` só roda quando `10-dominio.md` declara um port do tipo `LLM` ou `vector store`, e
`29-ia.md` é dona do provedor, do modelo, dos prompts, do tipo da resposta, dos dados enviados, do
timeout e da única camada de retry. O port, o valor "não consegui decidir" e o estado de revisão
continuam sendo de `10-dominio.md`; a migration do vector store é de `20-persistencia.md`
(`29-ia.md` § 9 só pede); um gatilho de reindexação ou um job de ingestão é de
`25-mensageria.md` ou `35-jobs.md`. O Spring AI mora só no pacote `.ai`, então
`org.springframework.ai..` num módulo `application` é violação de fronteira
(`.claude/decisions/0145-ai-architect-skill.md`).

**Uma operação GraphQL é um input port só, e quatro parciais são donas das suas peças.** O
`graphql-api-architect` só roda quando a linha `Trigger` de `00-caso-de-uso.md` termina em
`inbound GraphQL adapter` ou `inbound REST and GraphQL adapters`, e `31-graphql.md` é dono dos
root fields, dos tipos, dos payloads, das connections e dos limites do projeto. Um port de batch
que ele precisa chega como requisito de `31-graphql.md` § 9 e vira o port de `10-dominio.md` na
consolidação; o statement de batch e o índice que a ordem de uma connection precisa são de
`20-persistencia.md`; quem pode ler cada root field e cada campo sensível são as linhas de acesso
de `32-seguranca.md`, nunca de `31-graphql.md`. O snapshot do schema do
`GraphQlSchemaCompatibilityTest` fica em `src/test/graphql/`: em `src/test/resources` ele estaria
no classpath de teste. O Spring for GraphQL mora só no pacote `.graphql`
(`.claude/rules/api-graphql.md`, `.claude/decisions/0146-graphql-api-architect-skill.md`).

**A família de storage é um dado do caso de uso, e nenhum passo do pipeline a pondera.** O
`use-case-design` a registra na linha `Storage` de `00-caso-de-uso.md` — por agregado,
`relational` ou `document (MongoDB)` — a partir do pedido, ou de uma única pergunta factual
("SQL ou NoSQL?"), e nunca defende uma das duas; essa entrevista de decisão é de uma skill de
solution-design planejada. O `persistence-architect` para sem a linha. O MongoDB precisa de
replica set em todo lugar, inclusive no serviço de compose e no teste de integração, porque um
servidor standalone recusa toda transação; e declarar um `MongoTransactionManager` remove o
transaction manager JPA do Spring Boot, então o formato que mantém o JPA ao lado do MongoDB
declara os dois (`.claude/rules/persistence.md` § Document (MongoDB),
`.claude/decisions/0147-document-storage-and-search-architect.md`).

**Um índice de busca é uma projeção, e cinco parciais são donas das suas peças.** O
`search-architect` só roda quando a linha `Search index` de `00-caso-de-uso.md` nomeia uma
projeção, e `23-busca.md` é dono do documento, do mapping, do alias, da consulta, das regras de
escrita do indexer, do adapter de rebuild e do lag. A versão da fonte e o evento de outbox são de
`20-persistencia.md`; o consumer do indexer é de `25-mensageria.md`; o job de rebuild é de
`35-jobs.md`. Um cache sobre um port de busca recebe o `Freshness` do caso menos o lag, nunca ele
inteiro. O Spring Data Elasticsearch e o OpenSearch moram só no pacote `.search`, então seus
imports num módulo `application` são violação de fronteira (`.claude/rules/search.md`,
`.claude/decisions/0147-document-storage-and-search-architect.md`).

**O bounded context é um fato do projeto, não uma resposta por caso de uso.** É o primeiro
segmento de todo nome de tópico, perguntado junto com as coordenadas no `/init-project` e
escrito no `CLAUDE.md` raiz do projeto gerado. Um prefixo escolhido dentro de um caso de uso
dá a um sistema dois namespaces. Um projeto adotado por `/arch-adopt`, ou gerado antes de a
pergunta existir, recebe a pergunta no step 8 dessa skill — mas só na **segunda** execução
de `/arch-adopt` depois que a mudança for publicada: a primeira roda a cópia antiga da
skill, que ainda não tem o step. Até lá, escrever a linha à mão é o jeito imediato de
resolver. Design: `.claude/decisions/0093-lessons-learned-016-fact-sources.md`.

**O hook `tests` do `Stop` não roda enquanto um subagent escritor trabalha em background.** Um
agente cuja classe em `agent_classes` tem `executor: true` deixa um marcador no
`SubagentStart` e o apaga no `SubagentStop` do mesmo `agent_id`. Enquanto o marcador existe, o
`Stop` da thread principal imprime `⏸ Tests deferred: <agente> still running in background` e
sai 0, sem chamar o Maven. Quem roda os testes é o `Stop` do turno que a notificação do agente
abre. Um marcador mais velho que `tests.writer_agent_max_minutes` é apagado e ignorado: um
agente morto pelo sono da máquina nunca chega ao `SubagentStop`. Design:
`.claude/decisions/0116-tests-defers-while-a-writer-subagent-runs.md`.

**`✅ verify … green` no `Stop` não é o CI verde.** O gate roda `tests.stop_maven_args` —
`verify -DskipITs -Djacoco.skip=true` — nos módulos alterados, offline. Então `spotless:check` e
o `checkstyle-test` rodam em todo fim de turno, mas os ITs (Testcontainers) e o `check` do
JaCoCo ficam com o `./mvnw verify` do executor e com o `./mvnw clean verify` do CI. Um plugin do
próprio projeto ligado a `verify` que precise de rede bloqueia offline: o skip dele vai nessa
lista, nunca o plugin desligado. Sem a chave, o gate bloqueia e a nomeia. Design:
`.claude/decisions/0142-stop-gate-runs-verify-without-its.md`.

**Um A/B de custo atravessando o `/arch-adopt` perde as linhas do "antes" se o commit daquele
run foi recusado.** A trilha `.claude/audit-usage/` é stageada junto com o commit do run pelo
`git-publish`. Recusado o commit, ela fica como append não commitado, e o `/arch-adopt` recusa
um worktree sujo sem exceção para essa pasta — limpar o worktree para atualizar apaga o
`nodes.jsonl` do run de antes. Antes de atualizar, commite a trilha à parte, ou copie o
relatório e as linhas do run. Visto na issue #111. Design:
`.claude/decisions/0132-audit-per-piece-cache-and-context-growth.md`.

**Um bulk write num agregado auditado não deixa revisão, e nada avisa.** Um `UPDATE`/`DELETE` em
JPQL (`@Modifying`), `CriteriaUpdate`, `CriteriaDelete` ou SQL nativo muda as linhas e não passa
pelos listeners do Envers: o histórico fica sem a mudança, sem erro, sem teste vermelho. Nenhum
hook cobre isso — não houve falha observada que justifique um. O que existe é o `grep` de
`.claude/rules/persistence.md` § How to verify e a lista de bulk writes que o § Audit do
`20-persistencia.md` registra. O mesmo vale para o ator: uma passada de job ou consumer sem
`SystemActor` falha na escrita, por desenho — o ator nunca é `null`. Design:
`.claude/decisions/0149-auditing-variant-of-persistence-architect.md`.

**A versão da API é uma configuração por projeto, escrita pelo primeiro caso REST.** O
`ApiVersioningConfig` adiciona o prefixo `/api/v{version}` aos controllers do pacote do adapter
REST, e só a eles: um controller com `/api/v1` ainda no mapping de classe passa a responder em
`/api/v1/api/v1/...`. Por isso um projeto gerado antes de `0150` move todo controller antes de
ligar a config — a migração `api-version-path-prefix` diz como, e nenhuma URL pública muda. O
prefixo é `/api/v{version}`, não `/api/{version}` (o springdoc documentaria `/api/1/users`), e é
restrito ao pacote, não só a `@RestController` (o controller do próprio springdoc receberia o
prefixo e o `/v3/api-docs` daria 404). Uma regra de request em `/api/v1/…` deixa a `/v2` da mesma
rota só com `authenticated`: as regras escrevem `/api/*/…`. E o documento OpenAPI lista cada
operação só na versão em que o mapping começa — uma operação que chega à v2 pelo baseline não
aparece de novo lá (`.claude/rules/api-rest.md` § Versioning,
`.claude/decisions/0150-api-versioning-in-rest-api-architect.md`).

### Mods

**Abaixo do Claude Code 2.1.287 nenhum mod carrega, e nada na sessão avisa.** A faixa e o
`/nerviz-doctor` simplesmente não aparecem. A linha `Mods` do
`/arch-doctor` compara o CLI do PATH com `mods.min_version`. Em `claude -p`, VS Code, sessão
cloud ou sob `--safe-mode` eles não aparecem por design: os guards que eles desenham
continuam impondo.

**O marketplace de `.claude/mods/` só registra depois que o workspace trust da pasta é
aceito, e resolve contra o checkout principal.** Num git worktree o mod carrega do checkout
principal, não do worktree em edição; teste uma edição ali com
`claude --plugin-dir .claude/mods/<name>`. Uma edição vale na próxima sessão ou depois de
`/reload-plugins`.

**O `schema` no `Stop` bloqueia um mod que carregaria pela metade** — evento desconhecido,
hook de gating sem `.catch`, chamada proibida, programa que não é `java`, mod que o
marketplace ou o `enabledPlugins` esqueceu, sem testes. A mensagem nomeia o defeito e a chave
de `mods` dona da lista. Design: `.claude/decisions/0131-mods-in-architect-designer.md`.

**O diálogo do cockpit só aparece numa recusa do `guard`.** Ele reconhece uma pelos
fragmentos de `mods.deny_markers`; uma recusa digitada num prompt de permissão não carrega
nenhum e não pergunta nada. Reescrever uma mensagem do guard sem atualizar o marcador faz o
`schema` falhar pelo nome — senão o diálogo pararia de aparecer em silêncio. A recusa vale
seja qual for o botão; "Stop the turn" só encerra o turno.

### Rules e decisions

**Uma rule sem `paths` carrega no launch, em toda sessão — não é "só citação".** Por isso toda
rule declara o glob mais estreito que a sustenta, e Java é `**/src/**/*.java`, nunca
`**/*.java`: esse casa com `.claude/hooks/ArchHook.java` e puxa toda norma Java a cada leitura
do hook. O nome antigo de uma rule renomeada vai em `export.retired`, ou o destino fica com as
duas. Design: `.claude/decisions/0082-rules-without-paths-load-at-launch.md`.

**`.claude/decisions/` não é norma nem documentação viva.** Registra o que foi decidido na
data, não o que vale hoje: sem `paths`, fora do `00-index.md`, e não viaja para o projeto
gerado. Um registro novo substitui o antigo; o antigo não é reescrito.
