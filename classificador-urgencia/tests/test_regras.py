"""Classificador por regras: casos de exemplo, negação e uso como reserva do modelo."""
import pytest

from app.regras import classificar_por_regras
from app.schemas import SolicitacaoClassificacao


def sol(descricao, ponto=None):
    return SolicitacaoClassificacao(solicitacao_id="abcdefgh1234", denuncia_id=1,
                                    tipo_animal="CACHORRO", descricao=descricao, ponto_ref=ponto)


@pytest.mark.parametrize("texto,nivel", [
    ("Cachorro foi atropelado na avenida, está deitado no asfalto, sangrando muito e quase não se mexe.", "EMERGENCIA"),
    ("Cadela com a pata traseira quebrada, arrastando-se pela rua, com uma ferida aberta cheia de moscas.", "MUITO_URGENTE"),
    ("Cachorro acorrentado há semanas num quintal sem sombra, sem água e sem comida.", "URGENTE"),
    ("Alguns gatos vivem num terreno sujo, mas têm comida e água e parecem saudáveis.", "POUCO_URGENTE"),
    ("Gostaria de saber qual órgão cuida de animais soltos no meu bairro.", "NAO_URGENTE"),
    ("Ajuda, por favor, é urgente.", "INSUFICIENTE"),
])
def test_exemplos(texto, nivel):
    r = classificar_por_regras(sol(texto))
    assert r.nivel == nivel and r.origem == "REGRAS"
    assert r.revisao_manual == (nivel == "INSUFICIENTE")


def test_negacao_de_lesao():
    r = classificar_por_regras(sol("O cachorro não está ferido, mas vive num terreno sujo."))
    assert r.nivel == "POUCO_URGENTE"


def test_mais_grave_vence():
    assert classificar_por_regras(sol("Gato sujo e com a perna quebrada")).nivel == "MUITO_URGENTE"
