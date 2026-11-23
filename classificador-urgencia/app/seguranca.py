"""Camadas de segurança em volta do modelo (DTEC-014), todas independentes dele.

1. ``detectar_injecao``: heurística barata que *sinaliza* relatos com instruções ao modelo.
   Não bloqueia nada sozinha (é fácil de burlar); serve de gatilho para a camada 2.
2. ``arbitrar``: no modo híbrido, as regras (determinísticas, imunes a instruções) funcionam como
   árbitro:
   * **piso de segurança** - se as regras veem um sinal GRAVE e o modelo ficou 2 níveis ou mais
     abaixo (ou abstendo-se), o nível sobe para o das regras. Subestimar é o pior erro (SPEC-01);
     superestimar custa só uma revisão a mais.
   * **suspeita de injeção** - se há instrução embutida e as regras são conclusivas, vale o nível
     das regras (o modelo pode ter obedecido ao relato em vez de avaliá-lo).
   Em ambos os casos a revisão manual é marcada e a justificativa diz o que aconteceu.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from app.schemas import ResultadoClassificacao

# Ordem da escala de gravidade. INSUFICIENTE fica fora da escala (índice -1).
ESCALA = ("NAO_URGENTE", "POUCO_URGENTE", "URGENTE", "MUITO_URGENTE", "EMERGENCIA")
GRAVES = ("MUITO_URGENTE", "EMERGENCIA")
VERSAO_ARBITRO = "arbitro-regras-v1"


def indice(nivel: str) -> int:
    return ESCALA.index(nivel) if nivel in ESCALA else -1


def _normalizar(texto: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", texto.lower()) if unicodedata.category(c) != "Mn")


_PADROES_INJECAO = [re.compile(p) for p in (
    r"ignor\w* (as |todas as |suas )?(instruc|regra|ordens|orientac)",
    r"desconsider\w* (as |todas as )?(instruc|regra)",
    r"(ignore|disregard|forget) (all |the |any )?(previous|prior|above|earlier)",
    r"\bnovas? instruc",
    r"voce (agora )?(e|sera|vai ser) (um|uma) (novo |nova )?(assistente|ia|robo|modelo|sistema|classificador)",
    r"classifi\w* (esta|essa|a|o)? ?(denuncia |relato |caso )?como",
    r"(responda|retorne|devolva|use|marque|defina|atribua) (apenas |somente )?(o )?(nivel|urgencia)",
    r"\b(sistema|system|assistant|assistente)\s*:",
    r"\bnivel\s*[:=]\s*\w+",
    r"revel\w* (o |seu |as )?(prompt|instruc)",
)]


def detectar_injecao(*textos: str | None) -> bool:
    """``True`` se algum texto contém instruções endereçadas ao modelo (heurística)."""
    junto = _normalizar(" ".join(t for t in textos if t))
    return any(p.search(junto) for p in _PADROES_INJECAO)


@dataclass(frozen=True)
class Arbitragem:
    nivel: str
    justificativa: str
    confianca: float
    motivo: str | None            # None = o modelo foi mantido; "piso" ou "injecao" = foi sobreposto


def arbitrar(nivel_modelo: str, justificativa: str, confianca: float,
             regras: ResultadoClassificacao, injecao_suspeita: bool) -> Arbitragem:
    """Combina o resultado do modelo com o das regras. Só atua no modo híbrido."""
    regras_conclusivas = regras.nivel != "INSUFICIENTE"
    idx_modelo, idx_regras = indice(nivel_modelo), indice(regras.nivel)

    if regras.nivel in GRAVES and idx_regras - idx_modelo >= 2:
        return Arbitragem(
            regras.nivel,
            f"Piso de segurança: {regras.justificativa.removeprefix('Regras: ')} "
            f"O modelo havia indicado {nivel_modelo}.",
            min(confianca, regras.confianca), "piso")

    if injecao_suspeita and regras_conclusivas and regras.nivel != nivel_modelo:
        return Arbitragem(
            regras.nivel,
            f"Relato com instruções embutidas; prevaleceu a leitura das regras. "
            f"{regras.justificativa.removeprefix('Regras: ')}",
            min(confianca, regras.confianca), "injecao")

    return Arbitragem(nivel_modelo, justificativa, confianca, None)
