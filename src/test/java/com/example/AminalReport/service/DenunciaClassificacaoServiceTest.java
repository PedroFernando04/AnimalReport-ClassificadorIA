package com.example.AminalReport.service;

import com.example.AminalReport.dto.request.DenunciaRequest;
import com.example.AminalReport.dto.request.DenunciaUrgenteRequest;
import com.example.AminalReport.entities.enums.EnumAndamentoDenuncia;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.enums.EnumTipoAnimal;
import com.example.AminalReport.entities.formularios.Denuncia;
import com.example.AminalReport.mapper.DenunciaMapper;
import com.example.AminalReport.repository.formularios.DenunciaRepository;
import com.example.AminalReport.repository.logs.LogRepository;
import com.example.AminalReport.repository.usuarios.OrganizacaoRepository;
import com.example.AminalReport.repository.usuarios.UserRepository;
import com.example.AminalReport.service.urgencia.ClassificacaoSolicitadaEvent;
import com.example.AminalReport.service.urgencia.ResultadoClassificacao;
import com.example.AminalReport.service.urgencia.SolicitacaoClassificacao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Cobre T017, T018 e T028: registro pendente, aplicação de resultados e fluxo urgente inalterado. */
@ExtendWith(MockitoExtension.class)
class DenunciaClassificacaoServiceTest {

    private static final String SOLICITACAO = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Mock private DenunciaRepository denunciaRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrganizacaoRepository organizacaoRepository;
    @Mock private UploadService uploadService;
    @Mock private ApplicationEventPublisher eventos;
    @Mock private LogRepository logRepository;

    private DenunciaService service;

    @BeforeEach
    void preparar() {
        service = new DenunciaService(denunciaRepository, userRepository, organizacaoRepository,
                uploadService, new DenunciaMapper(), eventos, logRepository);
    }

    private DenunciaRequest formulario() {
        return new DenunciaRequest(null, EnumTipoAnimal.CACHORRO,
                "Cachorro atropelado na avenida, sangrando e sem conseguir levantar",
                "57000-000", "Rua A", "AL", "Maceió", "Centro", "Em frente ao mercado");
    }

    private Denuncia pendente(Long id, String solicitacaoId) {
        Denuncia d = new Denuncia();
        ReflectionTestUtils.setField(d, "id", id);
        d.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        d.setSolicitacaoClassificacaoId(solicitacaoId);
        return d;
    }

    private ResultadoClassificacao resultado(String solicitacaoId, EnumNivelUrgencia nivel, boolean revisao) {
        return new ResultadoClassificacao(1, solicitacaoId, 42L, nivel, "Justificativa do agente.", 0.9,
                EnumOrigemClassificacao.IA, "modelo/prompt-v1", revisao, 800, 1);
    }

    // ---- registro (CA-001, RF-002) ----

    @Test
    void registrarSalvaPendenteSemUrgenciaEPublicaEvento() {
        when(denunciaRepository.save(any(Denuncia.class))).thenAnswer(inv -> {
            Denuncia d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", 42L);
            return d;
        });

        service.registrar(formulario(), null);

        ArgumentCaptor<Denuncia> salva = ArgumentCaptor.forClass(Denuncia.class);
        verify(denunciaRepository).save(salva.capture());
        Denuncia d = salva.getValue();
        assertNull(d.getUrgencia());
        assertEquals(EnumStatusClassificacao.PENDENTE, d.getStatusClassificacao());
        assertNotNull(d.getSolicitacaoClassificacaoId());
        assertNotNull(d.getClassificacaoSolicitadaEm());
        assertEquals(EnumAndamentoDenuncia.AGUARDANDO, d.getAndamentoDenuncia());

        ArgumentCaptor<ClassificacaoSolicitadaEvent> evento = ArgumentCaptor.forClass(ClassificacaoSolicitadaEvent.class);
        verify(eventos).publishEvent(evento.capture());
        SolicitacaoClassificacao s = evento.getValue().solicitacao();
        assertEquals(42L, s.denunciaId());
        assertEquals(d.getSolicitacaoClassificacaoId(), s.solicitacaoId());
        assertEquals("CACHORRO", s.tipoAnimal());
        assertEquals("Em frente ao mercado", s.pontoRef());
        assertTrue(s.descricao().contains("atropelado"));
    }

    // ---- minimização de dados (RF-003, RQ-003) ----

    @Test
    void solicitacaoTemSomenteOsCamposDoContrato() {
        Set<String> campos = Arrays.stream(SolicitacaoClassificacao.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(Set.of("versaoEsquema", "solicitacaoId", "denunciaId", "tipoAnimal", "descricao", "pontoRef"), campos);
    }

    // ---- aplicação do resultado (CA-001, CA-002, RF-004) ----

    @Test
    void aplicarGravaTodosOsCamposEConcluiAClassificacao() {
        Denuncia d = pendente(42L, SOLICITACAO);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));

        service.aplicarClassificacao(resultado(SOLICITACAO, EnumNivelUrgencia.EMERGENCIA, false));

        assertEquals(EnumNivelUrgencia.EMERGENCIA, d.getUrgencia());
        assertEquals("Justificativa do agente.", d.getJustificativaUrgencia());
        assertEquals(0.9, d.getConfiancaUrgencia(), 1e-9);
        assertEquals(EnumOrigemClassificacao.IA, d.getOrigemClassificacao());
        assertEquals("modelo/prompt-v1", d.getVersaoClassificador());
        assertFalse(d.isRevisaoManual());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        assertNotNull(d.getClassificadaEm());
        verify(denunciaRepository).save(d);
    }

    @Test
    void insuficienteSempreMarcaRevisaoManual() {
        Denuncia d = pendente(42L, SOLICITACAO);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));

        service.aplicarClassificacao(resultado(SOLICITACAO, EnumNivelUrgencia.INSUFICIENTE, false));

        assertEquals(EnumNivelUrgencia.INSUFICIENTE, d.getUrgencia());
        assertTrue(d.isRevisaoManual());
    }

    @Test
    void resultadoDeSolicitacaoAntigaEhDescartado() {
        Denuncia d = pendente(42L, "outra-solicitacao-mais-recente");
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));

        service.aplicarClassificacao(resultado(SOLICITACAO, EnumNivelUrgencia.EMERGENCIA, false));

        assertNull(d.getUrgencia());
        assertEquals(EnumStatusClassificacao.PENDENTE, d.getStatusClassificacao());
        verify(denunciaRepository, never()).save(any());
    }

    @Test
    void resultadoDuplicadoNaoSobrescreveClassificacaoConcluida() {
        Denuncia d = pendente(42L, SOLICITACAO);
        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        d.setUrgencia(EnumNivelUrgencia.URGENTE);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));

        service.aplicarClassificacao(resultado(SOLICITACAO, EnumNivelUrgencia.EMERGENCIA, false));

        assertEquals(EnumNivelUrgencia.URGENTE, d.getUrgencia());
        verify(denunciaRepository, never()).save(any());
    }

    @Test
    void resultadoDeDenunciaInexistenteNaoLancaExcecao() {
        when(denunciaRepository.findById(42L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.aplicarClassificacao(resultado(SOLICITACAO, EnumNivelUrgencia.URGENTE, false)));
        verify(denunciaRepository, never()).save(any());
    }

    // ---- fluxo urgente inalterado (RF-018) ----

    @Test
    void registrarUrgenteContinuaFixoEFicaForaDaClassificacao() {
        when(denunciaRepository.save(any(Denuncia.class))).thenAnswer(inv -> inv.getArgument(0));

        service.registrarUrgente(new DenunciaUrgenteRequest(null, EnumTipoAnimal.GATO, "Preso numa grade", "8299999-0000"), null);

        ArgumentCaptor<Denuncia> salva = ArgumentCaptor.forClass(Denuncia.class);
        verify(denunciaRepository).save(salva.capture());
        Denuncia d = salva.getValue();
        assertEquals(EnumNivelUrgencia.EMERGENCIA, d.getUrgencia());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        assertNull(d.getOrigemClassificacao());
        assertNull(d.getSolicitacaoClassificacaoId());
        verifyNoInteractions(eventos);
    }
}
