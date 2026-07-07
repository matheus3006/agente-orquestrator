"use client";

import { useEffect } from "react";
import { writeLastCompanySlug } from "@/lib/last-company";

// A LENTE segue a VISITA (P2 #16): entrar numa company grava
// orq.lastCompanySlug mesmo sem passar pelo picker/switcher (URL direta,
// link externo) — é o que garante que a sidebar de /moldes opere pela
// company de onde o operador veio. Efeito de MOUNT, não request: o prefetch
// do Next não roda efeitos, então a armadilha da ADR-0013 (side-effect no
// prefetch) não se aplica aqui.
export function LembrarCompanyVisitada({ slug }: { slug: string }) {
  useEffect(() => {
    writeLastCompanySlug(slug);
  }, [slug]);
  return null;
}
