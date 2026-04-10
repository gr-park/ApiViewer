@echo off
chcp 65001 >nul 2>&1
title API Viewer
cls

:: ApiViewer 실행 스크립트 (Windows, Portable Tools)
:: 기본 실행 :  run.bat            (JAR만 실행)
:: 빌드 실행 :  run.bat --build    (빌드 후 실행)

cd /d "%~dp0"

:: ─── [경로 설정] tools 폴더의 portable JDK/Git 우선 사용 ───
set TOOL_DIR=%~dp0tools
if exist "%TOOL_DIR%\jdk\bin\java.exe" (
    set JAVA_BIN="%TOOL_DIR%\jdk\bin\java.exe"
    set PATH=%TOOL_DIR%\jdk\bin;%TOOL_DIR%\git\bin;%PATH%
    echo [TOOL] Portable JDK: %TOOL_DIR%\jdk\bin\java.exe
) else (
    set JAVA_BIN=java
    echo [TOOL] System Java (PATH)
)

:: ─── [JVM 메모리 옵션] ───
set JAVA_OPTS=-Xms512m -Xmx2g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8

set JAR=target\api-viewer-1.0.0.jar

:: 기본은 빌드 없이 실행 (운영 환경 — 빌드 환경 미지원)
:: 빌드까지 같이 하려면: run.bat --build
if not "%1"=="--build" goto skip_build

echo [INFO] 빌드 중...
if exist mvnw.cmd (
    call mvnw.cmd -q package -DskipTests
) else (
    call mvn -q package -DskipTests
)
if errorlevel 1 (
    echo [ERROR] 빌드 실패. 로그를 확인하세요.
    pause
    exit /b 1
)

:skip_build
if not exist "%JAR%" (
    echo [ERROR] JAR 파일이 없습니다: %JAR%
    echo         run.bat         : JAR만 실행 (기본)
    echo         run.bat --build : 빌드 후 실행
    pause
    exit /b 1
)

echo.
echo ================================================
echo   API Viewer 시작
echo ------------------------------------------------
echo   [JVM]   Heap: 512m ~ 2g (G1GC)
echo ------------------------------------------------
echo   [대시보드]      http://localhost/
echo   [URL분석현황]   http://localhost/viewer.html
echo   [URL호출현황]   http://localhost/call-stats.html
echo   [현업검토]     http://localhost/review.html
echo   [설정]         http://localhost/settings.html
echo   [URL분석추출]  http://localhost/extract.html
echo ------------------------------------------------
echo   [H2 콘솔]      http://localhost/h2-console
echo     JDBC URL    : jdbc:h2:file:./data/api-viewer-db
echo     User / Pass : sa / (없음)
echo ================================================
echo   종료: Ctrl+C
echo ================================================
echo.

%JAVA_BIN% %JAVA_OPTS% -jar "%JAR%"