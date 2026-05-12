@echo off
setlocal
set MAVEN_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
where mvn >nul 2>nul
if %ERRORLEVEL%==0 (
    mvn clean package -DskipTests -Dmaven.test.skip=true
) else if exist "%USERPROFILE%\maven\apache-maven-3.9.6\bin\mvn.cmd" (
    "%USERPROFILE%\maven\apache-maven-3.9.6\bin\mvn.cmd" clean package -DskipTests -Dmaven.test.skip=true
) else (
    echo Maven was not found. Install Maven or update build.bat with your Maven path.
    exit /b 1
)
echo EXIT_CODE=%ERRORLEVEL%
exit /b %ERRORLEVEL%
