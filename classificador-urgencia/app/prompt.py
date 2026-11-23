"""Montagem do prompt versionado e defesa contra injeção de instruções (RF-009, DTEC-008).

O texto do cidadão é tratado como DADO: vai dentro de um bloco delimitado por marcadores
com sufixo aleatório (não adivinhável), é higienizado para não conter marcadores falsos e é
truncado (início + fim) acima do limite. A barreira que não depende do modelo é a validação
da saída (llm_client) e o árbitro por regras (seguranca).
"""
from __future__ import annotations

import re
import secrets
from pathlib import Path

from app.schemas import SolicitacaoClassificacao

VERSAO_PROMPT = "prompt-v2"
ARQUIVO_PROMPT = Path(__file__).resolve().parent.parent / "prompts" / "urgencia-v2.txt"

MAX_DESCRICAO = 2000          # Q-014
MAX_PONTO_REF = 300
OMISSAO = "\n[...trecho omitido...]\n"

_CONTROLE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_MARCADORES = re.compile(r"[<>=#`]{3,}")          # runs que poderiam imitar delimitadores/markdown
_INSTRUCAO_DE_DELIMITADOR = re.compile(r"(?i)\b(inicio|fim)[_ ]?relato\b")


def _sistema_base() -> str:
    return ARQUIVO_PROMPT.read_text(encoding="utf-8")


def neutralizar(texto: str) -> str:
    """Remove caracteres de controle e sequências que imitam os delimitadores do bloco de dados."""
    texto = _CONTROLE.sub("", texto)
    texto = _MARCADORES.sub(lambda m: m.group(0)[0], texto)
    return _INSTRUCAO_DE_DELIMITADOR.sub("[marcador removido]", texto)


def truncar_relato(texto: str, limite: int = MAX_DESCRICAO) -> str:
    """Acima do limite, mantém o início e o fim: a informação decisiva costuma estar no fim (caso F13)."""
    if len(texto) <= limite:
        return texto
    metade = limite // 2
    return texto[:metade] + OMISSAO + texto[-metade:]


def montar_mensagens(s: SolicitacaoClassificacao, nonce: str | None = None) -> list[dict[str, str]]:
    """Devolve as mensagens ``system`` e ``user`` no formato de chat compatível com OpenAI."""
    nonce = nonce or secrets.token_hex(4)
    inicio, fim = f"<<<RELATO {nonce}>>>", f"<<<FIM DO RELATO {nonce}>>>"
    sistema = _sistema_base().replace("{{INICIO}}", inicio).replace("{{FIM}}", fim)

    descricao = neutralizar(truncar_relato(s.descricao.strip()))
    linhas = [inicio, f"tipo_animal: {neutralizar(s.tipo_animal)}", f"descricao: {descricao}"]
    if s.ponto_ref:
        linhas.append(f"ponto_referencia: {neutralizar(truncar_relato(s.ponto_ref.strip(), MAX_PONTO_REF))}")
    linhas.append(fim)
    usuario = "Avalie a denúncia abaixo e responda apenas com o JSON de saída.\n" + "\n".join(linhas)
    return [{"role": "system", "content": sistema}, {"role": "user", "content": usuario}]
