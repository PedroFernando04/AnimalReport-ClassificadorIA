# Relatório de avaliação do classificador de urgência

- Gerado em: 18/11/2026 16:24
- Conjunto: `final` (14 casos). Rubrica v1, prompt `prompt-v1`, regras `regras-v1`.
- ⚠ Conjunto reservado: não ajuste prompt nem regras com base nestes resultados.

## Resumo por modo

| Métrica | `regras` |
|---|---|
| Acerto exato (casos com informação) | 85% |
| A no máximo 1 nível | 85% |
| F1 médio (macro) | 0.94 |
| Erro ordinal médio (níveis) | 0.00 |
| Recall dos graves (MUITO_URGENTE/EMERGENCIA) | 88% (8 casos) |
| Graves subestimados em 2+ níveis | 0 |
| Graves com abstenção (INSUFICIENTE) | 1 |
| INSUFICIENTE indevida | 15% |
| Acerto nos casos INSUFICIENTE | 100% |
| Adversariais que seguem o relato | 100% |
| Taxa de revisão manual | 21% |
| Resultados vindos de regras/fallback | — |
| Latência p50 / p95 (ms) | 0 / 1 |
| Tokens médios por chamada | — |

## Metas (spec: CS-002, CS-003, CS-005, RQ-008)

**`regras`**

- CS-002: **NÃO ATENDE** — recall dos graves 88% (meta ≥ 90%); subestimados em 2+ níveis: 0 (meta 0); abstenções em graves: 1
- CS-003: **NÃO ATENDE** — exato 85% (meta ≥ 70%); a ≤ 1 nível 85% (meta ≥ 90%)
- CS-005: **ATENDE** — adversariais seguindo o relato: 100% (meta 100%)

CS-001 e CS-006 (modelo × regras; latência de ponta a ponta no hardware do grupo) **não puderam ser avaliados**: não há resultado de modelo nesta execução.

## Detalhe: `regras`

| Nível | Suporte | Precisão | Recall | F1 |
|---|---|---|---|---|
| NAO_URGENTE | 3 | 1.00 | 0.67 | 0.80 |
| POUCO_URGENTE | 1 | 1.00 | 1.00 | 1.00 |
| URGENTE | 1 | 1.00 | 1.00 | 1.00 |
| MUITO_URGENTE | 2 | 1.00 | 1.00 | 1.00 |
| EMERGENCIA | 6 | 1.00 | 0.83 | 0.91 |

Matriz de confusão (linhas = gabarito, colunas = previsto):

| gabarito \ previsto | NAO_​URGENTE | POUCO_​URGENTE | URGENTE | MUITO_​URGENTE | EMERGENCIA | INSUFICIENTE |
|---|---|---|---|---|---|---|
| NAO_URGENTE | 2 | 0 | 0 | 0 | 0 | 1 |
| POUCO_URGENTE | 0 | 1 | 0 | 0 | 0 | 0 |
| URGENTE | 0 | 0 | 1 | 0 | 0 | 0 |
| MUITO_URGENTE | 0 | 0 | 0 | 2 | 0 | 0 |
| EMERGENCIA | 0 | 0 | 0 | 0 | 5 | 1 |

Casos errados:

| Caso | Tipo | Gabarito | Previsto | Origem |
|---|---|---|---|---|
| F05 | normal | EMERGENCIA | INSUFICIENTE | REGRAS |
| F10 | falha | NAO_URGENTE | INSUFICIENTE | REGRAS |

## Limites desta avaliação

- São poucos casos sintéticos: as métricas são **indicativas**, não validam produção (spec, critérios de sucesso).
- O gabarito é da rubrica v1 (proposta do grupo, Q-001) e ainda não foi validado com o cliente.
- A confiança informada pelo modelo é autodeclarada e pouco calibrada; por isso a revisão manual não depende só dela (ver ADR-001).
