"""T035: janela por minuto e reinício após o período (RF-012)."""
import pytest

from app.limiter import LimitadorVazao


class Relogio:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


def test_bloqueia_ao_atingir_o_maximo_e_libera_apos_a_janela():
    r = Relogio()
    lim = LimitadorVazao(3, 60, r)
    assert [lim.tentar() for _ in range(4)] == [True, True, True, False]
    r.t = 59.9
    assert lim.tentar() is False
    r.t = 60.0
    assert lim.tentar() is True


def test_janela_deslizante_libera_uma_vaga_por_vez():
    r = Relogio()
    lim = LimitadorVazao(2, 60, r)
    assert lim.tentar()            # t=0
    r.t = 30
    assert lim.tentar()            # t=30
    r.t = 61
    assert lim.tentar()            # a de t=0 saiu da janela
    assert lim.tentar() is False   # a de t=30 ainda conta


def test_maximo_invalido():
    with pytest.raises(ValueError):
        LimitadorVazao(0)
