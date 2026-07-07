# ADR-0022: Escritor de workspace — a árvore materializada atravessa pelo mesmo seam (F.9e)

**Status:** Aceito (2026-07-05)

## Contexto
Os materializers escrevem no workDir do **host** (`SkillMaterializer` → `.claude/skills/`, Fatia 6;
`CompanyMemoryMaterializer` → `.claude/CLAUDE.md`, ADR-0018). O ADR-0016 fez o workspace DOCKER virar
**volume nomeado — invisível ao filesystem do backend**: o container nunca via o que foi materializado.
**Provado no smoke F.9d:** skill `criar-prototipo` anexada a agente claude-code+DOCKER não apareceu em
`/workspace/.claude/skills` — o agente rodava **sem as skills, em silêncio**. A F.9 (ADR-0021 §6) criou
o **leitor** de volume no seam do `ExecutionTarget`; faltava o escritor equivalente.

## Decisão

### 1. Mesmo seam: `ExecutionTarget.writeWorkspaceFiles(Spec, List<relativePath>)`
O eixo de variação da escrita ("diretório real" × "volume nomeado") é o mesmo do run e da leitura.
**LOCAL = identidade** (o workDir É o workspace; os materializers já escreveram no lugar);
**DOCKER = travessia**. Os `relativePath` são **constantes do pipeline** (contratos de lugar), nunca
input do agente.

### 2. Replace por subtree — o estado do host VENCE
Presente no workDir **substitui** o subtree do workspace; ausente no workDir **remove**. É assim que a
idempotência dos materializers atravessa o seam: skill removida do conjunto some do volume, carta
consolidada removida some do `CLAUDE.md` — como já somem do workDir no LOCAL.

### 3. DOCKER: 1 container efêmero hermético por run, alimentado por tar do host
`docker run --rm -i --network none -v <vol>:/workspace <imagem-do-adapter>
sh -c 'rm -rf /workspace/<contratos…> && tar -xf - -C /workspace'`, stdin = `tar -cf - -C <workDir>`
(com `COPYFILE_DISABLE=1` — sem ele o bsdtar do macOS empacota metadados AppleDouble `._*` para dentro
do workspace do agente; no GNU tar do Linux o env é inócuo). Hermético por construção: **sem rede, sem
NENHUM volume de credencial**, imagem do próprio adapter (garantidamente presente). `rm` e extract no
**mesmo sh**: extract falho depois do rm vira `exit != 0` → falha legível, nunca workspace pela metade.
Argv **puro e testável sem daemon** (`buildWriteArgs`/`buildTarArgs`/`hostTar`), como o run e o leitor.
Sem árvore materializada no host, o container é **só o rm** (sem `-i`, sem tar).

### 4. A lista espelha QUEM materializou
`.claude/skills` em **todo** run; `.claude/CLAUDE.md` **só em run de turno** (quando o
`CompanyMemoryMaterializer` rodou). Um `CLAUDE.md` que o agente escreveu no próprio workspace de
Issue **nunca é tocado** — o escritor não pode destruir no DOCKER estado que o LOCAL preservaria.

### 5. Falha do escritor é falha do RUN — assimetria consciente com o leitor
O leitor do preview é best-effort (não capturar = observação perdida; o settle segue). O escritor
**não**: rodar sem a árvore é exatamente a divergência silenciosa do F.9d. Falha vira
`RunOutcome.failure` legível (Issue → BLOCKED pelo settle normal; turno → FAILED, como falha da carta).

## Alternativas rejeitadas
- **`docker cp` em container criado** — 3-4 invocações do docker por run, merge em vez de replace, sem
  atomicidade rm+extract.
- **Materializers cientes do target (escrever direto no volume)** — dois eixos de variação no mesmo
  módulo; o seam que abstrai o eixo já existe.
- **Seam paralelo (`WorkspaceWriter`)** — mesmo argumento do ADR-0021: um eixo, um seam.
- **Sincronizar `.claude/` inteiro** — nukaria estado que o agente escreve legitimamente
  (settings, `CLAUDE.md` de Issue); a lista espelha os materializers.
- **tar em Java (sem binário do host)** — reimplementar ustar/PAX (nomes longos) para eliminar uma
  dependência que o backend já assume no host (como o próprio CLI `docker`).

## Ressalvas honestas
- **+1 container efêmero por run DOCKER** (custo por-run; o LOCAL não paga nada).
- **Ownership**: a extração roda como o USER da imagem do adapter (`node` nas `orq-*`; `/workspace`
  nasce `chown node` — gotcha F.8). Volume é por (company, agent, issue) e imagem por adapter — não há
  mistura de owners no mesmo volume.
- **busybox tar** (alpine, arquétipo process) lê o stream do GNU/bsdtar para os nomes do contrato;
  chaves de skill passam pelo sanitize do materializer antes de virar path.

## Relaciona
ADR-0016 (o volume nomeado criou o problema) · ADR-0021 §6 (o leitor — padrão espelhado; lá
best-effort, aqui falha do run) · ADR-0007 (layout `.claude/skills`) · ADR-0018/0019 (a carta) ·
ADR-0010 (argv puro testável; materializers seguem módulos profundos intocados).
