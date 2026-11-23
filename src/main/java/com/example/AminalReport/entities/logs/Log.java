package com.example.AminalReport.entities.logs;

import com.example.AminalReport.entities.usuarios.Usuario;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes.*;


import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "logs_auditoria")
public class Log {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TipoAcao acao;

    @ManyToOne
    @JoinColumn(name = "usuario_id", nullable = true, updatable = false)
    private Usuario usuario;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime data;

    @Column(nullable = false, updatable = false)
    private String entidadeAlterada;

    @Column(nullable = false, updatable = false)
    private Long itemId;

    /**
     * Descrição curta da alteração (ex.: "urgencia: URGENTE -> EMERGENCIA; motivo: ...").
     * Nunca deve conter dados pessoais além do que já está na denúncia.
     */
    @Column(length = 500, updatable = false)
    private String detalhes;



    public enum TipoAcao {
        INSERT,
        UPDATE,
        DELETE
    }

    //Construtores
    public Log() {}

    public Log(TipoAcao acao, Usuario usuario, LocalDateTime data, String entidadeAlterada, Long itemId) {
        this.acao = acao;
        this.usuario = usuario;
        this.data = data;
        this.entidadeAlterada = entidadeAlterada;
        this.itemId = itemId;
    }


    //Getters e Setters
    public Long getId() {
        return id;
    }

    public TipoAcao getAcao() {
        return acao;
    }

    public void setAcao(TipoAcao acao) {
        this.acao = acao;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public LocalDateTime getData() {
        return data;
    }

    public void setData(LocalDateTime data) {
        this.data = data;
    }

    public String getEntidadeAlterada() {
        return entidadeAlterada;
    }

    public void setEntidadeAlterada(String entidadeAlterada) {
        this.entidadeAlterada = entidadeAlterada;
    }

    public Long getItemId() {
        return itemId;
    }

    public String getDetalhes() {
        return detalhes;
    }

    public void setDetalhes(String detalhes) {
        this.detalhes = detalhes;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }
}
