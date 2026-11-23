"""T021 (parte Python): os logs nao contem o texto do relato, ponto de referencia nem contato (RQ-004)."""
import logging

from app.classifier import Classificador
from tests.conftest import nova_solicitacao

SEGREDO = "FRASE-SECRETA-DO-RELATO-9981"


def test_log_tem_metricas_e_nao_tem_o_relato(caplog):
    caplog.set_level(logging.DEBUG)
    s = nova_solicitacao(f"Cachorro sem água. {SEGREDO}", pontoRef=f"perto de {SEGREDO}")
    Classificador().classificar(s)

    assert "latenciaMs=" in caplog.text and "origem=REGRAS" in caplog.text
    assert SEGREDO not in caplog.text
