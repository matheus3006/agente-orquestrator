"use client";

import { useMutation } from "@tanstack/react-query";
import { Plus, Sparkles, X } from "lucide-react";
import { useState } from "react";
import { ApiError, type KnowledgeMatchResult, matchKnowledge } from "@/lib/api";
import { linhasDeConhecimento } from "@/lib/role-templates";
import { cn } from "@/lib/utils";

// O compositor de SKILLSET por conhecimento (KnowAll, T.1.b): o autor do molde descreve O QUE o
// agente precisa saber (um conhecimento por linha); POST /knowall/match devolve o top-1 de cada um
// como SUGESTÃO — o autor revisa. Chips carregam o install-state RELATIVO À LENTE (INSTALLED = já
// na biblioteca da company; AVAILABLE = existe no catálogo, importar no attach); gaps (sem skill no
// catálogo) ficam visíveis como pendência de importação, nunca somem calados. Quem manda no valor
// final é `skillKeys` do dono — o compositor só mescla e o autor remove chip a chip.

export function KnowallCompositor({
  companyId,
  lenteNome,
  skillKeys,
  onChange,
  disabled = false,
}: {
  companyId: string | undefined;
  lenteNome: string | undefined;
  skillKeys: string[];
  onChange: (skillKeys: string[]) => void;
  disabled?: boolean;
}) {
  const [conhecimentos, setConhecimentos] = useState("");
  const [manual, setManual] = useState("");
  const [ultimaBusca, setUltimaBusca] = useState<KnowledgeMatchResult | null>(null);

  const busca = useMutation({
    mutationFn: () => matchKnowledge(companyId as string, linhasDeConhecimento(conhecimentos)),
    onSuccess: (resultado) => {
      setUltimaBusca(resultado);
      onChange([...new Set([...skillKeys, ...resultado.matchedSkillKeys])]);
    },
  });

  const stateDe = new Map(
    (ultimaBusca?.matches ?? [])
      .filter((match) => match.skillKey)
      .map((match) => [match.skillKey as string, match.installState]),
  );

  function addManual() {
    const key = manual.trim();
    if (key && !skillKeys.includes(key)) {
      onChange([...skillKeys, key]);
    }
    setManual("");
  }

  const falha =
    busca.error instanceof ApiError
      ? busca.error.message
      : busca.isError
        ? "não deu para falar com o KnowAll"
        : null;

  return (
    <div className="flex flex-col gap-2">
      <textarea
        placeholder={"o que o agente precisa saber — um conhecimento por linha\nex.: desenhar módulos profundos\nescrever specs de produto"}
        value={conhecimentos}
        onChange={(event) => setConhecimentos(event.target.value)}
        disabled={disabled}
        rows={3}
      />
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="btn"
          disabled={disabled || busca.isPending || !companyId || linhasDeConhecimento(conhecimentos).length === 0}
          onClick={() => busca.mutate()}
        >
          <Sparkles size={13} strokeWidth={2} />
          {busca.isPending ? "casando…" : "casar conhecimento → skills"}
        </button>
        {lenteNome && (
          <span className="font-mono text-[10.5px] text-faint">
            install-state pela lente de {lenteNome}
          </span>
        )}
      </div>

      {falha && <p className="m-0 text-[12.5px] text-coral">{falha}</p>}

      {(skillKeys.length > 0 || (ultimaBusca?.gaps.length ?? 0) > 0) && (
        <div className="chips">
          {skillKeys.map((key) => {
            const state = stateDe.get(key);
            return (
              <span key={key} className={cn("skill-chip", state === "AVAILABLE" && "available")}>
                {key}
                {state && <span className="v">{state === "INSTALLED" ? "instalada" : "importável"}</span>}
                <button
                  type="button"
                  className="chip-x"
                  aria-label={`remover ${key} do skillset`}
                  disabled={disabled}
                  onClick={() => onChange(skillKeys.filter((existente) => existente !== key))}
                >
                  <X size={10} strokeWidth={2.5} />
                </button>
              </span>
            );
          })}
          {(ultimaBusca?.gaps ?? []).map((gap) => (
            <span key={gap} className="gap-chip" title="nenhuma skill no catálogo casa — importar depois">
              {gap} <span className="v">gap</span>
            </span>
          ))}
        </div>
      )}

      <div className="flex items-center gap-2">
        <input
          type="text"
          className="flex-1 font-mono !text-[12px]"
          placeholder="ou uma skill key direta — ex.: tdd"
          value={manual}
          disabled={disabled}
          onChange={(event) => setManual(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              addManual();
            }
          }}
        />
        <button
          type="button"
          className="btn ghost"
          disabled={disabled || !manual.trim()}
          onClick={addManual}
          aria-label="adicionar skill key"
        >
          <Plus size={13} strokeWidth={2} />
        </button>
      </div>
    </div>
  );
}
