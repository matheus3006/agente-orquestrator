#!/usr/bin/env python3
"""Harness 4 — a pergunta ABSTRATA (seed) vs a pergunta CONCRETA, com 18 casos.

A sondagem de sanidade mostrou o unico sinal real ate agora: perguntas LITERAIS
("o texto contem uma secao chamada '## Testes'?" -> SIM; "'## Riscos'?" -> NAO)
o modelo acerta e DISCRIMINA. As checks do seed sao semi-abstratas ("aponta o
seam/encaixe?"). Esta rodada isola a variavel: MESMOS planos, MESMA mecanica,
so muda a REDACAO da check.

Se o concreto salvar => a fatia e viavel e o produto e "redija checks literais".
Se nao salvar => o qwen3:0.6b nao faz este trabalho, e o dono precisa saber ANTES
do merge (o push iria pra producao sobre um sinal que e ruido).
"""
import json
import sys
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = __import__("os").environ.get("ORQ_PROBE_MODEL", "qwen3:0.6b")

# ---- redacoes: a do SEED (V32, abstrata) e a CONCRETA (literal) ----
CHECKS_SEED = [
    ("seam", "O plano aponta o seam/encaixe (arquivo/módulo) que vai tocar?"),
    ("file-line", "Cada tarefa/passo cita arquivo:linha concreto?"),
    ("test", "O plano tem seção ou menção explícita de teste?"),
]

CHECKS_CONCRETA = [
    ("seam", "O texto cita algum nome de arquivo, classe ou módulo (por exemplo `ReportService` ou `Foo.java`)?"),
    ("file-line", "O texto contém alguma referência no formato arquivo:linha (por exemplo `Foo.java:42`)?"),
    ("test", "O texto contém a palavra teste, testes ou uma seção de testes?"),
]

from probe import PLANO_LIMPO, PLANO_MAGRO, PLANO_VIOLADO  # noqa: E402

# 3 planos novos para dar poder estatistico (18 casos no total)
PLANO_SO_TESTE = """# Plano — subir o timeout do worker

Vou aumentar o timeout e medir de novo. Se continuar estourando, aumento mais.

## Testes
Rodar a suíte inteira depois da mudança.
"""

PLANO_SO_SEAM = """# Plano — trocar o parser de datas

O ponto de entrada é a classe `DateParser`, usada pelo importador de planilhas.
Troco a implementação por uma baseada em java.time e ajusto os chamadores.
Não vejo necessidade de cobertura nova, o comportamento é o mesmo.
"""

PLANO_TUDO_MENOS_SEAM = """# Plano — ajustar o rodapé

1. Trocar o texto do rodapé no arquivo `Footer.tsx:12`.
2. Atualizar o snapshot em `Footer.test.tsx:8`.

## Testes
O snapshot acima cobre a mudança.
"""

CASOS = [
    ("violado", PLANO_VIOLADO, {"seam": True, "file-line": False, "test": True}),
    ("limpo", PLANO_LIMPO, {"seam": True, "file-line": True, "test": True}),
    ("magro", PLANO_MAGRO, {"seam": False, "file-line": False, "test": False}),
    ("so-teste", PLANO_SO_TESTE, {"seam": False, "file-line": False, "test": True}),
    ("so-seam", PLANO_SO_SEAM, {"seam": True, "file-line": False, "test": False}),
    ("quase-tudo", PLANO_TUDO_MENOS_SEAM, {"seam": True, "file-line": True, "test": True}),
]
# verdade: 10 presentes, 8 ausentes => taxa-base de um chute constante = 10/18

SYS = (
    "Responda apenas SIM ou NÃO, com base exclusivamente no texto fornecido.\n"
    "SIM = o texto satisfaz o que a pergunta pede. NÃO = não satisfaz.\n"
    "Depois do SIM/NÃO, cite entre aspas o trecho do texto que embasa sua resposta."
)


def chat(user, think, num_predict):
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYS},
            {"role": "user", "content": user},
        ],
        "stream": False,
        "think": think,
        "options": {"temperature": 0.0, "num_predict": num_predict},
    }
    req = urllib.request.Request(
        OLLAMA, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=240) as resp:
        body = json.loads(resp.read())
    msg = body.get("message") or {}
    return {"content": msg.get("content") or "", "done_reason": body.get("done_reason")}


def parse_simnao(content):
    text = (content or "").strip().upper().replace("NÃO", "NAO")
    if not text:
        return None
    head = text[:40]
    if head.startswith("SIM") or head.startswith("**SIM"):
        return True
    if head.startswith("NAO") or head.startswith("**NAO"):
        return False
    tem_sim, tem_nao = "SIM" in text, "NAO" in text
    if tem_sim and not tem_nao:
        return True
    if tem_nao and not tem_sim:
        return False
    return None


def run(rotulo, checks, think):
    acertos = erros = indet = 0
    falso_neg = falso_pos = disse_sim = 0
    print(f"\n===== {rotulo} (think={think}) — 18 casos, taxa-base=10/18 =====")
    for plano_id, plano, verdade in CASOS:
        for check_id, check_text in checks:
            out = chat(f"TEXTO:\n<<<\n{plano}\n>>>\n\nPERGUNTA: {check_text}", think, 400)
            got = parse_simnao(out["content"])
            esperado = verdade[check_id]
            if got:
                disse_sim += 1
            if got is None:
                veredito, indet = "INDET", indet + 1
            elif got == esperado:
                veredito, acertos = "ok", acertos + 1
            else:
                veredito, erros = "ERRO", erros + 1
                if esperado:
                    falso_neg += 1
                else:
                    falso_pos += 1
            snippet = out["content"].replace("\n", " | ")[:60]
            print(
                f"  [{veredito:5}] {plano_id:11}/{check_id:9} "
                f"esp={'P' if esperado else 'A'} obt="
                f"{'P' if got is True else 'A' if got is False else '?'}  << {snippet}"
            )
    total = acertos + erros + indet
    print(
        f"  --> ACERTOS {acertos}/{total} | erros {erros} "
        f"(FN {falso_neg}, FP {falso_pos}) | indet {indet} | disse-SIM {disse_sim}/18"
    )
    return acertos, total


if __name__ == "__main__":
    alvo = sys.argv[1] if len(sys.argv) > 1 else "ambas"
    if alvo in ("ambas", "seed"):
        run("REDACAO DO SEED (abstrata)", CHECKS_SEED, False)
    if alvo in ("ambas", "concreta"):
        run("REDACAO CONCRETA (literal)", CHECKS_CONCRETA, False)
