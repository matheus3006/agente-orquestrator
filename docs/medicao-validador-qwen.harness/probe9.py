"""Harness 9 — dimensionar num_predict para plano perto do cap de 20k chars."""
import probe8
from probe4 import CHECKS_SEED, parse_simnao

# infla o plano ate ~7k chars repetindo o miolo (o cap do seam e 20k)
GRANDE = probe8.PREAMBULO + probe8.TAREFAS_SEM_LINHA + (probe8.MEIO * 5) + probe8.SEM_SECAO_TESTE
print(f"plano grande: {len(GRANDE)} chars — verdade: seam=P, file-line=A, test=A")
for num_predict in (800, 1500):
    print(f"\n--- num_predict={num_predict} ---")
    for check_id, check_text in CHECKS_SEED:
        out = probe8.chat(f"TEXTO:\n<<<\n{GRANDE}\n>>>\n\nPERGUNTA: {check_text}", num_predict)
        got = parse_simnao(out["content"])
        print(f"  {check_id:9} obt={'P' if got is True else 'A' if got is False else 'INDETERMINADO'} "
              f"({out['elapsed']:.1f}s, in={out['prompt_eval_count']}tok, {out['done_reason']})")
