# Blueprints de arquitetura

*[English](README.md)*

Cada pasta aqui é uma arquitetura pronta para gerar um projeto Spring Boot via
`project-bootstrap` (`/init-project`). O contrato completo que todo `<id>.yaml` segue
está em `@.claude/blueprints/_schema.md` — os campos `when_to_choose` e `trade_offs` de
cada arquivo são a fonte da verdade; este README é só um resumo de leitura rápida para
decidir por qual começar. Em todas elas, `rules/architecture-ddd.md` (domínio sem
framework, injeção por construtor, um caso de uso por classe) vale sem exceção — o que
muda de arquitetura para arquitetura é como as camadas são nomeadas e agrupadas, nunca
essas invariantes.

## Tabela rápida

| Arquitetura | Build | Ideia central |
|---|---|---|
| [`layered`](#layered-n-tier) | single-module | Controller → Service → Repository, vocabulário clássico de tutorial Spring |
| [`clean-architecture-single-module`](#clean-architecture-uncle-bob) | single-module | domain/application/infrastructure como pacotes |
| [`clean-architecture-multi-module`](#clean-architecture-uncle-bob) | multi-module | domain/application/infrastructure como módulos Maven |
| [`hexagonal`](#hexagonal-ports--adapters) | multi-module | Portas (interfaces) e adapters de entrada/saída em módulos próprios |
| [`hexagonal-single-module`](#hexagonal-ports--adapters) | single-module | As mesmas portas e adapters como pacotes de um módulo só |
| [`onion`](#onion-palermo) | multi-module | Domínio + serviços de domínio fundidos num "Application Core"; qualquer anel externo chama qualquer anel interno |
| [`onion-single-module`](#onion-palermo) | single-module | O mesmo Application Core e os mesmos anéis externos como pacotes de um módulo só |
| [`modular-monolith`](#modular-monolith-spring-modulith) | single-module | Vários bounded contexts como pacotes-módulo (`internal`), verificado por Spring Modulith |
| [`vertical-slice`](#vertical-slice-feature-slices) | single-module | Um pacote auto-contido por caso de uso (`features.<feature>.<usecase>`), não por camada técnica |
| [`custom-template`](#custom-template) | — | Ponto de partida para descrever sua própria arquitetura |

---

## `layered` (N-Tier)

**O quê:** pilha clássica Controller → Service → Repository, um módulo Maven só,
pacotes irmãos (`controller`, `service`, `repository`, `entity`, `dto`, `mapper`).

**Prós**
- Vocabulário que qualquer time Spring Boot já conhece — zero curva de aprendizado de
  nomenclatura.
- Menos arquivos por funcionalidade: sem par porta+adapter, o `Repository` é a própria
  fronteira (`extends JpaRepository`).

**Contras**
- Fronteira entre camadas só é validada por ArchUnit, não pelo compilador — nada
  impede um import indevido de compilar.
- Adicionar um segundo canal de entrada/saída depois exige mexer direto nas
  dependências da classe `Service`, sem porta para trocar por trás.

**Quando usar:** um canal de entrada (REST) e um canal de saída (um banco relacional),
CRUD onde um repositório Spring Data já é abstração suficiente entre serviço e banco.

---

## Clean Architecture (Uncle Bob)

Duas variantes, mesma separação domain → application → infrastructure; a diferença é
só onde a fronteira é imposta.

### `clean-architecture-single-module`

**Prós:** sem custo de manter 3 `pom.xml`; mesma disciplina de camadas do multi-module.
**Contras:** fronteira só validada em tempo de teste (ArchUnit) — uma classe em
`domain` pode fisicamente importar Spring e só o teste pega isso.
**Quando usar:** projeto pequeno/médio onde orquestração multi-módulo do Maven é
overhead que ninguém vai amortizar.

### `clean-architecture-multi-module`

**Prós:** regra de dependência imposta pelo próprio Maven (compila isolado = respeitou
fronteira); caso de uso é uma classe só, sem boilerplate de interface+impl por caso de
uso.
**Contras:** REST, persistência e mensageria dividem um único módulo `infrastructure`
— sem isolamento de build entre adapters; trocar a implementação de um caso de uso
significa editar a camada de aplicação, não só a infra.
**Quando usar:** sistema de vida longa, time pequeno/médio, regras de negócio que
precisam ficar isoladas de detalhes de infraestrutura sem pagar o preço de módulo por
adapter.

---

## Hexagonal (Ports & Adapters)

**O quê:** domínio no centro, portas como interfaces, adapters de entrada e saída nas
bordas, cada um em módulo próprio.

**Prós**
- Testa caso de uso sem subir contexto Spring.
- Troca infraestrutura sem tocar no domínio — múltiplos canais de entrada/saída
  (REST + mensageria + batch) convivem sem conflito.

**Contras**
- Mais módulos e mapeamento mais explícito que `layered`.
- Curva de entrada mais alta para quem chega no projeto.
- Overkill para CRUD simples.

**Quando usar:** mais de um canal de entrada ou saída, necessidade real de trocar
infraestrutura sem tocar domínio.

**`hexagonal-single-module`** é a mesma arquitetura com os módulos como pacotes de um módulo
Maven só (o mapa de pacotes é idêntico, então o código passa de um para o outro). A fronteira
deixa de ser imposta pelo compilador e passa ao hook de edição, ao `doctor gate` do CI e ao
ArchUnit; o mapa de fronteira derivado é mais fino que o do multi-module, e os adapters são
proibidos de importar uns aos outros, pacote por pacote. Use quando quiser o vocabulário
port/adapter sem um `pom.xml` por adapter.

---

## Onion (Palermo)

**O quê:** modelo de domínio e serviços de domínio fundidos num único "Application
Core" central (termo do próprio Palermo, sem módulo `application` separado);
persistência e apresentação como anéis externos independentes entre si — mas, ao
contrário de `layered`, qualquer anel externo pode chamar qualquer anel interno
diretamente.

**Prós**
- Terminologia própria (Application Core, Gateway) em vez de port/adapter do
  hexagonal.
- Persistência e apresentação não precisam ser estranhas uma à outra, mas nunca se
  chamam diretamente.

**Contras**
- Módulo de domínio maior que no hexagonal/clean architecture — modelo, serviços e
  toda interface `Gateway` moram juntos, mais mudanças tocam a mesma unidade de
  compilação.
- Regra "qualquer anel externo chama qualquer interno" é mais frouxa que a
  adjacência estrita do `layered` — mais fácil acoplar por engano.
- Persistência tem uma indireção a mais que o hexagonal: Gateway → Adapter →
  Repository.

**Quando usar:** domínio e serviços de domínio são projetados e mudam juntos, pelo
mesmo time, a maior parte do tempo — não vale um quarto módulo `application` só para

**`onion-single-module`** é a mesma arquitetura com os módulos como pacotes de um módulo Maven
só (o mapa de pacotes é idêntico, então o código passa de um para o outro). A fronteira deixa
de ser imposta pelo compilador e passa ao hook de edição, ao `doctor gate` do CI e ao ArchUnit;
os pacotes `app.*` mantêm o nome do módulo de composição, e `persistence` e `presentation`
são proibidos de importá-los. Use pelo vocabulário do Palermo sem um `pom.xml` por anel.
separá-los.

---

## `modular-monolith` (Spring Modulith)

**O quê:** um único deployável, vários módulos de bounded context como
sub-pacotes diretos da raiz da aplicação, cada um com um pacote `internal` oculto e
uma superfície pública pequena — verificado em tempo de teste por
`ApplicationModules.verify()`. Dentro de `internal`, a mesma camada
domain/application/adapter do hexagonal, com todo o rigor.

**Prós**
- Fronteira real de módulo sem precisar de deploy separado — caminho de saída para
  microsserviços já preparado.
- `rules/architecture-ddd.md` não relaxa dentro de cada módulo.

**Contras**
- Uma segunda ferramenta de verificação além do ArchUnit
  (`ApplicationModules.verify()`).
- Nomes de módulo são dado de negócio, não de arquitetura — não dá para pré-criar
  antes do primeiro caso de uso real existir.
- Ainda um único deployável: um módulo problemático pode esgotar a mesma JVM que
  todos os outros — isolamento estrutural, não de runtime.

**Quando usar:** vários bounded contexts que ainda não justificam deploys separados,
mas precisam de fronteira mais forte que "por favor não importe esse pacote"; ou
quando existe intenção real de migrar para microsserviços depois.

---

## `vertical-slice` (Feature Slices)

**O quê:** código agrupado por funcionalidade, não por camada técnica — cada caso de
uso ganha um pacote auto-contido (`features.<feature>.<usecase>`) com
Command/Query, Handler, Endpoint e Response (padrão REPR). Só o agregado cruza a
fronteira entre slices, mantido num kernel `domain` compartilhado; a porta de
persistência e seu adapter ficam dentro do próprio slice, não num módulo
compartilhado como nas outras arquiteturas.

**Prós**
- Adicionar funcionalidade custa só arquivo novo — nunca edita classe de serviço ou
  repositório compartilhada por todo mundo.
- Isolamento entre slices reduz conflito de merge e prepara extração futura de
  microsserviço, um nível abaixo do que `modular-monolith` faz por bounded context.

**Contras**
- Sem framework de mediator (papel do MediatR em .NET) — sem pipeline automático de
  validação/log/transação por handler, cada slice monta o próprio.
- Persistência espalhada por slice em vez de centralizada — dois slices que tocam o
  mesmo agregado podem duplicar porta e query se ninguém promover para o kernel
  `domain`.
- Isolamento entre slices-irmãos não é uma regra estática do ArchUnit (nomes de
  slice ainda não existem no momento do blueprint) — só é aplicável depois que o
  segundo slice existir, via `slices()` do ArchUnit.

**Quando usar:** funcionalidades divergem mais do que se sobrepõem, time quer que
feature nova custe só arquivo novo, ou reuso entre features importa menos que
isolamento e velocidade de entrega.

---

## `custom-template`

Não é uma arquitetura — é o ponto de partida para descrever a sua. Copie
`custom-template/custom.template.yaml`, preencha seguindo o checklist de 5 regras de
`_schema.md`, e valide: o build final é o árbitro (se compila respeitando os módulos
declarados, a arquitetura foi respeitada).

---

## Como escolher

Ver `@.claude/skills/project-bootstrap/references/blueprint-selection.md` para a
tabela de decisão apresentada durante `/init-project` e as perguntas de desempate
(quantos canais de entrada em 12 meses, quantos bounded contexts, quantas pessoas
tocam o repo).
