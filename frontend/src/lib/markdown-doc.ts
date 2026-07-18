import { micromark } from "micromark";
import { gfm, gfmHtml } from "micromark-extension-gfm";

// Emenda R29-A (ADR-0021): o entregável de pesquisa (.md) vira um documento HTML
// docs-style AUTOCONTIDO (CSS embutido, zero fetch externo), montado AQUI e
// renderizado só dentro do iframe sandbox do PreviewFrame — o invariante F.9 não
// muda. Sanitização por construção: allowDangerousHtml fica DESLIGADO no
// micromark, então HTML cru vindo do .md é escapado (script de agente vira
// texto); o único <script> do documento é o nosso, do scroll do TOC.

/** Título extraído do corpo convertido: âncora do TOC + id navegável. */
interface DocHeading {
  level: number;
  id: string;
  text: string;
}

/** md (GFM) → documento HTML completo: corpo convertido + TOC lateral + tema docs. */
export function renderMarkdownDoc(markdown: string): string {
  const body = micromark(markdown, {
    extensions: [gfm()],
    htmlExtensions: [gfmHtml()],
  });
  const { html, headings } = anchorHeadings(body);
  return [
    "<!doctype html>",
    '<html><head><meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    `<style>${DOC_CSS}</style>`,
    "</head><body>",
    '<div class="layout">',
    tocFor(headings),
    `<main class="doc">${html}</main>`,
    "</div>",
    `<script>${TOC_SCRIPT}</script>`,
    "</body></html>",
  ].join("\n");
}

/**
 * Dá id único a cada h1–h3 do corpo convertido e coleta as âncoras do TOC.
 * Regex é suficiente aqui: o HTML é SAÍDA do micromark (headings numa tag por
 * linha lógica), nunca HTML cru de agente — esse chega escapado.
 */
function anchorHeadings(body: string): { html: string; headings: DocHeading[] } {
  const headings: DocHeading[] = [];
  const seen = new Map<string, number>();
  const html = body.replace(
    /<h([1-3])>([\s\S]*?)<\/h\1>/g,
    (_tudo, nivel: string, conteudo: string) => {
      const text = conteudo.replace(/<[^>]+>/g, "").trim();
      const base = slugify(text);
      const vezes = (seen.get(base) ?? 0) + 1;
      seen.set(base, vezes);
      const id = vezes === 1 ? base : `${base}-${vezes}`;
      headings.push({ level: Number(nivel), id, text });
      return `<h${nivel} id="${id}">${conteudo}</h${nivel}>`;
    },
  );
  return { html, headings };
}

function slugify(text: string): string {
  const slug = text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return slug || "secao";
}

/** Sem título não há o que sumariar: o documento ocupa a largura toda. */
function tocFor(headings: DocHeading[]): string {
  if (headings.length === 0) {
    return "";
  }
  const items = headings
    .map(
      (h) =>
        `<li class="toc-l${h.level}"><a href="#${h.id}">${escapeHtml(h.text)}</a></li>`,
    )
    .join("");
  return `<nav class="toc" aria-label="Sumário"><p class="toc-title">Nesta página</p><ul>${items}</ul></nav>`;
}

function escapeHtml(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

// Rolagem suave sem trocar o hash: navegação por fragmento dentro de um srcdoc
// tem comportamento irregular entre browsers — scrollIntoView é determinístico.
const TOC_SCRIPT = `
document.addEventListener("click", function (event) {
  var link = event.target.closest(".toc a");
  if (!link) return;
  var alvo = document.getElementById(link.getAttribute("href").slice(1));
  if (!alvo) return;
  event.preventDefault();
  alvo.scrollIntoView({ behavior: "smooth", block: "start" });
});
`;

// Tema docs (decisão 5 do grill R29-A): coluna de leitura + TOC lateral fixo,
// claro por padrão e escuro acompanhando o sistema (prefers-color-scheme).
const DOC_CSS = `
:root {
  color-scheme: light dark;
  --bg: #ffffff;
  --fg: #1c1e21;
  --muted: #525860;
  --border: #dadde1;
  --accent: #0b6e4f;
  --code-bg: #f6f7f8;
  --zebra: #fafafa;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #1b1b1d;
    --fg: #e3e3e3;
    --muted: #a0a5ab;
    --border: #3c3f44;
    --accent: #4cc294;
    --code-bg: #26282c;
    --zebra: #202225;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--fg);
  font: 16px/1.65 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
}
.layout { display: grid; grid-template-columns: minmax(0, 1fr); }
.layout:has(.toc) { grid-template-columns: 240px minmax(0, 1fr); }
.toc {
  position: sticky;
  top: 0;
  align-self: start;
  max-height: 100vh;
  overflow-y: auto;
  padding: 24px 16px;
  border-right: 1px solid var(--border);
  font-size: 13px;
}
.toc-title {
  margin: 0 0 8px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--muted);
}
.toc ul { margin: 0; padding: 0; list-style: none; }
.toc li { margin: 2px 0; }
.toc a {
  display: block;
  padding: 3px 8px;
  border-radius: 6px;
  color: var(--muted);
  text-decoration: none;
}
.toc a:hover { color: var(--accent); background: var(--code-bg); }
.toc-l2 { padding-left: 12px; }
.toc-l3 { padding-left: 24px; }
.doc {
  max-width: 780px;
  padding: 32px 40px 64px;
  margin: 0 auto;
  width: 100%;
}
.doc h1 { font-size: 2em; line-height: 1.25; margin: 0 0 0.75em; }
.doc h2 {
  font-size: 1.4em;
  line-height: 1.3;
  margin: 1.8em 0 0.6em;
  padding-bottom: 0.25em;
  border-bottom: 1px solid var(--border);
}
.doc h3 { font-size: 1.15em; margin: 1.5em 0 0.5em; }
.doc a { color: var(--accent); }
.doc p { margin: 0 0 1em; }
.doc ul, .doc ol { padding-left: 1.4em; margin: 0 0 1em; }
.doc li { margin: 0.25em 0; }
.doc blockquote {
  margin: 0 0 1em;
  padding: 0.25em 1em;
  border-left: 3px solid var(--accent);
  color: var(--muted);
  background: var(--code-bg);
  border-radius: 0 6px 6px 0;
}
.doc code {
  font: 0.875em ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0.1em 0.35em;
}
.doc pre {
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 14px 16px;
  overflow-x: auto;
  margin: 0 0 1em;
}
.doc pre code { border: 0; padding: 0; background: none; }
.doc table {
  border-collapse: collapse;
  margin: 0 0 1em;
  display: block;
  overflow-x: auto;
  max-width: 100%;
}
.doc th, .doc td {
  border: 1px solid var(--border);
  padding: 6px 12px;
  text-align: left;
}
.doc thead th { background: var(--code-bg); }
.doc tbody tr:nth-child(even) { background: var(--zebra); }
.doc img { max-width: 100%; }
.doc hr { border: 0; border-top: 1px solid var(--border); margin: 2em 0; }
@media (max-width: 720px) {
  .layout:has(.toc) { grid-template-columns: 1fr; }
  .toc {
    position: static;
    max-height: none;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }
  .doc { padding: 24px 20px 48px; }
}
`;
