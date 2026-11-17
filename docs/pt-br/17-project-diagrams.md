# Diagramas do projeto — o que o `/project-diagrams` desenha de um projeto gerado

Um projeto gerado já diz tudo de que um diagrama precisa: os tipos de `src/`, as specs de
`docs/use-cases/`, o `docker-compose.yml`. O `/project-diagrams` desenha esses fatos, então um
diagrama não consegue citar uma classe que foi renomeada nem um serviço que foi removido. É uma skill
que viaja para o projeto, classe `media`: escreve `docs/diagrams/**` e mais nada, e nunca escreve ao lado
de uma spec, cujos diagramas continuam em Mermaid.

Desenho: [`0211`](../../.claude/decisions/0211-project-diagrams-skill-sharing-the-diagram-engine.md).
O motor é o mesmo que desenha o harness deste repositório (`/nerviz-diagrams`,
[`0190`](../../.claude/decisions/0190-nerviz-diagrams-skill-derived-from-data.md)): um arquivo,
`.claude/skills/project-diagrams/scripts/Diagrams.java`, com `--source project` ou
`--source harness`. O `nerviz-diagrams` é o chamador dele, só do meta-repo.

## O que desenha

Cada tipo responde a uma única pergunta, com um único tipo de diagrama. A skill não escolhe por gosto.

| Tipo · pasta | Tipo de diagrama | Pergunta | Fatos | Fica de fora |
|---|---|---|---|---|
| `architecture` · `docs/diagrams/architecture/` | Componentes por camada: adapters de entrada → use cases → domain; portas implementadas pelos adapters de saída (tracejado, ponta vazada) | Onde mora cada tipo, e para onde as dependências apontam? | o `packages.map` do blueprint ativo, os tipos de `src/main/java`, o `implements` lido no fonte, as specs (para drift) | métodos, campos, DTOs, config, testes |
| `blueprint` · `docs/diagrams/blueprint/` | Containers (C4 nível 2): a app, os serviços do compose, sistemas externos que uma spec nomeia; protocolo em cada aresta | O que roda junto, e como a app fala com cada peça? | `docker-compose.yml`, `application*.yml` (tópicos), `CREATE TABLE` das migrations, as linhas `External calls` | portas internas, réplicas, segredos, valores de variável |
| `use-cases/map` · `docs/diagrams/use-cases/map/` | Mapa: gatilho → use case → o que ele toca (tabela, tópico), o backlog apagado embaixo | Quais casos existem, o que os dispara, o que eles compartilham? | as linhas `Trigger` e `Side effects` de cada `00-*.md`, a tabela ativa do `BACKLOG.md` | o fluxo interno de cada caso |
| `use-cases/UC-NNN-<slug>` · `docs/diagrams/use-cases/UC-NNN-<slug>/` | Dois painéis: sequência (o caminho que dá certo) e fluxograma de decisões, cada participante conferido no código (✓ ou ✗) | Como este caso anda, e onde ele para? | os blocos `mermaid` da própria spec; sem bloco, a cadeia do cabeçalho Trigger → adapter → use case → Side effects, com a legenda `spec has no flowchart` | path, verbo, status, exceções, retries; `classDiagram` e `stateDiagram-v2` ficam na spec |

O `/project-diagrams` sem argumento desenha os três primeiros; `architecture`, `blueprint`, `map` ou
`UC-NNN-<slug>` desenha um só. O `/new-feature-implement` imprime `/project-diagrams UC-NNN-<slug>` no
relatório final, ao lado da linha do `/git-publish`, e nunca o executa.

Regras de desenho: um tipo que a spec nomeia e `src/` não tem fica rosa e tracejado, com o rótulo
`spec only`; um serviço de ferramenta de desenvolvimento no compose fica cinza e tracejado; a aresta
`implements` só existe quando lida no fonte; tipos de suporte (mapper, config, properties) viram legenda,
não caixa; o verbo de uma aresta vem da linha `Side effects`, ou a aresta fica sem rótulo.

## Os estilos do blueprint view

`"style"` no `view.json` da view: `brand` (o padrão), `icons` ou `boxes`.

| Estilo | Um serviço do compose é desenhado como |
|---|---|
| `brand` | o logo do produto, na cor da marca; o logo preto usa a cor do texto |
| `icons` | um ícone genérico do papel — cilindro, faixas de partição, escudo, funil, nuvem — na cor da paleta |
| `boxes` | um retângulo com texto |

O mapa `imagem → logo` é dado, `diagrams.image_icons` do `extensions.json` (`postgres*` → `postgresql`).
Serviço sem logo mapeado, o cliente e um sistema externo caem no ícone genérico. Os logos vêm do
`simple-icons@13.21.0` e ficam em `.claude/skills/project-diagrams/references/icons/`, com um `NOTICE`
que nomeia fonte e licença de cada ícone:

| Logo | Licença e política |
|---|---|
| Apache Kafka | Apache-2.0; a marca é da ASF (política de marcas) |
| Keycloak | a política de marca da Linux Foundation |
| OpenTelemetry | CC-BY-4.0, exige atribuição |
| PostgreSQL, SonarQube, Spring Boot | a política de marca de cada projeto |

Quem não quer marca no diagrama usa `icons` ou `boxes`.

## De onde vêm os fatos

O `references/project-facts.md` é o dono único: cada prefixo de id de fato (`pkg:`, `type:`, `impl:`,
`service:`, `topic:`, `table:`, `external:`, `uc:`, `bl:`, `mermaid:`, `drift:`) e o arquivo de onde ele
é lido. Toda lista que o extrator usa — os globs dos inputs, os papéis dos pacotes, as imagens do compose e
seus logos, as palavras de gatilho — é o bloco `diagrams` de `.claude/schemas/extensions.json`, nunca uma
constante no Java. Um bloco Mermaid de spec cuja primeira linha não é um dos quatro tipos permitidos
interrompe a extração e nomeia o arquivo e a linha.

## A checagem de legibilidade

Diagrama cujos rótulos colidem não se lê, por mais certos que estejam os fatos. Por isso o motor deve uma
garantia, em Java, idêntica byte a byte em qualquer SO:

1. **Medir, depois posicionar.** A largura de um rótulo vem de uma tabela de larguras de avanço versionada
   em `references/visual-identity.md`, nunca do AWT. A caixa tem a largura do maior rótulo mais o padding;
   o espaço entre colunas, ou entre linhas de vida, é pelo menos o do maior rótulo de aresta que ele carrega.
2. **Quebrar antes de encolher.** Um rótulo além de `max_label_width` quebra em fronteira de palavra, ou
   de camel case no nome de um tipo, em no máximo `label_lines` linhas. A fonte nunca encolhe.
3. **Aresta contorna texto.** A aresta corre no espaço entre duas colunas, numa pista só dela, e o rótulo
   fica no último trecho reto, ao lado da própria aresta.
4. **Checar depois de renderizar, exit 2.** `validate`, `render` e `check` leem o SVG que produziram e
   nomeiam as duas partes e a view: `TEXT×TEXT`, `TEXT×SHAPE`, `TEXT×LINE`, `OUT OF VIEWBOX`, `LINE×LINE`. O badge (o ✓
   do participante) é a única exceção declarada. Nada é escrito diante de um achado: conserta-se com novo
   layout, nunca apagando um fato.
5. **Nas duas fontes.** As views do harness deste repositório passam pela mesma checagem.

`Diagrams.java legible <arquivo.svg>` roda só a checagem num arquivo.

## Como usar

```bash
java .claude/skills/project-diagrams/scripts/Diagrams.java facts    --source project
java .claude/skills/project-diagrams/scripts/Diagrams.java render   --source project [architecture|blueprint|map|UC-NNN-<slug>]
java .claude/skills/project-diagrams/scripts/Diagrams.java check    --source project
```

O `render` escreve, ao lado de cada view, `.drawio`, `.excalidraw`, `.svg` e `.animated.svg`, e o
`docs/diagrams/.inputs.sha256` do projeto: o hash de cada arquivo de que as views foram desenhadas. Sem
`view.json` para o tipo pedido, escreve o padrão (`kind`, `title`, `facts`, e `style` no blueprint view).
Sem `docs/use-cases/`, o mapa e os fluxos são pulados com uma linha.

**PNG** precisa de Node, opcional e só para isso: uma vez por máquina, `npm ci` a partir do
`package-lock.json` da skill num cache por usuário, e então
`node .claude/skills/project-diagrams/scripts/export.mjs <view>`. Sem Node o PNG é pulado, e todo o resto
continua rodando em Java.

## A linha do `doctor`

`Diagrams` refaz o hash dos arquivos listados em `docs/diagrams/.inputs.sha256` e dos que os globs
casam agora. ⚪ quando nenhum diagrama foi renderizado ainda; ⚠️ nomeando o primeiro input que mudou, sumiu
ou apareceu desde então, com o comando a rodar. É aviso, nunca entra em `doctor.gate.labels`, e o CI do
projeto gerado não tem passo de diagrama: diagrama velho é visível, não perigoso.

## O que o CI prova

Só neste repositório: o `DiagramsTest.java` mantém as views do harness iguais a uma renderização nova, e
roda `--source project` sobre um projeto de fixture que ele escreve — pacote renomeado (`fact … not
found`), bloco Mermaid que não é um tipo permitido (apontado por arquivo e linha), serviço do compose
removido (`differs from a fresh render`), nome de tipo de 70 caracteres que precisa quebrar linha — além de
um caso vermelho por achado de legibilidade e da linha do `doctor` (um input alterado depois do render
avisa, `doctor gate` não se move).
