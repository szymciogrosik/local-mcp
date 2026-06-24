param (
    [string]$Time = "13:00"
)

$TaskName = "SyncSharepointDocumentation"
$ScriptPath = Join-Path $PSScriptRoot "run_sync.ps1"

Write-Host "Scheduling '$TaskName' to run daily at $Time..."

# Create the action to run the sync script visibly so the user sees the synchronization result
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoExit -ExecutionPolicy Bypass -Command `". `"$ScriptPath`"`""

# Create the trigger for the specified time
$trigger = New-ScheduledTaskTrigger -Daily -At $Time

# Configure settings so it runs nicely on laptops
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

# Ensure it runs interactively as the current user (so the browser window can spawn)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive

# Register the task. -Force will overwrite (reschedule) if it already exists!
try {
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    Write-Host "Successfully scheduled! The synchronization will run automatically every day at $Time." -ForegroundColor Green
    Write-Host "You can change this time simply by running: .\schedule_sync.ps1 -Time '14:30'"
} catch {
    Write-Error "Failed to schedule the task. You might need to run PowerShell as Administrator."
}
