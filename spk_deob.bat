@echo off
REM SpawnPK deob run: rs.* is own code, short junk names auto-renamed,
REM long genuine names (Client, gui, cache) kept, spk_map.json pins known names.
REM Targets the SpawnPK Temurin 11 JRE (has java.applet); big Client class needs the long timeout.
"C:\Users\naxos\.jdks\corretto-26.0.1\bin\java.exe" -jar "%~dp0target\clean-decompile.jar" --jar "%~dp0client.jar" --output "%~dp0out" --own-package rs --custom-names "%~dp0spk_map.json" --decompile-timeout-ms 120000 --release-level 11 --main-class rs.Client --jre-home "C:\Users\naxos\AppData\Local\SpawnPK\jre" --lombok-jar "%~dp0lib\lombok-1.18.32.jar" --extra-sources "%~dp0stubs"
