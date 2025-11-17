# Conductor Observability Stack

This directory contains the configuration for monitoring and logging Conductor using Prometheus, Loki, and Grafana.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Conductor Services                        │
│  ┌──────────────┐          ┌──────────────┐                 │
│  │   Server     │          │   Sweeper    │                 │
│  │  (Port 8080) │          │              │                 │
│  └──────┬───────┘          └──────┬───────┘                 │
│         │                         │                          │
│         │ /actuator/prometheus    │ /actuator/prometheus     │
│         │                         │                          │
└─────────┼─────────────────────────┼──────────────────────────┘
          │                         │
          │                         │
          ▼                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    Prometheus                                │
│             (Scrapes metrics every 15s)                      │
│                  Port: 9090                                  │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      │
┌─────────────────────┼───────────────────────────────────────┐
│                     │      Loki                              │
│                     │  (Stores logs)                         │
│                     │    Port: 3100                          │
│                     │        ▲                               │
│                     │        │                               │
│                     │    ┌───┴────┐                          │
│                     │    │Promtail│                          │
│                     │    │(Scrapes│                          │
│                     │    │  logs) │                          │
│                     │    └────────┘                          │
└─────────────────────┼───────────────────────────────────────┘
                      │
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                     Grafana                                  │
│          (Visualizes metrics & logs)                         │
│                  Port: 3000                                  │
│          Login: admin / admin                                │
└─────────────────────────────────────────────────────────────┘
```

## Directory Structure

```
observability/
├── prometheus/
│   └── prometheus.yml          # Prometheus scrape config
├── loki/
│   └── loki-config.yaml        # Loki storage config
├── promtail/
│   └── promtail-config.yaml    # Log collection config
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/
│   │   │   └── datasources.yaml  # Auto-configure Prometheus & Loki
│   │   └── dashboards/
│   │       └── dashboards.yaml   # Auto-load dashboards
│   └── dashboards/
│       └── conductor-metrics.json # Pre-built Conductor dashboard
└── README.md
```

## Quick Start

### 1. Start All Services

```bash
cd /Users/mali2/Desktop/Projects/opensource-freshworks/conductor/docker
podman-compose -f docker-compose-postgres-redis-observability.yaml up -d
```

### 2. Access Dashboards

- **Conductor UI**: http://localhost:5000
- **Conductor API**: http://localhost:8080
- **Grafana**: http://localhost:3000 (Login: admin/admin)
- **Prometheus**: http://localhost:9090
- **Loki**: http://localhost:3100

### 3. View Metrics in Grafana

1. Open Grafana: http://localhost:3000
2. Login with `admin` / `admin`
3. Navigate to Dashboards → Conductor Metrics Dashboard
4. You'll see:
   - Running Workflows count
   - Workflow start/completion rates
   - Task poll rates by type
   - Workflow execution times (p95, p99)

### 4. View Logs in Grafana

1. Go to Explore (compass icon in sidebar)
2. Select "Loki" as datasource
3. Use queries like:
   ```
   {service="conductor-server"}
   {service="conductor-sweeper"}
   {service="conductor-server"} |= "ERROR"
   {service="conductor-server"} | json | level="ERROR"
   ```

## Available Metrics

Conductor exposes Prometheus metrics at `/actuator/prometheus`. Key metrics include:

### Workflow Metrics
- `conductor_server_workflow_running` - Number of running workflows
- `conductor_server_workflow_start_total` - Total workflows started
- `conductor_server_workflow_completion_total` - Total workflows completed
- `conductor_server_workflow_execution_seconds` - Workflow execution time histogram

### Task Metrics
- `conductor_server_task_poll_total` - Task polls by type
- `conductor_server_task_execution_queue_full` - Queue saturation
- `conductor_server_task_execution_seconds` - Task execution time

### System Metrics
- `jvm_memory_used_bytes` - JVM memory usage
- `jvm_threads_live` - Active threads
- `process_cpu_usage` - CPU usage

## Log Queries

### Useful LogQL Queries

```logql
# All logs from conductor-server
{service="conductor-server"}

# Error logs only
{service="conductor-server"} |= "ERROR"

# Workflow execution logs
{service="conductor-server"} |= "workflow"

# Sweeper activity
{service="conductor-sweeper"} |= "sweep"

# Rate of errors per minute
rate({service="conductor-server"} |= "ERROR" [1m])

# Logs from specific class
{service="conductor-server"} | json | class="WorkflowExecutor"
```

## Customization

### Add More Scrape Targets

Edit `observability/prometheus/prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'my-service'
    static_configs:
      - targets: ['my-service:9090']
```

### Create Custom Dashboards

1. Create dashboard in Grafana UI
2. Export as JSON
3. Save to `observability/grafana/dashboards/`
4. Restart Grafana

### Adjust Log Retention

Edit `observability/loki/loki-config.yaml`:

```yaml
limits_config:
  retention_period: 168h  # 7 days
```

## Troubleshooting

### Prometheus Not Scraping Metrics

```bash
# Check Prometheus targets
open http://localhost:9090/targets

# Check if Conductor exposes metrics
curl http://localhost:8080/actuator/prometheus
```

### Loki Not Receiving Logs

```bash
# Check Promtail status
podman logs conductor-promtail

# Check Loki
curl http://localhost:3100/ready
```

### Grafana Can't Connect to Datasources

```bash
# Restart Grafana
podman restart conductor-grafana

# Check datasource health in Grafana UI
# Settings → Data Sources → Test
```

## Cleanup

```bash
# Stop all services
podman-compose -f docker-compose-postgres-redis-observability.yaml down

# Stop and remove volumes (deletes all data!)
podman-compose -f docker-compose-postgres-redis-observability.yaml down -v
```

## Production Considerations

1. **Security**:
   - Change Grafana admin password
   - Enable authentication on Prometheus
   - Use TLS for all connections

2. **Retention**:
   - Configure Prometheus retention: `--storage.tsdb.retention.time=30d`
   - Configure Loki retention in config

3. **High Availability**:
   - Run multiple Prometheus instances
   - Use remote storage (Thanos, Cortex)
   - Deploy Loki in distributed mode

4. **Alerting**:
   - Add Alertmanager for Prometheus
   - Configure alert rules
   - Set up notification channels (Slack, PagerDuty)

## References

- [Conductor Metrics Documentation](https://conductor.netflix.com/documentation/metrics.html)
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Loki Documentation](https://grafana.com/docs/loki/latest/)
- [Grafana Documentation](https://grafana.com/docs/grafana/latest/)
