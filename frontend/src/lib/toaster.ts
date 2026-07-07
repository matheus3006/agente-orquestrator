// Toasts do board (vocabulário do protótipo: entra 240ms, sai após ~3.6s).
// Store mínima para useSyncExternalStore — mutações chamam pushToast e a
// ToastZone (montada no layout da company) renderiza. O auto-dismiss vive
// AQUI (não no componente): zona desmontada no meio não deixa toast preso
// na store para replay em outro contexto (achado da revisão).

export interface BoardToast {
  id: number;
  text: string;
  tone: "good" | "bad";
}

/** Visível 3.6s + 280ms de saída animada (spec do protótipo). */
export const TOAST_VISIBLE_MS = 3600;
export const TOAST_LEAVE_MS = 280;

let toasts: BoardToast[] = [];
let nextToastId = 1;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) {
    listener();
  }
}

export function pushToast(text: string, tone: "good" | "bad" = "good"): void {
  const toast = { id: nextToastId++, text, tone };
  toasts = [...toasts, toast];
  emit();
  setTimeout(() => dismissToast(toast.id), TOAST_VISIBLE_MS + TOAST_LEAVE_MS);
}

export function dismissToast(id: number): void {
  toasts = toasts.filter((toast) => toast.id !== id);
  emit();
}

export function readToasts(): BoardToast[] {
  return toasts;
}

export function subscribeToasts(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
