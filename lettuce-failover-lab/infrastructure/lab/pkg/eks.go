package pkg

import (
	"encoding/json"

	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/iam"
	"github.com/pulumi/pulumi-eks/sdk/v2/go/eks"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type EKSResult struct {
	ClusterName     pulumi.StringOutput
	ClusterEndpoint pulumi.StringOutput
	Kubeconfig      pulumi.AnyOutput
}

// CreateEKSCluster creates an EKS cluster with managed node groups across 3 AZs
// eksSecurityGroupId is passed from the network stack but not directly used here
// (EKS component creates its own security groups)
func CreateEKSCluster(ctx *pulumi.Context, vpcId string, subnetIds []string, eksSecurityGroupId string) (*EKSResult, error) {
	// Create IAM role for EKS cluster
	clusterRole, err := iam.NewRole(ctx, "failover-lab-eks-cluster-role", &iam.RoleArgs{
		AssumeRolePolicy: pulumi.String(`{
			"Version": "2012-10-17",
			"Statement": [{
				"Effect": "Allow",
				"Principal": {
					"Service": "eks.amazonaws.com"
				},
				"Action": "sts:AssumeRole"
			}]
		}`),
		Tags: pulumi.StringMap{
			"Name": pulumi.String("failover-lab-eks-cluster-role"),
		},
	})
	if err != nil {
		return nil, err
	}

	// Attach required policies to cluster role
	_, err = iam.NewRolePolicyAttachment(ctx, "eks-cluster-policy", &iam.RolePolicyAttachmentArgs{
		Role:      clusterRole.Name,
		PolicyArn: pulumi.String("arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"),
	})
	if err != nil {
		return nil, err
	}

	// Create IAM role for worker nodes
	nodeRole, err := iam.NewRole(ctx, "failover-lab-eks-node-role", &iam.RoleArgs{
		AssumeRolePolicy: pulumi.String(`{
			"Version": "2012-10-17",
			"Statement": [{
				"Effect": "Allow",
				"Principal": {
					"Service": "ec2.amazonaws.com"
				},
				"Action": "sts:AssumeRole"
			}]
		}`),
		Tags: pulumi.StringMap{
			"Name": pulumi.String("failover-lab-eks-node-role"),
		},
	})
	if err != nil {
		return nil, err
	}

	// Attach required policies to node role
	nodePolicies := []string{
		"arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
		"arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
		"arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
	}

	for i, policyArn := range nodePolicies {
		_, err = iam.NewRolePolicyAttachment(ctx, "eks-node-policy-"+string(rune('0'+i)), &iam.RolePolicyAttachmentArgs{
			Role:      nodeRole.Name,
			PolicyArn: pulumi.String(policyArn),
		})
		if err != nil {
			return nil, err
		}
	}

	// Create custom policy for ElastiCache failover testing
	elasticachePolicy, err := iam.NewPolicy(ctx, "failover-lab-elasticache-policy", &iam.PolicyArgs{
		Description: pulumi.String("Policy for ElastiCache failover testing"),
		Policy: pulumi.String(`{
			"Version": "2012-10-17",
			"Statement": [
				{
					"Effect": "Allow",
					"Action": [
						"elasticache:TestFailover",
						"elasticache:DescribeReplicationGroups",
						"elasticache:DescribeCacheClusters",
						"elasticache:DescribeCacheSubnetGroups"
					],
					"Resource": "*"
				},
				{
					"Effect": "Allow",
					"Action": [
						"cloudwatch:PutMetricData",
						"cloudwatch:GetMetricData",
						"cloudwatch:ListMetrics"
					],
					"Resource": "*"
				},
				{
					"Effect": "Allow",
					"Action": [
						"logs:CreateLogGroup",
						"logs:CreateLogStream",
						"logs:PutLogEvents",
						"logs:DescribeLogGroups",
						"logs:DescribeLogStreams"
					],
					"Resource": "*"
				}
			]
		}`),
	})
	if err != nil {
		return nil, err
	}

	_, err = iam.NewRolePolicyAttachment(ctx, "eks-node-elasticache-policy", &iam.RolePolicyAttachmentArgs{
		Role:      nodeRole.Name,
		PolicyArn: elasticachePolicy.Arn,
	})
	if err != nil {
		return nil, err
	}

	// Create instance profile for nodes
	instanceProfile, err := iam.NewInstanceProfile(ctx, "failover-lab-eks-instance-profile", &iam.InstanceProfileArgs{
		Role: nodeRole.Name,
	})
	if err != nil {
		return nil, err
	}

	// Create EKS cluster using pulumi-eks component
	// Using Graviton3 (ARM64) with Bottlerocket OS for better price/performance
	// Kubernetes 1.32 - most mature version in standard support
	cluster, err := eks.NewCluster(ctx, "failover-lab-eks", &eks.ClusterArgs{
		VpcId:                        pulumi.String(vpcId),
		SubnetIds:                    pulumi.ToStringArray(subnetIds),
		Version:                      pulumi.String("1.32"),
		InstanceType:                 pulumi.String("m7g.large"),
		OperatingSystem:              eks.OperatingSystemBottlerocket,
		DesiredCapacity:              pulumi.Int(3),
		MinSize:                      pulumi.Int(3),
		MaxSize:                      pulumi.Int(5),
		NodeAssociatePublicIpAddress: pulumi.BoolRef(false),
		InstanceProfileName:          instanceProfile.Name,
		ServiceRole:                  clusterRole,
		CreateOidcProvider:           pulumi.Bool(true),
		Tags: pulumi.StringMap{
			"Name":        pulumi.String("failover-lab-eks"),
			"Environment": pulumi.String("testing"),
		},
	})
	if err != nil {
		return nil, err
	}

	return &EKSResult{
		ClusterName:     cluster.EksCluster.Name(),
		ClusterEndpoint: cluster.EksCluster.Endpoint(),
		Kubeconfig:      cluster.Kubeconfig,
	}, nil
}

// Helper to create JSON assume role policy
func createAssumeRolePolicy(service string) (string, error) {
	policy := map[string]interface{}{
		"Version": "2012-10-17",
		"Statement": []map[string]interface{}{
			{
				"Effect": "Allow",
				"Principal": map[string]string{
					"Service": service,
				},
				"Action": "sts:AssumeRole",
			},
		},
	}
	bytes, err := json.Marshal(policy)
	return string(bytes), err
}
