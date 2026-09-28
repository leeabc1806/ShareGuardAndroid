$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
$repoRoot = Split-Path $backendRoot -Parent
$containerName = 'shareguard-db-test-' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$previousUrl = $env:SHAREGUARD_TEST_DB_URL
$previousPassword = $env:SHAREGUARD_TEST_DB_PASSWORD
$started = $false
$pushed = $false
try {
    # Only a new, disposable, loopback-bound database is used. No team credentials.
    docker run --detach --rm --name $containerName --publish 127.0.0.1:55432:5432 `
        --env POSTGRES_PASSWORD=local-integration-test --env POSTGRES_DB=shareguard postgres:16-alpine
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the local test database. Check Docker and port 55432.' }
    $started = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        docker exec $containerName pg_isready -U postgres -d shareguard *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Local PostgreSQL did not become ready.' }
    $fixtures = @(
        (Join-Path $backendRoot 'src\test\resources\db\supabase-stubs.sql'),
        (Join-Path $repoRoot 'ShareGuardAndroid\database\schema.sql')
    )
    foreach ($fixture in $fixtures) {
        docker cp $fixture "${containerName}:/tmp/fixture.sql"
        if ($LASTEXITCODE -ne 0) { throw 'Could not copy test fixture.' }
        # Windows PowerShell turns redirected native stderr (including SQL NOTICEs)
        # into ErrorRecords. The native exit code is the failure signal here.
        $ErrorActionPreference = 'Continue'
        docker exec $containerName psql -U postgres -d shareguard -v ON_ERROR_STOP=1 -f /tmp/fixture.sql
        $fixtureExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($fixtureExit -ne 0) { throw 'Test fixture failed.' }
    }
    $env:SHAREGUARD_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/shareguard'
    $env:SHAREGUARD_TEST_DB_PASSWORD = 'local-integration-test'
    Push-Location -LiteralPath $backendRoot
    $pushed = $true
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat test bootJar --rerun-tasks --console=plain
    $testExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($testExit -ne 0) { throw 'Backend database integration tests failed.' }
} finally {
    if ($pushed) { Pop-Location }
    $env:SHAREGUARD_TEST_DB_URL = $previousUrl
    $env:SHAREGUARD_TEST_DB_PASSWORD = $previousPassword
    if ($started) { docker rm --force $containerName | Out-Null }
}
