package main

import (
	"redis-failover-lab/pkg"

	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi/config"
)

func main() {
	pulumi.Run(func(ctx *pulumi.Context) error {
		cfg := config.New(ctx, "")

		// Get configuration values
		vpcId := cfg.Require("vpcId")
		eksSecurityGroupId := cfg.Require("eksSecurityGroupId")
		redisSecurityGroupId := cfg.Require("redisSecurityGroupId")

		// Get private subnet IDs
		privateSubnetIds := cfg.RequireObject("privateSubnetIds").([]interface{})
		subnetIds := make([]string, len(privateSubnetIds))
		for i, id := range privateSubnetIds {
			subnetIds[i] = id.(string)
		}

		// Create EKS cluster
		eksResult, err := pkg.CreateEKSCluster(ctx, vpcId, subnetIds, eksSecurityGroupId)
		if err != nil {
			return err
		}

		// Create ElastiCache Redis cluster
		elasticacheResult, err := pkg.CreateElastiCacheCluster(ctx, subnetIds, redisSecurityGroupId)
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
