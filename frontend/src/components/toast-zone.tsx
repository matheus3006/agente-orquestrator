"use client";

import { Check, X } from "lucide-react";
import { useEffect, useState, useSyncExternalStore } from "react";
import {
  readToasts,
  subscribeToasts,
  TOAST_VISIBLE_MS,
  type BoardToast,
} from "@/lib/toaster";
import { cn } from "@/lib/utils";

export function ToastZone() {
  // O snapshot de servidor é o MESMO readToasts: no server a store nunca muda,
  // então a referência é estável (um `() => []` novo por chamada dispara o
  // aviso de loop infinito do React — visto na verificação viva).
  const toasts = useSyncExternalStore(subscribeToasts, readToasts, readToasts);
  if (toasts.length === 0) {
    return null;
  }
  return (
    <div className="toast-zone">
      {toasts.map((toast) => (
        <ToastCard key={toast.id} toast={toast} />
      ))}
    </div>
  );
}

function ToastCard({ toast }: { toast: BoardToast }) {
  // A remoção da store é agendada pelo pushToast; aqui só a saída animada.
  const [leaving, setLeaving] = useState(false);
  useEffect(() => {
    const leaveTimer = setTimeout(() => setLeaving(true), TOAST_VISIBLE_MS);
    return () => clearTimeout(leaveTimer);
  }, [toast.id]);

  return (
    <div className={cn("toast", toast.tone === "bad" && "bad", leaving && "leaving")} role="status">
      {toast.tone === "bad" ? <X strokeWidth={2} /> : <Check strokeWidth={2} />}
      <span>{toast.text}</span>
    </div>
  );
}
