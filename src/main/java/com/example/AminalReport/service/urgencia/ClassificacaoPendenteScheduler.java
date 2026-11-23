package com.example.AminalReport.service.urgencia;

import com.example.AminalReport.config.UrgenciaProperties;
import com.example.AminalReport.service.DenunciaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Garante o prazo máximo da classificação (RF-014): denúncias que ficaram PENDENTE além de
 * {@code urgencia.prazo-maximo-segundos} (agente travado, resposta perdida, aplicação reiniciada)
 * são encerradas como INSUFICIENTE com revisão manual. A denúncia nunca fica "pendente para sempre".
 */
@Component
public class ClassificacaoPendenteScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClassificacaoPendenteScheduler.class);

    private final DenunciaService denunciaService;
    private final UrgenciaProperties props;

    public ClassificacaoPendenteScheduler(DenunciaService denunciaService, UrgenciaProperties props) {
        this.denunciaService = denunciaService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${urgencia.verificacao-intervalo-ms:30000}",
            initialDelayString = "${urgencia.verificacao-intervalo-ms:30000}")
    public void verificar() {
        try {
            int encerradas = denunciaService.expirarClassificacoesPendentes(props.getPrazoMaximoSegundos());
            if (encerradas > 0) {
                log.warn("Classificações pendentes encerradas por prazo: {}", encerradas);
            }
        } catch (Exception e) {
            // Uma falha de banco não pode derrubar o agendador: a próxima rodada tenta de novo.
            log.error("Falha ao verificar classificações pendentes: {}", e.getClass().getSimpleName());
        }
    }
}
