<#
Removes old published releases: deletes the APK from Supabase Storage and the
matching row from the `versions` table that UpdateManager polls.

Both halves matter. A storage file with no row is invisible but still billed;
a row with no file is worse — UpdateManager would offer an update that 404s on
download, which looks like a broken app rather than a missing file.

Usage:
  # See what would go, change nothing:
  .\scripts\prune-releases.ps1 -Keep "3.04","3.0" -WhatIf

  # Actually delete:
  .\scripts\prune-releases.ps1 -Keep "3.04","3.0"

Always keep at least the current release and one known-good older build. The
older one is the rollback path, and it is only worth having if it actually
works — keeping the version you just replaced because it was broken defeats
the point.
#>

param(
    [Parameter(Mandatory = $true)]
    [string[]]$Keep,

    [switch]$WhatIf,

    [string]$BucketName = "app-releases"
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 defaults to TLS 1.0/1.1, which Supabase refuses. The
# failure reads as "the underlying connection was closed", which points nowhere
# near the real cause.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Invoke-RestMethod's default User-Agent begins "Mozilla/5.0", and Supabase
# rejects secret keys on anything that looks browser-originated.
$UA = "neveroff-prune/1.0"

$envFile = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path $envFile)) { throw ".env not found at $envFile" }

$envVars = @{}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)\s*$') { $envVars[$matches[1].Trim()] = $matches[2].Trim() }
}
$supabaseUrl = $envVars["SUPABASE_URL"]
$serviceKey  = $envVars["SUPABASE_SERVICE_ROLE_KEY"]
if (-not $supabaseUrl -or -not $serviceKey) {
    throw "SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing from .env"
}
$headers = @{ apikey = $serviceKey; Authorization = "Bearer $serviceKey" }

Write-Host "Keeping: $($Keep -join ', ')" -ForegroundColor Cyan

$rows = Invoke-RestMethod -Uri "$supabaseUrl/rest/v1/versions?select=id,version" `
    -Headers $headers -Method Get -UserAgent $UA

$listBody = @{ prefix = ""; limit = 1000; sortBy = @{ column = "name"; order = "asc" } } | ConvertTo-Json
$files = Invoke-RestMethod -Uri "$supabaseUrl/storage/v1/object/list/$BucketName" `
    -Headers $headers -Method Post -Body $listBody -ContentType "application/json" -UserAgent $UA

$doomedRows  = @($rows  | Where-Object { $Keep -notcontains $_.version })
$doomedFiles = @($files | Where-Object {
    $v = $_.name -replace '^app-', '' -replace '\.apk$', ''
    $Keep -notcontains $v
})

if ($doomedRows.Count -eq 0 -and $doomedFiles.Count -eq 0) {
    Write-Host "Nothing to remove." -ForegroundColor Green
    return
}

$freed = [math]::Round((($doomedFiles | ForEach-Object { $_.metadata.size } | Measure-Object -Sum).Sum) / 1MB, 1)
Write-Host "`nWould delete $($doomedFiles.Count) file(s) (${freed} MB) and $($doomedRows.Count) version row(s):"
$doomedFiles | ForEach-Object { Write-Host "  file  $($_.name)" }
$doomedRows  | ForEach-Object { Write-Host "  row   $($_.version)" }

if ($WhatIf) {
    Write-Host "`n-WhatIf: nothing was changed." -ForegroundColor Yellow
    return
}

# Rows first. If this run dies halfway, a version with a row but no file is the
# damaging state — the updater offers a download that 404s. Removing the row
# before the file means an interruption leaves an orphaned file, which costs
# storage and nothing else.
foreach ($row in $doomedRows) {
    Invoke-RestMethod -Uri "$supabaseUrl/rest/v1/versions?id=eq.$($row.id)" `
        -Headers $headers -Method Delete -UserAgent $UA | Out-Null
    Write-Host "Removed version row $($row.version)"
}

foreach ($file in $doomedFiles) {
    Invoke-RestMethod -Uri "$supabaseUrl/storage/v1/object/$BucketName/$($file.name)" `
        -Headers $headers -Method Delete -UserAgent $UA | Out-Null
    Write-Host "Deleted $($file.name)"
}

Write-Host "`nDone. Freed about ${freed} MB." -ForegroundColor Green
