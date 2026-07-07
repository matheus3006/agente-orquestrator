/* ============================================================
   agente-orquestrator — protótipo cadillac (P.1) — DESCARTÁVEL
   Dados: fixtures REAIS da F.0 (steps do Atlas, ledger do Nix,
   feed) + seed plausível. O movimento aqui mapeia 1:1 para o
   React da implementação (CSS transitions + appends).
   ============================================================ */

"use strict";

/* ---------- ícones (lucide, stroke 2) ---------- */
const I = {
  chev: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 18 6-6-6-6"/></svg>',
  issue: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3.2"/></svg>',
  expand: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"/></svg>',
  x: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m4.5 12.5 5 5 10-11"/></svg>',
  block: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="m5.5 5.5 13 13"/></svg>',
  reopen: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/></svg>',
  key: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="8" cy="15" r="4.5"/><path d="m11 12 9-9M17 6l3 3"/></svg>',
  bolt: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z"/></svg>',
  shield: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-3.2 8-9V5.4L12 2 4 5.4V13c0 5.8 8 9 8 9Z"/><path d="m9 12 2 2 4-4.5"/></svg>',
  skill: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m12 2 2.4 4.9 5.4.8-3.9 3.8.9 5.4-4.8-2.5-4.8 2.5.9-5.4L4.2 7.7l5.4-.8L12 2Z"/></svg>',
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>',
};

/* ---------- helpers ---------- */
const $ = (sel, el = document) => el.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const shortId = (id) => id.slice(0, 8);
const fmtClock = (ts) => new Date(ts).toLocaleTimeString("pt-BR", { hour12: false });
const fmtAgo = (ts) => {
  const s = Math.max(0, Math.floor((Date.now() - ts) / 1000));
  if (s < 60) return `há ${s}s`;
  if (s < 3600) return `há ${Math.floor(s / 60)}min`;
  return `há ${Math.floor(s / 3600)}h${Math.floor((s % 3600) / 60) || ""}`.replace(/h0?$/, "h");
};
const fmtElapsed = (ms) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};
const AGENT_HUES = { Atlas: "oklch(0.8 0.17 152)", Nix: "oklch(0.75 0.16 28)", Echo: "oklch(0.78 0.1 235)", Vega: "oklch(0.8 0.13 300)", Rune: "oklch(0.82 0.14 80)", Lyra: "oklch(0.8 0.1 195)" };
const agentDot = (name, size = "") => `<span class="agent-dot ${size}" style="background:${AGENT_HUES[name] || "oklch(0.6 0.02 240)"}">${esc(name.slice(0, 2).toUpperCase())}</span>`;

/* ============================================================
   DADOS — fixtures reais (F.0) + seed plausível
   ============================================================ */

/* transcript REAL do Atlas (fixtures/steps-transcript-completo.json), delays reais entre steps */
const ATLAS_SCRIPT = [
  { type: "RUN_QUEUED", detail: "wakeup: board", delay: 0 },
  { type: "RUN_STARTED", detail: null, delay: 500 },
  { type: "STDOUT", detail: "[plan] lendo issue e montando plano de execucao", delay: 50 },
  { type: "STDOUT", detail: "[skill] materializando skillset: analise-de-codigo v3", delay: 1010 },
  { type: "STDOUT", detail: "[step 1/5] clonando workspace da company", delay: 1010 },
  { type: "STDOUT", detail: "[step 2/5] rodando analise estatica nos 42 arquivos", delay: 2010 },
  { type: "STDOUT", detail: "[step 3/5] refatorando modulo de pagamentos", delay: 2010 },
  { type: "STDOUT", detail: "[warn] teste flaky detectado em PaymentServiceTest, re-rodando", delay: 2010 },
  { type: "STDOUT", detail: "[step 4/5] suite verde: 87/87", delay: 2010 },
  { type: "STDOUT", detail: "[step 5/5] gerando resumo do diff para review", delay: 2010 },
  { type: "STDOUT", detail: "[done] issue concluida com sucesso", delay: 1010 },
  { type: "RUN_SUCCEEDED", detail: null, delay: 500 },
];

const VEGA_SCRIPT = [
  { type: "RUN_QUEUED", detail: "wakeup: routine", delay: 0 },
  { type: "RUN_STARTED", detail: null, delay: 600 },
  { type: "STDOUT", detail: "[plan] issue pede documentacao da API de webhooks", delay: 100 },
  { type: "STDOUT", detail: "[skill] materializando skillset: doc-coauthoring v1", delay: 1400 },
  { type: "STDOUT", detail: "[step 1/4] varrendo controllers em busca de endpoints", delay: 1800 },
  { type: "STDOUT", detail: "[step 2/4] extraindo payloads de exemplo dos testes", delay: 2400 },
  { type: "STDOUT", detail: "[step 3/4] escrevendo docs/webhooks.md (2.4k palavras)", delay: 2800 },
  { type: "STDOUT", detail: "[step 4/4] validando exemplos contra o schema", delay: 2200 },
  { type: "STDOUT", detail: "[done] documentacao pronta, submetendo para review", delay: 1600 },
  { type: "RUN_SUCCEEDED", detail: null, delay: 500 },
];

const RUNE_SCRIPT = [
  { type: "RUN_QUEUED", detail: "wakeup: board", delay: 0 },
  { type: "RUN_STARTED", detail: null, delay: 500 },
  { type: "STDOUT", detail: "[plan] atualizando dependencias do backend", delay: 80 },
  { type: "STDOUT", detail: "[step 1/3] mvn versions:display-dependency-updates", delay: 1600 },
  { type: "STDOUT", detail: "[step 2/3] spring-boot 3.5.0 -> 3.5.3, flyway 10.22 -> 10.24", delay: 2600 },
  { type: "STDOUT", detail: "[step 3/3] suite completa: 107/107 verde", delay: 3400 },
  { type: "STDOUT", detail: "[done] bump seguro, zero breaking changes", delay: 1800 },
  { type: "RUN_SUCCEEDED", detail: null, delay: 500 },
];

const now = Date.now();

const AGENTS = [
  { id: "87dc5918-a9fb-4150-aa32-255c8aa6009d", name: "Atlas", role: "builder", runtime: "process · LOCAL", skills: [["analise-de-codigo", "v3"], ["tdd", "v2"], ["diagnose", "v1"]], script: ATLAS_SCRIPT, issueId: "0a9724a5" },
  { id: "9b1a44c0-5f2e-4f8f-9a1b-3c9d2e7f6a01", name: "Vega", role: "writer", runtime: "process · DOCKER", skills: [["doc-coauthoring", "v1"], ["write-a-skill", "v2"]], script: VEGA_SCRIPT, issueId: "d31f88a2" },
  { id: "19f8600c-221a-41b9-8d76-c5b0c5f90b6f", name: "Nix", role: "deployer", runtime: "process · DOCKER", skills: [["deploy", "v4"], ["incident-response", "v1"]], script: null, issueId: "c981a028" },
  { id: "4e77aa30-8c1d-4d2e-b5f6-a09b8c7d6e02", name: "Rune", role: "maintainer", runtime: "process · LOCAL", skills: [["tech-debt", "v1"], ["tdd", "v2"]], script: RUNE_SCRIPT, issueId: "e4429b17" },
  { id: "26268a10-b749-4294-9a75-8535ca4e1de9", name: "Echo", role: "analyst", runtime: "process · LOCAL", skills: [["metrics-review", "v1"]], script: null, issueId: null },
  { id: "7c53de91-2b0a-4b3c-8d4e-f5a6b7c8d903", name: "Lyra", role: "architect", runtime: "claude-code · DOCKER", skills: [["human-architect-mindset", "v2"], ["grilling", "v3"], ["domain-modeling", "v1"]], script: null, issueId: null },
];

const ISSUES = [
  { id: "0a9724a5", title: "Refatorar modulo de pagamentos", desc: "Extrair o gateway de pagamento para um seam proprio com contrato testavel. O acoplamento atual espalha chamadas HTTP por 4 services.", status: "IN_PROGRESS", risk: 3, assignee: "Atlas", createdAt: now - 86400e3 * 2 },
  { id: "d31f88a2", title: "Documentar API de webhooks", desc: "Gerar docs/webhooks.md a partir dos controllers reais, com payloads de exemplo extraidos dos testes.", status: "IN_PROGRESS", risk: 1, assignee: "Vega", createdAt: now - 86400e3 },
  { id: "c981a028", title: "Deploy do servico de billing", desc: "Publicar a imagem do billing no registry e promover para o ambiente de staging.", status: "BLOCKED", risk: 4, assignee: "Nix", createdAt: now - 3600e3 * 5 },
  { id: "e4429b17", title: "Atualizar dependencias do backend", desc: "Bump de patch/minor das dependencias Maven com a suite completa como guarda.", status: "OPEN", risk: 1, assignee: "Rune", createdAt: now - 3600e3 * 3 },
  { id: "f80cc341", title: "Migrar schema de billing para particionamento", desc: "Particionar cost_event por mes antes que o ledger cresca. Migration V12 com backfill em lotes.", status: "PENDING_REVIEW", risk: 5, assignee: "Lyra", createdAt: now - 86400e3 * 3 },
  { id: "a2b55e09", title: "Rotacionar credenciais do registry", desc: "Gerar novo token de push, atualizar o secret e invalidar o antigo.", status: "PENDING_REVIEW", risk: 4, assignee: "Echo", createdAt: now - 3600e3 * 8 },
  { id: "b7d10c55", title: "Escrever testes de contrato do gateway", desc: "Cobrir o contrato do gateway de pagamento com testes de contrato (happy path + 4xx/5xx).", status: "OPEN", risk: 2, assignee: null, createdAt: now - 3600e3 * 2 },
  { id: "c3e88f12", title: "Configurar pipeline de CI", desc: "GitHub Actions: build + suite + validacao de migrations em Postgres real.", status: "DONE", risk: 2, assignee: "Rune", createdAt: now - 86400e3 * 5 },
  { id: "d9f02a77", title: "Indexar catalogo de skills", desc: "Import inicial do catalogo global (~83 skills) e verificacao do ranking trigram.", status: "DONE", risk: 1, assignee: "Echo", createdAt: now - 86400e3 * 6 },
  { id: "e1a33b88", title: "Provisionar ambiente de staging", desc: "Cancelada: staging vira docker compose local por enquanto.", status: "CANCELLED", risk: 2, assignee: null, createdAt: now - 86400e3 * 4 },
];

/* ledger REAL do Nix (fixtures/issue-runs-ledger.json) — re-baseado no relogio da demo */
const RUN_LEDGERS = {
  c981a028: [
    { id: "213eb466-93a1-4dd0-9256-684ead1235a2", agent: "Nix", status: "FAILED", attempt: 1, startedAt: now - 3600e3 * 2, finishedAt: now - 3600e3 * 2 + 4080, lastError: "processo terminou com exit 1" },
    { id: "b5ff041c-dc2c-4ef6-a945-acede038bcd4", agent: "Nix", status: "FAILED", attempt: 1, startedAt: now - 3600e3 * 2 - 8060, finishedAt: now - 3600e3 * 2 - 3960, lastError: "processo terminou com exit 1" },
  ],
  f80cc341: [
    { id: "5a0c9d21-77b1-4c62-a90e-1f2e3d4c5b6a", agent: "Lyra", status: "SUCCEEDED", attempt: 1, startedAt: now - 86400e3, finishedAt: now - 86400e3 + 214e3, lastError: null },
  ],
  a2b55e09: [
    { id: "8e4f7a90-33c2-4d15-b6a7-9c8d7e6f5a4b", agent: "Echo", status: "SUCCEEDED", attempt: 2, startedAt: now - 3600e3 * 8, finishedAt: now - 3600e3 * 8 + 96e3, lastError: null },
  ],
};

/* transcript REAL congelado do run FAILED do Nix (fixtures/mission-control-settled.json) */
const NIX_FAILED_TRACE = [
  { type: "RUN_QUEUED", detail: "wakeup: retry apos reopen", at: now - 3600e3 * 2 - 600 },
  { type: "RUN_STARTED", detail: null, at: now - 3600e3 * 2 },
  { type: "STDOUT", detail: "[plan] preparando deploy do servico de billing", at: now - 3600e3 * 2 + 40 },
  { type: "STDOUT", detail: "[step 1/3] build da imagem docker", at: now - 3600e3 * 2 + 1050 },
  { type: "STDOUT", detail: "[step 2/3] push para o registry", at: now - 3600e3 * 2 + 2060 },
  { type: "STDOUT", detail: "[error] registry recusou: credencial expirada", at: now - 3600e3 * 2 + 3070 },
  { type: "RUN_FAILED", detail: "processo terminou com exit 1", at: now - 3600e3 * 2 + 4080 },
];

const LYRA_LAST_TRACE = [
  { type: "RUN_STARTED", detail: null, at: now - 86400e3 },
  { type: "STDOUT", detail: "[plan] desenhando migration V12 de particionamento", at: now - 86400e3 + 900 },
  { type: "STDOUT", detail: "[step 3/3] plano de backfill em lotes de 50k escrito", at: now - 86400e3 + 180e3 },
  { type: "STDOUT", detail: "[done] risco 5: estacionando para review do board", at: now - 86400e3 + 213e3 },
  { type: "RUN_SUCCEEDED", detail: null, at: now - 86400e3 + 214e3 },
];

const REVIEWS = [
  { id: "rv-4c21aa07", issueId: "f80cc341", runId: "5a0c9d21-77b1-4c62-a90e-1f2e3d4c5b6a", status: "PENDING", requestedAt: now - 86400e3 + 214e3, decidedAt: null, reason: null },
  { id: "rv-9d83be55", issueId: "a2b55e09", runId: "8e4f7a90-33c2-4d15-b6a7-9c8d7e6f5a4b", status: "PENDING", requestedAt: now - 3600e3 * 8 + 96e3, decidedAt: null, reason: null },
  { id: "rv-1f5090c3", issueId: "c3e88f12", runId: "77aa1b2c-3d4e-4f50-8a9b-0c1d2e3f4a5b", status: "APPROVED", requestedAt: now - 86400e3 * 5, decidedAt: now - 86400e3 * 5 + 3600e3, reason: "Pipeline verde nos 3 jobs, promovido." },
  { id: "rv-7e30dd18", issueId: "c981a028", runId: "b5ff041c-dc2c-4ef6-a945-acede038bcd4", status: "REJECTED", requestedAt: now - 86400e3 * 2, decidedAt: now - 86400e3 * 2 + 1800e3, reason: "Deploy sem janela combinada; reagendar e refazer com credencial nova." },
];

/* feed semeado com o shape REAL do activity_log (fixtures/activity-feed.json) */
let FEED = [
  { at: now - 40e3, action: "SKILL_ATTACHED", actor: "BOARD", body: `<b>matheus</b> anexou <b>deploy v4</b> à issue <span class="mono">c981a028</span> para <b>Nix</b>` },
  { at: now - 3600e3 * 2 + 4080, action: "ISSUE_BLOCKED", actor: "AGENT", body: `<b>Nix</b> travou em <b>Deploy do servico de billing</b> — <span class="mono">run 213eb466 exit 1</span>` },
  { at: now - 3600e3 * 2 - 700, action: "ISSUE_REOPENED", actor: "BOARD", body: `<b>matheus</b> reabriu <b>Deploy do servico de billing</b> (REOPEN mantém o assignee)` },
  { at: now - 3600e3 * 2 - 3960, action: "ISSUE_BLOCKED", actor: "AGENT", body: `<b>Nix</b> travou em <b>Deploy do servico de billing</b> — <span class="mono">run b5ff041c exit 1</span>` },
  { at: now - 3600e3 * 8 + 96e3, action: "ISSUE_SUBMITTED_FOR_REVIEW", actor: "AGENT", body: `<b>Echo</b> estacionou <b>Rotacionar credenciais do registry</b> para review — <span class="mono">risco 4 ≥ limiar</span>` },
  { at: now - 86400e3 + 214e3, action: "ISSUE_SUBMITTED_FOR_REVIEW", actor: "AGENT", body: `<b>Lyra</b> estacionou <b>Migrar schema de billing</b> para review — <span class="mono">risco 5 ≥ limiar</span>` },
  { at: now - 86400e3 * 2 + 1800e3, action: "REVIEW_REJECTED", actor: "BOARD", body: `<b>matheus</b> rejeitou o ciclo <span class="mono">rv-7e30dd18</span> — issue de volta a BLOCKED` },
];

const FEED_ICON = {
  ISSUE_BLOCKED: ["bad", I.block],
  ISSUE_REOPENED: ["info", I.reopen],
  ISSUE_CANCELLED: ["info", I.x],
  ISSUE_SUBMITTED_FOR_REVIEW: ["warn", I.shield],
  REVIEW_APPROVED: ["good", I.check],
  REVIEW_REJECTED: ["bad", I.x],
  SKILL_ATTACHED: ["info", I.skill],
  SKILL_INSTALLED: ["info", I.skill],
  RUN_SUCCEEDED: ["good", I.check],
};

/* ============================================================
   SIMULAÇÃO — o produto respirando
   ============================================================ */
const SIM = {}; // por agente: { status, lines[], startedAt, attempt, runId, timer, cursor }

function simInit() {
  for (const agent of AGENTS) {
    SIM[agent.name] = { status: "IDLE", lines: [], startedAt: null, attempt: 0, runId: null, timer: null, cursor: 0 };
  }
  // Nix: BLOCKED estático com o rastro REAL da falha
  SIM.Nix.status = "BLOCKED";
  SIM.Nix.lines = NIX_FAILED_TRACE.map((s) => ({ ...s }));
  SIM.Nix.runId = "213eb466-93a1-4dd0-9256-684ead1235a2";
  // Lyra: IDLE com rastro de sucesso (estacionou pra review ontem)
  SIM.Lyra.lines = LYRA_LAST_TRACE.map((s) => ({ ...s }));
  SIM.Lyra.runId = "5a0c9d21-77b1-4c62-a90e-1f2e3d4c5b6a";

  startReplay(AGENTS[0], 400);    // Atlas começa quase imediatamente
  startReplay(AGENTS[1], 5200);   // Vega entra depois (grid dessincronizada)
  setTimeout(() => queueThenRun(AGENTS[3]), 9000); // Rune mostra QUEUED -> RUNNING
}

function queueThenRun(agent) {
  const sim = SIM[agent.name];
  sim.status = "QUEUED";
  sim.runId = crypto.randomUUID();
  sim.lines.push({ type: "RUN_QUEUED", detail: "wakeup: board", at: Date.now() });
  paintAgent(agent);
  bumpTallies();
  setTimeout(() => startReplay(agent, 0, sim.runId), 4200);
}

function startReplay(agent, delayMs, keepRunId) {
  setTimeout(() => {
    const sim = SIM[agent.name];
    sim.status = "RUNNING";
    sim.runId = keepRunId || crypto.randomUUID();
    sim.attempt += 1;
    sim.startedAt = Date.now();
    sim.cursor = keepRunId ? 1 : 0; // se ja mostrou RUN_QUEUED, pula
    if (!keepRunId) sim.lines = [];
    paintAgent(agent);
    bumpTallies();
    playNextStep(agent);
  }, delayMs);
}

function playNextStep(agent) {
  const sim = SIM[agent.name];
  const script = agent.script;
  if (sim.cursor >= script.length) return finishReplay(agent);
  const step = script[sim.cursor];
  sim.timer = setTimeout(() => {
    sim.lines.push({ type: step.type, detail: step.detail, at: Date.now() });
    if (sim.lines.length > 40) sim.lines.shift();
    sim.cursor += 1;
    appendTermLine(agent, { type: step.type, detail: step.detail, at: Date.now() });
    if (step.type === "RUN_SUCCEEDED" || step.type === "RUN_FAILED") return finishReplay(agent);
    playNextStep(agent);
  }, step.delay);
}

function finishReplay(agent) {
  const sim = SIM[agent.name];
  sim.status = "IDLE";
  const doneIssue = ISSUES.find((i) => i.id === agent.issueId);
  paintAgent(agent);
  bumpTallies();
  // efeito no resto do produto: feed + (às vezes) review nova
  if (agent.name === "Vega" && !REVIEWS.some((r) => r.issueId === "d31f88a2" && r.status === "PENDING")) {
    pushFeed("ISSUE_SUBMITTED_FOR_REVIEW", `<b>Vega</b> estacionou <b>${esc(doneIssue.title)}</b> para review — <span class="mono">risco reavaliado</span>`);
  } else {
    pushFeed("RUN_SUCCEEDED", `<b>${agent.name}</b> concluiu um run em <b>${esc(doneIssue ? doneIssue.title : "rotina")}</b> — <span class="mono">${shortId(sim.runId)}</span>`);
  }
  // respira 6-9s e recomeça o ciclo (a demo nunca para)
  const rest = 6000 + Math.random() * 3000;
  setTimeout(() => startReplay(agent, 0), rest);
}

function pushFeed(action, body) {
  FEED.unshift({ at: Date.now(), action, actor: "AGENT", body, fresh: true });
  if (FEED.length > 30) FEED.pop();
  const feedEl = $("#feedList");
  if (feedEl) {
    feedEl.insertAdjacentHTML("afterbegin", feedRowHtml(FEED[0], true));
    if (feedEl.children.length > 12) feedEl.lastElementChild.remove();
  }
}

/* ---------- pintura incremental (sem re-render da página) ---------- */
function statusWord(status) {
  return { RUNNING: "rodando", QUEUED: "na fila", BLOCKED: "bloqueado", IDLE: "ocioso" }[status] || status;
}

function paintAgent(agent) {
  const card = document.querySelector(`[data-agent-card="${agent.id}"]`);
  if (card) {
    const sim = SIM[agent.name];
    card.className = `agent-card ${sim.status.toLowerCase()}`;
    $(".led", card).className = `led ${sim.status.toLowerCase()}`;
    const word = $(".agent-status-word", card);
    word.className = `agent-status-word ${sim.status.toLowerCase()}`;
    word.textContent = statusWord(sim.status);
    $(".agent-issue-line", card).outerHTML = issueLineHtml(agent);
    $(".mini-term", card).innerHTML = miniTermHtml(agent);
    $(".agent-card-foot", card).innerHTML = cardFootHtml(agent);
  }
  const drawer = $("#drawer");
  if (drawer.classList.contains("open") && drawer.dataset.agent === agent.name) {
    renderDrawerBody(agent);
  }
}

function appendTermLine(agent, step) {
  document.querySelectorAll(`[data-agent-card="${agent.id}"]`).forEach((card) => {
    const term = $(".mini-term", card);
    const empty = $(".term-empty", term);
    if (empty) empty.remove();
    const cursorLine = $(".cursor-line", term);
    if (cursorLine) cursorLine.insertAdjacentHTML("beforebegin", termLineHtml(step, true));
    else term.insertAdjacentHTML("beforeend", termLineHtml(step, true));
    const lines = term.querySelectorAll(".term-line:not(.cursor-line)");
    if (lines.length > 5) lines[0].remove();
  });
  const drawer = $("#drawer");
  if (drawer.classList.contains("open") && drawer.dataset.agent === agent.name) {
    const t = $(".transcript", drawer);
    if (t) {
      t.insertAdjacentHTML("beforeend", transcriptLineHtml(step));
      t.scrollTop = t.scrollHeight;
    }
  }
}

function bumpTallies() {
  const counts = { RUNNING: 0, QUEUED: 0, BLOCKED: 0, IDLE: 0 };
  for (const a of AGENTS) counts[SIM[a.name].status] += 1;
  for (const [key, cls] of [["RUNNING", "running"], ["QUEUED", "queued"], ["BLOCKED", "blocked"], ["IDLE", "idle"]]) {
    const el = document.querySelector(`[data-tally="${cls}"]`);
    if (el) el.textContent = counts[key];
  }
}

/* relógio de 1s: elapsed dos cards, "esperando há" da review, latência fake */
setInterval(() => {
  for (const agent of AGENTS) {
    const sim = SIM[agent.name];
    if (sim.status !== "RUNNING" || !sim.startedAt) continue;
    const el = document.querySelector(`[data-elapsed="${agent.id}"]`);
    if (el) el.textContent = fmtElapsed(Date.now() - sim.startedAt);
  }
  document.querySelectorAll("[data-waiting]").forEach((el) => {
    el.textContent = fmtAgo(Number(el.dataset.waiting));
  });
  const lat = $("#fakeLatency");
  if (lat) lat.textContent = `${34 + Math.floor(Math.random() * 14)}ms`;
}, 1000);

/* ============================================================
   TEMPLATES compartilhados
   ============================================================ */
function lineClass(type, detail) {
  if (type === "RUN_FAILED" || (detail || "").startsWith("[error]")) return "error";
  if ((detail || "").startsWith("[warn]")) return "warn";
  if ((detail || "").startsWith("[done]") || type === "RUN_SUCCEEDED") return "ok";
  if (type !== "STDOUT") return "meta";
  return "";
}

function termLineHtml(step, animate = false) {
  const text = step.detail ?? `— ${step.type} —`;
  return `<div class="term-line ${lineClass(step.type, step.detail)}" ${animate ? "" : 'style="animation:none"'}><span class="t">${fmtClock(step.at)}</span>${esc(text)}</div>`;
}

function miniTermHtml(agent) {
  const sim = SIM[agent.name];
  if (!sim.lines.length) return `<div class="term-empty">sem execuções ainda — acorde o agente para ver o trace aqui</div>`;
  const tail = sim.lines.slice(-5);
  let html = tail.map((s) => termLineHtml(s)).join("");
  if (sim.status === "RUNNING") html += `<div class="term-line cursor-line" style="animation:none"><span class="term-cursor"></span></div>`;
  return html;
}

function issueLineHtml(agent) {
  const sim = SIM[agent.name];
  const issue = ISSUES.find((i) => i.id === agent.issueId);
  if (sim.status === "IDLE" && !issue) return `<div class="agent-issue-line idle-note">disponível — sem issue designada</div>`;
  if (!issue) return `<div class="agent-issue-line idle-note">rotina interna</div>`;
  const verb = { RUNNING: "executando", QUEUED: "na fila para", BLOCKED: "travado em", IDLE: "última issue:" }[sim.status];
  return `<div class="agent-issue-line">${I.issue}<span class="issue-title"><span style="color:var(--text-faint)">${verb}</span> ${esc(issue.title)}</span></div>`;
}

function cardFootHtml(agent) {
  const sim = SIM[agent.name];
  const left = sim.status === "RUNNING"
    ? `<span class="elapsed" data-elapsed="${agent.id}">${fmtElapsed(Date.now() - sim.startedAt)}</span><span>· attempt ${sim.attempt || 1}</span>`
    : sim.status === "QUEUED"
      ? `<span>aguardando dispatch</span>`
      : sim.lines.length
        ? `<span>run ${shortId(sim.runId || "")}</span>`
        : `<span>—</span>`;
  return `${left}<span class="spacer"></span><span class="peek">${I.expand} transcript</span>`;
}

function feedRowHtml(entry, fresh = false) {
  const [tone, icon] = FEED_ICON[entry.action] || ["info", I.bolt];
  return `<div class="feed-row ${fresh ? "fresh" : ""}">
    <span class="feed-time" data-waiting="${entry.at}">${fmtAgo(entry.at)}</span>
    <span class="feed-dot ${tone}">${icon}</span>
    <span class="feed-body">${entry.body}</span>
  </div>`;
}

function riskBadge(risk) { return `<span class="risk-badge risk-${risk}">R${risk}</span>`; }

function assigneeChip(name) {
  if (!name) return `<span class="assignee-chip" style="color:var(--text-faint)">— livre</span>`;
  return `<span class="assignee-chip">${agentDot(name)}${esc(name)}</span>`;
}

/* ============================================================
   VIEWS
   ============================================================ */
const STATUS_ORDER = ["IN_PROGRESS", "PENDING_REVIEW", "BLOCKED", "OPEN", "DONE", "CANCELLED"];
const STATUS_LED = { IN_PROGRESS: "running", PENDING_REVIEW: "queued", BLOCKED: "blocked", OPEN: "", DONE: "", CANCELLED: "" };
const STATUS_LABEL = { IN_PROGRESS: "Em execução", PENDING_REVIEW: "Aguardando review", BLOCKED: "Bloqueadas", OPEN: "Abertas", DONE: "Concluídas", CANCELLED: "Canceladas" };

function viewMissionControl() {
  const cards = AGENTS.map((agent) => {
    const sim = SIM[agent.name];
    return `<article class="agent-card ${sim.status.toLowerCase()}" data-agent-card="${agent.id}" data-open-agent="${agent.name}" tabindex="0" role="button" aria-label="Transcript de ${agent.name}">
      <div class="agent-card-head">
        <span class="led ${sim.status.toLowerCase()}"></span>
        <span class="agent-name">${agent.name}</span>
        <span class="agent-role">${agent.role}</span>
        <span class="agent-status-word ${sim.status.toLowerCase()}">${statusWord(sim.status)}</span>
      </div>
      ${issueLineHtml(agent)}
      <div class="mini-term">${miniTermHtml(agent)}</div>
      <div class="agent-card-foot">${cardFootHtml(agent)}</div>
    </article>`;
  }).join("");

  return `<div class="view">
    <div class="view-head">
      <div>
        <h1 class="view-title">Mission Control</h1>
        <p class="view-sub">demo-fixtures · ${AGENTS.length} agentes · poll 2.0s</p>
      </div>
      <div class="tally-row">
        <div class="tally running"><span class="tally-num" data-tally="running">0</span><span class="tally-label">rodando</span></div>
        <div class="tally queued"><span class="tally-num" data-tally="queued">0</span><span class="tally-label">na fila</span></div>
        <div class="tally blocked"><span class="tally-num" data-tally="blocked">0</span><span class="tally-label">bloqueados</span></div>
        <div class="tally idle"><span class="tally-num" data-tally="idle">0</span><span class="tally-label">ociosos</span></div>
      </div>
    </div>
    <div class="agent-grid">${cards}</div>
    <h2 class="section-label">Atividade recente</h2>
    <div class="feed" id="feedList">${FEED.slice(0, 12).map((e) => feedRowHtml(e)).join("")}</div>
  </div>`;
}

function viewIssues() {
  const groups = STATUS_ORDER.map((status) => {
    const list = ISSUES.filter((i) => i.status === status);
    if (!list.length) return "";
    const rows = list.map((issue) => `
      <a class="row" href="#/issues/${issue.id}">
        <span class="led ${STATUS_LED[status]}"></span>
        ${riskBadge(issue.risk)}
        <span class="row-title">${esc(issue.title)}</span>
        ${assigneeChip(issue.assignee)}
        <span class="row-meta">${shortId(issue.id)}</span>
      </a>`).join("");
    return `<section class="status-group">
      <div class="status-group-head"><span class="name">${STATUS_LABEL[status]}</span><span class="count">${list.length}</span></div>
      <div class="row-list">${rows}</div>
    </section>`;
  }).join("");

  return `<div class="view">
    <div class="view-head">
      <div>
        <h1 class="view-title">Issues</h1>
        <p class="view-sub">${ISSUES.length} issues · agrupadas por status</p>
      </div>
      <button class="btn primary">${I.bolt} Nova issue</button>
    </div>
    ${groups}
  </div>`;
}

function viewIssueDetail(issueId) {
  const issue = ISSUES.find((i) => i.id === issueId);
  if (!issue) return `<div class="view"><div class="empty-state">Issue não encontrada.</div></div>`;
  const ledger = RUN_LEDGERS[issue.id] || [];
  const agent = AGENTS.find((a) => a.name === issue.assignee);
  const liveSim = agent ? SIM[agent.name] : null;

  const ledgerRows = ledger.length
    ? ledger.map((run) => `
      <div class="ledger-row" data-open-run="${run.id}" data-run-issue="${issue.id}">
        <span class="run-status ${run.status}">${run.status}</span>
        <span class="assignee-chip">${agentDot(run.agent)}${run.agent}</span>
        <span class="ledger-err">${run.lastError ? esc(run.lastError) : ""}</span>
        <span class="ledger-when">${fmtAgo(run.startedAt)} · ${fmtElapsed((run.finishedAt || Date.now()) - run.startedAt)}</span>
        <span class="ledger-id">${shortId(run.id)}</span>
      </div>`).join("")
    : (liveSim && liveSim.status === "RUNNING"
      ? `<div class="ledger-row" data-open-agent-run="${agent.name}"><span class="run-status RUNNING">RUNNING</span><span class="assignee-chip">${agentDot(agent.name)}${agent.name}</span><span class="ledger-err"></span><span class="ledger-when" ><span data-elapsed="${agent.id}">${fmtElapsed(Date.now() - liveSim.startedAt)}</span> em voo</span><span class="ledger-id">${shortId(liveSim.runId)}</span></div>`
      : `<div class="empty-state">${I.clock}<br>nenhuma execução ainda — o ledger conta a história completa da issue</div>`);

  const actions = issue.status === "BLOCKED"
    ? `<div class="action-bar">
        <button class="btn primary" data-unblock="REOPEN">${I.reopen} Reabrir (REOPEN)</button>
        <button class="btn danger" data-unblock="CANCEL">${I.x} Cancelar issue</button>
      </div>`
    : issue.status === "PENDING_REVIEW"
      ? `<div class="action-bar"><a class="btn" href="#/reviews">${I.shield} Decidir na fila de review</a></div>`
      : "";

  return `<div class="view">
    <div class="crumb"><a href="#/issues">issues</a>${I.chev}<span>${shortId(issue.id)}</span></div>
    <div class="detail-grid">
      <div>
        <h1 class="detail-title">${esc(issue.title)}</h1>
        <p class="detail-desc">${esc(issue.desc)}</p>
        ${actions}
        <h2 class="section-label">Ledger de execuções</h2>
        <div class="ledger">${ledgerRows}</div>
        <h2 class="section-label">Atividade da issue</h2>
        <div class="feed">${FEED.filter((f) => f.body.includes(issue.title.slice(0, 18)) || f.body.includes(issue.id)).slice(0, 6).map((e) => feedRowHtml(e)).join("") || '<div class="empty-state" style="padding:20px">sem eventos registrados</div>'}</div>
      </div>
      <aside class="props-panel">
        <div class="prop"><span class="prop-key">status</span><span class="status-pill ${issue.status}"><span class="led ${STATUS_LED[issue.status]}"></span>${issue.status}</span></div>
        <div class="prop"><span class="prop-key">risco</span><span class="prop-val">${riskBadge(issue.risk)} <span style="color:var(--text-faint);font-size:11px">${issue.risk >= 5 ? "review obrigatória" : issue.risk >= 3 ? "atenção" : "baixo"}</span></span></div>
        <div class="prop"><span class="prop-key">assignee</span><span class="prop-val">${issue.assignee ? `${agentDot(issue.assignee)} ${issue.assignee}` : "—"}</span></div>
        <div class="prop"><span class="prop-key">criada</span><span class="prop-val mono">${fmtAgo(issue.createdAt)}</span></div>
        <div class="prop"><span class="prop-key">id</span><span class="prop-val mono">${issue.id}</span></div>
      </aside>
    </div>
  </div>`;
}

function viewReviews(filter = "PENDING") {
  const list = REVIEWS.filter((r) => r.status === filter);
  const cards = list.map((review) => {
    const issue = ISSUES.find((i) => i.id === review.issueId);
    const pendingActions = `
      <div class="review-actions">
        <button class="btn primary" data-decide="APPROVE" data-review="${review.id}">${I.check} Aprovar</button>
        <button class="btn danger" data-decide="REJECT" data-review="${review.id}">${I.x} Rejeitar</button>
      </div>`;
    const decidedNote = `<div style="font-size:12px;color:var(--text-dim);max-width:340px">${review.reason ? `“${esc(review.reason)}”` : ""}<div class="review-meta" style="margin-top:4px">decidida ${fmtAgo(review.decidedAt)}</div></div>`;
    return `<div class="review-card" data-review-card="${review.id}">
      <div>
        <h3 class="review-title"><a href="#/issues/${issue.id}">${esc(issue.title)}</a></h3>
        <div class="review-meta">
          ${riskBadge(issue.risk)}
          <span>${assigneeChip(issue.assignee)}</span>
          <span>run <a href="#" data-open-run="${review.runId}" data-run-issue="${issue.id}" style="color:var(--ice)">${shortId(review.runId)}</a></span>
          <span class="mono">âncora ${review.id}</span>
          ${review.status === "PENDING" ? `<span class="waiting-clock" data-waiting="${review.requestedAt}">${fmtAgo(review.requestedAt)}</span>` : ""}
        </div>
      </div>
      ${review.status === "PENDING" ? pendingActions : decidedNote}
    </div>`;
  }).join("");

  return `<div class="view">
    <div class="view-head">
      <div>
        <h1 class="view-title">Fila de Review</h1>
        <p class="view-sub">decisão ancorada no ciclo (reviewId) · reason opcional</p>
      </div>
    </div>
    <div class="filter-tabs">
      ${["PENDING", "APPROVED", "REJECTED"].map((s) => `<button class="filter-tab ${s === filter ? "on" : ""}" data-filter="${s}">${s} ${s === "PENDING" ? `(${REVIEWS.filter((r) => r.status === "PENDING").length})` : ""}</button>`).join("")}
    </div>
    <div id="reviewList">${cards || `<div class="empty-state">${I.shield}<br>fila limpa — nenhum ciclo ${filter.toLowerCase()} no momento</div>`}</div>
  </div>`;
}

function viewAgents() {
  const rows = AGENTS.map((agent) => {
    const sim = SIM[agent.name];
    const issue = ISSUES.find((i) => i.id === agent.issueId);
    const doing = sim.status === "RUNNING" ? `executando <b>${esc(issue?.title || "rotina")}</b>`
      : sim.status === "QUEUED" ? `na fila para <b>${esc(issue?.title || "")}</b>`
      : sim.status === "BLOCKED" ? `travado em <b>${esc(issue?.title || "")}</b>`
      : sim.lines.length ? "ocioso — último run concluído" : "ocioso — nunca executou";
    return `<a class="roster-row" href="#/agents/${agent.name}">
      <span class="roster-id">${agentDot(agent.name)}<span><div class="roster-name">${agent.name}</div><div class="roster-role">${agent.role}</div></span></span>
      <span class="roster-doing">${doing}</span>
      <span class="roster-cell">${agent.runtime}</span>
      <span class="roster-cell">${agent.skills.length} skills no set</span>
      <span class="agent-status-word ${sim.status.toLowerCase()}" style="justify-self:end">${statusWord(sim.status)}</span>
    </a>`;
  }).join("");

  return `<div class="view">
    <div class="view-head">
      <div>
        <h1 class="view-title">Agents</h1>
        <p class="view-sub">${AGENTS.length} no roster · runtime por agente</p>
      </div>
      <button class="btn primary">${I.bolt} Contratar agente</button>
    </div>
    <div class="roster">${rows}</div>
  </div>`;
}

function viewAgentDetail(name) {
  const agent = AGENTS.find((a) => a.name === name);
  if (!agent) return `<div class="view"><div class="empty-state">Agente não encontrado.</div></div>`;
  const sim = SIM[agent.name];
  const issue = ISSUES.find((i) => i.id === agent.issueId);
  const runsOfAgent = Object.values(RUN_LEDGERS).flat().filter((r) => r.agent === agent.name);

  return `<div class="view">
    <div class="crumb"><a href="#/agents">agents</a>${I.chev}<span>${agent.name}</span></div>
    <div class="detail-grid">
      <div>
        <div style="display:flex;align-items:center;gap:14px;margin-bottom:18px">
          ${agentDot(agent.name)}
          <div>
            <h1 class="detail-title" style="margin:0">${agent.name}</h1>
            <div class="roster-role">${agent.role} · ${agent.runtime}</div>
          </div>
          <span class="agent-status-word ${sim.status.toLowerCase()}" style="margin-left:auto">${statusWord(sim.status)}</span>
        </div>

        <h2 class="section-label">Trace ao vivo</h2>
        <article class="agent-card ${sim.status.toLowerCase()}" data-agent-card="${agent.id}" data-open-agent="${agent.name}" tabindex="0" role="button" style="margin-bottom:26px">
          ${issueLineHtml(agent)}
          <div class="mini-term" style="height:150px">${miniTermHtml(agent)}</div>
          <div class="agent-card-foot">${cardFootHtml(agent)}</div>
        </article>

        <h2 class="section-label">Runs recentes</h2>
        <div class="ledger">
          ${runsOfAgent.length ? runsOfAgent.map((run) => `
            <div class="ledger-row" data-open-run="${run.id}" data-run-issue="${Object.keys(RUN_LEDGERS).find((k) => RUN_LEDGERS[k].includes(run))}">
              <span class="run-status ${run.status}">${run.status}</span>
              <span class="ledger-err">${run.lastError ? esc(run.lastError) : ""}</span>
              <span class="ledger-when">${fmtAgo(run.startedAt)}</span>
              <span class="ledger-id">${shortId(run.id)}</span>
            </div>`).join("") : `<div class="empty-state" style="padding:22px">runs desta sessão aparecem no trace acima; histórico persistido viria do ledger</div>`}
        </div>
      </div>
      <aside>
        <div class="props-panel" style="position:static;margin-bottom:16px">
          <div class="prop"><span class="prop-key">issue atual</span><span class="prop-val">${issue ? esc(issue.title).slice(0, 22) + "…" : "—"}</span></div>
          <div class="prop"><span class="prop-key">adapter</span><span class="prop-val mono">${agent.runtime}</span></div>
          <div class="prop"><span class="prop-key">id</span><span class="prop-val mono">${shortId(agent.id)}</span></div>
        </div>
        <div class="kv-panel">
          <span class="prop-key">skillset (${agent.skills.length})</span>
          <div class="chips">${agent.skills.map(([skill, version]) => `<span class="skill-chip">${I.skill}${skill}<span class="v">${version}</span></span>`).join("")}</div>
        </div>
        <div class="kv-panel">
          <span class="prop-key">credencial</span>
          <button class="btn" data-mint="${agent.name}" style="justify-content:center">${I.key} Gerar API key</button>
          <div id="mintTarget"></div>
        </div>
      </aside>
    </div>
  </div>`;
}

/* ============================================================
   DRAWER — transcript completo (streaming quando ativo)
   ============================================================ */
function transcriptLineHtml(step) {
  const text = step.detail ?? "";
  return `<div class="term-line ${lineClass(step.type, step.detail)}">
    <span class="t">${fmtClock(step.at)}</span>
    <span class="type-tag">${step.type}</span>
    <span>${esc(text)}</span>
  </div>`;
}

function openDrawerForAgent(agent) {
  const drawer = $("#drawer");
  drawer.dataset.agent = agent.name;
  renderDrawerBody(agent);
  drawer.classList.add("open");
  $("#drawerScrim").classList.add("open");
}

function renderDrawerBody(agent) {
  const drawer = $("#drawer");
  const sim = SIM[agent.name];
  const issue = ISSUES.find((i) => i.id === agent.issueId);
  const statusTag = sim.status === "RUNNING"
    ? `<span class="run-status RUNNING">RUNNING</span>`
    : sim.lines.some((l) => l.type === "RUN_FAILED") ? `<span class="run-status FAILED">FAILED</span>`
    : sim.lines.some((l) => l.type === "RUN_SUCCEEDED") ? `<span class="run-status SUCCEEDED">SUCCEEDED</span>`
    : `<span class="run-status QUEUED">${sim.status}</span>`;
  drawer.innerHTML = `
    <div class="drawer-head">
      ${agentDot(agent.name)}
      <div>
        <div class="drawer-title">${agent.name} — StepTrace</div>
        <div class="drawer-sub">${issue ? esc(issue.title) + " · " : ""}run ${sim.runId ? shortId(sim.runId) : "—"}</div>
      </div>
      ${statusTag}
      <button class="btn ghost drawer-close" data-close-drawer aria-label="Fechar">${I.x}</button>
    </div>
    <div class="transcript">${sim.lines.map((s) => transcriptLineHtml(s)).join("") || '<div class="term-empty">sem steps ainda</div>'}</div>
    <div class="drawer-foot">
      <span>offset incremental · ${sim.lines.length} steps</span>
      <span class="spacer" style="flex:1"></span>
      <span>${sim.status === "RUNNING" ? "poll 2s ativo" : "run encerrado — poll parado"}</span>
    </div>`;
  const t = $(".transcript", drawer);
  t.scrollTop = t.scrollHeight;
}

function openDrawerForFrozenRun(runId, issueId) {
  const trace = runId.startsWith("213eb466") || runId.startsWith("b5ff041c") ? NIX_FAILED_TRACE
    : runId.startsWith("5a0c9d21") ? LYRA_LAST_TRACE
    : NIX_FAILED_TRACE;
  const issue = ISSUES.find((i) => i.id === issueId);
  const failed = trace.some((l) => l.type === "RUN_FAILED");
  const drawer = $("#drawer");
  drawer.dataset.agent = "";
  drawer.innerHTML = `
    <div class="drawer-head">
      <div>
        <div class="drawer-title">StepTrace — run ${shortId(runId)}</div>
        <div class="drawer-sub">${issue ? esc(issue.title) : ""}</div>
      </div>
      <span class="run-status ${failed ? "FAILED" : "SUCCEEDED"}">${failed ? "FAILED" : "SUCCEEDED"}</span>
      <button class="btn ghost drawer-close" data-close-drawer aria-label="Fechar">${I.x}</button>
    </div>
    <div class="transcript">${trace.map((s) => transcriptLineHtml(s)).join("")}</div>
    <div class="drawer-foot"><span>${trace.length} steps · transcript completo</span><span style="flex:1"></span><span>run encerrado</span></div>`;
  drawer.classList.add("open");
  $("#drawerScrim").classList.add("open");
}

function closeDrawer() {
  $("#drawer").classList.remove("open");
  $("#drawerScrim").classList.remove("open");
}

/* ============================================================
   DIALOGS + TOASTS
   ============================================================ */
function toast(msg, tone = "good") {
  const zone = $("#toastZone");
  const el = document.createElement("div");
  el.className = `toast ${tone === "bad" ? "bad" : ""}`;
  el.innerHTML = `${tone === "bad" ? I.x : I.check}<span>${msg}</span>`;
  zone.appendChild(el);
  setTimeout(() => { el.classList.add("leaving"); setTimeout(() => el.remove(), 280); }, 3600);
}

function openDecisionDialog(reviewId, decision) {
  const review = REVIEWS.find((r) => r.id === reviewId);
  const issue = ISSUES.find((i) => i.id === review.issueId);
  const dialog = $("#dialog");
  const isReject = decision === "REJECT";
  dialog.innerHTML = `
    <form method="dialog">
      <div class="dialog-body">
        <h3 class="dialog-title">${isReject ? "Rejeitar" : "Aprovar"} ciclo de review</h3>
        <p class="dialog-text"><b>${esc(issue.title)}</b><br>
        <span class="mono">âncora ${review.id} · run ${shortId(review.runId)}</span><br>
        ${isReject ? "REJECT estaciona a issue em BLOCKED — o recovery é o unblock (REOPEN/CANCEL)." : "APPROVE conclui a issue (DONE)."}</p>
        <textarea id="reasonInput" placeholder="reason ${isReject ? "(recomendado no reject)" : "(opcional)"} — fica na tabela review, nunca no activity log"></textarea>
      </div>
      <div class="dialog-actions">
        <button class="btn ghost" value="cancel">Cancelar</button>
        <button class="btn ${isReject ? "danger" : "primary"}" value="confirm">${isReject ? "Rejeitar ciclo" : "Aprovar ciclo"}</button>
      </div>
    </form>`;
  dialog.showModal();
  dialog.onclose = () => {
    if (dialog.returnValue !== "confirm") return;
    const reason = $("#reasonInput") ? $("#reasonInput").value.trim() : "";
    review.status = isReject ? "REJECTED" : "APPROVED";
    review.decidedAt = Date.now();
    review.reason = reason || null;
    const issueRef = ISSUES.find((i) => i.id === review.issueId);
    issueRef.status = isReject ? "BLOCKED" : "DONE";
    pushFeed(isReject ? "REVIEW_REJECTED" : "REVIEW_APPROVED",
      `<b>matheus</b> ${isReject ? "rejeitou" : "aprovou"} o ciclo <span class="mono">${review.id}</span> de <b>${esc(issueRef.title)}</b>`);
    const card = document.querySelector(`[data-review-card="${review.id}"]`);
    if (card) {
      card.classList.add("leaving");
      setTimeout(() => { card.remove(); refreshNavCounts(); const list = $("#reviewList"); if (list && !list.children.length) list.innerHTML = `<div class="empty-state">${I.shield}<br>fila limpa — nenhum ciclo pendente no momento</div>`; }, 320);
    }
    toast(`Ciclo ${isReject ? "rejeitado" : "aprovado"} — issue ${isReject ? "BLOCKED (destrave no detail)" : "DONE"}`, isReject ? "bad" : "good");
  };
}

function openUnblockDialog(issueId, decision) {
  const issue = ISSUES.find((i) => i.id === issueId);
  const dialog = $("#dialog");
  const isCancel = decision === "CANCEL";
  dialog.innerHTML = `
    <form method="dialog">
      <div class="dialog-body">
        <h3 class="dialog-title">${isCancel ? "Cancelar issue" : "Reabrir issue"}</h3>
        <p class="dialog-text"><b>${esc(issue.title)}</b><br>
        ${isCancel ? "CANCEL encerra a issue de vez (CANCELLED)." : "REOPEN devolve a issue para OPEN mantendo o assignee — o próximo wakeup re-executa com o mesmo agente."}</p>
      </div>
      <div class="dialog-actions">
        <button class="btn ghost" value="cancel">Voltar</button>
        <button class="btn ${isCancel ? "danger" : "primary"}" value="confirm">${isCancel ? "Cancelar issue" : "Reabrir (REOPEN)"}</button>
      </div>
    </form>`;
  dialog.showModal();
  dialog.onclose = () => {
    if (dialog.returnValue !== "confirm") return;
    issue.status = isCancel ? "CANCELLED" : "OPEN";
    pushFeed(isCancel ? "ISSUE_CANCELLED" : "ISSUE_REOPENED", `<b>matheus</b> ${isCancel ? "cancelou" : "reabriu"} <b>${esc(issue.title)}</b>`);
    toast(isCancel ? "Issue cancelada" : "Issue reaberta — assignee mantido");
    render();
  };
}

function mintKey(agentName) {
  const target = $("#mintTarget");
  if (!target) return;
  const key = `orq_${crypto.randomUUID().replaceAll("-", "")}`;
  target.innerHTML = `<div class="key-reveal">${key}<small>guarde agora — o servidor só armazena o hash SHA-256</small></div>`;
  toast(`API key gerada para ${agentName}`);
}

/* ============================================================
   ROUTER
   ============================================================ */
let reviewFilter = "PENDING";

function route() {
  const hash = location.hash.replace(/^#\/?/, "") || "mission-control";
  const [head, param] = hash.split("/");
  return { head, param };
}

function refreshNavCounts() {
  $("#issuesCount").textContent = ISSUES.filter((i) => !["DONE", "CANCELLED"].includes(i.status)).length;
  $("#reviewsCount").textContent = REVIEWS.filter((r) => r.status === "PENDING").length;
  $("#agentsCount").textContent = AGENTS.length;
}

function render() {
  const { head, param } = route();
  const main = $("#main");
  const views = {
    "mission-control": () => viewMissionControl(),
    issues: () => (param ? viewIssueDetail(param) : viewIssues()),
    reviews: () => viewReviews(reviewFilter),
    agents: () => (param ? viewAgentDetail(param) : viewAgents()),
  };
  main.innerHTML = (views[head] || views["mission-control"])();
  main.scrollTop = 0;
  document.querySelectorAll(".nav-item").forEach((item) => {
    item.classList.toggle("active", item.dataset.nav === head || (!views[head] && item.dataset.nav === "mission-control"));
  });
  refreshNavCounts();
  bumpTallies();
}

window.addEventListener("hashchange", render);

/* ---------- delegação de eventos ---------- */
document.addEventListener("click", (event) => {
  const openAgent = event.target.closest("[data-open-agent]");
  if (openAgent) {
    const agent = AGENTS.find((a) => a.name === openAgent.dataset.openAgent);
    if (agent) openDrawerForAgent(agent);
    return;
  }
  const openRun = event.target.closest("[data-open-run]");
  if (openRun) {
    event.preventDefault();
    openDrawerForFrozenRun(openRun.dataset.openRun, openRun.dataset.runIssue);
    return;
  }
  const openAgentRun = event.target.closest("[data-open-agent-run]");
  if (openAgentRun) {
    const agent = AGENTS.find((a) => a.name === openAgentRun.dataset.openAgentRun);
    if (agent) openDrawerForAgent(agent);
    return;
  }
  if (event.target.closest("[data-close-drawer]") || event.target.id === "drawerScrim") { closeDrawer(); return; }
  const decide = event.target.closest("[data-decide]");
  if (decide) { openDecisionDialog(decide.dataset.review, decide.dataset.decide); return; }
  const unblock = event.target.closest("[data-unblock]");
  if (unblock) { openUnblockDialog(route().param, unblock.dataset.unblock); return; }
  const mint = event.target.closest("[data-mint]");
  if (mint) { mintKey(mint.dataset.mint); return; }
  const filterTab = event.target.closest("[data-filter]");
  if (filterTab) { reviewFilter = filterTab.dataset.filter; render(); return; }
  if (event.target.closest("#companySwitcher")) {
    toast("Switcher de company — no produto real troca o [companySlug] da rota");
    return;
  }
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeDrawer();
  const card = event.target.closest?.("[data-open-agent]");
  if (event.key === "Enter" && card) {
    const agent = AGENTS.find((a) => a.name === card.dataset.openAgent);
    if (agent) openDrawerForAgent(agent);
  }
});

/* ---------- boot ---------- */
simInit();
render();
