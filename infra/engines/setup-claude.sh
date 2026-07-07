#!/usr/bin/env bash
# Onboarding da credencial do plano Claude (F.8, ADR-0016) — roda UMA VEZ, interativo.
#
# O login NASCE no container Linux (ADR-0002): o refresh token OAuth ROTACIONA ao usar —
# copiar do host (docker cp / mount do ~/.claude do Mac) dessincroniza as duas cópias e
# quebra os dois lados (deep-research 2026-07-02, confirmado 3-0). O volume orq_claude_creds
# persiste o HOME inteiro do container: ~/.claude/ E ~/.claude.json (o onboarding-state
# vive fora do dir), tudo de uma vez.
#
# Alternativa sem browser (automação): gere `claude setup-token` (~1 ano) em qualquer máquina
# logada e exporte para o backend como ORQUESTRADOR_ENGINES_CLAUDE_OAUTH_TOKEN
# (property orquestrador.engines.claude.oauth-token) — aí este script é dispensável.
set -euo pipefail
cd "$(dirname "$0")"

CLAUDE_CODE_VERSION="$(grep -m1 '^ARG CLAUDE_CODE_VERSION=' claude-code/Dockerfile | cut -d= -f2)"
IMAGE="orq-claude-code:${CLAUDE_CODE_VERSION}"

docker volume create orq_claude_creds >/dev/null

cat <<'EOT'
== Onboarding do plano Claude (uma vez) ==
O `claude` interativo vai abrir DENTRO do container, com o volume de credencial montado.
  1. Siga o fluxo de login (tema/onboarding → abra a URL no seu browser e cole o código).
  2. Logado, saia com /exit — a credencial fica gravada no volume orq_claude_creds.
EOT

docker run -it --rm -v orq_claude_creds:/home/node "${IMAGE}" claude

echo "Verificando a credencial gravada no volume..."
docker run --rm -v orq_claude_creds:/home/node "${IMAGE}" sh -c \
  'test -s /home/node/.claude/.credentials.json \
     && echo "OK: credencial do plano Claude viva no volume orq_claude_creds" \
     || { echo "FALTOU: /home/node/.claude/.credentials.json não existe — o login completou?"; exit 1; }'
