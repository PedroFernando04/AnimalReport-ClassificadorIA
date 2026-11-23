"""Cliente do modelo de linguagem (API compatível com OpenAI) com saída validada de forma rígida.

* Temperatura 0 (DTEC-009); semente, ``max_tokens`` e ``reasoning_effort`` opcionais (DTEC-012).
* Timeout por chamada; as tentativas e o limite de vazão são decididos pelo classificador.
* A resposta só vira resultado se for um JSON com ``nivel`` na lista fechada, justificativa
  não vazia e confiança numérica. Qualquer outra coisa é erro (``ErroModelo``) e o classificador
  cai para as regras (CA-006). Campos extras do modelo são ignorados: ele só controla
  nível, justificativa e confiança (SPEC-01, limite de segurança).
* Os logs de erro trazem status HTTP e a mensagem curta do provedor, nunca o relato.
"""
from __future__ import annotations

import json
import logging
import re
import unicodedata
from dataclasses import dataclass

import httpx

from app.config import Config
from app.schemas import NIVEIS

log = logging.getLogger("agente.llm")

MAX_JUSTIFICATIVA = 300


class ErroModelo(Exception):
    """Falha ao obter uma classificação válida do modelo.

    ``repetivel`` indica se uma nova tentativa pode ajudar (timeout, 429, 5xx, saída inválida);
    erros de autenticação ou de modelo inexistente não melhoram ao repetir.
    """

    def __init__(self, motivo: str, repetivel: bool = True) -> None:
        super().__init__(motivo)
        self.motivo = motivo
        self.repetivel = repetivel


@dataclass(frozen=True)
class RespostaModelo:
    nivel: str
    justificativa: str
    confianca: float
    tokens_entrada: int | None = None
    tokens_saida: int | None = None


def _sem_acento(texto: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", texto) if unicodedata.category(c) != "Mn")


def _normalizar_nivel(bruto: object) -> str:
    if not isinstance(bruto, str):
        raise ErroModelo("nivel ausente ou não textual")
    nivel = re.sub(r"[\s-]+", "_", _sem_acento(bruto).strip().upper())
    if nivel not in NIVEIS:
        raise ErroModelo("nivel fora da lista permitida")
    return nivel


def _normalizar_confianca(bruto: object) -> float:
    if isinstance(bruto, bool) or not isinstance(bruto, (int, float)):
        raise ErroModelo("confianca ausente ou não numérica")
    valor = float(bruto)
    if 1.0 < valor <= 100.0:      # alguns modelos respondem em porcentagem (85 em vez de 0,85)
        valor /= 100.0
    if not 0.0 <= valor <= 1.0:
        raise ErroModelo("confianca fora do intervalo")
    return valor


def _normalizar_justificativa(bruto: object) -> str:
    if not isinstance(bruto, str) or not bruto.strip():
        raise ErroModelo("justificativa ausente")
    texto = re.sub(r"\s+", " ", bruto).strip()
    if len(texto) > MAX_JUSTIFICATIVA:
        texto = texto[: MAX_JUSTIFICATIVA - 3].rstrip() + "..."
    return texto


def _extrair_objeto_json(texto: str) -> dict:
    """Aceita o JSON puro ou cercado por bloco de código/texto, desde que haja UM objeto bem formado."""
    texto = texto.strip()
    tentativas = [texto]
    inicio, fim = texto.find("{"), texto.rfind("}")
    if inicio != -1 and fim > inicio:
        tentativas.append(texto[inicio:fim + 1])
    for candidato in tentativas:
        try:
            obj = json.loads(candidato)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            return obj
    raise ErroModelo("resposta não é um objeto JSON válido")


def interpretar_resposta(texto: str) -> RespostaModelo:
    """Valida o conteúdo textual devolvido pelo modelo (CA-006, RF-009)."""
    obj = _extrair_objeto_json(texto)
    return RespostaModelo(
        nivel=_normalizar_nivel(obj.get("nivel")),
        justificativa=_normalizar_justificativa(obj.get("justificativa")),
        confianca=_normalizar_confianca(obj.get("confianca")),
    )


def _mensagem_do_provedor(resposta: httpx.Response) -> str:
    """Extrai uma mensagem curta de erro (formatos OpenAI/Groq: objeto; Gemini: lista)."""
    try:
        corpo = resposta.json()
    except ValueError:
        return "corpo não é JSON"
    if isinstance(corpo, list) and corpo:
        corpo = corpo[0]
    erro = corpo.get("error") if isinstance(corpo, dict) else None
    mensagem = erro.get("message") if isinstance(erro, dict) else erro
    return re.sub(r"\s+", " ", str(mensagem or "sem mensagem"))[:200]


class ClienteLlm:
    def __init__(self, cfg: Config, transport: httpx.BaseTransport | None = None) -> None:
        self._cfg = cfg
        cabecalhos = {"Content-Type": "application/json"}
        if cfg.llm_api_key:
            cabecalhos["Authorization"] = f"Bearer {cfg.llm_api_key}"
        self._http = httpx.Client(base_url=cfg.llm_url, headers=cabecalhos, transport=transport,
                                  timeout=httpx.Timeout(cfg.llm_timeout_s))

    def fechar(self) -> None:
        self._http.close()

    def _corpo(self, mensagens: list[dict[str, str]]) -> dict:
        c = self._cfg
        corpo: dict = {"model": c.llm_modelo, "messages": mensagens, "temperature": 0,
                       "max_tokens": c.llm_max_tokens}
        if c.llm_json_mode:
            corpo["response_format"] = {"type": "json_object"}
        if c.llm_seed is not None:
            corpo["seed"] = c.llm_seed
        if c.llm_reasoning_effort:
            corpo["reasoning_effort"] = c.llm_reasoning_effort
        return corpo

    def classificar(self, mensagens: list[dict[str, str]]) -> RespostaModelo:
        try:
            r = self._http.post("/chat/completions", json=self._corpo(mensagens))
        except httpx.TimeoutException as e:
            raise ErroModelo(f"timeout ({type(e).__name__})") from e
        except httpx.HTTPError as e:
            raise ErroModelo(f"falha de conexão ({type(e).__name__})") from e

        if r.status_code != 200:
            msg = _mensagem_do_provedor(r)
            log.warning("provedor respondeu status=%d mensagem=%s", r.status_code, msg)
            # 401/403/404 (chave ou modelo errados) não se resolvem repetindo; 429 e 5xx podem.
            raise ErroModelo(f"http {r.status_code}", repetivel=r.status_code in (408, 429) or r.status_code >= 500)

        try:
            dados = r.json()
            conteudo = dados["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise ErroModelo("resposta do provedor fora do formato esperado") from e
        if not isinstance(conteudo, str) or not conteudo.strip():
            # típico de modelo de raciocínio sem tokens suficientes (max_tokens baixo): DTEC-012
            raise ErroModelo("resposta vazia (verifique URGENCIA_LLM_MAX_TOKENS)")

        base = interpretar_resposta(conteudo)
        uso = dados.get("usage") if isinstance(dados, dict) else None
        uso = uso if isinstance(uso, dict) else {}
        return RespostaModelo(base.nivel, base.justificativa, base.confianca,
                              uso.get("prompt_tokens"), uso.get("completion_tokens"))
