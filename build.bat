@echo off
setlocal
where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install JDK 25 first.
  exit /b 1
)
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle was not found.
  echo Install Gradle, then run this file again, or use the included GitHub Actions workflow.
  exit /b 1
)
java -version
call gradle clean build
if errorlevel 1 exit /b 1
echo.
echo Build complete: build\libs\WayfinderCompass-1.0.0.jar
endlocal
