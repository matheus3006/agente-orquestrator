#!/usr/bin/env bash
# Onboarding da credencial do plano Codex (F.8, ADR-0016) — roda UMA VEZ, interativo.
# Espelho do setup-claude.sh: login nasce no container (nunca copiado do host — a OpenAI até
# documenta `docker cp auth.json`, mas a rotação de refresh token torna o login-no-container
# o único caminho estável; deep-research 2026-07-02). Volume: orq_codex_creds (home inteiro).
set -euo pipefail
cd "$(dirname "$0")"

CODEX_VERSION="$(grep -m1 '^ARG CODEX_VERSION=' codex/Dockerfile | cut -d= -f2)"
IMAGE="orq-codex:${CODEX_VERSION}"

docker volume create orq_codex_creds >/dev/null

# Garante credencial-em-arquivo mesmo se o volume nasceu antes do seed da imagem.
docker run --rm -v orq_codex_creds:/home/node "${IMAGE}" sh -c \
  'mkdir -p ~/.codex && grep -q cli_auth_credentials_store ~/.codex/config.toml 2>/dev/null \
     || printf "cli_auth_credentials_store = \"file\"\n" >> ~/.codex/config.toml'

cat <<'EOT'
== Onboarding do plano Codex (uma vez) ==
Login por device-code DENTRO do container: siga as instruções (URL + código no seu browser).
EOT

docker run -it --rm -v orq_codex_creds:/home/node "${IMAGE}" codex login --device-auth || {
  echo "Se --device-auth não existir nesta versão, tente:"
  echo "  docker run -it --rm -v orq_codex_creds:/home/node ${IMAGE} codex login"
  exit 1
}

echo "Verificando a credencial gravada no volume..."
docker run --rm -v orq_codex_creds:/home/node "${IMAGE}" sh -c \
  'test -s /home/node/.codex/auth.json \
     && echo "OK: credencial do plano Codex viva no volume orq_codex_creds" \
     || { echo "FALTOU: /home/node/.codex/auth.json não existe — o login completou?"; exit 1; }'
