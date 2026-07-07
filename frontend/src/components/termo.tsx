"use client";

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { glossa, type TermoId } from "@/lib/glossario";

// <Termo id> — o termo autoral em PROSA ganha a explicação de 1 linha por tooltip
// (hover desktop) e a marca .termo (pontilhado sutil). SÓ nos ~6 sites de texto
// corrido; todo o resto consome glossa(id) via `title=` nativo (sem duplicar a
// frase, sem espalhar a marca). O gatilho é um <span> (asChild) para fluir na
// prosa e nunca roubar o clique de elementos interativos. A frase vem da fonte
// única lib/glossario.ts — nunca redigida aqui.

export function Termo({ id, children }: { id: TermoId; children: React.ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="termo">{children}</span>
      </TooltipTrigger>
      <TooltipContent>{glossa(id)}</TooltipContent>
    </Tooltip>
  );
}
