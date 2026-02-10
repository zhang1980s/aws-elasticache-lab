package pkg

import (
	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/elasticache"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type ElastiCacheResult struct {
	ConfigurationEndpoint pulumi.StringOutput
	ReplicationGroupId    pulumi.StringOutput
}

// CreateElastiCacheCluster creates a 3-shard Redis cluster with 1 replica per shard
func CreateElastiCacheCluster(ctx *pulumi.Context, subnetIds []string, securityGroup pulumi.IDOutput) (*ElastiCacheResult, error) {
	// Create subnet group for ElastiCache
	subnetGroup, err := elasticache.NewSubnetGroup(ctx, "failover-lab-subnet-group", &elasticache.SubnetGroupArgs{
		Name:        pulumi.String("failover-lab-subnet-group"),
		Description: pulumi.String("Subnet group for Failover Lab Redis cluster"),
		SubnetIds:   pulumi.ToStringArray(subnetIds),
		Tags: pulumi.StringMap{
			"Name": pulumi.String("failover-lab-subnet-group"),
		},
	})
	if err != nil {
		return nil, err
	}

	// Create parameter group for cluster mode
	parameterGroup, err := elasticache.NewParameterGroup(ctx, "failover-lab-params", &elasticache.ParameterGroupArgs{
		Name:        pulumi.String("failover-lab-params"),
		Family:      pulumi.String("redis7"),
		Description: pulumi.String("Parameter group for Failover Lab Redis cluster"),
		Parameters: elasticache.ParameterGroupParameterArray{
			&elasticache.ParameterGroupParameterArgs{
				Name:  pulumi.String("cluster-enabled"),
				Value: pulumi.String("yes"),
			},
		},
		Tags: pulumi.StringMap{
			"Name": pulumi.String("failover-lab-params"),
		},
	})
	if err != nil {
		return nil, err
	}

	// Create ElastiCache Redis cluster
	// 3 shards with 1 replica each = 6 nodes total
	replicationGroup, err := elasticache.NewReplicationGroup(ctx, "failover-lab-redis", &elasticache.ReplicationGroupArgs{
		ReplicationGroupId: pulumi.String("failover-lab"),
		Description:        pulumi.String("Redis cluster for Lettuce failover testing"),

		// Node configuration
		NodeType:          pulumi.String("cache.r7g.large"),
		Engine:            pulumi.String("redis"),
		EngineVersion:     pulumi.String("7.1"),
		ParameterGroupName: parameterGroup.Name,

		// Cluster mode configuration
		// 3 shards (node groups) with 1 replica per shard
		NumNodeGroups:        pulumi.Int(3),
		ReplicasPerNodeGroup: pulumi.Int(1),

		// Network configuration
		SubnetGroupName: subnetGroup.Name,
		SecurityGroupIds: pulumi.StringArray{
			securityGroup.ToStringOutput(),
		},

		// High availability
		AutomaticFailoverEnabled: pulumi.Bool(true),
		MultiAzEnabled:           pulumi.Bool(true),

		// Encryption
		AtRestEncryptionEnabled: pulumi.Bool(true),
		TransitEncryptionEnabled: pulumi.Bool(true),

		// Maintenance
		MaintenanceWindow:      pulumi.String("sun:05:00-sun:06:00"),
		SnapshotRetentionLimit: pulumi.Int(1),
		SnapshotWindow:         pulumi.String("04:00-05:00"),

		// Apply changes immediately for testing purposes
		ApplyImmediately: pulumi.Bool(true),

		Tags: pulumi.StringMap{
			"Name":        pulumi.String("failover-lab-redis"),
			"Environment": pulumi.String("testing"),
			"Purpose":     pulumi.String("lettuce-failover-testing"),
		},
	})
	if err != nil {
		return nil, err
	}

	return &ElastiCacheResult{
		ConfigurationEndpoint: replicationGroup.ConfigurationEndpointAddress,
		ReplicationGroupId:    replicationGroup.ReplicationGroupId,
	}, nil
}
