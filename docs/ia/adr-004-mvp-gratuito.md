# ADR-004 — Desvios do MVP gratuito (Q-008 e Q-009)

**Status:** aceito. **Relaciona:** DTEC-010, DTEC-013, Q-008, Q-009, T076.

| Previsto | Feito | Motivo / consequência |
|---|---|---|
| RabbitMQ com filas e DLQ | **HTTP** `POST /classificar`, disparado após o commit em thread própria | Zero infraestrutura extra no MVP. Sem DLQ: a falha vira `registrarFalhaClassificacao` + prazo de 180 s. Reinstaurar a fila exigiria reintroduzir publisher/listener (T002, T012, T027, T029) |
| Modelo local obrigatório (Q-008) | Modo padrão `regras`; modelo opcional (Ollama local, ou Groq/Gemini por chave) | O MVP funciona sem internet. Provedor em nuvem quebra a premissa de dados locais: aviso em log e uso só com dados fictícios |
| `docker-compose` completo (T005) | Execução local por `.bat`/IntelliJ; `Dockerfile` do agente | Compose com PostgreSQL + agente + Ollama fica como evolução |
| Hospedagem (DTEC-013) | **Não incluída neste pacote** (`render.yaml` e `Dockerfile` do app Java citados na tarefa T073 não estavam no zip recebido) | Reavaliar se for hospedar |
