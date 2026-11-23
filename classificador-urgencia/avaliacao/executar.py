"""Executa a avaliação (US5, T062): compara os modos do agente sobre o conjunto de casos e gera o relatório.

Uso (a partir de ``classificador-urgencia/``):

    python -m avaliacao.executar                       # regras (sempre) + modelo, se estiver acessível
    python -m avaliacao.executar --conjunto final      # conjunto reservado: rode UMA vez, para o relatório
    python -m avaliacao.executar --modos regras        # só a linha de base
    python -m avaliacao.executar --saida ../docs/ia/avaliacao-gerada.md --json resultado.json

O modelo vem das mesmas variáveis de ambiente do agente (URGENCIA_LLM_URL, URGENCIA_LLM_MODELO,
URGENCIA_LLM_API_KEY, ...). Sem modelo acessível, os modos ``llm``/``hibrido`` são pulados e o
relatório diz isso: nenhum número de modelo é inventado.

Atenção: no conjunto ``final`` não se ajusta prompt nem regras (anexo B do SPEC-01).
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

import httpx

from app.classifier import Classificador
from app.config import Config
from app.schemas import SolicitacaoClassificacao
from avaliacao.metricas import ESCALA, Metricas, Previsao, calcular, estabilidade, metas

ARQUIVO_CASOS = Path(__file__).with_name("casos-urgencia.json")
REPETICOES_PADRAO = 5


def carregar_casos(conjunto: str = "todos", caminho: Path = ARQUIVO_CASOS) -> list[dict]:
    casos = json.loads(caminho.read_text(encoding="utf-8"))
    return casos if conjunto == "todos" else [c for c in casos if c["conjunto"] == conjunto]


def _solicitacao(caso: dict, indice: int) -> SolicitacaoClassificacao:
    return SolicitacaoClassificacao(solicitacao_id=str(uuid.uuid4()), denuncia_id=indice,
                                    tipo_animal=caso["tipoAnimal"], descricao=caso["relato"])


def executar_modo(nome: str, classificador: Classificador, casos: list[dict],
                  repeticoes: int = REPETICOES_PADRAO) -> dict:
    """Classifica todos os casos uma vez e repete ``repeticoes`` vezes os casos críticos (RQ-008)."""
    previsoes: list[Previsao] = []
    revisoes: list[bool] = []
    repeticao_critica: dict[str, list[str]] = {}
    for i, caso in enumerate(casos):
        n = repeticoes if caso.get("critico") and classificador._cfg.usa_modelo else 1  # noqa: SLF001
        for rep in range(n):
            inicio = time.perf_counter()
            r = classificador.classificar(_solicitacao(caso, i))
            ms = (time.perf_counter() - inicio) * 1000
            if rep == 0:
                previsoes.append(Previsao(caso["id"], caso["gabarito"], tuple(caso.get("aceitos", [])),
                                          r.nivel, r.origem, ms, caso["tipo"], bool(caso.get("critico"))))
                revisoes.append(r.revisao_manual)
            if caso.get("critico"):
                repeticao_critica.setdefault(caso["id"], []).append(r.nivel)
    estab = estabilidade(repeticao_critica) if repeticoes > 1 and classificador._cfg.usa_modelo else None  # noqa: SLF001
    metricas = calcular(previsoes, revisoes)
    return {"modo": nome, "metricas": metricas, "estabilidade": estab, "metas": metas(metricas, estab),
            "previsoes": previsoes, "revisao_manual": revisoes}


# ---------------------------------------------------------------------------- disponibilidade do modelo

def modelo_acessivel(cfg: Config, timeout_s: float = 3.0) -> tuple[bool, str]:
    """Teste barato: GET {url}/models. Não envia nenhum relato."""
    try:
        cab = {"Authorization": f"Bearer {cfg.llm_api_key}"} if cfg.llm_api_key else {}
        r = httpx.get(f"{cfg.llm_url}/models", headers=cab, timeout=timeout_s)
        if r.status_code < 400:
            return True, "ok"
        return False, f"http {r.status_code}"
    except httpx.HTTPError as e:
        return False, type(e).__name__


# ---------------------------------------------------------------------------- relatório

def _pct(x: float | None) -> str:
    return "—" if x is None else f"{x:.0%}"


def _mark(ok: bool | None) -> str:
    return "n/a" if ok is None else ("ATENDE" if ok else "NÃO ATENDE")


def gerar_relatorio(resultados: list[dict], conjunto: str, n_casos: int, pulados: dict[str, str],
                    cfg: Config) -> str:
    L: list[str] = []
    L.append("# Relatório de avaliação do classificador de urgência")
    L.append("")
    L.append(f"- Gerado em: {datetime.now().strftime('%d/%m/%Y %H:%M')}")
    L.append(f"- Conjunto: `{conjunto}` ({n_casos} casos). Rubrica v1, prompt `prompt-v2`, regras `regras-v1`.")
    com_modelo = [r["modo"] for r in resultados if r["modo"] != "regras"]
    if com_modelo:
        L.append(f"- Modelo: `{cfg.llm_modelo}` em `{cfg.llm_url}` (temperatura 0, até {cfg.llm_tentativas} tentativas, "
                 f"timeout {cfg.llm_timeout_s:g} s).")
    for modo, motivo in pulados.items():
        L.append(f"- **Modo `{modo}` NÃO executado:** {motivo}. Nenhum número de modelo é apresentado.")
    if conjunto == "final":
        L.append("- ⚠ Conjunto reservado: não ajuste prompt nem regras com base nestes resultados.")
    L.append("")

    L.append("## Resumo por modo")
    L.append("")
    cab = ["Métrica"] + [f"`{r['modo']}`" for r in resultados]
    L.append("| " + " | ".join(cab) + " |")
    L.append("|" + "---|" * len(cab))

    def linha(nome, fn):
        L.append("| " + " | ".join([nome] + [fn(r["metricas"], r) for r in resultados]) + " |")

    linha("Acerto exato (casos com informação)", lambda m, r: _pct(m.acuracia_exata))
    linha("A no máximo 1 nível", lambda m, r: _pct(m.dentro_de_1))
    linha("F1 médio (macro)", lambda m, r: f"{m.f1_macro:.2f}")
    linha("Erro ordinal médio (níveis)", lambda m, r: f"{m.erro_ordinal_medio:.2f}")
    linha("Recall dos graves (MUITO_URGENTE/EMERGENCIA)", lambda m, r: f"{_pct(m.recall_graves)} ({m.n_graves} casos)")
    linha("Graves subestimados em 2+ níveis", lambda m, r: str(len(m.graves_subestimados_2_ou_mais)))
    linha("Graves com abstenção (INSUFICIENTE)", lambda m, r: str(len(m.graves_com_abstencao)))
    linha("INSUFICIENTE indevida", lambda m, r: _pct(m.insuficiente_indevida))
    linha("Acerto nos casos INSUFICIENTE", lambda m, r: _pct(m.acerto_insuficiente))
    linha("Adversariais que seguem o relato", lambda m, r: _pct(m.adversariais_ok))
    linha("Taxa de revisão manual", lambda m, r: _pct(m.taxa_revisao_manual))
    linha("Resultados vindos de regras/fallback", lambda m, r: "—" if r["modo"] == "regras" else _pct(m.taxa_fallback))
    linha("Latência p50 / p95 (ms)", lambda m, r: f"{m.latencia_p50_ms:.0f} / {m.latencia_p95_ms:.0f}")
    linha("Tokens médios por chamada", lambda m, r: "—" if m.tokens_medios is None else f"{m.tokens_medios:.0f}")
    L.append("")

    L.append("## Metas (spec: CS-002, CS-003, CS-005, RQ-008)")
    L.append("")
    for r in resultados:
        L.append(f"**`{r['modo']}`**")
        L.append("")
        for cs, (ok, detalhe) in r["metas"].items():
            L.append(f"- {cs}: **{_mark(ok)}** — {detalhe}")
        L.append("")
    base = next((r for r in resultados if r["modo"] == "regras"), None)
    if base and len(resultados) > 1:
        L.append("**CS-001 (modelo supera as regras em F1 médio):**")
        for r in resultados:
            if r["modo"] == "regras":
                continue
            d = r["metricas"].f1_macro - base["metricas"].f1_macro
            L.append(f"- `{r['modo']}`: F1 {r['metricas'].f1_macro:.2f} contra {base['metricas'].f1_macro:.2f} das regras "
                     f"({d:+.2f}) → **{'ATENDE' if d > 0 else 'NÃO ATENDE'}**")
        L.append("")
    elif len(resultados) == 1:
        L.append("CS-001 e CS-006 (modelo × regras; latência de ponta a ponta no hardware do grupo) "
                 "**não puderam ser avaliados**: não há resultado de modelo nesta execução.")
        L.append("")

    for r in resultados:
        m: Metricas = r["metricas"]
        L.append(f"## Detalhe: `{r['modo']}`")
        L.append("")
        L.append("| Nível | Suporte | Precisão | Recall | F1 |")
        L.append("|---|---|---|---|---|")
        for nivel in ESCALA:
            d = m.por_nivel[nivel]
            L.append(f"| {nivel} | {int(d['suporte'])} | {d['precisao']:.2f} | {d['recall']:.2f} | {d['f1']:.2f} |")
        L.append("")
        L.append("Matriz de confusão (linhas = gabarito, colunas = previsto):")
        L.append("")
        cols = ESCALA + ("INSUFICIENTE",)
        L.append("| gabarito \\ previsto | " + " | ".join(c.replace("_", "_​") for c in cols) + " |")
        L.append("|" + "---|" * (len(cols) + 1))
        for g in ESCALA:
            L.append(f"| {g} | " + " | ".join(str(m.matriz[g][c]) for c in cols) + " |")
        L.append("")
        if m.erros:
            L.append("Casos errados:")
            L.append("")
            L.append("| Caso | Tipo | Gabarito | Previsto | Origem |")
            L.append("|---|---|---|---|---|")
            for e in m.erros:
                L.append(f"| {e['id']} | {e['tipo']} | {e['gabarito']} | {e['previsto']} | {e['origem']} |")
            L.append("")
        else:
            L.append("Nenhum caso errado.")
            L.append("")
        if r["estabilidade"]:
            L.append("Estabilidade dos casos críticos (níveis nas repetições):")
            L.append("")
            for cid, e in r["estabilidade"].items():
                L.append(f"- {cid}: {', '.join(e['niveis'])} → {'estável' if e['estavel'] else '**INSTÁVEL**'}")
            L.append("")

    L.append("## Limites desta avaliação")
    L.append("")
    L.append("- São poucos casos sintéticos: as métricas são **indicativas**, não validam produção (spec, critérios de sucesso).")
    L.append("- O gabarito é da rubrica v1 (proposta do grupo, Q-001) e ainda não foi validado com o cliente.")
    L.append("- A confiança informada pelo modelo é autodeclarada e pouco calibrada; por isso a revisão manual "
             "não depende só dela (ver ADR-001).")
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------------------- CLI

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--conjunto", choices=["dev", "final", "todos"], default="dev")
    ap.add_argument("--modos", default="auto", help="auto, ou lista: regras,llm,hibrido")
    ap.add_argument("--repeticoes", type=int, default=REPETICOES_PADRAO)
    ap.add_argument("--saida", type=Path)
    ap.add_argument("--json", type=Path, dest="saida_json")
    args = ap.parse_args(argv)

    base_cfg = Config.do_ambiente()
    casos = carregar_casos(args.conjunto)
    modos = ["regras", "llm", "hibrido"] if args.modos == "auto" else [m.strip() for m in args.modos.split(",")]

    pulados: dict[str, str] = {}
    resultados: list[dict] = []
    acessivel: tuple[bool, str] | None = None
    for modo in modos:
        if modo != "regras":
            if acessivel is None:
                acessivel = modelo_acessivel(base_cfg)
            if not acessivel[0]:
                pulados[modo] = (f"modelo inacessível em {base_cfg.llm_url} ({acessivel[1]}); "
                                 f"configure URGENCIA_LLM_URL/URGENCIA_LLM_MODELO (e URGENCIA_LLM_API_KEY, se preciso)")
                continue
        cfg = base_cfg.com(modo=modo, vazao_por_minuto=10_000)   # a avaliação não deve esbarrar no limitador
        resultados.append(executar_modo(modo, Classificador(cfg), casos, args.repeticoes))

    relatorio = gerar_relatorio(resultados, args.conjunto, len(casos), pulados, base_cfg)
    if args.saida:
        args.saida.write_text(relatorio, encoding="utf-8")
        print(f"relatório gravado em {args.saida}", file=sys.stderr)
    else:
        sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
        print(relatorio)
    if args.saida_json:
        bruto = [{"modo": r["modo"], "metricas": r["metricas"].__dict__, "estabilidade": r["estabilidade"],
                  "metas": {k: {"ok": v[0], "detalhe": v[1]} for k, v in r["metas"].items()},
                  "previsoes": [p.__dict__ for p in r["previsoes"]]} for r in resultados]
        args.saida_json.write_text(json.dumps(bruto, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
