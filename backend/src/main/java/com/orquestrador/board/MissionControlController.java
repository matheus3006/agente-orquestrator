package com.orquestrador.board;

import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F.0: a home da company num poll — GET /mission-control devolve o snapshot agregado
 * ({@link MissionControlService}). Board-only: superfície de observação do operador.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/mission-control")
public class MissionControlController {

    private final MissionControlService missionControl;
    private final CompanyAccessGuard guard;

    public MissionControlController(MissionControlService missionControl, CompanyAccessGuard guard) {
        this.missionControl = missionControl;
        this.guard = guard;
    }

    @GetMapping
    public MissionControlService.Snapshot snapshot(@PathVariable UUID companyId) {
        guard.assertBoardMember(companyId);
        return missionControl.snapshot(companyId);
    }
}
