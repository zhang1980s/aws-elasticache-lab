# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this directory.

## Purpose

This lab tests Lettuce Redis client behavior during ElastiCache failover events, measuring connection recovery time, operation failures, and data loss patterns across different Lettuce configurations.

## Build Commands

### Infrastructure (Pulumi Go - Multi-Stack)
```bash
# Network stack (deploy first)
cd infrastructure/network
go mod tidy
pulumi up

# Lab stack (deploy second, uses network stack outputs)
cd infrastructure/lab
go mod tidy
pulumi up

# Teardown (reverse order)
cd infrastructure/lab && pulumi destroy
cd infrastructure/network && pulumi destroy
```

### Java Applications (Spring Boot)
```bash
# redis-failover-app
cd redis-failover-app
mvn clean package -DskipTests  # Build JAR
mvn spring-boot:run            # Run locally (requires Redis endpoint)

# redis-failover-controller
cd redis-failover-controller
mvn clean package -DskipTests  # Build JAR
mvn spring-boot:run            # Run locally
```

### Docker Images
```bash
cd redis-failover-app
docker build -t redis-failover-app:latest .

cd redis-failover-controller
docker build -t redis-failover-controller:latest .
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
├── infrastructure/
│   ├── network/         # Stack 1: Security groups (shared, rarely changes)
│   └── lab/             # Stack 2: EKS + ElastiCache (lab-specific)
├── redis-failover-app/        # Spring Boot - producer/consumer workloads with Lettuce
├── redis-failover-controller/ # Spring Boot - REST API to trigger AWS TestFailover
└── k8s/                 # Kubernetes manifests with AZ-aware scheduling
```

### Key Design Decisions

1. **Multi-Stack Infrastructure**: Network stack (security groups) separated from lab stack (EKS + ElastiCache) for independent lifecycle management and reusability.

2. **Lettuce Profiles** (`LettuceConfig.java`): Three configurable topology refresh strategies:
   - `aggressive`: 10s refresh, all adaptive triggers
   - `conservative`: 60s refresh, MOVED redirect only
   - `aws-recommended`: 30s refresh, all adaptive triggers (default)

3. **Workload Separation**: Producer and consumer run as separate pods to isolate failure analysis and enable AZ-aware testing.

4. **Metrics Collection** (`FailoverMetrics.java`): Custom Micrometer metrics exported to CloudWatch namespace `RedisFailoverLab`.

5. **Environment-driven Configuration**: All runtime behavior controlled via ConfigMaps and environment variables (`WORKLOAD_MODE`, `LETTUCE_PROFILE`, `OPS_PER_SECOND`).

## Key Files

| Purpose | File |
|---------|------|
| Security groups | `infrastructure/network/main.go` |
| EKS cluster setup | `infrastructure/lab/pkg/eks.go` |
| ElastiCache cluster setup | `infrastructure/lab/pkg/elasticache.go` |
| CloudWatch dashboard | `infrastructure/lab/pkg/monitoring.go` |
| Lettuce client configuration | `redis-failover-app/.../config/LettuceConfig.java` |
| Failover metrics tracking | `redis-failover-app/.../metrics/FailoverMetrics.java` |
| Connection event monitoring | `redis-failover-app/.../monitor/ConnectionMonitor.java` |
| AWS TestFailover API | `redis-failover-controller/.../FailoverController.java` |
