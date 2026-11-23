package com.example.AminalReport.service;

import com.example.AminalReport.dto.request.DenunciaAlteracaoRequest;
import com.example.AminalReport.dto.request.DenunciaRequest;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.formularios.Denuncia;
import com.example.AminalReport.mapper.DenunciaMapper;
import com.example.AminalReport.repository.formularios.DenunciaRepository;
import com.example.AminalReport.repository.logs.LogRepository;
import com.example.AminalReport.repository.usuarios.OrganizacaoRepository;
import com.example.AminalReport.repository.usuarios.UserRepository;
import com.example.AminalReport.service.urgencia.ClassificacaoSolicitadaEvent;
import com.example.AminalReport.service.urgencia.ResultadoClassificacao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * US4 (reclassificação ao editar), prazo máximo e falha do agente: T048, T049, T050, T036.
 * Cobre CA-007, CA-008, CA-014, RF-008, RF-014, RF-017 e as decisões Q-012/Q-017.
 */
@ExtendWith(MockitoExtension.class)
class DenunciaReclassificacaoTest {

    private static final String SOLICITACAO_ANTIGA = "11111111-1111-1111-1111-111111111111";

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

    /** Denúncia já classificada pelo agente, como no estado normal depois do registro. */
    private Denuncia classificada() {
        Denuncia d = new Denuncia();
        ReflectionTestUtils.setField(d, "id", 42L);
        d.setDescricao("Cachorro sem água no quintal");
        d.setPontoRef("Perto da praça");
        d.setCep("57000-000");
        d.setUrgencia(EnumNivelUrgencia.URGENTE);
        d.setJustificativaUrgencia("Sem água há dias.");
        d.setConfiancaUrgencia(0.8);
        d.setOrigemClassificacao(EnumOrigemClassificacao.IA);
        d.setVersaoClassificador("modelo/prompt-v1");
        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        d.setSolicitacaoClassificacaoId(SOLICITACAO_ANTIGA);
        d.setClassificadaEm(LocalDateTime.now().minusDays(1));
        return d;
    }

    private void salvarRetornandoArgumento() {
        when(denunciaRepository.save(any(Denuncia.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- editar o relato reclassifica (CA-007) ----

    @Test
    void editarDescricaoReiniciaAClassificacaoMantendoONivelAnterior() {
        Denuncia d = classificada();
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));
        salvarRetornandoArgumento();

        service.atualizar(42L, new DenunciaAlteracaoRequest("Agora o cachorro está caído e sangrando", null, null));

        assertEquals(EnumStatusClassificacao.PENDENTE, d.getStatusClassificacao());
        assertNotEquals(SOLICITACAO_ANTIGA, d.getSolicitacaoClassificacaoId());
        assertNull(d.getClassificadaEm());
        // nível e justificativa anteriores continuam até o novo resultado chegar (Q-012)
        assertEquals(EnumNivelUrgencia.URGENTE, d.getUrgencia());
        assertEquals("Sem água há dias.", d.getJustificativaUrgencia());

        ArgumentCaptor<ClassificacaoSolicitadaEvent> evento = ArgumentCaptor.forClass(ClassificacaoSolicitadaEvent.class);
        verify(eventos).publishEvent(evento.capture());
        assertEquals(d.getSolicitacaoClassificacaoId(), evento.getValue().solicitacao().solicitacaoId());
        assertEquals(42L, evento.getValue().solicitacao().denunciaId());
        assertTrue(evento.getValue().solicitacao().descricao().contains("sangrando"));
    }

    @Test
    void editarSoOPontoDeReferenciaTambemReclassifica() {
        Denuncia d = classificada();
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));
        salvarRetornandoArgumento();

        service.atualizar(42L, new DenunciaAlteracaoRequest(null, "Atrás do mercado", null));

        assertEquals(EnumStatusClassificacao.PENDENTE, d.getStatusClassificacao());
        verify(eventos).publishEvent(any(ClassificacaoSolicitadaEvent.class));
    }

    @Test
    void salvarSemMudarORelatoNaoReclassifica() {
        Denuncia d = classificada();
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));
        salvarRetornandoArgumento();

        // mesmo texto (só espaços diferentes) e sem foto nova
        service.atualizar(42L, new DenunciaAlteracaoRequest("  Cachorro sem água no quintal ", "Perto da praça", null));

        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        assertEquals(SOLICITACAO_ANTIGA, d.getSolicitacaoClassificacaoId());
        verifyNoInteractions(eventos);
    }

    @Test
    void trocarSomenteAFotoNaoReclassifica() {
        Denuncia d = classificada();
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));
        salvarRetornandoArgumento();

        service.atualizar(42L, new DenunciaAlteracaoRequest(null, null, null));

        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        verifyNoInteractions(eventos);
    }

    // ---- exceções: nível da organização e fluxo urgente (RF-017, RF-018) ----

    @Test
    void nivelCorrigidoPelaOrganizacaoNaoEhReclassificadoAoEditar() {
        Denuncia d = classificada();
        d.setUrgencia(EnumNivelUrgencia.EMERGENCIA);
        d.setOrigemClassificacao(EnumOrigemClassificacao.ORGANIZACAO);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));
        salvarRetornandoArgumento();

        service.atualizar(42L, new DenunciaAlteracaoRequest("Texto totalmente novo", null, null));

        assertEquals(EnumNivelUrgencia.EMERGENCIA, d.getUrgencia());
        assertEquals(EnumOrigemClassificacao.ORGANIZACAO, d.getOrigemClassificacao());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        verifyNoInteractions(eventos);
    }

    @Test
    void denunciaUrgenteNuncaPassaPeloAgenteNemAoEditar() {
        Denuncia d = new Denuncia();
        ReflectionTestUtils.setField(d, "id", 7L);
        d.setCep(Denuncia.CEP_DENUNCIA_URGENTE);
        d.setDescricao("Preso numa grade");
        d.setUrgencia(EnumNivelUrgencia.EMERGENCIA);
        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        when(denunciaRepository.findById(7L)).thenReturn(Optional.of(d));
        salvarRetornandoArgumento();

        service.atualizar(7L, new DenunciaAlteracaoRequest("Preso numa grade de ferro", null, null));

        assertEquals(EnumNivelUrgencia.EMERGENCIA, d.getUrgencia());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        assertNull(d.getSolicitacaoClassificacaoId());
        verifyNoInteractions(eventos);
    }

    // ---- resultado antigo descartado (CA-014) ----

    @Test
    void resultadoDaSolicitacaoAnteriorEDescartadoDepoisDeEditar() {
        Denuncia d = classificada();
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));
        salvarRetornandoArgumento();
        service.atualizar(42L, new DenunciaAlteracaoRequest("Relato novo e diferente", null, null));
        String nova = d.getSolicitacaoClassificacaoId();

        // chega o resultado da primeira chamada, ainda em andamento quando o usuário editou
        service.aplicarClassificacao(new ResultadoClassificacao(1, SOLICITACAO_ANTIGA, 42L,
                EnumNivelUrgencia.NAO_URGENTE, "Resultado velho.", 0.9, EnumOrigemClassificacao.IA, "v", false, 10, 1));
        assertEquals(EnumNivelUrgencia.URGENTE, d.getUrgencia());
        assertEquals(EnumStatusClassificacao.PENDENTE, d.getStatusClassificacao());

        // o resultado da solicitação nova vale
        service.aplicarClassificacao(new ResultadoClassificacao(1, nova, 42L,
                EnumNivelUrgencia.EMERGENCIA, "Resultado novo.", 0.9, EnumOrigemClassificacao.IA, "v", false, 10, 1));
        assertEquals(EnumNivelUrgencia.EMERGENCIA, d.getUrgencia());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
    }

    // ---- a urgência nunca vem do usuário (RF-008, CA-007) ----

    @Test
    void formulariosNaoTemCampoDeUrgencia() {
        for (Class<?> tipo : List.of(DenunciaRequest.class, DenunciaAlteracaoRequest.class)) {
            boolean temUrgencia = Arrays.stream(tipo.getRecordComponents())
                    .map(RecordComponent::getName)
                    .anyMatch(n -> n.toLowerCase().contains("urgencia") || n.toLowerCase().contains("nivel"));
            assertFalse(temUrgencia, tipo.getSimpleName() + " não pode aceitar urgência do usuário");
        }
    }

    // ---- falha do agente (CA-003, Q-012, Q-017) ----

    @Test
    void falhaNoPrimeiroRegistroViraInsuficienteComRevisaoManual() {
        Denuncia d = new Denuncia();
        ReflectionTestUtils.setField(d, "id", 42L);
        d.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        d.setSolicitacaoClassificacaoId(SOLICITACAO_ANTIGA);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));

        service.registrarFalhaClassificacao(42L, SOLICITACAO_ANTIGA);

        assertEquals(EnumNivelUrgencia.INSUFICIENTE, d.getUrgencia());
        assertEquals(EnumOrigemClassificacao.FALLBACK, d.getOrigemClassificacao());
        assertTrue(d.isRevisaoManual());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        assertNotNull(d.getClassificadaEm());
        verify(denunciaRepository).save(d);
    }

    @Test
    void falhaNaReclassificacaoMantemONivelAnteriorEMarcaRevisao() {
        Denuncia d = classificada();
        d.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        d.setSolicitacaoClassificacaoId("nova");
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));

        service.registrarFalhaClassificacao(42L, "nova");

        assertEquals(EnumNivelUrgencia.URGENTE, d.getUrgencia());
        assertEquals(EnumOrigemClassificacao.IA, d.getOrigemClassificacao());
        assertTrue(d.isRevisaoManual());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, d.getStatusClassificacao());
        assertTrue(d.getJustificativaUrgencia().contains("nível anterior"));
    }

    @Test
    void falhaDeSolicitacaoSubstituidaOuJaConcluidaEhIgnorada() {
        Denuncia d = classificada();
        d.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        d.setSolicitacaoClassificacaoId("nova");
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(d));

        service.registrarFalhaClassificacao(42L, SOLICITACAO_ANTIGA);
        assertEquals(EnumStatusClassificacao.PENDENTE, d.getStatusClassificacao());

        d.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        service.registrarFalhaClassificacao(42L, "nova");
        assertEquals(EnumNivelUrgencia.URGENTE, d.getUrgencia());
        verify(denunciaRepository, never()).save(any());
    }

    @Test
    void falhaDeDenunciaInexistenteNaoLancaExcecao() {
        when(denunciaRepository.findById(99L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.registrarFalhaClassificacao(99L, "x"));
    }

    // ---- prazo máximo (RF-014, T036) ----

    @Test
    void pendentesVencidasViramInsuficienteEAsDemaisNaoSaoTocadas() {
        Denuncia vencida = new Denuncia();
        ReflectionTestUtils.setField(vencida, "id", 1L);
        vencida.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        vencida.setClassificacaoSolicitadaEm(LocalDateTime.now().minusMinutes(10));
        Denuncia reclassificacaoVencida = classificada();
        reclassificacaoVencida.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);

        when(denunciaRepository.findByStatusClassificacaoAndClassificacaoSolicitadaEmBefore(
                eq(EnumStatusClassificacao.PENDENTE), any(LocalDateTime.class)))
                .thenReturn(List.of(vencida, reclassificacaoVencida));

        int encerradas = service.expirarClassificacoesPendentes(180);

        assertEquals(2, encerradas);
        assertEquals(EnumNivelUrgencia.INSUFICIENTE, vencida.getUrgencia());
        assertTrue(vencida.isRevisaoManual());
        assertEquals(EnumNivelUrgencia.URGENTE, reclassificacaoVencida.getUrgencia());   // mantém o anterior
        assertTrue(reclassificacaoVencida.isRevisaoManual());
        verify(denunciaRepository).saveAll(List.of(vencida, reclassificacaoVencida));
    }

    @Test
    void limiteDeExpiracaoUsaOPrazoInformado() {
        when(denunciaRepository.findByStatusClassificacaoAndClassificacaoSolicitadaEmBefore(any(), any()))
                .thenReturn(List.of());
        LocalDateTime antes = LocalDateTime.now();

        assertEquals(0, service.expirarClassificacoesPendentes(180));

        ArgumentCaptor<LocalDateTime> limite = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(denunciaRepository).findByStatusClassificacaoAndClassificacaoSolicitadaEmBefore(
                eq(EnumStatusClassificacao.PENDENTE), limite.capture());
        assertFalse(limite.getValue().isAfter(antes.minusSeconds(179)));
        assertFalse(limite.getValue().isBefore(antes.minusSeconds(181)));
        verify(denunciaRepository, never()).saveAll(any());
    }
}
