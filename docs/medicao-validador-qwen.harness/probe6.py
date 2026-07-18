#!/usr/bin/env python3
"""Harness 6 — few-shot, o ultimo lever de prompt-engineering antes de escalar.

Diagnostico consolidado: o modelo discrimina em pergunta LEXICAL ("contem a palavra
banana?" -> NAO, pelo MESMO caminho) mas colapsa em SIM nas perguntas ESTRUTURAIS
das checks reais ("cada tarefa cita arquivo:linha?"). Few-shot com exemplos
trabalhados e a correcao classica de vies de rotulo em modelo fraco.
"""
import json
import sys
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = "qwen3:0.6b"

from probe4 import CASOS, CHECKS_CONCRETA, parse_simnao  # noqa: E402

FEWSHOT_SYS = (
    "Você confere se um texto satisfaz uma exigência. Responda apenas SIM ou NÃO.\n"
    "Seja RIGOROSO: se o texto não contiver claramente o que a pergunta pede, responda NÃO.\n"
    "Muitos textos NÃO satisfazem a exigência — responder NÃO é comum e correto."
)

# Exemplos trabalhados (texto curto -> veredito), um SIM e dois NAO para contrabalancar o vies.
FEWSHOT = [
    (
        "TEXTO:\n<<<\n# Plano\nVou mexer no login e depois testo na mão.\n>>>\n\n"
        "O texto contém alguma referência no formato arquivo:linha (por exemplo `Foo.java:42`)?",
        "NÃO",
    ),
    (
        "TEXTO:\n<<<\n# Plano\n1. `Auth.java:15` — trocar o hash.\n\n## Testes\n`AuthTest.java:9`\n>>>\n\n"
        "O texto contém alguma referência no formato arquivo:linha (por exemplo `Foo.java:42`)?",
        "SIM",
    ),
    (
        "TEXTO:\n<<<\n# Plano\n1. `Auth.java:15` — trocar o hash.\n>>>\n\n"
        "O texto contém a palavra teste, testes ou uma seção de testes?",
        "NÃO",
    ),
]


def chat(user, num_predict=200):
    messages = [{"role": "system", "content": FEWSHOT_SYS}]
    for pergunta, resposta in FEWSHOT:
        messages.append({"role": "user", "content": pergunta})
        messages.append({"role": "assistant", "content": resposta})
    messages.append({"role": "user", "content": user})
    payload = {
        "model": MODEL,
        "messages": messages,
        "stream": False,
        "think": False,
        "options": {"temperature": 0.0, "num_predict": num_predict},
    }
    req = urllib.request.Request(
        OLLAMA, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=240) as resp:
        body = json.loads(resp.read())
    return (body.get("message") or {}).get("content") or ""


def run():
    acertos = erros = indet = disse_sim = 0
    falso_neg = falso_pos = 0
    print("\n===== FEW-SHOT (3 exemplos: 1 SIM, 2 NAO) — 18 casos, taxa-base 10/18 =====")
    for plano_id, plano, verdade in CASOS:
        for check_id, check_text in CHECKS_CONCRETA:
            out = chat(f"TEXTO:\n<<<\n{plano}\n>>>\n\n{check_text}")
            got = parse_simnao(out)
            esperado = verdade[check_id]
            if got:
                disse_sim += 1
            if got is None:
                marca, indet = "INDET", indet + 1
            elif got == esperado:
                marca, acertos = "ok", acertos + 1
            else:
                marca, erros = "ERRO", erros + 1
                if esperado:
                    falso_neg += 1
                else:
                    falso_pos += 1
            print(f"  [{marca:5}] {plano_id:11}/{check_id:9} "
                  f"esp={'P' if esperado else 'A'} "
                  f"obt={'P' if got is True else 'A' if got is False else '?'}"
                  f"  << {out.replace(chr(10), ' | ')[:50]}")
    total = acertos + erros + indet
    print(f"  --> ACERTOS {acertos}/{total} (taxa-base 10/18) | erros {erros} "
          f"(FN {falso_neg}, FP {falso_pos}) | indet {indet} | disse-SIM {disse_sim}/18")


if __name__ == "__main__":
    run()
