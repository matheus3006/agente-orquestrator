# Pesquisa — Como melhorar o uso da memória de agentes de IA

> **Data:** 2026-07-04 · **Método:** deep-research (fan-out 6 ângulos → 29 fontes → 139 claims → verificação adversarial 3 votos → 21 confirmados / 4 refutados)
> **Contexto do pedido:** CEO orquestrador (Claude Opus) cuja memória autoritativa = registro estruturado aprovado pelo humano, materializado no system prompt (estilo `CLAUDE.md`), sem busca vetorial. Queremos evoluí-la para um "segundo cérebro" que cresce e melhora a qualidade das decisões, **sem se auto-envenenar**.

---

## TL;DR (a tese central)

A literatura 2024–2026 **valida fortemente a arquitetura atual do CEO** (memória textual/interpretável + write-gating humano + proveniência) e aponta upgrades concretos:

1. **O difícil não é armazenar — é a consolidação episódico→semântico e a qualidade do retrieval.** Essa é a peça mais mal-servida ("particularly underserved") de todos os sistemas em produção.
2. **Contexto longo NÃO é memória.** Sistemas de memória dedicados batem janelas de 200k tokens em tarefas de recuperação seletiva. Benchmark que "cabe na janela" não testa memória de verdade.
3. **Auto-envenenamento é risco de deploy comprovado empiricamente.** Resumir repetidamente causa drift semântico/procedural; memória RAG é atacável na camada de dados (AgentPoison, NeurIPS 2024); recall obsoleto **pode ser pior que nenhum**. Por isso o consenso prescreve um **Write Validation Gate** (proveniência + checagem de contradição) antes de qualquer consolidação — que é **exatamente o que o gate de aprovação humana do dono já faz**.
4. **Escolha o substrato de retrieval por tipo de pergunta**, não por default de vetor. Grafo temporal vence em "o que causou o quê / quando".

---

## Achados confirmados (ranqueados por confiança)

### 1. Taxonomia canônica em camadas — e cada camada tem seu próprio caminho de leitura/escrita `[alta]`
Working/short-term, episódico, semântico, procedural (CoALA, arXiv:2309.02427, TMLR 2024, ~280 citações). O survey de Zhang (arXiv:2404.13501, ACM TOIS 2025) organiza por **fonte de informação**: *inside-trial* (≈working, dentro de uma run), *cross-trial* (≈episódico/experiencial, experiência acumulada incluindo erros passados) e *external knowledge*. "A procedure diz *como*, a memória semântica diz *qual é a política*, a episódica diz *o que aconteceu*, e a working segura o *raciocínio vivo*." **Cada camada opera em timescale diferente e deve ser escrita/lida por um caminho próprio — não como um store indiferenciado.**
- Fontes: arXiv:2309.02427v3, arXiv:2404.13501, arXiv:2603.07670.

### 2. A consolidação episódico→semântico é a peça mais mal-servida `[alta]` ⭐
"A integração de 4 camadas é a aspiração; a maioria dos sistemas implementa só **duas** camadas bem e trata as transições via heurísticas grosseiras. **A consolidação — onde episódios viram conhecimento semântico — é particularmente mal-servida:** requer ou regras explícitas de dev ou sumarização periódica por LLM, ambas **frágeis e difíceis de validar**." (verbatim, arXiv:2603.07670, Du, Mar 2026, §3.1).
- **Por que importa pra nós:** é o degrau que o #1 pula hoje (log → carta direto). Este é o achado que mais desafia o desenho atual.

### 3. Frameworks líderes divergem no *write path* e no substrato — não na taxonomia `[alta]`
- **MemGPT/Letta:** "virtual context management" inspirado em SO — o LLM auto-gerencia a memória via function calls/interrupts, paginando entre um tier em-contexto e storage externo (analogia RAM/disco). Edição e busca **autodirigidas** pelo próprio LLM.
- **Zep/Graphiti:** motor de **knowledge graph temporalmente-consciente** que sintetiza conversa não-estruturada + dados estruturados de negócio, preservando relações históricas ao longo do tempo.
- **LangGraph:** duas primitivas — *checkpointers* (curto prazo, escopo de thread) e *store* (longo prazo, cross-thread).
- (Mem0, LangMem, Cognee, LlamaIndex foram nomeados mas nenhum claim específico sobreviveu à verificação — ver perguntas em aberto.)
- Fontes: arXiv:2310.08560 (MemGPT), arXiv:2501.13956 (Zep), docs.langchain (LangGraph).

### 4. Escolha o substrato de retrieval por tipo de pergunta, não por default de vetor `[alta]`
Vetor escala a milhões mas só responde "o que é mais similar?" — perde relações estruturais ("o que causou o quê?"). Stores estruturados (SQL, KV, KG incluindo **KG temporal**) preservam relações e suportam queries temporais complexas ("todas as falhas de API do serviço X nos últimos 7 dias") ao custo de design de schema. No LongMemEval, o KG temporal do Zep entregou **+18.5% de acurácia relativa** cortando **~90% de latência** vs. baseline de contexto-cheio.
- Ressalva: números do Zep são de 1ª parte e relativos, contra um baseline fraco (contexto-cheio). Substrato+CoALA 3-0; números do Zep 2-1.
- Fontes: arXiv:2603.07670, arXiv:2309.02427v3, arXiv:2501.13956.

### 5. Contexto longo NÃO é memória `[alta]`
Agregando 4 benchmarks recentes (LoCoMo, MemBench, MemoryAgentBench, MemoryArena), modelos long-context até 200k tokens **consistentemente perdem** para sistemas de memória dedicados em tarefas de recuperação seletiva; o gargalo **não é mais storage — é qualidade de retrieval**. Muitos benchmarks sofrem de "context saturation" (tudo relevante cabe numa janela), o que os torna não-informativos: um benchmark só testa memória agêntica de verdade quando o gap Δ (score-com-memória − score-contexto-cheio) ≫ 0. LoCoMo (~20k) = risco moderado; MemBench (~100k) = alto (cabe em 128k); só **LongMemEval-M (>1M tokens)** realmente exige memória externa.
- Fontes: arXiv:2603.07670 §5.5, arXiv:2602.19320 ("Anatomy of Agentic Memory", Fev 2026).

### 6. Auto-envenenamento é risco concreto, com 3 interfaces de falha que compõem `[alta]` ⭐
Erros de memória são **cumulativos e persistentes**, formando um loop de falha em 3 interfaces: **ingestão** (poisoning), **consolidação** (drift), **retrieval** (hallucination). Resumir repetidamente distorce fatos (drift semântico) e reforça workflows subótimos (drift procedural). Memória RAG é **diretamente atacável na camada de dados sem treino** — AgentPoison (NeurIPS 2024) injeta demonstrações maliciosas na memória de longo prazo que são recuperadas por um trigger-backdoor otimizado. Em deploys longos, **a incapacidade de descartar info obsoleta envenena gradualmente a precisão do retrieval — recall obsoleto/alucinado pode ser pior que nenhum recall.**
- Corroboração peer-reviewed do drift: "LLM as a Broken Telephone" (ACL 2025, arXiv:2502.20258).
- Fontes: arXiv:2603.07670, AgentPoison (arXiv:2407.12784). *(Reconciliação 2026-07-04: SSGM arXiv:2603.11768 rebaixado a intuição-motivadora — refutado como prova na pesquisa-irmã, segundo-cerebro §REFUTADO.)*

### 7. O consenso prescreve exatamente a defesa que já usamos — e como endurecê-la `[alta]` ⭐
"Updates de memória **nunca** devem ser commitados passivamente — todo write deve passar por um **Write Validation Gate** antes da consolidação", agindo como um *Truth Maintenance System* que checa **proveniência** e roda uma **checagem lógica de contradição** contra os fatos-core estabelecidos. Memória pode ser **textual** (interpretável, fácil, leitura/escrita rápida — o mainstream, e é o que "memory blocks" no system prompt são) ou **paramétrica** (nos pesos, via fine-tuning/editing); **textual é o substrato mais seguro e auditável** para um registro aprovado por humano.
- Corroboração 2025–2026: SMSR (arXiv:2606.12703, "trata todo write como transição de estado privilegiada, verificando proveniência e consistência antes de commitar"); TMA-NM (arXiv:2606.24322, 0% de sucesso de ataque).
- **Nota:** o "Write Validation Gate" é o framing específico do SSGM (**refutado como prova** na pesquisa-irmã, segundo-cerebro §REFUTADO — a sustentação empírica é a corroboração SMSR/TMA-NM acima), e muitos sistemas em produção (Mem0 add-only, Letta self-edit) shippam com gating **fraco** — é um "should" da pesquisa que **o nosso human-in-the-loop já satisfaz**.
- Fontes: SMSR arXiv:2606.12703, TMA-NM arXiv:2606.24322, arXiv:2404.13501; SSGM arXiv:2603.11768 só como origem do framing (refutado como prova).

### 8. Roadmap para "segundo cérebro": adicione as camadas de Reflexão e Experiência `[alta]` ⭐
Duas taxonomias evolutivas dão o roteiro concreto:
- **Evolutiva** (arXiv:2605.06716, "From Storage to Experience", ACL Findings 2026): **Storage** (preservação fiel de trajetórias) → **Reflection** (loops de avaliação dinâmica que transformam a memória de *gravador* em *avaliador*, mitigando alucinação/erro lógico) → **Experience** (abstração cross-trajetória em estratégias heurísticas transferíveis e reutilizáveis).
- **Estrutural** (arXiv:2602.19320): Semântica Leve (vetor top-k) / Entity-Centric-Personalizada (registros atributo-valor estruturados) / Episódica & Reflexiva (consolidação periódica) / Estruturada-Hierárquica (grafos / multi-tier).
- **A conclusão-chave:** qualidade de decisão superior vem de **adicionar as camadas de Reflexão e Experiência** sobre o storage bruto — **não** de um índice vetorial maior.
- Fontes: arXiv:2605.06716, arXiv:2602.19320.

---

## O que foi REFUTADO na verificação (não usar como base)

- **Framework de 4 operações de consolidação** (reflection/summarization/merging/forgetting) — 1-2. A intuição geral existe, mas *esse* enquadramento nomeado não passou.
- **"Grafo tem custo de write severo; modelos fracos geram 30.38% de erro de formato"** — 0-3.
- **"Zep bate MemGPT no DMR (94.8% vs 93.4%)"** — 1-2 (número de 1ª parte).
- **Números específicos do AgentPoison (ASR≥80%, poison<0.1%)** — 0-3. O *fenômeno* AgentPoison é confirmado; os números exatos não.

---

## Perguntas em aberto (o que a pesquisa NÃO fechou)

1. **Arquitetura concreta de Mem0/LangMem/Cognee/LlamaIndex** e como o write-gating/consolidação deles se compara a MemGPT/Zep/LangGraph (nenhum claim sobreviveu esta passada).
2. **Split empírico ótimo** entre o que fica sempre-presente no "memory block" do system prompt vs. um store episódico/experiência recuperado à parte — onde fica a fronteira recency+salience que maximiza decisão sem inchar a janela?
3. **Como é um Write Validation Gate de produção** para um CEO orquestrador (quais checagens de contradição, quais metadados de proveniência, humano vs. automático) — e há evidência medida de que o gate reduz erro de decisão downstream, não só bloqueia writes?
4. **Cadência e método de consolidação** (reflexão-LLM vs. regras determinísticas vs. arestas de grafo com intervalos de validade) que melhor converte episódios em conhecimento durável **sem acumular drift** ao longo de meses.

---

## Caveats de qualidade das fontes

- Os achados mais decisivos e recentes (consolidação, auto-envenenamento, write-gating) vêm de **preprints arXiv 2026** (2603.07670, 2603.11768, 2602.19320, 2606.12703), vários single-author / não peer-reviewed. **Corroborados onde mais importa** por peer-reviewed: CoALA (TMLR 2024), survey Zhang (TOIS 2025), Broken Telephone (ACL 2025), AgentPoison (NeurIPS 2024), From Storage to Experience (ACL Findings 2026). Trate taxonomias/limiares dos preprints frescos como **framing forte-mas-provisório**.
- Números de benchmark são sensíveis ao tempo e parcialmente de 1ª parte (Zep +18.5%/−90% auto-reportados, relativos, contra baseline fraco). A comparação "200k tokens" cita Chen 2023 enquanto janelas atuais são 1M+.
- "Consolidação é mal-servida" é avaliação **descritiva**, não uma ablação provando que um fix específico vence.

---

## Fontes primárias (as que sustentam os achados de alta confiança)

- **arXiv:2309.02427 (CoALA, TMLR 2024)** — taxonomia canônica de 4 camadas; retrieval e writing como ações distintas.
- **arXiv:2404.13501 (Zhang et al., ACM TOIS 2025)** — memória por fonte (inside/cross-trial/external); textual vs. paramétrica.
- **arXiv:2603.07670 (Du, "Memory for Autonomous LLM Agents", Mar 2026)** — consolidação mal-servida; contexto-longo-não-é-memória; substrato por tipo de query.
- **arXiv:2603.11768 (SSGM, Mar 2026)** — Write Validation Gate; 3 interfaces de falha; drift semântico/procedural.
- **arXiv:2602.19320 ("Anatomy of Agentic Memory", Fev 2026)** — context saturation Δ; taxonomia estrutural de 4 categorias.
- **arXiv:2605.06716 ("From Storage to Experience", ACL Findings 2026)** — Storage→Reflection→Experience.
- **arXiv:2501.13956 (Zep/Graphiti)** — KG temporal; números LongMemEval.
- **arXiv:2310.08560 (MemGPT/Letta)** — virtual context management, self-editing.
- **AgentPoison (arXiv:2407.12784, NeurIPS 2024)** — ataque de backdoor via memória de longo prazo.

_Stats: 6 ângulos · 29 fontes · 139 claims · 25 verificados · 21 confirmados · 4 refutados · 112 agentes._
