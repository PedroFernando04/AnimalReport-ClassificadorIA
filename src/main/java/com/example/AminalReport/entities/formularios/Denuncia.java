package com.example.AminalReport.entities.formularios;

import com.example.AminalReport.entities.enums.EnumAndamentoDenuncia;
import com.example.AminalReport.entities.enums.EnumNivelUrgencia;
import com.example.AminalReport.entities.enums.EnumOrigemClassificacao;
import com.example.AminalReport.entities.enums.EnumStatusClassificacao;
import com.example.AminalReport.entities.enums.EnumTipoAnimal;
import com.example.AminalReport.entities.usuarios.Organizacao;
import com.example.AminalReport.entities.usuarios.Usuario;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@DiscriminatorValue("DENUNCIA")
public class Denuncia extends Formulario {

    /** Valores fixos usados nas denúncias criadas pelo formulário "urgente" (sem endereço). */
    public static final String CEP_DENUNCIA_URGENTE = "98998-989";
    public static final String LOCAL_DENUNCIA_URGENTE = "URGÊNCIA";

    /**
     * Nível de urgência. Fica vazio enquanto a classificação automática está pendente
     * e aceita {@link EnumNivelUrgencia#INSUFICIENTE} quando o relato não permite decidir.
     */
    @Column
    @Enumerated(EnumType.STRING)
    private EnumNivelUrgencia urgencia;

    // ---- Classificação automática de urgência (specs/001-classificacao-urgencia) ----

    @Column
    @Enumerated(EnumType.STRING)
    private EnumStatusClassificacao statusClassificacao;

    /** Identifica a solicitação em andamento; resultados de outra solicitação são descartados. */
    @Column(length = 64)
    private String solicitacaoClassificacaoId;

    /** Vazio nas denúncias do fluxo urgente (exibidas como informadas pelo usuário). */
    @Column
    @Enumerated(EnumType.STRING)
    private EnumOrigemClassificacao origemClassificacao;

    @Column(length = 300)
    private String justificativaUrgencia;

    @Column
    private Double confiancaUrgencia;

    @Column(length = 120)
    private String versaoClassificador;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean revisaoManual = false;

    @Column
    private LocalDateTime classificacaoSolicitadaEm;

    @Column
    private LocalDateTime classificadaEm;

    @JoinColumn(nullable = true)
    @ManyToOne
    private Organizacao organizacaoResponsavel;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EnumAndamentoDenuncia andamentoDenuncia;

    @Column
    private String cep;

    @Column
    private String rua;

    @Column (nullable = false)
    private String bairro;

    @Column (nullable = false)
    private String municipio;

    @Column (nullable = false)
    private String estado;

    @Column (columnDefinition = "TEXT")
    private String pontoRef;
    //Construtores
    public Denuncia () {}


    public Denuncia(String foto, EnumTipoAnimal tipoAnimal, String descricao, Usuario usuarioCriador, String contato, EnumNivelUrgencia urgencia, Organizacao organizacaoResponsavel, EnumAndamentoDenuncia andamentoDenuncia, String cep, String rua, String bairro, String municipio, String estado, String pontoRef) {
        super(foto, tipoAnimal, descricao, usuarioCriador, contato);
        this.urgencia = urgencia;
        this.organizacaoResponsavel = organizacaoResponsavel;
        this.andamentoDenuncia = andamentoDenuncia;
        this.cep = cep;
        this.rua = rua;
        this.bairro = bairro;
        this.municipio = municipio;
        this.estado = estado;
        this.pontoRef = pontoRef;
    }

//Getters e Setters


    public String getCep() {
        return cep;
    }

    public void setCep(String cep) {
        this.cep = cep;
    }

    public String getRua() {
        return rua;
    }

    public void setRua(String rua) {
        this.rua = rua;
    }

    public String getBairro() {
        return bairro;
    }

    public void setBairro(String bairro) {
        this.bairro = bairro;
    }

    public String getMunicipio() {
        return municipio;
    }

    public void setMunicipio(String municipio) {
        this.municipio = municipio;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    public String getPontoRef() {
        return pontoRef;
    }

    public void setPontoRef(String pontoRef) {
        this.pontoRef = pontoRef;
    }

    public EnumNivelUrgencia getUrgencia() {
        return urgencia;
    }

    public void setUrgencia(EnumNivelUrgencia urgencia) {
        this.urgencia = urgencia;
    }

    public Organizacao getOrganizacaoResponsavel() {
        return organizacaoResponsavel;
    }

    public void setOrganizacaoResponsavel(Organizacao organizacaoResponsavel) {
        this.organizacaoResponsavel = organizacaoResponsavel;
    }

    public EnumAndamentoDenuncia getAndamentoDenuncia() {
        return andamentoDenuncia;
    }

    public void setAndamentoDenuncia(EnumAndamentoDenuncia andamentoDenuncia) {
        this.andamentoDenuncia = andamentoDenuncia;
    }

    public EnumStatusClassificacao getStatusClassificacao() { return statusClassificacao; }
    public void setStatusClassificacao(EnumStatusClassificacao statusClassificacao) { this.statusClassificacao = statusClassificacao; }

    public String getSolicitacaoClassificacaoId() { return solicitacaoClassificacaoId; }
    public void setSolicitacaoClassificacaoId(String solicitacaoClassificacaoId) { this.solicitacaoClassificacaoId = solicitacaoClassificacaoId; }

    public EnumOrigemClassificacao getOrigemClassificacao() { return origemClassificacao; }
    public void setOrigemClassificacao(EnumOrigemClassificacao origemClassificacao) { this.origemClassificacao = origemClassificacao; }

    public String getJustificativaUrgencia() { return justificativaUrgencia; }
    public void setJustificativaUrgencia(String justificativaUrgencia) { this.justificativaUrgencia = justificativaUrgencia; }

    public Double getConfiancaUrgencia() { return confiancaUrgencia; }
    public void setConfiancaUrgencia(Double confiancaUrgencia) { this.confiancaUrgencia = confiancaUrgencia; }

    public String getVersaoClassificador() { return versaoClassificador; }
    public void setVersaoClassificador(String versaoClassificador) { this.versaoClassificador = versaoClassificador; }

    public boolean isRevisaoManual() { return revisaoManual; }
    public void setRevisaoManual(boolean revisaoManual) { this.revisaoManual = revisaoManual; }

    public LocalDateTime getClassificacaoSolicitadaEm() { return classificacaoSolicitadaEm; }
    public void setClassificacaoSolicitadaEm(LocalDateTime classificacaoSolicitadaEm) { this.classificacaoSolicitadaEm = classificacaoSolicitadaEm; }

    public LocalDateTime getClassificadaEm() { return classificadaEm; }
    public void setClassificadaEm(LocalDateTime classificadaEm) { this.classificadaEm = classificadaEm; }
}
