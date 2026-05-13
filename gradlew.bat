@rem Minimal Gradle wrapper bootstrap.
@echo off
set DIR=%~dp0
set JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
if not exist "%JAR%" (
  echo gradle-wrapper.jar not found. Run "gradle wrapper --gradle-version 8.7" once to generate it. 1>&2
  exit /b 1
)
java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
