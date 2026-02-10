# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this directory.

## Purpose

This lab tests Lettuce Redis client behavior during ElastiCache failover events, measuring connection recovery time, operation failures, and data loss patterns across different Lettuce configurations.

## Build Commands

### Infrastructure (Pulumi Go)
```bash
cd infrastructure
go mod tidy                    # Download dependencies
pulumi preview                 # Preview changes
pulumi up                      # Deploy infrastructure
pulumi destroy                 # Tear down infrastructure
```

### Java Applications (Spring Boot)
```bash
# failover-app
cd failover-app
mvn clean package -DskipTests  # Build JAR
mvn spring-boot:run            # Run locally (requires Redis endpoint)

# failover-controller
cd failover-controller
mvn clean package -DskipTests  # Build JAR
mvn spring-boot:run            # Run locally
```

### Docker Images
```bash
cd failover-app
docker build -t failover-app:latest .

cd failover-controller
docker build -t failover-controller:latest .
```

### Kubernetes Deployment
```bash
cd k8s
kubectl apply -f namespace.yaml
kubectl apply -f configmaps/
kubectl apply -f deployments/
kubectl apply -f services/
```

## Architecture Overview

```
├── infrastructure/      # Pulumi Go - creates EKS + ElastiCache (3 shards x 1 replica)
├── failover-app/        # Spring Boot - producer/consumer workloads with Lettuce
├── failover-controller/ # Spring Boot - REST API to trigger AWS TestFailover
└── k8s/                 # Kubernetes manifests with AZ-aware scheduling
```

### Key Design Decisions

1. **Lettuce Profiles** (`LettuceConfig.java`): Three configurable topology refresh strategies:
   - `aggressive`: 10s refresh, all adaptive triggers
   - `conservative`: 60s refresh, MOVED redirect only
   - `aws-recommended`: 30s refresh, all adaptive triggers (default)

2. **Workload Separation**: Producer and consumer run as separate pods to isolate failure analysis and enable AZ-aware testing.

3. **Metrics Collection** (`FailoverMetrics.java`): Custom Micrometer metrics exported to CloudWatch namespace `FailoverLab`.

4. **Environment-driven Configuration**: All runtime behavior controlled via ConfigMaps and environment variables (`WORKLOAD_MODE`, `LETTUCE_PROFILE`, `OPS_PER_SECOND`).

## Key Files

| Purpose | File |
|---------|------|
| Lettuce client configuration | `failover-app/.../config/LettuceConfig.java` |
| Failover metrics tracking | `failover-app/.../metrics/FailoverMetrics.java` |
| Connection event monitoring | `failover-app/.../monitor/ConnectionMonitor.java` |
| AWS TestFailover API | `failover-controller/.../FailoverController.java` |
| ElastiCache cluster setup | `infrastructure/pkg/elasticache.go` |
| CloudWatch dashboard | `infrastructure/pkg/monitoring.go` |
