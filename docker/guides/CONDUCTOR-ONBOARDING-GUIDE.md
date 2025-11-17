# Conductor Quick Start Guide

Get Conductor running with Podman using PostgreSQL and Redis.

---

## Prerequisites

### Required Software

- **Podman** (or Docker) - Latest version
- **Git** - To clone the repository

### Ports Used

- `8080` - Conductor REST API
- `5000` - Conductor UI
- `5432` - PostgreSQL
- `6379` - Redis
- `3000` - Grafana (metrics dashboard)
- `9090` - Prometheus (metrics)

---

## Quick Start with Podman Compose

### Step 1: Clone Repository

```bash
git clone https://github.com/conductor-oss/conductor.git
cd conductor/docker
```

### Step 2: Start All Services

```bash
podman-compose -f docker-compose-postgres-redis-observability.yaml up -d
```

This command starts all required services in the background.

### Step 3: Wait for Services to Start

```bash
# Wait 60 seconds for all services to initialize
sleep 60
```

### Step 4: Verify Health

```bash
curl http://localhost:8080/health
```

**Expected output:**
```json
{"healthy": true}
```

### Step 5: Access Conductor

- **Conductor UI**: http://localhost:5000
- **Conductor API**: http://localhost:8080/api
- **Grafana (Metrics)**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090

---

## Understanding the Docker Compose File

The `docker-compose-postgres-redis-observability.yaml` file defines all the services needed to run Conductor with monitoring.

### Services Overview

**Core Services:**
- **conductor-postgres**: Database for storing workflow and task data
- **conductor-redis**: Message queues and distributed locks
- **conductor-server**: Main Conductor API and UI server
- **conductor-sweeper**: Background service that progresses workflows

**Monitoring Services:**
- **prometheus**: Collects metrics from Conductor
- **loki**: Aggregates logs from all services
- **promtail**: Collects and forwards logs to Loki
- **grafana**: Visualizes metrics and logs in dashboards

### Key Configuration Sections

**PostgreSQL Setup:**
```yaml
conductor-postgres:
  image: postgres:15
  environment:
    - POSTGRES_USER=conductor
    - POSTGRES_PASSWORD=conductor
    - POSTGRES_DB=conductor
  volumes:
    - postgres_data:/var/lib/postgresql/data  # Persistent storage
```

**Conductor Server:**
```yaml
conductor-server:
  image: conductor:server-postgres
  ports:
    - "8080:8080"  # API port
    - "5000:5000"  # UI port
  volumes:
    - ./server/config/config-postgres-redis.properties:/app/config/config.properties:ro
  depends_on:
    conductor-postgres:
      condition: service_healthy  # Wait for DB to be ready
```

**Networking:**
All services are on the same `conductor-network` bridge network, allowing them to communicate using service names as hostnames.

**Data Persistence:**
Volumes are used to persist data across container restarts:
- `postgres_data` - Database files
- `prometheus_data` - Metrics history
- `loki_data` - Log data
- `grafana_data` - Dashboards and settings
- `conductor_logs` - Application logs

---

## Setting Up Metrics and Monitoring

Metrics are automatically enabled when you use the `docker-compose-postgres-redis-observability.yaml` file.

### Accessing Grafana

1. Open http://localhost:3000
2. Login with username: `admin`, password: `admin`
3. Navigate to **Dashboards** → **Conductor Metrics Dashboard**

### What You Can Monitor

**Key Metrics Available:**
- **Tasks In Progress**: Number of currently executing tasks
- **Task Queue Depth**: Tasks waiting in queues
- **Decider Queue Size**: Workflows waiting for evaluation
- **Task Poll Rate**: How fast workers are polling for tasks
- **Task Execution Time**: Performance of task execution (p95, p99)
- **JVM Memory Usage**: Server memory consumption

### Viewing Logs

1. In Grafana, click **Explore** (compass icon on left sidebar)
2. Select **Loki** as data source
3. Enter log query:
   ```logql
   {service="conductor-server"}
   ```
4. Filter for errors:
   ```logql
   {service="conductor-server"} |= "ERROR"
   ```

### Useful Prometheus Queries

Access Prometheus at http://localhost:9090

```promql
# Current tasks in progress
sum(task_in_progress)

# Task poll rate per second
rate(task_poll_total[5m])

# 95th percentile task execution time
histogram_quantile(0.95, rate(task_execution_seconds_bucket[5m]))
```

---

## Running with Podman (Manual Setup)

If you prefer to run containers manually instead of using docker-compose:

### Step 1: Create Network

```bash
podman network create conductor-network
```

### Step 2: Start PostgreSQL

```bash
podman run -d \
  --name conductor-postgres \
  --network conductor-network \
  -e POSTGRES_USER=conductor \
  -e POSTGRES_PASSWORD=conductor \
  -e POSTGRES_DB=conductor \
  -p 5432:5432 \
  postgres:15
```

### Step 3: Start Redis

```bash
podman run -d \
  --name conductor-redis \
  --network conductor-network \
  -p 6379:6379 \
  redis:6.2.3
```

### Step 4: Start Conductor

```bash
podman run -d \
  --name conductor-server \
  --network conductor-network \
  -p 8080:8080 \
  -p 5000:5000 \
  -v $(pwd)/server/config/config-postgres-redis.properties:/app/config/config.properties:ro \
  conductor:server-postgres
```

---

## Common Operations

### Start/Stop Services

```bash
# Start all services
podman-compose -f docker-compose-postgres-redis-observability.yaml up -d

# Stop all services
podman-compose -f docker-compose-postgres-redis-observability.yaml stop

# Stop and remove containers
podman-compose -f docker-compose-postgres-redis-observability.yaml down

# Stop and remove containers AND data volumes
podman-compose -f docker-compose-postgres-redis-observability.yaml down -v
```

### View Logs

```bash
# View all logs in real-time
podman-compose -f docker-compose-postgres-redis-observability.yaml logs -f

# View logs for specific service
podman logs -f conductor-server

# View last 100 lines
podman logs --tail 100 conductor-server

# View only errors
podman logs conductor-server 2>&1 | grep ERROR
```

### Check Service Health

```bash
# Check Conductor health
curl http://localhost:8080/health

# List running containers
podman ps

# Check metrics
curl http://localhost:8080/actuator/prometheus | grep task_in_progress
```

---

## Troubleshooting

### Conductor Won't Start

**Check logs:**
```bash
podman logs conductor-server --tail 50
```

**Verify PostgreSQL is running:**
```bash
podman exec conductor-postgres pg_isready -U conductor
```

**Verify Redis is running:**
```bash
podman exec conductor-redis redis-cli ping
```

### No Metrics in Grafana

1. Check Prometheus targets at http://localhost:9090/targets - all should be "UP"
2. In Grafana, go to Configuration → Data sources → Prometheus → "Test" button
3. Try a simple query in Grafana Explore: `up`

### Container Cannot Connect to Database

Make sure all containers are on the same network:
```bash
podman network ls
podman inspect conductor-server | grep NetworkMode
podman inspect conductor-postgres | grep NetworkMode
```

---

## Quick Reference

### Essential URLs

| Service | URL | Credentials |
|---------|-----|-------------|
| Conductor UI | http://localhost:5000 | - |
| Conductor API | http://localhost:8080 | - |
| Grafana | http://localhost:3000 | admin/admin |
| Prometheus | http://localhost:9090 | - |

### Essential Commands

```bash
# Start everything
podman-compose -f docker-compose-postgres-redis-observability.yaml up -d

# Check health
curl http://localhost:8080/health

# View logs
podman logs -f conductor-server

# Stop everything
podman-compose -f docker-compose-postgres-redis-observability.yaml down
```
