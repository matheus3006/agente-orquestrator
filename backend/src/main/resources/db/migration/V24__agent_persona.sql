-- Fatia 2 do v1 (ADR-0031, M1): discriminador de PERSONA do CEO. 3 linhas agent role='ceo'
-- distintas por persona (questionador/planejador/executor) — instituições duráveis, auditáveis e
-- substituíveis uma a uma (D1). Texto livre sem constraint, padrão dos alters de agent (V4/V12/V14/V15);
-- NULL = agente comum ou CEO legado pré-backfill (a V25 backfilla as companies existentes).

alter table agent add column persona text;
