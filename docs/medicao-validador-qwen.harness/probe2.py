#!/usr/bin/env python3
"""Harness 2 — o veredito sai do CODIGO, nao do modelo.

Diagnostico da rodada 1: o qwen3:0.6b respondeu PRESENTE em 9/9 (com e sem think);
5/9 de "acerto" e exatamente a taxa-base do conjunto => ZERO poder discriminativo.
Mas a EVIDENCIA que ele produz costuma estar certa (em magro/file-line ele escreveu
"nao ha trecho que mencione uma linha especifica" e AINDA assim rotulou PRESENTE).

Hipotese: o token do rotulo carrega vies de afirmacao; a extracao nao.
Entao o modelo so EXTRAI (trabalho braçal) e o nucleo deterministico JULGA:
present = a citacao existe E aparece MESMO no plano (substring verificada).
Isso alinha com o §0 do desenho (local nunca julga) e torna o vies inofensivo.
"""
import json
import re
import sys
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = "qwen3:0.6b"

from probe import CASOS, CHECKS  # noqa: E402  (mesmos planos e mesmas checks reais)


def chat(system, user, think, num_predict):
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "stream": False,
        "think": think,
        "options": {"temperature": 0.0, "num_predict": num_predict},
    }
    req = urllib.request.Request(
        OLLAMA, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=180) as resp:
        body = json.loads(resp.read())
    msg = body.get("message") or {}
    return {"content": msg.get("content") or "", "done_reason": body.get("done_reason")}


# ---------- v4: EXTRACAO (o modelo cita; o codigo julga) ----------
V4_SYS = (
    "Você é um EXTRATOR mecânico. Recebe um texto e uma exigência.\n"
    "Sua única tarefa: COPIAR do texto, literalmente, o trecho que satisfaz a exigência.\n"
    "- Se existir, responda apenas o trecho copiado, palavra por palavra.\n"
    "- Se NÃO existir nenhum trecho que satisfaça, responda exatamente: NADA\n"
    "Não explique. Não opine. Não invente texto que não esteja lá."
)


def v4_user(plano, check_text):
    return (
        f"TEXTO:\n<<<\n{plano}\n>>>\n\n"
        f"EXIGÊNCIA: {check_text}\n\n"
        "Copie o trecho do TEXTO que satisfaz a exigência, ou responda NADA."
    )


# ---------- v5: controle de polaridade (o vies inverte?) ----------
V5_SYS = (
    "Você confere a FALTA de uma exigência num texto.\n"
    "Responda 1 linha: VEREDITO: FALTA  ou  VEREDITO: TEM"
)


def v5_user(plano, check_text):
    return f"TEXTO:\n<<<\n{plano}\n>>>\n\nEXIGÊNCIA: {check_text}\n\nA exigência FALTA no texto?"


def normalize(text):
    text = text.lower().replace("`", " ").replace('"', " ").replace("'", " ")
    text = text.replace("*", " ").replace("—", " ").replace("-", " ")
    return re.sub(r"\s+", " ", text).strip()


MIN_QUOTE = 12


def judge_extraction(content, plano):
    """None = INDETERMINADO. Senao: present = citou E a citacao esta MESMO no plano."""
    quote = (content or "").strip()
    if not quote:
        return None, "vazio"
    if quote.upper().startswith("NADA") or quote.upper() == "NADA":
        return False, "modelo disse NADA"
    quote = quote.strip('"').strip("`").strip()
    n_quote, n_plano = normalize(quote), normalize(plano)
    if len(n_quote) < MIN_QUOTE:
        return None, f"citacao curta demais ({len(n_quote)})"
    if n_quote in n_plano:
        return True, "citacao VERIFICADA no plano"
    # tolera citacao truncada/parafraseada: tenta o maior prefixo verificavel
    for corte in (80, 60, 40, 24, MIN_QUOTE):
        if len(n_quote) >= corte and n_quote[:corte] in n_plano:
            return True, f"prefixo {corte} verificado"
    return None, "citacao NAO confere com o plano (alucinada)"


def parse_polarity(content):
    upper = (content or "").upper()
    if "FALTA" in upper and "TEM" not in upper:
        return False
    if "TEM" in upper and "FALTA" not in upper:
        return True
    for line in upper.splitlines():
        if "VEREDITO" in line:
            if "FALTA" in line:
                return False
            if "TEM" in line:
                return True
    return None


def run(name):
    acertos = erros = indet = 0
    falso_neg = falso_pos = 0
    print(f"\n===== {name} =====")
    for plano_id, plano, verdade in CASOS:
        for check_id, check_text in CHECKS:
            if name == "v4-extracao":
                out = chat(V4_SYS, v4_user(plano, check_text), False, 300)
                got, motivo = judge_extraction(out["content"], plano)
            else:
                out = chat(V5_SYS, v5_user(plano, check_text), False, 200)
                got, motivo = parse_polarity(out["content"]), "polaridade"
            esperado = verdade[check_id]
            if got is None:
                veredito, indet = "INDETERMINADO", indet + 1
            elif got == esperado:
                veredito, acertos = "ok", acertos + 1
            else:
                veredito, erros = "ERRO", erros + 1
                if esperado:
                    falso_neg += 1
                else:
                    falso_pos += 1
            snippet = out["content"].replace("\n", " | ")[:80]
            print(
                f"  [{veredito:13}] {plano_id:8} / {check_id:9} "
                f"esperado={'P' if esperado else 'A'} obtido="
                f"{'P' if got is True else 'A' if got is False else '?'} "
                f"({motivo})  << {snippet}"
            )
    total = acertos + erros + indet
    print(
        f"  --> acertos {acertos}/{total} | erros {erros} "
        f"(falso-negativo {falso_neg}, falso-positivo {falso_pos}) | indeterminado {indet}"
    )


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "v4-extracao")
