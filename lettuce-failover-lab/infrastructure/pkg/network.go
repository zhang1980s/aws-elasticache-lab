package pkg

import (
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/ec2"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type NetworkResult struct {
	EksSecurityGroup   *ec2.SecurityGroup
	RedisSecurityGroup *ec2.SecurityGroup
}

// CreateNetworkResources creates security groups for EKS and ElastiCache
func CreateNetworkResources(ctx *pulumi.Context, vpcId string, subnetIds []string) (*NetworkResult, error) {
	// Security group for EKS nodes
	eksSecurityGroup, err := ec2.NewSecurityGroup(ctx, "failover-lab-eks-sg", &ec2.SecurityGroupArgs{
		VpcId:       pulumi.String(vpcId),
		Description: pulumi.String("Security group for Failover Lab EKS nodes"),
		Tags: pulumi.StringMap{
			"Name": pulumi.String("failover-lab-eks-sg"),
		},
	})
	if err != nil {
		return nil, err
	}

	// Security group for ElastiCache Redis
	redisSecurityGroup, err := ec2.NewSecurityGroup(ctx, "failover-lab-redis-sg", &ec2.SecurityGroupArgs{
		VpcId:       pulumi.String(vpcId),
		Description: pulumi.String("Security group for Failover Lab ElastiCache Redis"),
		Tags: pulumi.StringMap{
			"Name": pulumi.String("failover-lab-redis-sg"),
		},
	})
	if err != nil {
		return nil, err
	}

	// Allow EKS nodes to connect to Redis on port 6379
	_, err = ec2.NewSecurityGroupRule(ctx, "eks-to-redis", &ec2.SecurityGroupRuleArgs{
		Type:                  pulumi.String("ingress"),
		FromPort:              pulumi.Int(6379),
		ToPort:                pulumi.Int(6379),
		Protocol:              pulumi.String("tcp"),
		SecurityGroupId:       redisSecurityGroup.ID(),
		SourceSecurityGroupId: eksSecurityGroup.ID(),
		Description:           pulumi.String("Allow EKS nodes to connect to Redis"),
	})
	if err != nil {
		return nil, err
	}

	// Allow all outbound from Redis security group
	_, err = ec2.NewSecurityGroupRule(ctx, "redis-egress", &ec2.SecurityGroupRuleArgs{
		Type:            pulumi.String("egress"),
		FromPort:        pulumi.Int(0),
		ToPort:          pulumi.Int(0),
		Protocol:        pulumi.String("-1"),
		SecurityGroupId: redisSecurityGroup.ID(),
		CidrBlocks:      pulumi.StringArray{pulumi.String("0.0.0.0/0")},
		Description:     pulumi.String("Allow all outbound traffic"),
	})
	if err != nil {
		return nil, err
	}

	// Allow all outbound from EKS security group
	_, err = ec2.NewSecurityGroupRule(ctx, "eks-egress", &ec2.SecurityGroupRuleArgs{
		Type:            pulumi.String("egress"),
		FromPort:        pulumi.Int(0),
		ToPort:          pulumi.Int(0),
		Protocol:        pulumi.String("-1"),
		SecurityGroupId: eksSecurityGroup.ID(),
		CidrBlocks:      pulumi.StringArray{pulumi.String("0.0.0.0/0")},
		Description:     pulumi.String("Allow all outbound traffic"),
	})
	if err != nil {
		return nil, err
	}

	return &NetworkResult{
		EksSecurityGroup:   eksSecurityGroup,
		RedisSecurityGroup: redisSecurityGroup,
	}, nil
}
