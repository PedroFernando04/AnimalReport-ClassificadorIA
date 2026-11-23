package com.example.AminalReport.mapper;

import com.example.AminalReport.dto.request.DenunciaAlteracaoRequest;
import com.example.AminalReport.dto.request.DenunciaRequest;
import com.example.AminalReport.dto.request.DenunciaUrgenteRequest;
import com.example.AminalReport.dto.response.DenunciaResponse;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.enums.EnumTipoAnimal;
import com.example.AminalReport.entities.formularios.Denuncia;
import com.example.AminalReport.entities.usuarios.Organizacao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenunciaMapperTest {

    private final DenunciaMapper mapper = new DenunciaMapper();

    @Test
    void toEntityMapeiaEnderecoESemUrgencia() {
        var request = new DenunciaRequest(null, EnumTipoAnimal.GATO, "Ferido",
                "57000-000", "Rua A", "AL", "Maceió", "Centro", "Perto da praça");

        Denuncia denuncia = mapper.toEntity(request);

        // a urgência não vem do formulário: é definida pelo agente de classificação
        assertNull(denuncia.getUrgencia());
        assertEquals("Maceió", denuncia.getMunicipio());
        assertEquals("Centro", denuncia.getBairro());
        assertEquals("Perto da praça", denuncia.getPontoRef());
        // andamento inicial é regra de negócio do service
        assertNull(denuncia.getAndamentoDenuncia());
    }

    @Test
    void toEntityUrgenteCopiaApenasCamposInformados() {
        var request = new DenunciaUrgenteRequest(null, EnumTipoAnimal.CACHORRO, "Atropelado", "8299999-0000");

        Denuncia denuncia = mapper.toEntity(request);

        assertEquals("8299999-0000", denuncia.getContato());
        assertNull(denuncia.getCep());
        assertNull(denuncia.getUrgencia());
    }

    @Test
    void atualizarEntidadeIgnoraCamposNaoInformados() {
        Denuncia denuncia = new Denuncia();
        denuncia.setDescricao("Original");
        denuncia.setUrgencia(EnumNivelUrgencia.URGENTE);
        denuncia.setPontoRef("Ref");

        mapper.atualizarEntidade(denuncia, new DenunciaAlteracaoRequest("", null, null));

        assertEquals("Original", denuncia.getDescricao());
        // a edição nunca altera a urgência diretamente
        assertEquals(EnumNivelUrgencia.URGENTE, denuncia.getUrgencia());
        assertEquals("Ref", denuncia.getPontoRef());
    }

    @Test
    void toResponseIdentificaDenunciaRapidaPeloCepFixo() {
        Denuncia rapida = new Denuncia();
        rapida.setCep(Denuncia.CEP_DENUNCIA_URGENTE);
        Denuncia comum = new Denuncia();
        comum.setCep("57000-000");

        assertTrue(mapper.toResponse(rapida).denunciaRapida());
        assertFalse(mapper.toResponse(comum).denunciaRapida());
    }

    @Test
    void toResponseExpoeEstadoDaClassificacao() {
        Denuncia pendente = new Denuncia();
        pendente.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);

        DenunciaResponse r = mapper.toResponse(pendente);

        assertTrue(r.classificacaoPendente());
        assertNull(r.urgencia());
        assertFalse(r.revisaoManual());
    }

    @Test
    void toResponseTrataOrigemVaziaComoUsuario() {
        Denuncia urgente = new Denuncia();
        urgente.setUrgencia(EnumNivelUrgencia.EMERGENCIA);
        urgente.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);

        DenunciaResponse r = mapper.toResponse(urgente);

        assertFalse(r.classificacaoPendente());
        assertEquals(EnumOrigemClassificacao.USUARIO, r.origemClassificacao());
    }

    @Test
    void toResponseCopiaJustificativaConfiancaOrigemERevisao() {
        Denuncia d = new Denuncia();
        d.setUrgencia(EnumNivelUrgencia.INSUFICIENTE);
        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        d.setJustificativaUrgencia("Faltaram informações.");
        d.setConfiancaUrgencia(0.2);
        d.setOrigemClassificacao(EnumOrigemClassificacao.FALLBACK);
        d.setRevisaoManual(true);

        DenunciaResponse r = mapper.toResponse(d);

        assertEquals("Faltaram informações.", r.justificativaUrgencia());
        assertEquals(0.2, r.confiancaUrgencia(), 1e-9);
        assertEquals(EnumOrigemClassificacao.FALLBACK, r.origemClassificacao());
        assertTrue(r.revisaoManual());
    }

    @Test
    void toResponseExpoeResumoDaOrganizacaoResponsavel() {
        Organizacao ong = new Organizacao();
        ong.setNome("ONG Patas");
        Denuncia denuncia = new Denuncia();
        denuncia.setOrganizacaoResponsavel(ong);

        DenunciaResponse response = mapper.toResponse(denuncia);

        assertEquals("ONG Patas", response.organizacaoResponsavel().nome());
        assertNull(mapper.toResponse(new Denuncia()).organizacaoResponsavel());
    }
}
