#!/usr/bin/env python3
"""Harness 5 — controles: o culpado e o SYSTEM PROMPT ou o MODELO?

Controle A: pergunta com resposta obviamente NAO ("o texto contem a palavra banana?")
            pelo MESMO caminho (system+user, SIM/NAO). Se vier SIM, o system prompt
            colapsa o modelo e ainda ha esperanca de formato.
Controle B: o formato que FUNCIONOU na sondagem de sanidade (sem system prompt,
            SIM/NAO pedido inline) aplicado as checks REAIS.
"""
import json
import sys
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = "qwen3:0.6b"

from probe4 import CASOS, CHECKS_CONCRETA, SYS, parse_simnao  # noqa: E402
from probe import PLANO_LIMPO  # noqa: E402


def chat(system, user, num_predict=400):
    payload = {
        "model": MODEL,
        "messages": (([{"role": "system", "content": system}] if system else [])
                     + [{"role": "user", "content": user}]),
        "stream": False,
        "think": False,
        "options": {"temperature": 0.0, "num_predict": num_predict},
    }
    req = urllib.request.Request(
        OLLAMA, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=240) as resp:
        body = json.loads(resp.read())
    return (json.loads(json.dumps((body.get("message") or {}).get("content") or "")))


def controle_a():
    print("\n===== CONTROLE A — perguntas absurdas pelo MESMO caminho (system+user) =====")
    absurdas = [
        ("banana", "O texto contém a palavra banana?", False),
        ("receita bolo", "O texto é uma receita de bolo?", False),
        ("python", "O texto contém código em Python?", False),
        ("relatorio", "O texto fala de relatório?", True),
    ]
    for nome, pergunta, esperado in absurdas:
        out = chat(SYS, f"TEXTO:\n<<<\n{PLANO_LIMPO}\n>>>\n\nPERGUNTA: {pergunta}")
        got = parse_simnao(out)
        marca = "ok" if got == esperado else "ERRO"
        print(f"  [{marca:5}] {nome:14} esp={'SIM' if esperado else 'NAO'} "
              f"obt={'SIM' if got is True else 'NAO' if got is False else '?'}  << {out[:60]}")


def controle_b():
    """Sem system prompt — o formato que a sondagem de sanidade acertou."""
    print("\n===== CONTROLE B — SEM system prompt, SIM/NAO inline, checks REAIS =====")
    acertos = erros = indet = disse_sim = 0
    for plano_id, plano, verdade in CASOS:
        for check_id, check_text in CHECKS_CONCRETA:
            user = (f"TEXTO:\n<<<\n{plano}\n>>>\n\n{check_text} "
                    f"Responda SIM ou NÃO, olhando apenas o texto acima.")
            out = chat(None, user)
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
            print(f"  [{marca:5}] {plano_id:11}/{check_id:9} "
                  f"esp={'P' if esperado else 'A'} "
                  f"obt={'P' if got is True else 'A' if got is False else '?'}"
                  f"  << {out.replace(chr(10), ' | ')[:55]}")
    total = acertos + erros + indet
    print(f"  --> ACERTOS {acertos}/{total} (taxa-base 10/18) | erros {erros} | "
          f"indet {indet} | disse-SIM {disse_sim}/18")


if __name__ == "__main__":
    alvo = sys.argv[1] if len(sys.argv) > 1 else "ambos"
    if alvo in ("ambos", "a"):
        controle_a()
    if alvo in ("ambos", "b"):
        controle_b()
