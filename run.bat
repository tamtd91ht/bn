@echo off
REM Chay bot local (Windows) voi .env o cung thu muc.
setlocal enabledelayedexpansion

cd /d "%~dp0"

if not exist .env (
    echo ERROR: khong tim thay .env
    echo Copy .env.example -^> .env roi dien API key.
    exit /b 1
)
if not exist target\bot.jar (
    echo ERROR: target\bot.jar chua ton tai.
    echo Chay: mvn package -DskipTests
    exit /b 1
)

REM Load .env (bo qua dong comment + dong rong)
for /f "usebackq tokens=* delims=" %%a in (".env") do (
    set "line=%%a"
    if not "!line!"=="" if not "!line:~0,1!"=="#" (
        for /f "tokens=1,* delims==" %%b in ("!line!") do (
            set "%%b=%%c"
        )
    )
)

if "%1"=="" (
    set COMMAND=run
) else (
    set COMMAND=%1
    shift
)

java -jar target\bot.jar %COMMAND% %*
