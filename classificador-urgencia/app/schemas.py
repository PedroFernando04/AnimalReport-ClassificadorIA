"""Contrato das mensagens trocadas com a aplicação Java (plan.md, seção Contratos).

Os nomes dos campos nas mensagens JSON são camelCase; em Python usamos snake_case
e o alias converte nos dois sentidos.
"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

VERSAO_ESQUEMA = 1

Nivel = Literal[
    "NAO_URGENTE", "POUCO_URGENTE", "URGENTE", "MUITO_URGENTE", "EMERGENCIA", "INSUFICIENTE"
]
Origem = Literal["IA", "REGRAS", "FALLBACK"]

NIVEIS: tuple[str, ...] = Nivel.__args__  # type: ignore[attr-defined]

# Tamanho máximo aceito na mensagem. O limite funcional de 2000 caracteres é aplicado
# pelo formulário Java e, como defesa, pelo prompt (truncamento início + fim).
MAX_DESCRICAO_MENSAGEM = 10_000


class _Base(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class SolicitacaoClassificacao(_Base):
    """Entrada mínima (RF-003, RQ-003). Qualquer campo extra (contato, e-mail, foto...)
    invalida a mensagem, que vai para a fila de mensagens mortas."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")

    versao_esquema: Literal[1] = VERSAO_ESQUEMA
    solicitacao_id: str = Field(min_length=8, max_length=64)
    denuncia_id: int = Field(ge=0)
    tipo_animal: str = Field(min_length=1, max_length=50)
    descricao: str = Field(min_length=1, max_length=MAX_DESCRICAO_MENSAGEM)
    ponto_ref: str | None = Field(default=None, max_length=2000)


class ResultadoClassificacao(_Base):
    """Saída do agente. Invariantes: justificativa de 1 a 300 caracteres, confiança de 0 a 1."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")

    versao_esquema: Literal[1] = VERSAO_ESQUEMA
    solicitacao_id: str
    denuncia_id: int
    nivel: Nivel
    justificativa: str = Field(min_length=1, max_length=300)
    confianca: float = Field(ge=0.0, le=1.0)
    origem: Origem
    versao_classificador: str = Field(min_length=1, max_length=120)
    revisao_manual: bool
    latencia_ms: int = Field(ge=0)
    tentativas: int = Field(ge=0)

    def para_json(self) -> str:
        return self.model_dump_json(by_alias=True)
