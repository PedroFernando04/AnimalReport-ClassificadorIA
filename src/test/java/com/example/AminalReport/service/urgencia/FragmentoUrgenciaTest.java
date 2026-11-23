package com.example.AminalReport.service.urgencia;

import com.example.AminalReport.dto.response.DenunciaResponse;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.formularios.Denuncia;
import com.example.AminalReport.mapper.DenunciaMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T020 e T037: o fragmento mostra o estado em texto, a justificativa e a revisão manual (RQ-007). */
class FragmentoUrgenciaTest {

    private static SpringTemplateEngine engine;
    private final DenunciaMapper mapper = new DenunciaMapper();

    @BeforeAll
    static void motor() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    private String renderizar(DenunciaResponse denuncia) {
        Context ctx = new Context();
        ctx.setVariable("denuncia", denuncia);
        return engine.process("teste-urgencia-selo", ctx);
    }

    @Test
    void pendenteMostraEmClassificacaoSemNivel() {
        Denuncia d = new Denuncia();
        d.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);

        String html = renderizar(mapper.toResponse(d));

        assertTrue(html.contains("Em classificação"));
        assertFalse(html.contains("Sem classificação"));
    }

    @Test
    void classificadaMostraNivelEmTextoEJustificativa() {
        Denuncia d = new Denuncia();
        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        d.setUrgencia(EnumNivelUrgencia.EMERGENCIA);
        d.setJustificativaUrgencia("Animal atropelado e sangrando.");
        d.setOrigemClassificacao(EnumOrigemClassificacao.IA);

        String html = renderizar(mapper.toResponse(d));

        assertTrue(html.contains("Emergência"));
        assertTrue(html.contains("Animal atropelado e sangrando."));
        assertTrue(html.contains("Classificada por IA"));
        assertFalse(html.contains("Em classificação"));
    }

    @Test
    void insuficienteMostraTextoERevisaoManual() {
        Denuncia d = new Denuncia();
        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        d.setUrgencia(EnumNivelUrgencia.INSUFICIENTE);
        d.setRevisaoManual(true);
        d.setOrigemClassificacao(EnumOrigemClassificacao.FALLBACK);

        String html = renderizar(mapper.toResponse(d));

        assertTrue(html.contains("Insuficiente"));
        assertTrue(html.contains("Revisão manual necessária"));
    }

    @Test
    void denunciaDoFluxoUrgenteMostraEmergenciaSemJustificativa() {
        Denuncia d = new Denuncia();
        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        d.setUrgencia(EnumNivelUrgencia.EMERGENCIA);

        String html = renderizar(mapper.toResponse(d));

        assertTrue(html.contains("Emergência"));
        assertFalse(html.contains("Justificativa:"));
    }
}
