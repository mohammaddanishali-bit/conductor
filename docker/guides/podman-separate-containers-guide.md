# Running Conductor with Separate Podman Containers

Complete guide to run PostgreSQL, Redis, and Conductor Server as separate podman containers that can communicate with each other.

## Prerequisites

- Podman installed
- Conductor source code built (or access to conductor:server image)
- Port 5432 (Postgres), 6379 (Redis), 8080 (Conductor) available

---

## Step 1: Create Podman Network

Create a dedicated network for all containers to communicate:

```bash
# Create network
podman network create conductor-network

# Verify network was created
podman network ls | grep conductor-network

# Inspect network details (optional)
podman network inspect conductor-network
```

**Why?** Containers on the same network can communicate using container names as hostnames.

---

## Step 2: Start PostgreSQL Container

```bash
podman run -d \
  --name conductor-postgres \
  --network conductor-network \
  -e POSTGRES_USER=conductor \
  -e POSTGRES_PASSWORD=conductor \
  -e POSTGRES_DB=conductor \
  -p 5432:5432 \
  -v conductor-postgres-data:/var/lib/postgresql/data \
  postgres:15-alpine
```

**Parameters:**
- `--name conductor-postgres`: Container name (used as hostname in network)
- `--network conductor-network`: Connect to our network
- `-e POSTGRES_USER=conductor`: Database user
- `-e POSTGRES_PASSWORD=conductor`: Database password
- `-e POSTGRES_DB=conductor`: Initial database name
- `-p 5432:5432`: Expose port to host
- `-v conductor-postgres-data:/var/lib/postgresql/data`: Persistent storage

**Verify:**

```bash
# Check container status
podman ps --filter "name=conductor-postgres"

# Check logs
podman logs conductor-postgres

# Test connection from host
psql -h localhost -U conductor -d conductor -c "SELECT version();"
# Or using podman:
podman exec conductor-postgres psql -U conductor -c "SELECT version();"
```

**Expected:** You should see PostgreSQL version information.

---

## Step 3: Start Redis Container

```bash
podman run -d \
  --name conductor-redis \
  --network conductor-network \
  -p 6379:6379 \
  -v conductor-redis-data:/data \
  redis:6.2.3-alpine redis-server --appendonly yes
```

**Parameters:**
- `--name conductor-redis`: Container name (hostname in network)
- `--network conductor-network`: Connect to our network
- `-p 6379:6379`: Expose port to host
- `-v conductor-redis-data:/data`: Persistent storage
- `redis-server --appendonly yes`: Enable AOF persistence

**Verify:**

```bash
# Check container status
podman ps --filter "name=conductor-redis"

# Check logs
podman logs conductor-redis

# Test connection from host
redis-cli -h localhost ping
# Or using podman:
podman exec conductor-redis redis-cli ping
```

**Expected:** You should see `PONG` response.

---

## Step 4: (Optional) Start OpenSearch Container

If you want indexing enabled:

```bash
podman run -d \
  --name conductor-opensearch \
  --network conductor-network \
  -e "discovery.type=single-node" \
  -e "plugins.security.disabled=true" \
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" \
  -p 9200:9200 \
  -v conductor-opensearch-data:/usr/share/opensearch/data \
  opensearchproject/opensearch:2.18.0
```

**Verify:**

```bash
# Check container status
podman ps --filter "name=conductor-opensearch"

# Test connection
curl http://localhost:9200
```

---

## Step 5: Create Conductor Configuration File

Create configuration file for Postgres + Redis setup:

**File:** `/tmp/config-postgres-redis.properties`

```properties
# Database persistence type - PostgreSQL
conductor.db.type=postgres

# PostgreSQL Configuration
# IMPORTANT: Use container name 'conductor-postgres' as hostname
spring.datasource.url=jdbc:postgresql://conductor-postgres:5432/conductor
spring.datasource.username=conductor
spring.datasource.password=conductor
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.connection-timeout=30000

# Redis Configuration for locks
# IMPORTANT: Use container name 'conductor-redis' as hostname
conductor.redis-lock.serverAddress=redis://conductor-redis:6379

# Workflow execution lock
conductor.workflow-execution-lock.type=redis
conductor.app.workflowExecutionLockEnabled=true
conductor.app.lockTimeToTry=500

# System task workers
conductor.app.systemTaskWorkerThreadCount=20
conductor.app.systemTaskMaxPollCount=20

# Indexing - Option 1: Disabled
conductor.indexing.enabled=false

# Indexing - Option 2: With OpenSearch (if you started opensearch container)
# conductor.indexing.enabled=true
# conductor.indexing.type=opensearch
# conductor.opensearch.url=http://conductor-opensearch:9200
# conductor.opensearch.indexName=conductor
# conductor.opensearch.version=2

# Metrics
conductor.metrics-prometheus.enabled=true
management.endpoints.web.exposure.include=health,prometheus

# Load sample workflows (optional)
loadSample=false
```

**OR for Redis + OpenSearch (like docker-compose-redis-os.yaml):**

**File:** `/tmp/config-redis-opensearch.properties`

```properties
# Database persistence type - Redis
conductor.db.type=redis_standalone
conductor.queue.type=redis_standalone

# Redis Configuration
# IMPORTANT: Use container name as hostname
conductor.redis.hosts=conductor-redis:6379:us-east-1c
conductor.redis-lock.serverAddress=redis://conductor-redis:6379
conductor.redis.taskDefCacheRefreshInterval=1
conductor.redis.workflowNamespacePrefix=conductor
conductor.redis.queueNamespacePrefix=conductor_queues

# Workflow execution lock
conductor.workflow-execution-lock.type=redis
conductor.app.workflowExecutionLockEnabled=true
conductor.app.lockTimeToTry=500

# System task workers
conductor.app.systemTaskWorkerThreadCount=20
conductor.app.systemTaskMaxPollCount=20

# OpenSearch indexing
conductor.indexing.enabled=true
conductor.indexing.type=opensearch
conductor.opensearch.url=http://conductor-opensearch:9200
conductor.opensearch.indexName=conductor
conductor.opensearch.version=2
conductor.opensearch.indexReplicasCount=0
conductor.opensearch.clusterHealthColor=green

# Metrics
conductor.metrics-prometheus.enabled=true
management.endpoints.web.exposure.include=health,prometheus

# Redis health indicator
management.health.redis.enabled=true

# Load sample kitchen sink workflow
loadSample=true
```

---

## Step 6: Start Conductor Server Container

### Option A: Using existing conductor:server image

```bash
 podman run -d \
    --name conductor-server \
    --network conductor-network \
    -p 8080:8080 \
    -p 5000:5000 \
    -v /Users/mali2/orkes-conductor/config/config-postgres-redis.properties:/app/config/config.properties:ro \
    localhost/conductor:server
```

### Option B: Build and run from source

First, build the image:

```bash
# Navigate to conductor source directory
cd /Users/mali2/Desktop/Projects/opensource-freshworks/conductor

# Build using podman
podman build -f docker/server/Dockerfile -t conductor:server .
```

Then run:

```bash
podman run -d \
  --name conductor-server \
  --network conductor-network \
  -p 8080:8080 \
  -p 5000:5000 \
  -v /tmp/config-postgres-redis.properties:/app/config/config.properties:ro \
  conductor:server
```
podman-separate-containers-guide.md
**Parameters:**
- `--name conductor-server`: Container name
- `--network conductor-network`: Connect to our network (CRITICAL for communication)
- `-p 8080:8080`: Conductor API port
- `-p 5000:5000`: Conductor UI port
- `-v /tmp/config-postgres-redis.properties:/app/config/config.properties:ro`: Mount config file

**Note:** The Dockerfile needs to be configured to read from `/app/config/config.properties`

---

## Step 7: Verify All Containers Are Running

```bash
# List all conductor containers
podman ps --filter "name=conductor"

# Expected output:
# conductor-postgres   (healthy)
# conductor-redis      (healthy)
# conductor-opensearch (healthy) - if started
# conductor-server     (starting/healthy)
```

---

## Step 8: Check Conductor Server Logs

```bash
# Follow logs in real-time
podman logs -f conductor-server

# Check for successful startup messages:
# - "Started ConductorServerApplication"
# - PostgreSQL connection successful
# - Redis connection successful
```

**Look for:**
- ✅ Spring Boot banner
- ✅ PostgreSQL connection: `HikariPool-1 - Start completed`
- ✅ Redis connection: No connection errors
- ✅ `Started Conductor using Java...`
- ✅ `Tomcat started on port 8080`

---

## Step 9: Test Conductor Server

```bash
# Test health endpoint
curl http://localhost:8080/health

# Expected:
# {"healthy": true}

# Test API
curl http://localhost:8080/api/metadata/workflow

# Access UI
# Open browser: http://localhost:5000
```

---

## Step 10: Verify Container Communication

Test that containers can reach each other:

```bash
# Test Conductor → Postgres
podman exec conductor-server ping -c 2 conductor-postgres

# Test Conductor → Redis
podman exec conductor-server ping -c 2 conductor-redis

# Test Conductor → OpenSearch (if started)
podman exec conductor-server ping -c 2 conductor-opensearch

# Test Conductor can connect to Postgres
podman exec conductor-server sh -c "apt-get update && apt-get install -y postgresql-client"
podman exec conductor-server psql -h conductor-postgres -U conductor -d conductor -c "SELECT version();"

# Test Conductor can connect to Redis
podman exec conductor-server sh -c "apt-get install -y redis-tools"
podman exec conductor-server redis-cli -h conductor-redis ping
```

---

## Complete Startup Script

Save this as `/tmp/start-conductor-separate.sh`:

```bash
#!/bin/bash

set -e

echo "=== Starting Conductor with Separate Containers ==="

# Step 1: Create network
echo "Step 1: Creating podman network..."
podman network create conductor-network 2>/dev/null || echo "Network already exists"

# Step 2: Start PostgreSQL
echo "Step 2: Starting PostgreSQL..."
podman run -d \
  --name conductor-postgres \
  --network conductor-network \
  -e POSTGRES_USER=conductor \
  -e POSTGRES_PASSWORD=conductor \
  -e POSTGRES_DB=conductor \
  -p 5432:5432 \
  -v conductor-postgres-data:/var/lib/postgresql/data \
  postgres:15-alpine

# Step 3: Start Redis
echo "Step 3: Starting Redis..."
podman run -d \
  --name conductor-redis \
  --network conductor-network \
  -p 6379:6379 \
  -v conductor-redis-data:/data \
  redis:6.2.3-alpine redis-server --appendonly yes

# Step 4: Start OpenSearch (optional)
echo "Step 4: Starting OpenSearch..."
podman run -d \
  --name conductor-opensearch \
  --network conductor-network \
  -e "discovery.type=single-node" \
  -e "plugins.security.disabled=true" \
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" \
  -p 9200:9200 \
  -v conductor-opensearch-data:/usr/share/opensearch/data \
  opensearchproject/opensearch:2.18.0

# Wait for services to be ready
echo "Waiting for services to start..."
sleep 10

# Step 5: Start Conductor Server
echo "Step 5: Starting Conductor Server..."
podman run -d \
  --name conductor-server \
  --network conductor-network \
  -p 8080:8080 \
  -p 5000:5000 \
  -v /tmp/config-redis-opensearch.properties:/app/config/config.properties:ro \
  localhost/conductor:server

echo "=== Waiting for Conductor to be ready ==="
sleep 15

echo "=== Checking container status ==="
podman ps --filter "name=conductor"

echo ""
echo "=== Testing health endpoint ==="
curl -s http://localhost:8080/health | jq || curl -s http://localhost:8080/health

echo ""
echo "=== Setup Complete! ==="
echo "Conductor UI: http://localhost:5000"
echo "Conductor API: http://localhost:8080"
echo "PostgreSQL: localhost:5432"
echo "Redis: localhost:6379"
echo "OpenSearch: http://localhost:9200"
echo ""
echo "View logs: podman logs -f conductor-server"
```

Make it executable:

```bash
chmod +x /tmp/start-conductor-separate.sh
```

---

## Complete Shutdown Script

Save this as `/tmp/stop-conductor-separate.sh`:

```bash
#!/bin/bash

echo "=== Stopping Conductor Containers ==="

# Stop containers
echo "Stopping containers..."
podman stop conductor-server conductor-opensearch conductor-redis conductor-postgres 2>/dev/null || true

# Remove containers
echo "Removing containers..."
podman rm conductor-server conductor-opensearch conductor-redis conductor-postgres 2>/dev/null || true

# Remove network (optional)
echo "Removing network..."
podman network rm conductor-network 2>/dev/null || true

# Remove volumes (optional - CAUTION: This deletes all data!)
# echo "Removing volumes..."
# podman volume rm conductor-postgres-data conductor-redis-data conductor-opensearch-data 2>/dev/null || true

echo "=== Cleanup Complete ==="
```

Make it executable:

```bash
chmod +x /tmp/stop-conductor-separate.sh
```

---

## Troubleshooting

### Container cannot connect to Postgres

**Problem:** Conductor shows `Connection refused: conductor-postgres:5432`

**Solution:**
1. Verify all containers are on same network:
   ```bash
   podman inspect conductor-server | jq '.[0].NetworkSettings.Networks'
   podman inspect conductor-postgres | jq '.[0].NetworkSettings.Networks'
   ```

2. Check Postgres is listening:
   ```bash
   podman exec conductor-postgres psql -U conductor -c "SELECT version();"
   ```

3. Verify hostname resolution:
   ```bash
   podman exec conductor-server getent hosts conductor-postgres
   ```

### Container cannot connect to Redis

**Problem:** Conductor shows `Failed to connect to Redis`

**Solution:**
1. Test Redis from conductor container:
   ```bash
   podman exec conductor-server sh -c "apk add redis && redis-cli -h conductor-redis ping"
   ```

2. Check Redis logs:
   ```bash
   podman logs conductor-redis
   ```

### Conductor not starting

**Problem:** Container exits immediately

**Solution:**
1. Check logs:
   ```bash
   podman logs conductor-server
   ```

2. Verify config file is mounted:
   ```bash
   podman exec conductor-server cat /app/config/config.properties
   ```

3. Check if config path matches what startup script expects

---

## Network Communication Flow

```
┌─────────────────────────────────────────────────────┐
│         Podman Network: conductor-network            │
│                                                       │
│  ┌──────────────────┐                                │
│  │ conductor-server │                                │
│  │   Port: 8080     │────┐                           │
│  │   Port: 5000     │    │                           │
│  └──────────────────┘    │                           │
│           │               │                           │
│           │ JDBC          │ Redis Lock Protocol      │
│           ▼               ▼                           │
│  ┌──────────────────┐  ┌──────────────────┐         │
│  │ conductor-postgres│ │ conductor-redis   │         │
│  │   Port: 5432     │  │   Port: 6379     │         │
│  └──────────────────┘  └──────────────────┘         │
│           │                      │                    │
│           │                      │                    │
│           ▼                      ▼                    │
│     Volume: postgres-data   Volume: redis-data       │
│                                                       │
│  ┌──────────────────────────┐                        │
│  │ conductor-opensearch     │                        │
│  │   Port: 9200             │◄───── HTTP             │
│  └──────────────────────────┘                        │
│           │                                           │
│           ▼                                           │
│     Volume: opensearch-data                          │
└─────────────────────────────────────────────────────┘
         │         │         │
         │         │         │ Port forwarding to host
         ▼         ▼         ▼
    localhost:5432 6379  8080/9200/5000
```

---

## Key Points

1. **Network is Critical:** All containers MUST be on `conductor-network` to communicate using container names

2. **Hostnames:** Use container names as hostnames in config:
   - `conductor-postgres` (not `localhost`)
   - `conductor-redis` (not `localhost`)
   - `conductor-opensearch` (not `localhost`)

3. **Port Mapping:** `-p` only needed for accessing from host. Containers communicate directly on internal ports.

4. **Volumes:** Use named volumes for data persistence across container restarts

5. **Configuration:** Mount config file as read-only (`:ro`) for security

6. **Startup Order:** Postgres & Redis must be healthy before Conductor starts

---

## Next Steps

Once all containers are running:

1. **Test the setup:**
   ```bash
   bash /tmp/workflow-execution-guide.md
   ```

2. **Monitor logs:**
   ```bash
   # Terminal 1
   podman logs -f conductor-server

   # Terminal 2
   podman logs -f conductor-postgres

   # Terminal 3
   podman logs -f conductor-redis
   ```

3. **Access UI:**
   - Open http://localhost:5000
   - Navigate workflows, tasks, and execution monitoring

4. **Use API:**
   - Follow guide at `/tmp/workflow-execution-guide.md`

---

# PostgreSQL + Redis Setup (No Indexing)

This section covers running Conductor with **PostgreSQL for data storage** and **Redis for distributed locks** without indexing.

## Architecture

```
┌─────────────────────────────────────────┐
│  conductor-postgres (port 5432)          │
│  - Workflow & task definitions           │
│  - Execution data                        │
│  - All metadata                          │
└─────────────────────────────────────────┘
              ▲
              │ JDBC
              │
┌─────────────────────────────────────────┐
│  conductor-server (ports 8080, 5000)     │
│  - Business logic                        │
│  - API endpoints                         │
└─────────────────────────────────────────┘
              │
              │ Redis Lock Protocol
              ▼
┌─────────────────────────────────────────┐
│  conductor-redis (port 6379)             │
│  - Distributed locks only                │
│  - Concurrency control                   │
└─────────────────────────────────────────┘
```

## Prerequisites

This setup requires fixing a bean conflict in Conductor source code.

### Fix 1: IndexDAO Bean Conflict

**Problem:** When indexing is disabled, two beans are created: `indexDAO` and `noopIndexDAO`, causing a conflict.

**Solution:** Edit `/conductor/core/src/main/java/com/netflix/conductor/core/config/IndexDAOConfig.java`:

```java
package com.netflix.conductor.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.netflix.conductor.core.index.NoopIndexDAO;
import com.netflix.conductor.dao.IndexDAO;

@Configuration
public class IndexDAOConfig {

    @Bean("indexDAO")
    @ConditionalOnProperty(
            name = "conductor.indexing.enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(IndexDAO.class)
    public IndexDAO indexDAO() {
        return new NoopIndexDAO();
    }
}
```

**Explanation:** This prevents the bean from being created when `conductor.indexing.enabled=false`, avoiding conflict with `NoopIndexDAOConfiguration`.

### Fix 2: Docker Base Image

**Problem:** The base image `openjdk:17-bullseye` is no longer available.

**Solution:** Edit `/conductor/docker/server/Dockerfile` line 7:

```dockerfile
# Change FROM:
FROM openjdk:17-bullseye AS builder

# To:
FROM eclipse-temurin:17-jdk AS builder
```

### Rebuild Conductor

```bash
cd /path/to/conductor

# Apply code formatting
./gradlew :conductor-core:spotlessApply

# Build project
./gradlew clean build -x test
```

### Rebuild Docker Image

```bash
cd /path/to/conductor
podman build -f docker/server/Dockerfile -t conductor:server-postgres .
```

**Note:** If yarn fails with network timeout during UI build, simply retry the command - it usually succeeds on the second attempt.

---

## Step-by-Step Setup

### 1. Create Network

```bash
podman network create conductor-network
```

### 2. Start PostgreSQL

```bash
podman run -d \
  --name conductor-postgres \
  --network conductor-network \
  -e POSTGRES_USER=conductor \
  -e POSTGRES_PASSWORD=conductor \
  -e POSTGRES_DB=conductor \
  -p 5432:5432 \
  -v conductor-postgres-data:/var/lib/postgresql/data \
  postgres:15-alpine
```

**Verify:**

```bash
podman exec conductor-postgres psql -U conductor -c "SELECT version();"
```

### 3. Start Redis

```bash
podman run -d \
  --name conductor-redis \
  --network conductor-network \
  -p 6379:6379 \
  -v conductor-redis-data:/data \
  redis:6.2.3-alpine redis-server --appendonly yes
```

**Verify:**

```bash
podman exec conductor-redis redis-cli ping
# Expected: PONG
```

### 4. Create Configuration File

The configuration file is already created at:
`/conductor/docker/server/config/config-postgres-redis.properties`

If you need to create it manually:

```properties
# Database persistence type - PostgreSQL
conductor.db.type=postgres

# PostgreSQL Configuration
# IMPORTANT: Use container name as hostname
spring.datasource.url=jdbc:postgresql://conductor-postgres:5432/conductor
spring.datasource.username=conductor
spring.datasource.password=conductor
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.connection-timeout=30000

# Redis Configuration for locks
# IMPORTANT: Use container name as hostname
conductor.redis-lock.serverAddress=redis://conductor-redis:6379

# Workflow execution lock
conductor.workflow-execution-lock.type=redis
conductor.app.workflowExecutionLockEnabled=true
conductor.app.lockTimeToTry=500

# System task workers
conductor.app.systemTaskWorkerThreadCount=20
conductor.app.systemTaskMaxPollCount=20

# Indexing DISABLED - No OpenSearch/ElasticSearch
conductor.indexing.enabled=false

# Metrics
conductor.metrics-prometheus.enabled=true
management.endpoints.web.exposure.include=health,prometheus

# Load sample workflows
loadSample=true
```

### 5. Start Conductor Server

```bash
podman run -d \
  --name conductor-server \
  --network conductor-network \
  -p 8080:8080 \
  -p 5000:5000 \
  -v /path/to/conductor/docker/server/config/config-postgres-redis.properties:/app/config/config.properties:ro \
  localhost/conductor:server-postgres
```

**Note:** Replace `/path/to/conductor` with your actual Conductor repository path. For example:
```bash
-v /Users/mali2/Desktop/Projects/opensource-freshworks/conductor/docker/server/config/config-postgres-redis.properties:/app/config/config.properties:ro
```

### 6. Verify Setup

```bash
# Check all containers
podman ps --filter "name=conductor"

# Expected output:
# conductor-postgres  (Up X minutes)
# conductor-redis     (Up X minutes)
# conductor-server    (Up X minutes)

# Test health endpoint
curl http://localhost:8080/health

# Expected: {"healthy": true}

# Test API
curl http://localhost:8080/api/metadata/workflow
```

---

## Complete Startup Script

Save as `/tmp/start-conductor-postgres-redis.sh`:

**Important:** Before running, update `CONDUCTOR_PATH` in the script to your actual conductor repository path.

```bash
#!/bin/bash

set -e

echo "=== Starting Conductor with PostgreSQL + Redis ==="

# Step 1: Create network
echo "Step 1: Creating network..."
podman network create conductor-network 2>/dev/null || echo "Network already exists"

# Step 2: Start PostgreSQL
echo "Step 2: Starting PostgreSQL..."
podman run -d \
  --name conductor-postgres \
  --network conductor-network \
  -e POSTGRES_USER=conductor \
  -e POSTGRES_PASSWORD=conductor \
  -e POSTGRES_DB=conductor \
  -p 5432:5432 \
  -v conductor-postgres-data:/var/lib/postgresql/data \
  postgres:15-alpine

# Step 3: Start Redis
echo "Step 3: Starting Redis..."
podman run -d \
  --name conductor-redis \
  --network conductor-network \
  -p 6379:6379 \
  -v conductor-redis-data:/data \
  redis:6.2.3-alpine redis-server --appendonly yes

# Wait for services
echo "Waiting for services to start..."
sleep 10

# Step 4: Start Conductor Server
echo "Step 4: Starting Conductor Server..."
# Note: Update the config path to your actual conductor repository location
CONDUCTOR_PATH="/path/to/conductor"
podman run -d \
  --name conductor-server \
  --network conductor-network \
  -p 8080:8080 \
  -p 5000:5000 \
  -v ${CONDUCTOR_PATH}/docker/server/config/config-postgres-redis.properties:/app/config/config.properties:ro \
  localhost/conductor:server-postgres

echo "=== Waiting for Conductor to be ready ==="
sleep 15

echo "=== Checking status ==="
podman ps --filter "name=conductor"

echo ""
echo "=== Testing health ==="
curl -s http://localhost:8080/health | jq || curl -s http://localhost:8080/health

echo ""
echo "=== Setup Complete! ==="
echo "Conductor UI: http://localhost:5000"
echo "Conductor API: http://localhost:8080"
echo "PostgreSQL: localhost:5432"
echo "Redis: localhost:6379"
echo ""
echo "View logs: podman logs -f conductor-server"
```

Make executable and run:

```bash
chmod +x /tmp/start-conductor-postgres-redis.sh
./start-conductor-postgres-redis.sh
```

---

## Shutdown Script

Save as `/tmp/stop-conductor-postgres-redis.sh`:

```bash
#!/bin/bash

echo "=== Stopping Conductor (Postgres + Redis) ==="

# Stop containers
podman stop conductor-server conductor-redis conductor-postgres 2>/dev/null || true

# Remove containers
podman rm conductor-server conductor-redis conductor-postgres 2>/dev/null || true

# Remove network (optional)
podman network rm conductor-network 2>/dev/null || true

# Remove volumes (optional - CAUTION: Deletes data!)
# podman volume rm conductor-postgres-data conductor-redis-data 2>/dev/null || true

echo "=== Cleanup Complete ==="
```

---

## Troubleshooting

### Bean Conflict Error

**Error:**
```
Parameter 2 of constructor in ExecutionDAOFacade required a single bean, 
but 2 were found: indexDAO, noopIndexDAO
```

**Solution:**
Apply the IndexDAO fix and rebuild (see Prerequisites section above).

### Cannot Connect to PostgreSQL

**Error:** `Connection refused: conductor-postgres:5432`

**Solutions:**

1. Verify all containers on same network:
   ```bash
   podman inspect conductor-server | jq '.[0].NetworkSettings.Networks'
   podman inspect conductor-postgres | jq '.[0].NetworkSettings.Networks'
   ```

2. Test Postgres from conductor container:
   ```bash
   podman exec conductor-server ping conductor-postgres
   ```

3. Check Postgres logs:
   ```bash
   podman logs conductor-postgres
   ```

---

## Monitoring

### Database Queries

Connect to Postgres and query directly:

```bash
# Connect to database
podman exec -it conductor-postgres psql -U conductor

# List all workflows
SELECT workflow_id, workflow_type, status, start_time 
FROM workflow 
ORDER BY start_time DESC 
LIMIT 10;

# Count workflows by status
SELECT status, COUNT(*) 
FROM workflow 
GROUP BY status;

# Find workflows by type
SELECT workflow_id, status, start_time 
FROM workflow 
WHERE workflow_type = 'my_workflow' 
ORDER BY start_time DESC;
```

### Redis Locks

Monitor active locks:

```bash
# Connect to Redis
podman exec -it conductor-redis redis-cli

# List all locks
KEYS conductor:lock:*

# Check specific lock
GET conductor:lock:workflow-id-here

# Monitor lock operations
MONITOR
```

### Container Logs

```bash
# Conductor server
podman logs -f conductor-server

# PostgreSQL
podman logs conductor-postgres

# Redis
podman logs conductor-redis
```

---

## Data Persistence

### Backup PostgreSQL

```bash
# Backup database
podman exec conductor-postgres pg_dump -U conductor conductor > backup.sql

# Restore database
cat backup.sql | podman exec -i conductor-postgres psql -U conductor conductor
```

### Backup Redis (if needed for locks)

```bash
# Redis saves to /data automatically with AOF
# Copy backup file
podman cp conductor-redis:/data/appendonly.aof ./redis-backup.aof
```

---
