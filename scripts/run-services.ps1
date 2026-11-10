<#
  Chạy cụ thể nhóm service giống compound "Services" của IntelliJ, không cần mở IDE.
  Mỗi service là 1 process `java -jar` (không qua Maven) với heap giới hạn.

  .\scripts\run-services.ps1 -Build        # build jar rồi chạy (lần đầu / sau khi sửa code)
  .\scripts\run-services.ps1               # chạy bằng jar đã build
  .\scripts\run-services.ps1 -Only auth-service,user-service,api-gateway
  .\scripts\run-services.ps1 -Xmx 192m
  .\scripts\run-services.ps1 -Stop         # tắt tất cả
  .\scripts\run-services.ps1 -Status

  Log: logs\<service>.log
#>
param(
    [switch]$Build,
    [switch]$Stop,
    [switch]$Status,
    [string[]]$Only,
    [string]$Xmx = "256m"
)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$logDir = Join-Path $root "logs"
$pidFile = Join-Path $logDir "pids.txt"
New-Item -ItemType Directory -Force $logDir | Out-Null

# Giống compound "Services" trong IntelliJ + admin-service (thứ tự: gateway/auth/user trước)
$services = @(
    "auth-service", "user-service", "api-gateway", "video-service", "media-worker",
    "interaction-service", "story-service", "recommendation-service", "chat-service",
    "notification-service", "search-service", "analytics-service", "admin-service"
)
# Split on commas too: from cmd (`powershell -File ... -Only a,b`) the list arrives as one string.
if ($Only) { $services = $Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ } }

function Stop-All {
    if (Test-Path $pidFile) {
        Get-Content $pidFile | ForEach-Object {
            $name, $procId = $_ -split '='
            if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
                Stop-Process -Id $procId -Force
                Write-Host "stopped $name ($procId)"
            }
        }
        Remove-Item $pidFile
    } else { Write-Host "Không có service nào đang chạy (theo pids.txt)." }
}

if ($Stop) { Stop-All; return }

if ($Status) {
    if (Test-Path $pidFile) {
        Get-Content $pidFile | ForEach-Object {
            $name, $procId = $_ -split '='
            $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
            $mem = if ($p) { "{0} MB" -f [int]($p.WorkingSet64 / 1MB) } else { "DEAD" }
            "{0,-26} pid={1,-7} {2}" -f $name, $procId, $mem
        }
    }
    return
}

# Nạp .env vào môi trường (IntelliJ cũng đọc file này qua envFilePaths)
if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('"'), "Process")
        }
    }
} else { Write-Warning ".env không tồn tại — chép từ .env.example." }

# Cần JDK 21: dùng JAVA_HOME nếu có, không thì tìm JDK 21 do IntelliJ tải về (~\.jdks)
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '21' } | Select-Object -First 1
    if (-not $jdk) { throw "Không tìm thấy JDK 21. Đặt JAVA_HOME trỏ tới JDK 21." }
    $env:JAVA_HOME = $jdk.FullName
}
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
Write-Host "JAVA_HOME = $env:JAVA_HOME"

if ($Build) {
    & .\mvnw.cmd clean package -DskipTests -T 1C
    if ($LASTEXITCODE -ne 0) { throw "Build lỗi (exit $LASTEXITCODE)" }
}

Stop-All  # tránh chạy trùng port

foreach ($svc in $services) {
    $jar = Get-ChildItem "services\$svc\target\$svc-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'original|sources|javadoc' } | Select-Object -First 1
    if (-not $jar) { Write-Warning "$svc : chưa có jar, chạy lại với -Build"; continue }

    $log = Join-Path $logDir "$svc.log"
    $javaArgs = @(
        "-Xms64m", "-Xmx$Xmx", "-XX:MaxMetaspaceSize=192m", "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1", "-Xss512k",
        "-jar", $jar.FullName, "--spring.profiles.active=local"
    )
    $p = Start-Process $java -ArgumentList $javaArgs -WorkingDirectory $root -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru
    "$svc=$($p.Id)" | Add-Content $pidFile
    Write-Host "started $svc (pid $($p.Id)) -> logs\$svc.log"
}
Write-Host "`nXem RAM: .\scripts\run-services.ps1 -Status | Tắt: .\scripts\run-services.ps1 -Stop"
