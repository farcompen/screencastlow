@echo off
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle bulunamadi. Lutfen Gradle kurun veya GitHub Actions kullanin.
exit /b 1
