package main

import (
	"lettuce-failover-lab/pkg"

	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi/config"
)

func main() {
	pulumi.Run(func(ctx *pulumi.Context) error {
		cfg := config.New(ctx, "")

		// Get configuration values
		vpcId := cfg.Require("vpcId")
		privateSubnetIds := cfg.RequireObject("privateSubnetIds").([]interface{})

		// Convert subnet IDs to string slice
		subnetIds := make([]string, len(privateSubnetIds))
		for i, id := range privateSubnetIds {
			subnetIds[i] = id.(string)
		}

		// Create networking resources (security groups)
		networkResult, err := pkg.CreateNetworkResources(ctx, vpcId, subnetIds)
		if err != nil {
			return err
		}

		// Create EKS cluster
		eksResult, err := pkg.CreateEKSCluster(ctx, vpcId, subnetIds, networkResult.EksSecurityGroup)
		if err != nil {
			return err
		}

		// Create ElastiCache Redis cluster
		elasticacheResult, err := pkg.CreateElastiCacheCluster(ctx, subnetIds, networkResult.RedisSecurityGroup)
		if err != nil {
			return err
		}

		// Create CloudWatch monitoring
		_, err = pkg.CreateMonitoring(ctx, elasticacheResult.ReplicationGroupId)
		if err != nil {
			return err
		}

		// Export outputs
		ctx.Export("eksClusterName", eksResult.ClusterName)
		ctx.Export("eksClusterEndpoint", eksResult.ClusterEndpoint)
		ctx.Export("kubeconfig", eksResult.Kubeconfig)
		ctx.Export("redisClusterEndpoint", elasticacheResult.ConfigurationEndpoint)
		ctx.Export("redisReplicationGroupId", elasticacheResult.ReplicationGroupId)

		return nil
	})
}
