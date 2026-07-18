#!/usr/bin/env python3
"""Harness 7 — parametros de PRODUCAO da config vencedora.

Vencedora medida: qwen3:1.7b + redacao do SEED + think=true => 17/18 (1 FN, 0 FP).
Mas think=true e exatamente o gotcha do kickoff (o raciocinio come o orcamento de
tokens e a resposta vem VAZIA). Aqui se mede:
  (a) estabilidade   — o placar repete? (temperature 0)
  (b) num_predict    — a partir de que orcamento a resposta para de truncar
  (c) latencia       — quanto custa por check (o seam e async/best-effort, mas conta)
"""
import json
import os
import time
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = os.environ.get("ORQ_PROBE_MODEL", "qwen3:1.7b")

from probe4 import CASOS, CHECKS_SEED, SYS, parse_simnao  # noqa: E402


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
    with urllib.request.urlopen(req, timeout=300) as resp:
        body = json.loads(resp.read())
    elapsed = time.monotonic() - started
    msg = body.get("message") or {}
    return {
        "content": msg.get("content") or "",
        "thinking": msg.get("thinking") or "",
        "done_reason": body.get("done_reason"),
        "elapsed": elapsed,
        "eval_count": body.get("eval_count"),
    }


def orcamento():
    """Quantos tokens o raciocinio come, e onde a resposta para de vir vazia."""
    print(f"\n===== ORCAMENTO DE TOKENS ({MODEL}, think=true) =====")
    print("  num_predict | vazias/18 | truncadas/18 | acertos | latencia media")
    for num_predict in (150, 300, 600, 1200):
        vazias = truncadas = acertos = erros = indet = 0
        tempos = []
        for plano_id, plano, verdade in CASOS:
            for check_id, check_text in CHECKS_SEED:
                out = chat(f"TEXTO:\n<<<\n{plano}\n>>>\n\nPERGUNTA: {check_text}", num_predict)
                tempos.append(out["elapsed"])
                if not out["content"].strip():
                    vazias += 1
                if out["done_reason"] == "length":
                    truncadas += 1
                got = parse_simnao(out["content"])
                if got is None:
                    indet += 1
                elif got == verdade[check_id]:
                    acertos += 1
                else:
                    erros += 1
        media = sum(tempos) / len(tempos)
        print(f"  {num_predict:11} | {vazias:9} | {truncadas:12} | "
              f"{acertos:2}/18 (err {erros}, indet {indet}) | {media:.2f}s")


def estabilidade(num_predict=1200, rodadas=2):
    """temperature=0 deveria ser deterministico — confirma que o placar repete."""
    print(f"\n===== ESTABILIDADE ({MODEL}, num_predict={num_predict}, {rodadas} rodadas) =====")
    for rodada in range(1, rodadas + 1):
        acertos = erros = indet = 0
        for plano_id, plano, verdade in CASOS:
            for check_id, check_text in CHECKS_SEED:
                out = chat(f"TEXTO:\n<<<\n{plano}\n>>>\n\nPERGUNTA: {check_text}", num_predict)
                got = parse_simnao(out["content"])
                if got is None:
                    indet += 1
                elif got == verdade[check_id]:
                    acertos += 1
                else:
                    erros += 1
        print(f"  rodada {rodada}: acertos {acertos}/18 (erros {erros}, indeterminados {indet})")


if __name__ == "__main__":
    import sys
    alvo = sys.argv[1] if len(sys.argv) > 1 else "orcamento"
    if alvo == "orcamento":
        orcamento()
    else:
        estabilidade()
