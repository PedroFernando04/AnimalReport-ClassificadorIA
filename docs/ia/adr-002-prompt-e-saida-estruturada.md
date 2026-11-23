# ADR-002 — Prompt versionado e saída estruturada

**Status:** aceito. **Relaciona:** RF-009, DTEC-008, CA-006, RQ-006.

## Decisão
1. O prompt de sistema vive em `classificador-urgencia/prompts/urgencia-v2.txt` e é identificado por
   `VERSAO_PROMPT = "prompt-v2"` (`app/prompt.py`). Qualquer mudança no texto **exige nova versão** e nova avaliação.
2. Conteúdo: papel e regras de segurança → níveis com sinais e **um exemplo por nível** → regras de
   decisão (na dúvida o mais grave; negações; gírias; informação decisiva pode estar no fim) → formato de saída.
3. O relato entra numa mensagem de usuário, entre marcadores com **nonce aleatório** por chamada
   (`secrets`), após remoção de caracteres de controle, sequências `<<<`/`===`/``` e falsos marcadores.
   Acima de 2000 caracteres mantém **início + fim**.
4. Saída esperada: `{"analise","nivel","justificativa","confianca"}`. O campo `analise` (raciocínio curto em 4 passos: fatos, ressalvas, gravidade, decisão) vem **primeiro** para o modelo pensar antes de decidir; é **descartado** pelo agente (não é gravado nem logado, pois pode citar o relato). A v1 (sem `analise`) foi substituída; nenhuma métrica de modelo havia sido medida com ela. O validador (`llm_client.interpretar_resposta`):
   - aceita o JSON puro ou cercado de texto/bloco de código, desde que haja **um** objeto bem formado;
   - normaliza `nivel` (acento, caixa, hífen) e exige que esteja na lista fechada;
   - aceita confiança em 0–1 (ou 0–100, convertida); rejeita booleanos e fora de faixa;
   - trunca a justificativa em 300 caracteres; **ignora campos extras**.
   Qualquer violação é `ErroModelo` (repetível) → nova tentativa → regras.

## Alternativas
- *Function calling / schema do provedor*: nem todo provedor compatível suporta; a validação própria é
  portável e é a barreira real de qualquer forma.
- *Texto livre*: exigiria interpretar, abrindo espaço a erro e manipulação.

## Consequências
Contrato mínimo e verificável (testes em `tests/test_prompt.py` e `tests/test_llm_client.py`).
