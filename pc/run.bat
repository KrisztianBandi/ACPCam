@echo off
cd /d "%~dp0"
pip install -r requirements.txt
python phonecam_server.py
pause
