$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force -Path out | Out-Null
javac --release 11 -d out LocalRaceServer.java
if ($LASTEXITCODE -ne 0) { throw 'Server compile failed' }
java -cp out LocalRaceServer
