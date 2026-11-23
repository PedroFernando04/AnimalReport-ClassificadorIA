package com.example.AminalReport.service.urgencia;

/** Evento interno: uma solicitação de classificação deve ser publicada depois do commit. */
public record ClassificacaoSolicitadaEvent(SolicitacaoClassificacao solicitacao) {
}
