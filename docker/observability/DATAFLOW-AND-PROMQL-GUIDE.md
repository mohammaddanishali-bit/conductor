# Conductor Observability: Data Flow and PromQL Guide

This guide explains how metrics flow from Conductor through Prometheus to Grafana dashboards, and how to write PromQL queries.

## Table of Contents
1. [Complete Data Flow](#complete-data-flow)
2. [How Metrics are Collected](#how-metrics-are-collected)
3. [Prometheus Scraping](#prometheus-scraping)
4. [Grafana Queries](#grafana-queries)
5. [PromQL Basics](#promql-basics)
6. [Useful PromQL Examples](#useful-promql-examples)

---

## Complete Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                      CONDUCTOR SERVER                           │
│                                                                 │
│  Application Code → Micrometer → /actuator/prometheus          │
│  (Java metrics)     (Library)    (HTTP endpoint)               │
│                                                                 │
│  task_in_progress = 5                                          │
│  task_poll_total = 1234                                        │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              │ HTTP GET (Pull)
                              │ Every 15 seconds
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         PROMETHEUS                              │
│                                                                 │
│  1. Scraper → Fetches metrics from Conductor                   │
│  2. TSDB    → Stores time-series data                          │
│  3. Query   → Executes PromQL queries                          │
│                                                                 │
│  Data: task_in_progress{job="conductor-server"} @ timestamp    │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              │ HTTP POST (PromQL)
                              │ Every 10 seconds (dashboard refresh)
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                           GRAFANA                               │
│                                                                 │
│  1. Dashboard → Contains panels with queries                   │
│  2. Datasource → Sends queries to Prometheus                   │
│  3. Render → Displays charts/graphs                            │
│                                                                 │
│  User sees: Live metrics updating every 10s                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## How Metrics are Collected

### Step 1: Application Instrumentation

Conductor uses **Micrometer** (Java metrics library) to collect metrics:

```java
// Example: Counter metric
Counter.builder("task_poll_total")
    .tag("taskType", "HTTP")
    .tag("job", "conductor-server")
    .register(meterRegistry)
    .increment();

// Example: Gauge metric
Gauge.builder("task_in_progress", taskInProgressCount)
    .tag("taskType", "HTTP")
    .register(meterRegistry);

// Example: Timer/Histogram
Timer.builder("task_execution_seconds")
    .tag("taskType", "HTTP")
    .register(meterRegistry)
    .record(() -> executeTask(task));
```

### Step 2: Metrics Exposed via HTTP

Conductor exposes metrics at: `http://conductor-server:8080/actuator/prometheus`

**Response format (Prometheus text format):**
```
# HELP task_in_progress Number of tasks currently in progress
# TYPE task_in_progress gauge
task_in_progress{taskType="HTTP",job="conductor-server"} 5.0
task_in_progress{taskType="JOIN",job="conductor-server"} 2.0

# HELP task_poll_total Total number of task polls
# TYPE task_poll_total counter
task_poll_total{taskType="HTTP",job="conductor-server"} 1234.0

# HELP task_execution_seconds Task execution time
# TYPE task_execution_seconds histogram
task_execution_seconds_bucket{taskType="HTTP",le="0.1"} 100.0
task_execution_seconds_bucket{taskType="HTTP",le="0.5"} 250.0
task_execution_seconds_bucket{taskType="HTTP",le="1.0"} 300.0
task_execution_seconds_sum{taskType="HTTP"} 450.5
task_execution_seconds_count{taskType="HTTP"} 300.0
```

### Metric Types

1. **Counter**: Always increasing number (task_poll_total)
2. **Gauge**: Value that can go up or down (task_in_progress)
3. **Histogram**: Distribution of values (task_execution_seconds)
4. **Summary**: Similar to histogram with percentiles

---

## Prometheus Scraping

### Configuration

**File:** `observability/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s     # Fetch metrics every 15 seconds
  evaluation_interval: 15s # Evaluate rules every 15 seconds

scrape_configs:
  - job_name: 'conductor-server'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s   # Override global interval
    static_configs:
      - targets: ['conductor-server:8080']
        labels:
          service: 'conductor-server'
          component: 'api'
```

### How Scraping Works

```
T=0s:  Prometheus → HTTP GET conductor-server:8080/actuator/prometheus
       Prometheus ← Response with all metrics
       Prometheus → Stores in TSDB with timestamp T=0

T=15s: Prometheus → HTTP GET conductor-server:8080/actuator/prometheus
       Prometheus ← Response with updated metrics
       Prometheus → Stores in TSDB with timestamp T=15

T=30s: Prometheus → HTTP GET conductor-server:8080/actuator/prometheus
       ...and so on
```

### Time Series Storage

Each unique metric + label combination creates a **time series**:

```
Time Series 1: task_in_progress{taskType="HTTP",job="conductor-server"}
  @ 2025-11-17 09:00:00 → 5.0
  @ 2025-11-17 09:00:15 → 6.0
  @ 2025-11-17 09:00:30 → 4.0
  @ 2025-11-17 09:00:45 → 7.0

Time Series 2: task_in_progress{taskType="JOIN",job="conductor-server"}
  @ 2025-11-17 09:00:00 → 2.0
  @ 2025-11-17 09:00:15 → 3.0
  @ 2025-11-17 09:00:30 → 2.0
```

---

## Grafana Queries

### Dashboard Structure

A Grafana dashboard is defined in JSON:

```json
{
  "title": "Conductor Metrics Dashboard",
  "panels": [
    {
      "title": "Tasks In Progress",
      "targets": [
        {
          "expr": "sum(task_in_progress)",
          "refId": "A",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          }
        }
      ],
      "type": "stat"
    }
  ]
}
```

### Query Execution Flow

1. **User opens dashboard** → Grafana loads dashboard JSON
2. **For each panel** → Grafana extracts the `expr` (PromQL query)
3. **Grafana sends HTTP POST** to Prometheus:
   ```
   POST http://conductor-prometheus:9090/api/v1/query_range

   Body:
   {
     "query": "sum(task_in_progress)",
     "start": "1700215200",  // Unix timestamp
     "end": "1700218800",
     "step": "15"             // Resolution in seconds
   }
   ```
4. **Prometheus executes query** → Returns time series data
5. **Grafana renders visualization** → Chart/Graph/Stat appears
6. **Auto-refresh** → Every 10 seconds, repeat steps 3-5

### Grafana Datasource Configuration

**File:** `observability/grafana/provisioning/datasources/datasources.yaml`

```yaml
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://conductor-prometheus:9090
    jsonData:
      httpMethod: POST
      timeInterval: 15s
```

**Key points:**
- `access: proxy` → Grafana backend queries Prometheus (not browser)
- `url` → Must use Docker service name, not localhost
- `timeInterval` → Minimum query resolution

---

## PromQL Basics

### PromQL (Prometheus Query Language)

PromQL is a functional query language for selecting and aggregating time series data.

### Basic Selectors

```promql
# Select all time series for a metric
task_in_progress

# Select by label match
task_in_progress{taskType="HTTP"}

# Multiple label filters
task_in_progress{taskType="HTTP",job="conductor-server"}

# Label regex match
task_in_progress{taskType=~"HTTP|KAFKA.*"}

# Negative match
task_in_progress{taskType!="HTTP"}
```

### Range Vectors

Select data over a time range:

```promql
# Last 5 minutes of data
task_poll_total[5m]

# Last 1 hour
task_execution_seconds[1h]
```

### Aggregation Functions

```promql
# Sum across all time series
sum(task_in_progress)

# Sum grouped by label
sum(task_in_progress) by (taskType)

# Average
avg(task_execution_seconds)

# Min/Max
min(task_queue_depth)
max(task_queue_depth)

# Count number of time series
count(task_in_progress)
```

### Rate and Increase

For **counters** (always increasing numbers):

```promql
# Per-second rate over last 5 minutes
rate(task_poll_total[5m])

# Total increase over last 5 minutes
increase(task_poll_total[5m])

# Example output:
# rate() → 2.5 tasks/second
# increase() → 750 tasks (2.5 * 300 seconds)
```

### Mathematical Operations

```promql
# Add two metrics
task_in_progress + task_queue_depth

# Multiply by constant
task_execution_seconds * 1000  # Convert to milliseconds

# Division (e.g., average)
sum(task_execution_seconds_sum) / sum(task_execution_seconds_count)
```

### Comparison Operators

```promql
# Find tasks where queue depth > 10
task_queue_depth > 10

# Boolean (returns 1 or 0)
task_in_progress > 5

# Filtering
sum(task_in_progress) > 10
```

### Functions

```promql
# Absolute value
abs(rate(task_poll_total[5m]))

# Round
round(task_execution_seconds)

# Logarithm
log2(task_queue_depth)

# Clamp (limit between min/max)
clamp_max(task_queue_depth, 100)
```

### Histograms and Percentiles

For histogram metrics:

```promql
# 95th percentile
histogram_quantile(0.95, rate(task_execution_seconds_bucket[5m]))

# 99th percentile
histogram_quantile(0.99, rate(task_execution_seconds_bucket[5m]))

# 50th percentile (median)
histogram_quantile(0.50, rate(task_execution_seconds_bucket[5m]))
```

---

## Useful PromQL Examples

### Conductor-Specific Queries

#### 1. Current Tasks In Progress

```promql
# Total across all task types
sum(task_in_progress)

# By task type
sum(task_in_progress) by (taskType)

# Only HTTP tasks
sum(task_in_progress{taskType="HTTP"})
```

#### 2. Task Poll Rate

```promql
# Polls per second (last 5 minutes)
rate(task_poll_total[5m])

# By task type
sum(rate(task_poll_total[5m])) by (taskType)

# Total polls in last hour
increase(task_poll_total[1h])
```

#### 3. Task Execution Time

```promql
# Average execution time
rate(task_execution_seconds_sum[5m]) / rate(task_execution_seconds_count[5m])

# 95th percentile
histogram_quantile(0.95, rate(task_execution_seconds_bucket[5m]))

# 99th percentile by task type
histogram_quantile(0.99,
  sum(rate(task_execution_seconds_bucket[5m])) by (le, taskType)
)
```

#### 4. Queue Metrics

```promql
# Current queue depth
sum(task_queue_depth)

# Queue depth by task type
task_queue_depth

# Decider queue size
_deciderQueue
```

#### 5. Error Rate

```promql
# Task poll errors per second
rate(task_poll_error_total[5m])

# Error ratio
rate(task_poll_error_total[5m]) / rate(task_poll_total[5m])
```

#### 6. JVM Metrics

```promql
# Memory usage in MB
jvm_memory_used_bytes{job=~"conductor-.*"} / 1024 / 1024

# CPU usage
process_cpu_usage{job=~"conductor-.*"}

# Thread count
jvm_threads_live{job=~"conductor-.*"}
```

### Advanced Queries

#### Top 5 Task Types by Poll Rate

```promql
topk(5, sum(rate(task_poll_total[5m])) by (taskType))
```

#### Task Execution Success Rate

```promql
# Assuming task_execution_total has status label
sum(rate(task_execution_total{status="success"}[5m]))
/
sum(rate(task_execution_total[5m]))
* 100
```

#### Alert When Queue Depth Too High

```promql
# Returns value only if condition is true
sum(task_queue_depth) > 100
```

#### Derivative (Change Rate)

```promql
# How fast is the queue growing?
deriv(task_queue_depth[5m])
```

#### Predict Future Value

```promql
# Predict queue depth in 1 hour based on last 5 minutes
predict_linear(task_queue_depth[5m], 3600)
```

---

## Query Optimization Tips

### 1. Use Appropriate Time Ranges

```promql
# Bad: Too long range for rate calculation
rate(task_poll_total[1h])

# Good: 4x scrape interval
rate(task_poll_total[1m])  # If scrape interval is 15s
```

### 2. Filter Early

```promql
# Bad: Aggregate then filter
sum(task_in_progress) > 10

# Good: Filter then aggregate (if possible)
sum(task_in_progress{taskType="HTTP"})
```

### 3. Use Recording Rules for Complex Queries

For frequently used complex queries, create recording rules in Prometheus:

```yaml
groups:
  - name: conductor_rules
    interval: 30s
    rules:
      - record: conductor:task_poll_rate:5m
        expr: rate(task_poll_total[5m])
```

Then use in Grafana:
```promql
sum(conductor:task_poll_rate:5m) by (taskType)
```

---

## Testing Queries

### 1. Prometheus UI

Access: `http://localhost:9090`

- Go to **Graph** tab
- Enter your PromQL query
- Click **Execute**
- View table or graph

### 2. Grafana Explore

Access: `http://localhost:3000/explore`

- Select **Prometheus** datasource
- Enter query
- Click **Run query**
- View results
- Switch between Table/Graph/Logs view

### 3. Command Line

```bash
# Query current value
curl -s 'http://localhost:9090/api/v1/query?query=task_in_progress' | jq .

# Query range
curl -s 'http://localhost:9090/api/v1/query_range?query=task_in_progress&start=1700215200&end=1700218800&step=15' | jq .

# Get all metrics
curl -s 'http://localhost:9090/api/v1/label/__name__/values' | jq .
```

---

## Common Troubleshooting

### No Data in Grafana

1. **Check Prometheus is scraping:**
   - Go to `http://localhost:9090/targets`
   - All targets should be "UP"

2. **Check metrics exist:**
   - Go to `http://localhost:9090/graph`
   - Enter metric name
   - Should see data

3. **Check Grafana datasource:**
   - Grafana → Configuration → Data sources → Prometheus
   - Click "Save & test"
   - Should say "Successfully queried"

4. **Check time range:**
   - Grafana dashboard time range must match data availability
   - Try "Last 5 minutes"

### Query Returns No Data

```promql
# Check if metric exists at all
task_in_progress

# Check labels
task_in_progress{taskType="HTTP"}

# Check all available labels
count(task_in_progress) by (taskType, job)
```

---

## References

- [Prometheus Documentation](https://prometheus.io/docs/)
- [PromQL Cheat Sheet](https://promlabs.com/promql-cheat-sheet/)
- [Grafana Prometheus Datasource](https://grafana.com/docs/grafana/latest/datasources/prometheus/)
- [Conductor Metrics](https://conductor.netflix.com/documentation/metrics.html)

---

## Summary

### Data Flow Chain:

1. **Conductor** → Instruments code with Micrometer
2. **Micrometer** → Exposes metrics at `/actuator/prometheus`
3. **Prometheus** → Scrapes metrics every 15 seconds
4. **Prometheus TSDB** → Stores time series data
5. **Grafana** → Queries Prometheus with PromQL
6. **Grafana** → Renders visualizations
7. **You** → View real-time dashboards

### Key Points:

- **Pull Model**: Prometheus fetches metrics (Conductor doesn't push)
- **Time Series**: Each metric+labels = one time series
- **PromQL**: Functional query language for time series data
- **Auto-refresh**: Dashboards update automatically every 10s
- **No Code Changes**: Conductor just exposes HTTP endpoint

This architecture provides powerful observability with minimal overhead!
