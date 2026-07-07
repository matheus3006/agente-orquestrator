import type { Engine } from "./api";

// O MOTOR de um agente (ADR-0014): o trio adapterType + model + effort. O catálogo
// servido por /engines é quem tem o `label` legível e a janela de contexto; estes
// helpers puros resolvem o trio contra o catálogo (null-aware) e nunca quebram.

export type Motor = { adapterType: string; model: string | null; effort: string | null };

/** O motor do agente É esta linha do catálogo? Trio exato, com null-de-eixo tratado. */
export function motorMatchesEngine(motor: Motor, engine: Engine): boolean {
  return (
    engine.adapterType === motor.adapterType &&
    (engine.model ?? null) === (motor.model ?? null) &&
    (engine.effort ?? null) === (motor.effort ?? null)
  );
}

/**
 * O rótulo legível do motor: resolve o trio contra o catálogo; um motor fora do
 * catálogo (custom) ou o catálogo ainda carregando cai num rótulo sintetizado a
 * partir dos eixos — nunca some.
 */
export function engineLabel(motor: Motor, engines: Engine[]): string {
  const match = engines.find((engine) => motorMatchesEngine(motor, engine));
  if (match) return match.label;
  if (motor.model) return motor.effort ? `${motor.model} · ${motor.effort}` : motor.model;
  return motor.adapterType;
}

/** A janela de contexto como "spec" curta do motor: 200000 → "200k"; ausente → null. */
export function fmtContextWindow(contextWindow: number | null): string | null {
  if (!contextWindow || contextWindow <= 0) return null;
  return contextWindow >= 1000 ? `${Math.round(contextWindow / 1000)}k` : String(contextWindow);
}
