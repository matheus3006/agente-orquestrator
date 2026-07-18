#!/usr/bin/env python3
"""Harness 8 — plano LONGO e realista (o tamanho que o arquiteto real escreve).

Os planos das rodadas 1-7 tem ~10 linhas. O seam capa o texto em 20k chars
(RunFinalOutput:18) e um plano de arquiteto real chega perto disso. Se a acuracia
so existe em texto de brinquedo, a fatia falha no motor real — que e exatamente a
armadilha registrada na memoria do projeto (verify-tool-de-agente-com-motor-real).

Mede: acuracia + truncamento + latencia em plano longo, VIOLADO e LIMPO.
"""
import json
import os
import time
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = os.environ.get("ORQ_PROBE_MODEL", "qwen3:1.7b")

from probe4 import CHECKS_SEED, SYS, parse_simnao  # noqa: E402

PREAMBULO = """# Plano de implementação — fatia de cobrança recorrente

## Contexto
A company hoje cobra por lançamento avulso. O board pediu assinatura mensal com
retentativa automática e um relatório de inadimplência. O trabalho cruza três
módulos e precisa de migração de dados, então o plano abaixo é sequenciado para
que nenhuma etapa deixe o sistema num estado inconsistente entre deploys.

## Restrições herdadas
- Nenhuma mudança pode quebrar a cobrança avulsa existente, que continua ativa.
- A migração roda com a aplicação no ar; nada de janela de manutenção.
- O gateway de pagamento tem limite de 10 req/s por conta — a retentativa precisa
  respeitar isso, senão a conta inteira é bloqueada por 24h.
- Idempotência é obrigatória: o gateway pode entregar o mesmo webhook duas vezes.

## Decisões já tomadas
Optei por uma máquina de estados explícita em vez de flags booleanas espalhadas.
Booleano acumulado foi o que apodreceu o módulo de faturamento antigo: seis flags
que na prática codificavam quatro estados, com combinações impossíveis que ninguém
sabia se podiam acontecer. A máquina de estados torna o inválido irrepresentável.

O agendamento fica no worker que já existe, e não num cron novo. Um cron a mais
significaria outro lugar para observar, outro alerta para configurar e outra fonte
de duplicidade quando os dois dispararem no mesmo minuto.
"""

MEIO = """
## Etapa 2 — retentativa com backoff
A retentativa segue backoff exponencial com teto, e cada tentativa registra o
motivo da recusa devolvido pelo gateway. Recusa por saldo insuficiente merece
retentativa; recusa por cartão inválido não merece nenhuma, porque o cartão só
volta a funcionar com ação do cliente. Distinguir os dois evita queimar o limite
de requisições com tentativas que já nascem mortas.

## Etapa 3 — relatório de inadimplência
O relatório agrega por company e por faixa de atraso. A agregação roda sobre a
tabela de tentativas, não sobre a de assinaturas, porque o que interessa é o
histórico de falha e não o estado corrente.

## Riscos
O maior risco é a migração de dados: as assinaturas legadas não têm data de
aniversário explícita, e derivá-la da primeira cobrança pode deslocar o
faturamento de quem assinou no dia 31. Trato isso normalizando para o dia 28.

## Rollout
Deploy em duas fases: primeiro o schema e o código novo desligado por flag,
depois a ativação por company, começando pelas internas.
"""

TAREFAS_COM_LINHA = """
## Tarefas
1. `SubscriptionSchedule.java:88` — criar a máquina de estados da assinatura.
2. `PaymentRetryPolicy.java:34` — política de backoff com teto e classificação de recusa.
3. `GatewayWebhookController.java:57` — idempotência por chave do gateway.
4. `DelinquencyReportQuery.java:19` — agregação por faixa de atraso.
5. `V44__subscription_schedule.sql:1` — schema novo + backfill do aniversário.
"""

TAREFAS_SEM_LINHA = """
## Tarefas
1. Criar a máquina de estados da assinatura no agendador.
2. Escrever a política de backoff com teto e classificação de recusa.
3. Garantir idempotência do webhook do gateway pela chave que ele manda.
4. Montar a agregação do relatório por faixa de atraso.
5. Migrar o schema e fazer o backfill do aniversário das assinaturas legadas.
"""

SECAO_TESTE = """
## Testes
- `SubscriptionScheduleTest.java:41` — transições válidas e inválidas da máquina.
- `PaymentRetryPolicyTest.java:63` — backoff respeita o teto; cartão inválido não retenta.
- `GatewayWebhookControllerIT.java:28` — webhook duplicado não cobra duas vezes.
"""

SEM_SECAO_TESTE = """
## Observabilidade
Métrica de tentativas por status e alerta quando a taxa de recusa passar de 30%.
"""

LONGO_LIMPO = PREAMBULO + TAREFAS_COM_LINHA + MEIO + SECAO_TESTE
LONGO_VIOLADO = PREAMBULO + TAREFAS_SEM_LINHA + MEIO + SEM_SECAO_TESTE

CASOS_LONGOS = [
    # o LIMPO satisfaz as 3; o VIOLADO omite de proposito file-line E test (seam continua presente)
    ("longo-limpo", LONGO_LIMPO, {"seam": True, "file-line": True, "test": True}),
    ("longo-violado", LONGO_VIOLADO, {"seam": True, "file-line": False, "test": False}),
]


def chat(user, num_predict):
    payload = {
        "model": MODEL,
        "messages": [{"role": "system", "content": SYS}, {"role": "user", "content": user}],
        "stream": False,
        "think": True,
        "options": {"temperature": 0.0, "num_predict": num_predict},
    }
    req = urllib.request.Request(
        OLLAMA, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=600) as resp:
        body = json.loads(resp.read())
    msg = body.get("message") or {}
    return {
        "content": msg.get("content") or "",
        "done_reason": body.get("done_reason"),
        "elapsed": time.monotonic() - started,
        "prompt_eval_count": body.get("prompt_eval_count"),
    }


if __name__ == "__main__":
    print(f"\n===== PLANO LONGO ({MODEL}, think=true) =====")
    print(f"  tamanho: limpo={len(LONGO_LIMPO)} chars, violado={len(LONGO_VIOLADO)} chars")
    for num_predict in (300, 800):
        acertos = erros = indet = truncadas = 0
        tempos = []
        print(f"\n  --- num_predict={num_predict} ---")
        for plano_id, plano, verdade in CASOS_LONGOS:
            for check_id, check_text in CHECKS_SEED:
                out = chat(f"TEXTO:\n<<<\n{plano}\n>>>\n\nPERGUNTA: {check_text}", num_predict)
                tempos.append(out["elapsed"])
                if out["done_reason"] == "length":
                    truncadas += 1
                got = parse_simnao(out["content"])
                esperado = verdade[check_id]
                if got is None:
                    marca, indet = "INDET", indet + 1
                elif got == esperado:
                    marca, acertos = "ok", acertos + 1
                else:
                    marca, erros = "ERRO", erros + 1
                print(f"    [{marca:5}] {plano_id:14}/{check_id:9} "
                      f"esp={'P' if esperado else 'A'} "
                      f"obt={'P' if got is True else 'A' if got is False else '?'} "
                      f"({out['elapsed']:.1f}s, in={out['prompt_eval_count']}tok, "
                      f"{out['done_reason']})  << {out['content'].replace(chr(10), ' | ')[:50]}")
        print(f"    --> acertos {acertos}/6 | erros {erros} | indet {indet} | "
              f"truncadas {truncadas} | latencia media {sum(tempos)/len(tempos):.1f}s")
