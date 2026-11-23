package com.example.AminalReport.dto.response;

import com.example.AminalReport.entities.enums.EnumAndamentoDenuncia;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.enums.EnumTipoAnimal;

/**
 * Dados de uma denúncia prontos para as views.
 *
 * @param imagemUrl      URL pronta para uso em {@code <img>}, já com imagem padrão quando não há foto
 * @param urgencia       vazia enquanto {@code classificacaoPendente}; pode ser INSUFICIENTE
 * @param origemClassificacao nunca nula: denúncias sem origem gravada aparecem como USUARIO
 * @param denunciaRapida {@code true} quando veio do formulário de denúncia urgente
 *                       (sem endereço; exibe o contato em vez da localização)
 */
public record DenunciaResponse(
        Long id,
        String foto,
        String imagemUrl,
        EnumTipoAnimal tipoAnimal,
        String descricao,
        String contato,
        EnumNivelUrgencia urgencia,
        EnumStatusClassificacao statusClassificacao,
        boolean classificacaoPendente,
        String justificativaUrgencia,
        Double confiancaUrgencia,
        EnumOrigemClassificacao origemClassificacao,
        boolean revisaoManual,
        EnumAndamentoDenuncia andamentoDenuncia,
        String cep,
        String rua,
        String bairro,
        String municipio,
        String estado,
        String pontoRef,
        boolean denunciaRapida,
        OrganizacaoResumoResponse organizacaoResponsavel) {
}
