@echo off
REM =============================================================================
REM build.bat — Reproducible build script for KV-Store (Windows)
REM
REM Produces byte-for-byte identical .class files on every invocation by:
REM   1. Sorting all source files alphabetically before passing them to javac
REM      (eliminates file-system ordering non-determinism).
REM   2. Stripping debug metadata with -g:none
REM      (removes line-number tables that embed source timestamps on some JDKs).
REM   3. Pinning the charset with -encoding UTF-8
REM      (avoids locale-dependent source interpretation).
REM   4. Using -implicit:none so javac never silently compiles extra source files
REM      that are referenced but not listed (deterministic input set).
REM
REM Usage:
REM   build.bat              compile src + tests
REM   build.bat --clean      delete out\ first, then compile
REM   build.bat --tests-only compile tests only (assumes out\StorageEngine.class exists)
REM
REM Verification (reproducibility):
REM   build.bat
REM   xcopy /e /i /q out out1
REM   build.bat --clean
REM   fc /b out\*.class out1\*.class  &  echo REPRODUCIBLE
REM =============================================================================

setlocal EnableDelayedExpansion

set "JAVAC=javac"
set "OUT_DIR=out"
set "SRC_DIR=src"
set "TEST_DIR=tests"
set "CLEAN=0"
set "TESTS_ONLY=0"
set "SRC_LIST=%TEMP%\kv_src_list.txt"
set "TEST_LIST=%TEMP%\kv_test_list.txt"

REM ── Parse arguments ──────────────────────────────────────────────────────────
:parse_args
if "%~1"=="" goto :done_args
if /I "%~1"=="--clean"      set "CLEAN=1"      & shift & goto :parse_args
if /I "%~1"=="--tests-only" set "TESTS_ONLY=1" & shift & goto :parse_args
echo [build] Unknown argument: %~1 >&2
exit /b 1
:done_args

REM ── Clean ────────────────────────────────────────────────────────────────────
if "%CLEAN%"=="1" (
    echo [build] Cleaning %OUT_DIR%\ ...
    if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
)

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

REM ── Compile sources ───────────────────────────────────────────────────────────
if "%TESTS_ONLY%"=="0" (
    echo [build] Collecting source files ...

    REM Collect src\*.java sorted alphabetically into a temp file.
    REM dir /b /s /on gives names sorted alphabetically (on = order by Name).
    if exist "%SRC_LIST%" del "%SRC_LIST%"
    for /f "delims=" %%F in ('dir /b /s /on "%SRC_DIR%\*.java" 2^>nul') do (
        echo %%F>> "%SRC_LIST%"
    )

    echo [build] Compiling sources ...
    %JAVAC% -g:none -implicit:none -encoding UTF-8 -d "%OUT_DIR%" @"%SRC_LIST%"
    if errorlevel 1 (
        echo [build] FAILED: source compilation error. >&2
        exit /b 1
    )
    echo [build] Sources compiled successfully.
)

REM ── Compile tests ─────────────────────────────────────────────────────────────
echo [build] Collecting test files ...
if exist "%TEST_LIST%" del "%TEST_LIST%"
for /f "delims=" %%F in ('dir /b /s /on "%TEST_DIR%\*.java" 2^>nul') do (
    echo %%F>> "%TEST_LIST%"
)

echo [build] Compiling tests ...
%JAVAC% -g:none -implicit:none -encoding UTF-8 -cp "%OUT_DIR%" -d "%OUT_DIR%" @"%TEST_LIST%"
if errorlevel 1 (
    echo [build] FAILED: test compilation error. >&2
    exit /b 1
)
echo [build] Tests compiled successfully.

REM ── Summary ───────────────────────────────────────────────────────────────────
echo.
echo [build] Build complete ^-^> %OUT_DIR%\
echo.
echo   Run CLI       :  java -cp %OUT_DIR% Main
echo   Run tests     :  java -cp %OUT_DIR% StorageEngineTest
echo   Run server    :  java -cp %OUT_DIR% Main --server [--port 8080]
echo   Run web server:  java -cp %OUT_DIR% Main --web [--web-port 8081]
echo   Run both      :  java -cp %OUT_DIR% Main --server --web
echo.

endlocal
exit /b 0
