<#
.SYNOPSIS
  安全清理 nginxUI-win 的构建/验证临时产物。

.DESCRIPTION
  固定清理清单，所有删除均送入 Windows 回收站（可恢复），不做永久删除。
  执行前会逐项校验路径：必须非空、位于工作区 target 内、且属于预期固定清单；
  任一校验失败立即中止。

.PARAMETER Build
  追加清理编译输出：classes / test-classes / generated-* / maven-status / maven-archiver。

.PARAMETER All
  回收整个 target 目录（含 nginxui-win.jar）。需显式指定。

.PARAMETER DryRun
  只打印将要清理的路径，不执行删除。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File clean.ps1
  powershell -ExecutionPolicy Bypass -File clean.ps1 -Build
  powershell -ExecutionPolicy Bypass -File clean.ps1 -All -DryRun
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$All,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName Microsoft.VisualBasic

# ---------- 1) 解析并校验工作区根目录 ----------
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
if ($root -eq [System.IO.Path]::GetPathRoot($root)) {
    throw "工作区解析异常（疑似根目录），已中止: $root"
}
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $root 'target'))
$allowedPrefix = $targetRoot.TrimEnd('\') + '\'

Write-Output "工作区    : $root"
Write-Output "允许范围  : $allowedPrefix*"
Write-Output "模式      : $(if ($DryRun) { '试运行（不删除）' } else { '执行（送入回收站）' })"

# ---------- 2) 生成固定清理清单 ----------
$items = [System.Collections.Generic.List[string]]::new()

function Add-Item([string]$path) {
    $full = [System.IO.Path]::GetFullPath($path)
    if (-not $items.Contains($full)) { $items.Add($full) }
}

if ($All) {
    Add-Item $targetRoot
} else {
    Add-Item (Join-Path $targetRoot 'tmp')
    Get-ChildItem -LiteralPath $targetRoot -Directory -Filter 'ui-home*' -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Item $_.FullName }
    Get-ChildItem -LiteralPath $targetRoot -File -Filter 'ui-*' -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Item $_.FullName }

    if ($Build) {
        foreach ($name in @('classes', 'test-classes', 'generated-sources',
                            'generated-test-sources', 'maven-status', 'maven-archiver')) {
            Add-Item (Join-Path $targetRoot $name)
        }
    }
}

# ---------- 3) 逐项校验后执行 ----------
$deleted = 0
$skipped = 0
$failed = [System.Collections.Generic.List[string]]::new()

foreach ($full in $items) {
    # 3.1 路径安全校验：非空、位于 target 内、非 target 根（除非 -All）
    if ([string]::IsNullOrWhiteSpace($full)) { throw '出现空路径，已中止' }
    $isTargetRoot = $full.TrimEnd('\') -eq $targetRoot.TrimEnd('\')
    if ($isTargetRoot -and -not $All) {
        throw "拒绝清理 target 根目录（需显式 -All）: $full"
    }
    if (-not $isTargetRoot -and
        -not $full.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝清理工作区 target 之外的路径: $full"
    }

    if (-not (Test-Path -LiteralPath $full)) {
        Write-Output "跳过(不存在): $full"
        $skipped++
        continue
    }

    Write-Output "清理: $full"
    if ($DryRun) { $deleted++; continue }

    try {
        if (Test-Path -LiteralPath $full -PathType Container) {
            [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory(
                $full, 'OnlyErrorDialogs', 'SendToRecycleBin')
        } else {
            [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(
                $full, 'OnlyErrorDialogs', 'SendToRecycleBin')
        }
        $deleted++
    } catch {
        $failed.Add("$full -> $($_.Exception.Message)")
        Write-Output "失败: $full ($($_.Exception.Message))"
    }
}

Write-Output "完成: 处理 $deleted 项, 跳过 $skipped 项, 失败 $($failed.Count) 项"
if ($DryRun) { Write-Output '（试运行未实际删除任何内容）' }
if ($failed.Count -gt 0) {
    Write-Output '失败明细:'
    $failed | ForEach-Object { Write-Output "  $_" }
    exit 1
}
