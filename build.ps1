param(
    [switch]$SkipClean,
    [switch]$SkipMaven,
    [switch]$SkipJpackage
)

$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$ProjectName = "nginxUI"
$Version     = "1.1.0"
$JarName     = "nginxui-win.jar"
$MainClass   = "com.nginxui.Main"
$MavenCmd    = "mvn.cmd"

$Jdk17Candidates = @(
    "D:\WorkSpace\nginxUI\jdk17.0.20.1-win_x64",
    "$env:JAVA_HOME",
    "C:\Program Files\Eclipse Adoptium\jdk-17*",
    "C:\Program Files\Java\jdk-17*",
    "C:\Program Files\Microsoft\jdk-17*",
    "C:\Program Files\Zulu\zulu-17*",
    "C:\Program Files\Amazon Corretto\jdk17*",
    "C:\Program Files\JetBrains\IntelliJ IDEA 2023*\jbr"
)

function Find-Jdk17 {
    foreach ($c in $Jdk17Candidates) {
        # 1) 直接匹配 (绝对路径)
        if (Test-Path (Join-Path $c "bin\jpackage.exe")) {
            Write-Host "  [OK] JDK 17: $c" -ForegroundColor Green
            return $c
        }
        # 2) Glob 通配匹配 (如 C:\Program Files\Java\jdk-17*)
        $matched = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($matched -and (Test-Path (Join-Path $matched.FullName "bin\jpackage.exe"))) {
            Write-Host "  [OK] JDK 17: $($matched.FullName)" -ForegroundColor Green
            return $matched.FullName
        }
    }
    return $null
}

function Step {
    param([string]$Title)
    Write-Host ""
    Write-Host "===== $Title =====" -ForegroundColor Cyan
}

function Die {
    param([string]$Msg)
    Write-Host "[FAIL] $Msg" -ForegroundColor Red
    exit 1
}

# 把 launcher exe 内嵌 manifest 的 asInvoker 改成 requireAdministrator:
# jpackage 不提供该选项, 只能打包后改写 RT_MANIFEST 资源(图标等其它资源保持不变).
function Set-ExeAdminManifest {
    param([string]$ExePath)

    if (-not ("NginxUIResTool" -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class NginxUIResTool
{
    private const int RT_MANIFEST = 24;
    private const uint LOAD_LIBRARY_AS_DATAFILE = 0x00000002;

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadLibraryEx(string lpFileName, IntPtr hFile, uint dwFlags);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool FreeLibrary(IntPtr hModule);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr FindResource(IntPtr hModule, IntPtr lpName, IntPtr lpType);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LoadResource(IntPtr hModule, IntPtr hResInfo);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LockResource(IntPtr hResData);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint SizeofResource(IntPtr hModule, IntPtr hResInfo);

    private delegate bool EnumResLangProc(IntPtr hModule, IntPtr lpType, IntPtr lpName, ushort wLanguage, IntPtr lParam);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool EnumResourceLanguages(IntPtr hModule, IntPtr lpType, IntPtr lpName, EnumResLangProc lpEnumFunc, IntPtr lParam);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr BeginUpdateResource(string pFileName, bool bDeleteExistingResources);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool UpdateResource(IntPtr hUpdate, IntPtr lpType, IntPtr lpName, ushort wLanguage, byte[] lpData, uint cbData);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool EndUpdateResource(IntPtr hUpdate, bool fDiscard);

    public static bool Patch(string exePath, out string message)
    {
        IntPtr hModule = LoadLibraryEx(exePath, IntPtr.Zero, LOAD_LIBRARY_AS_DATAFILE);
        if (hModule == IntPtr.Zero) { message = "LoadLibraryEx failed: " + Marshal.GetLastWin32Error(); return false; }

        IntPtr typeId = new IntPtr(RT_MANIFEST);
        IntPtr nameId = new IntPtr(1);
        List<ushort> langs = new List<ushort>();
        EnumResourceLanguages(hModule, typeId, nameId,
            delegate(IntPtr m, IntPtr t, IntPtr n, ushort lang, IntPtr p) { langs.Add(lang); return true; },
            IntPtr.Zero);

        IntPtr hRes = FindResource(hModule, nameId, typeId);
        if (hRes == IntPtr.Zero) { FreeLibrary(hModule); message = "RT_MANIFEST(1) not found"; return false; }
        uint size = SizeofResource(hModule, hRes);
        IntPtr hGlob = LoadResource(hModule, hRes);
        IntPtr ptr = LockResource(hGlob);
        if (ptr == IntPtr.Zero || size == 0) { FreeLibrary(hModule); message = "load manifest failed"; return false; }
        byte[] data = new byte[size];
        Marshal.Copy(ptr, data, 0, (int)size);
        FreeLibrary(hModule);

        string xml = Encoding.UTF8.GetString(data);
        if (xml.IndexOf("requireAdministrator", StringComparison.Ordinal) >= 0)
        {
            message = "already requireAdministrator";
            return true;
        }
        if (xml.IndexOf("asInvoker", StringComparison.Ordinal) < 0)
        {
            message = "asInvoker not found in manifest";
            return false;
        }
        xml = xml.Replace("level=\"asInvoker\"", "level=\"requireAdministrator\"");
        byte[] outData = new UTF8Encoding(false).GetBytes(xml);

        IntPtr hUpdate = BeginUpdateResource(exePath, false);
        if (hUpdate == IntPtr.Zero) { message = "BeginUpdateResource failed: " + Marshal.GetLastWin32Error(); return false; }

        ushort langId = langs.Count > 0 ? langs[0] : (ushort)0;
        bool ok = UpdateResource(hUpdate, typeId, nameId, langId, outData, (uint)outData.Length);
        if (!ok) { message = "UpdateResource failed: " + Marshal.GetLastWin32Error(); EndUpdateResource(hUpdate, true); return false; }
        if (!EndUpdateResource(hUpdate, false)) { message = "EndUpdateResource failed: " + Marshal.GetLastWin32Error(); return false; }
        message = "patched, lang=" + langId + ", " + outData.Length + " bytes";
        return true;
    }
}
'@
    }

    $msg = ""
    # jpackage 从 JDK 模板复制出的 launcher 可能带只读属性, 改写资源前先去掉
    $item = Get-Item $ExePath
    if ($item.IsReadOnly) { $item.IsReadOnly = $false }
    $ok = [NginxUIResTool]::Patch($ExePath, [ref]$msg)
    if ($ok) {
        Write-Host "  [OK] exe manifest: requireAdministrator ($msg)" -ForegroundColor Green
    } else {
        Die "设置管理员清单失败: $msg"
    }
}

Step "nginxUI build v$Version"
Write-Host "ProjectRoot: $ProjectRoot"

# 编译与运行时统一用 JDK 17: 若沿用系统 JAVA_HOME(可能是 JDK 8),
# maven 的 release=17 会被忽略, 会编译出 Java 8 字节码并漏用 Java 9+ API.
$Jdk17 = Find-Jdk17
if (-not $Jdk17) { Die "JDK 17 not found (compile + jpackage both require JDK 17)" }
$env:JAVA_HOME = $Jdk17

Push-Location $ProjectRoot
try {
    # --- Maven package ---
    if (-not $SkipMaven) {
        Step "Maven package"
        $mvnArgs = @()
        if (-not $SkipClean) { $mvnArgs += "clean" }
        $mvnArgs += @("package", "-DskipTests", "-q")
        & $MavenCmd @mvnArgs
        if ($LASTEXITCODE -ne 0) { Die "Maven package failed (exit=$LASTEXITCODE)" }
        Write-Host "[OK] Maven package done" -ForegroundColor Green
    } else {
        Write-Host "[SKIP] Maven package" -ForegroundColor DarkGray
    }

    $FatJar = Join-Path $ProjectRoot "target\$JarName"
    if (-not (Test-Path $FatJar)) { Die "Fat jar not found: $FatJar" }
    Write-Host "Fat jar: $FatJar" -ForegroundColor Green

    # --- jpackage ---
    if (-not $SkipJpackage) {
        Step "jpackage -> app image"

        # jpackage --input 目录必须干净 (只放 fat jar).
        # target/ 里 shade 插件会同时产出 nginxui-win.jar / original-*.jar / *-version-shaded.jar,
        # 三份都含 logback-classic → 启动时 SLF4J "multiple bindings" 警告 + logback.xml 多次加载.
        # 这里单独建一个临时 input 目录, 只放 fat jar.
        $InputDir = Join-Path $ProjectRoot "target\jpackage-input"
        if (Test-Path $InputDir) { Remove-Item $InputDir -Recurse -Force }
        New-Item -ItemType Directory -Path $InputDir | Out-Null
        Copy-Item $FatJar $InputDir -Force

        $jpackage = Join-Path $Jdk17 "bin\jpackage.exe"
        $DistDir  = Join-Path $ProjectRoot "dist"
        $AppDir   = Join-Path $DistDir $ProjectName

        # 生产运行时: 用 jlink 裁剪, 只保留业务所需模块(去掉 javac/jshell/jlink 等开发工具)
        Step "jlink -> production runtime"
        $RuntimeDir = Join-Path $ProjectRoot "target\jlink-runtime"
        if (Test-Path $RuntimeDir) { Remove-Item $RuntimeDir -Recurse -Force }
        $jlink = Join-Path $Jdk17 "bin\jlink.exe"
        # 模块依据 jdeps 分析 + 运行期依赖(hutool/oshi/logback/FlatLaf):
        #   jdk.unsupported(Unsafe/JNA) jdk.crypto.ec(TLS) jdk.localedata(中文等区域) java.logging(JUL)
        $Modules = "java.base,java.compiler,java.desktop,java.management,java.naming," +
                   "java.scripting,java.sql,jdk.httpserver,jdk.unsupported,jdk.crypto.ec," +
                   "jdk.localedata,java.logging"
        & $jlink --add-modules $Modules --strip-debug --no-header-files --no-man-pages `
                 --compress=2 --output $RuntimeDir
        if ($LASTEXITCODE -ne 0) { Die "jlink failed (exit=$LASTEXITCODE)" }
        $rtMb = [math]::Round((Get-ChildItem $RuntimeDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB, 1)
        Write-Host "  [OK] runtime: $RuntimeDir ($rtMb MB)" -ForegroundColor Green

        if (Test-Path $AppDir) {
            Write-Host "  Clean old dist: $AppDir"
            try { Remove-Item $AppDir -Recurse -Force -ErrorAction Stop }
            catch {
                Write-Host "  [WARN] Clean failed ($($_.Exception.Message)), jpackage will overwrite" -ForegroundColor Yellow
            }
        }

        $jpArgs = @(
            "--type", "app-image",
            "--name", $ProjectName,
            "--input", $InputDir,
            "--main-jar", $JarName,
            "--main-class", $MainClass,
            "--runtime-image", $RuntimeDir,
            "--dest", $DistDir,
            "--app-version", $Version
        )
        # 应用图标(Windows 需 .ico): launcher exe 图标 + 任务栏/资源管理器展示
        $IconFile = Join-Path $ProjectRoot "src\favicon.ico"
        if (Test-Path $IconFile) {
            $jpArgs += @("--icon", $IconFile)
        } else {
            Write-Host "  [WARN] icon not found: $IconFile" -ForegroundColor Yellow
        }
        & $jpackage @jpArgs
        if ($LASTEXITCODE -ne 0) { Die "jpackage failed (exit=$LASTEXITCODE)" }

        $exePath = Join-Path $AppDir "$ProjectName.exe"
        if (-not (Test-Path $exePath)) { Die "nginxUI.exe not generated" }

        # 双击 exe 默认以管理员身份启动(UAC 确认), 需要读写服务/端口/配置目录
        Set-ExeAdminManifest -ExePath $exePath

        Write-Host ""
        Write-Host "===== DONE =====" -ForegroundColor Green
        $mb = [math]::Round((Get-Item $exePath).Length / 1MB, 1)
        Write-Host "  nginxUI.exe => $exePath ($mb MB)"
    } else {
        Write-Host "[SKIP] jpackage" -ForegroundColor DarkGray
    }
}
finally {
    Pop-Location
}
