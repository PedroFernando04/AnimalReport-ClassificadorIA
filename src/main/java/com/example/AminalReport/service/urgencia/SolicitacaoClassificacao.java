package com.example.AminalReport.service.urgencia;

/**
 * Mensagem enviada ao agente.
 * Contém SOMENTE tipo de animal, descrição e ponto de referência: nunca contato, usuário,
 * foto ou endereço (RF-003, RQ-003). Não acrescente campos sem atualizar o contrato no plan.md.
 */
public record SolicitacaoClassificacao(
        int versaoEsquema,
        String solicitacaoId,
        Long denunciaId,
        String tipoAnimal,
        String descricao,
        String pontoRef) {

    public static final int VERSAO_ESQUEMA = 1;

    public static SolicitacaoClassificacao nova(String solicitacaoId, Long denunciaId, String tipoAnimal,
                                                String descricao, String pontoRef) {
        return new SolicitacaoClassificacao(VERSAO_ESQUEMA, solicitacaoId, denunciaId, tipoAnimal, descricao, pontoRef);
    }
}
