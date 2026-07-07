import type { AgentSummary } from "./api";

// Layout PURO do organograma (T.1-FE): a árvore de reports_to como posições x/y para o React Flow.
// O canvas ESPELHA a hierarquia que já existe em AgentSummary.reportsToId — não a edita (drag-to-
// reparent é T.2, quando o backend ligar o reports_to como cadeia de comando). Tidy tree clássico:
// a largura de um nó é a largura da própria subárvore; o pai centraliza sobre os filhos; irmãos e
// raízes em ordem alfabética (layout determinístico → dá para testar posição por posição).

export const ORG_NODE_W = 224;
export const ORG_NODE_H = 86;
const GAP_X = 28;
const GAP_Y = 64;

export interface NoDoOrganograma {
  agentId: string;
  x: number;
  y: number;
  profundidade: number;
}

export interface ArestaDoOrganograma {
  id: string;
  managerId: string;
  reportId: string;
}

export interface Organograma {
  nos: NoDoOrganograma[];
  arestas: ArestaDoOrganograma[];
  largura: number;
  altura: number;
  /** Nenhum reports_to resolvível na company — só raízes soltas (a cadeia liga em T.2). */
  semCadeia: boolean;
}

/** Cor determinística do time (listra do nó + chip da toolbar) — mesmo registro do agentHue. */
export function teamHue(teamId: string): string {
  let hash = 0;
  for (const char of teamId) {
    hash = (hash * 31 + (char.codePointAt(0) ?? 0)) % 360;
  }
  return `oklch(0.72 0.11 ${hash})`;
}

/**
 * Monta o organograma dos agentes: raiz = sem reports_to OU apontando para fora da company
 * (agente órfão não some — vira raiz). Ciclo (defensivo; o backend não deveria produzir) é
 * quebrado promovendo o menor nome do ciclo a raiz — as arestas continuam desenhadas.
 */
export function montarOrganograma(agents: AgentSummary[]): Organograma {
  const porNome = [...agents].sort((a, b) => a.name.localeCompare(b.name));
  const porId = new Map(porNome.map((agent) => [agent.id, agent]));

  const filhosDe = new Map<string, AgentSummary[]>();
  const raizes: AgentSummary[] = [];
  for (const agent of porNome) {
    if (agent.reportsToId && porId.has(agent.reportsToId)) {
      const irmaos = filhosDe.get(agent.reportsToId) ?? [];
      irmaos.push(agent);
      filhosDe.set(agent.reportsToId, irmaos);
    } else {
      raizes.push(agent);
    }
  }

  // Ciclos: quem nunca é alcançável a partir de uma raiz vira raiz forçada (menor nome primeiro).
  const alcancaveis = new Set<string>();
  const alcancar = (agent: AgentSummary) => {
    if (alcancaveis.has(agent.id)) {
      return;
    }
    alcancaveis.add(agent.id);
    for (const filho of filhosDe.get(agent.id) ?? []) {
      alcancar(filho);
    }
  };
  raizes.forEach(alcancar);
  for (const agent of porNome) {
    if (!alcancaveis.has(agent.id)) {
      raizes.push(agent);
      alcancar(agent);
    }
  }

  // Passo 1: a largura de cada subárvore (visita com guarda — sobrevive a dado cíclico).
  const larguraDe = new Map<string, number>();
  const medir = (agent: AgentSummary): number => {
    const memo = larguraDe.get(agent.id);
    if (memo !== undefined) {
      return memo;
    }
    larguraDe.set(agent.id, ORG_NODE_W); // guarda anti-ciclo antes de descer
    const filhos = (filhosDe.get(agent.id) ?? []).filter((filho) => filho.id !== agent.id);
    const somaFilhos = filhos.reduce((soma, filho) => soma + medir(filho), 0);
    const largura = Math.max(ORG_NODE_W, somaFilhos + GAP_X * Math.max(0, filhos.length - 1));
    larguraDe.set(agent.id, largura);
    return largura;
  };
  raizes.forEach(medir);

  // Passo 2: posiciona — o pai centralizado sobre o vão dos filhos; irmãos em sequência.
  const nos: NoDoOrganograma[] = [];
  const posicionados = new Set<string>();
  const posicionar = (agent: AgentSummary, esquerda: number, profundidade: number) => {
    if (posicionados.has(agent.id)) {
      return;
    }
    posicionados.add(agent.id);
    const largura = larguraDe.get(agent.id) ?? ORG_NODE_W;
    nos.push({
      agentId: agent.id,
      x: esquerda + (largura - ORG_NODE_W) / 2,
      y: profundidade * (ORG_NODE_H + GAP_Y),
      profundidade,
    });
    let cursor = esquerda;
    for (const filho of filhosDe.get(agent.id) ?? []) {
      posicionar(filho, cursor, profundidade + 1);
      cursor += (larguraDe.get(filho.id) ?? ORG_NODE_W) + GAP_X;
    }
  };
  let cursorRaiz = 0;
  for (const raiz of raizes) {
    posicionar(raiz, cursorRaiz, 0);
    cursorRaiz += (larguraDe.get(raiz.id) ?? ORG_NODE_W) + GAP_X * 2;
  }

  const arestas: ArestaDoOrganograma[] = porNome
    .filter((agent) => agent.reportsToId && porId.has(agent.reportsToId))
    .map((agent) => ({
      id: `${agent.reportsToId}->${agent.id}`,
      managerId: agent.reportsToId as string,
      reportId: agent.id,
    }));

  return {
    nos,
    arestas,
    largura: nos.reduce((max, no) => Math.max(max, no.x + ORG_NODE_W), 0),
    altura: nos.reduce((max, no) => Math.max(max, no.y + ORG_NODE_H), 0),
    semCadeia: agents.length > 0 && arestas.length === 0,
  };
}
