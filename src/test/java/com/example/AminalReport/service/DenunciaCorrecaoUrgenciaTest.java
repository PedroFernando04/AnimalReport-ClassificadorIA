package com.example.AminalReport.service;

import com.example.AminalReport.dto.request.CorrecaoUrgenciaRequest;
import com.example.AminalReport.entities.enums.EnumAndamentoDenuncia;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.formularios.Denuncia;
import com.example.AminalReport.entities.logs.Log;
import com.example.AminalReport.entities.usuarios.Organizacao;
import com.example.AminalReport.exception.RegraDeNegocioException;
import com.example.AminalReport.mapper.DenunciaMapper;
import com.example.AminalReport.repository.formularios.DenunciaRepository;
import com.example.AminalReport.repository.logs.LogRepository;
import com.example.AminalReport.repository.usuarios.OrganizacaoRepository;
import com.example.AminalReport.repository.usuarios.UserRepository;
import com.example.AminalReport.service.urgencia.ResultadoClassificacao;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** US6: assumir, corrigir urgência e auditoria (T053, T055, T056). Cobre CA-012, CA-013, RF-016, RF-017. */
@ExtendWith(MockitoExtension.class)
class DenunciaCorrecaoUrgenciaTest {

    @Mock private DenunciaRepository denunciaRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrganizacaoRepository organizacaoRepository;
    @Mock private UploadService uploadService;
    @Mock private ApplicationEventPublisher eventos;
    @Mock private LogRepository logRepository;

    private DenunciaService service;
    private Organizacao responsavel;
    private Organizacao outra;
    private Denuncia denuncia;

    @BeforeEach
    void preparar() {
        service = new DenunciaService(denunciaRepository, userRepository, organizacaoRepository,
                uploadService, new DenunciaMapper(), eventos, logRepository);
        responsavel = organizacao(10L);
        outra = organizacao(11L);

        denuncia = new Denuncia();
        ReflectionTestUtils.setField(denuncia, "id", 42L);
        denuncia.setUrgencia(EnumNivelUrgencia.URGENTE);
        denuncia.setOrigemClassificacao(EnumOrigemClassificacao.IA);
        denuncia.setConfiancaUrgencia(0.55);
        denuncia.setVersaoClassificador("modelo/prompt-v1");
        denuncia.setRevisaoManual(true);
        denuncia.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        denuncia.setAndamentoDenuncia(EnumAndamentoDenuncia.AGUARDANDO);
    }

    private static Organizacao organizacao(Long id) {
        Organizacao o = new Organizacao();
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }

    private void organizacaoLogada(String email, Organizacao o) {
        when(organizacaoRepository.findByEmail(email)).thenReturn(Optional.of(o));
    }

    // ---- assumir ----

    @Test
    void assumirTornaAOrganizacaoResponsavelEColocaEmAnalise() {
        organizacaoLogada("ong@x.com", responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        service.assumir(42L, "ong@x.com");

        assertSame(responsavel, denuncia.getOrganizacaoResponsavel());
        assertEquals(EnumAndamentoDenuncia.EM_ANALISE, denuncia.getAndamentoDenuncia());
        verify(denunciaRepository).save(denuncia);
    }

    @Test
    void denunciaJaAssumidaNaoPodeSerAssumidaDeNovo() {
        organizacaoLogada("outra@x.com", outra);
        denuncia.setOrganizacaoResponsavel(responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        assertThrows(RegraDeNegocioException.class, () -> service.assumir(42L, "outra@x.com"));
        assertSame(responsavel, denuncia.getOrganizacaoResponsavel());
    }

    // ---- correção pela responsável (CA-012) ----

    @Test
    void responsavelCorrigeONivelEFicaRegistradoComoOrganizacaoSemRevisao() {
        organizacaoLogada("ong@x.com", responsavel);
        denuncia.setOrganizacaoResponsavel(responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        service.corrigirUrgencia(42L, "ong@x.com",
                new CorrecaoUrgenciaRequest(EnumNivelUrgencia.EMERGENCIA, "Confirmado em visita"));

        assertEquals(EnumNivelUrgencia.EMERGENCIA, denuncia.getUrgencia());
        assertEquals(EnumOrigemClassificacao.ORGANIZACAO, denuncia.getOrigemClassificacao());
        assertFalse(denuncia.isRevisaoManual());
        assertEquals(EnumStatusClassificacao.CONCLUIDA, denuncia.getStatusClassificacao());
        assertTrue(denuncia.getJustificativaUrgencia().contains("Confirmado em visita"));
        assertNull(denuncia.getConfiancaUrgencia());
        assertNull(denuncia.getVersaoClassificador());
        verify(denunciaRepository).save(denuncia);
    }

    @Test
    void correcaoGravaLogComUsuarioValorAnteriorENovo() {
        organizacaoLogada("ong@x.com", responsavel);
        denuncia.setOrganizacaoResponsavel(responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        service.corrigirUrgencia(42L, "ong@x.com",
                new CorrecaoUrgenciaRequest(EnumNivelUrgencia.EMERGENCIA, "Confirmado em visita"));

        ArgumentCaptor<Log> captor = ArgumentCaptor.forClass(Log.class);
        verify(logRepository).save(captor.capture());
        Log log = captor.getValue();
        assertEquals(Log.TipoAcao.UPDATE, log.getAcao());
        assertSame(responsavel, log.getUsuario());
        assertEquals("Denuncia.urgencia", log.getEntidadeAlterada());
        assertEquals(42L, log.getItemId());
        assertTrue(log.getDetalhes().contains("URGENTE -> EMERGENCIA"));
        assertTrue(log.getDetalhes().contains("Confirmado em visita"));
    }

    @Test
    void motivoEhOpcional() {
        organizacaoLogada("ong@x.com", responsavel);
        denuncia.setOrganizacaoResponsavel(responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        service.corrigirUrgencia(42L, "ong@x.com", new CorrecaoUrgenciaRequest(EnumNivelUrgencia.POUCO_URGENTE, "  "));

        assertEquals("Nível definido pela organização responsável.", denuncia.getJustificativaUrgencia());
        ArgumentCaptor<Log> captor = ArgumentCaptor.forClass(Log.class);
        verify(logRepository).save(captor.capture());
        assertFalse(captor.getValue().getDetalhes().contains("motivo"));
    }

    @Test
    void correcaoDeDenunciaAindaPendenteConcluiEDescartaOResultadoTardio() {
        organizacaoLogada("ong@x.com", responsavel);
        denuncia.setOrganizacaoResponsavel(responsavel);
        denuncia.setUrgencia(null);
        denuncia.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        denuncia.setSolicitacaoClassificacaoId("s1");
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        service.corrigirUrgencia(42L, "ong@x.com", new CorrecaoUrgenciaRequest(EnumNivelUrgencia.URGENTE, null));
        assertEquals(EnumStatusClassificacao.CONCLUIDA, denuncia.getStatusClassificacao());

        // o agente responde tarde: não pode sobrescrever a decisão humana (RF-017)
        service.aplicarClassificacao(new ResultadoClassificacao(1, "s1", 42L, EnumNivelUrgencia.NAO_URGENTE,
                "Tardio.", 0.9, EnumOrigemClassificacao.IA, "v", false, 5, 1));
        assertEquals(EnumNivelUrgencia.URGENTE, denuncia.getUrgencia());
        assertEquals(EnumOrigemClassificacao.ORGANIZACAO, denuncia.getOrigemClassificacao());
    }

    // ---- autorização no backend (CA-013, RF-016) ----

    @Test
    void organizacaoQueNaoEhAResponsavelRecebeAcessoNegado() {
        organizacaoLogada("outra@x.com", outra);
        denuncia.setOrganizacaoResponsavel(responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        assertThrows(AccessDeniedException.class, () -> service.corrigirUrgencia(42L, "outra@x.com",
                new CorrecaoUrgenciaRequest(EnumNivelUrgencia.EMERGENCIA, null)));

        assertEquals(EnumNivelUrgencia.URGENTE, denuncia.getUrgencia());
        verify(denunciaRepository, never()).save(any());
        verifyNoInteractions(logRepository);
    }

    @Test
    void denunciaSemResponsavelNaoPodeTerUrgenciaCorrigida() {
        organizacaoLogada("ong@x.com", responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        assertThrows(AccessDeniedException.class, () -> service.corrigirUrgencia(42L, "ong@x.com",
                new CorrecaoUrgenciaRequest(EnumNivelUrgencia.EMERGENCIA, null)));
        verifyNoInteractions(logRepository);
    }

    @Test
    void usuarioQueNaoEhOrganizacaoRecebeAcessoNegado() {
        when(organizacaoRepository.findByEmail("comum@x.com")).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> service.corrigirUrgencia(42L, "comum@x.com",
                new CorrecaoUrgenciaRequest(EnumNivelUrgencia.EMERGENCIA, null)));
        verifyNoInteractions(denunciaRepository, logRepository);
    }

    @Test
    void insuficienteNaoEhNivelValidoParaCorrecao() {
        organizacaoLogada("ong@x.com", responsavel);
        denuncia.setOrganizacaoResponsavel(responsavel);
        when(denunciaRepository.findById(42L)).thenReturn(Optional.of(denuncia));

        assertThrows(RegraDeNegocioException.class, () -> service.corrigirUrgencia(42L, "ong@x.com",
                new CorrecaoUrgenciaRequest(EnumNivelUrgencia.INSUFICIENTE, null)));
        assertEquals(EnumNivelUrgencia.URGENTE, denuncia.getUrgencia());
        verifyNoInteractions(logRepository);
    }

    // ---- validação do formulário ----

    @Test
    void formularioExigeNivelEMotivoCurto() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertFalse(validator.validate(new CorrecaoUrgenciaRequest(null, null)).isEmpty());
        assertFalse(validator.validate(new CorrecaoUrgenciaRequest(EnumNivelUrgencia.URGENTE, "x".repeat(201))).isEmpty());
        assertTrue(validator.validate(new CorrecaoUrgenciaRequest(EnumNivelUrgencia.URGENTE, "x".repeat(200))).isEmpty());
        assertNull(new CorrecaoUrgenciaRequest(EnumNivelUrgencia.URGENTE, "   ").motivo());
    }
}
