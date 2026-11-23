# Relatório de avaliação do classificador de urgência

- Gerado em: 18/11/2026 16:24
- Conjunto: `dev` (11 casos). Rubrica v1, prompt `prompt-v1`, regras `regras-v1`.

## Resumo por modo

| Métrica | `regras` |
|---|---|
| Acerto exato (casos com informação) | 70% |
| A no máximo 1 nível | 80% |
| F1 médio (macro) | 0.66 |
| Erro ordinal médio (níveis) | 0.12 |
| Recall dos graves (MUITO_URGENTE/EMERGENCIA) | 83% (6 casos) |
| Graves subestimados em 2+ níveis | 0 |
| Graves com abstenção (INSUFICIENTE) | 1 |
| INSUFICIENTE indevida | 20% |
| Acerto nos casos INSUFICIENTE | 100% |
| Adversariais que seguem o relato | 100% |
| Taxa de revisão manual | 27% |
| Resultados vindos de regras/fallback | — |
| Latência p50 / p95 (ms) | 0 / 1 |
| Tokens médios por chamada | — |

## Metas (spec: CS-002, CS-003, CS-005, RQ-008)

**`regras`**

- CS-002: **NÃO ATENDE** — recall dos graves 83% (meta ≥ 90%); subestimados em 2+ níveis: 0 (meta 0); abstenções em graves: 1
- CS-003: **NÃO ATENDE** — exato 70% (meta ≥ 70%); a ≤ 1 nível 80% (meta ≥ 90%)
- CS-005: **ATENDE** — adversariais seguindo o relato: 100% (meta 100%)

CS-001 e CS-006 (modelo × regras; latência de ponta a ponta no hardware do grupo) **não puderam ser avaliados**: não há resultado de modelo nesta execução.

## Detalhe: `regras`

| Nível | Suporte | Precisão | Recall | F1 |
|---|---|---|---|---|
| NAO_URGENTE | 1 | 0.00 | 0.00 | 0.00 |
| POUCO_URGENTE | 2 | 0.50 | 0.50 | 0.50 |
| URGENTE | 1 | 1.00 | 1.00 | 1.00 |
| MUITO_URGENTE | 3 | 1.00 | 1.00 | 1.00 |
| EMERGENCIA | 3 | 1.00 | 0.67 | 0.80 |

Matriz de confusão (linhas = gabarito, colunas = previsto):

| gabarito \ previsto | NAO_​URGENTE | POUCO_​URGENTE | URGENTE | MUITO_​URGENTE | EMERGENCIA | INSUFICIENTE |
|---|---|---|---|---|---|---|
| NAO_URGENTE | 0 | 1 | 0 | 0 | 0 | 0 |
| POUCO_URGENTE | 0 | 1 | 0 | 0 | 0 | 1 |
| URGENTE | 0 | 0 | 1 | 0 | 0 | 0 |
| MUITO_URGENTE | 0 | 0 | 0 | 3 | 0 | 0 |
| EMERGENCIA | 0 | 0 | 0 | 0 | 2 | 1 |

Casos errados:

| Caso | Tipo | Gabarito | Previsto | Origem |
|---|---|---|---|---|
| D05 | normal | NAO_URGENTE | POUCO_URGENTE | REGRAS |
| D06 | normal | POUCO_URGENTE | INSUFICIENTE | REGRAS |
| D07 | normal | EMERGENCIA | INSUFICIENTE | REGRAS |

## Limites desta avaliação

- São poucos casos sintéticos: as métricas são **indicativas**, não validam produção (spec, critérios de sucesso).
- O gabarito é da rubrica v1 (proposta do grupo, Q-001) e ainda não foi validado com o cliente.
- A confiança informada pelo modelo é autodeclarada e pouco calibrada; por isso a revisão manual não depende só dela (ver ADR-001).
