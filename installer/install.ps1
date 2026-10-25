#Requires -Version 5.1

# UniVM Windows 安装脚本。
# 由 installer/InstallerLauncher.cs 在 IExpress 解压目录中调用。
# 安装位置：%LOCALAPPDATA%\UniVM（可用 UNIVM_INSTALL_DIR 覆盖，便于测试）。
# 桌面快捷方式：UniVM.lnk（可用 UNIVM_DESKTOP_DIR 覆盖，便于测试）。

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms

function Show-SetupMessage {
    param(
        [string]$Text,
        [string]$Title = 'UniVM Setup',
        [string]$Kind = 'Information'
    )

    try {
        $icon = [System.Windows.Forms.MessageBoxIcon]::Information
        if ($Kind -eq 'Error') {
            $icon = [System.Windows.Forms.MessageBoxIcon]::Error
        }
        if ($Kind -eq 'Warning') {
            $icon = [System.Windows.Forms.MessageBoxIcon]::Warning
        }
        [System.Windows.Forms.MessageBox]::Show(
            $Text,
            $Title,
            [System.Windows.Forms.MessageBoxButtons]::OK,
            $icon
        ) | Out-Null
    } catch {
        Write-Host "$Title`: $Text"
    }
}

function New-DesktopShortcut {
    param(
        [string]$ShortcutPath,
        [string]$TargetPath,
        [string]$WorkingDirectory,
        [string]$Description
    )

    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($ShortcutPath)
    $shortcut.TargetPath = $TargetPath
    $shortcut.WorkingDirectory = $WorkingDirectory
    $shortcut.IconLocation = "$TargetPath,0"
    $shortcut.Description = $Description
    $shortcut.Save()
}

try {
    $packageRoot = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($packageRoot)) {
        throw '无法确定安装包解压目录。'
    }

    $zips = @(Get-ChildItem -LiteralPath $packageRoot -Filter 'UniVM-*-windows-x64.zip' -File |
        Select-Object -First 2)
    if ($zips.Count -ne 1) {
        throw "安装包中应包含一个 UniVM 发布 zip，实际找到 $($zips.Count) 个。"
    }

    $zipPath = $zips[0].FullName
    $version = '1.1.1'
    if ($zips[0].Name -match '^UniVM-(.+)-windows-x64\.zip$') {
        $version = $Matches[1]
    }

    if ($env:UNIVM_INSTALL_DIR) {
        $installDir = $env:UNIVM_INSTALL_DIR
    } else {
        $localAppData = [Environment]::GetFolderPath('LocalApplicationData')
        if ([string]::IsNullOrWhiteSpace($localAppData)) {
            throw '无法确定 %LOCALAPPDATA% 目录。'
        }
        $installDir = Join-Path $localAppData 'UniVM'
    }
    $installDir = [System.IO.Path]::GetFullPath($installDir)
    if ($installDir -eq [System.IO.Path]::GetPathRoot($installDir)) {
        throw "拒绝安装到磁盘根目录：$installDir"
    }

    if ($env:UNIVM_DESKTOP_DIR) {
        $desktopDir = $env:UNIVM_DESKTOP_DIR
    } else {
        $desktopDir = [Environment]::GetFolderPath('Desktop')
    }
    $desktopDir = [System.IO.Path]::GetFullPath($desktopDir)

    $programsDir = [Environment]::GetFolderPath('Programs')
    $startMenuDir = if ([string]::IsNullOrWhiteSpace($programsDir)) {
        $null
    } else {
        Join-Path $programsDir 'UniVM'
    }

    # 升级安装时先关闭正在运行的 GUI，避免文件被占用。
    Get-Process -Name 'UniVM' -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500

    $stagingDir = Join-Path ([System.IO.Path]::GetTempPath()) ('UniVM-install-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null

    try {
        Expand-Archive -LiteralPath $zipPath -DestinationPath $stagingDir -Force
        $payloadDir = Join-Path $stagingDir 'UniVM'
        $payloadExe = Join-Path $payloadDir 'UniVM.exe'
        if (-not (Test-Path -LiteralPath $payloadExe -PathType Leaf)) {
            throw "发布包结构不正确：没有找到 $payloadExe"
        }

        if (Test-Path -LiteralPath $installDir) {
            for ($attempt = 0; $attempt -lt 5; $attempt++) {
                try {
                    Remove-Item -LiteralPath $installDir -Recurse -Force -ErrorAction Stop
                    break
                } catch {
                    if ($attempt -eq 4) { throw }
                    Start-Sleep -Milliseconds 500
                }
            }
        }

        New-Item -ItemType Directory -Force -Path $installDir | Out-Null
        Get-ChildItem -LiteralPath $payloadDir -Force | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $installDir -Recurse -Force
        }
    } finally {
        Remove-Item -LiteralPath $stagingDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    $installedExe = Join-Path $installDir 'UniVM.exe'
    if (-not (Test-Path -LiteralPath $installedExe -PathType Leaf)) {
        throw "安装失败：$installedExe 不存在。"
    }

    New-Item -ItemType Directory -Force -Path $desktopDir | Out-Null
    $desktopShortcut = Join-Path $desktopDir 'UniVM.lnk'
    New-DesktopShortcut -ShortcutPath $desktopShortcut -TargetPath $installedExe `
        -WorkingDirectory $installDir -Description "UniVM $version"

    if ($startMenuDir -and $env:UNIVM_NO_START_MENU -ne '1') {
        New-Item -ItemType Directory -Force -Path $startMenuDir | Out-Null
        $startMenuShortcut = Join-Path $startMenuDir 'UniVM.lnk'
        New-DesktopShortcut -ShortcutPath $startMenuShortcut -TargetPath $installedExe `
            -WorkingDirectory $installDir -Description "UniVM $version"
    }

    if ($env:UNIVM_SILENT -ne '1') {
        $message = "UniVM $version 安装完成！`r`n`r`n" +
            "安装位置：`r`n$installDir`r`n`r`n" +
            "桌面快捷方式：`r`n$desktopShortcut`r`n`r`n" +
            "双击桌面上的 UniVM 图标即可打开 GUI。"
        Show-SetupMessage -Text $message -Title "UniVM $version Setup"
    }

    exit 0
} catch {
    Show-SetupMessage -Text ("UniVM 安装失败：`r`n`r`n" + $_.Exception.Message) `
        -Title 'UniVM Setup' -Kind 'Error'
    exit 1
}
