package com.example.AminalReport.service.urgencia;

import com.example.AminalReport.config.UrgenciaProperties;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.service.DenunciaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T074: o cliente HTTP contra um agente falso (servidor do próprio JDK, sem dependência extra).
 * Cobre o contrato com o agente Python (SPEC-01 §contrato), CA-003, CA-006 e a minimização de dados (RF-003).
 */
@ExtendWith(MockitoExtension.class)
class HttpClassificadorTest {

    private static final String SOLICITACAO = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Mock private DenunciaService denunciaService;

    private HttpServer agente;
    private final AtomicReference<String> corpoRecebido = new AtomicReference<>();
    private final AtomicReference<String> autorizacaoRecebida = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String resposta = "";

    @BeforeEach
    void subirAgenteFalso() throws IOException {
        agente = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agente.createContext("/classificar", troca -> {
            corpoRecebido.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorizacaoRecebida.set(troca.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                troca.getResponseBody().write(bytes);
            }
            troca.close();
        });
        agente.start();
    }

    @AfterEach
    void pararAgenteFalso() {
        agente.stop(0);
    }

    private HttpClassificador cliente(String token) {
        return clienteApontandoPara("http://127.0.0.1:" + agente.getAddress().getPort(), token);
    }

    private HttpClassificador clienteApontandoPara(String url, String token) {
        UrgenciaProperties props = new UrgenciaProperties();
        props.setAgenteUrl(url);
        props.setAgenteToken(token);
        return new HttpClassificador(props, denunciaService);
    }

    private static SolicitacaoClassificacao solicitacao() {
        return SolicitacaoClassificacao.nova(SOLICITACAO, 42L, "CACHORRO",
                "Cachorro atropelado e sangrando", "Em frente ao mercado");
    }

    private static final String RESPOSTA_OK = """
            {"versaoEsquema":1,"solicitacaoId":"%s","denunciaId":42,"nivel":"EMERGENCIA",
             "justificativa":"Animal atropelado e sangrando.","confianca":0.95,"origem":"IA",
             "versaoClassificador":"modelo/prompt-v1","revisaoManual":false,"latenciaMs":812,"tentativas":1}
            """.formatted(SOLICITACAO);

    // ---- sucesso ----

    @Test
    void respostaValidaEAplicadaNaDenuncia() {
        resposta = RESPOSTA_OK;

        cliente("").classificar(solicitacao());

        ArgumentCaptor<ResultadoClassificacao> captor = ArgumentCaptor.forClass(ResultadoClassificacao.class);
        verify(denunciaService).aplicarClassificacao(captor.capture());
        ResultadoClassificacao r = captor.getValue();
        assertEquals(EnumNivelUrgencia.EMERGENCIA, r.nivel());
        assertEquals(EnumOrigemClassificacao.IA, r.origem());
        assertEquals(SOLICITACAO, r.solicitacaoId());
        assertEquals(42L, r.denunciaId());
        assertEquals("modelo/prompt-v1", r.versaoClassificador());
        verify(denunciaService, never()).registrarFalhaClassificacao(any(), any());
    }

    @Test
    void camposDesconhecidosNaRespostaSaoIgnorados() {
        resposta = RESPOSTA_OK.replace("\"tentativas\":1", "\"tentativas\":1,\"campoFuturo\":\"x\"");

        cliente("").classificar(solicitacao());

        verify(denunciaService).aplicarClassificacao(any(ResultadoClassificacao.class));
    }

    // ---- o que sai da aplicação (RF-003, RQ-003) ----

    @Test
    void corpoEnviadoTemSomenteOsCamposDoContrato() throws Exception {
        resposta = RESPOSTA_OK;

        cliente("").classificar(solicitacao());

        JsonNode corpo = new ObjectMapper().readTree(corpoRecebido.get());
        Set<String> campos = new HashSet<>();
        corpo.fieldNames().forEachRemaining(campos::add);
        assertEquals(Set.of("versaoEsquema", "solicitacaoId", "denunciaId", "tipoAnimal", "descricao", "pontoRef"), campos);
        assertEquals("CACHORRO", corpo.get("tipoAnimal").asText());
        assertEquals(1, corpo.get("versaoEsquema").asInt());
    }

    @Test
    void tokenConfiguradoVaiComoBearerESemTokenNaoHaCabecalho() {
        resposta = RESPOSTA_OK;

        cliente("segredo-123").classificar(solicitacao());
        assertEquals("Bearer segredo-123", autorizacaoRecebida.get());

        cliente("").classificar(solicitacao());
        assertNull(autorizacaoRecebida.get());
    }

    // ---- falhas: o service decide o que fazer (CA-003, CA-006) ----

    @Test
    void erro500DoAgenteRegistraFalha() {
        status = 500;
        resposta = "{\"detail\":\"erro interno\"}";

        cliente("").classificar(solicitacao());

        verify(denunciaService).registrarFalhaClassificacao(42L, SOLICITACAO);
        verify(denunciaService, never()).aplicarClassificacao(any());
    }

    @Test
    void respostaVaziaRegistraFalha() {
        status = 200;
        resposta = "";

        cliente("").classificar(solicitacao());

        verify(denunciaService).registrarFalhaClassificacao(42L, SOLICITACAO);
    }

    @Test
    void nivelForaDaListaEhRecusadoERegistraFalha() {
        resposta = RESPOSTA_OK.replace("EMERGENCIA", "GRAVISSIMO");

        cliente("").classificar(solicitacao());

        verify(denunciaService).registrarFalhaClassificacao(42L, SOLICITACAO);
        verify(denunciaService, never()).aplicarClassificacao(any());
    }

    @Test
    void versaoDeEsquemaDesconhecidaEhRecusada() {
        resposta = RESPOSTA_OK.replace("\"versaoEsquema\":1", "\"versaoEsquema\":2");

        cliente("").classificar(solicitacao());

        verify(denunciaService).registrarFalhaClassificacao(42L, SOLICITACAO);
    }

    @Test
    void agenteForaDoArRegistraFalha() throws IOException {
        int portaFechada;
        try (ServerSocket socket = new ServerSocket(0)) {
            portaFechada = socket.getLocalPort();
        }

        clienteApontandoPara("http://127.0.0.1:" + portaFechada, "").classificar(solicitacao());

        verify(denunciaService).registrarFalhaClassificacao(42L, SOLICITACAO);
    }

    @Test
    void falhaAoRegistrarIndisponibilidadeNaoPropagaExcecao() {
        status = 500;
        doThrow(new IllegalStateException("banco fora")).when(denunciaService).registrarFalhaClassificacao(any(), any());

        assertDoesNotThrow(() -> cliente("").classificar(solicitacao()));
    }

    @Test
    void falhaAoAplicarResultadoNaoPropagaExcecao() {
        resposta = RESPOSTA_OK;
        doThrow(new IllegalStateException("banco fora")).when(denunciaService).aplicarClassificacao(any());

        assertDoesNotThrow(() -> cliente("").classificar(solicitacao()));
    }
}
