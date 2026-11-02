param (
    [string]$SharepointHost = ""
)

$ErrorActionPreference = "Continue"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$venvDir = Join-Path $scriptDir "venv"
$reqFile = Join-Path $scriptDir "requirements.txt"
$syncScript = Join-Path $scriptDir "src\run_sync.py"
$syncConfigFile = Join-Path $scriptDir "sync-config.json"
$syncConfigExampleFile = Join-Path $scriptDir "sync-config-example.json"

if (-not $SharepointHost) {
    $configPath = if (Test-Path $syncConfigFile) { $syncConfigFile } elseif (Test-Path $syncConfigExampleFile) { $syncConfigExampleFile } else { $null }
    if ($configPath) {
        try {
            $syncJson = Get-Content $configPath -Raw | ConvertFrom-Json
            if ($syncJson.your_company -and $syncJson.your_company -ne "your_company") {
                $comp = $syncJson.your_company
                $SharepointHost = if ($comp -like "*.*") { $comp } else { "$comp.sharepoint.com" }
            }
        } catch {}
    }
}
if (-not $SharepointHost) {
    $SharepointHost = "company.sharepoint.com"
}

$startTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host "==================================================================================="
Write-Host " Sync documents execution between local and Sharepoint started: $startTime"
Write-Host "==================================================================================="

Write-Host "Ensuring virtual environment exists..."
if (-not (Test-Path -Path $venvDir)) {
    Write-Host "Creating virtual environment..."
    py -m venv $venvDir
}

Write-Host "Activating virtual environment..."
$activateScript = Join-Path $venvDir "Scripts\Activate.ps1"
if (Test-Path $activateScript) {
    # Dot-source the activation script
    . $activateScript
} else {
    Write-Error "Could not find activation script at $activateScript"
    exit 1
}

Write-Host "Installing dependencies..."
# Use --disable-pip-version-check to prevent stderr warnings, and 2>&1 to merge any remaining stderr to stdout safely
& pip install --disable-pip-version-check -r $reqFile 2>&1 | Write-Host

Write-Host "Running synchronization..."
& py $syncScript --host $SharepointHost 2>&1 | Write-Host

if ($LASTEXITCODE -eq 0) {
    Write-Host "`nAll python synchronizations completed successfully."
} else {
    Write-Host "`nPython sync finished with errors."
}

$endTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host "==================================================================================="
Write-Host " Sync documents execution between local and Sharepoint finished: $endTime"
Write-Host "==================================================================================="
