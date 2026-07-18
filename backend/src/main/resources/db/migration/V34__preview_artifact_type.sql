-- R29-A (emenda ADR-0021): o preview_artifact passa a carregar o TIPO do conteudo e a
-- PROVENIENCIA. O contrato de lugar agora captura tambem o entregavel de pesquisa
-- (.orq/preview/index.md, ou o UNICO *.md quando a convencao nao foi seguida) — a tela converte
-- markdown em HTML docs-style na renderizacao; o banco guarda a foto FIEL do que o agente
-- escreveu. Backfill 100% verdadeiro: toda linha pre-V34 nasceu do index.html (o capturador
-- nunca leu outro arquivo).

alter table preview_artifact add column content_type text not null default 'html';
alter table preview_artifact add column source_filename text not null default 'index.html';

alter table preview_artifact add constraint ck_preview_artifact_content_type
    check (content_type in ('html', 'markdown'));

-- Append-only: quem grava e sempre o capturador, com valores explicitos — sem default
-- silencioso mascarando um write incompleto.
alter table preview_artifact alter column content_type drop default;
alter table preview_artifact alter column source_filename drop default;
