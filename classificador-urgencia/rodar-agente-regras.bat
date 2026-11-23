@echo off
REM Agente de urgencia SO com regras (palavras-chave). Sem modelo, sem chave, sem internet apos instalar.
cd /d "%~dp0"
python -m pip install -r requirements.txt
set URGENCIA_MODO=regras
python -m app.http_server
pause
