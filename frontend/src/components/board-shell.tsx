"use client";

import { Menu, X } from "lucide-react";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Sidebar } from "@/components/sidebar";

// O SHELL do board (P0 #1) — o SEAM que torna a sidebar responsiva sem espalhar
// `if` de viewport pela árvore. A MESMA <Sidebar/> vira sidebar cheia (≥1200px),
// trilho só-ícone (768–1199) ou drawer off-canvas (<768) só pela CSS do
// `.board-shell`; este componente carrega apenas o BIT que a CSS não alcança —
// aberto/fechado do drawer — e a acessibilidade em volta dele (Esc, foco,
// trava de scroll, `inert` no fundo). Interface mínima: recebe o conteúdo da
// página; toda a mecânica do chrome fica escondida aqui dentro.
export function BoardShell({ children }: { children: React.ReactNode }) {
  const [navAberto, setNavAberto] = useState(false);
  const pathname = usePathname();
  const [rotaVista, setRotaVista] = useState(pathname);
  const gatilhoRef = useRef<HTMLButtonElement>(null);

  // Navegar fecha o drawer: clicar num link muda a rota. Ajuste de estado no
  // próprio render (padrão oficial "you might not need an effect") — sem efeito
  // e sem render em cascata; cobre nav, switcher e Moldes sem que cada link
  // precise saber do drawer.
  if (pathname !== rotaVista) {
    setRotaVista(pathname);
    setNavAberto(false);
  }

  // Enquanto aberto: Esc fecha (e devolve o foco ao gatilho) e o fundo não rola.
  useEffect(() => {
    if (!navAberto) {
      return;
    }
    const aoTeclar = (evento: KeyboardEvent) => {
      if (evento.key === "Escape") {
        // E1 (exceção aprovável ao gate C8): um Dialog aberto POR CIMA do drawer
        // (ex.: o glossário aberto de dentro do drawer no mobile) é dono do seu
        // próprio Esc — o Radix fecha só o Dialog e devolve o foco ao gatilho.
        // Sem este guard, este handler global fecharia o drawer JUNTO e roubaria
        // o foco (duplo-fechamento com briga de foco). Só ADICIONA segurança: sem
        // Dialog aberto o alvo do Esc nunca cai dentro de um .dialog-panel, então
        // o comportamento existente do shell fica intacto.
        if (evento.target instanceof Element && evento.target.closest(".dialog-panel")) {
          return;
        }
        setNavAberto(false);
        gatilhoRef.current?.focus();
      }
    };
    document.addEventListener("keydown", aoTeclar);
    const overflowAnterior = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", aoTeclar);
      document.body.style.overflow = overflowAnterior;
    };
  }, [navAberto]);

  const fecharPeloScrim = () => {
    setNavAberto(false);
    gatilhoRef.current?.focus();
  };

  return (
    <div className="board-shell" data-nav-open={navAberto || undefined}>
      {/* Topbar só-mobile: leva a marca e o gatilho do drawer. A CSS a esconde
          acima de 768px, então ela só existe onde a sidebar sai de cena. */}
      <header className="board-topbar">
        <button
          ref={gatilhoRef}
          type="button"
          onClick={() => setNavAberto((aberto) => !aberto)}
          aria-expanded={navAberto}
          aria-controls="board-nav"
          aria-label={navAberto ? "Fechar navegação" : "Abrir navegação"}
          className="grid h-9 w-9 place-items-center rounded-md border border-line bg-raised text-dim transition-colors duration-150 ease-out-strong hover:text-foreground"
        >
          {navAberto ? <X size={18} strokeWidth={2} /> : <Menu size={18} strokeWidth={2} />}
        </button>
        <span className="flex select-none items-center gap-2.5">
          <span aria-hidden className="wordmark-glyph">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4">
              <circle cx="12" cy="12" r="7.5" />
              <circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" />
            </svg>
          </span>
          <span className="font-display text-[13px] font-semibold tracking-[0.14em] text-foreground">
            ORQUESTRA<em className="not-italic text-phosphor">DOR</em>
          </span>
        </span>
      </header>

      <Sidebar />

      {/* Backdrop do drawer: fecha ao clicar. Fora da ordem de tabulação
          (tabIndex -1 + aria-hidden); Esc e o próprio gatilho dão conta do teclado. */}
      <button
        type="button"
        className="board-scrim"
        tabIndex={-1}
        aria-hidden
        onClick={fecharPeloScrim}
      />

      {/* `inert` tira o conteúdo do fundo do foco/tab enquanto o drawer está
          aberto — o escopo de foco fica no par topbar+drawer sem trap manual. */}
      <main className="board-main" inert={navAberto}>
        {children}
      </main>
    </div>
  );
}
