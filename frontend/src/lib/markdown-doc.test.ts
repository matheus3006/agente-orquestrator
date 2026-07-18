import { describe, expect, it } from "vitest";
import { renderMarkdownDoc } from "./markdown-doc";

// Emenda R29-A (ADR-0021): o entregável de pesquisa (.md) vira um documento HTML
// docs-style AUTOCONTIDO, montado no front e renderizado SÓ dentro do iframe
// sandbox do PreviewFrame. Sanitização por construção: HTML cru do .md é escapado
// pelo conversor (micromark, allowDangerousHtml desligado) — <script> vira texto.

describe("renderMarkdownDoc", () => {
  it("converte markdown GFM em documento HTML completo e autocontido", () => {
    const html = renderMarkdownDoc(
      "# Elasticidade\n\n| corte | perda |\n|---|---|\n| 10% | 2% |\n",
    );

    expect(html).toContain("<!doctype html>");
    expect(html).toContain("Elasticidade</h1>");
    expect(html).toContain("<table>");
    expect(html).toContain("<td>10%</td>");
    // autocontido: estilo embutido, nenhum fetch externo
    expect(html).toContain("<style>");
    expect(html).not.toMatch(/(href|src)="https?:\/\//);
  });

  it("escapa HTML cru do markdown — script de agente vira texto, nunca execução", () => {
    const html = renderMarkdownDoc('# T\n\n<script>alert("x")</script>\n');

    expect(html).toContain("&lt;script&gt;");
    expect(html).not.toContain('<script>alert("x")</script>');
  });

  it("dá id aos títulos h1–h3 e monta o TOC lateral com âncoras", () => {
    const html = renderMarkdownDoc(
      "# Pesquisa de preço\n\n## Método\n\ntexto\n\n### Amostra\n\nmais texto\n",
    );

    expect(html).toContain('<h1 id="pesquisa-de-preco">');
    expect(html).toContain('<h2 id="metodo">');
    expect(html).toContain('<h3 id="amostra">');
    expect(html).toContain('<nav class="toc"');
    expect(html).toContain('href="#metodo"');
    expect(html).toContain('href="#amostra"');
  });

  it("desambigua títulos repetidos com sufixo numérico", () => {
    const html = renderMarkdownDoc("## Plano\n\na\n\n## Plano\n\nb\n");

    expect(html).toContain('<h2 id="plano">');
    expect(html).toContain('<h2 id="plano-2">');
  });

  it("documento sem títulos não ganha TOC", () => {
    const html = renderMarkdownDoc("só um parágrafo solto\n");

    expect(html).not.toContain('<nav class="toc"');
    expect(html).toContain("só um parágrafo solto");
  });

  it("tema acompanha o sistema: claro por padrão com variante escura", () => {
    const html = renderMarkdownDoc("# T\n");

    expect(html).toContain("prefers-color-scheme: dark");
  });
});
