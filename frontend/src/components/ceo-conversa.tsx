"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BookOpenText } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { ApprovalThreadCard } from "@/components/approval-thread-card";
import { useCompanyNav } from "@/components/company-link";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import {
  ApiError,
  getCarta,
  listApprovals,
  listApprovalsAnchoredTo,
  listConversations,
  listTurns,
  openConversation,
  postCeoMessage,
  type ConversationTurn,
  type IssueSummary,
} from "@/lib/api";
import {
  chavesDaThread,
  entrandoNaThread,
  pendentesPorConversa,
  projetarThread,
  splitReply,
} from "@/lib/ceo-conversa";
import { fmtClock } from "@/lib/format";
import { glossa } from "@/lib/glossario";
import { queryKeys } from "@/lib/query-keys";
import { cn } from "@/lib/utils";

// A coluna DOCADA do CEO (T.3-FE, mockup §§1–5): chats nomeados por assunto
// como abas (mesmo CEO — muda o assunto, não o interlocutor), thread com
// approvals ancoradas e o ÚNICO composer do board (cadeia de comando imposta
// pela UI). Transporte = polling de 2s, o mesmo ritmo da Central: o POST de
// mensagem responde 202 e a resposta do CEO chega quando o Run liquidar.

export function CeoConversa({
  companyId,
  ceoNome,
  issues,
  onOpenTrace,
}: {
  companyId: string | undefined;
  ceoNome: string | null;
  issues: IssueSummary[];
  onOpenTrace: (runId: string) => void;
}) {
  const searchParams = useSearchParams();
  const { replace } = useCompanyNav();

  const conversasQuery = useQuery({
    queryKey: queryKeys.conversations(companyId ?? ""),
    queryFn: () => listConversations(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const pendentesQuery = useQuery({
    queryKey: queryKeys.approvalsQueue(companyId ?? "", "PENDING"),
    queryFn: () => listApprovals(companyId as string, "PENDING"),
    enabled: !!companyId,
    refetchInterval: 2000,
    refetchIntervalInBackground: false,
  });

  const conversas = useMemo(() => conversasQuery.data ?? [], [conversasQuery.data]);
  const badges = pendentesPorConversa(pendentesQuery.data ?? []);

  // Deep-link: ?chat=<id> vence; sem (ou inválido), a primeira conversa.
  const chatParam = searchParams.get("chat");
  const ativa = conversas.find((conversa) => conversa.id === chatParam) ?? conversas[0] ?? null;

  const trocarDeChat = (conversationId: string) => {
    replace(`/?chat=${conversationId}`, { scroll: false });
  };

  return (
    <>
      <div className="ceo-abas" role="tablist" aria-label="Chats com o CEO">
        {conversas.map((conversa) => {
          const pendentes = badges.get(conversa.id) ?? 0;
          const ativa_ = ativa?.id === conversa.id;
          return (
            <button
              key={conversa.id}
              type="button"
              role="tab"
              aria-selected={ativa_}
              className="ceo-aba"
              data-active={ativa_}
              title={conversa.subject}
              onClick={() => trocarDeChat(conversa.id)}
            >
              {conversa.subject}
              {pendentes > 0 && (
                <span className="ceo-aba-badge" aria-label={`${pendentes} aprovações pendentes`}>
                  {pendentes}
                </span>
              )}
            </button>
          );
        })}
        <NovoChatDialog companyId={companyId} onAberta={trocarDeChat} />
        <span className="ceo-carta">
          <CartaDialog companyId={companyId} />
        </span>
      </div>

      {ativa ? (
        <ThreadDoChat
          key={ativa.id}
          companyId={companyId as string}
          conversationId={ativa.id}
          subject={ativa.subject}
          ceoNome={ceoNome}
          issues={issues}
          onOpenTrace={onOpenTrace}
        />
      ) : (
        <div className="ceo-thread">
          <div className="sys-linha">
            <span>— sem chats ainda —</span>
          </div>
          <EmptyState
            compact
            tone={conversasQuery.isError ? "erro" : undefined}
            aria-busy={conversasQuery.isPending || undefined}
          >
            {conversasQuery.isPending
              ? "carregando chats…"
              : conversasQuery.isError
                ? "não deu para falar com o backend — o poll segue tentando"
                : "Abra o primeiro assunto no “+” acima — o board fala só com o CEO."}
          </EmptyState>
        </div>
      )}

      <Composer
        companyId={companyId}
        conversationId={ativa?.id ?? null}
        temCeo={ceoNome !== null}
      />
    </>
  );
}

/* ---------- a thread de UM assunto (remonta por chat: troca é instantânea) ---------- */

function ThreadDoChat({
  companyId,
  conversationId,
  subject,
  ceoNome,
  issues,
  onOpenTrace,
}: {
  companyId: string;
  conversationId: string;
  subject: string;
  ceoNome: string | null;
  issues: IssueSummary[];
  onOpenTrace: (runId: string) => void;
}) {
  const turnosQuery = useQuery({
    queryKey: queryKeys.conversationTurns(companyId, conversationId),
    queryFn: () => listTurns(companyId, conversationId),
    refetchInterval: 2000,
    refetchIntervalInBackground: false,
  });
  const ancoradasQuery = useQuery({
    queryKey: queryKeys.approvalsAnchored(companyId, conversationId),
    queryFn: () => listApprovalsAnchoredTo(companyId, conversationId),
    refetchInterval: 2000,
    refetchIntervalInBackground: false,
  });

  const itens = useMemo(
    () => projetarThread(turnosQuery.data ?? [], ancoradasQuery.data ?? []),
    [turnosQuery.data, ancoradasQuery.data],
  );

  // Só o que é APPENDADO pelo polling anima (espelho do MiniTerm): a primeira
  // pintura COMPLETA (turnos + ancoradas resolvidos) congela as chaves — carga
  // inicial e troca de aba não re-animam a história.
  const chaves = chavesDaThread(itens);
  // Congela na 1ª pintura completa e nunca mais. O setState no corpo do render é o
  // padrão sancionado do React ("ajustar estado durante o render"): re-renderiza
  // ANTES do paint, então `chavesIniciais` já existe no mesmo render que decide
  // `entrando` — um useEffect chegaria só depois do paint e animaria a carga inicial.
  const [chavesIniciais, congelarChaves] = useState<Set<string> | null>(null);
  if (chavesIniciais === null && turnosQuery.data && ancoradasQuery.data) {
    congelarChaves(new Set(chaves));
  }
  // A DECISÃO de quem anima é pura (entrandoNaThread); só o ESTADO — congelar na 1ª
  // pintura — fica aqui. Antes de congelar (null) nada anima; depois, só o que o
  // polling APENDOU (chave fora do congelado) recebe `entrando`.
  const entrandoPorItem = entrandoNaThread(chaves, chavesIniciais);

  const scrollRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [itens.length]);

  const iniciais = (nome: string | null) =>
    (nome ?? "CEO").replaceAll(/[^\p{L}\d]/gu, "").slice(0, 2).toUpperCase() || "CE";

  return (
    <div className="ceo-thread" ref={scrollRef} aria-live="polite">
      <div className="sys-linha">
        <span>— {subject} —</span>
      </div>

      {turnosQuery.isPending && (
        <EmptyState compact aria-busy="true">
          carregando a thread…
        </EmptyState>
      )}

      {turnosQuery.data && itens.length === 0 && (
        <EmptyState compact>
          Assunto novo. Dirija o CEO — peça um time, uma issue, uma direção; ele propõe e{" "}
          <b>você aprova aqui na thread</b>.
        </EmptyState>
      )}

      {itens.map((item, index) => {
        const entrando = entrandoPorItem[index];
        if (item.kind === "approval") {
          return (
            <div key={chaves[index]} className={cn(entrando && "msg entrando")}>
              <ApprovalThreadCard
                companyId={companyId}
                approval={item.approval}
                issues={issues}
                onOpenTrace={onOpenTrace}
              />
            </div>
          );
        }
        return (
          <Turno
            key={chaves[index]}
            turno={item.turno}
            ceoIniciais={iniciais(ceoNome)}
            entrando={entrando}
            onOpenTrace={onOpenTrace}
          />
        );
      })}
    </div>
  );
}

function Turno({
  turno,
  ceoIniciais,
  entrando,
  onOpenTrace,
}: {
  turno: ConversationTurn;
  ceoIniciais: string;
  entrando: boolean;
  onOpenTrace: (runId: string) => void;
}) {
  const reply = turno.ceoReply !== null ? splitReply(turno.ceoReply) : null;

  return (
    <>
      <div className={cn("msg minha", entrando && "entrando")}>
        <div className="msg-quem" aria-hidden>
          VC
        </div>
        <div className="balao">
          {turno.ownerMessage}
          <div className="balao-pe">
            <span className="balao-hora">você · {fmtClock(turno.createdAt)}</span>
          </div>
        </div>
      </div>

      {turno.status === "PENDING" && (
        <div className={cn("msg", entrando && "entrando")}>
          <div className="msg-quem" aria-hidden>
            {ceoIniciais}
          </div>
          <div className="balao">
            <span className="pensando" aria-label="CEO preparando a resposta">
              <i /> <i /> <i />
            </span>
            <div className="balao-pe">
              <span className="balao-hora">o CEO responde pelo polling — ~1 turno = 1 run</span>
            </div>
          </div>
        </div>
      )}

      {turno.status === "FAILED" && (
        <div className={cn("msg falhou", entrando && "entrando")}>
          <div className="msg-quem" aria-hidden>
            {ceoIniciais}
          </div>
          <div className="balao">
            O turno falhou — o run não liquidou. O resto da empresa segue rodando; reenvie a
            mensagem ou investigue o trace.
            <div className="balao-pe">
              <span className="balao-hora">falha · {fmtClock(turno.createdAt)}</span>
              {turno.runId && (
                <button
                  type="button"
                  className="link-trace"
                  onClick={() => onOpenTrace(turno.runId as string)}
                >
                  ▸ trace do run
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {turno.status === "ANSWERED" && reply && (
        <div className={cn("msg", entrando && "entrando")}>
          <div className="msg-quem" aria-hidden>
            {ceoIniciais}
          </div>
          <div className="balao">
            {reply.prosa || (reply.temProposta ? "Proposta encaminhada pro gate." : "")}
            {reply.temProposta && (
              <div className="balao-pe">
                <span className="balao-proposta">◆ proposta enviada — o card de aprovação ancora abaixo</span>
              </div>
            )}
            <div className="balao-pe">
              <span className="balao-hora">
                CEO · {fmtClock(turno.answeredAt ?? turno.createdAt)}
              </span>
              {turno.runId && (
                <button
                  type="button"
                  className="link-trace"
                  onClick={() => onOpenTrace(turno.runId as string)}
                >
                  ▸ trace
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/* ---------- composer: o ÚNICO lugar onde o board escreve (mockup §2) ---------- */

function Composer({
  companyId,
  conversationId,
  temCeo,
}: {
  companyId: string | undefined;
  conversationId: string | null;
  temCeo: boolean;
}) {
  const [mensagem, setMensagem] = useState("");
  const [falha, setFalha] = useState<string | null>(null);
  const areaRef = useRef<HTMLTextAreaElement>(null);
  const queryClient = useQueryClient();

  const envio = useMutation({
    mutationFn: () =>
      postCeoMessage(companyId as string, conversationId as string, mensagem.trim()),
    onSuccess: () => {
      setMensagem("");
      setFalha(null);
      if (areaRef.current) {
        areaRef.current.style.height = "44px";
      }
      // O 202 já criou o turno PENDING — invalidar mostra a mensagem na hora;
      // a resposta do CEO chega pelo poll quando o run liquidar (~1s).
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversationTurns(companyId as string, conversationId as string),
      });
    },
    onError: (error: unknown) => {
      setFalha(error instanceof ApiError ? error.message : "não deu para falar com o backend");
    },
  });

  const podeEnviar =
    !!companyId && !!conversationId && mensagem.trim().length > 0 && !envio.isPending;

  const enviar = () => {
    if (podeEnviar) {
      envio.mutate();
    }
  };

  const crescer = (el: HTMLTextAreaElement) => {
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`;
  };

  return (
    <div className="composer">
      <div className="composer-linha">
        <textarea
          ref={areaRef}
          id="composer-do-ceo"
          value={mensagem}
          maxLength={8000}
          placeholder={
            temCeo
              ? "fale com o CEO — dirija, aprove, peça um time…"
              : "sem CEO contratado — contrate um agente com role “ceo” primeiro"
          }
          aria-label="Mensagem para o CEO"
          disabled={!conversationId || !temCeo}
          onChange={(event) => {
            setMensagem(event.target.value);
            crescer(event.target);
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              enviar();
            }
          }}
        />
        <button
          type="button"
          className="composer-enviar"
          title="enviar (Enter)"
          aria-label="Enviar mensagem"
          disabled={!podeEnviar}
          onClick={enviar}
        >
          ↑
        </button>
      </div>
      {falha && <p className="m-0 mt-2 text-[12px] text-coral">{falha}</p>}
      <div className="composer-hint">
        a caixa existe só aqui · os cards à esquerda são somente-leitura (cadeia de comando) · ↵
        envia
      </div>
    </div>
  );
}

/* ---------- "+": novo chat por assunto (409 = assunto duplicado) ---------- */

function NovoChatDialog({
  companyId,
  onAberta,
}: {
  companyId: string | undefined;
  onAberta: (conversationId: string) => void;
}) {
  const [aberto, setAberto] = useState(false);
  const [assunto, setAssunto] = useState("");
  const queryClient = useQueryClient();

  const criacao = useMutation({
    mutationFn: () => openConversation(companyId as string, assunto.trim()),
    onSuccess: async (criada) => {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.conversations(companyId as string),
      });
      setAberto(false);
      setAssunto("");
      onAberta(criada.id);
    },
  });

  const falha =
    criacao.error instanceof ApiError
      ? criacao.error.status === 409
        ? "já existe um chat com esse assunto — os chats separam ASSUNTOS, não interlocutores"
        : criacao.error.message
      : criacao.isError
        ? "não deu para falar com o backend"
        : null;

  return (
    <Dialog
      open={aberto}
      onOpenChange={(proximo) => {
        setAberto(proximo);
        criacao.reset();
        if (!proximo) {
          setAssunto("");
        }
      }}
    >
      <button
        type="button"
        id="novo-chat-do-ceo"
        className="ceo-aba"
        title="novo chat por assunto"
        aria-label="Novo chat por assunto"
        onClick={() => setAberto(true)}
        disabled={!companyId}
      >
        +
      </button>
      <DialogContent aria-describedby={undefined}>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!criacao.isPending && assunto.trim()) {
              criacao.mutate();
            }
          }}
        >
          <div className="dialog-body">
            <DialogTitle className="dialog-title">Novo chat por assunto</DialogTitle>
            <p className="dialog-text">
              Todos os chats falam com o <b>mesmo CEO</b> — muda o assunto, não o interlocutor. As
              aprovações caem no chat do assunto certo.
            </p>
            <input
              type="text"
              maxLength={120}
              placeholder="assunto — ex.: Desenvolvimento, Marketing"
              value={assunto}
              onChange={(event) => setAssunto(event.target.value)}
              autoFocus
            />
            {falha && <p className="m-0 text-[12.5px] text-coral">{falha}</p>}
          </div>
          <div className="dialog-actions">
            <DialogClose className="btn ghost" type="button">
              Cancelar
            </DialogClose>
            <button
              className="btn primary"
              type="submit"
              disabled={criacao.isPending || !assunto.trim()}
            >
              {criacao.isPending ? "Abrindo…" : "Abrir chat"}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/* ---------- a carta (ADR-0019): a MESMA projeção que o CEO carrega ---------- */

function CartaDialog({ companyId }: { companyId: string | undefined }) {
  const [aberto, setAberto] = useState(false);

  const cartaQuery = useQuery({
    queryKey: queryKeys.carta(companyId ?? ""),
    queryFn: () => getCarta(companyId as string),
    enabled: aberto && !!companyId,
    staleTime: 30_000,
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  });

  const semCarta = cartaQuery.error instanceof ApiError && cartaQuery.error.status === 404;

  return (
    <>
      <button
        type="button"
        className="btn ghost"
        style={{ padding: "4px 10px", fontSize: "11px" }}
        onClick={() => setAberto(true)}
        disabled={!companyId}
        title={glossa("carta")}
      >
        <BookOpenText size={12} strokeWidth={2} /> carta
      </button>
      <Dialog open={aberto} onOpenChange={setAberto}>
        <DialogContent aria-describedby={undefined} className="max-w-[640px]">
          <div className="dialog-body">
            <DialogTitle className="dialog-title">
              Carta da empresa{cartaQuery.data ? ` — v${cartaQuery.data.version}` : ""}
            </DialogTitle>
            <p className="dialog-text">
              Projeção <b>determinística</b> das decisões de direção vigentes (zero LLM) — o CEO
              carrega exatamente isto como <span className="font-mono">.claude/CLAUDE.md</span> a
              cada run.
            </p>
            {cartaQuery.data && <div className="memoria-corpo">{cartaQuery.data.body}</div>}
            {semCarta && (
              <p className="dialog-text">
                Ainda não há carta — ela nasce da primeira <b>decisão de direção</b> aprovada no
                gate.
              </p>
            )}
            {cartaQuery.isPending && aberto && (
              <div className="h-24 animate-pulse rounded-lg bg-raised" aria-busy="true" />
            )}
            {cartaQuery.isError && !semCarta && (
              <p className="dialog-text text-coral">
                não deu para carregar a carta — feche e tente de novo
              </p>
            )}
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
