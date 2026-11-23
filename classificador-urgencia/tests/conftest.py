import json
from pathlib import Path

import pytest

from app.schemas import SolicitacaoClassificacao

EXEMPLOS = Path(__file__).parent / "exemplos"


@pytest.fixture
def solicitacao() -> SolicitacaoClassificacao:
    return SolicitacaoClassificacao.model_validate(
        json.loads((EXEMPLOS / "solicitacao_valida.json").read_text(encoding="utf-8")))


def nova_solicitacao(descricao: str, **extra) -> SolicitacaoClassificacao:
    dados = {"versaoEsquema": 1, "solicitacaoId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "denunciaId": 1,
             "tipoAnimal": "CACHORRO", "descricao": descricao, **extra}
    return SolicitacaoClassificacao.model_validate(dados)


class ClienteFalso:
    """Substitui o ClienteLlm: devolve, em ordem, respostas ou levanta os erros da lista."""

    def __init__(self, *roteiro):
        from app.llm_client import ErroModelo, RespostaModelo  # noqa: F401
        self.roteiro = list(roteiro)
        self.chamadas = []

    def classificar(self, mensagens):
        self.chamadas.append(mensagens)
        item = self.roteiro.pop(0) if len(self.roteiro) > 1 else self.roteiro[0]
        if isinstance(item, Exception):
            raise item
        return item


def resposta(nivel="URGENTE", justificativa="Justificativa do modelo.", confianca=0.9):
    from app.llm_client import RespostaModelo
    return RespostaModelo(nivel, justificativa, confianca, 120, 30)


def config(**alteracoes):
    from app.config import Config
    base = dict(modo="hibrido", llm_espera_entre_tentativas_s=0.0, llm_modelo="modelo-teste")
    base.update(alteracoes)
    return Config(**base)
