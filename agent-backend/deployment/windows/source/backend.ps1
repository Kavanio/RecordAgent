param([ValidateSet('Update','Start','Stop','Logs')][string]$Action = 'Start',
      [string]$DeploymentRoot = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Console]::OutputEncoding = New-Object Text.UTF8Encoding $false
. "$PSScriptRoot\backend-core.ps1"
. "$PSScriptRoot\backend-download.ps1"
$script:SupportRoot = $PSScriptRoot
$script:DeploymentRoot = $DeploymentRoot

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
    $arguments = @('-NoLogo','-NoProfile','-NonInteractive','-File',
        (ConvertTo-NativeArgument "$script:SupportRoot\signal-stop.ps1"),
        '-TargetProcessId', $process.ProcessId, '-CreatedTicks', $state.createdTicks, '-LaunchId', $state.launchId)
    $helper = Start-Process -FilePath "$PSHOME\powershell.exe" -ArgumentList $arguments `
        -PassThru
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
    Assert-JarMetadata $installed
    Assert-DownloadedJar $script:JarPath $installed
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
        "--logging.config=$script:SupportRoot\logback-windows.xml")
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

function New-JavaCheckProcess {
    # 直接持有 .NET Process 句柄，避免 Windows Start-Process 重定向后的空 ExitCode。
    return New-Object Diagnostics.Process
}

function Assert-Java {
    $java = New-JavaCheckProcess
    try {
        try {
            $javaExe = [string]$script:Settings.javaExe
            if ([string]::IsNullOrWhiteSpace($javaExe)) { throw 'Missing javaExe' }
            $application = @(Get-Command -Name $javaExe -CommandType Application -TotalCount 1 -ErrorAction Stop)[0]
            if ($null -eq $application) { throw 'Java not found' }
            # 检测和后续启动使用同一解析结果，仅更新本次内存配置。
            $script:Settings.javaExe = $application.Source
            $java.StartInfo.FileName = $application.Source
            $java.StartInfo.Arguments = '-version'
            $java.StartInfo.UseShellExecute = $false
            $java.StartInfo.CreateNoWindow = $true
            $java.StartInfo.RedirectStandardError = $true
            $java.StartInfo.RedirectStandardOutput = $true
            if (-not $java.Start()) { throw 'Java process did not start' }
        } catch { throw '找不到或无法启动 Java；请在 data/backend-settings.json 中将 javaExe 设置为 Java 21 的完整路径。' }
        # 同时读取两条流，避免管道阻塞；等待正文完成后再检查版本。
        $errorOutput = $java.StandardError.ReadToEndAsync()
        $standardOutput = $java.StandardOutput.ReadToEndAsync()
        if (-not $java.WaitForExit(10000)) { throw 'Java 版本检查超时，已停止本次操作。' }
        if (-not [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($errorOutput, $standardOutput), 2000)) {
            throw 'Java 版本输出读取超时，已停止本次操作。'
        }
        $exitCode = $java.ExitCode
        $versionPattern = '(?m)^(?:openjdk|java) version "(?<version>(?<major>[0-9]+)(?:[._+\-][0-9A-Za-z]+)*)"'
        $errorVersion = [regex]::Match($errorOutput.Result, $versionPattern)
        $outputVersion = [regex]::Match($standardOutput.Result, $versionPattern)
        $version = if ($errorVersion.Success) { $errorVersion } else { $outputVersion }
        $number = if ($version.Success) { $version.Groups['version'].Value } else { $null }
        # 只保存识别出的版本和退出码，过滤 JVM 选项、环境值等原始输出。
        foreach ($entry in @(@{ name = 'java-check.err'; match = $errorVersion }, @{ name = 'java-check.out'; match = $outputVersion })) {
            $safeOutput = if ($entry.match.Success) { 'java version "' + $entry.match.Groups['version'].Value + '"' } else { '' }
            [IO.File]::WriteAllText((Join-Path $script:DeploymentRoot ('runtime/' + $entry.name)), $safeOutput, (New-Object Text.UTF8Encoding $false))
        }
        Write-State (Join-Path $script:DeploymentRoot 'runtime/java-check.json') @{
            checkedAtUtc = [DateTime]::UtcNow.ToString('o')
            executable = $script:Settings.javaExe
            version = $number
            exitCode = $exitCode
        }
        if ($null -eq $exitCode) { throw 'Java 检查无法取得退出码，请查看 data/runtime/java-check.json；未判定为版本不符。' }
        if ($exitCode -ne 0) { throw "Java 版本查询失败（退出码 $exitCode），请核对 JVM 启动配置；详情见 data/runtime/java-check.json。" }
        if (-not $version.Success) { throw '无法识别 Java 版本，请将 data/backend-settings.json 的 javaExe 指向 Java 21；详情见 data/runtime/java-check.json。' }
        if ($version.Groups['major'].Value -ne '21') {
            throw "检测到 Java $number，需要 Java 21；请修改 data/backend-settings.json 的 javaExe。"
        }
        Write-Host "Java $number 检查通过。"
    } finally { $java.Dispose() }
}

function Download-Release {
    Assert-Java
    $directory = Join-Path $script:DeploymentRoot ('staging\' + [Guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $directory)
    $jar = Join-Path $directory 'butvan-backend.jar'
    Write-Host '正在下载最新后端 JAR……'
    $metadata = Save-PublicDownload $script:Settings.jarUrl $jar 1GB -RequireJarMetadata
    Assert-JarMetadata $metadata
    Assert-DownloadedJar $jar $metadata
    Assert-ExecutableJar $jar
    Write-Host "下载校验完成，提交：$($metadata.commit)"
    return @{ metadata = $metadata; directory = $directory; jar = $jar }
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
    Assert-DownloadedJar "$script:JarPath.new" $Release.metadata
    Move-Item -LiteralPath "$script:JarPath.new" -Destination $script:JarPath -Force
    Write-State "$script:DeploymentRoot\app\installed.json" $Release.metadata
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
    $script:Settings = Read-BackendSettings $script:DeploymentRoot $script:SupportRoot
    if ($script:Settings.port -lt 1024 -or $script:Settings.port -gt 65535 -or
        $script:Settings.startTimeoutSeconds -lt 10 -or $script:Settings.startTimeoutSeconds -gt 600 -or
        $script:Settings.stopTimeoutSeconds -lt 10 -or $script:Settings.stopTimeoutSeconds -gt 600) { throw '部署配置字段无效。' }
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
    Initialize-BackendToken "$script:DeploymentRoot\backend.env"
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
