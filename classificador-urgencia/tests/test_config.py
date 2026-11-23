"""Configuração por variáveis de ambiente (sem segredo no código)."""
import pytest

from app.config import Config


def test_padrao_e_hibrido_com_modelo_pensante():
    c = Config.do_ambiente({})
    assert c.modo == "hibrido" and c.usa_modelo and c.llm_max_tokens == 800 and c.host == "127.0.0.1" and c.porta == 8000
    assert c.llm_tentativas == 2 and c.llm_timeout_s == 20 and c.vazao_por_minuto == 30
    assert c.max_concorrencia == 1 and c.llm_seed is None and c.piso_regras is True


def test_le_modelo_e_parametros_do_ambiente():
    c = Config.do_ambiente({
        "URGENCIA_MODO": "Hibrido", "URGENCIA_LLM_URL": "https://api.exemplo.com/v1/",
        "URGENCIA_LLM_MODELO": "gpt-oss-20b", "URGENCIA_LLM_API_KEY": "k", "URGENCIA_LLM_SEED": "42",
        "URGENCIA_LLM_MAX_TOKENS": "1500", "URGENCIA_LLM_REASONING_EFFORT": "low",
        "URGENCIA_LLM_TIMEOUT_S": "7.5", "URGENCIA_LLM_JSON_MODE": "false", "URGENCIA_PISO_REGRAS": "nao",
        "PORT": "9000", "HOST": "0.0.0.0"})
    assert c.modo == "hibrido" and c.usa_modelo and c.llm_url == "https://api.exemplo.com/v1"
    assert (c.llm_seed, c.llm_max_tokens, c.llm_reasoning_effort, c.llm_timeout_s) == (42, 1500, "low", 7.5)
    assert c.llm_json_mode is False and c.piso_regras is False and c.porta == 9000 and c.host == "0.0.0.0"
    assert c.versao_modelo == "gpt-oss-20b/prompt-v2"


@pytest.mark.parametrize("env", [{"URGENCIA_MODO": "x"}, {"URGENCIA_LIMIAR_CONFIANCA": "2"},
                                 {"URGENCIA_LLM_TENTATIVAS": "0"}, {"URGENCIA_VAZAO_POR_MINUTO": "0"}])
def test_valores_invalidos_sao_recusados_na_partida(env):
    with pytest.raises(ValueError):
        Config.do_ambiente(env)


def test_chave_nunca_aparece_na_representacao_do_config():
    c = Config.do_ambiente({"URGENCIA_LLM_API_KEY": "SEGREDO-DA-CHAVE"})
    assert c.llm_api_key == "SEGREDO-DA-CHAVE" and "SEGREDO-DA-CHAVE" not in repr(c) + str(c)
