"""Classificador por regras (palavras-chave), sem modelo de linguagem. Pensado para o MVP e como reserva.

Escolhe o nível MAIS GRAVE cujos sinais aparecem no relato. Sinais de lesão
ignorados quando negados ("não está ferido", "sem ferimentos"). Sem nenhum sinal -> INSUFICIENTE.
"""
from __future__ import annotations

import re
import time
import unicodedata

from app.schemas import ResultadoClassificacao, SolicitacaoClassificacao

VERSAO_REGRAS = "regras-v1"

# (nível, confiança, [padrões em texto sem acento e minúsculo], sujeito_a_negacao)
_REGRAS: list[tuple[str, float, list[str], bool]] = [
    ("EMERGENCIA", 0.9, [
        r"atropel", r"sangr", r"hemorrag", r"envenen", r"intoxic", r"convuls", r"desacordad",
        r"inconscient", r"nao (se )?(mexe|move|levanta|reage)", r"quase nao (se )?(mexe|move)",
        r"afog", r"baleado|baleada|tiro", r"esfaquead", r"espancad", r"sendo (agredid|espancad|maltratad)",
        r"agonizand", r"morrendo", r"respirando com dificuldade", r"queimad",
    ], True),
    ("MUITO_URGENTE", 0.8, [
        r"fratur", r"quebrad", r"ferida aberta", r"ferid[oa]", r"ferimento", r"desnutri", r"muito magr",
        r"pele e osso", r"doente", r"vomit", r"diarreia", r"sarna", r"manca|mancando", r"arrastand",
        r"\bpus\b", r"infecc", r"miase|bicheira|larva|verme", r"tumor", r"machucad", r"sangue",
    ], True),
    ("URGENTE", 0.75, [
        r"acorrentad", r"preso|presa", r"trancad", r"sem agua", r"sem comida", r"sem abrigo", r"sem sombra",
        r"abandon", r"maus.?tratos", r"agred", r"apanh", r"negligenc", r"sem cuidado", r"deixad[oa] (la|sozinho)",
    ], False),
    ("POUCO_URGENTE", 0.65, [
        r"sujo|suja|sujeira", r"higiene", r"pulga", r"carrapato", r"solto na rua", r"terreno", r"confinad",
        r"espaco pequeno",
    ], False),
    ("NAO_URGENTE", 0.6, [
        r"queria saber", r"gostaria de saber", r"duvida", r"como (faco|posso)", r"qual (o )?orgao",
        r"informacao", r"saudavel", r"aparentemente (bem|saudavel)",
    ], False),
]

_NEGADORES = {"nao", "sem", "nenhum", "nenhuma", "nem"}


def _normalizar(texto: str) -> str:
    sem_acento = unicodedata.normalize("NFD", texto.lower())
    return "".join(c for c in sem_acento if unicodedata.category(c) != "Mn")


def _negado(texto: str, inicio: int) -> bool:
    anteriores = re.findall(r"\w+", texto[max(0, inicio - 30):inicio])[-3:]
    return any(p in _NEGADORES for p in anteriores)


def _achar(texto: str, padroes: list[str], negavel: bool) -> str | None:
    for p in padroes:
        for m in re.finditer(p, texto):
            if negavel and _negado(texto, m.start()):
                continue
            return m.group(0).strip()
    return None


def classificar_por_regras(s: SolicitacaoClassificacao, limiar: float = 0.6) -> ResultadoClassificacao:
    inicio = time.perf_counter()
    texto = _normalizar(f"{s.descricao} {s.ponto_ref or ''}")
    nivel, confianca, sinal = "INSUFICIENTE", 0.2, None
    for n, conf, padroes, negavel in _REGRAS:  # do mais grave ao menos grave
        achado = _achar(texto, padroes, negavel)
        if achado:
            nivel, confianca, sinal = n, conf, achado
            break
    if sinal:
        justificativa = f"Regras: o relato menciona \"{sinal}\"."
    else:
        justificativa = "Regras: o relato não traz sinais suficientes para decidir; revisão humana."
    return ResultadoClassificacao(
        solicitacao_id=s.solicitacao_id, denuncia_id=s.denuncia_id, nivel=nivel,
        justificativa=justificativa[:300], confianca=confianca, origem="REGRAS",
        versao_classificador=VERSAO_REGRAS,
        revisao_manual=(nivel == "INSUFICIENTE" or confianca < limiar),
        latencia_ms=int((time.perf_counter() - inicio) * 1000), tentativas=0)
