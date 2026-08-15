# Convenção de plano e ledger

**Regra de funcionamento da casa, fixada pelo dono em 2026-07-27.** Vale para todo plano e todo
ledger produzido por qualquer sessão deste projeto.

---

## O defeito que ela existe para impedir

Não é "falta detalhe". O detalhe existia e era excelente — o fragmento `adr-0009.md` do plano de
descoberta tem 78 linhas com cada afirmação, evidência, produtor real e cruzamento. O defeito era a
**topologia**:

| | O que acontecia | Consequência medida |
|---|---|---|
| 1 | O detalhe morava num *staging* que o contrato mandava **apagar** | O próprio plano avisou: apagar deixa os IDs "pendurados no vazio" |
| 2 | O plano apontava para o detalhe por **convenção de ID** (`0009-A14`), não por link | Ninguém navegava; todo mundo fazia `grep` |
| 3 | O ledger era **prosa anexada inline**, e parágrafo velho nunca saía | Uma sessão inteira em 2026-07-27 re-derivou uma task fechada há 10 dias |
| 4 | 612 linhas misturando método + inventário + ledger + baterias + decisões | Ninguém lia inteiro; todo mundo lia o pedaço errado |

O item 3 é o mais caro e o mais instrutivo: **o plano tinha um rastro registrado avisando que a prosa
estava velha** (R31, 2026-07-19) — e a armadilha pegou mesmo assim. Por isso a separação virou
**estrutural**, não disciplinar.

---

## A regra

### 1. O plano é índice, e é raso por contrato

- O plano é **um `.md`** — `.md` porque este projeto *grepa* seus documentos o tempo todo, e porque
  ele precisa aparecer em `git diff` de forma legível.
- **Cada ponto ocupa exatamente uma linha de tabela**, com as colunas:
  `# | Ponto | Tipo | Estado | O que decide | Detalhe`.
- A coluna **Detalhe é obrigatória** e aponta para a página do ponto.
- **Limite duro: 60 linhas.** Se não cabe, é porque conteúdo que pertence ao detalhe vazou para o
  índice.

### 2. Cada ponto tem uma página de detalhe `.html` adjacente

- Local: `<nome-do-plano>.detalhe/<ID>-<slug>.html`, irmão do plano.
- `.html` porque o detalhe é para **ler**: tabelas comparativas, blocos de evidência, hierarquia
  visual. Markdown cru não sustenta isso, e foi por isso que o detalhe anterior não era lido.
- Sem dependência externa. Abre por `file://`. Folha compartilhada em `_estilo.css`.
- **Sem limite de tamanho.** O detalhe é onde a profundidade mora — é o ponto inteiro da convenção.

**Seções mínimas de uma página de detalhe:**

| Seção | Por quê |
|---|---|
| O que é / o defeito | O leitor tem de entender sem abrir o código |
| **Evidência medida**, com âncora `arquivo:linha` e **data da medição** | A casa não aceita alegação sem efeito observado |
| Por que este ponto vem aqui | O racional da ordem é o que impede a próxima sessão de reabrir |
| Critério de aceite / o que fecha | Senão "pronto" vira opinião |
| **O que este ponto NÃO fecha** | O limite nomeado no alcançável, não no confortável |
| Perguntas abertas | O que ainda não foi decidido, dito como não decidido |
| Ligações | Os outros pontos que este toca |

### 3. O ledger sai de dentro do plano

- Local: `<nome-do-plano>.detalhe/ledger.html`.
- **O plano carrega só uma linha de estado + o link.** Nunca uma tabela de totais, nunca um
  parágrafo de atualização.
- O ledger tem **duas zonas com regras opostas**:
  - **"Estado atual"** — **sobrescrita**, nunca acrescida. É a única fonte de verdade do estado.
  - **"Histórico"** — **acrescida** no topo, nunca editada. Diz *o que mudou*, não repete o estado.
- **Toda medição carrega a data em que foi medida.** Número sem data é número sem valor.
- Número no "Estado atual" que **não** foi re-medido na rodada tem de estar marcado como tal.

### 4. Regras de citação (as que custaram sessão)

- **Nunca cite a prosa de um documento com ledger acumulado — cite a última tabela de totais.**
- Antes de declarar "próxima task", cruze com a memória: um plano que propõe uma task **sem citar
  qual faixa a executou** está propondo trabalho já feito.
- Âncora de código citada em página de detalhe **carrega a data da medição**. Este repo sofre drift
  de linha a cada Frente; âncora sem data é âncora sem valor.

---

## Conformidade

```bash
scripts/checar-plano.sh docs/planos/rumo-ao-uso-real.md
```

O verificador falha se:

1. algum ponto da tabela **não tem link de detalhe**;
2. algum link de detalhe **aponta para arquivo inexistente**;
3. o índice **passa do limite de linhas**;
4. existe página de detalhe **órfã** (não linkada pelo índice);
5. o índice contém **ledger inline** — bloco de atualização datada ou segunda tabela de totais;
6. **não existe** `ledger.html` para o plano;
7. alguma página **não declara `<meta charset="utf-8">`** nas 5 primeiras linhas;
8. **falta `_estilo.css`** no diretório de detalhe.

Rodar sem argumento verifica **todos** os planos em `docs/planos/`.

> **As checagens 7 e 8 nasceram de um defeito real, não de precaução.** Na primeira renderização
> destas páginas, "não" apareceu como "nÃ£o": faltava `<meta charset>`, e o servidor não mandava
> charset no header. `file://` costuma adivinhar UTF-8 e esconder o problema — só apareceu ao servir
> por HTTP de verdade. Encodar o defeito no verificador é mais barato que redescobri-lo.

**As 8 checagens foram provadas por MUTAÇÃO** — cada uma quebrada de propósito numa cópia, para
confirmar que reprova. Controle que nasce verde não discrimina nada.

> **Por que um verificador e não só disciplina:** o `docs/roadmap-interno.md` está parado desde
> 2026-07-17 e não registra nem os achados #18–#21 nem os PRs #40–#61. Ele apodreceu **exatamente**
> porque nada o checava. Convenção sem dente vira arqueologia.

---

## Migração dos documentos que já existem

| Documento | Estado | Resultado |
|---|---|---|
| `docs/planos/rumo-ao-uso-real.md` | ✅ nasceu na convenção | 8 pontos + ledger |
| `docs/planos/descoberta-de-bugs.md` | ✅ **migrado 2026-07-27** | 6 pontos + ledger. Era 612 linhas com ledger inline — o documento que causou o incidente |
| `docs/planos/roadmap.md` | ✅ **migrado 2026-07-27** | 8 pontos + ledger. Era 553 linhas; **reconciliado**: o roadmap de ajustes foi de 10 para **21 itens** e os PRs #34–#61 entraram |
| `HANDOFF-*-KICKOFF.md` | 🟠 fora de escopo por desenho | Deixar como estão — são **contrato de sessão única**, não documento vivo |

**Os caminhos antigos viraram ponteiros**, não foram apagados: `docs/roadmap-interno.md` é **tracked
e referenciado por 9 documentos** (incluindo a ADR-0019), e `docs/plano-descoberta-bugs.md` é citado
por outros dois. Cada ponteiro traz a tabela de "onde cada seção foi parar".

**O contrato untracked foi preservado:** `docs/planos/descoberta-de-bugs.md` e seu `.detalhe/` estão
no `.gitignore`, como o original — conferido por `git check-ignore`.

⚠️ **`docs/plano-descoberta-bugs.materiais/` NÃO migra e NÃO se apaga.** É onde as 1085 afirmações
realmente moram; toda citação por ID (`0037-A46`) dos dois planos só resolve lá dentro.
