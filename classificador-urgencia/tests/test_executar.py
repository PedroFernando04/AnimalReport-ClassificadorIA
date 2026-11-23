"""T062: o executor gera relatório com cliente falso; sem modelo acessível ele pula os modos de modelo."""
from app.classifier import Classificador
from app.config import Config
from avaliacao.executar import carregar_casos, executar_modo, gerar_relatorio, main, modelo_acessivel
from tests.conftest import ClienteFalso, config, resposta


class Oraculo:
    """Cliente falso que 'acerta' o gabarito: prova o encanamento da avaliação, NÃO a qualidade de um modelo."""

    def __init__(self, casos):
        self.casos = {c["relato"][:40]: c["gabarito"] for c in casos}

    def classificar(self, mensagens):
        usuario = mensagens[1]["content"]
        nivel = next((g for k, g in self.casos.items() if k in usuario), "INSUFICIENTE")
        return resposta(nivel, "Gabarito simulado.", 0.9)


def test_executa_regras_e_modelo_falso_e_gera_relatorio():
    casos = carregar_casos("dev")
    res = [executar_modo("regras", Classificador(Config(modo="regras")), casos),
           executar_modo("hibrido", Classificador(config(), cliente=Oraculo(casos)), casos, repeticoes=3)]
    rel = gerar_relatorio(res, "dev", len(casos), {}, config())
    assert "## Resumo por modo" in rel and "`hibrido`" in rel and "CS-001" in rel
    assert res[1]["metricas"].acuracia_exata == 1.0
    assert set(res[1]["estabilidade"]) == {"D01", "D07", "D11"} and all(e["estavel"] for e in res[1]["estabilidade"].values())
    assert res[0]["estabilidade"] is None                  # regras são determinísticas: sem repetições


def test_casos_criticos_sao_repetidos_so_nos_modos_com_modelo():
    casos = [c for c in carregar_casos("dev") if c["id"] == "D01"]
    cliente = ClienteFalso(resposta("EMERGENCIA"))
    executar_modo("hibrido", Classificador(config(), cliente=cliente), casos, repeticoes=5)
    assert len(cliente.chamadas) == 5


def test_sem_modelo_o_relatorio_declara_que_nao_executou(tmp_path, monkeypatch):
    monkeypatch.setenv("URGENCIA_LLM_URL", "http://127.0.0.1:9/v1")
    saida = tmp_path / "rel.md"
    assert main(["--conjunto", "dev", "--saida", str(saida), "--json", str(tmp_path / "r.json")]) == 0
    texto = saida.read_text(encoding="utf-8")
    assert "NÃO executado" in texto and "Nenhum número de modelo" in texto and "`regras`" in texto
    assert "`hibrido`" not in texto.split("## Detalhe")[0].split("## Resumo")[1]


def test_modelo_acessivel_falha_com_motivo():
    ok, motivo = modelo_acessivel(Config(llm_url="http://127.0.0.1:9/v1"), timeout_s=1)
    assert ok is False and motivo
