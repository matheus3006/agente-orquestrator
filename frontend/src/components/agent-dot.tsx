import { agentHue } from "@/lib/agent-hue";
import { cn } from "@/lib/utils";

/** O avatar quadrado do agente (drawer, chips, roster) — hue por nome. */
export function AgentDot({ name, className }: { name: string; className?: string }) {
  return (
    <span className={cn("agent-dot", className)} style={{ background: agentHue(name) }}>
      {name.slice(0, 2).toUpperCase()}
    </span>
  );
}
