#!/usr/bin/env python3
"""Harness 3 — sanidade + a pergunta NATURAL (o texto da check JA e uma pergunta).

Rodadas anteriores:
  v1 json/nothink      3/9  (9x present=true, json malformado)
  v2 rotulo/nothink    4/9  (8x PRESENTE)
  v3 rotulo/nothink    5/9  (9x PRESENTE = taxa-base, zero discriminacao)
  v3 rotulo/THINK      5/9  (9x PRESENTE)
  v4 extracao          4/9  (9x NADA — o vies so inverteu de constante)
  v5 polaridade TEM/FALTA 6/9  (PRIMEIRA variante que de fato varia)

Aqui: (a) o modelo consegue LER o texto? (b) a pergunta natural + SIM/NAO.
"""
import json
import sys
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = "qwen3:0.6b"

from probe import CASOS, CHECKS, PLANO_LIMPO  # noqa: E402


def chat(system, user, think, num_predict, temperature=0.0):
    payload = {
        "model": MODEL,
        "messages": (
            ([{"role": "system", "content": system}] if system else [])
            + [{"role": "user", "content": user}]
        ),
        "stream": False,
        "think": think,
        "options": {"temperature": temperature, "num_predict": num_predict},
    }
    req = urllib.request.Request(
        OLLAMA, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=240) as resp:
        body = json.loads(resp.read())
    msg = body.get("message") or {}
    return {"content": msg.get("content") or "", "done_reason": body.get("done_reason")}


def sanidade():
    """O modelo enxerga o texto? Perguntas cuja resposta esta LITERALMENTE no plano."""
    print("\n===== SANIDADE (o modelo le o texto?) =====")
    perguntas = [
        ("copiar titulo", "Copie a PRIMEIRA linha do texto, exatamente como está.", "# Plano"),
        ("achar numero", "Que número de linha aparece logo depois de 'ReportService.java:'?", "142"),
        ("listar secoes", "Liste os títulos de seção (linhas que começam com ##) do texto.", "Encaixe/Tarefas/Testes"),
        ("existe secao", "O texto contém uma seção chamada '## Testes'? Responda SIM ou NÃO.", "SIM"),
        ("existe secao ausente", "O texto contém uma seção chamada '## Riscos'? Responda SIM ou NÃO.", "NÃO"),
    ]
    for nome, pergunta, esperado in perguntas:
        out = chat(None, f"TEXTO:\n<<<\n{PLANO_LIMPO}\n>>>\n\n{pergunta}", False, 200)
        got = out["content"].replace("\n", " | ")[:100]
        print(f"  {nome:22} esperado~{esperado:18} << {got}")


# ---------- v6: a pergunta NATURAL da check + SIM/NAO ----------
V6_SYS = (
    "Responda apenas SIM ou NÃO, com base exclusivamente no texto fornecido.\n"
    "SIM = o texto satisfaz o que a pergunta pede. NÃO = não satisfaz.\n"
    "Depois do SIM/NÃO, cite entre aspas o trecho do texto que embasa sua resposta."
)


def v6_user(plano, check_text):
    return f"TEXTO:\n<<<\n{plano}\n>>>\n\nPERGUNTA: {check_text}"


def parse_simnao(content):
    text = (content or "").strip().upper().replace("NÃO", "NAO")
    if not text:
        return None
    head = text[:40]
    if head.startswith("SIM") or "**SIM**" in head:
        return True
    if head.startswith("NAO") or "**NAO**" in head:
        return False
    tem_sim, tem_nao = "SIM" in text, "NAO" in text
    if tem_sim and not tem_nao:
        return True
    if tem_nao and not tem_sim:
        return False
    return None


def run(name, think, samples=1, temperature=0.0):
    acertos = erros = indet = 0
    falso_neg = falso_pos = 0
    respostas_sim = 0
    print(f"\n===== {name} (think={think}, samples={samples}, temp={temperature}) =====")
    for plano_id, plano, verdade in CASOS:
        for check_id, check_text in CHECKS:
            votos = []
            for _ in range(samples):
                out = chat(V6_SYS, v6_user(plano, check_text), think, 400, temperature)
                votos.append(parse_simnao(out["content"]))
            validos = [v for v in votos if v is not None]
            if not validos:
                got = None
            else:
                got = sum(validos) * 2 > len(validos)
            if got:
                respostas_sim += 1
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
            snippet = out["content"].replace("\n", " | ")[:75]
            print(
                f"  [{veredito:13}] {plano_id:8} / {check_id:9} "
                f"esperado={'P' if esperado else 'A'} obtido="
                f"{'P' if got is True else 'A' if got is False else '?'} "
                f"votos={votos}  << {snippet}"
            )
    total = acertos + erros + indet
    print(
        f"  --> acertos {acertos}/{total} | erros {erros} "
        f"(falso-negativo {falso_neg}, falso-positivo {falso_pos}) | indeterminado {indet}"
        f" | disse-SIM {respostas_sim}/9"
    )


if __name__ == "__main__":
    alvo = sys.argv[1] if len(sys.argv) > 1 else "sanidade"
    if alvo == "sanidade":
        sanidade()
    elif alvo == "v6":
        run("v6-pergunta-natural-nothink", False)
    elif alvo == "v6think":
        run("v6-pergunta-natural-THINK", True)
    elif alvo == "v6vote":
        run("v6-pergunta-natural-VOTO3", True, samples=3, temperature=0.7)
