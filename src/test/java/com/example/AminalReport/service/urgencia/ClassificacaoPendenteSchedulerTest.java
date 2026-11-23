package com.example.AminalReport.service.urgencia;

import com.example.AminalReport.config.UrgenciaProperties;
import com.example.AminalReport.service.DenunciaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/** T036: o agendador usa o prazo configurado e nunca propaga exceção. */
@ExtendWith(MockitoExtension.class)
class ClassificacaoPendenteSchedulerTest {

    @Mock private DenunciaService denunciaService;

    @Test
    void verificarUsaOPrazoMaximoConfigurado() {
        UrgenciaProperties props = new UrgenciaProperties();
        props.setPrazoMaximoSegundos(120);
        when(denunciaService.expirarClassificacoesPendentes(120)).thenReturn(3);

        new ClassificacaoPendenteScheduler(denunciaService, props).verificar();

        verify(denunciaService).expirarClassificacoesPendentes(120);
    }

    @Test
    void falhaDeBancoNaoDerrubaOAgendador() {
        when(denunciaService.expirarClassificacoesPendentes(anyInt())).thenThrow(new IllegalStateException("banco fora"));

        assertDoesNotThrow(() -> new ClassificacaoPendenteScheduler(denunciaService, new UrgenciaProperties()).verificar());
    }
}
