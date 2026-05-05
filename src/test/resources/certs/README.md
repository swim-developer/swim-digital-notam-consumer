# mTLS Certificates for Development

This directory contains mTLS certificates for local development and testing.

## Required Files

- `client-keystore.p12` - Client certificate and private key (PKCS#12 format)
- `truststore.p12` - Trusted CA certificates (PKCS#12 format)

## Security Notice

**These certificates are for DEVELOPMENT ONLY.**

For production deployments:
- Certificates are mounted via Kubernetes Secrets
- Path: `/secrets/` (configured in OpenShift manifests)
- Never commit production certificates to git

## Obtaining Certificates

Development certificates are generated using OpenShift cert-manager.
See: `/openshift/mockserver-artemis/create-amq-ssl-secrets.sh`

Production certificates are issued by EACP (European Aviation Common PKI).

