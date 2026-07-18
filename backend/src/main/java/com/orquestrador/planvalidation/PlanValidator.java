package com.orquestrador.planvalidation;

import com.orquestrador.org.roletemplate.RoleTemplateService;

import java.util.List;

/**
 * O seam do validador auxiliar (docs §2). Recebe o plano-PROSA que o arquiteto escreveu e as
 * checagens do molde do papel dele; devolve um veredito factual por checagem.
 *
 * <p>Contrato de risco: <b>nunca lança</b> e <b>nunca bloqueia</b> (Inviolável §3.6). Qualquer
 * falha do modelo local vira {@link Presence#INDETERMINATE} naquela checagem — o run segue normal.
 */
public interface PlanValidator {

    List<PlanCheckVerdict> validate(String plan, List<RoleTemplateService.Check> checks);
}
