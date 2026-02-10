# Lettuce Failover Lab

This lab tests Lettuce Redis client behavior during ElastiCache failover events. It provides empirical data on how different Lettuce configurations perform during failover, helping teams choose optimal settings for production workloads.

## Architecture

```
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
│  Producer Pod   │   │  Consumer Pod   │   │  Consumer Pod   │
│    (AZ-a)       │   │    (AZ-b)       │   │    (AZ-c)       │
└────────┬────────┘   └────────┬────────┘   └────────┬────────┘
         │                     │                     │
         └─────────────────────┼─────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │  ElastiCache Redis  │
                    │   (3 shards x 2)    │
                    └─────────────────────┘
```

### Components

- **Infrastructure (Pulumi Go)**: EKS cluster + ElastiCache Redis cluster (3 shards, 1 replica each)
- **Failover App (Spring Boot)**: Producer/Consumer workloads with configurable Lettuce profiles
- **Failover Controller (Spring Boot)**: REST API for triggering and monitoring failovers
- **Kubernetes Manifests**: Deployment configurations with AZ-aware scheduling

### Infrastructure Configuration

| Component | Configuration |
|-----------|--------------|
| **EKS Cluster** | Kubernetes 1.32, Bottlerocket OS |
| **EKS Nodes** | m7g.large (Graviton3 ARM64), 3 nodes across AZs |
| **ElastiCache** | Redis 7.1, 3 shards x 1 replica (6 nodes) |
| **ElastiCache Nodes** | cache.r7g.large (Graviton) |
| **Docker Images** | amazoncorretto:17 (multi-arch ARM64/x86_64) |

## Prerequisites

- AWS CLI configured with appropriate credentials
- Pulumi CLI installed
- kubectl installed
- Docker installed
- Go 1.24+
- Java 17 (Amazon Corretto) and Maven
- Spring Boot 3.2.4

## Quick Start

### 1. Deploy Infrastructure

The infrastructure is split into two Pulumi stacks for better lifecycle management:

```bash
# Step 1: Deploy network stack (security groups)
cd infrastructure/network
# go mod tidy                                      # only needed if modifying Go code
pulumi stack init dev
pulumi config set vpcId vpc-xxxxxxxx
pulumi up

# Note the outputs:
# eksSecurityGroupId: sg-xxxxxxxx
# redisSecurityGroupId: sg-yyyyyyyy

# Step 2: Deploy lab stack (EKS + ElastiCache)
cd ../lab
# go mod tidy                                      # only needed if modifying Go code
pulumi stack init dev
pulumi config set vpcId vpc-xxxxxxxx
pulumi config set eksSecurityGroupId sg-xxxxxxxx      # from network stack
pulumi config set redisSecurityGroupId sg-yyyyyyyy   # from network stack
pulumi config set privateSubnetIds '["subnet-1", "subnet-2", "subnet-3"]'
pulumi up
```

### 2. Build and Push Docker Images

```bash
# Get ECR repository (create one if needed)
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com

# Build and push redis-failover-app
cd redis-failover-app
docker build -t <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/redis-failover-app:latest .
docker push <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/redis-failover-app:latest

# Build and push redis-failover-controller
cd ../redis-failover-controller
docker build -t <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/redis-failover-controller:latest .
docker push <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/redis-failover-controller:latest
```

### 3. Configure Kubernetes

```bash
# Get kubeconfig
aws eks update-kubeconfig --name redis-redis-failover-lab-eks --region us-east-1

# Update Redis endpoint ConfigMap with actual endpoint
# Get endpoint from lab stack output
cd infrastructure/lab
REDIS_ENDPOINT=$(pulumi stack output redisClusterEndpoint)
kubectl create configmap redis-endpoint -n redis-failover-lab \
  --from-literal=REDIS_CLUSTER_ENDPOINT="${REDIS_ENDPOINT}:6379" \
  --from-literal=ELASTICACHE_REPLICATION_GROUP_ID="redis-failover-lab" \
  --dry-run=client -o yaml | kubectl apply -f -
```

### 4. Deploy Applications

```bash
cd k8s

# Create namespace
kubectl apply -f namespace.yaml

# Apply ConfigMaps
kubectl apply -f configmaps/

# Apply Deployments
kubectl apply -f deployments/

# Apply Services
kubectl apply -f services/
```

### 5. Verify Deployment

```bash
# Check pods
kubectl get pods -n redis-failover-lab

# Check logs
kubectl logs -f deployment/redis-failover-producer -n redis-failover-lab
kubectl logs -f deployment/redis-failover-consumer -n redis-failover-lab
```

## Running Failover Tests

### Trigger a Failover

```bash
# Port-forward to controller
kubectl port-forward svc/redis-failover-controller 8080:8080 -n redis-failover-lab &

# Trigger failover for shard 1
curl -X POST http://localhost:8080/api/failover/1

# Check failover status
curl http://localhost:8080/api/status

# Get failover metrics
curl http://localhost:8080/api/metrics
```

### Monitor Metrics

View the CloudWatch dashboard "RedisFailoverLab-Dashboard" for:
- Connection drop duration
- Topology refresh count
- Operations failed during failover
- Operation latency (P50, P99)
- Pub/Sub message loss
- Stream consumer lag

## Configuration

### Lettuce Profiles

| Profile | Periodic Refresh | Adaptive Triggers | Use Case |
|---------|-----------------|-------------------|----------|
| `aggressive` | 10s | All | Low-latency apps needing fast failover |
| `conservative` | 60s | MOVED only | Stable clusters with few topology changes |
| `aws-recommended` | 30s | All | Most ElastiCache deployments (default) |

To change the profile, update `k8s/configmaps/lettuce-profiles.yaml`:

```yaml
data:
  LETTUCE_PROFILE: "aggressive"
```

### Lettuce Version (6.3.2 vs 6.3.1)

This lab uses Lettuce 6.3.2. The version difference from 6.3.1 does **not significantly impact failover test results**.

| Change in 6.3.2 | Impact on Lab |
|-----------------|---------------|
| Protected mode reconnect fix | None - ElastiCache doesn't use protected mode |
| HashedWheelTimer for timeouts | Minor - slightly more consistent timeout handling |
| ConcurrentLinkedQueue optimization | Minor - performance improvement only |
| ByteBuf encoding optimization | Minor - ~10x encoding performance, no failover impact |

Core failover mechanisms (topology refresh, adaptive triggers, reconnection logic) are identical between 6.3.1 and 6.3.2.

### Workload Configuration

Configure via `k8s/configmaps/workload-config.yaml`:

| Setting | Description | Default |
|---------|-------------|---------|
| `WORKLOAD_MODE` | producer, consumer, or both | both |
| `WORKLOAD_TYPES` | getset, pubsub, streams | all |
| `OPS_PER_SECOND` | Operations per second | 100 |
| `MESSAGE_SIZE_BYTES` | Payload size | 256 |

## Key Metrics

| Metric | Description |
|--------|-------------|
| `connection.drop.duration.ms` | Time from disconnect to reconnect |
| `topology.refresh.count` | Cluster discovery attempts |
| `operations.failed.during.failover` | Commands lost during failover |
| `operations.latency.p99.ms` | 99th percentile latency |
| `pubsub.message.loss.count` | Messages published but not received |
| `streams.lag.ms` | Consumer group lag during failover |
| `getset.sequence.gaps` | Detected gaps in sequence numbers |

## Cleanup

```bash
# Delete Kubernetes resources
kubectl delete namespace redis-failover-lab

# Destroy lab stack first (EKS + ElastiCache)
cd infrastructure/lab
pulumi destroy

# Then destroy network stack (security groups)
cd ../network
pulumi destroy
```

## Project Structure

```
lettuce-redis-failover-lab/
├── infrastructure/
│   ├── network/              # Stack 1: Shared security groups
│   │   ├── main.go
│   │   ├── go.mod
│   │   └── Pulumi.yaml
│   └── lab/                  # Stack 2: EKS + ElastiCache
│       ├── main.go
│       ├── go.mod
│       ├── Pulumi.yaml
│       └── pkg/
│           ├── eks.go
│           ├── elasticache.go
│           └── monitoring.go
├── redis-failover-app/             # Spring Boot workload app
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/.../
│       ├── config/LettuceConfig.java
│       ├── workload/
│       ├── monitor/
│       └── metrics/
├── redis-failover-controller/      # Failover trigger API
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/.../
└── k8s/                      # Kubernetes manifests
    ├── namespace.yaml
    ├── configmaps/
    ├── deployments/
    └── services/
```
