package com.example.AminalReport.entities.enums;

/**
 * Níveis de urgência. INSUFICIENTE não é um grau de gravidade: indica que o relato não trouxe
 * informação para decidir e que um humano deve revisar (spec Q-002).
 */
public enum EnumNivelUrgencia {

    NAO_URGENTE("Não Urgente"),      // Azul
    POUCO_URGENTE("Pouco Urgente"),  // Verde
    URGENTE("Urgente"),              // Amarelo
    MUITO_URGENTE("Muito Urgente"),  // Laranja
    EMERGENCIA("Emergência"),        // Vermelho
    INSUFICIENTE("Insuficiente");    // Cinza: exige revisão humana

    private final String descricao;

    EnumNivelUrgencia(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
