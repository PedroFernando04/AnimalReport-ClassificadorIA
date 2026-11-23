package com.example.AminalReport.entities.enums;

/** De onde veio o nível de urgência gravado na denúncia. */
public enum EnumOrigemClassificacao {
    IA("Classificada por IA"),
    REGRAS("Classificada por regras"),
    FALLBACK("Nível automático por falta de informação ou prazo"),
    USUARIO("Informada pelo usuário"),
    ORGANIZACAO("Corrigida pela organização");

    private final String descricao;

    EnumOrigemClassificacao(String descricao) {
        this.descricao = descricao;
    }

    public String getDescricao() {
        return descricao;
    }
}
