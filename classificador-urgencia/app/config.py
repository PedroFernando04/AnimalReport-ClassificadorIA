"""Configuração do agente, lida de variáveis de ambiente (nenhum segredo fica no código).

Modos (RF-019, DTEC-011), escolhidos por ``URGENCIA_MODO``:

* ``hibrido`` (padrão): um modelo "pensante" analisa o relato (analisa fatos, negações e gravidade antes de
                decidir). As regras de palavras-chave são o plano B: entram se o modelo estiver
                inacessível (sem internet, timeout, erro) ou inconclusivo (INSUFICIENTE ou confiança
                baixa), e também como piso de segurança contra subestimar casos graves e injeções.
* ``llm``     : só o modelo; se ele falhar, a classe é ``INSUFICIENTE`` (sem regras de reserva).
* ``regras``  : só palavras-chave; sem modelo, sem chave, sem internet.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field, replace

MODOS = ("regras", "llm", "hibrido")


def _bool(valor: str | None, padrao: bool) -> bool:
    if valor is None or valor.strip() == "":
        return padrao
    return valor.strip().lower() in {"1", "true", "sim", "yes", "on"}


def _opcional_int(valor: str | None) -> int | None:
    return int(valor) if valor not in (None, "") else None


@dataclass(frozen=True)
class Config:
    # --- geral ---
    modo: str = "hibrido"
    limiar_confianca: float = 0.6
    host: str = "127.0.0.1"
    porta: int = 8000

    # --- modelo (qualquer API compatível com OpenAI: Ollama, Groq, Gemini, ...) ---
    llm_url: str = "http://localhost:11434/v1"      # Ollama local por padrão (custo zero, dados não saem)
    llm_modelo: str = "qwen2.5:7b"
    llm_api_key: str = field(default="", repr=False)   # nunca aparece em repr/log
    llm_timeout_s: float = 20.0                      # RQ-001: 20 s por chamada
    llm_tentativas: int = 2                          # 1 chamada + 1 nova tentativa (RQ-001)
    llm_max_tokens: int = 800
    llm_reasoning_effort: str = ""                   # só para modelos de raciocínio (ex.: gpt-oss)
    llm_seed: int | None = None                      # DTEC-009: opcional, nem todo provedor aceita
    llm_json_mode: bool = True                       # response_format={"type": "json_object"}
    llm_espera_entre_tentativas_s: float = 0.5

    # --- proteção de hardware e custo (RF-012, RQ-005) ---
    vazao_por_minuto: int = 30
    max_concorrencia: int = 1

    # --- camadas de segurança (DTEC-014) ---
    piso_regras: bool = True                         # só vale no modo hibrido

    def __post_init__(self) -> None:
        if self.modo not in MODOS:
            raise ValueError(f"URGENCIA_MODO inválido: {self.modo!r} (use {', '.join(MODOS)})")
        if not 0.0 <= self.limiar_confianca <= 1.0:
            raise ValueError("URGENCIA_LIMIAR_CONFIANCA deve estar entre 0 e 1")
        if self.llm_tentativas < 1 or self.max_concorrencia < 1 or self.vazao_por_minuto < 1:
            raise ValueError("tentativas, concorrência e vazão devem ser >= 1")

    @property
    def usa_modelo(self) -> bool:
        return self.modo in ("llm", "hibrido")

    @property
    def versao_modelo(self) -> str:
        """Identificador gravado em cada resultado (RQ-006): modelo + versão do prompt."""
        from app.prompt import VERSAO_PROMPT  # import tardio: evita ciclo
        return f"{self.llm_modelo}/{VERSAO_PROMPT}"[:120]

    def com(self, **alteracoes) -> "Config":
        return replace(self, **alteracoes)

    @classmethod
    def do_ambiente(cls, env: dict[str, str] | None = None) -> "Config":
        e = os.environ if env is None else env
        d = cls  # valores padrão vêm dos atributos da classe
        return cls(
            modo=e.get("URGENCIA_MODO", d.modo).strip().lower(),
            limiar_confianca=float(e.get("URGENCIA_LIMIAR_CONFIANCA", d.limiar_confianca)),
            host=e.get("HOST", d.host),
            porta=int(e.get("PORT", d.porta)),
            llm_url=e.get("URGENCIA_LLM_URL", d.llm_url).rstrip("/"),
            llm_modelo=e.get("URGENCIA_LLM_MODELO", d.llm_modelo),
            llm_api_key=e.get("URGENCIA_LLM_API_KEY", d.llm_api_key),
            llm_timeout_s=float(e.get("URGENCIA_LLM_TIMEOUT_S", d.llm_timeout_s)),
            llm_tentativas=int(e.get("URGENCIA_LLM_TENTATIVAS", d.llm_tentativas)),
            llm_max_tokens=int(e.get("URGENCIA_LLM_MAX_TOKENS", d.llm_max_tokens)),
            llm_reasoning_effort=e.get("URGENCIA_LLM_REASONING_EFFORT", d.llm_reasoning_effort),
            llm_seed=_opcional_int(e.get("URGENCIA_LLM_SEED")),
            llm_json_mode=_bool(e.get("URGENCIA_LLM_JSON_MODE"), d.llm_json_mode),
            vazao_por_minuto=int(e.get("URGENCIA_VAZAO_POR_MINUTO", d.vazao_por_minuto)),
            max_concorrencia=int(e.get("URGENCIA_MAX_CONCORRENCIA", d.max_concorrencia)),
            piso_regras=_bool(e.get("URGENCIA_PISO_REGRAS"), d.piso_regras),
        )
