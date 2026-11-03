# CleanYourTV - X88 Pro 13 : installe adb si besoin, trouve la box, puis execute
# UNIQUEMENT les commandes adb deposees par l assistant IA dans le dossier queue\.
# Ferme la fenetre ou Ctrl+C pour tout arreter.
param([string]$TvIp = '')
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$Work  = Split-Path -Parent $MyInvocation.MyCommand.Path
$Queue = Join-Path $Work 'queue'
$Out   = Join-Path $Work 'out'
$Shots = Join-Path $Work 'shots'
foreach($d in @($Queue,$Out,$Shots)){ New-Item -ItemType Directory -Force -Path $d | Out-Null }
$LogFile = Join-Path $Out 'start.log'
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Log([string]$m, [string]$color = 'Gray'){
  $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $m
  Write-Host $line -ForegroundColor $color
  [System.IO.File]::AppendAllText($LogFile, $line + "`r`n", $Utf8)
}

Log '=== CleanYourTV X88 Pro 13 - demarrage ===' 'Green'

# ---------- 1. ADB ----------
$adb = $null
$cands = @()
$cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
if($cmd){ $cands += $cmd.Source }
$cands += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
$cands += (Join-Path $Work 'platform-tools\adb.exe')
foreach($c in $cands){ if($c -and (Test-Path $c)){ $adb = $c; break } }
if(-not $adb){
  Log 'adb introuvable : telechargement des platform-tools officiels Google (dl.google.com)...' 'Yellow'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $zip = Join-Path $Work 'platform-tools-latest-windows.zip'
  Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
  Log ('Telecharge : {0:N1} Mo' -f ((Get-Item $zip).Length / 1MB))
  Expand-Archive -Force -Path $zip -DestinationPath $Work
  $adb = Join-Path $Work 'platform-tools\adb.exe'
}
Log "adb : $adb" 'Green'

function Quote-Arg([string]$s){
  if($s -eq ''){ return '""' }
  if($s -notmatch '[\s"]'){ return $s }
  return '"' + ($s -replace '"', '\"') + '"'
}

function Invoke-Adb([string[]]$AdbArgs, [int]$TimeoutSec = 90){
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $adb
  $psi.Arguments = (($AdbArgs | ForEach-Object { Quote-Arg $_ }) -join ' ')
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.CreateNoWindow = $true
  $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
  $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
  $p = [System.Diagnostics.Process]::Start($psi)
  $so = $p.StandardOutput.ReadToEndAsync()
  $se = $p.StandardError.ReadToEndAsync()
  $timedOut = $false
  if(-not $p.WaitForExit($TimeoutSec * 1000)){
    $timedOut = $true
    try { $p.Kill() } catch {}
    [void]$p.WaitForExit(5000)
  }
  $txt = $so.Result + $se.Result
  if($timedOut){ $txt += "`n[TIMEOUT apres $TimeoutSec s]" } else { $txt += "`n[exit $($p.ExitCode)]" }
  return $txt
}

function Find-AdbHosts {
  $found = New-Object System.Collections.ArrayList
  $cfgs = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq 'Up' }
  foreach($c in $cfgs){
    foreach($a in @($c.IPv4Address)){
      $ip = $a.IPAddress
      Log ('PC : {0} ({1}), passerelle {2}' -f $ip, $c.InterfaceAlias, @($c.IPv4DefaultGateway)[0].NextHop)
      $base = ($ip.Split('.')[0..2]) -join '.'
      $jobs = @()
      foreach($i in 1..254){
        $h = "$base.$i"
        $cl = New-Object System.Net.Sockets.TcpClient
        $jobs += ,@($h, $cl, $cl.ConnectAsync($h, 5555))
      }
      Start-Sleep -Milliseconds 2500
      foreach($j in $jobs){
        $h = $j[0]; $cl = $j[1]; $t = $j[2]
        if($t.Status -eq 'RanToCompletion' -and $cl.Connected){ [void]$found.Add($h) }
        try { $cl.Close() } catch {}
      }
    }
  }
  return ,$found
}

& $adb start-server
Log (Invoke-Adb @('version') 15)

# ---------- 2. Trouver la box ----------
$Serial = ''
if(-not $TvIp){
  Log 'Recherche des appareils avec le port ADB 5555 ouvert sur ton reseau...' 'Cyan'
  $hosts = Find-AdbHosts
  if($hosts.Count -eq 0){
    Log 'Aucun appareil avec le port 5555 ouvert pour l instant. Le runner demarre quand meme : l assistant relancera la recherche.' 'Yellow'
  } else {
    foreach($h in $hosts){ $n = ''; try { $n = [System.Net.Dns]::GetHostEntry($h).HostName } catch {}; Log "Trouve : $h  $n" 'Green' }
    $TvIp = $hosts[0]
  }
}
if($TvIp){
  $Serial = "${TvIp}:5555"
  Log "Connexion a $Serial ... ACCEPTE la fenetre 'Autoriser le debogage' sur la TV (coche Toujours autoriser)." 'Yellow'
  Log (Invoke-Adb @('connect', $Serial) 20)
}
[System.IO.File]::WriteAllText((Join-Path $Out 'tv.txt'), $Serial, $Utf8)

# ---------- 3. Runner ----------
$NoSerial = @('connect','disconnect','devices','kill-server','start-server','version','mdns','pair')
Log "Runner actif. Il execute UNIQUEMENT des commandes adb deposees par l assistant IA dans $Queue" 'Green'
Log 'Chaque commande s affiche ici en bleu. Ferme cette fenetre ou Ctrl+C pour tout arreter.' 'Green'
$lastBeat = Get-Date '2000-01-01'
while($true){
  if(((Get-Date) - $lastBeat).TotalSeconds -ge 5){
    [System.IO.File]::WriteAllText((Join-Path $Out 'heartbeat.txt'), ((Get-Date -Format o) + " serial=$Serial"), $Utf8)
    $lastBeat = Get-Date
  }
  $jobFiles = @(Get-ChildItem -Path $Queue -Filter '*.job' -File -ErrorAction SilentlyContinue | Sort-Object Name)
  foreach($jf in $jobFiles){
    $name = $jf.BaseName
    $lines = [System.IO.File]::ReadAllLines($jf.FullName, $Utf8)
    Remove-Item -Force $jf.FullName
    $sb = New-Object System.Text.StringBuilder
    foreach($raw in $lines){
      $l = $raw.Trim()
      if($l -eq '' -or $l.StartsWith('#')){ continue }
      Write-Host ">> $l" -ForegroundColor Cyan
      [void]$sb.AppendLine("### $l")
      if($l.StartsWith('@serial ')){ $Serial = $l.Substring(8).Trim(); [void]$sb.AppendLine("serial=$Serial"); continue }
      if($l.StartsWith('@sleep ')){ Start-Sleep -Seconds ([int]$l.Substring(7)); continue }
      if($l -eq '@scan'){ $r = Find-AdbHosts; [void]$sb.AppendLine('scan: ' + ($r -join ', ')); continue }
      $timeout = 90
      if($l -match '^@t(\d+) (.*)$'){ $timeout = [int]$Matches[1]; $l = $Matches[2] }
      $sp = $l.IndexOf(' ')
      if($sp -lt 0){ $verb = $l; $rest = '' } else { $verb = $l.Substring(0, $sp); $rest = $l.Substring($sp + 1) }
      $a = $null
      if($verb -eq 'shell'){
        $a = @('shell', $rest)
      } elseif($verb -eq 'pull' -or $verb -eq 'push' -or $verb -eq 'install'){
        $parts = $rest.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
        $ok = $true
        if($verb -eq 'pull'){
          $dst = [System.IO.Path]::GetFullPath((Join-Path $Work $parts[-1]))
          if(-not $dst.StartsWith($Work, [StringComparison]::OrdinalIgnoreCase)){ $ok = $false } else { $a = @('pull', $parts[0], $dst) }
        } else {
          $src = [System.IO.Path]::GetFullPath((Join-Path $Work $parts[-1]))
          if(-not $src.StartsWith($Work, [StringComparison]::OrdinalIgnoreCase)){ $ok = $false } else {
            if($verb -eq 'push'){ $a = @('push', $src, $parts[0]) } else { if($parts.Count -gt 1){ $a = @('install') + $parts[0..($parts.Count-2)] + @($src) } else { $a = @('install', $src) } }
          }
        }
        if(-not $ok){ [void]$sb.AppendLine('REFUSE : chemin local hors du dossier de travail'); continue }
      } else {
        $a = @($verb) + @($rest.Split(' ', [StringSplitOptions]::RemoveEmptyEntries))
      }
      if(($NoSerial -notcontains $verb) -and $Serial){ $a = @('-s', $Serial) + $a }
      $res = Invoke-Adb $a $timeout
      ($res -split "`n") | Select-Object -First 20 | ForEach-Object { Write-Host $_ }
      [void]$sb.AppendLine($res)
    }
    $tmp = Join-Path $Out "$name.tmp"
    [System.IO.File]::WriteAllText($tmp, $sb.ToString(), $Utf8)
    Move-Item -Force $tmp (Join-Path $Out "$name.out")
  }
  Start-Sleep -Milliseconds 600
}
