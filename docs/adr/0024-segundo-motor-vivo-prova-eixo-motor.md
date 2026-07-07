# ADR-0024: Segundo motor vivo — prova do eixo motor-agnóstico (F.11)

**Status:** Aceito (2026-07-05, sessão grilling `docs/handoff-grilling-plano-codex-fechado.md`)

## Contexto
Desde a F.8 o produto CARREGA dois motores: `CodexAdapter` completo e testado (argv `codex exec
--json`, `CodexStreamParser`, remédio de credencial, cost events `provider=openai`, pin travado
por `EngineContainerPinTest`), imagem `orq-codex:0.142.5` construída, `setup-codex.sh` pronto
(device-auth no container, credencial-em-arquivo, volume `orq_codex_creds`). Mas o login nunca
rodou (`orq_codex_creds` não existe) e **nenhum run real de codex jamais aconteceu**. Tudo o que
os ADRs 0021/0022/0023 afirmam sobre "Codex ganha de graça" é promessa, não fato: o
`CodexStreamParser` confessa no javadoc que o shape de usage/session "foi montado do formato
documentado, não de fixture do CLI real". Enquanto isso, F.9/F.9e/F.10 empilharam três fatias de
contrato de workspace (escritor de skills, `.orq/preview/index.html`, `.orq/preview/server.json`)
provadas com UM motor só. Se o seam tiver vício escondido — algo que só funciona porque o motor é
o claude-code — cada fatia nova encarece a descoberta. Esta fatia transforma a tese
"agnóstico de motor" em evidência. Fato favorável: o operador tem conta Codex e o host roda
`codex-cli 0.142.5` — exatamente o pin da imagem, zero deriva de versão.

## Decisão

### 1. A fatia é PROVA, não construção — zero código previsto, salvo bugfix de deriva
O adapter, a imagem e o setup existem desde a F.8 (ADR-0014/0016) e **não se reabrem**. O que
falta é: login do container (uma vez, interativo) + prova ponta-a-ponta. Código novo só entra se
a prova exigir — o candidato confesso é o `CodexStreamParser` contra o stream real (bugfix, não
feature). Qualquer necessidade além de bugfix (ex.: o fallback da decisão 2) é aplicada como
decidida AQUI, sem reabrir grilling no meio da execução.

### 2. Skills chegam ao codex por PROMPT — o materializer permanece cego a motor
O caminho `<workspace>/.claude/skills/<key>/SKILL.md` é **contrato do workspace** (ADR-0022), não
config de engine — o nome não muda com um segundo motor. A descoberta é o prompt neutro canônico
("Você tem skills instaladas em .claude/skills/ — use a que se aplicar."): um motor que sabe ler
arquivos lista o diretório e segue o SKILL.md. **Fallback PRÉ-decidido** se o smoke provar que o
codex ignora as skills: o `SkillMaterializer` passa a escrever também um `AGENTS.md` na raiz do
workspace apontando para as skills — **incondicional** (um arquivo a mais não machuca o claude;
materializer condicionado a motor seria vazamento de seam). O fallback só entra mediante prova.

### 3. DoD = a pilha inteira com codex (nível "c" do grilling)
1. **CLI real × parser**: run DOCKER SUCCEEDED com StepTrace legível, `thread_id` capturado,
   custo `provider=openai` registrado (tokens do stream real).
2. **Credencial seletiva provada viva**: container do run monta SÓ `orq_codex_creds`; remédio
   legível se a credencial quebrar.
3. **Skill em uso com prompt neutro** → preview **v1 (foto)** publicado pelo contrato
   `.orq/preview/index.html` → capturado no settle → **aprovado VENDO → DONE**.
4. **Preview v2 (servidor)**: skill `criar-app-servidor` → `.orq/preview/server.json` → manifest
   no settle → start → **app viva no board** via codex → aprovado VENDO → DONE. Verificações
   herdadas do ADR-0023: 4º container SEM credencial, `:ro`+tmpfs, porta só loopback, caps.

### 4. Roteiro LOCAL-first com DOIS cobaias — o login interativo vem depois da prova barata
- **Ato 1 — smoke LOCAL** (cobaia `codex+LOCAL` novo na teams-demo): o alvo LOCAL herda o login
  que o operador já tem no host (ADR-0016) — valida CLI real × parser, skills-por-prompt e
  contrato v1 sem fricção de credencial e com debug direto. Deriva do parser conserta-se aqui.
- **Ato 2 — `setup-codex.sh`** interativo (operador presente; device-auth), só após o ato 1
  passar — não se gasta o tempo interativo antes de saber que o adapter conversa com o CLI real.
- **Atos 3/4 — smokes DOCKER v1 e v2** (cobaia `codex+DOCKER` novo, o definitivo): issues risk 5,
  wakeup por API, prompt neutro.
- **Dois cobaias, não um**: agente é linha barata; trocar o target do mesmo agente por SQL é
  mutação invisível — a classe de gambiarra que os handoffs vêm condenando.
- **Wakeup por API, não conversa com CEO**: a máquina de conversa (ADR-0018) é pipeline, não
  motor; prová-la com codex não prova nada novo sobre o eixo.

## Alternativas rejeitadas
- **Renomear `.claude/skills` para caminho neutro** — reabre contrato provado da F.9 inteira
  (escritor, testes, skills vivas) por cosmética; o nome é do workspace, não do motor.
- **Ponte AGENTS.md antecipada** — código antes da prova; a regra da fatia é o contrário
  (fica como fallback pré-decidido, não como passo).
- **Um cobaia com target mutado por SQL** — mutação invisível de estado; dois agentes contam a
  história de cada alvo separadamente.
- **Provar a conversa CEO com codex** — máquina de pipeline já provada; gasto de token sem
  informação nova sobre o eixo motor.
- **Upgrade preventivo do pin 0.142.5** — host e imagem estão na MESMA versão; re-pinar sem
  sintoma reabriria F.8 sem motivo. Se o login do container falhar por deriva do fluxo de auth,
  aí é sintoma — trata-se como bugfix de infra (bump de pin + rebuild), documentado no handoff.
- **Copiar o login do host para o volume** (`docker cp auth.json`) — rejeição HERDADA do
  ADR-0002/0016 (rotação de refresh token dessincroniza as cópias); reafirmada aqui porque a
  tentação dobra quando o host já está logado.

## Recorte explícito (fora da fatia)
1. **Retomada de sessão** (`--resume`/`thread_id` de volta ao CLI) — hoje simétrica e AUSENTE nos
   dois motores (handle capturado, não retomado; ADR-0014: cada run é fresh, continuidade
   preferida é handoff/ADR-0015). Se algum dia entrar, é fatia própria dos DOIS motores.
2. **Exposição de model/effort do codex na UI do board** — o argv já traduz; a UI não muda aqui.
3. **Gestão de rate limit/quota do plano ChatGPT** — o remédio de credencial cobre o legível;
   política de quota é problema de outro dia.
4. **Codex LOCAL como alvo de produção** — segue dev-only (mesma classe de risco do run LOCAL,
   ADR-0016); o smoke LOCAL é andaime da prova, não promessa de suporte.
5. Todo o resto do menu da Fase 2 (GC/retenção, thumbnail, redação de segredos, edição no board,
   multi-arquivo v1, recortes do v2) — continuam abertos, conscientes.

## Ressalvas honestas
- **A prova vale para ESTE codex** (`0.142.5`, `exec --json`): upgrade futuro de pin re-expõe o
  risco de deriva do stream — o `EngineContainerPinTest` existe para tornar o bump consciente.
- **O smoke LOCAL gasta o plano do operador no host** — runs reais custam tokens da conta; o
  roteiro minimiza (1 issue no LOCAL, 2 no DOCKER).
- **Skills-por-prompt provada com N=1 motor novo**: se um terceiro motor um dia ignorar o prompt,
  o fallback AGENTS.md já está decidido — mas a generalização "qualquer motor lê" segue indutiva.

## Relaciona
ADR-0002 (login nasce no container — reafirmado contra a tentação do host logado) · ADR-0014
(motor = adapterType+model+effort; adapter rico do codex nasceu lá) · ADR-0015 (continuidade por
handoff, não resume — por isso a retomada de sessão segue fora) · ADR-0016 (EngineContainer,
montagem seletiva, setup-*.sh — a fatia EXECUTA o que ele desenhou) · ADR-0021/0022/0023 (os
contratos de workspace cuja agnosticidade esta fatia prova; "Codex ganha de graça" vira fato ou
vira bug conhecido).
