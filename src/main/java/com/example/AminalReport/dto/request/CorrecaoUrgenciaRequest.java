package com.example.AminalReport.dto.request;

import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.util.TextoUtils;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Correção do nível de urgência feita pela organização responsável (spec US6, RF-016).
 * {@code INSUFICIENTE} não é um nível de gravidade e é recusado pelo service.
 */
public record CorrecaoUrgenciaRequest(
        @NotNull(message = "Selecione o novo nível de urgência.")
        EnumNivelUrgencia nivel,
        @Size(max = CorrecaoUrgenciaRequest.MAX_MOTIVO, message = "O motivo deve ter no máximo 200 caracteres.")
        String motivo) {

    public static final int MAX_MOTIVO = 200;

    public CorrecaoUrgenciaRequest {
        motivo = TextoUtils.vazioParaNulo(motivo == null ? null : motivo.strip());
    }
}
