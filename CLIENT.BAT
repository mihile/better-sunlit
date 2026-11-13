@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if exist "tools\better-sunlit\tools\release.py" cd /d "tools\better-sunlit"
python -X utf8 -u "tools\release.py" --side client --local-only %*
set "release_result=%ERRORLEVEL%"
echo.
if not "%release_result%"=="0" echo [ERROR] See the message above.
pause
exit /b %release_result%
