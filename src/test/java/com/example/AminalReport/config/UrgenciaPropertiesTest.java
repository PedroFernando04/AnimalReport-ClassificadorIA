package com.example.AminalReport.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** T003: valores padrão e binding das propriedades urgencia.* (sem segredo). */
class UrgenciaPropertiesTest {

    @Test
    void padroesSaoOsDoPlano() {
        UrgenciaProperties p = new UrgenciaProperties();
        assertEquals(180, p.getPrazoMaximoSegundos());
        assertEquals(0.6, p.getLimiarConfianca(), 1e-9);
    }

    @Test
    void bindingLePropriedadesComHifen() {
        var fonte = new MapConfigurationPropertySource(Map.of(
                "urgencia.prazo-maximo-segundos", "5",
                "urgencia.limiar-confianca", "0.8"));
        UrgenciaProperties p = new Binder(fonte).bind("urgencia", UrgenciaProperties.class).get();
        assertEquals(5, p.getPrazoMaximoSegundos());
        assertEquals(0.8, p.getLimiarConfianca(), 1e-9);
    }
}
