# ADR-0003: Relação SkillSet × KnowAll (base estático + aumento dinâmico)

**Status:** Aceito (2026-06-30)

## Contexto
O diferencial central do produto é resolver a poluição de contexto: cada Agent deve carregar **só** as Skills que precisa. Mas tarefas variam — às vezes um funcionário precisa de uma Skill que não está no seu set. O KnowAll conhece todas as Skills. Como os dois se ligam sem reintroduzir a poluição?

## Decisão
**SkillSet base estático + aumento dinâmico por-tarefa.**
- Cada Agent tem um **SkillSet base** (`agent_skill_selection`) que o dono define — é o que é materializado por padrão em cada Run.
- Numa Issue específica, o Agent (ou o dono) consulta o **KnowAll** e pode **anexar** uma Skill ao **escopo daquele Run** (não permanente). A anexação por-Run é registrada (activity log) e expira com o Run.
- KnowAll **nunca** injeta sozinho/automaticamente (isso seria o modo "totalmente dinâmico", rejeitado).

## Alternativas consideradas
- **Só estático:** previsível, mas inflexível; KnowAll viraria só consulta informativa.
- **Totalmente dinâmico:** KnowAll injeta por análise da Issue — imprevisível e arrisca re-poluir o contexto (contra o objetivo).

## Consequências
- Contexto enxuto por padrão; flexibilidade sob demanda e auditável.
- Requer modelar "Skill anexada por Run" (escopo efêmero) além do SkillSet base.
- KnowAll é tool (chamável pelo Agent) **e** persona (consultável pelo humano) — ambos só *sugerem/anexam sob pedido*.

## Revisão 2026-06-30 (grilling)
- **Escopo da anexação: por-Issue, não por-Run.** A tarefa é a Issue; "por-Run" era impreciso. A Skill anexada materializa no início de **cada Run da Issue**; o tool do KnowAll devolve o **body inline** para uso imediato no Run vivo (sem injetar no container em execução).
- **Releitura de "expira com o Run":** o **efeito** (materialização em disco) expira; o **registro** (`issue_skill_attachment`) é **imutável** e persiste para auditoria. "Expira" de fato quando a **Issue fecha**.
- **Install-on-attach + Approval:** anexar uma Skill do catálogo global ainda não instalada **instala** na biblioteca da company (copiando da base global) **e** anexa — uma ação, **dois eventos** (`SKILL_INSTALLED` + `SKILL_ATTACHED`). Install novo disparado por **agente** passa por **Approval** (invariante #4); o **board** instala direto. Anexar o que já está instalado é livre (board e agente).
- Ver ADR-0008 (KnowAll nível-operador) e ADR-0007 revisado (modelo de dados).

## Revisão 2026-07-01 (grilling 7c)
- **v1 da 7c é operador-only.** Install/attach acontece via **board REST**, apenas. As frases acima "anexam
  board **E** agente (tool)", "install novo por agente → **Approval**" e "o tool do KnowAll devolve o **body
  inline** pro agente" descrevem o **estado-alvo FUTURO**, **NÃO** o v1.
- **Razão do adiamento (o *porquê*, não "faltou tempo"):** agente-como-tool contradiz os diferenciais #1
  (re-poluição de contexto — o agente pediria N skills "por via das dúvidas") e #3 (isolamento — exigiria
  furar o container selado com rota de rede container→core + credencial run-scoped). E o efeito é **assíncrono
  de qualquer jeito** (materializa no próximo Run da Issue), então "agente pede → board aprova → efeito no
  próximo run" é funcionalmente equivalente a "operador anexa → efeito no próximo run"; solo owner = sem 2º
  humano p/ justificar o laço pede→aprova. Sem canal agente→core no v1 (`env=Map.of()`).
- **KnowAll no v1 = operator-reachable only** — o agente selado não alcança search/body inline.
- **install-on-attach do board (o que a 7c constrói):** o input do attach é a identidade **GLOBAL** (`skillId`)
  — **fold** do endpoint da 7a (`companySkillId` sai do contrato). O servidor resolve o install-state:
  **INSTALLED** → anexa a versão corrente (1 evento `SKILL_ATTACHED`); **AVAILABLE** → **instala** (copia a
  versão corrente global, `source=CATALOG`, `SKILL_INSTALLED`) **+** anexa (`SKILL_ATTACHED`), na MESMA tx.
