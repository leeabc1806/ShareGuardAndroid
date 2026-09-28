param([string]$ServerUrl = 'http://127.0.0.1:8080', [switch]$CheckHealth)
$ErrorActionPreference = 'Stop'
$required = @('SUPABASE_DB_URL', 'SUPABASE_DB_USER', 'SUPABASE_DB_PASSWORD',
    'SUPABASE_JWKS_URI', 'TOSS_SECRET_KEY', 'TOSS_WEBHOOK_PATH_TOKEN',
    'PORTONE_API_SECRET', 'PORTONE_STORE_ID')
$missing = @()
foreach ($name in $required) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) {
        $missing += $name
        Write-Output "$name : MISSING"
    } else { Write-Output "$name : SET" }
}
if ($missing.Count -gt 0) { throw 'Required server environment values are missing. No values or secrets were printed.' }
if ($env:FLYWAY_ENABLED -eq 'false') { throw 'FLYWAY_ENABLED=false disables the required migrations.' }
if ($env:TOSS_WEBHOOK_PATH_TOKEN -eq 'change-me') { throw 'Replace the default webhook token.' }
Write-Output 'Environment names checked. This does not yet prove DB authentication or provider connectivity.'
if ($CheckHealth) {
    try {
        $health = Invoke-RestMethod -Uri ($ServerUrl.TrimEnd('/') + '/actuator/health') -TimeoutSec 10
        if ($health.status -ne 'UP') { throw 'Backend health is not UP.' }
        Write-Output 'Backend health: UP. Verify V1, V2 and V3 in flyway_schema_history using verify-migrations.sql.'
    } catch { throw 'Backend health verification failed. Check server startup and database credentials.' }
}
