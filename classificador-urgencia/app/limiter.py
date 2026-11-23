"""Limite de vazão das chamadas ao modelo (RF-012, RQ-005): protege hardware, cota e custo."""
from __future__ import annotations

import threading
import time
from collections import deque
from typing import Callable


class LimitadorVazao:
    """Janela deslizante: no máximo ``maximo`` chamadas em qualquer intervalo de ``janela_s`` segundos.

    ``tentar()`` nunca bloqueia: devolve ``False`` quando o limite foi atingido e o chamador
    segue para o plano B (regras), em vez de enfileirar e estourar o prazo da denúncia.
    """

    def __init__(self, maximo: int, janela_s: float = 60.0,
                 relogio: Callable[[], float] = time.monotonic) -> None:
        if maximo < 1:
            raise ValueError("maximo deve ser >= 1")
        self._maximo = maximo
        self._janela = janela_s
        self._relogio = relogio
        self._marcas: deque[float] = deque()
        self._trava = threading.Lock()

    def tentar(self) -> bool:
        agora = self._relogio()
        with self._trava:
            while self._marcas and agora - self._marcas[0] >= self._janela:
                self._marcas.popleft()
            if len(self._marcas) >= self._maximo:
                return False
            self._marcas.append(agora)
            return True
