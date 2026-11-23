package com.example.AminalReport.entities.enums;

/** Estado da classificação automática de uma denúncia. */
public enum EnumStatusClassificacao {
    PENDENTE("Em classificação"),
    CONCLUIDA("Classificada");

    private final String descricao;

    EnumStatusClassificacao(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
