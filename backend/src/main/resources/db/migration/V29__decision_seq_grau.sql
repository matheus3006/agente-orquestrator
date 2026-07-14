-- F-C1 (Frente C, C3/C4 — emendas ADR-0019): o átomo ganha ENDEREÇO e ALCANCE.
--
-- decision.seq — o #N humano da Decisão, clone exato do V23 (ADR-0033, o #N da Issue).
-- Contrato: por company, denso (1, 2, 3... sem buraco), imutavel, nunca reusado, comeca em 1.
-- Sem endereco estavel o metodo trava em tres pontas: o CEO nao cita o que supersede (D11),
-- o fetch JIT nao tem chave, a linhagem de um principio nao tem o que citar. Alocacao = MAX+1
-- dentro da tx do record — serializa no lock da linha da company (CompanyRepository
-- .findByIdForUpdate); a unique e backstop, nao mecanismo. Backfill retroativo numera o que
-- existe na ordem (decided_at, id) — o UUID desempata timestamps identicos.
--
-- decision.grau — o eixo do ALCANCE (C3): DECISAO resolve UM caso; PRINCIPIO governa casos
-- futuros. Eixo NOVO, ortogonal ao tipo (ASSUNTO, derivado da area): um principio tecnico e
-- grau=PRINCIPIO + tipo=TECNICA — colapsar os eixos faria o Log filtravel mentir. text sem
-- check (precedente V18): o valor PRINCIPIO entra JUNTO do writer de graduacao (F-C2).

alter table decision add column seq int;

update decision
   set seq = numbered.seq
  from (select id,
               row_number() over (partition by company_id order by decided_at, id) as seq
          from decision) numbered
 where decision.id = numbered.id;

alter table decision alter column seq set not null;

alter table decision add constraint uq_decision_company_seq unique (company_id, seq);

alter table decision add column grau text not null default 'DECISAO';
