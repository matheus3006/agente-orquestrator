# Imagem do runtime `codex` (F.8, ADR-0016). CLI PINADO — o EngineContainerPinTest trava esta
# versão à constante do CodexAdapter. A credencial nasce no volume orq_codex_creds via
# setup-codex.sh (login no container, nunca copiado do host — ADR-0002).
FROM node:22-bookworm-slim

ARG CODEX_VERSION=0.142.5

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates curl procps \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g @openai/codex@${CODEX_VERSION}

# Credencial em ARQUIVO, não keyring (refinamento deep-research): o auth.json precisa viver no
# volume montado. Semeado aqui (o volume herda na 1ª montagem) E garantido no setup-codex.sh.
RUN mkdir -p /home/node/.codex \
    && printf 'cli_auth_credentials_store = "file"\n' > /home/node/.codex/config.toml \
    && chown -R node:node /home/node/.codex

# Mesmo motivo do claude-code: o volume do workspace herda o ownership deste mountpoint.
RUN mkdir -p /workspace && chown node:node /workspace

USER node
WORKDIR /workspace
