package com.orquestrador.skills;

import com.orquestrador.execution.adapter.WorkspaceConvention;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materializa o hook PreToolUse mínimo (F-N6/ADR-0042, D6 skills read-only + D7 cinto de sessão)
 * no workspace de um Run, atrás do seam {@code WorkspaceConvention} (ADR-0032) — irmão do
 * {@link SkillMaterializer}. Confirmado empiricamente contra os CLIs reais (claude 2.1.206 /
 * codex 0.142.5): os DOIS motores aceitam a MESMA forma de configuração
 * {@code {"hooks":{"PreToolUse":[{"matcher":...,"hooks":[{"type":"command","command":...}]}]}}}
 * — só o ARQUIVO muda ({@code .claude/settings.json} bloco `hooks` × {@code .codex/hooks.json}
 * dono do arquivo inteiro) — então o MESMO script guarda (Node — garantido no PATH nos 2
 * Dockerfiles {@code node:22-bookworm-slim} e no host de qualquer dev, já que os 2 CLIs são
 * pacotes npm) serve os dois motores sem nenhum if/else por engine. IDEMPOTENTE: reescreve os
 * 2 arquivos a cada Run (espelho do SkillMaterializer).
 */
@Component
public class HookMaterializer {

    /**
     * O comando do hook é RELATIVO ao {@code cwd} do Run (confirmado empiricamente: claude e
     * codex resolvem {@code command} relativo à raiz do workspace) — portável entre LOCAL (workDir
     * do host) e DOCKER ({@code /workspace}), o mesmo motivo do resto da materialização por-run.
     * Superset de tool names dos 2 motores (claude: Write/Edit/Read/Bash; codex: Bash/apply_patch)
     * — alternância "|" ignora silenciosamente os nomes que o motor corrente não usa.
     */
    private static final String MATCHER = "Write|Edit|Read|Bash|apply_patch";

    /**
     * O script guarda: nega (a) Write/Edit/apply_patch nos paths de skills materializadas (D6) e
     * (b) leitura (Read/Bash/apply_patch) dos diretórios de sessão dos motores fora do workspace
     * (D7 — cinto, não isolamento real). {@code fail-open} em payload ilegível ou tool_name não
     * reconhecido: o hook eleva a barra, nunca deve travar um run por bug do próprio guarda.
     *
     * <p>Ressalva honesta (ADR-0042): a checagem de leitura em Bash/apply_patch é um SUBSTRING
     * heurístico sobre o texto do comando (absoluto, {@code ~/} e {@code $HOME/}) — não resolve
     * symlink nem entende shell de verdade (encode/indirection escapa). Glob/Grep não são
     * cobertos (fora do mínimo D6/D7 — candidata pós-v1, junto do enforcement amplo).
     */
    static final String GUARD_SCRIPT = """
            'use strict';
            const path = require('path');
            const os = require('os');

            const DENY_WRITE_SUBDIRS = ['.claude/skills', '.codex/skills'];
            const DENY_READ_DIRS = [
              path.join(os.homedir(), '.claude', 'projects'),
              path.join(os.homedir(), '.codex', 'sessions'),
            ];

            let raw = '';
            process.stdin.on('data', chunk => { raw += chunk; });
            process.stdin.on('end', () => {
              let input;
              try {
                input = JSON.parse(raw);
              } catch {
                process.exit(0);
              }
              const cwd = input.cwd || process.cwd();
              const toolName = input.tool_name || '';
              const toolInput = input.tool_input || {};

              for (const target of mutationTargets(toolName, toolInput, cwd)) {
                for (const subdir of DENY_WRITE_SUBDIRS) {
                  const denied = path.resolve(cwd, subdir);
                  if (isUnder(target, denied)) {
                    deny(`skills sao read-only (D6/ADR-0042): ${toolName} negado em ${target}`);
                  }
                }
              }

              const text = readableText(toolName, toolInput);
              for (const dir of DENY_READ_DIRS) {
                if (text.includes(dir) || text.includes(dir.replace(os.homedir(), '~'))
                    || text.includes(dir.replace(os.homedir(), '$HOME'))) {
                  deny(`leitura do diretorio de sessao do motor fora do workspace negada (D7/ADR-0042): ${dir}`);
                }
              }

              process.exit(0);
            });

            function mutationTargets(toolName, toolInput, cwd) {
              if (toolName === 'Write' || toolName === 'Edit') {
                return toolInput.file_path ? [path.resolve(cwd, toolInput.file_path)] : [];
              }
              if (toolName === 'apply_patch') {
                const body = toolInput.command || '';
                const targets = [];
                const re = /^\\*\\*\\* (?:Add File|Update File|Delete File|Move to): (.+)$/gm;
                let m;
                while ((m = re.exec(body)) !== null) {
                  targets.push(path.resolve(cwd, m[1].trim()));
                }
                return targets;
              }
              return [];
            }

            function readableText(toolName, toolInput) {
              if (toolName === 'Read') {
                return toolInput.file_path || '';
              }
              if (toolName === 'Bash' || toolName === 'apply_patch') {
                return toolInput.command || '';
              }
              return '';
            }

            function isUnder(target, root) {
              const rel = path.relative(root, target);
              return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
            }

            function deny(reason) {
              process.stderr.write(reason + '\\n');
              process.exit(2);
            }
            """;

    public void materialize(Path workDir, WorkspaceConvention convention) {
        Path scriptPath = workDir.resolve(convention.hooksScript());
        Path configPath = workDir.resolve(convention.hooksFile());
        try {
            Files.createDirectories(scriptPath.getParent());
            Files.writeString(scriptPath, GUARD_SCRIPT, StandardCharsets.UTF_8);
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, configJson(convention.hooksScript()), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("falha ao materializar hooks PreToolUse em " + workDir, exception);
        }
    }

    private static String configJson(String scriptRelativePath) {
        return """
                {
                  "hooks": {
                    "PreToolUse": [
                      {
                        "matcher": "%s",
                        "hooks": [
                          { "type": "command", "command": "node %s" }
                        ]
                      }
                    ]
                  }
                }
                """.formatted(MATCHER, scriptRelativePath);
    }
}
