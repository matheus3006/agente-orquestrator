package com.orquestrador.context;

import com.orquestrador.execution.RunContext;
import com.orquestrador.execution.adapter.WorkspaceConvention;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.memory.MemoryAudience;
import com.orquestrador.skills.CompanySkillVersion;

import java.nio.file.Path;
import java.util.List;

/** Única entrada do executor para preparar, publicar e manifestar o contexto canônico de um Run. */
public interface RunContextCompiler {

    ContextPackage compile(Request request);

    record Request(RunContext runContext, Path workDir, WorkspaceConvention convention,
                   List<CompanySkillVersion> skills, MemoryAudience audience, ExecutionTarget target,
                   String image, String volumeName, String prompt, String outputSchema) {

        public Request {
            skills = List.copyOf(skills);
        }
    }
}
