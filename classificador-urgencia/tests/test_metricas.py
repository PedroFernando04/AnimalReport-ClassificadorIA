"""T060: métricas com matriz de confusão conhecida."""
import pytest

from avaliacao.metricas import Previsao, calcular, estabilidade, metas, percentil


def P(id_, gab, prev, **kw):
    return Previsao(id_, gab, tuple(kw.pop("aceitos", ())), prev, **kw)


CASOS = [
    P("a", "EMERGENCIA", "EMERGENCIA"),
    P("b", "EMERGENCIA", "MUITO_URGENTE"),        # erro de 1 nível, ainda grave
    P("c", "EMERGENCIA", "URGENTE"),              # grave subestimado em 2 níveis
    P("d", "MUITO_URGENTE", "MUITO_URGENTE"),
    P("e", "URGENTE", "URGENTE"),
    P("f", "NAO_URGENTE", "POUCO_URGENTE"),
    P("g", "NAO_URGENTE", "INSUFICIENTE"),        # abstenção indevida
    P("h", "INSUFICIENTE", "INSUFICIENTE"),       # avaliado à parte
    P("i", "INSUFICIENTE", "URGENTE"),
]


def test_contagens_e_acuracia():
    m = calcular(CASOS)
    assert (m.n_ordinais, m.n_insuficientes) == (7, 2)
    assert m.acuracia_exata == pytest.approx(3 / 7)                 # a, d, e
    assert m.dentro_de_1 == pytest.approx(5 / 7)                    # + b, f
    assert m.insuficiente_indevida == pytest.approx(1 / 7)
    assert m.acerto_insuficiente == pytest.approx(0.5)


def test_precisao_recall_f1_por_nivel():
    m = calcular(CASOS)
    e = m.por_nivel["EMERGENCIA"]
    assert (e["suporte"], e["precisao"], e["recall"]) == (3, 1.0, pytest.approx(1 / 3))
    assert e["f1"] == pytest.approx(0.5)
    p = m.por_nivel["POUCO_URGENTE"]
    assert (p["suporte"], p["precisao"], p["recall"], p["f1"]) == (0, 0.0, 0.0, 0.0)
    assert m.por_nivel["URGENTE"]["precisao"] == pytest.approx(0.5)     # previsto em c (errado) e e (certo)
    assert m.matriz["NAO_URGENTE"]["POUCO_URGENTE"] == 1 and m.matriz["NAO_URGENTE"]["INSUFICIENTE"] == 1


def test_f1_macro_ignora_niveis_sem_suporte():
    m = calcular(CASOS)
    com_suporte = [d["f1"] for d in m.por_nivel.values() if d["suporte"]]
    assert m.f1_macro == pytest.approx(sum(com_suporte) / len(com_suporte))


def test_recall_dos_graves_e_subestimados():
    m = calcular(CASOS)
    assert m.n_graves == 4
    assert m.recall_graves == pytest.approx(3 / 4)                  # a, b, d previstos como graves
    assert m.graves_subestimados_2_ou_mais == ["c"] and m.graves_com_abstencao == []


def test_abstencao_em_caso_grave_e_reportada_a_parte():
    m = calcular([P("x", "EMERGENCIA", "INSUFICIENTE")])
    assert m.graves_com_abstencao == ["x"] and m.graves_subestimados_2_ou_mais == [] and m.recall_graves == 0


def test_gabarito_com_niveis_aceitos():
    m = calcular([P("f11", "MUITO_URGENTE", "EMERGENCIA", aceitos=["EMERGENCIA"])])
    assert m.acuracia_exata == 1.0 and m.matriz["EMERGENCIA"]["EMERGENCIA"] == 1 and not m.erros


def test_adversariais_e_erros_listados():
    m = calcular([P("d11", "EMERGENCIA", "EMERGENCIA", tipo="adversarial"),
                  P("f09", "NAO_URGENTE", "EMERGENCIA", tipo="adversarial")])
    assert m.adversariais_ok == 0.5 and [e["id"] for e in m.erros] == ["f09"]


def test_latencia_percentis_e_origem():
    m = calcular([P(str(i), "URGENTE", "URGENTE", latencia_ms=float(i), origem="IA" if i % 2 else "REGRAS")
                  for i in range(1, 11)])
    assert (m.latencia_p50_ms, m.latencia_p95_ms) == (5.0, 10.0)
    assert m.taxa_fallback == 0.5
    assert percentil([], 95) == 0.0


def test_estabilidade_variacao_maxima_de_um_nivel():
    e = estabilidade({"ok": ["EMERGENCIA", "MUITO_URGENTE", "EMERGENCIA"], "ruim": ["EMERGENCIA", "URGENTE"],
                      "abst": ["EMERGENCIA", "INSUFICIENTE"], "fixo": ["URGENTE"] * 5})
    assert e["ok"]["estavel"] and e["fixo"]["estavel"] and not e["ruim"]["estavel"] and not e["abst"]["estavel"]


def test_metas_cs():
    ruim = calcular(CASOS)
    r = metas(ruim, estabilidade({"c": ["EMERGENCIA", "URGENTE"]}))
    assert r["CS-002"][0] is False and r["CS-003"][0] is False and r["RQ-008"][0] is False
    otimo = calcular([P("a", "EMERGENCIA", "EMERGENCIA"), P("b", "URGENTE", "URGENTE", tipo="adversarial")] * 5)
    r = metas(otimo)
    assert r["CS-002"][0] and r["CS-003"][0] and r["CS-005"][0]
