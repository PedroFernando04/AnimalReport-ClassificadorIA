"""Transporte HTTP: mesmo contrato da fila, com token opcional."""
import json
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from app.http_server import criar_handler
from app.schemas import ResultadoClassificacao
from tests.conftest import EXEMPLOS


class ClassificadorFalso:
    def classificar(self, s):
        return ResultadoClassificacao(
            solicitacao_id=s.solicitacao_id, denuncia_id=s.denuncia_id, nivel="URGENTE",
            justificativa="Teste.", confianca=0.9, origem="IA", versao_classificador="m/prompt-v2",
            revisao_manual=False, latencia_ms=5, tentativas=1)


@pytest.fixture()
def servidor():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), criar_handler(ClassificadorFalso(), "segredo"))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def post(url, corpo, token="segredo"):
    req = urllib.request.Request(url + "/classificar", data=corpo, method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
    return urllib.request.urlopen(req)


def test_classifica_solicitacao_valida(servidor):
    corpo = (EXEMPLOS / "solicitacao_valida.json").read_bytes()
    with post(servidor, corpo) as r:
        dados = json.load(r)
    assert r.status == 200 and dados["nivel"] == "URGENTE"
    assert dados["solicitacaoId"] == json.loads(corpo)["solicitacaoId"]


def test_token_errado_401(servidor):
    with pytest.raises(urllib.error.HTTPError) as e:
        post(servidor, (EXEMPLOS / "solicitacao_valida.json").read_bytes(), token="x")
    assert e.value.code == 401


def test_campo_extra_422(servidor):
    with pytest.raises(urllib.error.HTTPError) as e:
        post(servidor, (EXEMPLOS / "solicitacao_invalida_campo_extra.json").read_bytes())
    assert e.value.code == 422


def test_saude(servidor):
    assert urllib.request.urlopen(servidor + "/saude").status == 200


def test_corpo_chunked(servidor):
    import http.client
    corpo = (EXEMPLOS / "solicitacao_valida.json").read_bytes()
    host, porta = servidor.replace("http://", "").split(":")
    c = http.client.HTTPConnection(host, int(porta))
    c.request("POST", "/classificar", body=iter([corpo[:20], corpo[20:]]), encode_chunked=True,
              headers={"Content-Type": "application/json", "Authorization": "Bearer segredo"})
    r = c.getresponse()
    assert r.status == 200 and json.loads(r.read())["nivel"] == "URGENTE"
