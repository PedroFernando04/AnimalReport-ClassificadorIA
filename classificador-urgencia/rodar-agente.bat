@echo off
REM Agente de urgencia (padrao): modelo "pensante" + regras de palavras-chave como plano B.
REM Se o modelo nao estiver acessivel (sem internet, Ollama desligado) ou nao concluir, as regras assumem.
REM Opcao local gratuita: instale o Ollama (https://ollama.com), rode "ollama pull qwen2.5:7b" e deixe-o aberto.
REM Opcao nuvem (Groq/Gemini): ajuste URGENCIA_LLM_URL / MODELO / API_KEY abaixo (nao versione a chave).
cd /d "%~dp0"
python -m pip install -r requirements.txt
set URGENCIA_MODO=hibrido
set URGENCIA_LLM_URL=https://api.groq.com/openai/v1
set URGENCIA_LLM_MODELO=openai/gpt-oss-20b
set URGENCIA_LLM_API_KEY=%GROQ_API_KEY%
set URGENCIA_LLM_MAX_TOKENS=1500
set URGENCIA_LLM_REASONING_EFFORT=low
python -m app.http_server
pause
