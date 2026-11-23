package com.example.AminalReport.dto.request;

import com.example.AminalReport.entities.enums.EnumTipoAnimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

/** Dados do formulário de denúncia de maus-tratos / animal em situação de risco. */
public record DenunciaRequest(

        MultipartFile foto,

        @NotNull(message = "Selecione o tipo do animal.")
        EnumTipoAnimal tipoAnimal,

        @NotBlank(message = "Informe a descrição da situação.")
        @Size(max = DenunciaRequest.MAX_DESCRICAO, message = "A descrição deve ter no máximo 2000 caracteres.")
        String descricao,

        String cep,

        String rua,

        @NotBlank(message = "Informe o estado.")
        String estado,

        @NotBlank(message = "Informe o município.")
        String municipio,

        @NotBlank(message = "Informe o bairro.")
        String bairro,

        String pontoRef) {

    /** Limite da descrição (spec Q-014); também aplicado no formulário e, como defesa, no agente. */
    public static final int MAX_DESCRICAO = 2000;
}
