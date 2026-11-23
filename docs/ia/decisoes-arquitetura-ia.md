# Decisões de arquitetura da IA do classificador de urgência

> Resumo para a banca/cliente. Os detalhes de cada decisão estão nos ADRs desta pasta; o fluxo está em
> [`diagramas/sequencia-classificador.png`](diagramas/sequencia-classificador.png) (fonte: `.mmd`).

## 1. O problema, em uma frase

Classificar o texto livre de um cidadão leigo em cinco níveis de urgência (+ `INSUFICIENTE`) para
**priorizar o atendimento de animais em risco**. O erro que mais custa é **subestimar** um caso grave;
superestimar custa só uma revisão a mais. Quase todas as decisões abaixo derivam dessa assimetria.

## 2. Visão geral

```
Spring Boot ──(após o commit, HTTP)──► Agente Python ──► Modelo (LLM)  ──┐
                                            │                            ├─► Árbitro por regras ─► Resultado
                                            └──────────► Regras (palavras-chave) ┘
```

Um agente separado (Python) recebe `{tipoAnimal, descrição, pontoRef}` e **sempre** devolve um resultado
válido: `{nível, justificativa, confiança, origem, versão, revisãoManual}`. O modelo é uma peça
**substituível e desconfiada** (e, no padrão, a primeira a decidir); a garantia de segurança vem do que está em volta dele.

## 3. Decisões e por quê

| # | Decisão | Motivo | Alternativa descartada |
|---|---|---|---|
| 1 | **Agente em processo separado, contrato fechado** (`POST /classificar`) | Decisão do cliente (Q-009); isola dependência de IA, permite trocar modelo sem mexer no Java, deploy independente | Chamar o LLM direto do Spring |
| 2 | **Três modos** `hibrido` (padrão) / `llm` / `regras` por variável de ambiente | O agente "pensante" é o padrão; sem internet ou modelo, as regras seguram (modo `regras` roda sem chave nem internet); o modelo entra sem alterar código; permite comparar os modos na avaliação (US5) | Um único modo fixo |
| 3 | **API compatível com OpenAI**, configurável por URL/nome/chave | Mesmo código para Ollama (local, grátis, dados não saem), Groq e Gemini; sem SDK de provedor | SDK de um provedor; modelo embutido |
| 4 | **Temperatura 0**, `max_tokens` limitado, semente opcional | Reduz variação entre repetições (RQ-008). Semente é opcional porque nem todo provedor aceita | Temperatura padrão |
| 5 | **Prompt versionado em arquivo** (`prompts/urgencia-v2.txt`): rubrica + 1 exemplo por nível + regra de desempate "na dúvida, o mais grave" + **roteiro de raciocínio** (fatos → ressalvas → gravidade → decisão) escrito no campo `analise` antes do nível | Rastreabilidade (RQ-006: modelo + versão do prompt gravados em cada resultado); a rubrica é a mesma da avaliação; o viés de segurança fica explícito | Prompt no código; sem exemplos |
| 6 | **Relato tratado como dado**: bloco delimitado por marcadores com **nonce aleatório**, caracteres de controle e falsos delimitadores removidos, truncamento **início + fim** (2000 caracteres) | Defesa de 1ª camada contra injeção (DTEC-008); a informação decisiva costuma estar no fim do texto (caso F13), por isso não se corta só o final | Concatenar o texto cru; cortar só o final |
| 7 | **Saída estruturada validada de forma rígida**: JSON; `nivel` em lista fechada; justificativa não vazia (≤ 300); confiança 0–1; **campos extras ignorados** | O modelo só controla três campos. Origem, versão, revisão manual e ids nunca vêm dele. Saída inválida = falha, nunca "adivinhar" | Aceitar texto livre e interpretar |
| 8 | **Regras determinísticas como reserva *e* como árbitro** (modo híbrido) | (a) Se o modelo cair, ainda há resposta; (b) **piso de segurança**: se as regras veem sinal grave e o modelo ficou ≥ 2 níveis abaixo (ou se absteve), sobe para o nível das regras; (c) se o relato contém instruções ao modelo e as regras são conclusivas e discordam, valem as regras. Regras são imunes a prompt injection | Confiar só no modelo; confiar na confiança autodeclarada |
| 9 | **Detector de injeção só sinaliza, não bloqueia** | Heurística é fácil de burlar; usada como gatilho do árbitro e para **forçar revisão humana**, não como barreira única | Bloquear o relato |
| 10 | **Abstenção explícita**: `INSUFICIENTE` não é grau de gravidade | O modelo pode dizer "não sei" em vez de chutar; vai sempre para revisão manual e fica fora da matriz de confusão | Forçar um dos cinco níveis |
| 11 | **Plano B por inconclusão** (híbrido): modelo `INSUFICIENTE` ou confiança < 0,6 e regras com sinal → vale o nível mais grave entre os dois (se o modelo for mais grave, mantém o modelo); sem sinal nas regras, mantém o modelo. **Revisão manual obrigatória** em: abstenção, confiança < 0,6, árbitro atuou, injeção suspeita, plano B, fallback | A confiança do LLM é autodeclarada e mal calibrada, então não é o único gatilho | Revisão só por confiança |
| 12 | **Resiliência**: timeout 20 s, até 2 tentativas (só para erros repetíveis: timeout, 429, 5xx, saída inválida; 401/403/404 não repetem), limite de 30 chamadas/min, **uma chamada ao modelo por vez** | Proteger hardware local e custo (RF-012, RQ-005); evitar tempestade de retentativas | Retry ilimitado; concorrência livre |
| 13 | **Prazo máximo de 180 s no lado Java** (scheduler a cada 30 s) | Denúncia nunca fica "em classificação" para sempre: vira `INSUFICIENTE` + revisão manual (ou mantém o nível anterior numa reclassificação) | Confiar que o agente sempre responde |
| 14 | **`solicitacaoId` por solicitação; resultado só é aplicado se for o mais recente e o status for `PENDENTE`** | Evita que uma classificação atrasada sobrescreva uma edição ou **correção humana** (DTEC-005) | Último resultado a chegar vence |
| 15 | **Publicação após o commit** (`AFTER_COMMIT`), chamada em thread própria | Não chama o agente por uma denúncia que pode sofrer rollback; não segura a requisição do cidadão | Chamada síncrona dentro da transação |
| 16 | **Minimização de dados**: só tipo de animal, descrição e ponto de referência vão ao modelo (sem contato, usuário, foto, endereço); **logs sem texto do relato**; chave nunca em `repr`/log | LGPD / RQ-004 / RQ-009; modo `llm` em nuvem é avisado em log | Enviar a denúncia inteira |
| 17 | **A correção humana sempre vence** (RF-017) e gera **log de auditoria** com valor antes/depois; autorização no backend | A IA é apoio à triagem, não decisor final | Reclassificar por cima da organização |
| 18 | **Avaliação reproduzível** (`avaliacao/`): 25 casos sintéticos (11 dev + 14 reservados), matriz de confusão, recall dos graves, erros ≥ 2 níveis, adversariais, estabilidade, latência | Escolher modelo/prompt por evidência, não impressão. Relatório não inventa número: sem modelo, diz "não executado" | Teste manual de alguns exemplos |

## 3.1 Por que "híbrido" e não "só LLM" ou "só regras"?

- **Só regras**: determinísticas, instantâneas, sem custo, mas rígidas. Na avaliação (conjunto reservado),
  acertaram 85% exatos, mas erraram por paráfrase e negação: F05 ("jogado de um carro… deitado **sem se mexer**", grave) e
  F10 ("**não está ferido nem sangrando**, só quer carinho") caíram em `INSUFICIENTE`. São o tipo de relato que um modelo
  tende a entender — hipótese a confirmar na avaliação com modelo.
- **Só LLM**: entende contexto, mas varia, pode ser manipulado pelo texto do relato e subestimar casos
  graves sem aviso.
- **Híbrido**: o modelo dá a nuance; as regras dão **piso de segurança** e **reserva**. O custo é
  uma revisão manual a mais em alguns casos — aceito conscientemente.

## 4. O que a segurança NÃO depende de

A proteção não depende de o modelo "obedecer ao prompt". Se ele for enganado por uma injeção, ou subestimar
um caso grave, as camadas seguintes (validação do esquema → árbitro por regras → revisão humana →
correção da organização) atuam de forma independente. É a defesa em profundidade da decisão 8.

## 5. Estado atual e limites (leia antes de apresentar)

- **Python**: 163 testes automatizados passam (cliente com transporte falso; não exigem modelo real).
- **Modelo real: não foi executado nesta entrega.** As métricas de LLM/híbrido (CS-001, CS-006, T063) estão
  **pendentes**; só existem números reais das **regras** (`avaliacao-regras-*.md`). Rode
  `python -m avaliacao.executar` com o Ollama/Groq do grupo e anexe o relatório.
- **Java**: código e testes novos foram escritos, passaram só por checagem de sintaxe; **não foram compilados
  nem testados** nesta entrega (sem acesso ao Maven Central no ambiente). Rodar `./mvnw test` no IntelliJ.
- As regras **não atendem** CS-002 (recall dos graves 88% < 90%) e CS-003 no conjunto reservado: um caso
  grave termina em `INSUFICIENTE` (vai para revisão humana, mas sem nível). É o ganho esperado do modo híbrido.
- Gabarito (rubrica v1) é proposta do grupo, ainda não validada com o cliente; conjunto pequeno e sintético:
  métricas **indicativas**, não validação de produção.
- Limitações conhecidas: o `assumir` tem corrida "último a gravar vence"; qualquer usuário autenticado pode
  editar qualquer denúncia (fora do escopo desta feature); RabbitMQ não foi reinstaurado (HTTP, DTEC-010).
