# SWIM DNOTAM Consumer -- Helm Chart

## Prerequisites

- Helm 3.x installed
- `kubectl` or `oc` CLI authenticated to your cluster
- Namespace `swim-demo` exists
- MongoDB deployed with `mongodb-credentials` secret
- Kafka and AMQP broker (Artemis) available

## Quick Start

### OpenShift / OpenShift Local (CRC)

```bash
# Install with default values
helm install swim-dnotam-consumer . -n swim-demo

# Or with custom values
helm install swim-dnotam-consumer . -n swim-demo -f my-values.yaml
```

### Kubernetes / minikube

This chart does not include Routes (OpenShift-specific) or Ingress resources.
The service is accessible via ClusterIP only. Use `kubectl port-forward` for local access:

```bash
helm install swim-dnotam-consumer . -n swim-demo

kubectl port-forward svc/swim-dnotam-consumer 8080:8080 -n swim-demo
```

## Customizing Values

Override any value at install time with `--set` or a custom values file:

```bash
# Change image tag
helm install swim-dnotam-consumer . -n swim-demo \
  --set image.tag=1.2.0

# Change replicas and resources
helm install swim-dnotam-consumer . -n swim-demo \
  --set replicas=3 \
  --set resources.requests.memory=512Mi \
  --set resources.limits.memory=1Gi

# Disable HPA
helm install swim-dnotam-consumer . -n swim-demo \
  --set hpa.enabled=false

# Disable ServiceMonitor (if Prometheus Operator is not installed)
helm install swim-dnotam-consumer . -n swim-demo \
  --set serviceMonitor.enabled=false
```

### Key Values

| Parameter | Default | Description |
|-----------|---------|-------------|
| `namespace` | `swim-demo` | Target namespace |
| `image.repository` | `quay.io/masales/swim-dnotam-consumer` | Container image |
| `image.tag` | `latest` | Image tag |
| `replicas` | `1` | Number of replicas |
| `resources.requests.memory` | `256Mi` | Memory request |
| `resources.limits.memory` | `512Mi` | Memory limit |
| `hpa.enabled` | `true` | Enable autoscaling |
| `hpa.maxReplicas` | `5` | Maximum replicas |
| `serviceMonitor.enabled` | `true` | Enable Prometheus metrics |

## Upgrade

```bash
helm upgrade swim-dnotam-consumer . -n swim-demo
```

## Uninstall

```bash
helm uninstall swim-dnotam-consumer -n swim-demo
```

## Platform Compatibility

| Resource | OpenShift | OpenShift Local | Kubernetes | minikube |
|----------|-----------|-----------------|------------|----------|
| Deployment | Yes | Yes | Yes | Yes |
| Service | Yes | Yes | Yes | Yes |
| ConfigMap | Yes | Yes | Yes | Yes |
| Secret | Yes | Yes | Yes | Yes |
| HPA | Yes | Yes | Yes | Yes |
| ServiceMonitor | Yes (1) | Yes (1) | Yes (1) | Yes (1) |

(1) Requires Prometheus Operator installed in the cluster.
