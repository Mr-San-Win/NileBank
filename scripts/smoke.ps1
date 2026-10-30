# Requires PowerShell 7. Creates fictional demo users and simulated transactions.
param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
function Assert-Equal($Actual, $Expected, [string]$Label) {
    if ([string]$Actual -ne [string]$Expected) { throw "$Label expected $Expected, received $Actual" }
}
function New-Browser { return @{ Session = [Microsoft.PowerShell.Commands.WebRequestSession]::new(); Csrf = $null } }
function Send-Request($Browser, [string]$Method, [string]$Path, $Body = $null, [string]$Key = '', [int]$Expected = 200) {
    $headers = @{}
    if ($Method -ne 'GET' -and $Browser.Csrf) { $headers[$Browser.Csrf.headerName] = $Browser.Csrf.token }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $parameters = @{ Uri = "$BaseUrl/api/v1$Path"; Method = $Method; WebSession = $Browser.Session; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 30 }
    if ($null -ne $Body) { $parameters.ContentType = 'application/json'; $parameters.Body = ($Body | ConvertTo-Json -Compress) }
    $response = Invoke-WebRequest @parameters
    Assert-Equal $response.StatusCode $Expected "$Method $Path status"
    if ($response.Content) { return $response.Content | ConvertFrom-Json }
}
function Refresh-Csrf($Browser) { $Browser.Csrf = Send-Request $Browser GET '/auth/csrf' }
function New-DemoAccount($Browser) {
    Refresh-Csrf $Browser
    $email = "smoke-$([guid]::NewGuid().ToString('N'))@example.test"
    $password = "Demo-$([guid]::NewGuid().ToString('N'))"
    $null = Send-Request $Browser POST '/auth/register' @{ fullName = 'Smoke Demo Student'; email = $email; password = $password } -Expected 201
    $null = Send-Request $Browser POST '/auth/login' @{ email = $email; password = $password }
    Refresh-Csrf $Browser
    return Send-Request $Browser POST '/accounts' -Expected 201
}
$sender = New-Browser
$recipient = New-Browser
$health = Send-Request $sender GET '/health'
Assert-Equal $health.status 'UP' 'Health'
$source = New-DemoAccount $sender
$destination = New-DemoAccount $recipient
Assert-Equal $source.balance '0.00' 'Initial balance'
$null = Send-Request $sender POST '/transactions/deposit' @{accountId=$source.id; amount='100.50'} -Expected 201
$null = Send-Request $sender POST '/transactions/withdraw' @{accountId=$source.id; amount='10.25'} -Expected 201
$payload = @{sourceAccountId=$source.id; recipientAccountNumber=$destination.accountNumber; amount='20.25'}
$key = [guid]::NewGuid().ToString()
$first = Send-Request $sender POST '/transfers' $payload -Key $key
$replay = Send-Request $sender POST '/transfers' $payload -Key $key
Assert-Equal $first.id $replay.id 'Idempotent replay'
$senderAccount = Send-Request $sender GET '/accounts/me'
$recipientAccount = Send-Request $recipient GET '/accounts/me'
Assert-Equal $senderAccount.balance '70.00' 'Sender balance'
Assert-Equal $recipientAccount.balance '20.25' 'Recipient balance'
$history = Send-Request $sender GET '/transactions'
Assert-Equal $history.totalElements 3 'Successful history count'
$null = Send-Request $recipient POST '/transactions/withdraw' @{accountId=$source.id; amount='1.00'} -Expected 404
$null = Send-Request $sender POST '/auth/logout' -Expected 204
$null = Send-Request $sender GET '/auth/me' -Expected 401
Write-Output 'PASS: Docker HTTP journey, CSRF/session login/logout, deposit, withdrawal, atomic transfer, idempotent replay, ownership, balances 70.00/20.25, history count 3.'

