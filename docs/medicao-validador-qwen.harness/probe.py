#!/usr/bin/env python3
"""Harness empirico do validador (Uso B / B-ii).

Mede a PRECISAO do qwen3:0.6b nas 3 checks REAIS do seed (V32) sobre planos com
verdade CONHECIDA. Bate no /api/chat (o mesmo endpoint que o Spring AI Ollama usa),
para que o formato medido aqui seja o formato que vai pra producao.

Regra dura: resposta vazia/truncada => INDETERMINADO, jamais "ausente".
"""
import json
import sys
import urllib.request

OLLAMA = "http://localhost:11434/api/chat"
MODEL = "qwen3:0.6b"

# --- As 3 checks REAIS do seed v1 do arquiteto (V32__role_template_checks.sql) ---
CHECKS = [
    ("seam", "O plano aponta o seam/encaixe (arquivo/módulo) que vai tocar?"),
    ("file-line", "Cada tarefa/passo cita arquivo:linha concreto?"),
    ("test", "O plano tem seção ou menção explícita de teste?"),
]

# --- Planos com VERDADE CONHECIDA (prosa, em portugues, como o arquiteto real escreve) ---
PLANO_VIOLADO = """# Plano — corrigir o cálculo de saldo do relatório

## Encaixe
A mudança entra no serviço de relatórios (`ReportService`), no ponto onde o saldo é agregado.

## Tarefas
1. Ajustar a soma do saldo para considerar lançamentos estornados.
2. Propagar o novo campo até o DTO de resposta.
3. Invalidar o cache do relatório quando houver estorno.

## Testes
Cobrir com teste de unidade a soma com estorno e um teste de regressão do endpoint.
"""

PLANO_LIMPO = """# Plano — corrigir o cálculo de saldo do relatório

## Encaixe
`ReportService.java:142` — o método `somarSaldo` é o único ponto que agrega lançamentos.

## Tarefas
1. `ReportService.java:142` — considerar lançamentos estornados na soma.
2. `ReportResponse.java:31` — expor o novo campo `saldoEstornado`.
3. `ReportCache.java:88` — invalidar a entrada quando houver estorno.

## Testes
`ReportServiceTest.java:60` — teste de unidade da soma com estorno.
`ReportEndpointIT.java:24` — regressão do endpoint.
"""

PLANO_MAGRO = """# Plano

Vou ajustar a soma do saldo para considerar estornos e depois propagar o valor
para a resposta da API. Faço isso hoje.
"""

# verdade: (plano_id, check_id) -> True=presente / False=ausente
CASOS = [
    ("violado", PLANO_VIOLADO, {"seam": True, "file-line": False, "test": True}),
    ("limpo", PLANO_LIMPO, {"seam": True, "file-line": True, "test": True}),
    ("magro", PLANO_MAGRO, {"seam": False, "file-line": False, "test": False}),
]


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
    return {
        "content": (msg.get("content") or ""),
        "thinking": (msg.get("thinking") or ""),
        "done_reason": body.get("done_reason"),
    }


# ---------------- variantes de prompt ----------------

V1_SYS = (
    "Você confere se uma exigência está presente num texto. Não julgue qualidade. "
    "Responda APENAS com JSON: {\"present\": true|false, \"evidence\": \"trecho literal\"}."
)


def v1_user(plano, check_text):
    return f"EXIGÊNCIA: {check_text}\n\nTEXTO DO PLANO:\n---\n{plano}\n---\n\nJSON:"


V2_SYS = (
    "Você é um conferente mecânico. Recebe UM texto e UMA exigência factual. "
    "Diga apenas se a exigência está presente no texto. NÃO julgue se o plano é bom.\n"
    "Responda em exatamente 2 linhas:\n"
    "VEREDITO: PRESENTE ou AUSENTE\n"
    "EVIDENCIA: um trecho literal copiado do texto (ou '-' se ausente)"
)


def v2_user(plano, check_text):
    return f"EXIGÊNCIA: {check_text}\n\nTEXTO DO PLANO:\n---\n{plano}\n---"


V3_SYS = (
    "Você confere PRESENÇA de uma exigência num texto de plano técnico. Trabalho mecânico:\n"
    "- PRESENTE = o texto contém algo que satisfaz a exigência (mesmo que parcialmente mal escrito).\n"
    "- AUSENTE = o texto não contém nada que satisfaça.\n"
    "Nunca opine sobre qualidade, elegância ou completude do plano.\n"
    "Formato obrigatório, 2 linhas:\n"
    "VEREDITO: PRESENTE|AUSENTE\n"
    "EVIDENCIA: <trecho literal do texto, ou - >"
)


def v3_user(plano, check_text):
    return (
        f"TEXTO DO PLANO:\n<<<\n{plano}\n>>>\n\n"
        f"EXIGÊNCIA A CONFERIR: {check_text}\n\n"
        "Procure no texto acima. Responda no formato de 2 linhas."
    )


VARIANTS = {
    "v1-json-nothink": (V1_SYS, v1_user, False, 200),
    "v2-2linhas-nothink": (V2_SYS, v2_user, False, 200),
    "v3-2linhas-explicito-nothink": (V3_SYS, v3_user, False, 200),
    "v3-2linhas-explicito-THINK": (V3_SYS, v3_user, True, 1500),
}


def parse(variant, content):
    """None = INDETERMINADO (vazio/truncado/ilegivel). Nunca vira 'ausente'."""
    text = (content or "").strip()
    if not text:
        return None
    if variant.startswith("v1"):
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end <= start:
            return None
        try:
            return bool(json.loads(text[start : end + 1]).get("present"))
        except Exception:
            return None
    upper = text.upper()
    for line in upper.splitlines():
        if "VEREDITO" in line:
            if "PRESENTE" in line:
                return True
            if "AUSENTE" in line:
                return False
    if "PRESENTE" in upper and "AUSENTE" not in upper:
        return True
    if "AUSENTE" in upper and "PRESENTE" not in upper:
        return False
    return None


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for name, (system, user_fn, think, num_predict) in VARIANTS.items():
        if only and only != name:
            continue
        acertos = erros = indet = 0
        falso_neg = falso_pos = 0
        print(f"\n===== VARIANTE {name} (think={think}, num_predict={num_predict}) =====")
        for plano_id, plano, verdade in CASOS:
            for check_id, check_text in CHECKS:
                out = chat(system, user_fn(plano, check_text), think, num_predict)
                got = parse(name, out["content"])
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
                snippet = out["content"].replace("\n", " | ")[:110]
                print(
                    f"  [{veredito:13}] {plano_id:8} / {check_id:9} "
                    f"esperado={'P' if esperado else 'A'} obtido="
                    f"{'P' if got is True else 'A' if got is False else '?'} "
                    f"done={out['done_reason']}  << {snippet}"
                )
        total = acertos + erros + indet
        print(
            f"  --> acertos {acertos}/{total} | erros {erros} "
            f"(falso-negativo {falso_neg}, falso-positivo {falso_pos}) | indeterminado {indet}"
        )


if __name__ == "__main__":
    main()
