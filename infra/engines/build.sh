#!/usr/bin/env bash
# Constrói as imagens da casa com versão PINADA — 1 comando. A fonte da versão é o Dockerfile de
# cada uma (os testes de pino travam Dockerfile ↔ constante Java; a tag da imagem É a versão):
#   - engines por-adapter (F.8, ADR-0016): tag = versão do CLI, do ARG de cada Dockerfile.
#   - imagem-shot (F.13, ADR-0027): tag = base Alpine pinada, do FROM alpine:<release>.
set -euo pipefail
cd "$(dirname "$0")"

CLAUDE_CODE_VERSION="$(grep -m1 '^ARG CLAUDE_CODE_VERSION=' claude-code/Dockerfile | cut -d= -f2)"
CODEX_VERSION="$(grep -m1 '^ARG CODEX_VERSION=' codex/Dockerfile | cut -d= -f2)"
SHOT_ALPINE="$(grep -m1 '^FROM alpine:' ../preview-shot/Dockerfile | cut -d: -f2)"

docker build -t "orq-claude-code:${CLAUDE_CODE_VERSION}" claude-code/
docker build -t "orq-codex:${CODEX_VERSION}" codex/
docker build -t "orq-preview-shot:alpine${SHOT_ALPINE}" ../preview-shot/

echo "OK: orq-claude-code:${CLAUDE_CODE_VERSION}, orq-codex:${CODEX_VERSION} e orq-preview-shot:alpine${SHOT_ALPINE} construídas."
