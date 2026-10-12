# `validate.yml` — CI dos invariantes

Fonte primária: `.github/workflows/validate.yml`, `.claude/hooks/ArchHook.java`
(método `schema()`), `CLAUDE.md` § Invariantes.

## Por que existe

Os invariantes de `CLAUDE.md` e as normas em `rules/` são prosa, não código — nada
impede que uma edição viole um deles em silêncio. `ArchHook.java check` só guarda a
fronteira Java de um projeto **gerado**; nunca roda contra o próprio grafo de prompts
deste repositório. `validate.yml` é o que transforma cada invariante numa verificação
determinística e cobrindo o repositório inteiro, a cada `push` e `pull_request`, em vez
de uma alegação que só se sustenta enquanto quem escreve a próxima skill lembra dela.

## Diagrama dos jobs

```mermaid
flowchart TD
    T[push / pull_request / workflow_dispatch] --> J1
    T --> J2
    T --> J3
    T --> J4

    subgraph J1["hooks-cross-platform (matrix: ubuntu · macos · windows)"]
        H1[ArchHook.java doctor]
        H0[build --verify — ArchHook.jar commitado = o que o source compila]
        H2[BoundaryTest.java — import proibido → exit 2, pelo payload e por `check <path>`]
        H3[InjectionPathTest.java — injection relativa ao cwd → exit 2]
        H4[ComposeTagTest.java — tag de image divergente de src/test → reportada]
        H5[SkillTerritoryTest.java — escrita fora do write_allow da classe → exit 2]
        H6[AgentTerritoryTest.java — agent_type fora do write_allow da classe → exit 2]
        H7[BashGuardTest.java — force push / escrita de shell fora da fase → exit 2]
        H8[SweepTest.java — escrita do turno fora do território → exit 2 no Stop]
        H9[ComposeGateTest.java — porta publicada anunciada só na rede interna, ou placeholder que o host ou o app não alcança → exit 2]
        H10[SubagentContextTest.java — catálogo só para agents pattern_catalog]
        H11[AuditRenderTest.java — onde a execução gastou, erros redigidos, classes observer puladas]
        H12[MigrationsSchemaTest.java — entrada de migrations que nenhum projeto veria → exit 2]
        H13[ModuleMapTest.java — arquivo editado → o módulo Maven que format, check e tests passam no -pl]
        H14[TestsDeferTest.java — subagent escritor da sessão rodando → tests adiado, Maven não chamado]
        H15[GenesisTest.java — números do GENESIS vindos das transcrições, preenchidos uma vez]
        H16[DoctorGateTest.java — linha gated do doctor vermelha → exit 1; estado do runner nunca bloqueia]
        H17[GuardStatusTest.java — guard status imprime a fase aberta, a classe e o território]
        H18[ModsSchemaTest.java — mod que carregaria pela metade → exit 2]
        H19[PluginBlockTest.java — settings sem o false de um plugin bloqueado → exit 2]
        H20[SkillShadowTest.java — skill pessoal com nome de skill do projeto → nomeada; nunca bloqueia]
    end

    subgraph J2["design (ubuntu-latest)"]
        D1[schema — frontmatter, shadow de comando nativo, manifesto de export vs disco]
        D4[blueprint não toca prompts]
        D5[rules é folha]
        D6[norma sem boilerplate de código]
        D7[sem commands/]
        D8[sufixo .example]
        D10[XmlTemplatesTest.java — todo *.xml.example faz parse]
        D17[SpecDiagramTemplateTest.java — diagrama dos templates de spec só com nomes do texto]
        D16[corpo de exemplar sem comentário além de Javadoc]
        D9[templates do blueprint existem]
        D10[dono único da norma]
        D15[templates de serviço do compose mesclam e parseiam]
        D11[sem dependência fora do ecossistema Java]
        D12[decisions/ isolado]
        D13[sem versão hardcoded]
        D14[paths bate com blueprint]
    end

    subgraph J3["exemplar-imports (ubuntu-latest)"]
        E1[baixa starter.tgz real do Initializr]
        E2[cada import de .java.example resolve no classpath]
        E3[nenhum símbolo denylisted como deprecado]
    end

    subgraph J4["export-determinism (ubuntu-latest)"]
        X1[export duas vezes por blueprint → árvores idênticas]
        X2[árvore exportada leva ArchHook.jar idêntico byte a byte, e só o que um projeto precisa]
        X3[jar exportado roda schema + doctor na própria árvore]
        X4[re-export apaga todo caminho de export.retired]
    end
```

## O que cada verificação cobre

| Job / passo | Verifica | Contra o quê |
|---|---|---|
| `hooks-cross-platform` | `ArchHook.java doctor`, `build --verify` e depois dezessete testes nas três OSes, todos disparando `java -jar .claude/hooks/ArchHook.jar` — o mesmo comando que os registros rodam | Decisão D8 — "cross-platform" como fato, não alegação — e decisão 0084: testar o source não prova nada sobre o jar que os hooks lançam |
| `committed ArchHook.jar is what the source compiles to` | `build --verify` recompila sob o `hook_build.javac_feature` fixado e compara byte a byte | Decisão 0075 — editar `ArchHook.java` sem rebuild não muda nada que um hook executa. Roda antes dos testes, para que exercitem bytes revisados |
| `hook blocks forbidden import, from the payload and from argv` | `BoundaryTest.java`, projeto descartável com um `forbidden-imports.txt`: o payload no stdin e o `check <path>` saem com exit 2 num import proibido, a forma com argv com o stdin aberto; arquivo limpo sai com 0, arquivo inexistente com 1 | Decisão 0123 — `check <path>` lia o stdin primeiro, travava até o timeout da tool e depois passava em silêncio; o probe de fronteira do bootstrap reportou um ✓ que nenhum hook produziu (lessons-learned-020 § 3) |
| `format, check and tests hand Maven the module the edited file belongs to` | `ModuleMapTest.java`, projetos descartáveis com um `mvnw` stub que registra seus argumentos: um arquivo sob o `src/main` ou `src/test` da raiz de um projeto single-module chega a `format`, `check` e `tests` como `-pl .`, um arquivo multi-module como `-pl <módulo>`; um `.java` sob `.claude/` e um projeto sem `pom.xml` nunca chamam o Maven; depois do `-am`, o `tests` passa ao Maven os goals de `tests.stop_maven_args` do `extensions.json` do próprio projeto, e bloqueia sem chamar o Maven quando a chave some | Decisão 0107, issue #60 — `moduleOf` nunca olhava o POM da raiz, então esses três hooks retornavam antes de chamar o Maven em todo projeto single-module desde o primeiro commit, e um hook que não faz nada parece um hook que passou. Decisão 0142, issue #126 — o gate parava em `test` enquanto `spotless:check` e `checkstyle-test` ficam em `verify`, e imprimia verde numa árvore que o CI rejeitava |
| `tests defers while a writer subagent of the session runs, and only then` | `TestsDeferTest.java`, projetos descartáveis com um `mvnw` stub e uma cópia do `extensions.json` real: depois de `tests agent-start` de um agente cuja classe tem `executor: true`, o `Stop` não chama o Maven e imprime `Tests deferred` — também quando um primeiro escritor parou e um segundo já começou, os grupos encadeados do executor da 0130; um agente só de leitura, um agente sem classe, um escritor que já parou, o escritor de outra sessão e um marcador além de `tests.writer_agent_max_minutes` chamam o Maven como antes, assim como três grupos que já pararam | Decisões 0116 e 0130, issue #76 — um `Stop` da thread principal durante um executor em background rodava o Maven sobre a árvore pela metade e bloqueava uma thread que não pode escrever `src/`. Um marcador nunca escrito traz isso de volta, um nunca apagado desliga o gate — os dois em silêncio |
| `guard bash refuses force pushes and holds shell writes to the phase` | `BashGuardTest.java`, 29 casos: toda grafia de force push de `guard.force_push` (`-f`, `-uf`, `--force-with-lease`, `+ref`, `git -C`, `sh -c "…"`) recusada, redirect / `sed -i` fora do território de uma fase de design recusados, `sed` simples, alvo `$OUT` irresolvível e `ls` liberados; na fase do `sonar-lessons`, `./mvnw -q` e `gh issue create` liberados e o scan redirecionado para `target/` recusado; para o `java-spring-boot-developer`, a rodada de teste documentada com log em `$TMPDIR` e um caminho absoluto fora do projeto liberados, um log em `target/` recusado; `sed -i ''`, `-i '' -e`, dois scripts `-e` e `--expression=` sobre um arquivo do território do executor liberados, as mesmas grafias sobre um arquivo fora dele recusadas | Decisões 0076, 0117 e 0118. O modo roda antes de todo comando de shell em toda sessão: falha caro nas duas direções, e o parser é dado que uma edição de JSON muda |
| `guard status prints the open phase the cockpit draws` | `GuardStatusTest.java`: sem fase, imprime `phase` vazio e classe `null`, e chamar `status` não abre nada; `/claude-code-architect-designer` abre uma fase que imprime a skill, a classe `meta` e `.claude/**`; um `Skill` da mesma classe mantém a classe; `deny_markers` sai igual a `mods.deny_markers`; o próximo prompt esvazia a fase | Decisão 0131 — a faixa do `nerviz-cockpit` desenha a partir dessa única linha de JSON, e os testes do próprio mod só conseguem fazer stub dela: uma chave renomeada esvaziaria a faixa sem nada falhar |
| `schema blocks a mod that would load half-way` | `ModsSchemaTest.java`, numa cópia descartável de `.claude/`: os mods entregues passam; evento desconhecido, hook de gating sem `.catch`, um `$.http.fetch`, um `$.process.run` de `sh`, um mod que o marketplace não lista, um mod que `enabledPlugins` deixa desligado, um mod sem testes e uma entrada de `deny_markers` que não está mais no `ArchHook.java` saem com 2 nomeando o defeito; comentário dentro de um hook não é defeito; árvore sem `.claude/mods/` fica calada | Decisão 0131 — todos esses defeitos são silenciosos em runtime (hook pulado é uma linha de debug log), e `claude plugin validate` só roda onde o CLI está, o workflow `mods` abaixo |
| `guard sweep reports this turn's writes, never pre-existing dirt` | `SweepTest.java` num repo git descartável: arquivo sujo antes do prompt nunca é nomeado, escrita fora do território no turno sai 2, `stop_hook_active` não bloqueia duas vezes, escrita dentro do território fica calada, a trilha do `audit` gravada no turno não é reportada enquanto um `Write` nela continua recusado, uma cadeia `/new-feature` → skill de design → `git-publish` cujos spec, parciais e escrita do executor em `src/` foram admitidos em tool-time fica calada com a pasta aprovada depois, e um arquivo que nenhum guard de tool-time viu no mesmo turno continua nomeado | Decisões 0065, 0105 e 0114 — o sweep vale o que vale a baseline do `guard prompt`, e uma baseline quebrada falha calada nos dois sentidos |
| `compose gate blocks a published service no host client can reach` | `ComposeGateTest.java`: `9092:9092` + `PLAINTEXT://kafka:9092` sai 2 nomeando a linha de endereço anunciado, `stop_hook_active` sai 0, um listener `localhost` limpa a linha, sem compose fica calado; collector sem porta publicada e `app` sem `OTLP_METRICS_ENDPOINT` saem 2 pela pergunta 5, e as variantes corrigidas, `SPRING_DATASOURCE_URL` sobrescrevendo `${DB_URL:…}` e o default host-first do Kafka (com portas de host fixas e variáveis) ficam calados; na forma de relatório, um datasource cujo banco ou usuário diverge do serviço `postgres`, ou que não tem senha onde o serviço exige uma, é aviso, nunca linha do gate, e o literal da senha nunca aparece na saída | Decisões 0064, 0110 e 0124. Não precisa de Docker — as checagens de endereço anunciado e de placeholders leem os arquivos |
| `doctor gate fails on every gated line and only on those` | `DoctorGateTest.java` num projeto descartável com cópias do `extensions.json`, `ArchHook.java` e `ArchHook.jar` reais, `CLAUDE_PROJECT_DIR` ausente: o saudável sai 0 mesmo com `CLAUDE_PROJECT_DIR`, `Maven wrapper`, `Compose` e `git HEAD` vermelhos; quebrar `Boundaries`, `Boundary violations`, `Schema`, `Hook jar`, `Hooks`, `MCP` ou `Audit overrides` sai 1 nomeando a linha; todo rótulo de `doctor.gate.labels` tem de aparecer na saída saudável; o `doctor` puro sai 0 com `ENFORCEMENT OFF`; lista vazia falha fechada | Decisão 0128 (issue #104). O job `architectural boundaries` do projeto gerado é `doctor gate`; o `doctor` puro só imprime, então esse job ficava verde com `ENFORCEMENT OFF`. O rótulo casa por texto, então um rename o tiraria do gate em silêncio |
| `schema blocks a settings file that re-enables a colliding plugin` | `PluginBlockTest.java`, numa cópia descartável de `.claude/`: os dois arquivos entregues passam; tirar ou trocar para `true` o `false` de um plugin de `settings.blocked_plugins` no `.claude/settings.json` ou no `settings.json.example` sai com 2 nomeando o arquivo e o id; um id novo na lista passa a ser exigido nos dois; lista vazia não exige nada | Decisão 0138 — um plugin ligado no nível de usuário volta a carregar em silêncio quando o `false` do projeto some, e a mesma lista em dois arquivos diverge na primeira edição de um só |
| `doctor names a personal skill that shadows a project skill, and never gates` | `SkillShadowTest.java` com `CLAUDE_CONFIG_DIR` num diretório descartável: uma skill pessoal com o nome de uma do projeto aparece na linha `Skill shadowing ... ❌`; uma de nome distinto e uma pasta sem `SKILL.md` não; sem colisão ✅; sem o diretório ⚪; o `doctor gate` nunca nomeia a linha | Decisão 0138 — o Claude Code resolve um nome como pessoal > projeto, sem aviso; nada no projeto recusa o diretório pessoal, e o estado da máquina de um dev não pode bloquear o CI |
| `doctor checks the agent instruction files against the blueprint, and never gates` | `AgentInstructionsTest.java` num projeto descartável com o blueprint `hexagonal` e um stamp: tudo presente ✅ e módulos sem diretório pulados; prefixo não listado, `CLAUDE.md` de módulo ausente ou sem `@AGENTS.md`, caminho citado morto e ponteiros raiz ausentes nomeados; sem stamp, calada; a lista inline de `forbidden_imports` lida; o `doctor gate` nunca nomeia a linha | Decisão 0139 — um `AGENTS.md` de módulo sozinho é pulado pelo Claude Code enquanto a raiz tem `CLAUDE.md`, em silêncio; os arquivos são renderizados pelo modelo, então um módulo esquecido só aparece aqui |
| `doctor sweeps every source file for forbidden imports, with check's own match` | `BoundarySweepTest.java` num projeto descartável: árvore limpa ✅ com a contagem; import proibido, simples ou `static`, nomeado com prefixo e módulo; teste sob o módulo varrido; arquivo fora de todo módulo não; prefixo de package do single-module casa; com git, arquivo não versionado varrido e `target/` ignorado; sem git, `build/` pulado em qualquer profundidade; sem mapa ✅ "nothing to sweep"; o `check <path>` bloqueia exatamente o arquivo que a varredura nomeia. O `DoctorGateTest` ganha o caso que reprova o gate pelo label | Decisão 0140 — a linha é gated e é a única coisa no CI que recusa import proibido antes do ArchUnit, que sob outro agente pode nunca existir; uma varredura que não casa nada pareceria projeto limpo |
| `export notes only real overwrites, and never guesses the stamp's commit` | `ExportProvenanceTest.java` exportando o `hexagonal`: árvore nova e arquivo igual sem `Overwrites`; `settings.json` editado no destino nomeado; `--commit` gravado como dado; sem a flag, a fonte no topo da própria work tree grava o HEAD dela, e uma cópia do `.claude/` aninhada em outro repositório grava `unknown`, não o HEAD de fora; `--commit` malformado sai com 2 | Decisão 0141 (issue #123) — o stamp do `arch-adopt` dizia `unknown`, e o `report-issue` compara esse campo quando o ref não é tag; o aviso mandava a pessoa a um `git diff` vazio |
| `context subagent hands the catalog to pattern_catalog agents only` | `SubagentContextTest.java`: `java-spring-boot-developer` recebe um `## Catalog` dentro de `subagent_context.max_chars`; installers e `general-purpose` não recebem nada | Decisão 0077. `SubagentStart` não bloqueia, então catálogo que para de chegar — ou chega em todo lugar — é silencioso |
| `audit renders where the run spent, redacts tool errors, skips what class or project turns off` | `AuditRenderTest.java` num projeto descartável com cópia do `extensions.json` real: chamadas de tool por peça (incluindo o transcript próprio de um subagent), os turnos mais caros, o pico de contexto, a primeira linha de cada erro de tool redigida, os campos de ledger que `audit summary` agrega, nenhum relatório para peça cuja classe declara `audited: false`, e o `audited.json` do projeto ligando ou desligando uma peça por cima da classe — exceto `arch-adopt`, desligado fixo pelo próprio override — com o `doctor` apontando cada entrada inválida, e um prompt que o harness injeta (hand-back `<agent-message>`, `<task-notification>`) deixando o run aberto até o próximo prompt do usuário; cache read e write de cada peça e a description da chamada do agent na tabela e no `nodes.jsonl`, e as chamadas de tool cujo resultado os requests seguintes mais releram, ranqueadas, com a releitura parando numa compactação e a primeira linha de um comando redigida; nenhum custo em parte alguma — nenhuma célula `💰`, nenhum campo `cost` nos dois ledgers, o `doctor` calado sobre um `pricing.json` que sobrou — e o modelo nomeado por peça; execuções por janela de plano a partir dos tokens billable do run e do `plan-limits.json` do projeto — Max como múltiplo do Pro, semanal de Max não publicada, as horas da janela limitando pelo tempo — no relatório e no `audit summary`, e o `doctor` apontando cada entrada inválida do orçamento, uma chave `*_usd` inclusive | Decisões 0041, 0085, 0086, 0111, 0119, 0132, 0133, 0134. Todo número é extraído de um layout de transcript não documentado e um render que lança exceção sai com 0 — o relatório só para de atualizar; a linha de erro cai num arquivo versionado, então um token ecoado vazaria (invariante 11) |
| `audit genesis fills GENESIS from the transcripts, once` | `GenesisTest.java`, um `CLAUDE_CONFIG_DIR` descartável com transcrição principal e de subagent, e um projeto com um `pricing.json` que sobrou e GENESIS com placeholders: Started é a mensagem do `/init-project`, turnos anteriores e um tool result que cita a tag não contam, tokens exatos, nenhum custo escrito; registro já preenchido e sessão desconhecida são recusados | Decisões 0123 e 0134 — o GENESIS registrou início depois do fim, escrito pelo modelo a partir da data de criação de um diretório (lessons-learned-020 §§ 4–5) |
| `guard keeps each skill inside its class's territory` | `SkillTerritoryTest.java` roda o modo `guard` sobre o `extensions.json` real em 59 casos: sem fase aberta nada restringe, dentro e fora do `write_allow`, o `agent_type` prevalecendo sobre a fase aberta — na escrita e na chamada `Skill` de um agent com classe, que nem é recusada nem troca a fase de quem chamou (decisão 0126) —, a recusa de `Skill(<build>)` com fase de design aberta, o território mais estreito do callee numa chamada entre classes, a fase sobrevivendo a uma chamada `Agent` até o próximo prompt, um encadeamento da mesma classe (`arch-adopt` → `sonarqube-setup` → `docker-architect`) somando territórios — ainda negação por padrão fora da união —, a classe `report` (`sonar-lessons`) presa a `docs/lessons-learned/` e recusada no meio de um design, e o território por arquivo do `transport-security-setup`: `application.yml`, a única classe `HstsHeaderFilter` e o IT de transporte liberados, qualquer outra classe principal e um arquivo de chave sob `resources` recusados, e o `Dockerfile` e `docker/caddy/**` recusados até o `docker-architect` entrar na fase, e a fase de implement: a chamada `Skill(docker-architect)` do `compose-service-installer` passa enquanto a da thread principal é recusada | O território ser allowlist é o tipo de alegação que apodrece em silêncio: vale até alguém alargar uma entrada de `write_allow` sem perceber. O caso que motivou tudo — um run de design escrevendo `docker-compose.yml`, arquivo que nenhuma denylist nomeava — é um dos 59, e também o `sonarqube-setup` encadeado que tinha o `pom.xml` recusado antes da decisão 0092 |
| `guard keeps each agent inside its class's territory` | `AgentTerritoryTest.java` roda o mesmo modo sobre `agent_classes` em 37 casos: cada installer dentro e fora da sua lista estreita, as grafias single- e multi-module do mesmo caminho, o executor alcançando a única linha de spec que ele fecha, o driver escrevendo qualquer coisa, o `issue-verifier` (classe `verifier`) recusado em toda escrita no repo — por `Write`, por heredoc, e com a fase `.claude/**` do designer aberta — enquanto o scratch fora do repo passa, um agent sem classe caindo na fase do chamador, e o `compose-service-installer` escrevendo cada path que o `docker-architect` escreve — `docker-compose*.yml`, `docker/**`, `Dockerfile`, `.env`, `.env.example`, `.gitignore`, `.dockerignore` — e recusado em config de aplicação, no POM e na spec, com a escrita no compose valendo sob a fase de implement aberta | Cada agent prometia o próprio território em prosa (`**Does not write:** docker-compose.yml`) enquanto o guard dava bypass irrestrito aos quatro. A promessa agora é dado, e os dois bloqueios que mais importam — `archunit-installer` recusado no `docker-compose.yml`, e recusado no source principal — são casos deste arquivo |
| `hook reports a compose image tag that disagrees with src/test` | `ComposeTagTest.java` monta um projeto descartável com `docker-compose.yml` e um `DockerImageName.parse(...)` em `src/test`, e exige que `ArchHook.java compose` reporte a divergência, expanda `${VAR:-default}` e fique calado quando as tags batem | A suíte passar contra uma versão de engine que ninguém roda. Roda nas três OSes porque a pergunta 3 do modo `compose` compara dois arquivos e não precisa de Docker — o que também prova que o casamento de `src/test/` sobrevive ao separador do Windows |
| `frontmatter schema` | `java .claude/hooks/ArchHook.java schema` | Invariante 10 — `extensions.json` é o dono único do frontmatter reconhecido; campo inventado ou `metadata:` falha alto em vez de ser ignorado em silêncio pelo runtime. O mesmo modo exige que toda injection `` !`command` `` resolva caminho a partir de `${CLAUDE_PROJECT_DIR}` — uma relativa reporta arquivo ausente sempre que o cwd do shell derivou —, reprova pasta de skill com nome de slash command nativo (`types.skill.native_commands`; até a 0084 um passo só no YAML que projeto gerado nunca rodava) e reprova rule sem `paths` (`types.rule.required`; decisão 0082 — rule sem ele carrega no launch, toda sessão), e reprova entrada de `migrations` que o `arch-adopt` nunca mostraria — blueprint id inexistente, campo faltando, id repetido (decisão 0104, `MigrationsSchemaTest.java`) |
| `new blueprint doesn't touch prompts` | adicionar um blueprint deixa `.claude/skills` e `.claude/agents` intactos | Invariante 7 — arquiteturas são dados |
| `rules is a leaf of the graph` | nenhuma rule menciona "skill", "agent", "subagent" | Invariante 1 |
| `norm contains no code boilerplate` | nenhuma declaração de `class`/`record`/`interface`/`enum` dentro de `rules/` | Invariante 3 |
| `no new commands/` | `.claude/commands/` não existe | Invariante 4 |
| `exemplar imports have the .example suffix at the end` | todo arquivo sob `skills/*/templates/` | Globs de precondição que dependem do sufixo |
| `exemplar bodies carry no comment but Javadoc` | `TemplateCommentsTest.java`: nenhum `*.java.example` (fora de `claude-code-architect-designer/`) tem `//` ou `/* */` no corpo — depois do cabeçalho `EXEMPLAR` e fora dos marcadores `// --- ` na coluna 0 — que o `checkstyle.xml.example` rejeitaria. Lê as regex dos módulos `LineComment`, `BlockComment` e `TrailingComment` do próprio template, então os dois não divergem | Decisão 0108 — o executor copia a forma do exemplar, comentários inclusive, e 60 templates carregavam 508 comentários no corpo. Com o Checkstyle do projeto gerado rejeitando-os, um comentário que volta a um template vira hook `check` vermelho em todo projeto que o copiar |
| `XML exemplars are well-formed` | `XmlTemplatesTest.java`: todo `*.xml.example` sob `.claude/skills/` faz parse com o parser DOM do JDK; fragmento sem declaração sob uma raiz sintética | Decisão 0123 — o `logback-spring.xml.example` tinha a declaração abaixo do comentário EXEMPLAR, e todo teste com contexto Spring do projeto gerado ficou vermelho (lessons-learned-020 § 1) |
| `spec template diagrams name only what their text defines` | `SpecDiagramTemplateTest.java`: todo bloco `mermaid` de um `*.md.example` sob `.claude/skills/` abre com `sequenceDiagram`, `classDiagram` ou `stateDiagram-v2`; todo nome do bloco — token com maiúscula, id e alias de participante, campo de um corpo `class { }`, primeira palavra do rótulo de uma transição — aparece no mesmo arquivo fora dos blocos; um `sequenceDiagram` tem uma mensagem por passo da lista numerada acima dele; o template do caso de uso tem o `sequenceDiagram` e o do domínio tem o `classDiagram` e o `stateDiagram-v2` | Decisão 0143 — o template é a única fonte do formato do spec (0115), então um diagrama que diverge do próprio texto ali entra em todo spec gerado dele. Prova os templates, nunca um spec gerado: nada de `.claude/.ci/` viaja, e lá `rules/spec-diagrams.md` continua norma |
| `blueprint-declared templates exist on disk` | todo `templates.<role>` de um blueprint resolve a um arquivo real | Blueprint apontando para template renomeado ou apagado |
| `issue forms cited by skills exist` | todo `.github/ISSUE_TEMPLATE/*.yml` citado em `.claude/skills` existe | Form renomeado ou apagado: projetos já exportados o buscam do `HEAD` deste repo, e o passo de formulário do `report-issue` — o de publicação do `sonar-lessons`, nos projetos exportados antes da decisão 0103 — para em todos eles (decisões 0100, 0103) |
| `compose service templates merge and parse` | todo `docker-architect/templates/*-service.yml.example` mesclado de uma vez no compose base do `project-bootstrap`, depois `docker compose config -q` | Nada mais lê esses templates antes do `docker compose up` de um projeto gerado: uma indentação errada, ou um volume nomeado que o template usa sem declarar (os templates de Kafka e da stack Grafana tinham as duas formas disso), só aparecia lá. Os templates de banco leem `${DB_PASSWORD:?…}` sem default (0124), então o step exporta senhas descartáveis e preenche `{{DB_NAME}}` como o step 4.5 do `docker-architect` faz |
| `each norm has a single owner` | lista fixa de termos conhecidos aparece em no máximo um arquivo sob `rules/` | Invariante 2 — estreito por construção, ver nota abaixo. A lista ganhou três termos quando `secrets.md` e `transport-security.md` assumiram a redação que quatro outras normas reescreviam: `forward-headers-strategy`, `reload-on-update` e `A placeholder for a secret has no default` (decisão 0122) |
| `no dependencies outside the Java ecosystem` | nenhum `pip install`, `npm install`, `node `, `python` solto em `.claude/` | Decisão D7 |
| `every norm paths matches some blueprint` | o glob `paths` de uma rule nomeia um pacote que algum blueprint declara | Gap 8 de `decisions/0024-lessons-learned-001-remediation.md` — rule que nunca carrega sozinha, em silêncio. Os globs isentos dessa checagem são lidos um por linha de `export.derived_paths.derived_paths_exempt_globs` em `extensions.json` — a mesma lista que o `schema` lê, então as duas checagens não podem divergir, e um glob é comparado inteiro e literalmente, hífens inclusive |
| o manifesto de export bate com o disco (dentro de `frontmatter schema`) | toda skill e agent em disco está no `include` ou `exclude` do bloco `export`; toda rule cujo `paths` nomeia um pacote tem entrada em `export.derived_paths` | Invariante 9 — esse manifesto **é** a lista de cópia que torna o projeto gerado self-contained. As duas checagens faziam grep nas tabelas de cópia em prosa de `project-bootstrap` §§ 6.6–6.8; as tabelas sumiram (D54) e o `schema` é dono da checagem, então ela também dispara localmente a cada edição em `.claude/`. Uma norma nova ausente do manifesto não quebra nada na geração: quebra para quem clona o projeto depois e segue uma citação para um arquivo que nunca foi copiado |
| `decisions/ doesn't grow paths or enter 00-index.md` | nenhum arquivo em `decisions/` declara `paths:`; nenhum está listado em `rules/00-index.md` | `decisions/` é histórico, não é rule — ver [11-pitfalls.md](11-pitfalls.md) |
| `no hardcoded Spring/Java version outside decisions/` | nenhum `Spring Boot X.Y` / `Java NN` escrito como fato em `rules/`, `skills/`, `blueprints/`, `CLAUDE.md` | Invariante 8 — versão é resolvida via Spring Initializr, nunca escrita de memória. Exclui a linha de requisito mínimo `JDK 21+` e notas datadas de "Tested to compile" em exemplares, que registram uma verificação passada, não uma versão a usar |
| `export-determinism` (job inteiro) | dois exports por blueprint geram árvores idênticas; a árvore leva `ArchHook.jar` idêntico ao verificado e nada que só este repo precisa; o **jar exportado** roda `schema` e `doctor` na própria árvore; semear cada caminho de `export.retired` e re-exportar apaga cada um | Invariante 9 e decisão D54. O passo de retired é o rename da decisão 0082: sem o delete, projeto atualizado fica com norma sem dono. O id do blueprint é lido do stamp com `sed`, não `python3` — D7 |
| `exemplar-imports` (job inteiro) | todo `import` de um `.java.example` resolve contra JARs de uma request real ao `start.spring.io`, e nenhum `.java.example` ou `.yml.example` usa nome na denylist de deprecados | Gaps 4, 5, 9 de `decisions/0024-lessons-learned-001-remediation.md`; a varredura de `.yml.example` e as entradas dos serializers Kafka, `decisions/0106-kafka-string-wire-contract.md`; a entrada mais nova da denylist, `requiresChannel(` — deprecado na geração do Spring Security que o Boot 4 gerencia, substituído por `redirectToHttps()` (decisão 0122) — exemplar que "compila na cabeça de quem escreveu" |

Nota sobre "each norm has a single owner": o teste checa uma lista fixa de frases
literais (`Constructor injection`, `Zero framework`, `RuntimeException`,
`forward-headers-strategy`, `reload-on-update`, `A placeholder for a secret has no
default`), não um detector geral de duplicação. Pega regressão de termos que já se sabe
terem divergido uma vez; uma rule nova sem frase nova adicionada a essa lista não é
coberta.

## Postura do workflow

Quatro decisões que não verificam invariante nenhum — são o custo e a superfície do
próprio CI:

- **`permissions: contents: read`** no topo. Nenhum job escreve: todos leem o checkout e
  rodam `java`. Sem isso o `GITHUB_TOKEN` herda o default do repositório, que é write.
- **`concurrency` com `cancel-in-progress`**. `push: [main]` e `pull_request` disparam no
  mesmo head quando se empurra um branch de PR; o run superado é cancelado em vez de
  disputar a mesma conclusão.
- **`timeout-minutes` em todo job** — 15 nos dois de hook e prompt, 30 no
  `exemplar-imports`, que depende de rede. O default é 6h.
- **Actions fixadas por commit SHA**, com a tag como comentário ao lado. `@v7` é um ref
  móvel que o dono da action pode reapontar — uma tag torna irreproduzível o que o CI
  rodou. Subir os dois juntos. Ambas estão no major atual (`checkout` v7.0.1,
  `setup-java` v6.0.1): v1–v4 de `setup-java` estão deprecadas, e os majors intermediários
  só apertam o que este workflow não usa — node24 (exige runner ≥ v2.327.1, que os
  runners hospedados no GitHub são), o bloqueio de checkout de PR de fork, que vale para
  `pull_request_target`/`workflow_run` e não para o gatilho `pull_request` daqui, e a
  remoção das distribuições `adopt` legadas no `setup-java` v6, sendo `temurin` a usada.

Os jobs `design` e `exemplar-imports` declaram `defaults.run.shell: bash` para ganhar
`pipefail`, que o shell default (`bash -e {0}`) não tem. Por job, nunca no nível do
workflow: a matriz `hooks-cross-platform` tem uma perna `windows-latest` cujo shell
default é pwsh.

## O workflow vizinho: `release.yml`

Fonte primária: `.github/workflows/release.yml`, `.github/PULL_REQUEST_TEMPLATE.md`
§ Release bump.

`validate.yml` prova que o que entrou em `main` está correto. `release.yml` responde a
outra pergunta: **com que nome esse estado passa a ser citável**. Todo merge em `main`
ganha uma tag anotada, porque quem adota a arquitetura fixa um ref — o
`.plugin-source.json` do marketplace e o selo de procedência de cada projeto gerado
guardam exatamente isso. Um merge sem tag é um estado que ninguém consegue apontar.

| Job | Quando roda | O que faz |
|---|---|---|
| `bump-declared` | todo evento de PR (`opened`, `edited`, `synchronize`, …) | Lê § Release bump do corpo do PR e exige **exatamente um** nível marcado: `major`, `minor` ou `patch`. Publica o nível como output |
| `tag` | uma vez, no `closed` de um PR **merged** com base `main` | Roda `schema` e `doctor` no commit de merge, calcula o próximo semver a partir da última tag e cria e empurra a tag anotada |

Quatro decisões que não são óbvias no arquivo:

- **O nível vive no corpo do PR, não numa label nem num commit.** O campo é obrigatório
  no template e o job `bump-declared` falha enquanto o PR está aberto — a decisão
  acontece na revisão, não depois do merge, quando já não há onde registrá-la.
- **Um parser, um arquivo.** A checagem de PR e a criação da tag leem o mesmo corpo; duas
  regex em dois workflows divergem silenciosamente. Por isso são dois jobs do mesmo
  `release.yml`, e o segundo consome o output do primeiro.
- **A tag nasce do `merge_commit_sha`, não de `main`.** `main` pode já carregar o merge
  seguinte quando o job roda. E o job revalida esse commit: `validate.yml` passou na head
  do PR, que é outra árvore sempre que `main` andou por baixo — um conflito semântico em
  `extensions.json` falha ali em vez de virar um ref que o marketplace pode fixar.
- **O marketplace é avisado, não escrito.** O último step manda um `repository_dispatch`
  (`source-released`, payload `{ref}`) para `nerviz-ai/marketplace`, cujo
  `sync.yml` roda `./sync.sh vX.Y.Z`, ajusta a versão do plugin, chama o próprio
  `validate.yml` na branch e abre um PR. O `GITHUB_TOKEN` não alcança outro repositório, então
  o step lê o secret `MARKETPLACE_DISPATCH_TOKEN` (PAT fine-grained, só aquele repositório,
  Contents: read and write). Sem ele, o step avisa em vez de falhar: a tag já foi enviada.

O GitHub Release não é deste workflow. `release-notes.yml` roda toda sexta-feira, e à mão
(`workflow_dispatch`) quando um lote deve sair antes: junta todas as tags desde o último
Release publicado, agrupa por nível de bump (Breaking, New, Fixes and docs) com a § What
changes de cada PR, lista as entradas de `migrations` novas no intervalo e publica um Release
só, na tag mais nova. Com `dry_run`, só mostra as notas no resumo da execução.

Uma coisa continua manual, de propósito: o merge do PR no marketplace — publicar é uma
decisão, não consequência de um merge.

**O que torna o campo obrigatório não está no arquivo:** é `bump-declared` como *required
status check* na branch protection de `main`. Sem isso, o job falha e o merge acontece
de todo jeito.

## O workflow vizinho: `templates.yml`

Filtrado por paths: roda só quando um PR mexe nos arquivos que ele testa, porque todos os jobs
baixam do Maven Central, do GitHub ou do `start.spring.io`. Não é required check — um required
check num workflow filtrado por paths fica pendente em todo PR que ele pula. Design:
`.claude/decisions/0099-ci-tests-for-verbatim-templates.md`.

| Job | Verifica | Contra o quê |
|---|---|---|
| `checkstyle-configs` | `CheckstyleConfigTest.java`: `checkstyle.xml.example` e `checkstyle-test.xml.example` rodando no Checkstyle **mais recente** (resolvido no Maven Central, jar `-all` da release no GitHub) sobre três fixtures — arquivo limpo passa nos dois, `record` e `permits` como nome falham nos dois com `IllegalIdentifierName`. Mais seis fixtures de comentário só no `checkstyle.xml`: `//` em linha própria, `//` no fim da linha, `/* */`, `/** */` dentro de corpo e `TODO` em Javadoc falham, cada um pelo seu módulo; um arquivo com as duas exceções (corpo vazio, diretiva de ferramenta) e strings `"http://…"` e `"/api/*"` passa; o `checkstyle-test.xml` deixa o comentário passar. Mais onze fixtures de assertion de exceção, só no `checkstyle-test.xml`: dez lambdas com mais de uma chamada — uma por nome de assertion que `testing.md` § Names and shape lista, um argumento `new`, um `orElseThrow()` encadeado, um bloco de dois statements, um `Assertions.` qualificado — falham com `OneCallInThrowLambda`; um arquivo de formas com uma chamada (construtor sob teste, method reference, bloco de um statement, lambda de `forEach` com duas chamadas) passa. Mais duas fixtures de literal repetido: um literal de 5 caracteres três vezes falha no `checkstyle.xml` com `MultipleStringLiterals` e passa no `checkstyle-test.xml`; um arquivo com o mesmo literal duas vezes, um literal de 4 caracteres três vezes e um literal três vezes dentro de anotação passa no `checkstyle.xml` | Decisão 0097 — o `format` default do Checkstyle 14 só rejeita `var`; uma config que dependia dele passou quatro variáveis `record` com 0 violações, e a config nunca roda neste repositório. Decisão 0108 — Javadoc é o único comentário em `src/main`. Decisão 0113 — a regra de uma chamada na lambda de assertion de exceção era só uma linha de norma, e a forma voltou em formatos de teste que nenhum template cobria. Decisão 0120 — "nada de string mágica" era só norma; os números são os do S1192 do Sonar, que pula arquivos de teste |
| `java-templates` | `JavaTemplatesTest.java`: todo arquivo de `new-feature-implement/templates/commons/` mais os blocos `// --- ` de `JpaEntity.java.example` (`AssignedIdEntity` incluído) num projeto novo do `start.spring.io`, depois `./mvnw test` sobre os três templates `*Test` | Decisões 0096 e 0098 — esses templates são copiados como arquivo, não lidos como forma: um que para de compilar quebra o `commons-logging-installer` em todo projeto. `exemplar-imports` prova que cada import existe; este prova que os arquivos compilam juntos e que os testes enviados passam |
| `ci-it-check` | `CiItCheckTest.java`: lê o step `integration tests actually ran` de `ci.yml.example` e de `ci-gradle.yml.example` e o roda com `bash -eo pipefail` sobre um projeto novo do `start.spring.io` por build tool, ligado pelo bloco de IT de `pom.parent.xml.example` ou `build.gradle.parent.example`. Só com `ForwardedHeadersIT` (todos os testes em `@Nested`) o step tem que passar; com um `GuardedIT` desligado pelo guard `@EnabledIf` do Docker, tem que falhar citando a classe | Decisão 0127 — o step reprovava todo build cujo IT usava `@Nested` (o failsafe escreve `Tests run: 0` no `.txt` da classe externa; o Gradle nomeia o relatório `<Outer>$<Nested>`) e nunca pegou o skip do guard, que sai como `Skipped: N`, não como zero testes. Esse step só roda dentro do projeto gerado, então nada aqui o via |

## O workflow vizinho: `mods.yml`

Filtrado por paths em `.claude/mods/**`, `extensions.json` e nele mesmo; não é check
obrigatório. O único lugar do CI deste repositório que instala algo fora de
`java`/`git`/`curl`: o CLI do Claude Code, via npm, em `mods.ci_version` — fixado, então uma
release que muda a API de mods quebra o PR que sobe a versão. Nada que uma pessoa ou um
projeto gerado roda precisa dele (mods ficam neste repositório). Design:
`.claude/decisions/0131-mods-in-architect-designer.md`.

| Passo | Verifica | Contra o quê |
|---|---|---|
| `claude plugin validate` | O marketplace e cada mod, lidos como o engine lê; falha em qualquer linha `gating hook without .catch` | O que o `ModsSchemaTest` checa como dado, provado contra o parser do próprio engine |
| `claude plugin test` | Os `tests/*.test.ts` de cada mod contra o `$` do próprio engine — sem sessão, login nem rede | O TypeScript em si: a faixa, o spinner deixado intocado, o diálogo do guard, `/nerviz-doctor` onde nada desenha |

## O que ainda não está aqui

- Invariante 9 (o projeto gerado é self-contained) está coberto **pela metade**: o passo
  `schema` prova que o manifesto de `export` cobre o disco, e `export-determinism` prova
  que a árvore exportada é estável e passa no próprio `schema` e `doctor` — as partes cujo
  modo de falha era silencioso.
  Que o projeto gerado *de fato* compile e não tenha caminho morto continua verificado à
  mão, pelo bloco de comandos em `project-bootstrap/SKILL.md` § 8 ("Verify"): exige gerar
  um projeto de verdade contra o Initializr, e esse custo não foi automatizado. Uma fatia
  foi: o workflow vizinho `templates.yml` (abaixo) compila, num projeto novo do Initializr,
  os templates que o projeto recebe **como arquivo**, e roda os testes que vêm com eles.
- `claude plugin validate .claude/skills` — o CLI não está instalado no runner do
  GitHub Actions, e instalá-lo puxaria uma dependência fora de `java`/`git`/`curl`
  (ver `CLAUDE.md` § Dependencies). Rode à mão antes de abrir um PR; é uma checagem
  barata e complementar ao passo `schema` acima, pega YAML malformado. (O `mods.yml` instala
  o CLI, mas só pra testar `.claude/mods/`.)

## Como rodar localmente

```bash
# Os mesmos comandos que o job `design` roda, um a um:
java .claude/hooks/ArchHook.java schema
claude plugin validate .claude/skills   # não roda em CI — CLI ausente no runner
java .claude/hooks/ArchHook.java doctor
java .claude/.ci/BoundaryTest.java
java .claude/.ci/ModuleMapTest.java
java .claude/.ci/TestsDeferTest.java
java .claude/.ci/InjectionPathTest.java
java .claude/.ci/MigrationsSchemaTest.java
java .claude/.ci/ComposeTagTest.java
java .claude/.ci/SkillTerritoryTest.java
java .claude/.ci/AgentTerritoryTest.java
java .claude/.ci/GenesisTest.java
java .claude/.ci/XmlTemplatesTest.java
java .claude/.ci/SpecDiagramTemplateTest.java
java .claude/.ci/GuardStatusTest.java
java .claude/.ci/ModsSchemaTest.java
java .claude/.ci/PluginBlockTest.java
java .claude/.ci/SkillShadowTest.java
# mods.yml — um CLI do Claude Code em mods.ci_version ou mais novo:
claude plugin validate .claude/mods && claude plugin test .claude/mods/nerviz-cockpit
# templates.yml — rede, ~1 min com cache Maven quente:
java .claude/.ci/CheckstyleConfigTest.java
java .claude/.ci/JavaTemplatesTest.java
java .claude/.ci/CiItCheckTest.java
```

Um `git push` sem rodar isso antes ainda passa pelo hook local (`settings.json`), mas
só o CI roda os passos do job `design` e `exemplar-imports` — esses dois não têm hook
equivalente local.
