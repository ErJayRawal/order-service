param(
    [string]$OrderServiceUrl = "http://localhost:8080",
    [string]$KeycloakUrl = "http://localhost:8180",
    [string]$Namespace = "e-commerce-platform"
)

$ErrorActionPreference = "Stop"

function Get-HttpStatus {
    param(
        [string]$Url,
        [string]$Method,
        [hashtable]$Headers,
        [string]$Body
    )

    try {
        $request = @{
            Uri = $Url
            Method = $Method
            Headers = $Headers
        }
        if (-not [string]::IsNullOrEmpty($Body)) {
            $request.ContentType = "application/json"
            $request.Body = $Body
        }
        $request.UseBasicParsing = $true
        Invoke-WebRequest @request | Out-Null
        return 200
    }
    catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-TokenClaims {
    param([string]$Token)
    $payload = $Token.Split(".")[1].Replace("-", "+").Replace("_", "/")
    switch ($payload.Length % 4) {
        2 { $payload += "==" }
        3 { $payload += "=" }
    }
    return ConvertFrom-Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)))
}

function Assert-Status {
    param(
        [string]$Name,
        [int]$Expected,
        [int]$Actual
    )
    if ($Actual -ne $Expected) {
        throw "$Name expected HTTP $Expected but received HTTP $Actual"
    }
    Write-Output "$Name : HTTP $Actual"
}

function New-TestUser {
    param(
        [string]$Realm,
        [string]$Username,
        [string]$Password,
        [hashtable]$Headers
    )

    $testUser = @{
        username = $Username
        enabled = $true
        emailVerified = $true
        firstName = "Order"
        lastName = "Smoke"
        email = "$Username@example.invalid"
        requiredActions = @()
    } | ConvertTo-Json
    Invoke-RestMethod `
        -Method Post `
        -Uri "$KeycloakUrl/admin/realms/$Realm/users" `
        -Headers $Headers `
        -ContentType "application/json" `
        -Body $testUser | Out-Null

    $user = (Invoke-RestMethod `
        -Uri "$KeycloakUrl/admin/realms/$Realm/users?username=$Username&exact=true" `
        -Headers $Headers)[0]
    if (-not $user.id) {
        throw "Keycloak did not return the created smoke-test user."
    }

    $credential = @{
        type = "password"
        value = $Password
        temporary = $false
    } | ConvertTo-Json
    Invoke-RestMethod `
        -Method Put `
        -Uri "$KeycloakUrl/admin/realms/$Realm/users/$($user.id)/reset-password" `
        -Headers $Headers `
        -ContentType "application/json" `
        -Body $credential | Out-Null

    return $user.id
}

$secretJson = kubectl get secret keycloak-admin-credentials --namespace $Namespace -o json
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read the local Keycloak admin Secret in namespace '$Namespace'."
}
$adminSecret = $secretJson | ConvertFrom-Json
$encodedAdminPassword = $adminSecret.data.password
if (-not $encodedAdminPassword) {
    throw "The local Keycloak admin Secret does not contain its expected password key."
}
$adminPassword = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String($encodedAdminPassword)
)
$adminTokenResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
    -ContentType "application/x-www-form-urlencoded" `
    -Body @{
        grant_type = "password"
        client_id = "admin-cli"
        username = "admin"
        password = $adminPassword
    }
$adminHeaders = @{ Authorization = "Bearer $($adminTokenResponse.access_token)" }
$suffix = [Guid]::NewGuid().ToString("N")
$username = "order-smoke-$suffix"
$otherUsername = "order-smoke-other-$suffix"
$passwordBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($passwordBytes)
$testPassword = [Convert]::ToBase64String($passwordBytes)
$realm = "orders"
$testClientId = "order-service-smoke-$suffix"
$createdUserIds = [System.Collections.Generic.List[string]]::new()
$createdClientId = $null
$failure = $null
$cleanupFailures = [System.Collections.Generic.List[string]]::new()

try {
    $testClient = @{
        clientId = $testClientId
        enabled = $true
        publicClient = $true
        standardFlowEnabled = $false
        directAccessGrantsEnabled = $true
        serviceAccountsEnabled = $false
    } | ConvertTo-Json
    Invoke-RestMethod `
        -Method Post `
        -Uri "$KeycloakUrl/admin/realms/$realm/clients" `
        -Headers $adminHeaders `
        -ContentType "application/json" `
        -Body $testClient | Out-Null
    $client = (Invoke-RestMethod `
        -Uri "$KeycloakUrl/admin/realms/$realm/clients?clientId=$testClientId" `
        -Headers $adminHeaders)[0]
    $createdClientId = $client.id
    if (-not $createdClientId) {
        throw "Keycloak did not return the created smoke-test client."
    }

    $createdUserIds.Add((New-TestUser $realm $username $testPassword $adminHeaders))
    $createdUserIds.Add((New-TestUser $realm $otherUsername $testPassword $adminHeaders))

    $tokenResponse = Invoke-RestMethod `
        -Method Post `
        -Uri "$KeycloakUrl/realms/$realm/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "password"
            client_id = "order-service-local"
            username = $username
            password = $testPassword
            scope = "openid"
        }
    $token = $tokenResponse.access_token
    $claims = Get-TokenClaims $token
    if ($claims.iss -ne "$KeycloakUrl/realms/$realm") {
        throw "The issued test token has an unexpected issuer."
    }
    if ([string]::IsNullOrWhiteSpace($claims.sub)) {
        throw "The issued test token has no subject."
    }
    if (@($claims.aud) -notcontains "order-service") {
        throw "The issued test token has no order-service audience."
    }

    $ordersUrl = "$OrderServiceUrl/api/v1/orders"
    $noTokenStatus = Get-HttpStatus $ordersUrl "GET" @{} $null
    Assert-Status "Request without a token" 401 $noTokenStatus

    $validHeaders = @{ Authorization = "Bearer $token" }
    $tokenParts = $token.Split(".")
    $signature = $tokenParts[2]
    $replacement = if ($signature.EndsWith("A")) { "B" } else { "A" }
    $badSignature = "$($tokenParts[0]).$($tokenParts[1]).$($signature.Substring(0, $signature.Length - 1))$replacement"
    $badSignatureStatus = Get-HttpStatus $ordersUrl "GET" @{
        Authorization = "Bearer $badSignature"
    } $null
    Assert-Status "Token with modified signature" 401 $badSignatureStatus

    $otherClientToken = Invoke-RestMethod `
        -Method Post `
        -Uri "$KeycloakUrl/realms/$realm/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "password"
            client_id = $testClientId
            username = $username
            password = $testPassword
            scope = "openid"
        }
    $otherClientClaims = Get-TokenClaims $otherClientToken.access_token
    if (@($otherClientClaims.aud) -contains "order-service") {
        throw "The negative-test client unexpectedly received the order-service audience."
    }
    $wrongAudienceStatus = Get-HttpStatus $ordersUrl "GET" @{
        Authorization = "Bearer $($otherClientToken.access_token)"
    } $null
    Assert-Status "Validly signed token without the required audience" 401 $wrongAudienceStatus

    $requestBody = @{
        customerId = "client-selected-customer"
        currency = "USD"
        items = @(@{
            productId = "smoke-test-product"
            quantity = 1
            unitPrice = 1.25
        })
    } | ConvertTo-Json -Depth 5
    $createdOrder = Invoke-RestMethod `
        -Method Post `
        -Uri $ordersUrl `
        -Headers $validHeaders `
        -ContentType "application/json" `
        -Body $requestBody
    if (-not $createdOrder.id) {
        throw "The authenticated customer could not create an order."
    }
    if ($createdOrder.customerId -ne $claims.sub) {
        throw "The request body was able to override the authenticated customer identity."
    }
    Write-Output "Authenticated create and request-body ownership protection : HTTP 201"

    $readback = Invoke-RestMethod `
        -Uri "$ordersUrl/$($createdOrder.id)" `
        -Headers $validHeaders
    if ($readback.id -ne $createdOrder.id) {
        throw "The authenticated customer could not retrieve the created order."
    }
    Write-Output "Authenticated owner read : HTTP 200"

    $otherTokenResponse = Invoke-RestMethod `
        -Method Post `
        -Uri "$KeycloakUrl/realms/$realm/protocol/openid-connect/token" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{
            grant_type = "password"
            client_id = "order-service-local"
            username = $otherUsername
            password = $testPassword
            scope = "openid"
        }
    $otherHeaders = @{ Authorization = "Bearer $($otherTokenResponse.access_token)" }
    $otherReadStatus = Get-HttpStatus "$ordersUrl/$($createdOrder.id)" "GET" $otherHeaders $null
    Assert-Status "Different customer reading the order" 404 $otherReadStatus
    $otherCancelStatus = Get-HttpStatus "$ordersUrl/$($createdOrder.id)/cancel" "POST" $otherHeaders $null
    Assert-Status "Different customer cancelling the order" 404 $otherCancelStatus

    Write-Output "OIDC smoke tests passed."
}
catch {
    $failure = $_
}
finally {
    foreach ($userId in $createdUserIds) {
        try {
            Invoke-RestMethod `
                -Method Delete `
                -Uri "$KeycloakUrl/admin/realms/$realm/users/$userId" `
                -Headers $adminHeaders | Out-Null
        }
        catch {
            $cleanupFailures.Add("Failed to remove temporary Keycloak user '$userId': $($_.Exception.Message)")
        }
    }
    if ($createdClientId) {
        try {
            Invoke-RestMethod `
                -Method Delete `
                -Uri "$KeycloakUrl/admin/realms/$realm/clients/$createdClientId" `
                -Headers $adminHeaders | Out-Null
        }
        catch {
            $cleanupFailures.Add("Failed to remove temporary Keycloak client '$createdClientId': $($_.Exception.Message)")
        }
    }
    $adminPassword = $null
    $adminTokenResponse = $null
    $testPassword = $null
    $token = $null
    $passwordBytes = $null
}

if ($failure) {
    if ($cleanupFailures.Count -gt 0) {
        throw "$($failure.Exception.Message) Cleanup errors: $($cleanupFailures -join '; ')"
    }
    throw $failure
}
if ($cleanupFailures.Count -gt 0) {
    throw "OIDC tests completed, but cleanup failed: $($cleanupFailures -join '; ')"
}
