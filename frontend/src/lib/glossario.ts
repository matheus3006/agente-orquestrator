// #11a — a FONTE ÚNICA das explicações dos 8 termos autorais do produto (molde,
// motor, carta, grill, ancorada, arquiteto, dispatch, KnowAll). Uma frase por
// termo, no tom do produto: direta, sem tutorial. O <Termo> (tooltip na prosa) e
// os `title=` nativos dos sites não-prosa leem daqui — nenhuma frase é escrita
// duas vezes. A fatia 3 (glossário reencontrável) reusa esta MESMA fonte.
//
// `TermoId` é união fechada: um id fora dela não compila (o call-site erra no
// typecheck, não em runtime) — por isso `glossa` nunca precisa de fallback.

export type TermoId =
  | "molde"
  | "motor"
  | "carta"
  | "grill"
  | "ancorada"
  | "arquiteto"
  | "dispatch"
  | "knowall";

// Record TOTAL sobre TermoId: o compilador exige as 8 chaves — esquecer uma não passa.
const GLOSSARIO: Record<TermoId, string> = {
  molde: "Preset pronto de agente — motor e skills; contratar copia o snapshot da versão.",
  motor: "O cérebro do agente — plano, modelo e esforço; sobrescreve o do molde.",
  carta: "As decisões de direção vigentes que o CEO carrega como CLAUDE.md a cada run.",
  grill: "As rodadas de perguntas que arrancam o racional antes de a decisão fechar.",
  ancorada: "Toda decisão fica presa ao ciclo de review (reviewId) — nunca solta.",
  arquiteto: "O agente que lidera o time — sem ele designado, o dispatch trava.",
  dispatch: "A liberação do time para disparar trabalho — livre com arquiteto, travado sem ele.",
  knowall:
    "Compõe o skillset por conhecimento — descreva o que o agente precisa saber e ele casa cada linha a uma skill.",
};

/** A explicação de 1 linha do termo. Total sobre {@link TermoId} — sempre devolve string. */
export function glossa(id: TermoId): string {
  return GLOSSARIO[id];
}

// #11b — o ÍNDICE do glossário reencontrável: a ordem de exibição (D-11-01) e o
// rótulo de cada termo. A ordem é pedagógica — loop principal (molde→motor→carta),
// depois o gate (grill→ancorada), depois o power-user (arquiteto→dispatch) e por
// fim o mais profundo (KnowAll). A FRASE de cada um NÃO mora aqui: o diálogo
// percorre TERMOS e chama glossa(id), a mesmíssima fonte do tooltip — zero
// duplicação. O rótulo é o próprio termo autoral (a palavra É o nome), exceto
// `knowall`, cujo nome de produto é a marca "KnowAll".
export const TERMOS: readonly { id: TermoId; rotulo: string }[] = [
  { id: "molde", rotulo: "molde" },
  { id: "motor", rotulo: "motor" },
  { id: "carta", rotulo: "carta" },
  { id: "grill", rotulo: "grill" },
  { id: "ancorada", rotulo: "ancorada" },
  { id: "arquiteto", rotulo: "arquiteto" },
  { id: "dispatch", rotulo: "dispatch" },
  { id: "knowall", rotulo: "KnowAll" },
];
