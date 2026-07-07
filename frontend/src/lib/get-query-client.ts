import { QueryClient, defaultShouldDehydrateQuery, isServer } from "@tanstack/react-query";

// Padrão oficial TanStack v5 (ADR-0013): no servidor, um QueryClient NOVO por
// request (nunca em escopo de módulo — vazaria dados entre requests); no
// browser, um singleton criado lazy (estável através de Suspense re-renders).
function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // O realtime v1 é polling por tela; entre telas, 30s de frescor bastam.
        staleTime: 30_000,
        refetchOnWindowFocus: true,
      },
      dehydrate: {
        shouldDehydrateQuery: (query) =>
          defaultShouldDehydrateQuery(query) || query.state.status === "pending",
      },
    },
  });
}

let browserQueryClient: QueryClient | undefined;

export function getQueryClient() {
  if (isServer) {
    return makeQueryClient();
  }
  browserQueryClient ??= makeQueryClient();
  return browserQueryClient;
}
