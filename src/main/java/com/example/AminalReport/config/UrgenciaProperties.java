package com.example.AminalReport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuração da classificação de urgência (prefixo {@code urgencia.*}). Sem segredos. */
@Component
@ConfigurationProperties(prefix = "urgencia")
public class UrgenciaProperties {

    /** Tempo máximo de espera pelo resultado antes de marcar INSUFICIENTE (RF-014). */
    private int prazoMaximoSegundos = 180;
    /** Abaixo disso o agente marca revisão manual; mantido aqui para exibição e testes. */
    private double limiarConfianca = 0.6;

    /** URL base do agente e token opcional (Bearer). */
    private String agenteUrl = "http://localhost:8000";
    private String agenteToken = "";

    public String getAgenteUrl() { return agenteUrl; }
    public void setAgenteUrl(String agenteUrl) { this.agenteUrl = agenteUrl; }
    public String getAgenteToken() { return agenteToken; }
    public void setAgenteToken(String agenteToken) { this.agenteToken = agenteToken; }
    public int getPrazoMaximoSegundos() { return prazoMaximoSegundos; }
    public void setPrazoMaximoSegundos(int prazoMaximoSegundos) { this.prazoMaximoSegundos = prazoMaximoSegundos; }
    public double getLimiarConfianca() { return limiarConfianca; }
    public void setLimiarConfianca(double limiarConfianca) { this.limiarConfianca = limiarConfianca; }
}
