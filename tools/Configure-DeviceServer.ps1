param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [switch]$CheckHealth
)
$ErrorActionPreference = 'Stop'
$uri = [Uri]$BaseUrl
if (-not $uri.IsAbsoluteUri -or $uri.Scheme -notin @('http', 'https') -or
    $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
    throw 'Use an absolute HTTP(S) backend URL without credentials, query or fragment.'
}
if ($uri.Host -eq '10.0.2.2') { throw '10.0.2.2 is emulator-only. Use a LAN address or HTTPS host for a phone.' }
$config = Join-Path (Split-Path $PSScriptRoot -Parent) 'ShareGuardAndroid\local.properties'
if (-not (Test-Path -LiteralPath $config)) { throw 'Create ShareGuardAndroid/local.properties from the example first.' }
$base = $BaseUrl.Trim().TrimEnd('/') + '/'
$lines = [IO.File]::ReadAllLines($config) | Where-Object {
    $_ -notmatch '^\s*(SHAREGUARD_API_BASE_URL|TOSS_CONFIRM_URL)\s*='
}
$lines = @($lines) + @("SHAREGUARD_API_BASE_URL=$base", 'TOSS_CONFIRM_URL=')
[IO.File]::WriteAllLines($config, $lines, [Text.UTF8Encoding]::new($false))
Write-Output "Android base URL: $base"
Write-Output 'Payment approval URL is derived automatically. Rebuild and reinstall the APK.'
if ($CheckHealth) {
    try {
        $health = Invoke-RestMethod -Uri ($base + 'actuator/health') -TimeoutSec 10
        if ($health.status -ne 'UP') { throw 'Backend health is not UP.' }
        Write-Output 'Backend health: UP. Also open this URL from the phone to check its network route.'
    } catch {
        throw 'URL saved, but backend health could not be verified. Check backend startup, DB config and the private-network firewall rule.'
    }
}
