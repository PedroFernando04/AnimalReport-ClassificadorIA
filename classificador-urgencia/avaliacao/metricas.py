"""Métricas da avaliação (US5, CA-009, anexo B do SPEC-01, metas CS-001 a CS-006 da spec).

Convenções
* A escala ordinal tem cinco níveis (NAO_URGENTE .. EMERGENCIA). ``INSUFICIENTE`` não é um grau de
  gravidade: casos com gabarito ``INSUFICIENTE`` são avaliados à parte (CS-003) e fora da matriz.
* "Graves" = gabarito ``MUITO_URGENTE`` ou ``EMERGENCIA``.
* Quando o gabarito aceita mais de um nível (campo ``aceitos``, ex.: F11), prever um nível aceito
  conta como acerto exato.
* Prever ``INSUFICIENTE`` num caso com gabarito na escala conta como erro (nem exato, nem a 1 nível)
  e é reportado também como "INSUFICIENTE indevida".
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

ESCALA = ("NAO_URGENTE", "POUCO_URGENTE", "URGENTE", "MUITO_URGENTE", "EMERGENCIA")
GRAVES = ("MUITO_URGENTE", "EMERGENCIA")


@dataclass
class Previsao:
    """Uma classificação de um caso (uma repetição)."""
    caso_id: str
    gabarito: str
    aceitos: tuple[str, ...]
    previsto: str
    origem: str = "REGRAS"
    latencia_ms: float = 0.0
    tipo: str = "normal"
    critico: bool = False
    tokens: int | None = None
    repeticao: int = 0

    @property
    def gabarito_efetivo(self) -> str:
        return self.previsto if self.previsto in self.aceitos else self.gabarito

    @property
    def acerto_exato(self) -> bool:
        return self.previsto == self.gabarito_efetivo


@dataclass
class Metricas:
    n_ordinais: int = 0
    n_insuficientes: int = 0
    acuracia_exata: float = 0.0
    dentro_de_1: float = 0.0
    erro_ordinal_medio: float = 0.0
    por_nivel: dict[str, dict[str, float]] = field(default_factory=dict)
    f1_macro: float = 0.0
    matriz: dict[str, dict[str, int]] = field(default_factory=dict)
    n_graves: int = 0
    recall_graves: float = 0.0
    graves_subestimados_2_ou_mais: list[str] = field(default_factory=list)
    graves_com_abstencao: list[str] = field(default_factory=list)
    insuficiente_indevida: float = 0.0
    acerto_insuficiente: float | None = None
    adversariais_ok: float | None = None
    taxa_fallback: float = 0.0
    taxa_revisao_manual: float | None = None
    latencia_p50_ms: float = 0.0
    latencia_p95_ms: float = 0.0
    tokens_medios: float | None = None
    erros: list[dict] = field(default_factory=list)


def percentil(valores: list[float], p: float) -> float:
    """Percentil pelo método do posto mais próximo (suficiente para poucas dezenas de amostras)."""
    if not valores:
        return 0.0
    ordenados = sorted(valores)
    k = max(1, math.ceil(p / 100 * len(ordenados)))
    return ordenados[k - 1]


def _dist(a: str, b: str) -> int | None:
    return abs(ESCALA.index(a) - ESCALA.index(b)) if a in ESCALA and b in ESCALA else None


def calcular(previsoes: list[Previsao], revisao_manual: list[bool] | None = None) -> Metricas:
    """Calcula as métricas sobre UMA classificação por caso (use a 1ª repetição)."""
    m = Metricas()
    ordinais = [p for p in previsoes if p.gabarito in ESCALA]
    insuf = [p for p in previsoes if p.gabarito == "INSUFICIENTE"]
    m.n_ordinais, m.n_insuficientes = len(ordinais), len(insuf)

    # --- matriz de confusão (linhas = gabarito efetivo; colunas = previsto, com INSUFICIENTE) ---
    colunas = ESCALA + ("INSUFICIENTE",)
    m.matriz = {g: {c: 0 for c in colunas} for g in ESCALA}
    for p in ordinais:
        m.matriz[p.gabarito_efetivo][p.previsto] += 1

    if ordinais:
        m.acuracia_exata = sum(p.acerto_exato for p in ordinais) / len(ordinais)
        distancias = [_dist(p.previsto, p.gabarito_efetivo) for p in ordinais]
        m.dentro_de_1 = sum(1 for d in distancias if d is not None and d <= 1) / len(ordinais)
        validas = [d for d in distancias if d is not None]
        m.erro_ordinal_medio = sum(validas) / len(validas) if validas else 0.0
        m.insuficiente_indevida = sum(p.previsto == "INSUFICIENTE" for p in ordinais) / len(ordinais)

    # --- precisão / recall / F1 por nível ---
    f1s = []
    for nivel in ESCALA:
        tp = m.matriz[nivel][nivel]
        suporte = sum(m.matriz[nivel].values())
        previstos = sum(m.matriz[g][nivel] for g in ESCALA)
        precisao = tp / previstos if previstos else 0.0
        recall = tp / suporte if suporte else 0.0
        f1 = 2 * precisao * recall / (precisao + recall) if precisao + recall else 0.0
        m.por_nivel[nivel] = {"suporte": suporte, "precisao": precisao, "recall": recall, "f1": f1}
        if suporte:
            f1s.append(f1)
    m.f1_macro = sum(f1s) / len(f1s) if f1s else 0.0

    # --- casos graves (CS-002) ---
    graves = [p for p in ordinais if p.gabarito_efetivo in GRAVES]
    m.n_graves = len(graves)
    if graves:
        m.recall_graves = sum(p.previsto in GRAVES for p in graves) / len(graves)
    for p in graves:
        if p.previsto == "INSUFICIENTE":
            m.graves_com_abstencao.append(p.caso_id)
        elif ESCALA.index(p.gabarito_efetivo) - ESCALA.index(p.previsto) >= 2:
            m.graves_subestimados_2_ou_mais.append(p.caso_id)

    # --- INSUFICIENTE e adversariais ---
    if insuf:
        m.acerto_insuficiente = sum(p.previsto == "INSUFICIENTE" for p in insuf) / len(insuf)
    adv = [p for p in previsoes if p.tipo == "adversarial"]
    if adv:
        m.adversariais_ok = sum(p.acerto_exato for p in adv) / len(adv)

    # --- operação ---
    if previsoes:
        m.taxa_fallback = sum(p.origem in ("FALLBACK", "REGRAS") for p in previsoes) / len(previsoes)
        m.latencia_p50_ms = percentil([p.latencia_ms for p in previsoes], 50)
        m.latencia_p95_ms = percentil([p.latencia_ms for p in previsoes], 95)
        toks = [p.tokens for p in previsoes if p.tokens is not None]
        m.tokens_medios = sum(toks) / len(toks) if toks else None
    if revisao_manual:
        m.taxa_revisao_manual = sum(revisao_manual) / len(revisao_manual)

    m.erros = [{"id": p.caso_id, "gabarito": p.gabarito, "previsto": p.previsto, "origem": p.origem,
                "tipo": p.tipo}
               for p in previsoes if not (p.acerto_exato if p.gabarito in ESCALA else p.previsto == "INSUFICIENTE")]
    return m


def estabilidade(repeticoes: dict[str, list[str]]) -> dict[str, dict]:
    """Por caso crítico: níveis obtidos nas repetições e se a variação é de no máximo 1 nível (RQ-008)."""
    saida = {}
    for caso_id, niveis in repeticoes.items():
        if any(n not in ESCALA for n in niveis):
            amplitude = None if len(set(niveis)) > 1 else 0   # INSUFICIENTE misturado = instável
        else:
            idx = [ESCALA.index(n) for n in niveis]
            amplitude = max(idx) - min(idx)
        saida[caso_id] = {"niveis": niveis, "amplitude": amplitude,
                          "estavel": amplitude is not None and amplitude <= 1}
    return saida


def metas(m: Metricas, estab: dict[str, dict] | None = None) -> dict[str, tuple[bool | None, str]]:
    """Confere as metas CS-002, CS-003, CS-005 e RQ-008 (CS-001 e CS-006 dependem de comparação/hardware)."""
    r: dict[str, tuple[bool | None, str]] = {}
    ok2 = m.recall_graves >= 0.9 and not m.graves_subestimados_2_ou_mais
    r["CS-002"] = (ok2, f"recall dos graves {m.recall_graves:.0%} (meta ≥ 90%); "
                        f"subestimados em 2+ níveis: {len(m.graves_subestimados_2_ou_mais)} (meta 0); "
                        f"abstenções em graves: {len(m.graves_com_abstencao)}")
    ok3 = m.acuracia_exata >= 0.70 and m.dentro_de_1 >= 0.90
    r["CS-003"] = (ok3, f"exato {m.acuracia_exata:.0%} (meta ≥ 70%); a ≤ 1 nível {m.dentro_de_1:.0%} (meta ≥ 90%)")
    if m.adversariais_ok is None:
        r["CS-005"] = (None, "sem casos adversariais no conjunto")
    else:
        r["CS-005"] = (m.adversariais_ok == 1.0, f"adversariais seguindo o relato: {m.adversariais_ok:.0%} (meta 100%)")
    if estab:
        instaveis = [c for c, e in estab.items() if not e["estavel"]]
        r["RQ-008"] = (not instaveis, f"casos críticos instáveis (variação > 1 nível): {instaveis or 'nenhum'}")
    return r
