# `/platform-upgrade` — levar um projeto até o piso do Spring Boot

Fonte primária: `.claude/skills/platform-upgrade/SKILL.md`, `.claude/hooks/ArchHook.java`
(modo `upgrade-recipe`), bloco `doctor.platform` de `.claude/schemas/extensions.json`.
Decisão: `@.claude/decisions/0175-platform-upgrade-below-the-floor.md`.

## O que faz

O `export` recusa um projeto cujo build declara uma linha do Spring Boot abaixo de
`doctor.platform.min_line` (`@.claude/decisions/0161-spring-boot-3-5-line-for-adopted-projects.md`),
e o `/arch-adopt`, que escreve por ele, recusa junto. Até esta skill, nada levava um projeto dessa
linha até o piso: a recusa dizia o problema e parava.

O `/platform-upgrade` é o caminho para cima. Rodado de dentro do projeto, leva o build da linha que
ele declara até a que o Initializr serve hoje, e deixa o build verde e o diff **sem commit**:

| Muda | Por onde |
|---|---|
| A versão do Spring Boot no build | passo 1 da receita |
| Starters e artefatos do Testcontainers renomeados | passo 1, de `renames.dependencies` |
| Tipos renomeados — test slices, `@MockBean` e `@SpyBean`, customizers | passo 1, de `renames.types` |
| Propriedades de configuração renomeadas | passo 2, dos metadados dos próprios jars |
| O que nenhum rename cobre — um test starter, código Jackson 3, uma API que mudou de forma | o resíduo guiado, pelo modelo, um erro de compilação por vez |

Termina imprimindo `/arch-adopt`, para a pessoa digitar depois de revisar o diff.

Só manual (`disable-model-invocation: true`): reescreve o build e o código de uma pessoa, então roda
quando ela pede pelo nome. Viaja para o projeto gerado (`export.skills.include`), porque um aumento
de piso futuro manda um projeto adotado de volta para cá, e o plugin que entregou a skill pode não
estar mais lá. O plugin a entrega junto com o `arch-adopt`, como `/nerviz:platform-upgrade`.

## Dois motores, nesta ordem

```mermaid
flowchart LR
    U[usuário] -->|/platform-upgrade| SK["skill: platform-upgrade<br/>(decide)"]
    SK -->|1 · git status| G[worktree limpa?]
    SK -->|2 · curl + tar| SRC["$SRC<br/>cópia buscada"]
    SK -->|3 · projeto descartável| INI["Initializr<br/>versão alvo"]
    SK -->|4 · metadados| ENG["versão do motor<br/>Maven · Gradle"]
    SK -->|5 · invoca| MODE["ArchHook.jar upgrade-recipe<br/>(escreve a receita)"]
    MODE -->|lê| DATA["extensions.json<br/>doctor.platform.upgrade"]
    MODE -->|lê| JARS["META-INF/<br/>spring-configuration-metadata.json dos jars"]
    MODE --> REC["bump.yml · properties.yml"]
    REC -->|rodada pelo| OR["núcleo do OpenRewrite<br/>pelo wrapper do projeto"]
    OR --> DIFF["build e src/** do projeto"]
    SK -->|8 · edita| DIFF
    SK -->|9 · verifica| VER["./mvnw verify · ./gradlew build"]

    classDef skill fill:#123a5c,stroke:#5aa9e6,color:#fff,stroke-width:2px
    classDef hook fill:#5c1a1a,stroke:#ff6b6b,color:#fff,stroke-width:2px
    classDef data fill:#5c4a1a,stroke:#e6b45a,color:#fff,stroke-width:2px
    class SK skill
    class MODE hook
    class DATA,JARS data
```

**O que é mecânico é uma receita.** O `ArchHook.jar upgrade-recipe` escreve uma receita do
OpenRewrite a partir de dados, e a mesma entrada dá a mesma receita — por isso é um modo do hook
(Forma 7c) e não prosa que um modelo refaz a cada execução. O núcleo do OpenRewrite a roda pelo
wrapper Maven ou Gradle do próprio projeto. A skill nunca presume um Maven ou um Gradle no `PATH`, e
nunca adiciona um wrapper.

**O que não é mecânico é o resíduo guiado.** O modelo lê os primeiros erros de compilação, corrige a
causa contra as normas e o guia de migração do fornecedor, e compila de novo. Duas regras valem sem
exceção: um teste que falha é reportado, nunca apagado, pulado ou afrouxado; e o mesmo erro três
vezes seguidas para o laço e pergunta.

### As duas passadas

A receita é escrita em duas passadas porque a segunda precisa de um classpath que só existe depois
da primeira.

| Passada | Comando | Escreve | Por que aqui |
|---|---|---|---|
| `bump` | `upgrade-recipe . --pass bump --target <x.y.z> --out <arquivo>` | o bump de versão, um rename de coordenada por entrada de `renames.dependencies`, um rename de tipo por entrada de `renames.types` | A receita roda contra o classpath que o projeto **ainda tem**. Um tipo antigo deixa de resolver quando o BOM novo entra, então os renames de tipo ficam aqui, antes dele |
| `properties` | `upgrade-recipe . --pass properties --classpath <arquivo> --out <arquivo>` | um rename por propriedade que um jar declara como `deprecation.level == "error"` com `replacement` | Os jars novos carregam os próprios renames como dado, então nenhuma tabela de propriedades é mantida à mão. Roda **sem compilar**: depois da passada 1 os testes não compilam até o starter deles entrar |

O `upgrade-recipe` sai com 2 e não escreve nada para um alvo malformado, um alvo abaixo do piso, um
projeto que já está no piso (passada 1), um bloco `recipes` ausente, ou uma entrada de dado que ele
não sabe escrever. Sai com 0 e não escreve nada quando nenhum jar declara rename de propriedade; a
skill então pula a execução. `CLAUDE_PROJECT_DIR` nomeia a fonte que guarda os dados, já que um
projeto abaixo do piso não tem dados próprios.

## O que a skill faz, passo a passo

| # | Passo | O que garante |
|---|---|---|
| 1 | Recusar worktree suja; achar a ferramenta de build e o wrapper | `git diff` é como a pessoa revisa o upgrade e `git checkout -- .` como desfaz. Sem wrapper → para |
| 2 | Buscar a fonte em `$SRC`, como o `arch-adopt` § 2-3; `grep` do modo | Um projeto abaixo do piso não tem um `ArchHook.jar` desta forma. Um ref anterior à skill não tem o modo, e um modo desconhecido sai com 0 — pareceria sucesso |
| 3 | Resolver o alvo a partir de um projeto descartável que o Initializr gera | Invariante 8: versão nunca vem da memória. Sem rede → para e pergunta |
| 4 | Resolver a versão do motor no repositório que a serve | Veja a armadilha abaixo |
| 5 | Passada 1 | O bump, coordenadas e tipos. `git diff --stat` precisa mostrar o arquivo de build alterado |
| 6 | Listar o classpath novo | Maven `dependency:build-classpath`; Gradle um init script de `templates/` |
| 7 | Passada 2, sem compilar | Só o que um jar declara como erro com replacement |
| 8 | O resíduo guiado | Compila, lê, corrige a causa, compila de novo |
| 9 | Verificar | `./mvnw -B verify` ou `./gradlew build`; verde, ou os testes que falham são o relatório |
| 10 | Entregar o diff | `git status --short`, `git diff --stat`, sem commit, imprime `/arch-adopt` |

Tudo que o procedimento baixa ou escreve fora do projeto vai em um diretório só, o `$WORK`.

### Onde ficam os dados

Os renames são dados, de dono único: `doctor.platform.upgrade` em
`@.claude/schemas/extensions.json` (invariantes 2 e 10). Um rename que falta no upgrade de um
projeto é uma entrada que falta ali, nunca uma segunda lista em outro lugar.

| Chave | Guarda |
|---|---|
| `recipes` | os nomes das receitas e no que cada tipo de entrada se transforma — modelos `{type, options}` com placeholders `${...}`. O motor é dado |
| `renames.dependencies` | renames de starters e artefatos: `starter-web` → `starter-webmvc`, `starter-aop` → `starter-aspectj`, os artefatos `org.testcontainers:*` |
| `renames.types` | renames de tipos: os test slices e suas anotações de auto-configure, `@MockBean` → `@MockitoBean`, `@SpyBean` → `@MockitoSpyBean`, customizers, as classes dos módulos do Testcontainers |
| `test_starters` | pacote → test starter de escopo test. Um erro de compilação que nomeia o pacote de um slice se cura com o starter para o qual a chave aponta |
| `engine` | onde as versões do motor são listadas, o endpoint do Initializr, o guia de migração, e quantas versões do plugin Gradle tentar |

Todo `to` de `renames` é um nome que um exemplar que este repositório compila no CI já usa, ou um
artefato publicado. Todo `from` foi achado no jar da linha que se deixa. Um `from` errado não
renomeia nada; um `to` errado quebra um build, então só o segundo é conferido contra um artefato
publicado.

### A linha pública do motor, e uma armadilha que ela armou

O motor é o núcleo do OpenRewrite, Apache 2.0, rodado pelo wrapper do projeto. O módulo
`rewrite-spring` nunca é usado: ele guarda as receitas prontas de Spring Boot, mas sob uma licença
source-available que proíbe um produto cujo valor derive dele. As entradas aqui foram escritas a
partir do guia de migração do Spring e dos metadados que os próprios jars do Spring publicam.

O Maven Central serve o BOM do núcleo só até uma versão que o plugin Gradle mais novo já passou, então
o plugin mais novo falha com `Could not find org.openrewrite:rewrite-bom`. O Maven não tem essa
lacuna: o plugin dele vive no Central e o `<release>` é coerente com ele. Por isso, para Gradle, a
skill pega o plugin mais novo cujo BOM o Maven Central responde 200, entre as últimas
`engine.gradle_candidates` entradas. Nenhum → para, e `engine` é o bloco a editar quando a linha
pública se mover.

## O que não faz

- **Commit.** O diff é da pessoa para revisar.
- **Escrever `.claude/`.** Isso é do `/arch-adopt`, para o qual a skill passa a vez.
- **Renomear código Jackson 3.** Uma anotação ficou, um pacote se moveu e uma exceção virou
  não verificada: uma decisão por ponto, não um rename. Fica no resíduo guiado, contra o bullet do
  Jackson de `@.claude/rules/architecture-ddd.md`.
- **Alcançar um rename que nenhum exemplar nomeia.** `renames` guarda o que os exemplares deste
  repositório usam; um pacote movido que nenhum deles toca é do resíduo.
- **Contornar um build que esconde a versão.** Uma versão do Spring Boot em um POM pai que não é do
  projeto, ou em um version catalog, a receita não lê: a skill nomeia o arquivo e pede à pessoa que
  mude essa linha.
- **Rodar em um projeto que já está no piso ou acima.** Diz isso e para.

## Território de escrita

Classe `build`, com override em `skill_classes` de `extensions.json`: o arquivo de build da raiz, os
arquivos do wrapper e `src/**`, na raiz do projeto e em qualquer módulo. O `ArchHook.java guard`
impõe. `.claude/**` fica fora.

## O que o CI prova

| Job | O que prova |
|---|---|
| `validate › hooks-cross-platform › upgrade-recipe writes the bump and the property renames, and refuses what it cannot spell` | `UpgradeRecipeTest`: o bump carrega o alvo e um passo por rename do dado de produção; cada recusa sai com 2 sem escrever nada; a passada de propriedades sobre jars de teste pula um warning, uma entrada sem replacement e um jar sem metadados |
| `templates › platform-upgrade` | `PlatformUpgradeTest`, com rede: em Maven e em Gradle, um fixture na GA mais nova abaixo do piso passa pelo procedimento da skill e builda verde na linha do Initializr |
| `validate › design › schema` | a classe da skill, seções, território e entrada em `export` |
| `validate › export-determinism` | a skill e o modo viajam iguais em todo SO |

Não provado, de propósito: uma execução agendada do job do motor, então uma deriva da linha pública
do motor aparece no próximo PR que toca os caminhos dele; builds Gradle com Kotlin DSL e
multi-módulo; e com que frequência falta um rename no dado. A seção *CI coverage* do registro lista
tudo isso.

## Como rodar

```text
/platform-upgrade [--ref <tag|sha|main>] [--source <local path>]
```

Uma worktree limpa, um JDK que o build aceita, um wrapper na raiz do projeto e acesso de rede ao
Initializr e aos repositórios do motor. Termina com o relatório:

```text
✅ upgraded — <from line> → <target>, <Maven | Gradle>

Source ....... <host>, ref <ref>
Engine ....... <engine artifact> <version>
Pass 1 ....... <n> files changed (<n> sources, <n> build)
Pass 2 ....... <n> property renames applied | none declared by the jars
Residue ...... <n> edits by hand — <one line each>
Verify ....... <green | the failing tests, verbatim first line>

Review: git diff · Undo: git checkout -- .
Next: /arch-adopt
```

Depois, o `/arch-adopt` instala este `.claude/` no projeto já atualizado; veja
[10-arch-adopt.md](10-arch-adopt.md).
