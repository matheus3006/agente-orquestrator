# Imagem do runtime `claude` (F.8, ADR-0016). O CLI é PINADO: o `--bare` pode virar default
# do `claude -p` e passar a exigir API key (risco do ADR-0014) — o pino é a defesa, e o
# EngineContainerPinTest trava esta versão à constante do ClaudeCodeAdapter.
# A credencial NÃO mora na imagem: nasce no volume orq_claude_creds via setup-claude.sh
# (o login DEVE nascer no container — o refresh token OAuth rotaciona ao usar; ADR-0002).
FROM node:22-bookworm-slim

ARG CLAUDE_CODE_VERSION=2.1.201

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates curl procps \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}

# Auto-update gravaria um CLI novo dentro do volume-home, desatualizando o pino por baixo.
ENV DISABLE_AUTOUPDATER=1

# O DockerExecutionTarget monta o workspace aqui; um volume novo herda o ownership do
# mountpoint da imagem — sem o chown, nasceria root-owned e o user node não escreveria.
RUN mkdir -p /workspace && chown node:node /workspace

USER node
WORKDIR /workspace
