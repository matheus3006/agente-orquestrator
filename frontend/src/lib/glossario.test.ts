import { describe, expect, it } from "vitest";
import { glossa, TERMOS, type TermoId } from "./glossario";

// #11a — a FONTE ÚNICA das frases dos 8 termos autorais. O tooltip (prosa) e o
// glossário reencontrável (fatia 3) leem daqui; testar a redação exata seria
// tautológico (o teste repetiria a string), então ancoramos no VOCABULÁRIO real
// do produto: cada frase precisa carregar a palavra-chave que prova que casou o
// termo certo — trocar duas frases de lugar quebra o teste.

// Âncoras semânticas: cada uma vem do domínio (código real), não da minha redação,
// e é única de uma frase só — glossa(id) tem de conter a âncora do SEU id.
const ANCORAS: Record<TermoId, string> = {
  molde: "preset",
  motor: "cérebro",
  carta: "CLAUDE.md",
  grill: "racional",
  ancorada: "reviewId",
  arquiteto: "lidera",
  dispatch: "disparar",
  knowall: "skillset",
};

const IDS = Object.keys(ANCORAS) as TermoId[];

describe("glossa — casa cada termo à sua explicação", () => {
  it.each(IDS)("a frase de %s carrega a âncora do domínio", (id) => {
    expect(glossa(id).toLowerCase()).toContain(ANCORAS[id].toLowerCase());
  });
});

describe("glossa — contrato de frase de tooltip", () => {
  it.each(IDS)("%s é uma linha só, não-vazia (cabe num tooltip/title)", (id) => {
    const frase = glossa(id);
    expect(frase.trim().length).toBeGreaterThan(0);
    expect(frase).not.toContain("\n");
  });

  it("nenhuma frase se repete (evita copiar/colar o termo errado)", () => {
    const frases = IDS.map(glossa);
    expect(new Set(frases).size).toBe(IDS.length);
  });

  it("barra id fora da união em tempo de compilação", () => {
    // @ts-expect-error — "xpto" não é TermoId; se glossa afrouxar p/ string, o
    // typecheck acusa este @ts-expect-error órfão (TS2578) e o gate C9 falha.
    glossa("xpto");
    expect(glossa("molde")).toBeTruthy();
  });
});

// #11b — TERMOS é o índice ORDENADO que o glossário reencontrável percorre. Ele
// NÃO guarda frases (o diálogo chama glossa(id), a mesma fonte do tooltip): guarda
// só a ORDEM de exibição (D-11-01) e o RÓTULO de cada termo. Fonte de verdade da
// cobertura = o próprio TermoId; este Record TOTAL força o compilador a listar os
// 8 ids, e TERMOS tem de bater com ele — nem mais, nem menos.
const TODOS_OS_IDS: Record<TermoId, true> = {
  molde: true,
  motor: true,
  carta: true,
  grill: true,
  ancorada: true,
  arquiteto: true,
  dispatch: true,
  knowall: true,
};

describe("TERMOS — índice do glossário reencontrável (#11b)", () => {
  it("cobre exatamente os 8 TermoId — nenhum a mais, nenhum a menos", () => {
    const idsListados = TERMOS.map((termo) => termo.id);
    // sem duplicatas: cada id aparece uma vez só
    expect(new Set(idsListados).size).toBe(idsListados.length);
    // exatamente o conjunto do TermoId (o Record TOTAL é a fonte independente)
    expect(new Set(idsListados)).toEqual(new Set(Object.keys(TODOS_OS_IDS)));
  });

  it("lista na ordem de aprendizado D-11-01 (loop → gate → power-user → o mais profundo)", () => {
    // ordem-fonte = a decisão D-11-01 escrita à mão do plano (não derivada do código)
    const ordemD1101: TermoId[] = [
      "molde",
      "motor",
      "carta",
      "grill",
      "ancorada",
      "arquiteto",
      "dispatch",
      "knowall",
    ];
    expect(TERMOS.map((termo) => termo.id)).toEqual(ordemD1101);
  });

  it("todo rótulo é não-vazio (o <dt> nunca renderiza em branco)", () => {
    for (const { rotulo } of TERMOS) {
      expect(rotulo.trim().length).toBeGreaterThan(0);
    }
  });

  it("o rótulo dos termos autorais é o próprio id — a palavra É o nome", () => {
    for (const { id, rotulo } of TERMOS) {
      if (id === "knowall") {
        continue; // knowall tem marca própria — coberto no teste seguinte
      }
      expect(rotulo).toBe(id);
    }
  });

  it("knowall exibe a marca do produto 'KnowAll', não o id interno minúsculo", () => {
    const knowall = TERMOS.find((termo) => termo.id === "knowall");
    expect(knowall?.rotulo).toBe("KnowAll");
  });
});
