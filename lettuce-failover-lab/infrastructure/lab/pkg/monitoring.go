package pkg

import (
	"fmt"

	"github.com/pulumi/pulumi-aws/sdk/v6/go/aws/cloudwatch"
	"github.com/pulumi/pulumi/sdk/v3/go/pulumi"
)

type MonitoringResult struct {
	DashboardArn pulumi.StringOutput
	LogGroupArn  pulumi.StringOutput
}

// CreateMonitoring creates CloudWatch dashboard and log groups for failover monitoring
func CreateMonitoring(ctx *pulumi.Context, replicationGroupId pulumi.StringOutput) (*MonitoringResult, error) {
	// Create log group for application logs
	logGroup, err := cloudwatch.NewLogGroup(ctx, "failover-lab-logs", &cloudwatch.LogGroupArgs{
		Name:            pulumi.String("/failover-lab/application"),
		RetentionInDays: pulumi.Int(7),
		Tags: pulumi.StringMap{
			"Name":        pulumi.String("failover-lab-logs"),
			"Environment": pulumi.String("testing"),
		},
	})
	if err != nil {
		return nil, err
	}

	// Create CloudWatch dashboard
	dashboardBody := replicationGroupId.ApplyT(func(rgId string) string {
		return fmt.Sprintf(`{
			"widgets": [
				{
					"type": "text",
					"x": 0,
					"y": 0,
					"width": 24,
					"height": 1,
					"properties": {
						"markdown": "# Lettuce Failover Lab Dashboard"
					}
				},
				{
					"type": "metric",
					"x": 0,
					"y": 1,
					"width": 8,
					"height": 6,
					"properties": {
						"title": "ElastiCache - Replication Lag",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["AWS/ElastiCache", "ReplicationLag", "CacheClusterId", "%s-0001-001", {"label": "Shard 1 Replica"}],
							["AWS/ElastiCache", "ReplicationLag", "CacheClusterId", "%s-0002-001", {"label": "Shard 2 Replica"}],
							["AWS/ElastiCache", "ReplicationLag", "CacheClusterId", "%s-0003-001", {"label": "Shard 3 Replica"}]
						],
						"region": "us-east-1",
						"period": 60
					}
				},
				{
					"type": "metric",
					"x": 8,
					"y": 1,
					"width": 8,
					"height": 6,
					"properties": {
						"title": "ElastiCache - Current Connections",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["AWS/ElastiCache", "CurrConnections", "CacheClusterId", "%s-0001-001", {"label": "Shard 1 Primary"}],
							["AWS/ElastiCache", "CurrConnections", "CacheClusterId", "%s-0002-001", {"label": "Shard 2 Primary"}],
							["AWS/ElastiCache", "CurrConnections", "CacheClusterId", "%s-0003-001", {"label": "Shard 3 Primary"}]
						],
						"region": "us-east-1",
						"period": 60
					}
				},
				{
					"type": "metric",
					"x": 16,
					"y": 1,
					"width": 8,
					"height": 6,
					"properties": {
						"title": "ElastiCache - CPU Utilization",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["AWS/ElastiCache", "CPUUtilization", "CacheClusterId", "%s-0001-001", {"label": "Shard 1"}],
							["AWS/ElastiCache", "CPUUtilization", "CacheClusterId", "%s-0002-001", {"label": "Shard 2"}],
							["AWS/ElastiCache", "CPUUtilization", "CacheClusterId", "%s-0003-001", {"label": "Shard 3"}]
						],
						"region": "us-east-1",
						"period": 60
					}
				},
				{
					"type": "metric",
					"x": 0,
					"y": 7,
					"width": 12,
					"height": 6,
					"properties": {
						"title": "Application - Failover Metrics",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["FailoverLab", "connection.drop.duration.ms", {"label": "Connection Drop Duration"}],
							["FailoverLab", "topology.refresh.count", {"label": "Topology Refresh Count"}],
							["FailoverLab", "operations.failed.during.failover", {"label": "Failed Operations"}]
						],
						"region": "us-east-1",
						"period": 10
					}
				},
				{
					"type": "metric",
					"x": 12,
					"y": 7,
					"width": 12,
					"height": 6,
					"properties": {
						"title": "Application - Operation Latency",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["FailoverLab", "operations.latency.p50.ms", {"label": "P50 Latency"}],
							["FailoverLab", "operations.latency.p99.ms", {"label": "P99 Latency"}],
							["FailoverLab", "operations.latency.max.ms", {"label": "Max Latency"}]
						],
						"region": "us-east-1",
						"period": 10
					}
				},
				{
					"type": "metric",
					"x": 0,
					"y": 13,
					"width": 8,
					"height": 6,
					"properties": {
						"title": "Pub/Sub Metrics",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["FailoverLab", "pubsub.messages.published", {"label": "Published"}],
							["FailoverLab", "pubsub.messages.received", {"label": "Received"}],
							["FailoverLab", "pubsub.message.loss.count", {"label": "Lost"}]
						],
						"region": "us-east-1",
						"period": 10
					}
				},
				{
					"type": "metric",
					"x": 8,
					"y": 13,
					"width": 8,
					"height": 6,
					"properties": {
						"title": "Streams Metrics",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["FailoverLab", "streams.messages.added", {"label": "Added"}],
							["FailoverLab", "streams.messages.consumed", {"label": "Consumed"}],
							["FailoverLab", "streams.lag.ms", {"label": "Lag (ms)"}]
						],
						"region": "us-east-1",
						"period": 10
					}
				},
				{
					"type": "metric",
					"x": 16,
					"y": 13,
					"width": 8,
					"height": 6,
					"properties": {
						"title": "GET/SET Operations",
						"view": "timeSeries",
						"stacked": false,
						"metrics": [
							["FailoverLab", "getset.operations.success", {"label": "Success"}],
							["FailoverLab", "getset.operations.failed", {"label": "Failed"}],
							["FailoverLab", "getset.sequence.gaps", {"label": "Sequence Gaps"}]
						],
						"region": "us-east-1",
						"period": 10
					}
				}
			]
		}`, rgId, rgId, rgId, rgId, rgId, rgId, rgId, rgId, rgId)
	}).(pulumi.StringOutput)

	dashboard, err := cloudwatch.NewDashboard(ctx, "failover-lab-dashboard", &cloudwatch.DashboardArgs{
		DashboardName: pulumi.String("FailoverLab-Dashboard"),
		DashboardBody: dashboardBody,
	})
	if err != nil {
		return nil, err
	}

	return &MonitoringResult{
		DashboardArn: dashboard.DashboardArn,
		LogGroupArn:  logGroup.Arn,
	}, nil
}
