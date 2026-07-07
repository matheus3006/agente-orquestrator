import Link from "next/link";

// 404 ESCOPADO do segmento de company: recebe notFound() lançado pelas PÁGINAS
// internas (issue/agent inexistente nas fatias F.4/F.6). O slug inválido em si
// resolve no not-found da RAIZ (notFound() de layout sobe ao segmento pai).
// Renderiza DENTRO do <main> do shell (F.2) — a sidebar continua de pé.
export default function CompanyResourceNotFound() {
  return (
    <div className="grid min-h-full place-items-center p-8">
      <div className="max-w-md rounded-lg border border-dashed border-line-hot bg-raised p-10 text-center">
        <p className="font-mono text-[11px] uppercase tracking-[0.16em] text-coral">
          404 · não encontrado
        </p>
        <h1 className="mt-3 font-display text-xl font-semibold tracking-wide">
          Esse recurso não existe nesta company
        </h1>
        <p className="mt-2 text-[13px] text-dim">
          A issue, run ou agente que você procurou não está aqui (ou pertence a outra company).
        </p>
        <Link
          href="/"
          className="mt-6 inline-block rounded-md border border-line-hot bg-overlay px-4 py-2 text-[13px] font-medium transition-colors hover:border-phosphor-dim hover:text-phosphor"
        >
          ← Voltar
        </Link>
      </div>
    </div>
  );
}
