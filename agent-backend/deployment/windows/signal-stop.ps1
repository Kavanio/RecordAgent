param([Parameter(Mandatory=$true)][int]$TargetProcessId,
      [Parameter(Mandatory=$true)][string]$CreatedTicks,
      [Parameter(Mandatory=$true)][string]$LaunchId)
$ErrorActionPreference = 'Stop'
# 此辅助进程拥有自己的控制台；脱离后再附着目标，绝不向用户终端广播 Ctrl+C。
try {
    $target = Get-CimInstance Win32_Process -Filter "ProcessId=$TargetProcessId"
    if ($null -eq $target) { exit 0 }
    if ($target.CreationDate.ToUniversalTime().Ticks.ToString() -ne $CreatedTicks -or
        -not $target.CommandLine.Contains("-Dbutvan.launcher.id=$LaunchId")) { exit 2 }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class BackendConsoleSignal {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint pid);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GenerateConsoleCtrlEvent(uint signal, uint group);
}
'@
    [void][BackendConsoleSignal]::FreeConsole()
    if (-not [BackendConsoleSignal]::AttachConsole([uint32]$TargetProcessId)) { exit 3 }
    if (-not [BackendConsoleSignal]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)) { exit 4 }
    if (-not [BackendConsoleSignal]::GenerateConsoleCtrlEvent(0, 0)) { exit 5 }
    Start-Sleep -Milliseconds 500
    [void][BackendConsoleSignal]::FreeConsole()
    exit 0
} catch {
    # 不输出进程命令行或系统异常，避免附带受保护信息。
    exit 6
}
