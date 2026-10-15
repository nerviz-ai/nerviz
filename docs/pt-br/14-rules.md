# Rules — o papel de cada norma e onde termina a responsabilidade dela

English version: [`docs/en/14-rules.md`](../en/14-rules.md).

Esta página responde a uma pergunta: **qual rule decide o quê, e onde ela passa a palavra para
outra.** Ela não diz *o que* cada norma manda. Para isso, abra a rule: ela é a fonte única
(`CLAUDE.md`, invariante 2), e esta página só aponta para ela. Quando esta página e uma rule
discordarem, vale a rule. O mapa curto, uma linha por rule, é
[`.claude/rules/00-index.md`](../../.claude/rules/00-index.md). O mecanismo de carga (`paths`,
citação) está em [01-tipos-de-arquivo.md § Rule](01-tipos-de-arquivo.md#rule).

Cada rule abaixo tem quatro entradas:

- **Papel** — a responsabilidade que só ela tem.
- **Carrega quando** — o território dos `paths`, em palavras.
- **Não é dela** — a fronteira, e quem é dono do outro lado.
- **Verificada por** — o que pega uma violação. "Review" significa que nada mecânico pega.

As rules marcadas com ⟳ têm `paths` de exemplo neste repositório. O `export` reescreve esses
`paths` a partir do `packages.map` do blueprint ativo, porque o nome do pacote muda de arquitetura
para arquitetura (`adapter/in/rest` em uma, `infrastructure/rest` em outra).

---

## Como elas se agrupam

| Grupo | Rules | O que têm em comum |
|---|---|---|
| Fundação | `architecture-ddd`, `naming`, `code-quality`, `error-handling`, `logging`, `lombok`, `value-objects` | Valem para todo código Java, qualquer que seja a camada |
| Dados que vazam | `personal-data`, `secrets` | Atravessam todas as camadas, porque o vazamento é decidido longe de onde acontece |
| Entrada | `api-rest`, `api-graphql`, `authorization`, `inbound-admission`, `transport-security`, `observability` | O caminho de uma requisição até o use case |
| Saída | `persistence`, `messaging`, `http-client`, `ai`, `caching`, `search` | Cada uma é dona de um tipo de adapter outbound |
| Relógio | `scheduling` | Código que nada upstream espera |
| Execução | `virtual-threads` | Em que threads o código roda, decidido pela configuração e não por uma camada |
| Testes e specs | `testing`, `spec-diagrams` | O território não é `src/main` |

---

## Fundação

### `architecture-ddd.md` — direção das dependências

- **Papel:** decide quem pode importar quem. As dependências apontam para dentro, e o domínio
  não conhece framework. O domínio é o mesmo em todo blueprint. As outras camadas fixam só o
  princípio, e o nome de cada abstração (port, use case, service) é do blueprint.
- **Carrega quando:** um arquivo das camadas é tocado. Os globs vêm de `architecture_paths` do
  blueprint ativo, não de uma lista fixa.
- **Não é dela:** o nome das classes (`naming.md`), o que um adapter de persistência pode expor
  (`persistence.md` § Boundary) e a forma das exceções (`error-handling.md`).
- **Verificada por:** `ArchHook.java check` na edição e `doctor gate` no CI, ambos lendo os
  `forbidden_imports` do blueprint. No build, a compilação isolada do módulo interno. No layout
  single-module não há módulo para isolar, e o teste ArchUnit é a única verificação fora do
  editor.

### `naming.md` — o nome diz o papel

- **Papel:** decide o nome de pacotes, classes, métodos e testes. O nome diz o papel, nunca o
  padrão de projeto. Inclui o identificador de use case (`UC-NNN-slug`) e os identificadores
  restritos.
- **Carrega quando:** qualquer Java de `src/`.
- **Não é dela:** o vocabulário do use case e dos ports (`UseCase`, `Service`, `Handler`). Esse
  vocabulário é da convenção do blueprint, que vence quando discorda da tabela da rule. A forma
  de um teste é de `testing.md`.
- **Verificada por:** review. O Checkstyle pega o formato (pacote, tipo, método, identificador
  restrito) e o ArchUnit pega o sufixo `Impl`.

### `code-quality.md` — SOLID e Clean Code, só o que sobra

- **Papel:** é dona dos limites de tamanho e complexidade, de `null` e mutabilidade, do
  `switch` sobre tipo selado e da política de comentários. Também decide onde mora um desvio
  sobre enum ou tipo selado: no próprio tipo, em um `switch` expressão na camada, ou em um
  Strategy. E exige que um `switch` que calcula valor seja expressão. Cobre de propósito só o
  que nenhuma outra rule cobre.
- **Carrega quando:** qualquer Java de `src/`.
- **Não é dela:** SRP e DIP de camada (`architecture-ddd.md`), nomes (`naming.md`) e exceções
  (`error-handling.md`). A exceção "corpo vazio com um `//`" da política de comentários **não**
  vale para um `catch`: um `catch` vazio é assunto de `error-handling.md`.
- **Verificada por:** Checkstyle na fase `validate`. Cada módulo do `checkstyle.xml` corresponde
  a um limite da rule, e mudar um lado sem o outro é bug. Spotless cuida da formatação. O ArchUnit
  cobre ISP, campos `private final` no domínio e `throw` genérico. OCP, LSP, o nível de
  abstração e onde mora um desvio sobre conjunto fechado ficam com o review.

### `error-handling.md` — o que é um erro, e o que um `catch` faz com ele

- **Papel:** é dona da taxonomia de exceções, agnóstica de transporte. São duas raízes e sete
  famílias: quatro de domínio, para entrada ou estado errados, e três de integração, para um
  sistema chamado que não respondeu de forma útil. Também decide o que um `catch` pode fazer e
  o que é específico de uma exceção quando ela chega ao log (stack trace ou não, `errorCode`,
  `dependency`).
- **Carrega quando:** qualquer Java de `src/`.
- **Não é dela:** o status HTTP de cada família (`api-rest.md`) e a semântica de `ERROR`, `WARN`
  e `INFO` (`logging.md`). A recusa por quota ou concorrência não é nenhuma das sete famílias: é
  de `inbound-admission.md`.
- **Verificada por:** teste de contrato (tipo lançado + `errorCode`). No Checkstyle,
  `IllegalCatch`, `IllegalThrows` e `EmptyCatchBlock` (só em `src/main`). No ArchUnit, o `throw`
  genérico. O `catch` que engole atrás de um valor placeholder fica com o review.

### `logging.md` — a linha de log como única testemunha

- **Papel:** decide o que se escreve no log e como. Cobre o `logback-spring.xml` e seu padrão, a
  semântica dos níveis (`DEBUG`/`TRACE` só quando pedidos; requisição recusada em `WARN`, sem a
  exceção), o que cada tipo de classe loga e o que fica fora do log. É dona de
  **derivar** quais campos são candidatos a máscara, por tipo ou por nome.
- **Carrega quando:** qualquer Java de `src/`.
- **Não é dela:** de onde vem o valor do `traceId` (`observability.md`) e o stack trace e os
  campos de uma exceção (`error-handling.md`). O mesmo dado pessoal fora do log, numa coluna ou
  num tópico, é de `personal-data.md`.
- **Verificada por:** o ArchUnit pega o DTO REST com campo sensível sem máscara e o domínio que
  depende de API de log. Os `forbidden_imports` do módulo de domínio barram o import. Formato,
  níveis e conteúdo por classe ficam com o review; nos templates daqui, o `TemplateLogLevelTest`
  barra `DEBUG`/`TRACE` e a rejeição fora de `WARN`.

### `lombok.md` — só gera o que não viola invariante

- **Papel:** decide quais anotações Lombok podem ser geradas. Um acessor de leitura não viola
  invariante; um mutador sempre pode violar. Também decide o que o Lombok escreve por padrão:
  `@Slf4j` no lugar do logger escrito à mão, `@RequiredArgsConstructor` no lugar do construtor que
  só atribui campos `final` (fora do domínio e das exceções que ela lista) e `@FieldDefaults`
  quando todo campo de instância é privado.
- **Carrega quando:** qualquer Java de `src/`.
- **Não é dela:** a imutabilidade do agregado (`architecture-ddd.md`) e o value object em si
  (`value-objects.md`). O Lombok **não** conta como framework para a pureza do domínio, porque
  nada dele sobrevive no bytecode. A exceção são os loggers do Lombok, que `logging.md` proíbe
  no domínio.
- **Verificada por:** o compilador, via `lombok.config` com `flagUsage = ERROR`. A condição do
  `@Getter` (retorno imutável) e o que o Lombok escreve por padrão ficam com o review; nos
  templates daqui, o `TemplateLombokTest` barra o logger, o construtor e o modificador escritos à
  mão.

### `value-objects.md` — quando um campo deixa de ser `String`

- **Papel:** é dona do critério que transforma um campo em value object (existe uma entrada
  capaz de ser rejeitada), do catálogo, do contra-catálogo e do tipo de cada número.
- **Carrega quando:** Java do domínio. ⟳
- **Não é dela:** a exceção que a invariante lança (`error-handling.md`) e a pureza do domínio
  (`architecture-ddd.md`). Os adapters recebem texto de fora de propósito, e o critério não
  vale lá. Quais desses tipos são dado pessoal é usado por `logging.md` e `personal-data.md`.
- **Verificada por:** review.

---

## Dados que vazam

### `personal-data.md` — dado pessoal em repouso e em trânsito

- **Papel:** decide onde dado pessoal pode ficar e por onde pode sair: colunas de payload,
  retenção, o mínimo de que um receptor precisa e a decisão registrada quando um valor precisa
  cruzar em claro.
- **Carrega quando:** todo Java e toda migration. É larga de propósito: no caso observado, o
  campo que vazou foi decidido num evento de domínio, longe do adapter que o publicou.
- **Não é dela:** a máscara no **log** (`logging.md` § Masking candidates; esta rule começa onde
  aquela para), credenciais (`secrets.md`) e autenticação (`authorization.md`).
- **Verificada por:** `grep` da § How to verify e o registro de design que ela exige.

### `secrets.md` — onde uma credencial pode morar

- **Papel:** decide onde um segredo pode existir: nunca num arquivo versionado, placeholders sem
  default, nada embutido em imagem, material de chave montado em runtime e gerado pelos testes.
  Também decide o que fazer quando um segredo vaza.
- **Carrega quando:** todo fonte e todo arquivo de configuração (`.yml`, `.properties`,
  `Dockerfile`). Antes, essa norma estava repetida em quatro rules de fronteira. Agora cada uma
  delas cita esta.
- **Não é dela:** o que pode aparecer no log (`logging.md`) e dado pessoal (`personal-data.md`).
- **Verificada por:** `grep` e review.

---

## Entrada

### `api-rest.md` — a forma do contrato HTTP

- **Papel:** decide só a forma do contrato exposto: recursos e URIs, verbos, status de sucesso e
  de erro, Problem Details, versionamento, paginação, idempotência e OpenAPI. O cliente distingue
  o que aconteceu pelo status.
- **Carrega quando:** o adapter REST de entrada é tocado. ⟳
- **Não é dela:** regra de negócio (`architecture-ddd.md`), a taxonomia de erros
  (`error-handling.md`, que esta rule só mapeia para status) e o nome das classes (`naming.md`).
  Os corpos de 401 e 403 seguem a forma daqui, mas *quem* pode chamar é de `authorization.md`. O
  429 e o 503 de admissão são de `inbound-admission.md`.
- **Verificada por:** `grep`, o OpenAPI e testes de contrato.

### `api-graphql.md` — o que substitui as proteções que o HTTP dava de graça

- **Papel:** decide schema, resolvers, batching, connections, erros, limites de profundidade e
  complexidade, introspecção e evolução do schema. Também é dona do **critério de escolha** entre
  REST, GraphQL ou os dois para um use case.
- **Carrega quando:** o adapter GraphQL ou os arquivos `.graphqls` são tocados. ⟳
- **Não é dela:** a semântica HTTP compartilhada com REST (status, Problem Details,
  `Idempotency-Key`). Ela é de `api-rest.md` e não vale aqui, a menos que esta rule diga o
  contrário. Também não são dela onde cada checagem de acesso fica (`authorization.md`; esta rule
  só diz como aplicá-la num endpoint único, por operação e por campo) e a quota
  (`inbound-admission.md`).
- **Verificada por:** `grep`, teste de slice GraphQL, teste HTTP pela filter chain real e teste
  de compatibilidade de schema.

### `authorization.md` — quem pode chamar

- **Papel:** decide onde cada checagem fica (papéis na fronteira, ownership no use case), o deny
  por padrão, um mecanismo por tipo de chamador, stateless, CSRF, CORS e headers, e o que uma
  recusa nunca revela ao chamador. Vale só para a stack servlet.
- **Carrega quando:** os adapters REST e GraphQL ou a configuração são tocados. ⟳
- **Não é dela:** a tabela de status e o formato Problem Details do corpo (`api-rest.md` §
  Errors e § Error body), a máscara de credencial no log (`logging.md`) e onde a credencial
  mora (`secrets.md`).
- **Verificada por:** `grep` e testes de contrato pela filter chain real.

### `inbound-admission.md` — recusar antes do use case

- **Papel:** decide a quota por chamador e o teto de concorrência na entrada, REST e GraphQL. Um
  limite só existe quando o use case declara um.
- **Carrega quando:** os adapters REST e GraphQL são tocados. ⟳
- **Não é dela:** a resiliência de chamadas que **saem** (`http-client.md`). A recusa não é
  família de domínio nem de integração (`error-handling.md`). A forma do 429 e do 503 segue
  `api-rest.md`.
- **Verificada por:** `grep` e testes de integração contra um servidor real, com o contexto
  completo.

### `transport-security.md` — como a requisição chega

- **Papel:** decide onde o TLS termina (edge, direto, mutual TLS, mesh), forwarded headers e
  proxies confiáveis, o SSL bundle, as versões de protocolo, HTTP/2, HSTS e a renovação de
  certificado. É decidido uma vez por projeto, antes do primeiro endpoint.
- **Carrega quando:** `application*.yml`, `Dockerfile` ou `docker-compose*.yml` são tocados.
- **Não é dela:** o TLS das chamadas que **saem** (`http-client.md` § Security) e onde a chave e o
  keystore moram (`secrets.md`).
- **Verificada por:** `grep` e testes de integração contra um servidor real.

### `observability.md` — a fiação do vendor

- **Papel:** decide de onde vem o valor do identificador de correlação e como tracing e métricas
  se integram ao vendor (cardinalidade, health endpoint, anotações).
- **Carrega quando:** os adapters de entrada REST e GraphQL são tocados. ⟳
- **Não é dela:** o que se loga, em que nível e em que forma (`logging.md`). Esta rule decide só
  que o valor existe e de onde vem. `logging.md` decide onde ele fica na linha.
- **Verificada por:** testes de contrato e a subida do contexto.

---

## Saída

### `persistence.md` — o store como detalhe do adapter

- **Papel:** decide a fronteira comum às duas famílias de store (um store por agregado, um
  repositório por agregado, tradução de exceções, queries paginadas), o mapeamento relacional
  (JPA, migrations, N+1) e documental (MongoDB, `@Version`, índices, `schemaVersion`), e a
  auditoria (carimbos, histórico Envers).
- **Carrega quando:** o adapter de persistência ou as migrations são tocados. ⟳
- **Não é dela:** **escolher** entre relacional e documental, que é um fato do use case registrado
  antes. Retenção de dado pessoal é de `personal-data.md`; o job que a aplica é de
  `scheduling.md`. O second-level cache fica desligado por `caching.md`.
- **Verificada por:** `grep` e testes de integração.

### `messaging.md` — o evento depois que ele sai do domínio

- **Papel:** decide o adapter que publica um evento num broker e o que o lê de volta: semântica
  de entrega (at-least-once, consumidor idempotente), momento da publicação (direto ou outbox +
  relay), tópicos e serialização, o contrato AsyncAPI gerado do código e comparado com um snapshot
  commitado (os dois lados anotados; o spec-first registrado com quando usar e quando não), retry e
  DLQ. Hoje só Kafka; um segundo broker entra como seção nova, não como arquivo novo.
- **Carrega quando:** os adapters de mensageria de entrada ou saída são tocados. ⟳
- **Não é dela:** o evento de domínio em si, um fato no passado e sem framework
  (`architecture-ddd.md` § Domain). O gatilho que roda o relay no relógio é de `scheduling.md`.
- **Verificada por:** `grep` e testes de integração.

### `http-client.md` — chamar outro serviço

- **Papel:** decide todos os defaults de uma biblioteca HTTP que ninguém escolheu: cliente, engine
  e pool, timeouts, **uma** camada de retry, mapeamento de falha pela pergunta "a requisição foi
  enviada?", resiliência, autenticação de saída, TLS e SSRF, consumo limitado e URI templates.
- **Carrega quando:** o adapter HTTP de saída é tocado. ⟳
- **Não é dela:** as três famílias de integração que a falha vira (`error-handling.md` §
  Integration families) e a credencial de saída (`secrets.md`). A recusa na **entrada** é de
  `inbound-admission.md`.
- **Verificada por:** `grep` e testes de integração contra um servidor stub.

### `ai.md` — o modelo sugere, o domínio decide

- **Papel:** decide chamadas a um modelo de linguagem (resposta estruturada) e a um vector store
  (busca por significado): modelo e opções por port, prompts versionados, resposta tipada com
  campos fechados, o que vai para o provedor, threshold nomeado, uma camada de retry e métricas de
  token.
- **Carrega quando:** o adapter de IA ou os prompts são tocados. ⟳
- **Não é dela:** a busca por **texto** e facetas (`search.md`) e o transporte HTTP genérico
  (`http-client.md`). Tool calling, chat memory e geração sobre passagens recuperadas estão fora,
  e usar um deles exige uma decisão registrada antes.
- **Verificada por:** `grep`, teste de contrato com modelo stub, teste de integração com provedor
  stub e teste de integração com o engine real do store.

### `caching.md` — a cópia que precisa ficar honesta

- **Papel:** decide o cache de aplicação como decorator de um port de saída. Só existe com uma
  tolerância de frescor declarada pelo negócio. Cobre chave por chamador, expiração e tamanho,
  evicção depois do commit, stampede e falha do cache que nunca derruba a leitura.
- **Carrega quando:** o pacote de cache é tocado. ⟳
- **Não é dela:** o cache HTTP de resposta (o transporte) e o second-level cache do Hibernate, que
  esta rule manda manter desligado: três caches sobre uma leitura são três caminhos de
  invalidação.
- **Verificada por:** `grep` e testes de integração que provam o hit, a evicção e a chave por
  chamador.

### `search.md` — o índice como projeção reconstruível

- **Papel:** decide um índice Elasticsearch ou OpenSearch alimentado por um source of truth:
  escrito por um indexer a partir de um evento, reconstruível desde o primeiro índice, mapping
  versionado, alias trocado atomicamente e versão externa na escrita.
- **Carrega quando:** o adapter de busca ou os mappings são tocados. ⟳
- **Não é dela:** a busca por significado (`dense_vector`, kNN), que é vector store e pertence a
  `ai.md` mesmo no mesmo engine. Full-text dentro do próprio banco do agregado (`tsvector`, text
  index do MongoDB) é índice desse banco, logo de `persistence.md`.
- **Verificada por:** `grep` e testes de integração contra o engine real, com eventos fora de
  ordem e troca de alias.

---

## Relógio

### `scheduling.md` — o código que ninguém espera

- **Papel:** decide jobs que rodam no próprio relógio. O trigger é um adapter de entrada, como um
  controller. Cobre fixed delay ou rate, fuso do cron, propriedade liga/desliga, coordenação entre
  instâncias, execuções perdidas, passadas limitadas e métrica de último sucesso. Também é dona da
  frase "uma janela de retenção só existe enquanto um job a aplica".
- **Carrega quando:** o pacote de agendamento é tocado. ⟳
- **Não é dela:** o que o job faz, que é o use case chamado pelo port de entrada. O relay de outbox
  é de `messaging.md`; esta rule cuida só do relógio dele.
- **Verificada por:** `grep` e testes unitários pelo port de entrada.

---

## Execução

### `virtual-threads.md` — em que threads o código roda

- **Papel:** decide que um projeto cujo build mira JDK 21 ou mais liga virtual threads e o
  keep-alive ao lado delas, e que nenhum profile as desliga sem dizer por quê. A versão é a do
  build, não a da máquina. Também diz o que o pool de requisições deixa de limitar.
- **Carrega quando:** um `application*.yml`, `.yaml` ou `.properties` de `src/main/resources` é
  tocado.
- **Não é dela:** o tamanho do teto de concorrência na entrada (`inbound-admission.md`) e o pool
  do scheduler abaixo do JDK 21 (`scheduling.md`). Os dois citam esta rule para o caso ligado.
- **Verificada por:** `grep` e a linha `Virtual threads` do `doctor`, que avisa e nunca bloqueia.

---

## Testes e specs

### `testing.md` — um teste existe para falhar

- **Papel:** decide a pirâmide e os níveis, slices e contexto, doubles, nome e forma, asserções,
  efeitos assíncronos, dados de teste, banco nos testes de integração, gate de cobertura e testes
  de arquitetura.
- **Carrega quando:** qualquer arquivo de `src/test/`.
- **Não é dela:** o que cada teste de fronteira precisa provar. Isso está no § Tests de cada rule
  de adapter (por exemplo, `transport-security.md` precisa de socket real). Nomes e limites de
  código também não são dela (`naming.md`, `code-quality.md`).
- **Verificada por:** `./mvnw verify` (failsafe + JaCoCo) e ArchUnit.

### `spec-diagrams.md` — o diagrama é uma vista do texto

- **Papel:** decide os diagramas dos specs de use case: Mermaid dentro do markdown, só nomes que o
  texto define, nenhum fato que o texto não tenha (e o texto vence quando discordam), e o que cada
  tipo de diagrama desenha.
- **Carrega quando:** `docs/use-cases/**`, tanto quando um spec é escrito quanto quando é lido para
  implementar. É a única rule cujo território é documentação, e por isso o glob viaja sem
  reescrita.
- **Não é dela:** o conteúdo do spec.
- **Verificada por:** `grep` e review. O CI confere os diagramas dos próprios templates de spec
  contra o texto deles.

---

## Perguntas de fronteira — qual rule responde

| Pergunta | Dona | Não é |
|---|---|---|
| Que status HTTP um `NotFoundException` vira? | `api-rest.md` | `error-handling.md`, que só define a família |
| Logo esta exceção com stack trace? | `error-handling.md` § Logging and verification | `logging.md`, que fixa só a semântica do nível |
| Este `catch` pode ficar vazio, ou só com um comentário? | `error-handling.md` § A caught exception is never silent | `code-quality.md` § Comments, que exclui o `catch` da exceção "corpo vazio" |
| De onde vem o `traceId`? E onde ele fica na linha? | `observability.md` / `logging.md` | — |
| Este CPF pode aparecer no log? E numa coluna `jsonb` ou num tópico? | `logging.md` / `personal-data.md` | — |
| Esta senha pode ficar no `application.yml`? | `secrets.md` | `logging.md`, `personal-data.md` |
| Quem pode chamar este endpoint? E o formato do corpo da recusa? | `authorization.md` / `api-rest.md` § Error body | — o que o corpo **não** pode revelar volta para `authorization.md` |
| TLS na entrada? E na chamada ao parceiro? | `transport-security.md` / `http-client.md` | — |
| 429 por quota do chamador, ou 429 que o parceiro devolveu? | `inbound-admission.md` / `http-client.md` (vira `DependencyUnavailableException`) | — |
| Busca por palavras, ou por significado? | `search.md` / `ai.md` | `persistence.md`, se for full-text no próprio banco |
| Esta leitura pode vir de cache? | `caching.md`, só com tolerância de frescor declarada | `persistence.md` (second-level cache desligado) |
| Quem apaga o dado vencido? | `personal-data.md` decide a retenção, `scheduling.md` o job que a aplica | — |
| `@Slf4j` numa classe de domínio? | `logging.md`, que proíbe | `lombok.md`, que libera o Lombok em geral |
| Virtual threads ligadas? E quantas requisições podem rodar ao mesmo tempo? | `virtual-threads.md` / `inbound-admission.md` § Concurrency cap | — |

---

## Exemplo: um `catch` passando pelas rules

O código abaixo veio de um projeto gerado. O use case de anonimizar responde 204 mesmo quando o
cliente não existe:

```java
catch (CustomerNotFoundException e) {
    // answered as success by decision of the spec
}
```

Cada rule responde uma parte:

1. **`error-handling.md`** — o `catch` precisa fazer uma de quatro coisas. Devolver como
   resposta não serve, porque o 204 é idêntico ao sucesso. Sobra **logar**, e uma família de
   domínio vai em `WARN`, sem stack, com `errorCode` como campo.
2. **`code-quality.md`** — o comentário não legitima o bloco, e o motivo que ele dizia vai para a
   mensagem do log.
3. **`logging.md`** — `WARN` porque é uma rejeição de negócio esperada. A linha fica no adapter
   ou no service, nunca no domínio.
4. **`logging.md` § Masking candidates** — loga o `customerId`, uma chave técnica, e não o
   documento do cliente.
5. **`api-rest.md`** — o 204 em si continua sendo decisão do contrato, registrada no spec.

O build pega o passo 1 pelo `EmptyCatchBlock` do Checkstyle. Os passos 3 a 5 ficam com o review.

---

## Manter esta página

Uma rule nova entra aqui, nas duas línguas, com as quatro entradas e, se for o caso, uma linha
em "Perguntas de fronteira". Esta página não repete números, listas nem exemplos de uma rule:
quando a rule muda, só "Papel" e "Não é dela" podem ficar desatualizados, e os dois mudam pouco.
Nenhum job de CI confere esta página contra `.claude/rules/`.
