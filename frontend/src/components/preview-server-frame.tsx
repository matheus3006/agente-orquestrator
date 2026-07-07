"use client";

import { ExternalLink } from "lucide-react";
import { useEffect } from "react";
import { heartbeatPreviewServer } from "@/lib/api";

// O MODO SRC do PreviewFrame (F.10/ADR-0023 §5; CONTEXT.md: PreviewFrame em
// DOIS MODOS). Contraste estrutural com o modo srcdoc do v1: lá o perigo era
// HTML de agente ganhar o origin do BOARD (por isso srcdoc + sandbox sem
// allow-same-origin); aqui o app vivo tem origin PRÓPRIO — a porta no
// loopback — e o browser isola de graça. allow-same-origin é seguro E
// necessário: sem ele o origin opaco quebra o fetch do app à própria API,
// exatamente o que a fatia existe para entregar. Segue sem popups e sem
// top-navigation. JS do app não age como o board: cross-origin por
// construção + X-Board-User (header custom → preflight → bloqueado).

/**
 * O keepalive invisível (~60s com o frame montado): renova o TTL por
 * inatividade do servidor. Desmontou a tela → para → o reaper ceifa em 15min.
 */
export function useServerKeepalive(
  companyId: string | undefined,
  issueId: string,
  enabled: boolean,
) {
  useEffect(() => {
    if (!companyId || !enabled) {
      return;
    }
    const beat = () => {
      heartbeatPreviewServer(companyId, issueId).catch(() => {
        // best-effort: um heartbeat perdido não é erro de UI — o próximo renova
      });
    };
    const timer = setInterval(beat, 60_000);
    return () => clearInterval(timer);
  }, [companyId, issueId, enabled]);
}

export function PreviewServerFrame({
  companyId,
  issueId,
  url,
}: {
  companyId: string;
  issueId: string;
  url: string;
}) {
  useServerKeepalive(companyId, issueId, true);

  return (
    <div className="preview-frame">
      <div className="preview-frame-bar">
        <span className="preview-frame-dots live" aria-hidden>
          <i /><i /><i />
        </span>
        <span className="preview-frame-label mono">
          app viva · {url.replace("http://", "")}
        </span>
        <span className="spacer" />
        {/* Abrir a porta numa aba não é o origin do board (ADR-0023 §5);
            o board segue sendo a moldura canônica. */}
        <a
          className="server-open-link mono"
          href={url}
          target="_blank"
          rel="noopener noreferrer"
        >
          <ExternalLink size={12} strokeWidth={2} /> abrir
        </a>
      </div>
      <div className="preview-stage desktop">
        <div className="preview-canvas desktop server-live">
          {/* modo src: allow-same-origin é o origin da PORTA DO APP, não do board */}
          <iframe
            key={url}
            title="App viva do preview"
            sandbox="allow-scripts allow-same-origin allow-forms"
            referrerPolicy="no-referrer"
            src={url}
          />
        </div>
      </div>
    </div>
  );
}
