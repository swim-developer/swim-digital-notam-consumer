#!/bin/bash
# Generates local development certificates for swim-digital-notam-consumer.
#
# This project is self-contained: no dependency on swim-developer-tools.
#
# Output (all files in certs/):
#   ca.crt             CA certificate (mkcert root CA, PEM)
#   broker.p12         Artemis broker keystore (PKCS12)
#   ca-truststore.p12  Artemis CA truststore   (PKCS12) — verifies client certs
#   keystore.p12       Consumer client keystore (PKCS12)
#   keystore.jks       Consumer client keystore (JKS)   — used by Quarkus dev profile
#   truststore.p12     Consumer CA truststore   (PKCS12)
#   truststore.jks     Consumer CA truststore   (JKS)   — used by Quarkus dev profile
#
# Prerequisites:
#   mkcert   https://github.com/FiloSottile/mkcert  (brew install mkcert)
#   keytool  Bundled with JDK 21
#   openssl  Pre-installed on macOS / most Linux distros
#
# Usage (run from the project root):
#   ./certs/generate.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CERTS_DIR="${SCRIPT_DIR}"
TMP_DIR="${CERTS_DIR}/.tmp"
PASSWORD="changeit"

# SANs for the Artemis broker server certificate.
# nip.io resolves embedded IPs automatically:
#   artemis.127.0.0.1.nip.io → 127.0.0.1
# This avoids /etc/hosts entries for local development.
BROKER_SANS=(
    "localhost"
    "127.0.0.1"
    "::1"
    "dnotam-consumer-validator-artemis"
    "artemis.127.0.0.1.nip.io"
    "dnotam-consumer-validator-artemis.127.0.0.1.nip.io"
    "dnotam-consumer-validator-artemis.swim.lab"
)

# SANs for the validator server certificate.
# Used for HTTPS on the validator REST API (Subscription Manager mock).
VALIDATOR_SANS=(
    "localhost"
    "127.0.0.1"
    "::1"
    "dnotam-consumer-validator"
    "validator.127.0.0.1.nip.io"
    "dnotam-consumer-validator.127.0.0.1.nip.io"
    "dnotam-consumer-validator.swim.lab"
)

# SANs for the consumer client certificate.
# Used for mTLS identification when the consumer connects to the broker.
CONSUMER_SANS=(
    "dnotam-consumer"
    "localhost"
    "127.0.0.1"
    "dnotam-consumer.127.0.0.1.nip.io"
    "dnotam-consumer.swim.lab"
)

# ─── Cleanup ──────────────────────────────────────────────────────────────────

for f in ca.crt broker.p12 ca-truststore.p12 validator.crt validator.key \
          keystore.p12 keystore.jks truststore.p12 truststore.jks; do
    rm -f "${CERTS_DIR}/${f}"
done

# ─── Prerequisites ────────────────────────────────────────────────────────────

check_command() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "ERROR: '$1' not found."
        echo "       $2"
        exit 1
    }
}

check_command mkcert  "Install: brew install mkcert  |  https://github.com/FiloSottile/mkcert"
check_command keytool "Install JDK 21: https://adoptium.net"
check_command openssl "Install: brew install openssl"

# ─── Setup ────────────────────────────────────────────────────────────────────

echo ""
echo "=== swim-digital-notam-consumer local PKI ==="
echo ""

mkcert -install

mkdir -p "${TMP_DIR}"
trap 'rm -rf "${TMP_DIR}"' EXIT

# CA certificate (mkcert root CA, shared across all local projects)
CA_ROOT="$(mkcert -CAROOT)"
cp "${CA_ROOT}/rootCA.pem" "${CERTS_DIR}/ca.crt"
echo "[CA] ca.crt  ←  $(mkcert -CAROOT)/rootCA.pem"

# ─── Artemis broker server certificate ───────────────────────────────────────

echo ""
echo "[broker] Generating Artemis server certificate..."
echo "         SANs: ${BROKER_SANS[*]}"

mkcert \
    -cert-file "${TMP_DIR}/broker.crt" \
    -key-file  "${TMP_DIR}/broker.key" \
    "${BROKER_SANS[@]}"

openssl pkcs12 -export \
    -in      "${TMP_DIR}/broker.crt" \
    -inkey   "${TMP_DIR}/broker.key" \
    -certfile "${CERTS_DIR}/ca.crt" \
    -out     "${CERTS_DIR}/broker.p12" \
    -name    broker \
    -password "pass:${PASSWORD}"

chmod 644 "${CERTS_DIR}/broker.p12"
echo "[broker] broker.p12  (keystore, password: ${PASSWORD})"

# ─── Artemis CA truststore (verifies incoming client certs) ──────────────────

echo ""
echo "[artemis-truststore] Creating CA truststore for Artemis..."

keytool -importcert -noprompt \
    -alias      swim-ca \
    -file       "${CERTS_DIR}/ca.crt" \
    -keystore   "${CERTS_DIR}/ca-truststore.p12" \
    -storetype  PKCS12 \
    -storepass  "${PASSWORD}"

chmod 644 "${CERTS_DIR}/ca-truststore.p12"
echo "[artemis-truststore] ca-truststore.p12  (password: ${PASSWORD})"

# ─── Validator server certificate (HTTPS REST API) ───────────────────────────

echo ""
echo "[validator] Generating validator server certificate..."
echo "            SANs: ${VALIDATOR_SANS[*]}"

mkcert \
    -cert-file "${CERTS_DIR}/validator.crt" \
    -key-file  "${CERTS_DIR}/validator.key" \
    "${VALIDATOR_SANS[@]}"

chmod 644 "${CERTS_DIR}/validator.crt" "${CERTS_DIR}/validator.key"
echo "[validator] validator.crt, validator.key  (PEM)"

# ─── Consumer client certificate ─────────────────────────────────────────────

echo ""
echo "[consumer] Generating client certificate..."
echo "           SANs: ${CONSUMER_SANS[*]}"

mkcert \
    -client \
    -cert-file "${TMP_DIR}/consumer.crt" \
    -key-file  "${TMP_DIR}/consumer.key" \
    "${CONSUMER_SANS[@]}"

# PKCS12 keystore
openssl pkcs12 -export \
    -in      "${TMP_DIR}/consumer.crt" \
    -inkey   "${TMP_DIR}/consumer.key" \
    -certfile "${CERTS_DIR}/ca.crt" \
    -out     "${CERTS_DIR}/keystore.p12" \
    -name    dnotam-consumer \
    -password "pass:${PASSWORD}"

echo "[consumer] keystore.p12  (PKCS12, password: ${PASSWORD})"

# JKS keystore (Quarkus dev profile uses JKS)
keytool -importkeystore -noprompt \
    -srckeystore  "${CERTS_DIR}/keystore.p12" \
    -srcstoretype PKCS12 \
    -srcstorepass "${PASSWORD}" \
    -destkeystore "${CERTS_DIR}/keystore.jks" \
    -deststoretype JKS \
    -deststorepass "${PASSWORD}"

echo "[consumer] keystore.jks  (JKS, password: ${PASSWORD})"

# ─── Consumer truststore (PKCS12 + JKS) ──────────────────────────────────────

echo ""
echo "[consumer-truststore] Creating consumer truststore..."

keytool -importcert -noprompt \
    -alias      swim-ca \
    -file       "${CERTS_DIR}/ca.crt" \
    -keystore   "${CERTS_DIR}/truststore.p12" \
    -storetype  PKCS12 \
    -storepass  "${PASSWORD}"

keytool -importcert -noprompt \
    -alias      swim-ca \
    -file       "${CERTS_DIR}/ca.crt" \
    -keystore   "${CERTS_DIR}/truststore.jks" \
    -storetype  JKS \
    -storepass  "${PASSWORD}"

echo "[consumer-truststore] truststore.p12, truststore.jks  (password: ${PASSWORD})"

# ─── Summary ─────────────────────────────────────────────────────────────────

cat <<SUMMARY

=== Done ===

  certs/
  ├── ca.crt                 CA certificate (mkcert root CA)
  ├── broker.p12             Artemis broker keystore  (PKCS12, password: ${PASSWORD})
  ├── ca-truststore.p12      Artemis CA truststore    (PKCS12, password: ${PASSWORD})
  ├── validator.crt          Validator server cert    (PEM)
  ├── validator.key          Validator server key     (PEM)
  ├── keystore.p12           Consumer client keystore (PKCS12, password: ${PASSWORD})
  ├── keystore.jks           Consumer client keystore (JKS,    password: ${PASSWORD})
  ├── truststore.p12         Consumer CA truststore   (PKCS12, password: ${PASSWORD})
  └── truststore.jks         Consumer CA truststore   (JKS,    password: ${PASSWORD})

  Broker SANs    : ${BROKER_SANS[*]}
  Validator SANs : ${VALIDATOR_SANS[*]}
  Consumer SANs  : ${CONSUMER_SANS[*]}

  All keystore / truststore password: ${PASSWORD}

SUMMARY
