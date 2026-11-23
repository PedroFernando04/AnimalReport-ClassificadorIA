package com.example.AminalReport.service.urgencia;

import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** T009: contrato das mensagens. Lê os mesmos JSONs de exemplo usados pelos testes Python. */
class ResultadoClassificacaoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String exemplo(String nome) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/exemplos/" + nome)) {
            assertNotNull(in, "exemplo ausente: " + nome);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void resultadoValidoEhLidoComTodosOsCampos() throws Exception {
        ResultadoClassificacao r = mapper.readValue(exemplo("resultado_valido.json"), ResultadoClassificacao.class);
        assertEquals(EnumNivelUrgencia.EMERGENCIA, r.nivel());
        assertEquals(EnumOrigemClassificacao.IA, r.origem());
        assertEquals(42L, r.denunciaId());
        assertEquals(0.93, r.confianca(), 1e-9);
        assertFalse(r.revisaoManual());
    }

    @Test
    void insuficienteComRevisaoManualEhValido() throws Exception {
        ResultadoClassificacao r = mapper.readValue(exemplo("resultado_insuficiente.json"), ResultadoClassificacao.class);
        assertEquals(EnumNivelUrgencia.INSUFICIENTE, r.nivel());
        assertEquals(EnumOrigemClassificacao.FALLBACK, r.origem());
        assertTrue(r.revisaoManual());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "resultado_invalido_nivel.json",
            "resultado_invalido_confianca.json",
            "resultado_invalido_sem_justificativa.json",
            "resultado_invalido_justificativa_longa.json"})
    void resultadoInvalidoEhRejeitado(String nome) {
        assertThrows(Exception.class, () -> mapper.readValue(exemplo(nome), ResultadoClassificacao.class));
    }

    @Test
    void origemDeUsuarioOuOrganizacaoNaoVemDoAgente() {
        assertThrows(IllegalArgumentException.class, () -> new ResultadoClassificacao(
                1, "7c9e6679-7425", 1L, EnumNivelUrgencia.URGENTE, "ok", 0.5,
                EnumOrigemClassificacao.ORGANIZACAO, "v", false, 1, 1));
    }

    @Test
    void solicitacaoSerializaSomenteOsCamposDoContrato() throws Exception {
        SolicitacaoClassificacao s = SolicitacaoClassificacao.nova(
                "7c9e6679-7425-40de-944b-e07fc1f90ae7", 42L, "CACHORRO",
                "Cachorro atropelado na avenida, sangrando e sem conseguir levantar", "Em frente ao mercado");
        JsonNode esperado = mapper.readTree(exemplo("solicitacao_valida.json"));
        assertEquals(esperado, mapper.valueToTree(s));
    }
}
