# AnimalReport — classificação de urgência por IA

A urgência de cada denúncia **não é mais escolhida pelo usuário**: um agente Python classifica o relato em segundo plano.
Por padrão um **modelo de linguagem "pensante"** analisa o relato (fatos, negações, gravidade) e decide o nível. Se ele estiver inacessível (sem internet, Ollama desligado, erro) ou inconclusivo (não decidiu ou confiança baixa), as **regras de palavras-chave** assumem como plano B. O modo só com regras continua disponível.

## Rodar
1. **PostgreSQL** local com o banco `animalreportdb`. Usuário e senha: variáveis `DB_USER` / `DB_PASSWORD`
   (padrão em `src/main/resources/application.properties`: `postgres` / `postgres`).
2. **Agente de urgência** (Python 3.12+), porta 8000 — deixe a janela aberta:
   - `classificador-urgencia/rodar-agente.bat` → **modelo + regras** (padrão; requer Ollama ou chave, veja abaixo; sem modelo acessível cai nas regras);
   - `classificador-urgencia/rodar-agente-regras.bat` → só **regras**, sem modelo.
3. **Aplicação**: abra no IntelliJ e rode `AminalReportApplication` (ou `./mvnw spring-boot:run`) → http://localhost:8080.

Sem o agente, a denúncia é salva como `INSUFICIENTE` com revisão manual (no máximo 180 s depois).

## Modos do agente (`URGENCIA_MODO`)
| Modo | O que faz |
|---|---|
| `hibrido` (padrão) | modelo pensante; **regras como plano B** (modelo inacessível, `INSUFICIENTE` ou confiança baixa → regras, com revisão manual) e como **piso de segurança** contra subestimar casos graves e contra injeção de instruções |
| `llm` | só o modelo; se falhar → `INSUFICIENTE` + revisão manual |
| `regras` | só palavras-chave; sem modelo |

Variáveis (veja `classificador-urgencia/.env.example`): `URGENCIA_LLM_URL`, `URGENCIA_LLM_MODELO`, `URGENCIA_LLM_API_KEY`,
`URGENCIA_LLM_TIMEOUT_S`, `URGENCIA_LLM_TENTATIVAS`, `URGENCIA_LLM_MAX_TOKENS`, `URGENCIA_LLM_REASONING_EFFORT`,
`URGENCIA_VAZAO_POR_MINUTO`, `URGENCIA_LIMIAR_CONFIANCA`, `AGENTE_TOKEN` (no Spring: `URGENCIA_AGENTE_TOKEN`, `URGENCIA_AGENTE_URL`).
Modelo local gratuito: instale o [Ollama](https://ollama.com) e rode `ollama pull qwen2.5:7b`.
> Com provedor em nuvem (Groq/Gemini) o texto do relato sai da máquina: use **só dados fictícios**. Nunca versione a chave.

## Testes e avaliação
- Java: `./mvnw test` (o teste de contexto exige o PostgreSQL ligado).
- Python: `cd classificador-urgencia && python -m pytest`
- Avaliação (matriz de confusão, recall dos graves, adversariais): `cd classificador-urgencia && python -m avaliacao.executar`
- O que ainda precisa ser executado pelo grupo: [`docs/ia/checklist-verificacao.md`](docs/ia/checklist-verificacao.md).

## Documentação
- [`docs/ia/decisoes-arquitetura-ia.md`](docs/ia/decisoes-arquitetura-ia.md) — decisões de arquitetura da IA.
- [`docs/ia/diagramas/sequencia-classificador.png`](docs/ia/diagramas/sequencia-classificador.png) — diagrama de sequência (fonte `.mmd`, vetor `.svg`).
- ADRs `docs/ia/adr-001` a `adr-004`; relatórios `docs/ia/avaliacao-regras-*.md`.
- `specs/001-classificacao-urgencia/` — especificação, plano e tarefas.

## Estrutura
- `src/` — aplicação Spring Boot (Thymeleaf + JPA).
- `classificador-urgencia/` — agente (regras, cliente LLM, árbitro, servidor HTTP) e `avaliacao/`.
