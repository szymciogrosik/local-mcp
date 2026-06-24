$ErrorActionPreference = "Continue"

$SharepointHost = "company.sharepoint.com"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$venvDir = Join-Path $scriptDir "venv"
$reqFile = Join-Path $scriptDir "requirements.txt"
$syncScript = Join-Path $scriptDir "src\run_sync.py"

$startTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host "=================================================================================="
Write-Host " Sync documents execution between local and Sharepoint started: $startTime"
Write-Host "=================================================================================="

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

$endTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Host "=================================================================================="
Write-Host " Sync documents execution between local and Sharepoint finished: $endTime"
Write-Host "=================================================================================="
