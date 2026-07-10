-- G5 (ADR-0033): issue.seq — identidade sequencial humana da Issue (#N por company).
-- Contrato: por company, denso (1, 2, 3... sem buraco), imutavel, nunca reusado, comeca em 1.
-- "#N e o que eu vejo, UUID e o id unico para auditoria e debug." Alocacao = MAX+1 dentro da
-- tx do create — seguro porque a alocacao serializa no lock da linha da company
-- (CompanyRepository.findByIdForUpdate); a unique e backstop, nao mecanismo. Backfill
-- retroativo numera o que existe na ordem (created_at, id) — o UUID desempata timestamps
-- identicos (D2).

alter table issue add column seq int;

update issue
   set seq = numbered.seq
  from (select id,
               row_number() over (partition by company_id order by created_at, id) as seq
          from issue) numbered
 where issue.id = numbered.id;

alter table issue alter column seq set not null;

alter table issue add constraint uq_issue_company_seq unique (company_id, seq);
