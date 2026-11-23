package com.example.AminalReport.service.urgencia;

import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;

/**
 * Mensagem recebida do agente. O construtor valida o contrato:
 * resultado inválido nunca chega ao service (CA-006, RF-009).
 */
public record ResultadoClassificacao(
        int versaoEsquema,
        String solicitacaoId,
        Long denunciaId,
        EnumNivelUrgencia nivel,
        String justificativa,
        double confianca,
        EnumOrigemClassificacao origem,
        String versaoClassificador,
        boolean revisaoManual,
        long latenciaMs,
        int tentativas) {

    public static final int MAX_JUSTIFICATIVA = 300;

    public ResultadoClassificacao {
        if (versaoEsquema != SolicitacaoClassificacao.VERSAO_ESQUEMA) {
            throw new IllegalArgumentException("versaoEsquema não suportada: " + versaoEsquema);
        }
        if (solicitacaoId == null || solicitacaoId.isBlank()) {
            throw new IllegalArgumentException("solicitacaoId é obrigatório");
        }
        if (denunciaId == null) {
            throw new IllegalArgumentException("denunciaId é obrigatório");
        }
        if (nivel == null) {
            throw new IllegalArgumentException("nivel é obrigatório");
        }
        if (justificativa == null || justificativa.isBlank() || justificativa.length() > MAX_JUSTIFICATIVA) {
            throw new IllegalArgumentException("justificativa deve ter de 1 a " + MAX_JUSTIFICATIVA + " caracteres");
        }
        if (Double.isNaN(confianca) || confianca < 0.0 || confianca > 1.0) {
            throw new IllegalArgumentException("confianca deve estar entre 0 e 1");
        }
        if (origem != EnumOrigemClassificacao.IA && origem != EnumOrigemClassificacao.REGRAS
                && origem != EnumOrigemClassificacao.FALLBACK) {
            throw new IllegalArgumentException("origem inválida para resultado do agente: " + origem);
        }
        if (versaoClassificador == null || versaoClassificador.isBlank()) {
            throw new IllegalArgumentException("versaoClassificador é obrigatória");
        }
    }
}
