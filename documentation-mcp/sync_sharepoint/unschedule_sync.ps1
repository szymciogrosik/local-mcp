$TaskName = "SyncSharepointDocumentation"

Write-Host "Removing scheduled task '$TaskName'..."

try {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Successfully removed the scheduled synchronization!" -ForegroundColor Green
} catch {
    Write-Host "Task '$TaskName' does not exist or is already removed." -ForegroundColor Yellow
}
