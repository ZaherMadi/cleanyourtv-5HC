# Compile le launcher CleanYourTV (dossier launcher/ du depot) -> tvhome.apk, a cote de ce script
# Il installe si besoin le SDK Android (command-line tools officielles Google), puis lance Gradle.
# Tu devras lire et accepter les licences Google (taper y) : c'est toi qui acceptes, pas moi.
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$Work = Split-Path -Parent $MyInvocation.MyCommand.Path
$Src  = [System.IO.Path]::GetFullPath((Join-Path $Work '..\..\launcher'))
function Step([string]$m){ Write-Host ('>> ' + $m) -ForegroundColor Cyan }
function Fail([string]$m){ Write-Host ('ERREUR : ' + $m) -ForegroundColor Red; exit 1 }

if(-not (Test-Path (Join-Path $Src 'gradlew.bat'))){ Fail "dossier launcher introuvable : $Src" }

# ---------- 1. JDK (17 a 22, complet avec jlink) ----------
Step 'Recherche du JDK'
$jdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -match '-(1[789]|2[012])(\.|$)' -and (Test-Path (Join-Path $_.FullName 'bin\jlink.exe')) } |
  Select-Object -First 1
if(-not $jdk){ Fail "Aucun JDK 17 a 22 complet dans $env:USERPROFILE\.jdks" }
$env:JAVA_HOME = $jdk.FullName
Write-Host ("JDK : " + $env:JAVA_HOME) -ForegroundColor Green

# ---------- 2. SDK Android ----------
$sdk = Join-Path $env:USERPROFILE 'Android\Sdk'
$sm  = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if(-not (Test-Path $sm)){
  Step 'Telechargement des command-line tools Android (dl.google.com, officiel)'
  New-Item -ItemType Directory -Force -Path $sdk | Out-Null
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $zip = Join-Path $Work 'commandlinetools-win.zip'
  $ok = $false
  try { Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip -ErrorAction Stop; $ok = $true } catch { Write-Host 'URL fixe indisponible, recherche de la derniere version...' -ForegroundColor Yellow }
  if(-not $ok){
    $xml = (Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/repository2-3.xml').Content
    $n = [regex]::Matches($xml,'commandlinetools-win-(\d+)_latest\.zip') | ForEach-Object { [int]$_.Groups[1].Value } | Sort-Object -Descending | Select-Object -First 1
    if(-not $n){ Fail 'Impossible de trouver les command-line tools' }
    Invoke-WebRequest -UseBasicParsing -Uri "https://dl.google.com/android/repository/commandlinetools-win-${n}_latest.zip" -OutFile $zip
  }
  Write-Host ('Telecharge : {0:N1} Mo' -f ((Get-Item $zip).Length / 1MB))
  $tmp = Join-Path $Work 'cmdtools-tmp'
  if(Test-Path $tmp){ Remove-Item -Recurse -Force $tmp }
  Expand-Archive -Force -Path $zip -DestinationPath $tmp
  New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
  Move-Item -Force (Join-Path $tmp 'cmdline-tools') (Join-Path $sdk 'cmdline-tools\latest')
  Remove-Item -Recurse -Force $tmp
}
if(-not (Test-Path $sm)){ Fail 'sdkmanager introuvable apres installation' }

Step 'Licences Android SDK : lis-les puis tape y a chaque question'
& $sm "--sdk_root=$sdk" --licenses
Step 'Installation de la plateforme 34 et des build-tools 34.0.0'
& $sm "--sdk_root=$sdk" 'platforms;android-34' 'build-tools;34.0.0'
if($LASTEXITCODE -ne 0){ Fail 'sdkmanager a echoue (licences non acceptees ?)' }

# ---------- 3. Compilation ----------
Step 'Compilation du launcher (5 a 10 minutes la premiere fois, Gradle telecharge ses dependances)'
$env:ANDROID_HOME = $sdk
("sdk.dir=" + ($sdk -replace '\\','\\')) | Out-File -Encoding ascii (Join-Path $Src 'local.properties')
Push-Location $Src
& .\gradlew.bat assembleRelease -q --console=plain
$rc = $LASTEXITCODE
Pop-Location
if($rc -ne 0){ Fail 'La compilation Gradle a echoue (copie le message ci-dessus et envoie-le a Claude)' }
$apk = Join-Path $Src 'app\build\outputs\apk\release\app-release.apk'
if(-not (Test-Path $apk)){ Fail "APK introuvable : $apk" }
Copy-Item -Force $apk (Join-Path $Work 'tvhome.apk')
$h = Get-FileHash (Join-Path $Work 'tvhome.apk') -Algorithm SHA256
Write-Host ('SHA-256 : ' + $h.Hash)
Write-Host ('Taille  : {0:N0} octets' -f (Get-Item (Join-Path $Work 'tvhome.apk')).Length)
Write-Host 'TERMINE : tvhome.apk est pret (a installer avec adb install -r tvhome.apk).' -ForegroundColor Green
