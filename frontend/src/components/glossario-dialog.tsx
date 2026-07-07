"use client";

import { CircleHelp } from "lucide-react";
import { useState } from "react";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { glossa, TERMOS } from "@/lib/glossario";

// #11b — o glossário REENCONTRÁVEL: os 8 termos autorais num lugar só, sempre a um
// clique. Segue o padrão provado da casa (CartaDialog, ceo-conversa.tsx:554-614):
// Dialog CONTROLADO por useState + botão cru (não DialogTrigger). O gatilho é uma
// entrada na seção Catálogo da sidebar — e como o diálogo NÃO navega, é um
// <button> sem active-bar nem aria-current (o activeSurfaceId de lib/shell.ts não
// ganha entrada). A lista reusa a FONTE ÚNICA lib/glossario.ts: TERMOS dá a ordem
// e o rótulo, glossa(id) dá a frase — as MESMAS frases do tooltip, nenhuma
// reescrita aqui. dt = termo (label mono, ≥11px, gate C5); dd = prosa sans no
// visual de .dialog-text (a explicação nunca é label mono). Zero CSS novo.
export function GlossarioDialog() {
  const [aberto, setAberto] = useState(false);

  return (
    <>
      <button
        type="button"
        onClick={() => setAberto(true)}
        title="Glossário — o que cada termo do produto quer dizer"
        className="board-nav-item flex items-center gap-2.5 rounded-md border border-transparent px-2.5 py-[7px] text-left text-[13.5px] font-[450] text-dim transition-colors duration-150 ease-out-strong hover:bg-raised hover:text-foreground"
      >
        <CircleHelp size={16} strokeWidth={2} className="shrink-0" />
        <span className="board-nav-label">Glossário</span>
        <span className="board-moldes-tag ml-auto font-mono text-[9.5px] uppercase tracking-[0.1em] text-faint">
          global
        </span>
      </button>

      <Dialog open={aberto} onOpenChange={setAberto}>
        <DialogContent aria-describedby={undefined} className="max-w-[560px]">
          <div className="dialog-body">
            <DialogTitle className="dialog-title">Glossário</DialogTitle>
            <p className="dialog-text">O vocabulário próprio do produto, num lugar só.</p>
            <dl className="m-0 max-h-[55vh] space-y-3.5 overflow-y-auto pr-1">
              {TERMOS.map(({ id, rotulo }) => (
                <div key={id}>
                  <dt className="font-mono text-[13px] font-medium text-foreground">{rotulo}</dt>
                  <dd className="m-0 mt-0.5 text-[13px] leading-[1.5] text-dim">{glossa(id)}</dd>
                </div>
              ))}
            </dl>
          </div>
          <div className="dialog-actions">
            <DialogClose className="btn ghost" type="button">
              Fechar
            </DialogClose>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
