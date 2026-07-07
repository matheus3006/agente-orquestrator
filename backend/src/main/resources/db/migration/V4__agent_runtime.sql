-- Fase 1, slice 3: config de runtime do agente (qual adapter, comando, onde executa).
alter table agent add column adapter_type     text not null default 'process';
alter table agent add column runtime_command  text;
alter table agent add column execution_target text not null default 'LOCAL';
