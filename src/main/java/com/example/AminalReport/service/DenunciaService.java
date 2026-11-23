package com.example.AminalReport.service;

import com.example.AminalReport.dto.request.CorrecaoUrgenciaRequest;
import com.example.AminalReport.dto.request.DenunciaAlteracaoRequest;
import com.example.AminalReport.dto.request.DenunciaRequest;
import com.example.AminalReport.dto.request.DenunciaUrgenteRequest;
import com.example.AminalReport.dto.response.DenunciaResponse;
import com.example.AminalReport.entities.enums.EnumAndamentoDenuncia;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.formularios.Denuncia;
import com.example.AminalReport.entities.logs.Log;
import com.example.AminalReport.entities.usuarios.Comum;
import com.example.AminalReport.entities.usuarios.Organizacao;
import com.example.AminalReport.entities.usuarios.Usuario;
import com.example.AminalReport.exception.RecursoNaoEncontradoException;
import com.example.AminalReport.exception.RegraDeNegocioException;
import com.example.AminalReport.mapper.DenunciaMapper;
import com.example.AminalReport.repository.formularios.DenunciaRepository;
import com.example.AminalReport.repository.logs.LogRepository;
import com.example.AminalReport.repository.usuarios.OrganizacaoRepository;
import com.example.AminalReport.repository.usuarios.UserRepository;
import com.example.AminalReport.service.urgencia.ClassificacaoSolicitadaEvent;
import com.example.AminalReport.service.urgencia.ResultadoClassificacao;
import com.example.AminalReport.service.urgencia.SolicitacaoClassificacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class DenunciaService {

    private static final Logger log = LoggerFactory.getLogger(DenunciaService.class);
    private static final String PASTA_UPLOAD = "denuncia";
    static final String JUSTIFICATIVA_SEM_RESULTADO =
            "Não foi possível classificar automaticamente; é necessária revisão humana.";
    static final String JUSTIFICATIVA_RECLASSIFICACAO_FALHOU =
            "Reclassificação não concluída; o nível anterior foi mantido e requer revisão manual.";

    private final DenunciaRepository denunciaRepository;
    private final UserRepository userRepository;
    private final OrganizacaoRepository organizacaoRepository;
    private final UploadService uploadService;
    private final DenunciaMapper denunciaMapper;
    private final ApplicationEventPublisher eventos;
    private final LogRepository logRepository;

    public DenunciaService(DenunciaRepository denunciaRepository,
                           UserRepository userRepository,
                           OrganizacaoRepository organizacaoRepository,
                           UploadService uploadService,
                           DenunciaMapper denunciaMapper,
                           ApplicationEventPublisher eventos,
                           LogRepository logRepository) {
        this.denunciaRepository = denunciaRepository;
        this.userRepository = userRepository;
        this.organizacaoRepository = organizacaoRepository;
        this.uploadService = uploadService;
        this.denunciaMapper = denunciaMapper;
        this.eventos = eventos;
        this.logRepository = logRepository;
    }

    /**
     * @param emailCriador e-mail do usuário autenticado; {@code null} para visitante anônimo
     */
    @Transactional
    public void registrar(DenunciaRequest request, String emailCriador) {
        Denuncia denuncia = denunciaMapper.toEntity(request);
        denuncia.setUsuarioCriador(buscarUsuario(emailCriador));
        denuncia.setFoto(uploadService.salvarImagem(request.foto(), PASTA_UPLOAD));
        denuncia.setAndamentoDenuncia(EnumAndamentoDenuncia.AGUARDANDO);

        // A urgência não é informada pelo usuário: nasce pendente e é definida pelo agente.
        denuncia.setUrgencia(null);
        solicitarClassificacao(denuncia);

        Denuncia salva = denunciaRepository.save(denuncia);
        eventos.publishEvent(new ClassificacaoSolicitadaEvent(montarSolicitacao(salva)));
    }

    /** Denúncia urgente: sem endereço, sempre com urgência máxima. */
    @Transactional
    public void registrarUrgente(DenunciaUrgenteRequest request, String emailCriador) {
        Denuncia denuncia = denunciaMapper.toEntity(request);
        denuncia.setUsuarioCriador(buscarUsuario(emailCriador));
        denuncia.setFoto(uploadService.salvarImagem(request.foto(), PASTA_UPLOAD));

        denuncia.setBairro(Denuncia.LOCAL_DENUNCIA_URGENTE);
        denuncia.setEstado(Denuncia.LOCAL_DENUNCIA_URGENTE);
        denuncia.setMunicipio(Denuncia.LOCAL_DENUNCIA_URGENTE);
        denuncia.setCep(Denuncia.CEP_DENUNCIA_URGENTE);
        denuncia.setUrgencia(EnumNivelUrgencia.EMERGENCIA);
        // Fluxo urgente continua fixo e fora da classificação automática (RF-018); origem fica vazia.
        denuncia.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        denuncia.setAndamentoDenuncia(EnumAndamentoDenuncia.AGUARDANDO);

        denunciaRepository.save(denuncia);
    }

    /**
     * Aplica o resultado do agente. Só vale se a denúncia ainda está pendente e se o resultado
     * pertence à solicitação mais recente; duplicatas e resultados antigos são descartados (RF-013).
     */
    @Transactional
    public void aplicarClassificacao(ResultadoClassificacao resultado) {
        Optional<Denuncia> encontrada = denunciaRepository.findById(resultado.denunciaId());
        if (encontrada.isEmpty()) {
            log.warn("Resultado descartado: denuncia inexistente id={}", resultado.denunciaId());
            return;
        }
        Denuncia denuncia = encontrada.get();
        if (denuncia.getStatusClassificacao() != EnumStatusClassificacao.PENDENTE
                || !resultado.solicitacaoId().equals(denuncia.getSolicitacaoClassificacaoId())) {
            log.info("Resultado descartado (duplicado ou de solicitação antiga): denunciaId={} solicitacaoId={}",
                    resultado.denunciaId(), resultado.solicitacaoId());
            return;
        }

        denuncia.setUrgencia(resultado.nivel());
        denuncia.setJustificativaUrgencia(resultado.justificativa());
        denuncia.setConfiancaUrgencia(resultado.confianca());
        denuncia.setOrigemClassificacao(resultado.origem());
        denuncia.setVersaoClassificador(resultado.versaoClassificador());
        // INSUFICIENTE sempre exige revisão humana, independentemente do que o agente informar.
        denuncia.setRevisaoManual(resultado.revisaoManual() || resultado.nivel() == EnumNivelUrgencia.INSUFICIENTE);
        denuncia.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        denuncia.setClassificadaEm(LocalDateTime.now());
        denunciaRepository.save(denuncia);

        log.info("Classificação aplicada: denunciaId={} nivel={} origem={} latenciaMs={} tentativas={}",
                denuncia.getId(), resultado.nivel(), resultado.origem(), resultado.latenciaMs(), resultado.tentativas());
    }

    /**
     * O agente não respondeu (fora do ar, erro, resposta inválida). Só vale para a solicitação
     * mais recente ainda pendente; resultado tardio de uma solicitação já encerrada é ignorado.
     */
    @Transactional
    public void registrarFalhaClassificacao(Long denunciaId, String solicitacaoId) {
        Optional<Denuncia> encontrada = denunciaRepository.findById(denunciaId);
        if (encontrada.isEmpty()) {
            log.warn("Falha ignorada: denuncia inexistente id={}", denunciaId);
            return;
        }
        Denuncia denuncia = encontrada.get();
        if (denuncia.getStatusClassificacao() != EnumStatusClassificacao.PENDENTE
                || !Objects.equals(solicitacaoId, denuncia.getSolicitacaoClassificacaoId())) {
            log.info("Falha ignorada (solicitação já encerrada ou substituída): denunciaId={}", denunciaId);
            return;
        }
        encerrarSemResultado(denuncia);
        denunciaRepository.save(denuncia);
    }

    /**
     * Prazo máximo (RF-014): classificações pendentes há mais de {@code prazoSegundos} são encerradas.
     * Chamado pelo agendador, também cobre reinício da aplicação com solicitações em andamento.
     *
     * @return quantas denúncias foram encerradas
     */
    @Transactional
    public int expirarClassificacoesPendentes(int prazoSegundos) {
        LocalDateTime limite = LocalDateTime.now().minusSeconds(prazoSegundos);
        List<Denuncia> vencidas = denunciaRepository
                .findByStatusClassificacaoAndClassificacaoSolicitadaEmBefore(EnumStatusClassificacao.PENDENTE, limite);
        for (Denuncia denuncia : vencidas) {
            encerrarSemResultado(denuncia);
            log.warn("Classificação expirada: denunciaId={} solicitacaoId={}",
                    denuncia.getId(), denuncia.getSolicitacaoClassificacaoId());
        }
        if (!vencidas.isEmpty()) {
            denunciaRepository.saveAll(vencidas);
        }
        return vencidas.size();
    }

    /**
     * Sem resultado: se nunca houve nível, vira INSUFICIENTE (revisão humana); se já havia um nível
     * (reclassificação após edição), o nível anterior é mantido e vai para revisão (Q-012, Q-017).
     */
    private void encerrarSemResultado(Denuncia denuncia) {
        if (denuncia.getUrgencia() != null) {
            denuncia.setJustificativaUrgencia(JUSTIFICATIVA_RECLASSIFICACAO_FALHOU);
        } else {
            denuncia.setUrgencia(EnumNivelUrgencia.INSUFICIENTE);
            denuncia.setJustificativaUrgencia(JUSTIFICATIVA_SEM_RESULTADO);
            denuncia.setConfiancaUrgencia(0.0);
            denuncia.setOrigemClassificacao(EnumOrigemClassificacao.FALLBACK);
            denuncia.setVersaoClassificador("indisponivel");
        }
        denuncia.setRevisaoManual(true);
        denuncia.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        denuncia.setClassificadaEm(LocalDateTime.now());
    }

    /**
     * Nova classificação depois de editar o relato: nasce uma nova solicitação (a antiga passa a ser
     * descartada) e o nível/justificativa anteriores permanecem até o novo resultado chegar.
     */
    private void reiniciarClassificacao(Denuncia denuncia) {
        denuncia.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        denuncia.setSolicitacaoClassificacaoId(UUID.randomUUID().toString());
        denuncia.setClassificacaoSolicitadaEm(LocalDateTime.now());
        denuncia.setClassificadaEm(null);
    }

    /**
     * Só o relato (descrição e ponto de referência) reclassifica. Ficam de fora: nível corrigido
     * por organização (RF-017) e denúncias que nunca passaram pelo agente, como a urgente (RF-018).
     */
    private boolean deveReclassificar(Denuncia denuncia, boolean relatoMudou) {
        return relatoMudou
                && denuncia.getOrigemClassificacao() != EnumOrigemClassificacao.ORGANIZACAO
                && denuncia.getSolicitacaoClassificacaoId() != null
                && !Denuncia.CEP_DENUNCIA_URGENTE.equals(denuncia.getCep());
    }

    private static String normalizar(String texto) {
        return texto == null ? "" : texto.strip();
    }

    private void solicitarClassificacao(Denuncia denuncia) {
        denuncia.setStatusClassificacao(EnumStatusClassificacao.PENDENTE);
        denuncia.setSolicitacaoClassificacaoId(UUID.randomUUID().toString());
        denuncia.setClassificacaoSolicitadaEm(LocalDateTime.now());
        denuncia.setOrigemClassificacao(null);
        denuncia.setJustificativaUrgencia(null);
        denuncia.setConfiancaUrgencia(null);
        denuncia.setVersaoClassificador(null);
        denuncia.setRevisaoManual(false);
        denuncia.setClassificadaEm(null);
    }

    /** Monta a mensagem com o mínimo necessário: nunca contato, usuário, foto ou endereço (RF-003). */
    private SolicitacaoClassificacao montarSolicitacao(Denuncia denuncia) {
        return SolicitacaoClassificacao.nova(
                denuncia.getSolicitacaoClassificacaoId(),
                denuncia.getId(),
                denuncia.getTipoAnimal().name(),
                denuncia.getDescricao(),
                denuncia.getPontoRef());
    }

    /** Denúncias que ainda não foram assumidas por nenhuma organização (mais recentes primeiro). */
    @Transactional(readOnly = true)
    public List<DenunciaResponse> listarPendentes() {
        return denunciaMapper.toResponseList(denunciaRepository.findByOrganizacaoResponsavelIsNullOrderByIdDesc());
    }

    /**
     * Denúncias exibidas na tela de status: as criadas pelo usuário comum
     * ou as sob responsabilidade da organização.
     */
    @Transactional(readOnly = true)
    public List<DenunciaResponse> listarParaStatus(String email) {
        Usuario usuario = userRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Usuário não encontrado."));

        List<Denuncia> denuncias;
        if (usuario instanceof Comum) {
            denuncias = denunciaRepository.findByUsuarioCriador(usuario);
        } else if (usuario instanceof Organizacao) {
            denuncias = denunciaRepository.findByOrganizacaoResponsavel(usuario);
        } else {
            denuncias = List.of();
        }
        return denunciaMapper.toResponseList(denuncias);
    }

    @Transactional(readOnly = true)
    public Optional<DenunciaResponse> buscarPorId(Long id) {
        return denunciaRepository.findById(id).map(denunciaMapper::toResponse);
    }

    @Transactional
    public void atualizar(Long id, DenunciaAlteracaoRequest request) {
        Denuncia denuncia = denunciaRepository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Denúncia não encontrada: " + id));

        String descricaoAnterior = normalizar(denuncia.getDescricao());
        String pontoRefAnterior = normalizar(denuncia.getPontoRef());

        denunciaMapper.atualizarEntidade(denuncia, request);
        denuncia.setFoto(uploadService.substituirImagem(denuncia.getFoto(), request.foto(), PASTA_UPLOAD));

        boolean relatoMudou = !descricaoAnterior.equals(normalizar(denuncia.getDescricao()))
                || !pontoRefAnterior.equals(normalizar(denuncia.getPontoRef()));
        boolean reclassificar = deveReclassificar(denuncia, relatoMudou);
        if (reclassificar) {
            reiniciarClassificacao(denuncia);
        }

        Denuncia salva = denunciaRepository.save(denuncia);
        if (reclassificar) {
            eventos.publishEvent(new ClassificacaoSolicitadaEvent(montarSolicitacao(salva)));
        }
    }

    /** Uma organização passa a ser a responsável pela denúncia. */
    @Transactional
    public void assumir(Long idDenuncia, String emailOrganizacao) {
        Organizacao organizacao = organizacaoRepository.findByEmail(emailOrganizacao)
                .orElseThrow(() -> new AccessDeniedException("Apenas organizações podem assumir denúncias."));

        Denuncia denuncia = denunciaRepository.findById(idDenuncia)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Denúncia não encontrada: " + idDenuncia));

        if (denuncia.getOrganizacaoResponsavel() != null) {
            throw new RegraDeNegocioException("Esta denúncia já foi assumida por uma organização.");
        }

        denuncia.setOrganizacaoResponsavel(organizacao);
        denuncia.setAndamentoDenuncia(EnumAndamentoDenuncia.EM_ANALISE);
        denunciaRepository.save(denuncia);
    }

    /**
     * Correção do nível pela organização responsável (US6, RF-016/RF-017). A decisão humana prevalece e
     * nunca é sobrescrita por reclassificação automática; fica registrada no log com valor anterior e novo.
     */
    @Transactional
    public void corrigirUrgencia(Long idDenuncia, String emailOrganizacao, CorrecaoUrgenciaRequest request) {
        Organizacao organizacao = organizacaoRepository.findByEmail(emailOrganizacao)
                .orElseThrow(() -> new AccessDeniedException("Apenas organizações podem corrigir a urgência."));

        Denuncia denuncia = denunciaRepository.findById(idDenuncia)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Denúncia não encontrada: " + idDenuncia));

        Organizacao responsavel = denuncia.getOrganizacaoResponsavel();
        if (responsavel == null || !Objects.equals(responsavel.getId(), organizacao.getId())) {
            throw new AccessDeniedException("Somente a organização responsável pode corrigir a urgência.");
        }

        EnumNivelUrgencia novo = request.nivel();
        if (novo == null || novo == EnumNivelUrgencia.INSUFICIENTE) {
            throw new RegraDeNegocioException("Escolha um dos cinco níveis de urgência.");
        }

        EnumNivelUrgencia anterior = denuncia.getUrgencia();
        denuncia.setUrgencia(novo);
        denuncia.setOrigemClassificacao(EnumOrigemClassificacao.ORGANIZACAO);
        denuncia.setJustificativaUrgencia(request.motivo() == null
                ? "Nível definido pela organização responsável."
                : "Nível definido pela organização responsável. Motivo: " + request.motivo());
        denuncia.setConfiancaUrgencia(null);
        denuncia.setVersaoClassificador(null);
        denuncia.setRevisaoManual(false);
        // Se ainda havia classificação pendente, o resultado tardio será descartado (status deixa de ser PENDENTE).
        denuncia.setStatusClassificacao(EnumStatusClassificacao.CONCLUIDA);
        denuncia.setClassificadaEm(LocalDateTime.now());
        denunciaRepository.save(denuncia);

        Log registro = new Log(Log.TipoAcao.UPDATE, organizacao, LocalDateTime.now(),
                "Denuncia.urgencia", denuncia.getId());
        registro.setDetalhes("urgencia: " + anterior + " -> " + novo
                + (request.motivo() == null ? "" : "; motivo: " + request.motivo()));
        logRepository.save(registro);
    }

    private Usuario buscarUsuario(String email) {
        return email == null ? null : userRepository.findByEmail(email).orElse(null);
    }
}
