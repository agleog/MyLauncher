param(
    [Parameter(Mandatory = $true)][long]$ParentPid,
    [Parameter(Mandatory = $true)][string]$InstallationDirectory,
    [Parameter(Mandatory = $true)][string]$StagedImage,
    [Parameter(Mandatory = $true)][string]$StagingDirectory,
    [switch]$NoRestart
)

$ErrorActionPreference = 'Stop'

function Remove-Tree([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
}

function Move-Path([string]$Source, [string]$Target) {
    if (Test-Path -LiteralPath $Target) {
        throw "Update target already exists: $Target"
    }
    Move-Item -LiteralPath $Source -Destination $Target
}

$installation = [System.IO.Path]::GetFullPath($InstallationDirectory)
$staging = [System.IO.Path]::GetFullPath($StagingDirectory)
$stagedImagePath = [System.IO.Path]::GetFullPath($StagedImage)
$parentDirectory = [System.IO.Path]::GetDirectoryName($installation)
$stagingParent = [System.IO.Path]::GetDirectoryName($staging)
$stagingName = [System.IO.Path]::GetFileName($staging)
$items = @('app', 'runtime', 'MyLauncher.exe')
$saved = @{}
$installed = @{}
$backup = $null
$success = $false

try {
    if ($ParentPid -le 0 -or $parentDirectory -ne $stagingParent -or
        -not $stagingName.StartsWith('.mylauncher-update-', [StringComparison]::OrdinalIgnoreCase) -or
        $stagedImagePath -ne [System.IO.Path]::Combine($staging, 'image')) {
        throw 'Update paths failed validation.'
    }

    foreach ($item in $items) {
        $target = Join-Path $installation $item
        $replacement = Join-Path $stagedImagePath $item
        if (-not (Test-Path -LiteralPath $target) -or -not (Test-Path -LiteralPath $replacement)) {
            throw "Required launcher file is missing: $item"
        }
        $existing = Get-Item -LiteralPath $target -Force
        if (($existing.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing to replace a reparse point: $target"
        }
    }

    $runningLauncher = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
    if ($null -ne $runningLauncher -and -not $runningLauncher.WaitForExit(120000)) {
        throw 'MyLauncher did not exit within the update timeout.'
    }

    $backup = Join-Path $parentDirectory ('.mylauncher-rollback-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $backup | Out-Null

    foreach ($item in $items) {
        $target = Join-Path $installation $item
        $previous = Join-Path $backup $item
        $replacement = Join-Path $stagedImagePath $item
        Move-Path $target $previous
        $saved[$item] = $true
        Move-Path $replacement $target
        $installed[$item] = $true
    }

    if (-not $NoRestart) {
        Start-Process -FilePath (Join-Path $installation 'MyLauncher.exe') -WorkingDirectory $installation
    }
    $success = $true
}
catch {
    $failure = $_
    $rollbackFailure = $null
    $rollbackItems = $items.Clone()
    [array]::Reverse($rollbackItems)
    foreach ($item in $rollbackItems) {
        try {
            $target = Join-Path $installation $item
            $previous = if ($null -ne $backup) { Join-Path $backup $item } else { '' }
            if ($installed.ContainsKey($item) -and (Test-Path -LiteralPath $target)) {
                Remove-Tree $target
            }
            if ($saved.ContainsKey($item) -and (Test-Path -LiteralPath $previous)) {
                Move-Path $previous $target
            }
        }
        catch {
            if ($null -eq $rollbackFailure) {
                $rollbackFailure = $_
            }
        }
    }

    if ($null -ne $rollbackFailure) {
        throw "Update failed: $($failure.Exception.Message) Rollback failed; backup retained at '$backup': $($rollbackFailure.Exception.Message)"
    }
    if ($null -ne $backup) {
        Remove-Tree $backup
    }
    if (-not $NoRestart -and (Test-Path -LiteralPath (Join-Path $installation 'MyLauncher.exe'))) {
        Start-Process -FilePath (Join-Path $installation 'MyLauncher.exe') -WorkingDirectory $installation
    }
    throw $failure
}
finally {
    if ($success) {
        Remove-Tree $backup
        Remove-Tree $staging
    }
    $scriptPath = $MyInvocation.MyCommand.Path
    if ($scriptPath -and (Test-Path -LiteralPath $scriptPath)) {
        Remove-Item -LiteralPath $scriptPath -Force -ErrorAction SilentlyContinue
    }
}
