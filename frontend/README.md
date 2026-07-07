# frontend — agente-orquestrator

Painel (board) do operador. Next.js 16 + React 19 + TypeScript, tokens em CSS variables.

## Rodar

```bash
pnpm install
pnpm dev
```

Aponta para a API do backend em `NEXT_PUBLIC_API_BASE` (default `http://localhost:8080`).
Em dev, o ator board é simulado via `NEXT_PUBLIC_DEV_BOARD_USER` (um UUID).

## Estrutura
- `src/app/` — App Router (layout, board)
- `src/app/globals.css` — design tokens (light/dark)
- `src/lib/api.ts` — cliente tipado da API
