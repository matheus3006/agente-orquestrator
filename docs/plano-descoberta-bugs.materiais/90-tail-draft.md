## 6. Esqueleto do roadmap de ajustes (formato de entrada dos achados)

**Pendência de reconciliação (registrada):** a seção "Roadmap de ajustes" em
`docs/roadmap-interno.md` é carona docs da **R22-A** (em voo; o kickoff dela manda abri-la com o
item #1 + achados §6). No momento desta escrita ela **ainda não existe** no roadmap — o formato
abaixo segue o desenho do conserto (`HANDOFF-prova-final-v1.md` §4) como referência. Quando a
R22-A landar, o fecho da fase reconcilia este esqueleto com a seção real (quem chega primeiro
define a numeração; o item #1 é dela).

Cada achado de bateria entra assim (uma linha por achado, espelho do item #1):

| # | Achado (1 frase: o que NÃO funciona) | Afirmação violada (âncora ADR:linha, ID do §2) | Onde achou (bateria/passo) | Evidência (o dado observado: SQL/UI/log, com IDs) | Gravidade | Fronteira (par produtor×consumidor) | Conserto (se já desenhado; senão "grill pendente") | Estado |
|---|---|---|---|---|---|---|---|---|
| 1 | Organização contratada não trabalha; issue vira DONE fantasma | 0037:… ("issue é conversa direcionada" → instrui) · 0004/0005 (run executa trabalho) | pré-fase (R21-A, perna g) | run `e67f f7dd` 58,6ms / 3 eventos / zero motor; #1 DONE | 🔴 | gate (0018) × adapter (0004) × settle (0005/0011) | **grelhado** (§4 da prova: compositor + trava no-op + V30) | R22-A EM VOO |
| 2… | *(próximos achados das baterias)* | | | | | | | |

Regras de entrada (do método):
- **Gravidade:** 🔴 = mente para o dono ou perde trabalho (board mente, DONE falso, dado sumido) ·
  🟠 = funcionalidade prometida não acontece, com contorno · 🟡 = superfície/UX/registro errado ·
  ⚪ = cosmético.
- Achado SEM afirmação de ADR correspondente = lacuna de ESPECIFICAÇÃO — entra mesmo assim, com a
  coluna "afirmação violada" = "(não especificado — candidato a ADR/emenda)".
- Achado que confirma candidata v1.1 existente: referencia o nº dela (roadmap §Candidatas) em vez
  de duplicar.
- "Funciona" TAMBÉM se registra: o passo de bateria que passou vira ✅ no inventário (§2), com a
  citação da bateria — é o que impede re-teste eterno.

## 7. Honestidade de cobertura

*(preenchido na síntese — lista de afirmações ❓ N/C com o porquê, ADRs com extração suspeita
sinalizada pelo crítico, e limites conhecidos do plano)*

## 8. Handoff de volta (contrato do ledger)

- **Branch/PR:** nenhum — faixa de planejamento, ZERO código/commit/branch (contrato honrado).
- **Arquivos tocados:** somente `docs/plano-descoberta-bugs.md` (novo, untracked — este arquivo).
  Working tree do repo intocada no resto; os 4 untracked de contrato preservados.
- **Vxx consumida:** nenhuma (V30 segue reservada à R22-A; V31+/ADR-0043+ livres).
- **Contagem de testes:** não medida nesta faixa (nenhum código tocado; piso vigente 738/294 na
  `83c5fe9`, medido pela sessão de controle 2026-07-16).
- **O que ficou por fazer:** (a) emissão das baterias como faixas próprias (controle; Bateria 1
  pronta no §5); (b) reconciliação do esqueleto §6 com a seção "Roadmap de ajustes" que a R22-A
  abre no roadmap; (c) baterias ⛔ BUG#1 aguardam merge da R22-A.
- **Decisões novas no caminho:** *(preenchido no fecho — ver §Decisões desta faixa no fim)*
