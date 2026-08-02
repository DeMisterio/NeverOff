<#
Publishes a new app release in one step: uploads the APK to Supabase Storage,
computes its SHA-256, and inserts the matching row into the `versions` table
that UpdateManager.kt polls.

Usage:
  .\scripts\publish-update.ps1 -ApkPath "app\build\outputs\apk\release\app-release.apk" -Version "2.1" -Description "Fixed crash on chat open"

Requires SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY in the repo-root .env
(already present there) — the service role key bypasses RLS so this script
can write to storage/the versions table even though regular app users can't.
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$Description = "",

    [string]$BucketName = "app-releases"
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 still defaults to TLS 1.0/1.1, which Supabase refuses —
# the failure surfaces as the unhelpful "The underlying connection was closed:
# An unexpected error occurred on a send." Force 1.2 before any request.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not (Test-Path $ApkPath)) {
    throw "APK not found: $ApkPath"
}

$envFile = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path $envFile)) {
    throw ".env not found at $envFile"
}
$envVars = @{}
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)\s*=\s*(.*)\s*$') {
        $envVars[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$supabaseUrl = $envVars["SUPABASE_URL"]
$serviceKey = $envVars["SUPABASE_SERVICE_ROLE_KEY"]
if (-not $supabaseUrl -or -not $serviceKey) {
    throw "SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing from .env"
}

$fileName = "app-$Version.apk"

Write-Host "Hashing $ApkPath ..."
$sha256 = (Get-FileHash -Path $ApkPath -Algorithm SHA256).Hash.ToLower()
Write-Host "SHA-256: $sha256"

# Supabase rejects sb_secret_* keys on requests that look browser-originated, and
# Invoke-RestMethod's default User-Agent starts with "Mozilla/5.0" — which trips that check
# with "Forbidden use of secret API key in browser". Identify as a CLI instead.
$UA = "neveroff-publish/1.0"

# Ensure the bucket exists (first run only — safe to ignore "already exists").
try {
    Invoke-RestMethod -Method Post -Uri "$supabaseUrl/storage/v1/bucket" `
        -Headers @{ Authorization = "Bearer $serviceKey"; apikey = $serviceKey } `
        -UserAgent $UA `
        -ContentType "application/json" `
        -Body (@{ id = $BucketName; name = $BucketName; public = $true } | ConvertTo-Json) | Out-Null
    Write-Host "Created bucket '$BucketName'."
} catch {
    if ($_.ErrorDetails.Message -notmatch "already exists") { throw }
}

Write-Host "Uploading $fileName to bucket '$BucketName' ..."
Invoke-RestMethod -Method Post -Uri "$supabaseUrl/storage/v1/object/$BucketName/$fileName" `
    -Headers @{
        Authorization = "Bearer $serviceKey"
        apikey        = $serviceKey
        "x-upsert"    = "true"
    } `
    -UserAgent $UA `
    -ContentType "application/vnd.android.package-archive" `
    -InFile $ApkPath | Out-Null

$publicUrl = "$supabaseUrl/storage/v1/object/public/$BucketName/$fileName"
Write-Host "Uploaded: $publicUrl"

Write-Host "Inserting versions row ..."
$body = @{
    version     = $Version
    description = $Description
    google_link = $publicUrl
    sha256      = $sha256
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$supabaseUrl/rest/v1/versions" `
    -Headers @{
        Authorization = "Bearer $serviceKey"
        apikey        = $serviceKey
        Prefer        = "return=minimal"
    } `
    -UserAgent $UA `
    -ContentType "application/json" `
    -Body $body | Out-Null

Write-Host ""
Write-Host "Release $Version published." -ForegroundColor Green
Write-Host "URL:    $publicUrl"
Write-Host "SHA256: $sha256"
