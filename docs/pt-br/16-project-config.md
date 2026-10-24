# Configurações do projeto — o que um projeto gerado configura no próprio `.claude/`

Quase tudo dentro do `.claude/` de um projeto gerado pertence ao Nerviz, e o `/arch-adopt`
sobrescreve esse conteúdo a cada atualização. Alguns arquivos, porém, pertencem ao
**projeto**. O `export` nunca os nomeia, então nenhuma atualização mexe neles. Enquanto um
desses arquivos não existe, vale o comportamento que o Nerviz entrega. Esta página lista esses
arquivos, o único comando que os altera e como adicionar um novo.

Desenho: [`0187`](../../.claude/decisions/0187-project-config-registry-skill-and-mod.md).

## As configurações

| Id | Arquivo | O que muda | Default | Linha do `doctor` | Decisão |
|---|---|---|---|---|---|
| `audited` | `.claude/audit-usage/audited.json` | Quais skills e agents a trilha de auditoria registra. `skills.<nome>` / `agents.<nome>`: `true` ou `false` | a classe de cada peça (`audited` em `skill_classes` / `agent_classes`). `arch-adopt` é fixo em desligado | `Audit overrides` (bloqueia o CI) | [`0111`](../../.claude/decisions/0111-audit-opt-out-owned-by-the-project.md) |
| `plan-limits` | `.claude/audit-usage/plan-limits.json` | O orçamento em tokens faturáveis usado na projeção de janelas de plano dos relatórios. `pro.five_hour_tokens`, `pro.weekly_tokens`: número acima de 0, ou `null` | não definido, então não há projeção | `Plan limits` | [`0133`](../../.claude/decisions/0133-audit-plan-window-projection.md) |
| `compose-gate` | `.claude/compose-gate.json` | Se um serviço do compose em `exited`/`dead` bloqueia o fim do turno. `exited`: `block` ou `warn` | `block` | `Compose overrides` (bloqueia o CI) | [`0186`](../../.claude/decisions/0186-compose-gate-foreign-port-warns-while-our-stack-is-stopped.md) |

A tabela é só para leitura. A fonte de verdade é o bloco `project_config` de
`.claude/schemas/extensions.json`, e o `config list` imprime esse bloco com os valores atuais.

## Mudando uma configuração

```bash
java -jar .claude/hooks/ArchHook.jar config list                              # todo arquivo, chave, valor, default
java -jar .claude/hooks/ArchHook.jar config set compose-gate exited warn
java -jar .claude/hooks/ArchHook.jar config set audited skills.report-issue false
java -jar .claude/hooks/ArchHook.jar config set plan-limits pro.five_hour_tokens 400000
java -jar .claude/hooks/ArchHook.jar config unset compose-gate exited         # volta ao default
```

No Claude Code, o `/project-config` faz o mesmo em conversa. Ele mostra o que está definido,
pergunta qual mudança você quer e roda o comando acima.

Como o `config` se comporta:

- **Só o `config` grava esses arquivos.** O `set` monta o arquivo como ficaria depois da
  mudança e passa esse conteúdo pelo mesmo validador do `doctor`. Se houver qualquer problema,
  não grava nada, sai com 2 e diz qual é o problema.
- **O `guard` bloqueia escrita direta.** Write, Edit ou redirect de shell em qualquer desses
  caminhos é recusado, e a mensagem indica o `config set`. Por isso o modelo não consegue
  deixar o arquivo meio válido, nem num host que ignora `disable-model-invocation`.
- **`unset` volta ao default.** Remove a chave e, se o arquivo ficar vazio, apaga o arquivo,
  já que arquivo ausente é o default.
- **Valores aceitos.** `true`/`false`, `null`, número ou string (qualquer outra coisa).
- **Fora de alcance.** Chaves que o upstream fixa são recusadas; hoje só `skills.arch-adopt`.
  Configurações da trilha de auditoria também são recusadas enquanto `.claude/audit-usage/`
  não existir. Criar o diretório só para guardar a configuração ligaria a trilha inteira, e
  isso é outra decisão (ver [`08-audit-usage.md`](08-audit-usage.md)).
- **Códigos de saída.** 0 = gravou; 2 = recusou e não gravou nada; 1 = erro de uso.

Esses arquivos são versionados. Faça o commit da configuração junto com a mudança que a
motivou, para que o motivo fique ao lado dela no histórico.

## Em outras plataformas agênticas

- **`config`** é Java puro. Funciona igual no Claude Code, no GitHub Copilot CLI, no VS Code,
  num cloud agent ou num terminal sem agent.
- **`/project-config`** é um `SKILL.md`, que o Copilot também lê de `.claude/skills/`. A
  injeção `` !` `` só expande no Claude Code. Nos outros hosts, a skill instrui o modelo a
  rodar `config list` antes de tudo.
- **`guard`** vale onde o host executa os hooks de `.claude/settings.json`
  ([`13-github-copilot.md`](13-github-copilot.md) § 2.5). Onde não executa, o `doctor gate` no
  CI continua falhando com um `audited.json` ou `compose-gate.json` malformado.

## Adicionando uma nova configuração do projeto

1. Crie uma entrada em `project_config.files` de `.claude/schemas/extensions.json` com:
   `base` (`root` ou `audit`), `path`, `validator`, `doctor_label`, `summary`, `decision` e
   `keys`. Uma chave pode usar `{skill}`, `{agent}`, `{budget_plan}` ou `{window_budget}`, que
   o `config list` expande.
2. Defina um validador. Para arquivo plano com valores fechados, o validador `keys` já resolve:
   liste os valores em `values` e não escreva Java. Qualquer outro caso vira um `case` novo em
   `projectConfigProblems` do `ArchHook.java`, que devolve os problemas e, como último
   elemento, o que o arquivo define.
3. Ajuste o leitor, ou seja, o código cujo comportamento a configuração muda. Ele busca o
   caminho com `projectConfigRel` / `projectConfigName`, nunca com um literal.
4. Crie uma linha no `doctor` via `projectConfigFileProblems`. Coloque essa linha em
   `doctor.gate.labels` quando um arquivo malformado for defeito do projeto, e não da máquina.
5. Adicione uma linha na tabela acima, nos dois idiomas, e um caso em
   `.claude/.ci/ProjectConfigTest.java`.

Nunca crie um quarto arquivo declarado num bloco próprio, como eram os três primeiros antes
da 0187.

## O que não está aqui

- **`.claude/settings.json` e `extensions.json`** pertencem ao Nerviz, e o `/arch-adopt`
  sobrescreve os dois.
- **`.claude/settings.local.json` e `~/.claude/settings.json`** são configuração pessoal, não
  versionada.
- **Uma visualização dessas configurações** será um mod, planejado para depois do merge da
  branch que entrega esta página (0187).
