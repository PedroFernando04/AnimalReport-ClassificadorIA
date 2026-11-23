"""T010: contrato das mensagens. Os mesmos JSONs de tests/exemplos/ são lidos pelos testes Java."""
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.schemas import NIVEIS, ResultadoClassificacao, SolicitacaoClassificacao

EXEMPLOS = Path(__file__).parent / "exemplos"


def carregar(nome: str) -> dict:
    return json.loads((EXEMPLOS / nome).read_text(encoding="utf-8"))


def test_niveis_sao_os_cinco_mais_insuficiente():
    assert set(NIVEIS) == {
        "NAO_URGENTE", "POUCO_URGENTE", "URGENTE", "MUITO_URGENTE", "EMERGENCIA", "INSUFICIENTE",
    }


@pytest.mark.parametrize("nome", ["solicitacao_valida.json", "solicitacao_sem_ponto_ref.json"])
def test_solicitacao_valida(nome):
    s = SolicitacaoClassificacao.model_validate(carregar(nome))
    assert s.descricao
    assert s.solicitacao_id


def test_solicitacao_campos_em_camel_case():
    s = SolicitacaoClassificacao.model_validate(carregar("solicitacao_valida.json"))
    assert s.denuncia_id == 42
    assert s.tipo_animal == "CACHORRO"
    assert s.ponto_ref == "Em frente ao mercado"


@pytest.mark.parametrize(
    "nome",
    [
        "solicitacao_invalida_campo_extra.json",  # contato não pode trafegar (RF-003, RQ-003)
        "solicitacao_invalida_sem_descricao.json",
        "solicitacao_invalida_versao.json",
    ],
)
def test_solicitacao_invalida(nome):
    with pytest.raises(ValidationError):
        SolicitacaoClassificacao.model_validate(carregar(nome))


@pytest.mark.parametrize("nome", ["resultado_valido.json", "resultado_insuficiente.json"])
def test_resultado_valido(nome):
    r = ResultadoClassificacao.model_validate(carregar(nome))
    assert 0.0 <= r.confianca <= 1.0
    assert 1 <= len(r.justificativa) <= 300


@pytest.mark.parametrize(
    "nome",
    [
        "resultado_invalido_nivel.json",
        "resultado_invalido_confianca.json",
        "resultado_invalido_sem_justificativa.json",
        "resultado_invalido_justificativa_longa.json",
    ],
)
def test_resultado_invalido(nome):
    with pytest.raises(ValidationError):
        ResultadoClassificacao.model_validate(carregar(nome))


def test_resultado_serializa_em_camel_case_sem_perder_campos():
    original = carregar("resultado_valido.json")
    r = ResultadoClassificacao.model_validate(original)
    assert json.loads(r.para_json()) == original
