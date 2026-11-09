$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) {
    throw 'Set JAVA_HOME to JDK 17 or 21 before building.'
}
$directory = Join-Path $env:LOCALAPPDATA 'WorkPause\signing'
$keystore = Join-Path $directory 'workpause-release.p12'
$passwordFile = Join-Path $directory 'release-password.dpapi'
if (-not (Test-Path -LiteralPath $keystore) -or -not (Test-Path -LiteralPath $passwordFile)) {
    throw 'Release signing is not initialized. Run tools\Initialize-ReleaseSigning.ps1 first.'
}
$securePassword = (Get-Content -LiteralPath $passwordFile -Raw).Trim() | ConvertTo-SecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$names = @('WORKPAUSE_KEYSTORE_PATH', 'WORKPAUSE_STORE_PASSWORD', 'WORKPAUSE_KEY_ALIAS', 'WORKPAUSE_KEY_PASSWORD')
$previous = @{}
foreach ($name in $names) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:WORKPAUSE_KEYSTORE_PATH = $keystore
    $env:WORKPAUSE_KEY_ALIAS = 'workpause'
    $env:WORKPAUSE_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:WORKPAUSE_KEY_PASSWORD = $env:WORKPAUSE_STORE_PASSWORD
    Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
    try {
        & .\gradlew.bat :app:assembleRelease --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw 'Signed release build failed.'
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $securePassword.Dispose()
}
Write-Host 'Signed WorkPause release APK built successfully.'
