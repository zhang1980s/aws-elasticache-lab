package main

import (
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/ec2"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi/config"
)

func main() {
	pulumi.Run(func(ctx *pulumi.Context) error {
		cfg := config.New(ctx, "")

		// Get configuration values
		vpcId := cfg.Require("vpcId")

		// Security group for EKS nodes
		eksSecurityGroup, err := ec2.NewSecurityGroup(ctx, "failover-lab-eks-sg", &ec2.SecurityGroupArgs{
			VpcId:       pulumi.String(vpcId),
			Description: pulumi.String("Security group for Failover Lab EKS nodes"),
			Tags: pulumi.StringMap{
				"Name": pulumi.String("failover-lab-eks-sg"),
			},
		})
		if err != nil {
			return err
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
			return err
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
			return err
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
			return err
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
			return err
		}

		// Export outputs for use by lab stack
		ctx.Export("vpcId", pulumi.String(vpcId))
		ctx.Export("eksSecurityGroupId", eksSecurityGroup.ID())
		ctx.Export("redisSecurityGroupId", redisSecurityGroup.ID())

		return nil
	})
}
