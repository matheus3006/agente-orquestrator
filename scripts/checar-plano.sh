#!/usr/bin/env bash
# Verifica conformidade com docs/convencao-plano-e-ledger.md.
#
#   scripts/checar-plano.sh                    # todos os planos de docs/planos/
#   scripts/checar-plano.sh docs/planos/X.md   # um plano
#
# Sai 0 se tudo conforme, 1 se qualquer plano falhar.

set -uo pipefail

LIMITE_LINHAS=${LIMITE_LINHAS:-60}
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
falhas_totais=0

vermelho() { printf '\033[31m%s\033[0m\n' "$1"; }
verde()    { printf '\033[32m%s\033[0m\n' "$1"; }
amarelo()  { printf '\033[33m%s\033[0m\n' "$1"; }

checar_plano() {
  local plano="$1"
  local nome dir detalhe falhas=0
  nome="$(basename "$plano" .md)"
  dir="$(dirname "$plano")"
  detalhe="$dir/$nome.detalhe"

  echo "── $plano"

  # (3) limite de linhas do índice
  local linhas
  linhas=$(wc -l < "$plano" | tr -d ' ')
  if [ "$linhas" -gt "$LIMITE_LINHAS" ]; then
    vermelho "  ✗ índice com $linhas linhas (limite $LIMITE_LINHAS) — conteúdo de detalhe vazou para o índice"
    falhas=$((falhas + 1))
  else
    verde "  ✓ índice com $linhas linhas (limite $LIMITE_LINHAS)"
  fi

  # (1) e (2) todo ponto tem link, e o link resolve
  local pontos=0 sem_link=0 quebrados=0
  local -a linkados=()
  while IFS= read -r linha; do
    # Linha de ponto da tabela: "| <ID> |", onde ID = 1+ letras + 1+ dígitos (P1, R4, D12…).
    # O prefixo é livre de propósito — cada plano escolhe o seu.
    [[ "$linha" =~ ^\|[[:space:]]*[A-Za-z]+[0-9]+[[:space:]]*\| ]] || continue
    pontos=$((pontos + 1))
    local id
    id=$(printf '%s' "$linha" | sed -E 's/^\|[[:space:]]*([A-Za-z]+[0-9]+)[[:space:]]*\|.*/\1/')
    local alvo
    alvo=$(printf '%s' "$linha" | sed -nE 's/.*\]\(([^)]*\.detalhe\/[^)]+\.html)\).*/\1/p')
    if [ -z "$alvo" ]; then
      vermelho "  ✗ $id sem link de detalhe"
      sem_link=$((sem_link + 1)); continue
    fi
    if [ ! -f "$dir/$alvo" ]; then
      vermelho "  ✗ $id aponta para arquivo inexistente: $alvo"
      quebrados=$((quebrados + 1)); continue
    fi
    linkados+=("$(basename "$alvo")")
  done < "$plano"

  if [ "$pontos" -eq 0 ]; then
    vermelho "  ✗ nenhum ponto encontrado (a tabela usa linhas '| P<n> | ... |'?)"
    falhas=$((falhas + 1))
  elif [ "$sem_link" -eq 0 ] && [ "$quebrados" -eq 0 ]; then
    verde "  ✓ $pontos pontos, todos com detalhe que resolve"
  else
    falhas=$((falhas + sem_link + quebrados))
  fi

  # (6) ledger existe
  if [ -f "$detalhe/ledger.html" ]; then
    verde "  ✓ ledger.html presente"
  else
    vermelho "  ✗ falta $detalhe/ledger.html"
    falhas=$((falhas + 1))
  fi

  # (4) detalhe órfão
  if [ -d "$detalhe" ]; then
    local orfaos=0
    for arquivo in "$detalhe"/*.html; do
      [ -e "$arquivo" ] || continue
      local base; base="$(basename "$arquivo")"
      [ "$base" = "ledger.html" ] && continue
      local achou=0
      for l in ${linkados+"${linkados[@]}"}; do
        [ "$l" = "$base" ] && { achou=1; break; }
      done
      if [ "$achou" -eq 0 ]; then
        amarelo "  ✗ detalhe órfão (existe e não é linkado): $base"
        orfaos=$((orfaos + 1))
      fi
    done
    [ "$orfaos" -eq 0 ] && verde "  ✓ nenhum detalhe órfão"
    falhas=$((falhas + orfaos))
  fi

  # (7) toda página de detalhe declara UTF-8, e a folha compartilhada existe.
  # Achado ao vivo em 2026-07-27: sem <meta charset>, servida por HTTP sem charset no header,
  # "não" vira "nÃ£o". file:// costuma adivinhar certo — por isso o defeito passa despercebido.
  if [ -d "$detalhe" ]; then
    local sem_charset=0
    for arquivo in "$detalhe"/*.html; do
      [ -e "$arquivo" ] || continue
      if ! head -5 "$arquivo" | grep -qi 'meta charset'; then
        vermelho "  ✗ sem <meta charset=\"utf-8\"> nas 5 primeiras linhas: $(basename "$arquivo")"
        sem_charset=$((sem_charset + 1))
      fi
    done
    [ "$sem_charset" -eq 0 ] && verde "  ✓ todas as páginas declaram UTF-8"
    falhas=$((falhas + sem_charset))

    # A folha pode ser compartilhada (docs/planos/_estilo.css) ou local ao plano.
    if [ -f "$dir/_estilo.css" ] || [ -f "$detalhe/_estilo.css" ]; then
      verde "  ✓ _estilo.css alcançável"
    else
      vermelho "  ✗ falta _estilo.css em $dir/ ou $detalhe/ (as páginas linkam essa folha)"
      falhas=$((falhas + 1))
    fi

    # O href tem de bater com onde a folha realmente está.
    local href_quebrado=0
    for arquivo in "$detalhe"/*.html; do
      [ -e "$arquivo" ] || continue
      local href; href=$(sed -nE 's/.*<link[^>]*href="([^"]*\.css)".*/\1/p' "$arquivo" | head -1)
      [ -z "$href" ] && continue
      if [ ! -f "$detalhe/$href" ]; then
        vermelho "  ✗ href de CSS não resolve em $(basename "$arquivo"): $href"
        href_quebrado=$((href_quebrado + 1))
      fi
    done
    [ "$href_quebrado" -eq 0 ] && verde "  ✓ href do CSS resolve em todas"
    falhas=$((falhas + href_quebrado))
  fi

  # (5) ledger inline no índice — o defeito que originou a convenção
  local inline=0
  if grep -qE '^>?[[:space:]]*\*\*\[(Atualização|Correção)' "$plano"; then
    vermelho "  ✗ bloco de atualização datada no índice — ledger inline (vai apodrecer; mova para ledger.html)"
    inline=$((inline + 1))
  fi
  if [ "$(grep -cE '^\|.*(TOTAL|total).*\|' "$plano")" -gt 1 ]; then
    vermelho "  ✗ mais de uma tabela de totais no índice — só o ledger carrega totais"
    inline=$((inline + 1))
  fi
  [ "$inline" -eq 0 ] && verde "  ✓ sem ledger inline"
  falhas=$((falhas + inline))

  if [ "$falhas" -eq 0 ]; then
    verde "  ✔ conforme"
  else
    vermelho "  ✘ $falhas problema(s)"
  fi
  echo
  return "$falhas"
}

if [ "$#" -gt 0 ]; then
  alvos=("$@")
else
  shopt -s nullglob
  alvos=("$RAIZ"/docs/planos/*.md)
  shopt -u nullglob
fi

if [ "${#alvos[@]}" -eq 0 ]; then
  amarelo "Nenhum plano encontrado em docs/planos/"
  exit 0
fi

for plano in "${alvos[@]}"; do
  checar_plano "$plano" || falhas_totais=$((falhas_totais + $?))
done

if [ "$falhas_totais" -eq 0 ]; then
  verde "Tudo conforme com docs/convencao-plano-e-ledger.md"
  exit 0
fi
vermelho "$falhas_totais problema(s) — ver docs/convencao-plano-e-ledger.md"
exit 1
