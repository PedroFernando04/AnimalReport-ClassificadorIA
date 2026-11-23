package com.example.AminalReport.service.urgencia;

import com.example.AminalReport.config.UrgenciaProperties;
import com.example.AminalReport.service.DenunciaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chama o agente de urgência (Python: regras, modelo ou híbrido) por HTTP.
 * Depois do commit da denúncia, chama o agente em outra thread e aplica o resultado.
 * Se o agente não responder (fora do ar, dormindo além do tempo, erro), a denúncia recebe
 * INSUFICIENTE com revisão manual (ou mantém o nível anterior, se for reclassificação).
 * O log traz apenas identificadores, nunca o texto do relato.
 */
@Component
public class HttpClassificador {

    private static final Logger log = LoggerFactory.getLogger(HttpClassificador.class);

    private final RestClient client;
    private final DenunciaService denunciaService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public HttpClassificador(UrgenciaProperties props, DenunciaService denunciaService) {
        this.denunciaService = denunciaService;
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        fabrica.setReadTimeout(Duration.ofSeconds(Math.max(30, props.getPrazoMaximoSegundos() - 20)));
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(fabrica)
                .baseUrl(props.getAgenteUrl().replaceAll("/+$", ""));
        if (props.getAgenteToken() != null && !props.getAgenteToken().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + props.getAgenteToken());
        }
        this.client = builder.build();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoSolicitar(ClassificacaoSolicitadaEvent evento) {
        executor.submit(() -> classificar(evento.solicitacao()));
    }

    @PreDestroy
    void encerrar() {
        executor.shutdown();
    }

    void classificar(SolicitacaoClassificacao s) {
        ResultadoClassificacao resultado;
        try {
            resultado = client.post().uri("/classificar")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(s)
                    .retrieve()
                    .body(ResultadoClassificacao.class);
            if (resultado == null) {
                throw new IllegalStateException("resposta vazia");
            }
        } catch (Exception e) {
            log.warn("Agente indisponível: denunciaId={} solicitacaoId={} erro={}",
                    s.denunciaId(), s.solicitacaoId(), e.getClass().getSimpleName());
            registrarFalha(s);
            return;
        }
        try {
            denunciaService.aplicarClassificacao(resultado);
        } catch (Exception e) {
            log.error("Falha ao aplicar classificação: denunciaId={} erro={}", s.denunciaId(), e.getClass().getSimpleName());
        }
    }

    /**
     * Quem decide o que fazer sem resultado é o service: INSUFICIENTE se nunca houve nível,
     * ou manter o nível anterior com revisão manual se for uma reclassificação.
     */
    private void registrarFalha(SolicitacaoClassificacao s) {
        try {
            denunciaService.registrarFalhaClassificacao(s.denunciaId(), s.solicitacaoId());
        } catch (Exception e) {
            log.error("Falha ao registrar indisponibilidade: denunciaId={} erro={}",
                    s.denunciaId(), e.getClass().getSimpleName());
        }
    }
}
