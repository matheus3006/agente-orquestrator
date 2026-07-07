# Imagem-shot: o 5º container da familia (F.13, ADR-0027) — Chromium headless que fotografa o app
# v2 VIVO e devolve o PNG pelo stdout (chromium --screenshot=/tmp/s.png ... && cat).
#
# Base: alpine + o pacote `chromium` (Chromium COMPLETO, mantido e current pelo Alpine). O smoke do
# argv (ADR-0027 §C.2) REPROVOU o chromedp/headless-shell que a deep-research recomendou como #1:
# aquele binario (`headless_shell`) so fala CDP (DevTools protocol) — NAO implementa o modo one-shot
# de linha de comando `--screenshot`/`--dump-dom` (trava sem capturar nem sair, confirmado no smoke).
# O one-shot que o seam usa exige o Chromium COMPLETO. Alpine+chromium entrega isso, CURRENT (Chromium
# 136+, nao congelado como o zenika/alpine-chrome da pesquisa, preso no 124), MANTIDO (security do
# Alpine) e num pull pequeno — provado no smoke: PNG 1280x800 valido, exit 0.
#
# PINO (defesa --bare): a base e o RELEASE do Alpine (fixo, pinavel); o chromium FLUTUA dentro do
# release — mas isso e o Alpine aplicando patch de SEGURANCA (CVE), o OPOSTO do risco de --bare de um
# CLI auto-atualizar e mudar comportamento. O PreviewShotImagePinTest trava este release <-> constante
# Java (PreviewThumbnailCapturer.SHOT_ALPINE_RELEASE); a tag da imagem carrega a base pinada.
FROM alpine:3.21

# chromium COMPLETO (one-shot --screenshot funciona) + fonte Latina (font-liberation, senao texto vira
# tofu/caixas vazias) + fontconfig (fc-cache pre-aquece o cache). O binario do Alpine e
# `chromium-browser` — symlink `chromium` para o argv do seam (`chromium ...`) resolver. Os flags
# --no-sandbox/--disable-dev-shm-usage/--disable-gpu sao do RUN-COMMAND (moram no argv do seam,
# DockerExecutionTarget.buildScreenshotArgs), nao intrinsecos da imagem. CJK/emoji ficam de fora
# (recorte: shot canonico Latino, ADR-0027 §H).
RUN apk add --no-cache chromium font-liberation fontconfig \
    && ln -sf /usr/bin/chromium-browser /usr/local/bin/chromium \
    && fc-cache -f
