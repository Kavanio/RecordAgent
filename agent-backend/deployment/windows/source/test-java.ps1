# 仅运行 -version 查询或模拟进程，不启动业务后端，不访问个人数据。
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot\backend.ps1"
function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message } }
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('butvan-java-test-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporary)
try {
    if ($env:BUTVAN_TEST_JAVA) {
        $script:DeploymentRoot = Join-Path $temporary 'real'
        [void][IO.Directory]::CreateDirectory((Join-Path $script:DeploymentRoot 'runtime'))
        $script:Settings = [pscustomobject]@{ javaExe = $env:BUTVAN_TEST_JAVA }
        # 原有实现会把此空退出码误判为未安装；新实现须直接持有真实 Process。
        function Start-Process {
            param($FilePath, $ArgumentList, [switch]$PassThru, $WindowStyle, $RedirectStandardError, $RedirectStandardOutput)
            [IO.File]::WriteAllText($RedirectStandardError, 'openjdk version "21.0.13" 2026-10-20 LTS')
            [IO.File]::WriteAllText($RedirectStandardOutput, '')
            $process = [pscustomobject]@{ ExitCode = $null }
            $process | Add-Member ScriptMethod WaitForExit { param($Milliseconds); return $true }
            return $process
        }
        Assert-Java
        Remove-Item Function:Start-Process
        $actual = Get-Content (Join-Path $script:DeploymentRoot 'runtime/java-check.json') -Raw | ConvertFrom-Json
        Assert-True ($actual.exitCode -eq 0 -and $actual.version -match '^21[.\-+_]') '真实 Java 21 查询未通过。'
        Assert-True ([IO.Path]::IsPathRooted($script:Settings.javaExe)) '实际启动未固定到已检查的 Java。'
    } else { Write-Host '未配置 BUTVAN_TEST_JAVA，跳过真实 Java 21 查询。' }

    $executable = if ($env:OS -eq 'Windows_NT' -and $PSVersionTable.PSVersion.Major -lt 6) {
        Join-Path $PSHOME 'powershell.exe'
    } elseif ($env:OS -eq 'Windows_NT') { Join-Path $PSHOME 'pwsh.exe' } else { Join-Path $PSHOME 'pwsh' }
    $secretFixture = 'private-options-must-not-be-logged'
    $cases = @(
        @{ name = 'stderr21'; error = "Picked up JAVA_TOOL_OPTIONS: $secretFixture`r`nopenjdk version `"21.0.13`" 2026-10-20 LTS"; output = ''; code = 0; expected = $null },
        @{ name = 'stdout21'; error = ''; output = 'java version "21.0.13"'; code = 0; expected = $null },
        @{ name = 'java17'; error = 'openjdk version "17.0.1"'; output = ''; code = 0; expected = '检测到 Java 17.0.1' },
        @{ name = 'java211'; error = 'openjdk version "211.0.1"'; output = ''; code = 0; expected = '检测到 Java 211.0.1' },
        @{ name = 'failed-query'; error = 'openjdk version "21.0.13"'; output = ''; code = 7; expected = '退出码 7' },
        @{ name = 'missing-exit-code'; error = 'openjdk version "21.0.13"'; output = ''; code = $null; expected = '无法取得退出码' },
        @{ name = 'unrecognized'; error = $secretFixture; output = ''; code = 0; expected = '无法识别 Java 版本' },
        @{ name = 'timeout'; error = ''; output = ''; code = 0; expected = '检查超时' },
        @{ name = 'start-failed'; error = ''; output = ''; code = 0; expected = '无法启动 Java' }
    )
    foreach ($case in $cases) {
        $script:DeploymentRoot = Join-Path $temporary $case.name
        [void][IO.Directory]::CreateDirectory((Join-Path $script:DeploymentRoot 'runtime'))
        $script:Settings = [pscustomobject]@{ javaExe = $executable }
        $errorReader = [pscustomobject]@{ Text = $case.error }
        $outputReader = [pscustomobject]@{ Text = $case.output }
        foreach ($reader in @($errorReader, $outputReader)) {
            $reader | Add-Member ScriptMethod ReadToEndAsync {
                $completion = New-Object 'Threading.Tasks.TaskCompletionSource[string]'
                $completion.SetResult($this.Text)
                return $completion.Task
            }
        }
        $script:probe = [pscustomobject]@{
            StartInfo = (New-Object Diagnostics.ProcessStartInfo)
            StandardError = $errorReader; StandardOutput = $outputReader; ExitCode = $case.code
            Completed = ($case.name -ne 'timeout'); StartResult = ($case.name -ne 'start-failed'); Disposed = $false
        }
        $script:probe | Add-Member ScriptMethod Start { return $this.StartResult }
        $script:probe | Add-Member ScriptMethod WaitForExit { param($Milliseconds); return $this.Completed }
        $script:probe | Add-Member ScriptMethod Dispose { $this.Disposed = $true }
        function New-JavaCheckProcess { return $script:probe }
        $errorMessage = $null
        try { Assert-Java } catch { $errorMessage = $_.Exception.Message }
        if ($null -eq $case.expected) { Assert-True ($null -eq $errorMessage) "有效 Java 21 被拒绝：$errorMessage" }
        else { Assert-True ($null -ne $errorMessage -and $errorMessage.Contains($case.expected)) "失败语义不符：$errorMessage" }
        Assert-True $script:probe.Disposed '查询结束未释放进程句柄。'
        Assert-True (-not $script:probe.StartInfo.UseShellExecute -and $script:probe.StartInfo.Arguments -eq '-version') '查询调用使用了 Shell 或非固定参数。'
        if ($case.name -notin @('timeout', 'start-failed')) {
            $diagnostic = Get-Content (Join-Path $script:DeploymentRoot 'runtime/java-check.json') -Raw | ConvertFrom-Json
            Assert-True ($diagnostic.exitCode -eq $case.code) '诊断退出码丢失。'
            foreach ($file in Get-ChildItem (Join-Path $script:DeploymentRoot 'runtime')) {
                Assert-True (-not ([IO.File]::ReadAllText($file.FullName)).Contains($secretFixture)) '诊断泄露了原始 JVM 选项。'
            }
        }
    }
    $emptyError = $null
    $script:DeploymentRoot = Join-Path $temporary 'empty-java'
    [void][IO.Directory]::CreateDirectory((Join-Path $script:DeploymentRoot 'runtime'))
    $script:Settings = [pscustomobject]@{ javaExe = '' }
    try { Assert-Java } catch { $emptyError = $_.Exception.Message }
    Assert-True ($null -ne $emptyError -and $emptyError.Contains('无法启动 Java')) "空 javaExe 未正确拒绝：$emptyError"
    Write-Host 'Java 检测测试通过：真实查询（若配置）、双流、版本、退出码、超时、资源释放与敏感输出过滤。'
} finally { Remove-Item -LiteralPath $temporary -Recurse -Force }
