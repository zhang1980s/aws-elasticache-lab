#!/bin/bash
set -e

# Lettuce Failover Lab Deployment Script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWS_REGION="${AWS_REGION:-us-east-1}"
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

echo "========================================="
echo "Lettuce Failover Lab Deployment"
echo "========================================="
echo "Region: ${AWS_REGION}"
echo "Account: ${AWS_ACCOUNT_ID}"
echo ""

# Function to check prerequisites
check_prerequisites() {
    echo "Checking prerequisites..."

    if ! command -v aws &> /dev/null; then
        echo "ERROR: AWS CLI is not installed"
        exit 1
    fi

    if ! command -v pulumi &> /dev/null; then
        echo "ERROR: Pulumi CLI is not installed"
        exit 1
    fi

    if ! command -v kubectl &> /dev/null; then
        echo "ERROR: kubectl is not installed"
        exit 1
    fi

    if ! command -v docker &> /dev/null; then
        echo "ERROR: Docker is not installed"
        exit 1
    fi

    echo "All prerequisites met!"
    echo ""
}

# Function to deploy infrastructure
deploy_infrastructure() {
    echo "========================================="
    echo "Step 1: Deploying Infrastructure"
    echo "========================================="

    cd "${SCRIPT_DIR}/infrastructure"

    # Check if Pulumi stack exists
    if ! pulumi stack ls 2>/dev/null | grep -q "dev"; then
        echo "Creating Pulumi stack..."
        pulumi stack init dev
    fi

    # Check for required config
    if ! pulumi config get vpcId &>/dev/null; then
        echo "ERROR: VPC ID not configured"
        echo "Run: pulumi config set vpcId vpc-xxxxxxxx"
        exit 1
    fi

    if ! pulumi config get privateSubnetIds &>/dev/null; then
        echo "ERROR: Private subnet IDs not configured"
        echo "Run: pulumi config set privateSubnetIds '[\"subnet-1\", \"subnet-2\", \"subnet-3\"]'"
        exit 1
    fi

    # Deploy
    pulumi up --yes

    # Get outputs
    EKS_CLUSTER_NAME=$(pulumi stack output eksClusterName)
    REDIS_ENDPOINT=$(pulumi stack output redisClusterEndpoint)
    REDIS_REPLICATION_GROUP=$(pulumi stack output redisReplicationGroupId)

    echo ""
    echo "Infrastructure deployed!"
    echo "EKS Cluster: ${EKS_CLUSTER_NAME}"
    echo "Redis Endpoint: ${REDIS_ENDPOINT}"
    echo ""

    cd "${SCRIPT_DIR}"
}

# Function to build and push Docker images
build_and_push_images() {
    echo "========================================="
    echo "Step 2: Building and Pushing Docker Images"
    echo "========================================="

    # Login to ECR
    aws ecr get-login-password --region ${AWS_REGION} | \
        docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

    # Create ECR repositories if they don't exist
    for repo in failover-app failover-controller; do
        aws ecr describe-repositories --repository-names ${repo} --region ${AWS_REGION} 2>/dev/null || \
            aws ecr create-repository --repository-name ${repo} --region ${AWS_REGION}
    done

    # Build and push failover-app
    echo "Building failover-app..."
    cd "${SCRIPT_DIR}/failover-app"
    docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/failover-app:latest .
    docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/failover-app:latest

    # Build and push failover-controller
    echo "Building failover-controller..."
    cd "${SCRIPT_DIR}/failover-controller"
    docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/failover-controller:latest .
    docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/failover-controller:latest

    echo ""
    echo "Docker images built and pushed!"
    echo ""

    cd "${SCRIPT_DIR}"
}

# Function to configure kubectl
configure_kubectl() {
    echo "========================================="
    echo "Step 3: Configuring kubectl"
    echo "========================================="

    cd "${SCRIPT_DIR}/infrastructure"
    EKS_CLUSTER_NAME=$(pulumi stack output eksClusterName)

    aws eks update-kubeconfig --name ${EKS_CLUSTER_NAME} --region ${AWS_REGION}

    echo "kubectl configured!"
    echo ""

    cd "${SCRIPT_DIR}"
}

# Function to deploy Kubernetes resources
deploy_kubernetes() {
    echo "========================================="
    echo "Step 4: Deploying Kubernetes Resources"
    echo "========================================="

    cd "${SCRIPT_DIR}/infrastructure"
    REDIS_ENDPOINT=$(pulumi stack output redisClusterEndpoint)
    REDIS_REPLICATION_GROUP=$(pulumi stack output redisReplicationGroupId)

    cd "${SCRIPT_DIR}/k8s"

    # Create namespace
    kubectl apply -f namespace.yaml

    # Update image references in deployments
    sed -i "s|failover-app:latest|${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/failover-app:latest|g" deployments/*.yaml
    sed -i "s|failover-controller:latest|${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/failover-controller:latest|g" deployments/*.yaml

    # Create Redis endpoint ConfigMap
    kubectl create configmap redis-endpoint -n failover-lab \
        --from-literal=REDIS_CLUSTER_ENDPOINT="${REDIS_ENDPOINT}:6379" \
        --from-literal=ELASTICACHE_REPLICATION_GROUP_ID="${REDIS_REPLICATION_GROUP}" \
        --dry-run=client -o yaml | kubectl apply -f -

    # Apply ConfigMaps
    kubectl apply -f configmaps/

    # Apply Deployments
    kubectl apply -f deployments/

    # Apply Services
    kubectl apply -f services/

    echo ""
    echo "Kubernetes resources deployed!"
    echo ""

    cd "${SCRIPT_DIR}"
}

# Function to verify deployment
verify_deployment() {
    echo "========================================="
    echo "Step 5: Verifying Deployment"
    echo "========================================="

    echo "Waiting for pods to be ready..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/part-of=lettuce-failover-lab -n failover-lab --timeout=300s || true

    echo ""
    echo "Pod status:"
    kubectl get pods -n failover-lab

    echo ""
    echo "Services:"
    kubectl get svc -n failover-lab

    echo ""
}

# Main deployment
main() {
    check_prerequisites

    case "${1:-all}" in
        infra)
            deploy_infrastructure
            ;;
        images)
            build_and_push_images
            ;;
        k8s)
            configure_kubectl
            deploy_kubernetes
            verify_deployment
            ;;
        all)
            deploy_infrastructure
            build_and_push_images
            configure_kubectl
            deploy_kubernetes
            verify_deployment
            ;;
        *)
            echo "Usage: $0 [infra|images|k8s|all]"
            exit 1
            ;;
    esac

    echo "========================================="
    echo "Deployment Complete!"
    echo "========================================="
    echo ""
    echo "To trigger a failover:"
    echo "  kubectl port-forward svc/failover-controller 8080:8080 -n failover-lab &"
    echo "  curl -X POST http://localhost:8080/api/failover/1"
    echo ""
    echo "To view logs:"
    echo "  kubectl logs -f deployment/failover-producer -n failover-lab"
    echo "  kubectl logs -f deployment/failover-consumer -n failover-lab"
    echo ""
}

main "$@"
