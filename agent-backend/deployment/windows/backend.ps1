param([ValidateSet('Update','Start','Stop','Logs')][string]$Action = 'Start')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Console]::OutputEncoding = New-Object Text.UTF8Encoding $false
. "$PSScriptRoot\backend-core.ps1"
$script:DeploymentRoot = $PSScriptRoot

function Write-State {
    param([string]$Path, $Value)
    $temporary = "$Path.tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding $false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Get-ManagedProcess {
    if (-not (Test-Path -LiteralPath $script:StatePath)) { return $null }
    $state = Get-Content -LiteralPath $script:StatePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$state.processId)"
    if ($null -eq $process) { return $null }
    if ($process.CreationDate.ToUniversalTime().Ticks.ToString() -ne $state.createdTicks -or
        $state.launchId -notmatch '^[a-f0-9]{32}$' -or
        $null -eq $process.CommandLine -or
        -not $process.CommandLine.Contains("-Dbutvan.launcher.id=$($state.launchId)") -or
        -not $process.CommandLine.Contains($script:JarPath)) {
        throw 'PID 身份与本运行器记录不一致，拒绝停止或启动。请人工核对 runtime/process.json。'
    }
    return $process
}

function Assert-PortFree {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, [int]$script:Settings.port)
    $listener.ExclusiveAddressUse = $true
    try { $listener.Start() }
    catch { throw '后端端口已被占用；请确认旧 Docker 后端或其他进程已停止。' }
    finally { $listener.Stop() }
}

function Test-BackendHealth {
    try {
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:$($script:Settings.port)/api/health" `
            -Headers @{ Authorization = 'Bearer ' + $script:Environment['BUTVAN_API_TOKEN'].Trim() } -TimeoutSec 2
        return ($response.code -eq 200 -and $response.data -eq 'ok')
    } catch { return $false }
}

function Stop-Backend {
    $process = Get-ManagedProcess
    if ($null -eq $process) { Assert-PortFree; return }
    $state = Get-Content -LiteralPath $script:StatePath -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Host '正在请求后端正常关闭……'
    $arguments = @('-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',
        (ConvertTo-NativeArgument "$script:DeploymentRoot\signal-stop.ps1"),
        '-TargetProcessId', $process.ProcessId, '-CreatedTicks', $state.createdTicks, '-LaunchId', $state.launchId)
    $helper = Start-Process -FilePath "$PSHOME\powershell.exe" -ArgumentList $arguments `
        -WindowStyle Hidden -PassThru
    if (-not $helper.WaitForExit(15000) -or $helper.ExitCode -ne 0) {
        throw '发送正常关闭信号失败，已停止操作；不会强杀 Java 或替换 JAR。'
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($script:Settings.stopTimeoutSeconds)
    while ($null -ne (Get-ManagedProcess)) {
        if ([DateTime]::UtcNow -ge $deadline) { throw '等待正常退出超时，未强杀或替换，请查看实时日志。' }
        Start-Sleep -Milliseconds 250
    }
    Remove-Item -LiteralPath $script:StatePath -Force
    Assert-PortFree
    Write-Host '后端已退出。'
}

function Start-Backend {
    if ($null -ne (Get-ManagedProcess)) {
        if (Test-BackendHealth) { Write-Host '后端已运行且健康，无需重复启动。'; return }
        throw '本运行器已有进程但健康检查失败，请查看日志后停止；不会再启动第二个进程。'
    }
    Assert-PortFree
    if (-not (Test-Path -LiteralPath $script:JarPath)) { throw '尚未安装 JAR，请先运行 update-and-start.bat。' }
    $installed = Get-Content -LiteralPath "$script:DeploymentRoot\app\installed.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ReleaseManifest $installed $script:Settings.prefix
    Assert-DownloadedJar $script:JarPath $installed.jar
    # 所有启动路径先验证 Java 21，避免停机后才发现依赖缺失。
    Assert-Java
    foreach ($name in @('boot.out.log','boot.err.log')) {
        $path = Join-Path $script:LogDirectory $name
        if (Test-Path -LiteralPath $path) { Move-Item -LiteralPath $path -Destination "$path.old" -Force }
    }
    $launchId = [Guid]::NewGuid().ToString('N')
    $nativeArgs = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
        '-Duser.timezone=Asia/Shanghai', "-Duser.home=$($script:Settings.userHome)",
        "-Dbutvan.launcher.id=$launchId", '-jar', $script:JarPath,
        "--server.port=$($script:Settings.port)", '--server.address=127.0.0.1',
        '--butvan.api.require-token=true', '--server.ssl.enabled=false',
        "--logging.config=$script:DeploymentRoot\logback-windows.xml")
    $argumentLine = ($nativeArgs | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $restore = @{}
    try {
        foreach ($key in $script:Environment.Keys) {
            $restore[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $script:Environment[$key], 'Process')
        }
        foreach ($key in @('BUTVAN_API_REQUIRE_TOKEN','BUTVAN_NATIVE_LOG_DIR')) {
            $restore[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        }
        $env:BUTVAN_API_REQUIRE_TOKEN = 'true'
        $env:BUTVAN_NATIVE_LOG_DIR = $script:LogDirectory.Replace('\','/')
        # 新建独立隐藏控制台，保留 Java 的 Ctrl+C 关闭能力，不依附 BAT 窗口。
        $started = Start-Process -FilePath $script:Settings.javaExe -ArgumentList $argumentLine `
            -WorkingDirectory $script:DeploymentRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput "$script:LogDirectory\boot.out.log" `
            -RedirectStandardError "$script:LogDirectory\boot.err.log"
    } finally {
        foreach ($key in $restore.Keys) { [Environment]::SetEnvironmentVariable($key, $restore[$key], 'Process') }
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($started.Id)"
    if ($null -eq $process) { throw 'Java 启动后立即退出，请检查 logs/boot.err.log。' }
    Write-State $script:StatePath @{
        processId = $started.Id; launchId = $launchId
        createdTicks = $process.CreationDate.ToUniversalTime().Ticks.ToString()
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($script:Settings.startTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($null -eq (Get-ManagedProcess)) { throw '后端启动失败，请查看日志；未自动回退数据库或 JAR。' }
        if (Test-BackendHealth) {
            Write-Host "后端已在后台运行，地址 http://127.0.0.1:$($script:Settings.port)。可以关闭此窗口。"
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw '后端健康检查超时，进程和现场已保留；请打开日志查看，未自动回退。'
}

function Assert-Java {
    # 标准错误重定向到临时文件，既能读取版本，也不将 JVM 错误文本暴露为配置日志。
    $check = Join-Path $script:DeploymentRoot 'runtime\java-check.err'
    $stdout = Join-Path $script:DeploymentRoot 'runtime\java-check.out'
    $java = Start-Process -FilePath $script:Settings.javaExe -ArgumentList '-version' -PassThru `
        -WindowStyle Hidden -RedirectStandardError $check -RedirectStandardOutput $stdout
    if (-not $java.WaitForExit(10000)) { throw 'Java 版本检查超时。' }
    if ($java.ExitCode -ne 0 -or (Get-Content -LiteralPath $check -Raw) -notmatch 'version "21[\."]') {
        throw '请安装 Java 21，并在 backend-settings.json 中配置 javaExe。'
    }
}

function Copy-MinIOObject {
    param([string]$Key, [string]$Destination)
    $source = "$($script:Settings.minioAlias)/$($script:Settings.bucket)/$Key"
    # 客户端使用本机已保存的只读别名；输出丢弃，防止异常响应泄露凭据。
    & $script:Settings.mcExe --quiet cp -- $source $Destination *> $null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $Destination)) {
        throw 'MinIO 下载失败，请核对 S3 API、只读别名、网络和对象权限。原服务未停止。'
    }
}

function Download-Release {
    Assert-Java
    $directory = Join-Path $script:DeploymentRoot ('staging\' + [Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $directory)
    $manifestPath = Join-Path $directory 'manifest.json'
    Copy-MinIOObject "$($script:Settings.prefix)/channels/$($script:Settings.channel).json" $manifestPath
    if ((Get-Item -LiteralPath $manifestPath).Length -gt 64KB) { throw '版本清单超过大小限制。' }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ReleaseManifest $manifest $script:Settings.prefix
    $jar = Join-Path $directory 'butvan-backend.jar'
    Write-Host "下载提交 $($manifest.commit)……"
    Copy-MinIOObject $manifest.jar.key $jar
    Assert-DownloadedJar $jar $manifest.jar
    return @{ manifest = $manifest; directory = $directory; jar = $jar }
}

function Backup-Backend {
    # 执行时已停止旧进程；完整复制包括 SQLite WAL 和所有文件，不做运行中单库备份。
    Assert-PortFree
    $script:BackupDirectory = Join-Path $script:DeploymentRoot ('backups\' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $script:BackupDirectory)
    if (Test-Path -LiteralPath $script:DataDirectory) {
        Copy-Item -LiteralPath $script:DataDirectory -Destination "$script:BackupDirectory\user-data" -Recurse -Force
    }
    if (Test-Path -LiteralPath $script:JarPath) {
        Copy-Item -LiteralPath $script:JarPath -Destination "$script:BackupDirectory\butvan-backend.jar"
    }
    if (Test-Path -LiteralPath "$script:DeploymentRoot\app\installed.json") {
        Copy-Item -LiteralPath "$script:DeploymentRoot\app\installed.json" -Destination "$script:BackupDirectory\installed.json"
    }
    Write-Host "停机备份已保存：$script:BackupDirectory"
}

function Install-Release {
    param($Release)
    Copy-Item -LiteralPath $Release.jar -Destination "$script:JarPath.new" -Force
    Assert-DownloadedJar "$script:JarPath.new" $Release.manifest.jar
    Move-Item -LiteralPath "$script:JarPath.new" -Destination $script:JarPath -Force
    Write-State "$script:DeploymentRoot\app\installed.json" $Release.manifest
}

function Watch-BackendLog {
    $path = Join-Path $script:DeploymentRoot 'logs\backend.log'
    Write-Host '实时日志（关闭本窗口不会停止后端；Ctrl+C 结束查看）'
    $reader = $null
    $created = $null
    $initial = $true
    try {
        while ($true) {
            if (-not (Test-Path -LiteralPath $path)) { Start-Sleep -Milliseconds 500; continue }
            $item = Get-Item -LiteralPath $path
            if ($null -eq $reader -or $created -ne $item.CreationTimeUtc -or $reader.BaseStream.Position -gt $item.Length) {
                if ($null -ne $reader) { $reader.Dispose() }
                $stream = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
                    ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
                $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
                $created = $item.CreationTimeUtc
                if ($initial) {
                    $tail = New-Object 'Collections.Generic.Queue[string]'
                    while (-not $reader.EndOfStream) {
                        $tail.Enqueue($reader.ReadLine())
                        if ($tail.Count -gt 80) { [void]$tail.Dequeue() }
                    }
                    foreach ($line in $tail) { Write-Host $line }
                    $initial = $false
                }
            }
            while (-not $reader.EndOfStream) { Write-Host $reader.ReadLine() }
            Start-Sleep -Milliseconds 250
        }
    } finally { if ($null -ne $reader) { $reader.Dispose() } }
}

# 点加载仅导出函数，供隔离测试使用，不创建目录、不操作真实进程。
if ($MyInvocation.InvocationName -eq '.') { return }

$operationLock = $null
try {
    if ($env:OS -ne 'Windows_NT') { throw '此入口仅支持原生 Windows PowerShell。' }
    if ($Action -eq 'Logs') { Watch-BackendLog; exit 0 }
    foreach ($directory in @('runtime','app','logs','staging','backups')) {
        [void](New-Item -ItemType Directory -Path (Join-Path $script:DeploymentRoot $directory) -Force)
    }
    try {
        $operationLock = [IO.File]::Open("$script:DeploymentRoot\runtime\operation.lock", 'OpenOrCreate', 'ReadWrite', 'None')
    } catch { throw '另一个启动、停止或更新操作正在运行，请等待完成。' }
    $script:StatePath = "$script:DeploymentRoot\runtime\process.json"
    $script:JarPath = "$script:DeploymentRoot\app\butvan-backend.jar"
    $script:LogDirectory = "$script:DeploymentRoot\logs"
    $script:Settings = Get-Content -LiteralPath "$script:DeploymentRoot\backend-settings.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($script:Settings.port -lt 1024 -or $script:Settings.port -gt 65535 -or
        $script:Settings.startTimeoutSeconds -lt 10 -or $script:Settings.startTimeoutSeconds -gt 600 -or
        $script:Settings.stopTimeoutSeconds -lt 10 -or $script:Settings.stopTimeoutSeconds -gt 600 -or
        $script:Settings.minioAlias -notmatch '^[a-zA-Z0-9_-]+$' -or
        $script:Settings.bucket -notmatch '^[a-z0-9][a-z0-9.-]+[a-z0-9]$' -or
        $script:Settings.prefix -notmatch '^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$' -or
        $script:Settings.channel -ne 'develop') { throw '部署配置字段无效。' }
    if ([string]::IsNullOrWhiteSpace($script:Settings.userHome)) {
        $script:Settings.userHome = [Environment]::GetFolderPath('UserProfile')
    }
    if (-not [IO.Path]::IsPathRooted($script:Settings.userHome) -or
        -not (Test-Path -LiteralPath $script:Settings.userHome -PathType Container)) {
        throw 'userHome 必须是已存在的 Windows 用户主目录绝对路径。'
    }
    $script:Settings.userHome = [IO.Path]::GetFullPath($script:Settings.userHome).TrimEnd('\')
    if ($script:Settings.userHome -eq [IO.Path]::GetPathRoot($script:Settings.userHome).TrimEnd('\')) {
        throw 'userHome 不能是磁盘根目录。'
    }
    $null = ConvertTo-NativeArgument $script:Settings.userHome
    $script:DataDirectory = Join-Path $script:Settings.userHome '.butvan-agent'
    if ($script:DeploymentRoot.StartsWith($script:DataDirectory + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $script:DeploymentRoot -eq $script:DataDirectory) { throw '发布目录不能放在业务数据目录内。' }
    # 停止现有进程不依赖密钥、Java 或 MinIO 仍可用。
    if ($Action -eq 'Stop') { Stop-Backend; exit 0 }
    $script:Environment = Read-BackendEnvironment "$script:DeploymentRoot\backend.env"
    if ($Action -eq 'Update') {
        Invoke-BackendUpdate -Download { Download-Release } -Stop { Stop-Backend } `
            -Backup { Backup-Backend } -Install { param($release) Install-Release $release } -Start { Start-Backend }
    } else { Start-Backend }
} catch {
    # 只打印自定义错误。系统异常可能包含命令、路径中的认证字段或环境内容。
    if ($_.Exception -is [Management.Automation.RuntimeException] -and $_.Exception.WasThrownFromThrowStatement) {
        Write-Host $_.Exception.Message -ForegroundColor Red
    } else {
        Write-Host '操作失败，请核对配置文件、路径权限、工具安装和 logs 下日志；未自动回退。' -ForegroundColor Red
    }
    exit 1
} finally { if ($null -ne $operationLock) { $operationLock.Dispose() } }
