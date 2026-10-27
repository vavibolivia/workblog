<#
.SYNOPSIS
  WorkBlog 설치 (Windows). 관리자 PowerShell 에서 실행.

.EXAMPLE
  .\install.ps1                                   # 같은 PC 의 PostgreSQL 에 DB 를 만들고 설치 (postgres 비밀번호를 물어봄)
  .\install.ps1 -DbUrl jdbc:postgresql://db:5432/workblog -DbUser wb -DbPassword '***'
  .\install.ps1 -Upgrade                          # 설정은 그대로, 프로그램만 교체
  .\install.ps1 -Uninstall                        # 자동 시작·프로그램 제거 (설정·데이터는 남김)

  끝나면 접속 주소와 초기 관리자 계정(아이디 admin)을 출력한다.
#>
param(
  [string]$InstallDir = "C:\WorkBlog",
  [int]$Port = 8080,
  [string]$DbUrl = "",
  [string]$DbUser = "",
  [string]$DbPassword = "",
  [string]$AdminPassword = "",
  [string]$PublicUrl = "",
  [string]$Jar = (Join-Path $PSScriptRoot "workblog.jar"),
  [switch]$Upgrade,
  [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
function Say($m) { Write-Host "== $m" -ForegroundColor Cyan }
function Fail($m) { Write-Host "오류: $m" -ForegroundColor Red; exit 1 }
function Rand($n) { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count $n | ForEach-Object { [char]$_ }) }

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Fail "관리자 권한 PowerShell 에서 실행하세요 (시작 메뉴 → PowerShell 우클릭 → 관리자 권한으로 실행)." }

$Conf = Join-Path $InstallDir "config\workblog.properties"
$TaskName = "WorkBlog"

if ($Uninstall) {
  Say "자동 시작 작업 중지·제거"
  try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop } catch {}
  try { Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop } catch {}
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$InstallDir*workblog.jar*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
  Remove-Item (Join-Path $InstallDir "workblog.jar") -ErrorAction SilentlyContinue
  Say "제거 완료. 설정($Conf)·데이터·DB 는 남겼습니다."
  exit 0
}

if (-not (Test-Path $Jar)) { Fail "workblog.jar 를 찾을 수 없습니다: $Jar" }

# 1) Java 21
Say "Java 21 확인"
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $java) { Fail "Java 21 이 필요합니다. 설치: winget install Microsoft.OpenJDK.21  (설치 후 새 PowerShell 에서 다시 실행)" }
$ver = (& cmd /c "`"$java`" -version 2>&1") -join " "
if ($ver -notmatch 'version "(2[1-9]|[3-9][0-9])') { Fail "Java 21 이상이 필요합니다 (현재: $ver). winget install Microsoft.OpenJDK.21" }

New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir "config"), (Join-Path $InstallDir "data\files"), (Join-Path $InstallDir "logs"), (Join-Path $InstallDir "static-extra") | Out-Null

if ($Upgrade -or (Test-Path $Conf)) {
  if (-not (Test-Path $Conf)) { Fail "업그레이드할 설정이 없습니다: $Conf" }
  Say "기존 설정 유지: $Conf"
  $adminInfo = "(기존 비밀번호 그대로)"
} else {
  # 2) DB
  if (-not $DbUrl) {
    Say "같은 PC 의 PostgreSQL 에 DB 생성"
    $psql = (Get-Command psql -ErrorAction SilentlyContinue).Source
    if (-not $psql) {
      $cand = Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\psql.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
      if ($cand) { $psql = $cand.FullName }
    }
    if (-not $psql) { Fail "psql 을 찾을 수 없습니다. PostgreSQL 설치(winget install PostgreSQL.PostgreSQL.16) 후 다시 실행하거나 -DbUrl/-DbUser/-DbPassword 로 외부 DB 를 지정하세요." }
    $sec = Read-Host "PostgreSQL 'postgres' 계정 비밀번호" -AsSecureString
    $env:PGPASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    $DbUser = "workblog"; $DbPassword = Rand 24
    & $psql -U postgres -h localhost -v ON_ERROR_STOP=1 -q -c "DO `$`$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='$DbUser') THEN CREATE ROLE $DbUser LOGIN PASSWORD '$DbPassword'; ELSE ALTER ROLE $DbUser PASSWORD '$DbPassword'; END IF; END `$`$;"
    if ($LASTEXITCODE -ne 0) { Fail "DB 계정을 만들지 못했습니다(비밀번호 확인)." }
    $exists = & $psql -U postgres -h localhost -tAc "SELECT 1 FROM pg_database WHERE datname='workblog'"
    if ($exists -notmatch "1") { & $psql -U postgres -h localhost -q -c "CREATE DATABASE workblog OWNER $DbUser" }
    & $psql -U postgres -h localhost -q -d workblog -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
    Remove-Item Env:\PGPASSWORD
    $DbUrl = "jdbc:postgresql://localhost:5432/workblog"
  } elseif (-not $DbUser -or -not $DbPassword) { Fail "-DbUrl 을 쓰면 -DbUser, -DbPassword 도 필요합니다 (DB 에 pg_trgm 확장 필요)." }

  # 3) 설정 파일
  if (-not $AdminPassword) { $AdminPassword = Rand 14 }
  if (-not $PublicUrl) { $PublicUrl = "http://$($env:COMPUTERNAME.ToLower()):$Port" }
  $files = (Join-Path $InstallDir "data\files") -replace '\\', '/'
  $static = (Join-Path $InstallDir "static-extra") -replace '\\', '/'
  Say "설정 파일 작성: $Conf"
  @"
# WorkBlog 설정 (install.ps1 작성). 바꾼 뒤: Restart-ScheduledTask 대신 'Stop-ScheduledTask WorkBlog; Start-ScheduledTask WorkBlog'
SERVER_PORT=$Port
DB_URL=$DbUrl
DB_USERNAME=$DbUser
DB_PASSWORD=$DbPassword
JWT_SECRET=$(Rand 48)
# 초기 관리자: 사용자가 하나도 없을 때 한 번만 만들어진다.
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$AdminPassword
STORAGE_DIR=$files
EXTRA_STATIC_DIR=$static
PUBLIC_BASE_URL=$PublicUrl
SIGNUP_ENABLED=true
SIGNUP_APPROVAL=false
DRAWIO_URL=https://embed.diagrams.net
"@ | Set-Content -Path $Conf -Encoding UTF8
  # 설정 파일은 관리자·SYSTEM 만 읽게
  icacls $Conf /inheritance:r /grant:r "Administrators:F" "SYSTEM:F" | Out-Null
  $adminInfo = $AdminPassword
}

# 4) 프로그램
Say "프로그램 설치: $InstallDir\workblog.jar"
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$InstallDir*workblog.jar*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
$target = Join-Path $InstallDir "workblog.jar"
if (Test-Path $target) { Copy-Item $target "$target.bak" -Force }
Copy-Item $Jar $target -Force

$start = Join-Path $InstallDir "start-workblog.cmd"
@"
@echo off
rem WorkBlog 실행 (자동 시작 작업이 호출). 로그: logs\workblog.log
cd /d "$InstallDir"
set WORKBLOG_CONFIG=$Conf
"$java" -Xms256m -Xmx768m -jar "$target" >> "$InstallDir\logs\workblog.log" 2>&1
"@ | Set-Content -Path $start -Encoding ASCII

# 5) 자동 시작 (부팅 시 SYSTEM 으로)
Say "자동 시작 등록 (작업 스케줄러: $TaskName)"
$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument "/c `"$start`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Days 0)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -User "SYSTEM" -RunLevel Highest -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

$portNow = ((Get-Content $Conf | Where-Object { $_ -match '^SERVER_PORT=' }) -split '=', 2)[1]
Say "기동 확인 (최대 90초)"
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
  try { Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$portNow/api/health" -TimeoutSec 2 | Out-Null; $ok = $true; break } catch { Start-Sleep -Seconds 1 }
}
if (-not $ok) { Fail "기동하지 않았습니다. 로그: $InstallDir\logs\workblog.log" }
New-NetFirewallRule -DisplayName "WorkBlog $portNow" -Direction Inbound -Protocol TCP -LocalPort $portNow -Action Allow -ErrorAction SilentlyContinue | Out-Null

$url = ((Get-Content $Conf | Where-Object { $_ -match '^PUBLIC_BASE_URL=' }) -split '=', 2)[1]
Write-Host ""
Write-Host "  WorkBlog 설치 완료" -ForegroundColor Green
Write-Host "  주소         : $url"
Write-Host "  초기 아이디  : admin"
Write-Host "  초기 비밀번호: $adminInfo"
Write-Host "  설정 파일    : $Conf"
Write-Host "  로그         : $InstallDir\logs\workblog.log"
Write-Host "  처음 로그인한 뒤 '내 정보'에서 비밀번호를 바꾸세요."
