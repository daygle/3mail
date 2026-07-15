@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d C:\Users\glen\StudioProjects\3mail
"%~dp0gradlew.bat" %*
