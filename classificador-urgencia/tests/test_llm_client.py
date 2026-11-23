"""T014/T045/T072: cliente do modelo com servidor falso (httpx.MockTransport)."""
import json

import httpx
import pytest

from app.llm_client import ClienteLlm, ErroModelo, interpretar_resposta
from app.prompt import montar_mensagens
from tests.conftest import config, nova_solicitacao


def cliente(handler, **cfg):
    return ClienteLlm(config(**cfg), transport=httpx.MockTransport(handler))


def ok(conteudo, usage=None):
    corpo = {"choices": [{"message": {"content": conteudo}}]}
    if usage:
        corpo["usage"] = usage
    return httpx.Response(200, json=corpo)


MSGS = montar_mensagens(nova_solicitacao("Cachorro atropelado"))
JSON_OK = '{"nivel": "EMERGENCIA", "justificativa": "Atropelado.", "confianca": 0.95}'


def test_resposta_valida_vira_resultado_com_tokens():
    c = cliente(lambda r: ok(JSON_OK, {"prompt_tokens": 210, "completion_tokens": 25}))
    r = c.classificar(MSGS)
    assert (r.nivel, r.confianca, r.tokens_entrada, r.tokens_saida) == ("EMERGENCIA", 0.95, 210, 25)


def test_corpo_enviado_tem_so_o_prompt_e_parametros_deterministicos():
    capturado = {}

    def handler(req):
        capturado["corpo"] = json.loads(req.content)
        capturado["auth"] = req.headers.get("authorization")
        capturado["url"] = str(req.url)
        return ok(JSON_OK)

    cliente(handler, llm_api_key="chave-x", llm_url="http://modelo.local/v1").classificar(MSGS)
    c = capturado["corpo"]
    assert capturado["url"] == "http://modelo.local/v1/chat/completions"
    assert capturado["auth"] == "Bearer chave-x"
    assert c["temperature"] == 0 and c["model"] == "modelo-teste"
    assert c["response_format"] == {"type": "json_object"} and "seed" not in c
    assert set(c) == {"model", "messages", "temperature", "max_tokens", "response_format"}
    # RF-003/RQ-003: nada além do relato mínimo vai no prompt
    assert "Cachorro atropelado" in json.dumps(c["messages"], ensure_ascii=False)


def test_parametros_opcionais_so_quando_configurados():
    capturado = {}
    cliente(lambda r: capturado.update(c=json.loads(r.content)) or ok(JSON_OK),
            llm_seed=7, llm_reasoning_effort="low", llm_json_mode=False).classificar(MSGS)
    assert capturado["c"]["seed"] == 7 and capturado["c"]["reasoning_effort"] == "low"
    assert "response_format" not in capturado["c"]


def test_sem_chave_nao_envia_authorization():
    capturado = {}
    cliente(lambda r: capturado.update(a=r.headers.get("authorization")) or ok(JSON_OK)).classificar(MSGS)
    assert capturado["a"] is None


@pytest.mark.parametrize("conteudo", [
    '```json\n{"nivel": "URGENTE", "justificativa": "x", "confianca": 0.7}\n```',
    'Claro! {"nivel": "URGENTE", "justificativa": "x", "confianca": 0.7} Espero ter ajudado.',
    '{"nivel": "urgente", "justificativa": "x", "confianca": 0.7}',
    '{"nivel": "Muito Urgente", "justificativa": "x", "confianca": 0.7}',
    '{"nivel": "EMERGÊNCIA", "justificativa": "x", "confianca": 0.7}',
])
def test_formatos_toleraveis_sao_normalizados(conteudo):
    assert interpretar_resposta(conteudo).nivel in {"URGENTE", "MUITO_URGENTE", "EMERGENCIA"}


@pytest.mark.parametrize("conteudo", [
    "não sei",
    "[1, 2, 3]",
    '{"nivel": "GRAVISSIMO", "justificativa": "x", "confianca": 0.7}',   # nível fora da lista (CA-006)
    '{"nivel": "PENDENTE", "justificativa": "x", "confianca": 0.7}',
    '{"nivel": 3, "justificativa": "x", "confianca": 0.7}',
    '{"justificativa": "x", "confianca": 0.7}',
    '{"nivel": "URGENTE", "justificativa": "", "confianca": 0.7}',
    '{"nivel": "URGENTE", "confianca": 0.7}',
    '{"nivel": "URGENTE", "justificativa": "x", "confianca": "alta"}',
    '{"nivel": "URGENTE", "justificativa": "x", "confianca": true}',
    '{"nivel": "URGENTE", "justificativa": "x", "confianca": 250}',
    '{"nivel": "URGENTE", "justificativa": "x", "confianca": -0.1}',
])
def test_saida_invalida_levanta_erro_repetivel(conteudo):
    with pytest.raises(ErroModelo) as e:
        interpretar_resposta(conteudo)
    assert e.value.repetivel


def test_campos_extras_do_modelo_sao_ignorados():
    r = interpretar_resposta('{"nivel": "URGENTE", "justificativa": "x", "confianca": 0.8, '
                             '"origem": "USUARIO", "revisaoManual": false, "urgencia": "NAO_URGENTE"}')
    assert r.nivel == "URGENTE" and not hasattr(r, "origem")


def test_confianca_em_porcentagem_e_convertida():
    assert interpretar_resposta('{"nivel": "URGENTE", "justificativa": "x", "confianca": 85}').confianca == 0.85


def test_justificativa_longa_e_truncada_em_300():
    r = interpretar_resposta(json.dumps({"nivel": "URGENTE", "justificativa": "a " * 400, "confianca": 0.5}))
    assert len(r.justificativa) <= 300 and r.justificativa.endswith("...")


def test_timeout_vira_erro_repetivel():
    def handler(req):
        raise httpx.ReadTimeout("lento")
    with pytest.raises(ErroModelo) as e:
        cliente(handler).classificar(MSGS)
    assert e.value.repetivel and "timeout" in e.value.motivo


def test_conexao_recusada_vira_erro():
    def handler(req):
        raise httpx.ConnectError("recusada")
    with pytest.raises(ErroModelo):
        cliente(handler).classificar(MSGS)


@pytest.mark.parametrize("status,repetivel", [(401, False), (403, False), (404, False), (429, True), (500, True), (503, True)])
def test_status_http_de_erro(status, repetivel):
    with pytest.raises(ErroModelo) as e:
        cliente(lambda r: httpx.Response(status, json={"error": {"message": "falhou"}})).classificar(MSGS)
    assert e.value.repetivel is repetivel


def test_log_do_provedor_formato_lista_gemini_sem_o_relato(caplog):
    caplog.set_level("WARNING")
    corpo = [{"error": {"code": 400, "message": "Unknown name seed"}}]
    with pytest.raises(ErroModelo):
        cliente(lambda r: httpx.Response(400, json=corpo)).classificar(montar_mensagens(nova_solicitacao("SEGREDO-123")))
    assert "status=400" in caplog.text and "Unknown name seed" in caplog.text
    assert "SEGREDO-123" not in caplog.text


@pytest.mark.parametrize("corpo", [{}, {"choices": []}, {"choices": [{"message": {}}]}, {"choices": [{"message": {"content": None}}]}])
def test_resposta_do_provedor_fora_do_formato(corpo):
    with pytest.raises(ErroModelo):
        cliente(lambda r: httpx.Response(200, json=corpo)).classificar(MSGS)


def test_resposta_vazia_de_modelo_de_raciocinio_orienta_max_tokens():
    with pytest.raises(ErroModelo) as e:
        cliente(lambda r: ok("")).classificar(MSGS)
    assert "MAX_TOKENS" in e.value.motivo


def test_campo_analise_do_modelo_pensante_e_aceito_e_descartado():
    from app.llm_client import interpretar_resposta
    r = interpretar_resposta('{"analise": "1) cão atropelado 2) sem negações 3) grave 4) emergência", '
                             '"nivel": "EMERGENCIA", "justificativa": "Atropelado.", "confianca": 0.95}')
    assert r.nivel == "EMERGENCIA" and not hasattr(r, "analise")
