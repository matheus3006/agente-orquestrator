import type { IssueSummary, MissionControlAgent } from "./api";
import { type StationIssueLine, stationIssueLine } from "./mission-control";

// A linha "doing" de cada linha do roster (F.6). RUNNING/QUEUED/BLOCKED reusam
// a MESMA derivação do card do Mission Control (stationIssueLine); só o texto
// IDLE é próprio do roster ("último run concluído" se já rodou / "nunca
// executou"). Agente ainda fora do snapshot do agregado (recém-contratado,
// antes do próximo poll de 2s) → nota neutra, nunca uma afirmação errada.

export function rosterDoing(
  station: MissionControlAgent | undefined,
  issues: IssueSummary[],
): StationIssueLine {
  if (!station) {
    return { note: "sincronizando…", verb: null, seq: null, title: null };
  }
  if (station.status === "IDLE") {
    return {
      note: station.lastSteps.length ? "ocioso — último run concluído" : "ocioso — nunca executou",
      verb: null,
      seq: null,
      title: null,
    };
  }
  return stationIssueLine(station, issues);
}
