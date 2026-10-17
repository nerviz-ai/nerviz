# Probe — o motivo de uma negação chega ao modelo? (lessons-learned-022 § 4, comando 3)

Pergunta: no Copilot CLI, um `preToolUse` que nega mostra ao modelo só
`Denied by preToolUse hook: hook exited with code 2`. Algum canal de **stdout** leva o motivo? E em
qual exit code, sem mudar o que o Claude Code já faz com o `exit 2` + stderr do `guard`?

Nada do `guard`, do `check` ou do `settings.json` muda antes deste resultado.

## O sentinela

`DenyReasonProbe.java` roda em `PreToolUse` (`pre`) e `PostToolUse` (`post`), com `matcher: "Bash"`.
Ele só age quando o comando contém `PROBE_PRE_<variante>` ou `PROBE_POST_<variante>` do seu evento.
Fora isso, e com payload ilegível, sai com 0.

Cada canal leva um marcador próprio. O marcador que aparece no transcript diz qual canal chegou ao modelo.

| Marcador | Canal |
|---|---|
| `M_STDERR` | stderr em texto (o que o `guard` faz hoje) |
| `M_STDOUT_TEXT` | stdout em texto puro |
| `M_FLAT_REASON` | stdout `{"permissionDecision":"deny","permissionDecisionReason":…}`, a forma documentada do Copilot |
| `M_NESTED_REASON` | stdout `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":…}}`, a forma documentada do Claude Code |
| `M_LEGACY_REASON` | stdout `{"decision":"block","reason":…}` |
| `M_ADDCTX_FLAT` | stdout `additionalContext` no topo (só `post`) |
| `M_ADDCTX_NESTED` | stdout `hookSpecificOutput.additionalContext` (só `post`) |

| Variante | Exit | stdout | stderr | Pergunta que responde |
|---|---|---|---|---|
| `PRE_E2_STDERR` | 2 | — | sim | Controle. Reproduz o § 4 |
| `PRE_E2_TEXT` | 2 | texto | sim | O Copilot repassa stdout cru no exit 2? |
| `PRE_E2_FLAT` | 2 | plano | sim | O Copilot lê o JSON plano mesmo com exit 2? **Saída ideal**: o Claude Code ignora stdout no exit 2 |
| `PRE_E2_NESTED` | 2 | aninhado | sim | Idem, com a forma do Claude |
| `PRE_E2_BOTH` | 2 | plano + aninhado | sim | Os dois juntos quebram algum parser? |
| `PRE_E0_FLAT` | 0 | plano | — | O documentado do Copilot. No Claude Code, **deve deixar rodar** |
| `PRE_E0_NESTED` | 0 | aninhado | — | O documentado do Claude Code. E no Copilot? |
| `PRE_E0_BOTH` | 0 | plano + aninhado | — | Um objeto que nega nos dois runtimes com exit 0? |
| `PRE_E0_LEGACY` | 0 | `decision`/`reason` | — | O formato antigo ainda nega nos dois? |
| `POST_E2_STDERR` | 2 | — | sim | O `check` de hoje: o stderr do `postToolUse` chega? |
| `POST_E2_JSON` | 2 | `decision`/`reason` + `additionalContext` plano e aninhado | sim | Algum canal de stdout chega no exit 2 do `post`? |
| `POST_E0_JSON` | 0 | o mesmo | — | Algum canal de stdout chega no exit 0 do `post`? |

## Preparação (uma vez)

Rode num **terminal comum** (Terminal.app ou iTerm), não no terminal integrado do VS Code. O VS Code
injeta `COPILOT_DEBUG_NONCE` e `VSCODE_*` no ambiente, e isso contamina a lista de nomes que o
sentinela registra para distinguir os runtimes.

```zsh
NERVIZ=~/Documents/GitHub/nerviz
PROBE=~/Documents/GitHub/probe-deny-reason
mkdir -p "$PROBE/.claude" "$PROBE/.probe" && cd "$PROBE" && git init -q
cp "$NERVIZ/.claude/lessons-learned/lessons-learned-022/probe-deny-reason/DenyReasonProbe.java" .probe/
cp "$NERVIZ/.claude/lessons-learned/lessons-learned-022/probe-deny-reason/settings.probe.json" .claude/settings.json
echo '.probe/' > .gitignore
claude --version > .probe/versions.txt; copilot --version >> .probe/versions.txt; java -version 2>> .probe/versions.txt
copilot --help > .probe/copilot-help.txt
```

O diretório é vazio de propósito: sem `CLAUDE.md`, skills nem `guard`, nada além do sentinela decide.

Os flags do loop do Copilot (`-p`, `--allow-all-tools`, `--share`, `--output-format json`,
`--log-level`, `--log-dir`) foram conferidos no `copilot --help` em 2026-10-17.
Abra o diretório uma vez em `copilot` interativo para aceitar a confiança da pasta.

## Rodada Claude Code

`--allowedTools "Bash(echo:*)"` tira a permissão do caminho. Quem nega é só o hook.

```zsh
cd "$PROBE"
VARIANTS=(PRE_E2_STDERR PRE_E2_TEXT PRE_E2_FLAT PRE_E2_NESTED PRE_E2_BOTH PRE_E0_FLAT PRE_E0_NESTED PRE_E0_BOTH PRE_E0_LEGACY POST_E2_STDERR POST_E2_JSON POST_E0_JSON)
for v in $VARIANTS; do
  claude -p "Teste de hook. Rode uma única vez, exatamente: echo PROBE_$v — não repita e não rode mais nada. Depois cite, palavra por palavra, toda mensagem que você recebeu sobre esse comando, inclusive de hook, e diga se ele executou." \
    --allowedTools "Bash(echo:*)" --output-format stream-json --verbose > ".probe/cc-$v.jsonl" 2> ".probe/cc-$v.err"
done
mv .probe/deny-probe.log .probe/deny-probe.cc.log
```

## Rodada Copilot CLI

Cada `copilot -p` abre uma sessão nova, e os hooks só são relidos em sessão nova (§ 4).

```zsh
cd "$PROBE"
for v in $VARIANTS; do
  copilot -p "Teste de hook. Rode uma única vez, exatamente: echo PROBE_$v — não repita e não rode mais nada. Depois cite, palavra por palavra, toda mensagem que você recebeu sobre esse comando, inclusive de hook, e diga se ele executou." \
    --allow-all-tools --output-format json --share ".probe/cop-$v.md" \
    --log-level debug --log-dir ".probe/cop-logs/$v" > ".probe/cop-$v.jsonl" 2> ".probe/cop-$v.err"
done
mv .probe/deny-probe.log .probe/deny-probe.cop.log
```

`--allow-all-tools` vale só neste diretório vazio. Não rode esse loop no `nerviz` nem num projeto real.

Se o modo `-p` não carregar os hooks, a run é inválida. O sinal disso é a falta de uma linha
`event=pre token=<variante>` em `.probe/deny-probe.cop.log`. Nesse caso, repita a variante no modo
interativo, uma sessão nova por variante, e copie da tela a linha da negação para `.probe/cop-<v>.screen.txt`. Ao fim,
anexe o log novo: `cat .probe/deny-probe.log >> .probe/deny-probe.cop.log`.

## Leitura

```zsh
cd "$PROBE"
for v in $VARIANTS; do
  ccran=$(grep -c "event=post token=$v " .probe/deny-probe.cc.log)
  copran=$(grep -c "event=post token=$v " .probe/deny-probe.cop.log)
  cc=$(grep -h '"type":"user"\|"type":"assistant"\|"type":"result"' ".probe/cc-$v.jsonl" 2>/dev/null | grep -o 'M_[A-Z_]*' | sort -u | tr '\n' ' ')
  cop=$(cat ".probe/cop-$v.md" ".probe/cop-$v.jsonl" ".probe/cop-$v.screen.txt" 2>/dev/null | grep -o 'M_[A-Z_]*' | sort -u | tr '\n' ' ')
  coplog=$(cat ".probe/cop-logs/$v"/* 2>/dev/null | grep -o 'M_[A-Z_]*' | sort -u | tr '\n' ' ')
  printf '%-16s claude: ran=%s %s | copilot: ran=%s %s | copilot-log: %s\n' "$v" "$ccran" "$cc" "$copran" "$cop" "$coplog"
done | tee .probe/resumo.txt
```

`ran` conta as vezes que o hook `post` viu a variante, ou seja, que o comando executou. Um `PRE_*`
negado fica com `ran=0`. Confira com a citação do modelo e com os `.jsonl`.

Os marcadores entram só nas linhas `user`, `assistant` e `result` do Claude Code. Assim fica de fora o
que o runtime registra sem mostrar ao modelo. No Copilot, o `--share` e o `.jsonl` contêm a conversa. Um
marcador lá foi visto pelo modelo ou exibido como resultado da tool. Anote qual dos dois casos é.

A coluna `copilot-log` lê o log de debug, não a conversa. Um marcador só ali prova que o Copilot
recebeu o stdout do hook, mas não que o modelo o viu. Marcador no log e fora da conversa quer dizer
que o Copilot leu o canal e o descartou.

## O que devolver

Copie para `lessons-learned-022/captures/probe-deny-reason/`:

- `.probe/resumo.txt`, `versions.txt` e `copilot-help.txt`;
- `deny-probe.cc.log`, `deny-probe.cop.log` e `deny-probe-payload.jsonl`;
- os `cc-*.jsonl`, `cop-*.jsonl` e `cop-*.md`;
- dos logs de debug, só as linhas com `M_` ou `hook`: `grep -rh "M_\|hook" .probe/cop-logs > .probe/cop-hook-lines.txt`.

Antes do commit, limpe as capturas:

- tire a linha `"subtype":"init"` de cada `cc-*.jsonl`, que lista as contas conectadas, os plugins e o socket da sessão;
- troque `/Users/<usuário>` por `~` e `-Users-<usuário>-` por `-~-`;
- procure o hostname (`hostname`) e o nome da conta (`id -F`) em tudo que for copiado.

**Não copie `cop-logs/` inteiro.** Um log de debug pode trazer cabeçalhos, URLs ou tokens da sessão,
e `captures/` vai para um repositório público. Leia `cop-hook-lines.txt` antes de copiar.

Escreva também `evidence/D3b-deny-reason.txt` com as duas colunas por variante: negou?
e quais marcadores o modelo viu.

## Hipóteses (preencher contra o resultado)

| Variante | Claude Code (docs) | Copilot CLI (docs) |
|---|---|---|
| `PRE_E2_*` | nega; vê `M_STDERR`; stdout ignorado | nega; motivo **não testado** — a pergunta |
| `PRE_E0_FLAT` | **executa** (campo de topo desconhecido) | nega com `M_FLAT_REASON` |
| `PRE_E0_NESTED` | nega com `M_NESTED_REASON` | não documentado |
| `PRE_E0_BOTH` | nega com `M_NESTED_REASON`, se o validador aceitar a chave extra | nega com `M_FLAT_REASON`, se aceitar a chave extra |
| `PRE_E0_LEGACY` | nega (`block` mapeia para `deny`, obsoleto) | não documentado |
| `POST_E2_STDERR` | vê `M_STDERR` | não testado |
| `POST_E*_JSON` | vê `M_LEGACY_REASON`/`M_ADDCTX_NESTED` só no exit 0 | `additionalContext` documentado em `postToolUse` |

## Como o resultado decide o desenho

1. **Algum `PRE_E2_*` mostra o motivo no Copilot.** O `guard` mantém o exit 2 e o stderr, e passa a
   escrever no stdout o JSON que o Copilot leu. O Claude Code fica igual, porque ignora o stdout no exit 2.
2. **Só um `PRE_E0_*` mostra o motivo no Copilot.** Um exit único não serve aos dois runtimes sem mudar
   o do Claude Code. O `guard` precisa saber em qual runtime roda. O payload do Claude Code traz
   `transcript_path` e `permission_mode`, e o do Copilot não traz (`captures/probe-single/`). Os nomes de
   ambiente no `deny-probe.log` dizem se há um sinal mais firme. Com o Claude Code, o `guard` sai 2 com
   stderr, como hoje. Com o Copilot, sai 0 com o JSON que negou.
3. **Nenhum canal chega.** O motivo vai para um lugar que o modelo lê em seguida (o `Stop` chega, § 4).
   Ou o `guard` passa a nomear a regra numa linha curta que caiba no texto padrão. O desenho volta ao
   designer sem mudar o `guard`.

O `post` decide o mesmo para o `check`, o `format` e o `schema` em `PostToolUse`.
