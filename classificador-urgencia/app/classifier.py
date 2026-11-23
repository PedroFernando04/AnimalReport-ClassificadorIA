"""Classificador: cadeia de decisão do agente. É o único ponto que decide e SEMPRE devolve um resultado.

    modo regras  : regras -> (INSUFICIENTE se nada for reconhecido)
    modo llm     : modelo -> (INSUFICIENTE/FALLBACK se falhar)
    modo hibrido : modelo "pensante" -> árbitro por regras (piso de segurança / suspeita de injeção)
                   modelo inconclusivo (INSUFICIENTE ou confiança baixa) -> regras (origem REGRAS, revisão manual)
                   modelo inacessível/falhou (sem internet, timeout, erro) -> regras -> INSUFICIENTE

Proteções (RF-012): limite de vazão por minuto e uma chamada ao modelo por vez; quando o limite
é atingido o modelo nem é chamado. Logs sem relato, ponto de referência ou contato (RF-011, RQ-004).
"""
from __future__ import annotations

import logging
import threading
import time

from app.config import Config
from app.limiter import LimitadorVazao
from app.llm_client import ClienteLlm, ErroModelo, RespostaModelo
from app.prompt import montar_mensagens
from app.regras import VERSAO_REGRAS, classificar_por_regras
from app.schemas import ResultadoClassificacao, SolicitacaoClassificacao
from app.seguranca import VERSAO_ARBITRO, arbitrar, detectar_injecao, indice

log = logging.getLogger("agente.classificador")

JUSTIFICATIVA_FALLBACK = "Não foi possível classificar automaticamente; é necessária revisão humana."


class Classificador:
    def __init__(self, cfg: Config | None = None, cliente: ClienteLlm | None = None,
                 limitador: LimitadorVazao | None = None) -> None:
        self._cfg = cfg or Config()
        self._cliente = cliente
        if self._cfg.usa_modelo and self._cliente is None:
            self._cliente = ClienteLlm(self._cfg)
        self._limitador = limitador or LimitadorVazao(self._cfg.vazao_por_minuto)
        self._vez = threading.Semaphore(self._cfg.max_concorrencia)

    # ------------------------------------------------------------------ API pública

    def classificar(self, s: SolicitacaoClassificacao) -> ResultadoClassificacao:
        inicio = time.perf_counter()
        if self._cfg.modo == "regras":
            resultado = classificar_por_regras(s, self._cfg.limiar_confianca)
            detalhe = "modo=regras"
        else:
            resultado, detalhe = self._classificar_com_modelo(s, inicio)
        resultado = resultado.model_copy(update={"latencia_ms": int((time.perf_counter() - inicio) * 1000)})
        # Sem descricao, ponto de referencia, contato nem e-mail no log (RF-011, RQ-004).
        log.info("classificacao solicitacaoId=%s denunciaId=%s origem=%s nivel=%s confianca=%.2f "
                 "latenciaMs=%d tentativas=%d versao=%s %s",
                 resultado.solicitacao_id, resultado.denuncia_id, resultado.origem, resultado.nivel,
                 resultado.confianca, resultado.latencia_ms, resultado.tentativas,
                 resultado.versao_classificador, detalhe)
        return resultado

    # ------------------------------------------------------------------ modelo

    def _classificar_com_modelo(self, s: SolicitacaoClassificacao, inicio: float) -> tuple[ResultadoClassificacao, str]:
        resposta, tentativas, motivo, tokens = self._chamar_modelo(s)
        if resposta is None:
            return self._plano_b(s, tentativas), f"modelo=falhou motivo={motivo}"

        nivel, justificativa, confianca = resposta.nivel, resposta.justificativa, resposta.confianca
        versao, origem, arbitragem, motivo_plano_b = self._cfg.versao_modelo, "IA", None, None
        injecao = detectar_injecao(s.descricao, s.ponto_ref)
        inconclusivo = nivel == "INSUFICIENTE" or confianca < self._cfg.limiar_confianca

        if self._cfg.modo == "hibrido":
            regras = classificar_por_regras(s, self._cfg.limiar_confianca)
            if self._cfg.piso_regras:
                arbitragem = arbitrar(nivel, justificativa, confianca, regras, injecao)
            if arbitragem is not None and arbitragem.motivo:
                nivel, justificativa, confianca = arbitragem.nivel, arbitragem.justificativa, arbitragem.confianca
                versao = f"{versao}+{VERSAO_ARBITRO}"[:120]
            elif inconclusivo and regras.nivel != "INSUFICIENTE":
                # Plano B por inconclusão: o modelo não decidiu (abstenção ou confiança baixa) e as
                # palavras-chave reconhecem sinais. Vale o nível mais grave entre os dois: subestimar
                # é o pior erro; superestimar custa só uma revisão a mais.
                if indice(nivel) > indice(regras.nivel):
                    motivo_plano_b = "modelo_mais_grave"
                else:
                    nivel, justificativa, confianca = regras.nivel, regras.justificativa, regras.confianca
                    origem, versao, motivo_plano_b = "REGRAS", VERSAO_REGRAS, "regras"

        arbitro_atuou = arbitragem is not None and arbitragem.motivo is not None
        # Revisão manual: abstenção, baixa confiança, árbitro que sobrepôs o modelo, plano B por
        # inconclusão ou relato com instruções estranhas (esses sempre passam por olhos humanos).
        revisao = (nivel == "INSUFICIENTE" or confianca < self._cfg.limiar_confianca
                   or arbitro_atuou or injecao or motivo_plano_b is not None)
        resultado = ResultadoClassificacao(
            solicitacao_id=s.solicitacao_id, denuncia_id=s.denuncia_id, nivel=nivel,
            justificativa=justificativa[:300], confianca=confianca, origem=origem,
            versao_classificador=versao, revisao_manual=revisao, latencia_ms=0, tentativas=tentativas)
        extra = f"tokens={tokens} injecaoSuspeita={str(injecao).lower()}"
        if arbitro_atuou:
            extra += f" arbitro={arbitragem.motivo}"
        if motivo_plano_b:
            extra += f" planoB={motivo_plano_b}"
        return resultado, extra

    def _chamar_modelo(self, s: SolicitacaoClassificacao) -> tuple[RespostaModelo | None, int, str, str]:
        """Até ``llm_tentativas`` chamadas. Devolve (resposta, tentativas feitas, motivo da falha, tokens)."""
        mensagens = montar_mensagens(s)
        tentativas, motivo = 0, "sem tentativa"
        for n in range(self._cfg.llm_tentativas):
            if not self._limitador.tentar():
                return None, tentativas, "vazao excedida", "-"
            tentativas += 1
            try:
                with self._vez:   # uma chamada ao modelo por vez (RQ-005)
                    resposta = self._cliente.classificar(mensagens)
                return resposta, tentativas, "", f"{resposta.tokens_entrada}/{resposta.tokens_saida}"
            except ErroModelo as e:
                motivo = e.motivo
                log.warning("tentativa %d/%d falhou solicitacaoId=%s motivo=%s",
                            tentativas, self._cfg.llm_tentativas, s.solicitacao_id, e.motivo)
                if not e.repetivel:
                    break
                if n + 1 < self._cfg.llm_tentativas and self._cfg.llm_espera_entre_tentativas_s > 0:
                    time.sleep(self._cfg.llm_espera_entre_tentativas_s)
        return None, tentativas, motivo, "-"

    # ------------------------------------------------------------------ plano B

    def _plano_b(self, s: SolicitacaoClassificacao, tentativas: int) -> ResultadoClassificacao:
        """Modelo indisponível: regras no modo híbrido; INSUFICIENTE no modo llm. Sempre revisão manual."""
        if self._cfg.modo == "hibrido":
            r = classificar_por_regras(s, self._cfg.limiar_confianca)
            return r.model_copy(update={"revisao_manual": True, "tentativas": tentativas,
                                        "versao_classificador": VERSAO_REGRAS})
        return ResultadoClassificacao(
            solicitacao_id=s.solicitacao_id, denuncia_id=s.denuncia_id, nivel="INSUFICIENTE",
            justificativa=JUSTIFICATIVA_FALLBACK, confianca=0.0, origem="FALLBACK",
            versao_classificador=self._cfg.versao_modelo, revisao_manual=True, latencia_ms=0,
            tentativas=tentativas)
