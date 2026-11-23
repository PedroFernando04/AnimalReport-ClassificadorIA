# ADR-001 — Modelo de IA e parâmetros

**Status:** aceito (provisório até a avaliação T063). **Relaciona:** DTEC-001, DTEC-009, DTEC-011, DTEC-012, RQ-006.

## Contexto
O sistema classifica relatos de cidadãos. É preciso custo baixo, dados pessoais protegidos e poder trocar de
modelo sem mexer no código.

## Decisão
- O agente fala com **qualquer API compatível com OpenAI** (`POST {URL}/chat/completions`), configurada por
  `URGENCIA_LLM_URL`, `URGENCIA_LLM_MODELO`, `URGENCIA_LLM_API_KEY`.
- **Padrão: Ollama local**, modelo `qwen2.5:7b` (pesos abertos, bom em português, roda em máquina comum).
  Groq e Gemini (camadas gratuitas) são alternativas já suportadas por URL.
- Parâmetros: `temperature=0`, `max_tokens=800` (a análise escrita consome tokens; aumentar mais para modelos de raciocínio, junto com
  `URGENCIA_LLM_REASONING_EFFORT`), `response_format=json_object` (desligável por `URGENCIA_LLM_JSON_MODE`),
  semente opcional, timeout 20 s, 2 tentativas.
- Cada resultado grava `versaoClassificador = "<modelo>/<versão do prompt>[+arbitro-regras-v1]"`.

## Consequências
- Troca de modelo = mudar variável de ambiente, e reavaliar (`python -m avaliacao.executar`).
- Provedor em nuvem envia o texto do relato para fora: o agente registra um aviso em log e isso só deve ser
  usado com **dados fictícios** (RQ-009).
- `qwen2.5:7b` é um ponto de partida **não validado** por medição; a escolha final depende de T063
  (≥ 2 candidatos medidos no hardware do grupo).
