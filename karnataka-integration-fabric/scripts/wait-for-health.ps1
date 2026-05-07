# ============================================================
# wait-for-health.ps1 — Polls GET /api/v1/health until UP
#
# Usage: .\scripts\wait-for-health.ps1 [-Url URL] [-Timeout 120]
# ============================================================

param(
    [string]$Url = "http://localhost:8080/api/v1/health",
    [int]$Timeout = 120,
    [int]$Interval = 3
)

Write-Host "`n+===================================================="
Write-Host "| Waiting for fabric-api to become healthy ..."
Write-Host "| URL:     $Url"
Write-Host "| Timeout: ${Timeout}s"
Write-Host "+====================================================`n"

$elapsed = 0

while ($elapsed -lt $Timeout) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        if ($response.StatusCode -eq 200) {
            Write-Host "`n`nFabric-api is UP (HTTP $($response.StatusCode)) after ${elapsed}s" -ForegroundColor Green
            Write-Host "  Health response: $($response.Content)`n"
            exit 0
        }
    }
    catch {
        # Connection refused or timeout — keep waiting
    }

    Write-Host "`r  Waiting... ${elapsed}s / ${Timeout}s" -NoNewline
    Start-Sleep -Seconds $Interval
    $elapsed += $Interval
}

Write-Host "`n`nTimed out after ${Timeout}s waiting for $Url" -ForegroundColor Red
Write-Host "  Troubleshooting:"
Write-Host "    docker compose logs fabric-api"
Write-Host "    docker compose ps`n"
exit 1
