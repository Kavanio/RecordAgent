# 无需 Pester；全部使用临时目录和模拟操作，不接触真实业务进程与数据。
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot\backend.ps1"

function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message } }
function Assert-Fails {
    param([scriptblock]$Operation)
    $failed = $false
    try { & $Operation } catch { $failed = $true }
    Assert-True $failed '预期失败的操作没有失败。'
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) ('butvan-deploy-test-' + [Guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $temporary)
try {
    $jar = Join-Path $temporary 'test.jar'
    [IO.File]::WriteAllText($jar, 'jar 测试内容', (New-Object Text.UTF8Encoding $false))
    $manifest = @{
        schemaVersion = 1; javaVersion = 21; commit = ('a' * 40)
        jar = @{ key = ('backend/releases/' + ('a' * 40) + '/123-1/butvan-backend.jar')
            sha256 = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant()
            size = (Get-Item $jar).Length }
    } | ConvertTo-Json -Depth 5 | ConvertFrom-Json
    Assert-ReleaseManifest $manifest 'backend'
    Assert-DownloadedJar $jar $manifest.jar
    foreach ($key in @('../escape.jar', 'backend/releases/other/butvan-backend.jar', 'https://example.com/app.jar')) {
        $original = $manifest.jar.key
        $manifest.jar.key = $key
        Assert-Fails { Assert-ReleaseManifest $manifest 'backend' }
        $manifest.jar.key = $original
    }
    $manifest.jar.size = 1GB + 1
    Assert-Fails { Assert-ReleaseManifest $manifest 'backend' }
    $manifest.jar.size = (Get-Item $jar).Length
    [IO.File]::WriteAllText($jar, 'corrupted')
    Assert-Fails { Assert-DownloadedJar $jar $manifest.jar }

    foreach ($failedStep in @('Download','Stop','Backup','Install','Start','None')) {
        $script:steps = New-Object 'Collections.Generic.List[string]'
        $download = { [void]$script:steps.Add('Download'); if ($failedStep -eq 'Download') { throw 'failed' }; return 'release' }
        $stop = { [void]$script:steps.Add('Stop'); if ($failedStep -eq 'Stop') { throw 'failed' } }
        $backup = { [void]$script:steps.Add('Backup'); if ($failedStep -eq 'Backup') { throw 'failed' } }
        $install = { param($release); Assert-True ($release -eq 'release') '下载结果丢失'; [void]$script:steps.Add('Install'); if ($failedStep -eq 'Install') { throw 'failed' } }
        $start = { [void]$script:steps.Add('Start'); if ($failedStep -eq 'Start') { throw 'failed' } }
        $operation = { Invoke-BackendUpdate $download $stop $backup $install $start }
        if ($failedStep -eq 'None') { & $operation } else { Assert-Fails $operation }
        $all = @('Download','Stop','Backup','Install','Start')
        $count = if ($failedStep -eq 'None') { 5 } else { [array]::IndexOf($all, $failedStep) + 1 }
        Assert-True (($script:steps -join ',') -eq ($all[0..($count-1)] -join ',')) '失败后仍执行了后续停机、替换或启动。'
    }

    $envPath = Join-Path $temporary 'backend.env'
    [IO.File]::WriteAllText($envPath, "BUTVAN_API_TOKEN=$('x'*32)`nBUTVAN_TEST=literal=a`$b`n")
    $environment = Read-BackendEnvironment $envPath
    Assert-True ($environment['BUTVAN_TEST'] -eq 'literal=a$b') '环境配置发生了插值或丢失。'
    foreach ($invalid in @('PATH=bad', 'BUTVAN_API_REQUIRE_TOKEN=false', 'BUTVAN_NATIVE_LOG_DIR=bad', 'BAD LINE')) {
        [IO.File]::WriteAllText($envPath, "BUTVAN_API_TOKEN=$('x'*32)`n$invalid")
        Assert-Fails { Read-BackendEnvironment $envPath }
    }
    [IO.File]::WriteAllText($envPath, 'BUTVAN_API_TOKEN=short')
    Assert-Fails { Read-BackendEnvironment $envPath }

    Assert-True ((ConvertTo-NativeArgument 'C:\Program Files\Java') -eq '"C:\Program Files\Java"') '空格路径未正确引用。'
    Assert-True ((ConvertTo-NativeArgument 'C:\') -eq '"C:\\"') '尾部反斜杠未正确引用。'
    Assert-Fails { ConvertTo-NativeArgument 'bad"path' }

    # 固定 HTTPS 下载地址和清单对象键拼接，拒绝跨站地址和路径穿越。
    Assert-True ((Get-ReleaseObjectUrl 'https://example.com/recordagent/' 'backend/channels/develop.json') -eq
        'https://example.com/recordagent/backend/channels/develop.json') '下载地址拼接错误。'
    foreach ($base in @('http://example.com/bucket','https://user:secret@example.com/bucket','https://example.com/bucket?token=hidden')) {
        Assert-Fails { Get-ReleaseObjectUrl $base 'backend/channels/develop.json' }
    }
    foreach ($key in @('backend/../private/file','backend/./file','https://other.com/file','backend/%2e%2e/file')) {
        Assert-Fails { Get-ReleaseObjectUrl 'https://example.com/bucket' $key }
    }

    # 首次模板初始化和旧版本配置兼容，不覆盖已经填写的真实配置。
    $configDirectory = Join-Path $temporary 'configuration'
    [void](New-Item -ItemType Directory -Path $configDirectory)
    Copy-Item (Join-Path $PSScriptRoot 'backend-settings.example.json') $configDirectory
    Copy-Item (Join-Path $PSScriptRoot 'backend.env.example') $configDirectory
    $settings = Read-BackendSettings $configDirectory
    Assert-True ($settings.downloadBaseUrl -eq 'https://minio.server.butvan.top/recordagent') '缺少默认下载地址。'
    Assert-True (Test-Path (Join-Path $configDirectory 'backend.env')) '未生成首次环境配置。'
    $configPath = Join-Path $configDirectory 'backend-settings.json'
    [IO.File]::WriteAllText($configPath, '{"javaExe":"custom-java.exe","mcExe":"legacy-mc.exe"}')
    $before = [IO.File]::ReadAllText($configPath)
    $legacy = Read-BackendSettings $configDirectory
    Assert-True ($legacy.javaExe -eq 'custom-java.exe' -and $legacy.downloadBaseUrl -match '^https://') '旧配置不兼容。'
    Assert-True ([IO.File]::ReadAllText($configPath) -eq $before) '覆盖了已有配置。'
    [IO.File]::WriteAllText($configPath, '{ bad JSON }')
    Assert-Fails { Read-BackendSettings $configDirectory }

    # 验证真实备份函数：源目录不存在时保留旧 JAR 和版本；存在时整目录复制。
    $script:DeploymentRoot = $temporary
    $script:JarPath = Join-Path $temporary 'app/butvan-backend.jar'
    $script:DataDirectory = Join-Path $temporary 'separate-user/.butvan-agent'
    [void](New-Item -ItemType Directory -Path (Join-Path $temporary 'app'))
    function Assert-PortFree {}
    [IO.File]::WriteAllText($script:JarPath, 'old jar')
    [void](New-Item -ItemType Directory -Path (Join-Path $script:DataDirectory 'data') -Force)
    [IO.File]::WriteAllText((Join-Path $script:DataDirectory 'data/butvan.db-wal'), 'wal')
    [IO.File]::WriteAllText((Join-Path $script:DataDirectory 'config.json'), 'test configuration')
    Backup-Backend
    Assert-True (Test-Path (Join-Path $script:BackupDirectory 'user-data/data/butvan.db-wal')) '备份丢失 WAL。'
    Assert-True (Test-Path (Join-Path $script:BackupDirectory 'user-data/config.json')) '备份丢失用户配置。'
    Assert-True ((Get-Content (Join-Path $script:BackupDirectory 'butvan-backend.jar') -Raw) -eq 'old jar') '未保留旧 JAR。'

    # PID 复用和手工 Java 进程不能被当作运行器持有的进程。
    $script:StatePath = Join-Path $temporary 'process.json'
    $created = [DateTime]::UtcNow
    $launchId = 'b' * 32
    Write-State $script:StatePath @{ processId = 123; createdTicks = $created.Ticks.ToString(); launchId = $launchId }
    $script:fakeProcess = [pscustomobject]@{
        ProcessId = 123; CreationDate = $created
        CommandLine = "java -Dbutvan.launcher.id=$launchId -jar $script:JarPath"
    }
    function Get-CimInstance { param($ClassName, $Filter) return $script:fakeProcess }
    Assert-True ((Get-ManagedProcess).ProcessId -eq 123) '合法进程未被识别。'
    $script:fakeProcess.CreationDate = $created.AddSeconds(1)
    Assert-Fails { Get-ManagedProcess }
    $script:fakeProcess.CreationDate = $created
    $script:fakeProcess.CommandLine = "java -jar $script:JarPath"
    Assert-Fails { Get-ManagedProcess }
    $script:fakeProcess = $null
    Assert-True ($null -eq (Get-ManagedProcess)) '已退出的进程不应保持活跃。'
    Write-Host 'Windows 部署逻辑测试通过（不含真实 Windows 进程与控制台信号）。'
} finally { Remove-Item -LiteralPath $temporary -Recurse -Force }
