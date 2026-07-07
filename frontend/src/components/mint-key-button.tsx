"use client";

import { useMutation } from "@tanstack/react-query";
import { KeyRound } from "lucide-react";
import { useState } from "react";
import { ApiError, mintAgentKey } from "@/lib/api";
import { pushToast } from "@/lib/toaster";

// Mint de API key do agente (F.6). O segredo em claro volta UMA vez e vive
// SÓ neste estado local para o reveal — nunca é persistido nem logado. É a
// credencial de IDENTIDADE do agente no control plane (`ag_...`), não uma
// chave de modelo. Re-clicar gera uma NOVA key (a anterior segue válida).

export function MintKeyButton({
  companyId,
  agentId,
  agentName,
}: {
  companyId: string | undefined;
  agentId: string;
  agentName: string;
}) {
  const [revealed, setRevealed] = useState<string | null>(null);

  const mint = useMutation({
    mutationFn: () => mintAgentKey(companyId as string, agentId),
    onSuccess: (minted) => {
      setRevealed(minted.apiKey);
      pushToast(`API key gerada para ${agentName}`);
    },
    onError: (error) => {
      pushToast(error instanceof ApiError ? error.message : "não deu para gerar a key", "bad");
    },
  });

  return (
    <>
      <button
        type="button"
        className="btn"
        style={{ justifyContent: "center" }}
        disabled={mint.isPending || !companyId}
        onClick={() => mint.mutate()}
      >
        <KeyRound size={13} strokeWidth={2} />
        {mint.isPending ? "Gerando…" : revealed ? "Gerar nova API key" : "Gerar API key"}
      </button>
      {revealed && (
        <div className="key-reveal">
          {revealed}
          <small>guarde agora — o servidor só armazena o hash SHA-256</small>
        </div>
      )}
    </>
  );
}
