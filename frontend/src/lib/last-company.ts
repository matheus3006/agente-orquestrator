import { useSyncExternalStore } from "react";

// Última company visitada — CLIENT-SIDE por decisão (ADR-0013): gravar num
// request prefetchável executaria o side-effect no prefetch (armadilha
// documentada do next-intl). Grava-se no CLIQUE (picker/switcher) e na
// VISITA (P2 #16, LembrarCompanyVisitada); lê-se na raiz para o redirect e
// nas rotas globais como LENTE.
const STORAGE_KEY = "orq.lastCompanySlug";

export function readLastCompanySlug(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function writeLastCompanySlug(slug: string): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, slug);
  } catch {
    // storage indisponível (aba anônima etc.) — o redirect só perde a memória
  }
}

function subscribeLastCompanySlug(onChange: () => void): () => void {
  window.addEventListener("storage", onChange);
  return () => window.removeEventListener("storage", onChange);
}

/**
 * A lente em RENDER (P2 #16): o mesmo slug de readLastCompanySlug, mas seguro
 * para SSR/hidratação — o snapshot do servidor é null (lá não existe
 * localStorage), a hidratação bate 1:1 e o valor real entra num re-render
 * pós-mount. O evento "storage" cobre troca de company em outra aba.
 */
export function useLastCompanySlug(): string | null {
  return useSyncExternalStore(subscribeLastCompanySlug, readLastCompanySlug, () => null);
}
