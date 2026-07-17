# Resumo por ADR (contagens finais, extraído do journal.jsonl da run wf_07d7327a-ed4)

| ADR | N | PROVADA | REFUTADA | AGORA | BUG#1 | NÃO-TEST | N/C | status |
|---|---|---|---|---|---|---|---|---|
| 0001 | 21 | 2 | 0 | 12 | 0 | 7 | 0 | verificado |
| 0002 | 16 | 8 | 0 | 7 | 0 | 1 | 0 | verificado |
| 0003 | 28 | 12 | 0 | 9 | 5 | 2 | 0 | verificado |
| 0004 | 31 | 4 | 2 | 11 | 3 | 11 | 0 | verificado |
| 0005 | 19 | 7 | 0 | 5 | 0 | 7 | 0 | verificado |
| 0006 | 21 | 4 | 0 | 4 | 4 | 9 | 0 | verificado |
| 0007 | 21 | 4 | 0 | 14 | 2 | 1 | 0 | verificado |
| 0008 | 10 | 2 | 0 | 5 | 1 | 1 | 1 | verificado |
| 0009 | 18 | 0 | 0 | 10 | 3 | 5 | 0 | verificado |
| 0010 | 11 | 1 | 0 | 1 | 0 | 9 | 0 | verificado |
| 0011 | 20 | 6 | 0 | 3 | 7 | 4 | 0 | verificado |
| 0012 | 26 | 8 | 1 | 6 | 9 | 2 | 0 | verificado |
| 0013 | 37 | 0 | 1 | 23 | 1 | 12 | 0 | verificado |
| 0014 | 28 | 6 | 1 | 15 | 1 | 5 | 0 | verificado |
| 0015 | 16 | 2 | 1 | 6 | 1 | 3 | 3 | verificado |
| 0016 | 36 | 1 | 0 | 22 | 1 | 12 | 0 | verificado |
| 0017 | 31 | 4 | 2 | 22 | 1 | 2 | 0 | verificado |
| 0018 | 57 | 27 | 1 | 10 | 10 | 7 | 2 | verificado |
| 0019 | 32 | 17 | 0 | 10 | 1 | 4 | 0 | verificado |
| 0020 | 27 | 13 | 0 | 8 | 3 | 2 | 1 | verificado |
| 0021 | 25 | 2 | 0 | 8 | 14 | 1 | 0 | SEM VERIFICAÇÃO (output>64k, 2 tentativas) |
| 0022 | 19 | 3 | 0 | 7 | 3 | 6 | 0 | verificado |
| 0023 | 39 | 0 | 0 | 7 | 26 | 6 | 0 | verificado |
| 0024 | 17 | 7 | 0 | 3 | 3 | 4 | 0 | verificado |
| 0025 | 17 | 7 | 0 | 1 | 4 | 5 | 0 | verificado |
| 0026 | 24 | 8 | 0 | 6 | 1 | 9 | 0 | verificado |
| 0027 | 38 | 13 | 0 | 8 | 9 | 8 | 0 | verificado |
| 0028 | 27 | 9 | 0 | 9 | 1 | 8 | 0 | verificado |
| 0029 | 19 | 10 | 0 | 5 | 0 | 4 | 0 | verificado |
| 0030 | 28 | 7 | 2 | 12 | 1 | 6 | 0 | verificado |
| 0031 | 43 | 25 | 1 | 12 | 1 | 4 | 0 | SEM VERIFICAÇÃO (output>64k, 2 tentativas) |
| 0032 | 20 | 6 | 0 | 9 | 3 | 2 | 0 | verificado |
| 0033 | 19 | 7 | 1 | 10 | 0 | 1 | 0 | verificado |
| 0034 | 25 | 9 | 1 | 11 | 1 | 3 | 0 | verificado |
| 0035 | 27 | 12 | 1 | 11 | 1 | 2 | 0 | SEM VERIFICAÇÃO (output>64k, 2 tentativas) |
| 0036 | 16 | 2 | 0 | 10 | 1 | 3 | 0 | verificado |
| 0037 | 41 | 10 | 4 | 18 | 6 | 3 | 0 | verificado |
| 0038 | 18 | 5 | 0 | 5 | 2 | 6 | 0 | verificado |
| 0039 | 27 | 11 | 0 | 10 | 1 | 5 | 0 | verificado |
| 0040 | 33 | 12 | 0 | 15 | 1 | 5 | 0 | verificado |
| 0041 | 17 | 9 | 0 | 3 | 1 | 4 | 0 | verificado |
| 0042 | 22 | 0 | 0 | 15 | 1 | 6 | 0 | verificado |

**TOTAIS:** {'PROVADA': 302, 'REFUTADA': 19, 'AGORA': 398, 'BUG1': 134, 'NAO_TESTAVEL': 207, 'NC': 7} · soma N = 1067 · ADRs verificadas: 39/42

**Sem verificação adversarial** (estourou 64000 tokens de output, 2 tentativas — fragmento fica na forma da extração inicial, não-revisada): ['0021', '0031', '0035']
