package com.example.AminalReport.repository.formularios;

import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.formularios.Denuncia;
import com.example.AminalReport.entities.usuarios.Organizacao;
import com.example.AminalReport.entities.usuarios.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DenunciaRepository extends JpaRepository<Denuncia, Long> {

    List<Denuncia> findByUsuarioCriador(Usuario user);

    List<Denuncia> findByOrganizacaoResponsavel(Usuario org);

    List<Denuncia> findByOrganizacaoResponsavelIsNullOrderByIdDesc();

    /** Classificações solicitadas antes de {@code limite} e ainda sem resultado (prazo máximo, RF-014). */
    List<Denuncia> findByStatusClassificacaoAndClassificacaoSolicitadaEmBefore(
            EnumStatusClassificacao status, LocalDateTime limite);

}