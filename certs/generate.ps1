#Requires -Version 5.1
<#
.SYNOPSIS
    Generates local development certificates for swim-digital-notam-consumer.

.DESCRIPTION
    This project is self-contained: no dependency on swim-developer-tools.
    Uses mkcert, keytool, and openssl to generate all certificates needed
    for mTLS between the Quarkus dev profile and the validator's Artemis broker.

    Output (all files in certs\):
      ca.crt             CA certificate (mkcert root CA, PEM)
      broker.p12         Artemis broker keystore (PKCS12)
      ca-truststore.p12  Artemis CA truststore   (PKCS12) - verifies client certs
      validator.crt      Validator server cert   (PEM)    - HTTPS REST API
      validator.key      Validator server key    (PEM)    - HTTPS REST API
      keystore.p12       Consumer client keystore (PKCS12)
      keystore.jks       Consumer client keystore (JKS)   - used by Quarkus dev profile
      truststore.p12     Consumer CA truststore   (PKCS12)
      truststore.jks     Consumer CA truststore   (JKS)   - used by Quarkus dev profile

.NOTES
    Prerequisites:
      mkcert   choco install mkcert           | scoop install mkcert
      keytool  Bundled with JDK 21 (https://adoptium.net)
      openssl  choco install openssl          | scoop install openssl
               Also available via Git for Windows (usually already on PATH)

.EXAMPLE
    # Run from the project root:
    .\certs\generate.ps1
#>

$ErrorActionPreference = "Stop"

$ScriptDir    = Split-Path -Parent $MyInvocation.MyCommand.Path
$CertsDir     = $ScriptDir
$TmpDir       = Join-Path $CertsDir ".tmp"
$Password     = "changeit"

$BrokerSans = @(
    "localhost"
    "127.0.0.1"
    "::1"
    "dnotam-consumer-validator-artemis"
    "artemis.127.0.0.1.nip.io"
    "dnotam-consumer-validator-artemis.127.0.0.1.nip.io"
    "dnotam-consumer-validator-artemis.swim.lab"
)

$ValidatorSans = @(
    "localhost"
    "127.0.0.1"
    "::1"
    "dnotam-consumer-validator"
    "validator.127.0.0.1.nip.io"
    "dnotam-consumer-validator.127.0.0.1.nip.io"
    "dnotam-consumer-validator.swim.lab"
)

$ConsumerSans = @(
    "dnotam-consumer"
    "localhost"
    "127.0.0.1"
    "dnotam-consumer.127.0.0.1.nip.io"
    "dnotam-consumer.swim.lab"
)

# ─── Cleanup ──────────────────────────────────────────────────────────────────

@("ca.crt", "broker.p12", "ca-truststore.p12", "validator.crt", "validator.key",
  "keystore.p12", "keystore.jks", "truststore.p12", "truststore.jks") | ForEach-Object {
    $f = Join-Path $CertsDir $_
    if (Test-Path $f) { Remove-Item $f }
}

# ─── Prerequisites ────────────────────────────────────────────────────────────

function Assert-Command {
    param([string]$Name, [string]$Hint)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Write-Error "ERROR: '$Name' not found.`n       $Hint"
        exit 1
    }
}

Assert-Command "mkcert"  "Install: choco install mkcert  |  scoop install mkcert  |  https://github.com/FiloSottile/mkcert"
Assert-Command "keytool" "Install JDK 21: https://adoptium.net"
Assert-Command "openssl" "Install: choco install openssl  |  scoop install openssl  |  or use Git for Windows (includes openssl)"

# ─── Setup ────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== swim-digital-notam-consumer local PKI ===" -ForegroundColor Cyan
Write-Host ""

mkcert -install

if (Test-Path $TmpDir) { Remove-Item -Recurse -Force $TmpDir }
New-Item -ItemType Directory -Path $TmpDir | Out-Null

try {

    # CA certificate (mkcert root CA)
    $CaRoot = (mkcert -CAROOT).Trim()
    Copy-Item (Join-Path $CaRoot "rootCA.pem") (Join-Path $CertsDir "ca.crt")
    Write-Host "[CA] ca.crt  <-  $CaRoot\rootCA.pem"

    # ─── Artemis broker server certificate ───────────────────────────────────

    Write-Host ""
    Write-Host "[broker] Generating Artemis server certificate..."
    Write-Host "         SANs: $($BrokerSans -join ', ')"

    & mkcert `
        -cert-file (Join-Path $TmpDir "broker.crt") `
        -key-file  (Join-Path $TmpDir "broker.key") `
        @BrokerSans

    & openssl pkcs12 -export `
        -in       (Join-Path $TmpDir "broker.crt") `
        -inkey    (Join-Path $TmpDir "broker.key") `
        -certfile (Join-Path $CertsDir "ca.crt") `
        -out      (Join-Path $CertsDir "broker.p12") `
        -name     "broker" `
        -password "pass:$Password"

    Write-Host "[broker] broker.p12  (password: $Password)"

    # ─── Artemis CA truststore (verifies incoming client certs) ──────────────

    Write-Host ""
    Write-Host "[artemis-truststore] Creating CA truststore for Artemis..."

    $AtlasTs = Join-Path $CertsDir "ca-truststore.p12"

    & keytool -importcert -noprompt `
        -alias     "swim-ca" `
        -file      (Join-Path $CertsDir "ca.crt") `
        -keystore  $AtlasTs `
        -storetype PKCS12 `
        -storepass $Password

    Write-Host "[artemis-truststore] ca-truststore.p12  (password: $Password)"

    # ─── Validator server certificate (HTTPS REST API) ───────────────────────

    Write-Host ""
    Write-Host "[validator] Generating validator server certificate..."
    Write-Host "            SANs: $($ValidatorSans -join ', ')"

    & mkcert `
        -cert-file (Join-Path $CertsDir "validator.crt") `
        -key-file  (Join-Path $CertsDir "validator.key") `
        @ValidatorSans

    Write-Host "[validator] validator.crt, validator.key  (PEM)"

    # ─── Consumer client certificate ─────────────────────────────────────────

    Write-Host ""
    Write-Host "[consumer] Generating client certificate..."
    Write-Host "           SANs: $($ConsumerSans -join ', ')"

    & mkcert `
        -client `
        -cert-file (Join-Path $TmpDir "consumer.crt") `
        -key-file  (Join-Path $TmpDir "consumer.key") `
        @ConsumerSans

    & openssl pkcs12 -export `
        -in       (Join-Path $TmpDir "consumer.crt") `
        -inkey    (Join-Path $TmpDir "consumer.key") `
        -certfile (Join-Path $CertsDir "ca.crt") `
        -out      (Join-Path $CertsDir "keystore.p12") `
        -name     "dnotam-consumer" `
        -password "pass:$Password"

    Write-Host "[consumer] keystore.p12  (PKCS12, password: $Password)"

    # JKS keystore (Quarkus dev profile)
    $KsJks = Join-Path $CertsDir "keystore.jks"

    & keytool -importkeystore -noprompt `
        -srckeystore   (Join-Path $CertsDir "keystore.p12") `
        -srcstoretype  PKCS12 `
        -srcstorepass  $Password `
        -destkeystore  $KsJks `
        -deststoretype JKS `
        -deststorepass $Password

    Write-Host "[consumer] keystore.jks  (JKS, password: $Password)"

    # ─── Consumer truststore (PKCS12 + JKS) ──────────────────────────────────

    Write-Host ""
    Write-Host "[consumer-truststore] Creating consumer truststore..."

    $TsP12 = Join-Path $CertsDir "truststore.p12"

    & keytool -importcert -noprompt `
        -alias     "swim-ca" `
        -file      (Join-Path $CertsDir "ca.crt") `
        -keystore  $TsP12 `
        -storetype PKCS12 `
        -storepass $Password

    $TsJks = Join-Path $CertsDir "truststore.jks"

    & keytool -importcert -noprompt `
        -alias     "swim-ca" `
        -file      (Join-Path $CertsDir "ca.crt") `
        -keystore  $TsJks `
        -storetype JKS `
        -storepass $Password

    Write-Host "[consumer-truststore] truststore.p12, truststore.jks  (password: $Password)"

} finally {
    Remove-Item -Recurse -Force $TmpDir -ErrorAction SilentlyContinue
}

# ─── Summary ─────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green
Write-Host ""
Write-Host "  certs\"
Write-Host "  +-- ca.crt                 CA certificate (mkcert root CA)"
Write-Host "  +-- broker.p12             Artemis broker keystore  (PKCS12, password: $Password)"
Write-Host "  +-- ca-truststore.p12      Artemis CA truststore    (PKCS12, password: $Password)"
Write-Host "  +-- validator.crt          Validator server cert    (PEM)"
Write-Host "  +-- validator.key          Validator server key     (PEM)"
Write-Host "  +-- keystore.p12           Consumer client keystore (PKCS12, password: $Password)"
Write-Host "  +-- keystore.jks           Consumer client keystore (JKS,    password: $Password)"
Write-Host "  +-- truststore.p12         Consumer CA truststore   (PKCS12, password: $Password)"
Write-Host "  +-- truststore.jks         Consumer CA truststore   (JKS,    password: $Password)"
Write-Host ""
Write-Host "  Broker SANs    : $($BrokerSans -join ', ')"
Write-Host "  Validator SANs : $($ValidatorSans -join ', ')"
Write-Host "  Consumer SANs  : $($ConsumerSans -join ', ')"
Write-Host ""
Write-Host "  All keystore / truststore password: $Password"
Write-Host ""
