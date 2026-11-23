"""Agente de urgencia: servidor HTTP (biblioteca padrao + pydantic; httpx so para o modelo).

POST /classificar  corpo = SolicitacaoClassificacao (JSON)  ->  ResultadoClassificacao (JSON)
GET  /saude        -> 200 "ok"

Se AGENTE_TOKEN estiver definido, exige "Authorization: Bearer <token>" em /classificar.
"""
from __future__ import annotations

import hmac
import logging
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from pydantic import ValidationError

from app.classifier import Classificador
from app.config import Config
from app.schemas import SolicitacaoClassificacao

log = logging.getLogger("agente.http")

MAX_CORPO = 64 * 1024


def _ler_corpo(h: BaseHTTPRequestHandler) -> bytes | None:
    """Lê o corpo com Content-Length ou com Transfer-Encoding: chunked (o cliente Java pode enviar assim)."""
    if "chunked" in h.headers.get("Transfer-Encoding", "").lower():
        partes, total = [], 0
        while True:
            try:
                tam = int(h.rfile.readline().split(b";")[0].strip() or b"0", 16)
            except ValueError:
                return None
            if tam == 0:
                while h.rfile.readline().strip():  # descarta trailers
                    pass
                break
            total += tam
            if total > MAX_CORPO:
                return None
            partes.append(h.rfile.read(tam))
            h.rfile.readline()  # CRLF depois do bloco
        return b"".join(partes) or None
    try:
        tamanho = int(h.headers.get("Content-Length", "0"))
    except ValueError:
        return None
    if tamanho <= 0 or tamanho > MAX_CORPO:
        return None
    return h.rfile.read(tamanho)


def criar_handler(classificador: Classificador, token: str = ""):
    class Handler(BaseHTTPRequestHandler):
        def _responder(self, status: int, corpo: bytes = b"", tipo: str = "application/json") -> None:
            self.send_response(status)
            self.send_header("Content-Type", tipo)
            self.send_header("Content-Length", str(len(corpo)))
            self.end_headers()
            self.wfile.write(corpo)

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/saude":
                self._responder(200, b"ok", "text/plain")
            else:
                self._responder(404)

        def do_POST(self) -> None:  # noqa: N802
            if self.path != "/classificar":
                return self._responder(404)
            if token and not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {token}"):
                return self._responder(401)
            corpo = _ler_corpo(self)
            if corpo is None:
                return self._responder(400)
            try:
                solicitacao = SolicitacaoClassificacao.model_validate_json(corpo)
            except ValidationError:
                log.warning("solicitação fora do contrato")  # sem o corpo: pode conter o relato
                return self._responder(422)
            try:
                resultado = classificador.classificar(solicitacao)
            except Exception as e:  # noqa: BLE001
                log.error("falha ao classificar solicitacaoId=%s: %s", solicitacao.solicitacao_id, type(e).__name__)
                return self._responder(500)
            self._responder(200, resultado.para_json().encode("utf-8"))

        def log_message(self, formato, *args) -> None:  # silencia o log padrão (traz o caminho, sem corpo)
            pass

    return Handler


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cfg = Config.do_ambiente()
    classificador = Classificador(cfg)
    # Padrão 127.0.0.1: aceita chamadas apenas desta máquina. Em contêiner/hospedagem use HOST=0.0.0.0
    # e defina AGENTE_TOKEN.
    servidor = ThreadingHTTPServer((cfg.host, cfg.porta), criar_handler(
        classificador, os.environ.get("AGENTE_TOKEN", "")))
    if cfg.usa_modelo:
        log.info("agente de urgencia pronto em http://%s:%d modo=%s modelo=%s (a chave nunca é registrada)",
                 cfg.host, cfg.porta, cfg.modo, cfg.llm_modelo)
        if cfg.llm_api_key or not cfg.llm_url.startswith(("http://localhost", "http://127.0.0.1")):
            log.warning("o texto dos relatos será enviado a %s: use só com dados fictícios (RQ-009)", cfg.llm_url)
    else:
        log.info("agente de urgencia pronto em http://%s:%d modo=regras (sem modelo)", cfg.host, cfg.porta)
    servidor.serve_forever()


if __name__ == "__main__":
    main()
