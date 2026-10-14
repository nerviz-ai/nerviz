# Workflow do Nerviz

English version: [`docs/en/`](en/README.md).

Esta pasta documenta como as peças do `.claude/` deste repositório colaboram para
executar os comandos principais — `/init-project`, `/new-feature`, `/arch-doctor`,
`/audit-usage`, `/arch-adopt`, `/report-issue` e `/triage-issue` — e o que este projeto faz que os repositórios
semelhantes não fazem.
Quem chega pela primeira vez deve começar por
[09-diferenciais.md](09-diferenciais.md).

Nerviz não é uma aplicação Java — é um meta-repositório. O que ele produz são
**arquivos de instrução** (skills, rules, agents, hooks) que, juntos, geram projetos
Spring Boot já preparados para desenvolvimento assistido por IA. Entender como esses
arquivos se chamam entre si é o pré-requisito para editar qualquer um deles sem quebrar
os outros.

## Leitura recomendada, em ordem

| Documento | Conteúdo |
|---|---|
| [00-visao-geral.md](00-visao-geral.md) | Diagrama geral da arquitetura do `.claude/`, com legenda por tipo de peça |
| [01-tipos-de-arquivo.md](01-tipos-de-arquivo.md) | Como funciona cada tipo — skill, rule, agent, `CLAUDE.md`, hook — segundo o runtime do Claude Code |
| [02-init-project.md](02-init-project.md) | Workflow completo de `/init-project`: quem chama quem, exemplo de invocação, relatório final |
| [03-new-feature.md](03-new-feature.md) | Os dois fluxos, `/new-feature` e `/new-feature-implement`: criar a spec (pipeline de skills de design, 5 fixas + mensageria e jobs quando se aplicam) e implementá-la (executor em três grupos encadeados), exemplo, relatório final |
| [04-arch-doctor.md](04-arch-doctor.md) | Workflow de `/arch-doctor`: o que ele diagnostica e como ler a saída |
| [05-blueprints.md](05-blueprints.md) | Contrato `_schema.md`, o que cada blueprint declara, como criar um blueprint customizado |
| [06-claude-code-architect-designer.md](06-claude-code-architect-designer.md) | Workflow de `/claude-code-architect-designer`: as 5 fases, matriz de decisão, campos de frontmatter, exemplo completo |
| [07-ci-validate.md](07-ci-validate.md) | `validate.yml`: por que existe, o que cada job/passo verifica, o que ainda falta cobrir |
| [08-audit-usage.md](08-audit-usage.md) | Trilha de auditoria: hook `audit` (relatório por execução, tokens billable e o modelo por peça), hook `guard` (cada skill escreve só o território da própria classe, spec aprovado congelado), skill `/audit-usage` |
| [09-diferenciais.md](09-diferenciais.md) | Que categoria é esta (um agent harness especializado em uma stack), os vizinhos mapeados no GitHub e em ferramentas comerciais, o que é único aqui e o que não é — tabela comparativa e fontes |
| [10-arch-adopt.md](10-arch-adopt.md) | Workflow de `/arch-adopt`: instalar este `.claude/` num projeto que nunca foi gerado aqui, ou atualizar um que está atrasado — modo `export`, manifesto de cópia, stamp de proveniência |
| [11-pitfalls.md](11-pitfalls.md) | Toda armadilha silenciosa, em duas partes. Do runtime: skill com nome de comando nativo, `$ARGUMENTS` interpolado em prosa, segmentos de pipe em `allowed-tools`, cwd de injeção, frontmatter desconhecido, as quatro falhas mudas de um hook, `AskUserQuestion`, e os quatro pitfalls de `.mcp.json`. Deste repositório: territórios de escrita e os guards, pastas de spec congeladas, alcance do compose, quem é dono do `pom.xml` e do outbox |
| [12-issues.md](12-issues.md) | Issues, dos dois lados: o `/report-issue` abre, a partir de um projeto gerado, uma issue verificável e sem dados do projeto; o `/triage-issue` faz o `issue-verifier`, só-leitura, checar cada afirmação no `HEAD` — diagnóstico e fix proposto incluídos — antes de comentário, label ou design |
| [13-github-copilot.md](13-github-copilot.md) | O que um projeto gerado entrega a quem usa GitHub Copilot, superfície por superfície (CLI, cloud agent, VS Code, code review): o que funciona igual, o que precisa de tradução de arquivo ou frontmatter, o que só o Claude Code tem, o que só o Copilot tem, e qual garantia do Nerviz continua valendo lá. Base da fase 8 do `roadmap.md` e do desenho do `AGENTS.md` |
| [14-rules.md](14-rules.md) | O papel de cada rule de `.claude/rules/`: o que só ela decide, onde passa a palavra para outra, o que a verifica — e uma tabela de perguntas de fronteira (quem decide o status, o nível de log, a máscara, o TLS de entrada e de saída). Não repete as normas; aponta para elas |

## Como este repositório está organizado (referência rápida)

```
.claude/
├── hooks/ + settings.json   infra de enforcement — determinístico (ArchHook.java → ArchHook.jar)
├── schemas/                 o que o hook lê: campos válidos, classes de skill e de agent,
│                            território de escrita, manifesto de export
├── skills/                  procedimento + exemplares
├── agents/                  execução isolada
├── rules/ + blueprints/     normas e dados (folhas — não chamam ninguém)
├── .ci/                     testes de CI que exercitam o hook (ArchHook.jar) ponta a ponta
└── decisions/ + lessons-learned/   histórico, versionado — fora do grafo em runtime
```

Esse é o mesmo grafo de dependências descrito em `CLAUDE.md` § Architecture of the AI
files, aplicado à própria pasta `.claude/` como Clean Architecture. Os documentos desta
pasta detalham como esse grafo se comporta nos comandos citados acima.

## O que esta documentação não é

Não é uma cópia das regras — cada `SKILL.md`, `rule` e `agent` continua sendo a fonte
única (`CLAUDE.md`, invariante 2). O que está aqui é a **leitura entre os arquivos**: a
sequência de chamadas, o motivo de cada peça existir na forma em que existe, e exemplos
concretos de execução. Onde este documento cita um comportamento do runtime do Claude
Code (frontmatter, hooks, subagents), a fonte é `claude-help.md`, na raiz deste
repositório.
