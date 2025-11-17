# Conductor Core Architecture and Distributed Deployment Guide

This guide explains how Netflix Conductor's core module works, the workflow execution flow, and how to run services (like the Sweeper) separately across different networks for distributed deployment.

## Table of Contents
1. [Conductor Core Architecture](#conductor-core-architecture)
2. [Workflow Execution Flow](#workflow-execution-flow)
3. [Key Components](#key-components)
4. [Distributed Service Deployment](#distributed-service-deployment)
5. [Running Sweeper Service Separately](#running-sweeper-service-separately)
6. [Configuration Reference](#configuration-reference)

---

## Conductor Core Architecture

### Overview

Conductor Core (`conductor-core` module) is the heart of Netflix Conductor's workflow orchestration engine. It handles:

- **Workflow Execution**: Starting, progressing, and completing workflows
- **Task Scheduling**: Determining which tasks to execute and when
- **Workflow Reconciliation (Sweeping)**: Background service ensuring workflow progression
- **System Task Execution**: Built-in tasks (HTTP, Kafka, Event, etc.)
- **Event Processing**: Handling workflow and task events
- **Consistency Management**: Repairing inconsistencies between execution and queue state

### High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CONDUCTOR CORE                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌────────────────┐     ┌──────────────────┐    ┌──────────────┐  │
│  │ REST API       │────▶│ WorkflowExecutor │───▶│ DeciderService│  │
│  │ (Server)       │     │ (Orchestrator)   │    │ (Brain)       │  │
│  └────────────────┘     └──────────────────┘    └──────────────┘  │
│          │                       │                      │           │
│          │                       ▼                      ▼           │
│          │              ┌──────────────────┐   ┌──────────────┐    │
│          │              │ ExecutionDAO     │   │ TaskMappers  │    │
│          │              │ (Persistence)    │   │ (22 types)   │    │
│          │              └──────────────────┘   └──────────────┘    │
│          │                       │                      │           │
│          ▼                       ▼                      ▼           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    DECIDER QUEUE                             │  │
│  │       (Workflows waiting for evaluation/progression)          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                             │                                       │
│                             ▼                                       │
│          ┌─────────────────────────────────────────┐               │
│          │   WorkflowReconciler (Scheduled)        │               │
│          │   Polls DECIDER_QUEUE every 500ms       │               │
│          └─────────────────────────────────────────┘               │
│                             │                                       │
│                             ▼                                       │
│          ┌─────────────────────────────────────────┐               │
│          │   WorkflowSweeper (Core Logic)          │               │
│          │   - Acquire lock                        │               │
│          │   - Call DeciderService.decide()        │               │
│          │   - Update workflow state               │               │
│          │   - Schedule tasks                      │               │
│          └─────────────────────────────────────────┘               │
│                             │                                       │
│                             ▼                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │                   TASK QUEUES                              │    │
│  │   - System Task Queues (HTTP, Event, Lambda, etc.)        │    │
│  │   - User-defined Task Queues (Custom workers)             │    │
│  └────────────────────────────────────────────────────────────┘    │
│                             │                                       │
│                             ▼                                       │
│          ┌─────────────────────────────────────────┐               │
│          │   SystemTaskWorkerCoordinator           │               │
│          │   - Polls system task queues            │               │
│          │   - Executes async system tasks         │               │
│          └─────────────────────────────────────────┘               │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │   Event Processing (Optional)                               │   │
│  │   - DefaultEventProcessor                                   │   │
│  │   - DefaultEventQueueProcessor                              │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Workflow Execution Flow

### Complete Flow Diagram

```
┌───────────────────────────────────────────────────────────────────┐
│ 1. START WORKFLOW (API Request)                                  │
└───────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│ 2. WorkflowExecutorOps.startWorkflow()                           │
│    - Validate workflow definition                                │
│    - Create workflow instance in ExecutionDAO                    │
│    - Embed workflow and task definitions (no metadata lookups)   │
│    - Push workflow ID to DECIDER_QUEUE                           │
│    - Call decide() for immediate evaluation                      │
└───────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│ 3. DeciderService.decide(workflow)                               │
│    - Load workflow with all tasks                                │
│    - Filter unprocessed tasks                                    │
│    - Check for timeouts (workflow and task level)                │
│    - Determine next tasks to schedule using TaskMappers          │
│    - Handle retries with backoff (FIXED, LINEAR, EXPONENTIAL)    │
│    - Check workflow completion conditions                        │
└───────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│ 4. TASK SCHEDULING                                               │
│    ┌─────────────────────────────────────────────────────────┐   │
│    │ System Tasks (Sync - Decision, Switch, Fork, Join)     │   │
│    │ → Execute immediately in same thread                   │   │
│    └─────────────────────────────────────────────────────────┘   │
│    ┌─────────────────────────────────────────────────────────┐   │
│    │ System Tasks (Async - HTTP, Event, Lambda, Kafka)      │   │
│    │ → Queue for SystemTaskWorker execution                 │   │
│    └─────────────────────────────────────────────────────────┘   │
│    ┌─────────────────────────────────────────────────────────┐   │
│    │ User-defined Tasks (Custom tasks)                      │   │
│    │ → Queue for external worker polling                    │   │
│    └─────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│ 5. TASK EXECUTION                                                │
│    - Workers poll task queues                                    │
│    - Execute task logic                                          │
│    - Update task status via API                                  │
└───────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│ 6. TASK UPDATE (updateTask API)                                 │
│    - Update task in ExecutionDAO                                 │
│    - If workflow needs re-evaluation:                            │
│      → Push workflow ID to DECIDER_QUEUE                         │
│      → Call decide() (expedited for parent workflows)            │
└───────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│ 7. BACKGROUND SWEEPING (Runs continuously)                       │
│    ┌─────────────────────────────────────────────────────────┐   │
│    │ WorkflowReconciler (Scheduled every 500ms)              │   │
│    │ - Poll DECIDER_QUEUE for workflow IDs                   │   │
│    │ - For each workflow:                                    │   │
│    │   → WorkflowSweeper.sweepAsync(workflowId)              │   │
│    └─────────────────────────────────────────────────────────┘   │
│                            │                                      │
│                            ▼                                      │
│    ┌─────────────────────────────────────────────────────────┐   │
│    │ WorkflowSweeper.sweep(workflowId)                       │   │
│    │ 1. Acquire execution lock (prevents concurrent updates)│   │
│    │ 2. Load workflow with tasks from ExecutionDAO          │   │
│    │ 3. WorkflowRepairService.verifyAndRepairTasks()        │   │
│    │    (if enabled - fixes queue inconsistencies)          │   │
│    │ 4. Call DeciderService.decide(workflow)                │   │
│    │ 5. If workflow is terminal:                            │   │
│    │    → Remove from DECIDER_QUEUE                          │   │
│    │    Else:                                                │   │
│    │    → Calculate next sweep timeout with jitter          │   │
│    │    → Set unack timeout in DECIDER_QUEUE                │   │
│    │ 6. Release execution lock                              │   │
│    └─────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│ 8. WORKFLOW COMPLETION                                           │
│    - All tasks completed                                         │
│    - Workflow marked as COMPLETED/FAILED/TERMINATED              │
│    - Removed from DECIDER_QUEUE                                  │
│    - Events published (if configured)                            │
│    - Parent workflow notified (if sub-workflow)                  │
└───────────────────────────────────────────────────────────────────┘
```

### Detailed Step Descriptions

#### Step 1-2: Workflow Start
- Client sends POST request to `/api/workflow` or uses `startWorkflow()` SDK method
- **WorkflowExecutor** validates workflow definition and input
- Creates workflow instance with embedded definitions (v2.x - no metadata lookups needed)
- Persists to **ExecutionDAO** (Redis/PostgreSQL)
- Adds workflow ID to **DECIDER_QUEUE**
- Immediately calls `decide()` for first evaluation

#### Step 3: Decision Logic
- **DeciderService** is the "brain" of Conductor
- Evaluates current workflow state
- Determines which tasks should execute next based on:
  - Task dependencies (sequential/parallel)
  - Control flow (DECISION, SWITCH tasks)
  - Task status (SCHEDULED, IN_PROGRESS, COMPLETED, FAILED)
  - Retries and timeouts
- Uses **TaskMappers** to convert WorkflowTask definitions → TaskModel instances
- Returns list of tasks to schedule

#### Step 4-5: Task Execution
**Synchronous System Tasks** (Decision, Switch, Fork, Join, DoWhile):
- Execute immediately in the DeciderService thread
- No external worker needed
- State updated directly

**Asynchronous System Tasks** (HTTP, Event, Lambda, Kafka):
- Pushed to system task queues
- **SystemTaskWorkerCoordinator** manages workers
- Workers poll queues and execute tasks
- Results posted back via `updateTask()` API

**User-defined Tasks** (Custom):
- Pushed to task-specific queues
- External workers (SDK-based) poll queues
- Execute business logic
- Post results via `updateTask()` API

#### Step 6: Task Updates
- When a task completes, worker calls `updateTask()` API
- **WorkflowExecutor** updates task in ExecutionDAO
- If workflow needs re-evaluation (task completion may unblock other tasks):
  - Workflow ID pushed back to DECIDER_QUEUE
  - `decide()` called (expedited evaluation for parent workflows)

#### Step 7: Background Sweeping
- **WorkflowReconciler** runs on a schedule (default: every 500ms)
- Polls DECIDER_QUEUE for workflows needing evaluation
- For each workflow:
  - Acquires execution lock (prevents concurrent modifications)
  - Loads complete workflow state
  - Optionally runs repair service (fixes queue inconsistencies)
  - Calls `decide()` to progress workflow
  - Calculates next sweep time based on task timeouts
  - Sets message timeout in queue (auto-requeues if not removed)
  - Releases lock

**Sweep Timeout Calculation**:
```
Base timeout: conductor.app.workflowOffsetTimeout (default: 30s)
Jitter: ±33% (prevents thundering herd)
Adjusted for task state:
  - IN_PROGRESS: Use task's responseTimeoutSeconds
  - SCHEDULED: Use task's pollTimeoutSeconds
  - WAIT: Use task's waitTimeout
  - HUMAN: Use workflow offset timeout
Max cap: conductor.app.maxPostponeDurationSeconds (default: 3600s)
```

#### Step 8: Completion
- Workflow reaches terminal state (COMPLETED, FAILED, TERMINATED)
- Removed from DECIDER_QUEUE
- Events published (if event handlers configured)
- Parent workflow notified (if this was a sub-workflow)
- Metrics updated

---

## Key Components

### 1. WorkflowExecutor (WorkflowExecutorOps)

**Location**: `core/src/main/java/com/netflix/conductor/core/execution/WorkflowExecutorOps.java`

**Purpose**: Main orchestrator for workflow operations

**Key Methods**:
- `startWorkflow()`: Start new workflow execution
- `rewind()`: Rewind workflow to specific task
- `restart()`: Restart failed workflow
- `terminate()`: Terminate running workflow
- `pauseWorkflow()` / `resumeWorkflow()`: Pause/resume execution
- `updateTask()`: Update task status and progress workflow

**Key Features**:
- Embeds workflow and task definitions in execution (v2.x+)
- No metadata lookups during execution (performance optimization)
- Handles task retries with configurable backoff strategies
- Manages sub-workflow hierarchies

---

### 2. DeciderService

**Location**: `core/src/main/java/com/netflix/conductor/core/execution/DeciderService.java`

**Purpose**: The "brain" that determines workflow progression

**Key Methods**:
- `decide(Workflow)`: Main decision method
  - Returns `DeciderOutcome` with tasks to schedule and workflow update flag

**Decision Logic**:
```java
1. Filter tasks that need processing (not in terminal state)
2. Check workflow timeout
3. For each task:
   - Check task timeout
   - Determine if ready to execute (dependencies met)
   - Handle task-specific logic via TaskMappers
4. Check workflow completion conditions
5. Return list of tasks to schedule
```

**Retry Logic**:
- Supports FIXED, LINEAR, EXPONENTIAL backoff
- Configurable retry count and delay
- Optional jitter to prevent thundering herd

---

### 3. WorkflowReconciler

**Location**: `core/src/main/java/com/netflix/conductor/core/reconciliation/WorkflowReconciler.java`

**Purpose**: Background scheduler that polls DECIDER_QUEUE

**Configuration**:
```properties
# Enable/disable reconciler
conductor.workflow-reconciler.enabled=true

# Polling frequency
conductor.sweep-frequency.millis=500

# Thread pool size
conductor.app.sweeperThreadCount=8
```

**How It Works**:
```java
@Scheduled(fixedDelayString = "${conductor.sweep-frequency.millis:500}")
public void pollAndSweep() {
    List<String> workflowIds = queueDAO.pop(DECIDER_QUEUE, ...);
    workflowIds.forEach(workflowId ->
        workflowSweeper.sweepAsync(workflowId)
    );
}
```

**Key Features**:
- Implements `LifecycleAwareComponent` (stops on shutdown)
- Uses thread pool for parallel sweeping
- Can be disabled for distributed deployment

---

### 4. WorkflowSweeper

**Location**: `core/src/main/java/com/netflix/conductor/core/reconciliation/WorkflowSweeper.java`

**Purpose**: Core sweeping logic for individual workflows

**Key Method**:
```java
public void sweep(String workflowId) {
    // 1. Acquire execution lock
    workflowExecutionLock.acquireLock(workflowId);

    try {
        // 2. Load workflow with tasks
        Workflow workflow = executionDAOFacade.getWorkflowById(workflowId, true);

        // 3. Verify and repair tasks (if repair service enabled)
        workflowRepairService.verifyAndRepairWorkflowTasks(workflowId);

        // 4. Decide and update workflow
        workflowExecutor.decide(workflowId);

        // 5. Handle queue management
        if (workflow.getStatus().isTerminal()) {
            queueDAO.remove(DECIDER_QUEUE, workflowId);
        } else {
            int timeout = calculateTimeout(workflow);
            queueDAO.setUnackTimeout(DECIDER_QUEUE, workflowId, timeout);
        }
    } finally {
        // 6. Release lock
        workflowExecutionLock.releaseLock(workflowId);
    }
}
```

**Timeout Calculation Logic**:
- Base: `workflowOffsetTimeout` (default: 30s)
- Adds jitter: ±33% to prevent synchronized sweeps
- Adjusts based on task state and timeouts
- Caps at `maxPostponeDurationSeconds` (default: 1 hour)

---

### 5. WorkflowRepairService

**Location**: `core/src/main/java/com/netflix/conductor/core/reconciliation/WorkflowRepairService.java`

**Purpose**: Maintains consistency between ExecutionDAO and QueueDAO

**Configuration**:
```properties
conductor.workflow-repair-service.enabled=true
```

**When to Enable**:
- Required for **Redis/Dynomite** (no transactional guarantees)
- Optional for **PostgreSQL** (ACID compliant)
- Requires QueueDAO to implement `containsMessage()` method

**What It Does**:
```java
public void verifyAndRepairWorkflowTasks(String workflowId) {
    Workflow workflow = executionDAOFacade.getWorkflowById(workflowId, true);

    for (Task task : workflow.getTasks()) {
        if (task.getStatus() == SCHEDULED) {
            // Verify task is in queue
            if (!queueDAO.containsMessage(task.getTaskDefName(), task.getTaskId())) {
                // Repair: Add task back to queue
                queueDAO.push(task.getTaskDefName(), task.getTaskId(), 0);
            }
        }
    }
}
```

---

### 6. SystemTaskWorkerCoordinator

**Location**: `core/src/main/java/com/netflix/conductor/core/execution/tasks/SystemTaskWorkerCoordinator.java`

**Purpose**: Manages system task workers that execute async system tasks

**Configuration**:
```properties
# Enable/disable system task workers
conductor.system-task-workers.enabled=true

# Thread pool settings
conductor.app.systemTaskWorkerThreadCount=8
conductor.app.systemTaskMaxPollCount=8
```

**System Tasks** (14 built-in):
- **HTTP**: REST API calls
- **Event**: Publish events to queues/topics
- **Kafka_Publish**: Kafka producer
- **Lambda**: AWS Lambda invocation
- **SubWorkflow**: Nested workflow execution
- **StartWorkflow**: Start another workflow
- **Wait**: Delay execution
- **Human**: Wait for human intervention
- **Terminate**: Terminate workflow
- **Decision** / **Switch**: Branching logic
- **Fork** / **Join** / **ExclusiveJoin**: Parallel execution
- **DoWhile**: Looping
- **SetVariable** / **Noop**: Utilities

**Execution Namespace Isolation**:
```properties
# Instance-level isolation
conductor.app.systemTaskWorkerExecutionNamespace=production

# Task definition with namespace
{
  "name": "encode_task",
  "type": "HTTP",
  "executionNameSpace": "production"
}
```

---

### 7. TaskMappers (22 Types)

**Location**: `core/src/main/java/com/netflix/conductor/core/execution/mapper/`

**Purpose**: Convert WorkflowTask definitions → TaskModel instances

**Key Mappers**:
- `DecisionTaskMapper`: Handle DECISION tasks (branching)
- `SwitchTaskMapper`: Handle SWITCH tasks (multi-way branching)
- `ForkJoinTaskMapper`: Handle FORK_JOIN tasks (parallel execution)
- `ForkJoinDynamicTaskMapper`: Dynamic fork/join
- `SubWorkflowTaskMapper`: Handle SUB_WORKFLOW tasks
- `DoWhileTaskMapper`: Handle DO_WHILE tasks (loops)
- `HTTPTaskMapper`: Handle HTTP system tasks
- `EventTaskMapper`: Handle EVENT tasks
- `UserDefinedTaskMapper`: Handle custom user tasks

**Mapper Interface**:
```java
public interface TaskMapper {
    TaskType getTaskType();
    List<TaskModel> getMappedTasks(TaskMapperContext context);
}
```

---

### 8. EventProcessor (Optional)

**Location**: `core/src/main/java/com/netflix/conductor/core/events/DefaultEventProcessor.java`

**Purpose**: Processes events and triggers configured actions

**Configuration**:
```properties
# Enable/disable event processor
conductor.default-event-processor.enabled=true

# Event processing thread pool
conductor.app.eventProcessorThreadCount=4

# Event queue polling
conductor.app.eventQueuePollInterval=100ms
conductor.app.eventQueuePollCount=10
```

**Event Actions**:
- Start workflow
- Complete task
- Fail task
- Terminate workflow

---

## Distributed Service Deployment

### Why Distribute Services?

1. **Scalability**: Scale different components independently
2. **Resource Optimization**: Allocate resources based on workload
3. **Fault Isolation**: Isolate failures to specific components
4. **Performance**: Dedicate nodes to CPU-intensive operations

### Service Distribution Options

Conductor supports fine-grained service distribution through conditional bean creation:

| Service | Configuration Property | Default | Description |
|---------|----------------------|---------|-------------|
| **System Task Workers** | `conductor.system-task-workers.enabled` | `true` | Executes async system tasks (HTTP, Event, Lambda) |
| **Workflow Reconciler/Sweeper** | `conductor.workflow-reconciler.enabled` | `true` | Background workflow progression |
| **Workflow Repair Service** | `conductor.workflow-repair-service.enabled` | `false` | Queue consistency repair |
| **Event Processor** | `conductor.default-event-processor.enabled` | `true` | Event-driven workflow triggers |
| **Event Queue Processor** | `conductor.default-event-queue-processor.enabled` | `true` | Default event queue listener |
| **Workflow Monitor** | `conductor.workflow-monitor.enabled` | `true` | Metrics publishing |
| **gRPC Server** | `conductor.grpc-server.enabled` | `false` | gRPC API interface |

---

## Running Sweeper Service Separately

### Architecture for Distributed Deployment

```
┌─────────────────────────────────────────────────────────────────────┐
│                    POSTGRES NETWORK (postgres-network)              │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  PostgreSQL Container                                      │    │
│  │  - Stores workflow & task metadata                         │    │
│  │  - Port: 5432 (internal)                                   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                             ▲                                       │
└─────────────────────────────┼───────────────────────────────────────┘
                              │
                    ┌─────────┴──────────┐
                    │                    │
┌───────────────────┼────────────────────┼───────────────────────────┐
│ CONDUCTOR NETWORK │(conductor-network) │                           │
│                   │                    │                           │
│  ┌────────────────▼────────────────────▼──────────────────────┐    │
│  │  Conductor API Server Instance                             │    │
│  │  - REST API endpoints (port 8080)                          │    │
│  │  - gRPC API (port 8090)                                    │    │
│  │  - UI (port 5000)                                          │    │
│  │  - WorkflowExecutor enabled                                │    │
│  │  - DeciderService enabled                                  │    │
│  │  Configuration:                                            │    │
│  │  ✅ REST/gRPC APIs enabled                                 │    │
│  │  ❌ conductor.workflow-reconciler.enabled=false            │    │
│  │  ❌ conductor.system-task-workers.enabled=false            │    │
│  │  ❌ conductor.default-event-processor.enabled=false        │    │
│  └────────────────┬────────────────────┬──────────────────────┘    │
│                   │                    │                           │
│  ┌────────────────▼────────────────────▼──────────────────────┐    │
│  │  Conductor Sweeper Service Instance                        │    │
│  │  - No exposed ports                                        │    │
│  │  - WorkflowReconciler enabled                              │    │
│  │  - WorkflowSweeper enabled                                 │    │
│  │  - DeciderService enabled                                  │    │
│  │  Configuration:                                            │    │
│  │  ✅ conductor.workflow-reconciler.enabled=true             │    │
│  │  ❌ conductor.system-task-workers.enabled=false            │    │
│  │  ❌ conductor.default-event-processor.enabled=false        │    │
│  │  ❌ No REST/gRPC APIs exposed                              │    │
│  └────────────────┬────────────────────┬──────────────────────┘    │
│                   │                    │                           │
└───────────────────┼────────────────────┼───────────────────────────┘
                    │                    │
┌───────────────────┼────────────────────┼───────────────────────────┐
│  REDIS NETWORK    │ (redis-network)    │                           │
│                   │                    │                           │
│  ┌────────────────▼────────────────────▼──────────────────────┐    │
│  │  Redis Container                                           │    │
│  │  - Queues (DECIDER_QUEUE, task queues)                     │    │
│  │  - Distributed locks                                       │    │
│  │  - Port: 6379 (internal)                                   │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Step-by-Step Setup

#### Step 1: Create Networks

```bash
# Create isolated networks
podman network create postgres-network
podman network create redis-network
podman network create conductor-network
```

#### Step 2: Start PostgreSQL

```bash
podman run -d \
  --name conductor-postgres \
  --network postgres-network \
  -e POSTGRES_USER=conductor \
  -e POSTGRES_PASSWORD=conductor \
  -e POSTGRES_DB=conductor \
  postgres:15
```

#### Step 3: Start Redis

```bash
podman run -d \
  --name conductor-redis \
  --network redis-network \
  redis:6.2.3
```

#### Step 4: Create Configuration Files

**API Server Config** (`config-api-server.properties`):
```properties
# Database persistence
conductor.db.type=postgres
spring.datasource.url=jdbc:postgresql://conductor-postgres:5432/conductor
spring.datasource.username=conductor
spring.datasource.password=conductor

# Redis for locks
conductor.redis-lock.serverAddress=redis://conductor-redis:6379
conductor.workflow-execution-lock.type=redis
conductor.app.workflowExecutionLockEnabled=true

# Disable background services on API server
conductor.workflow-reconciler.enabled=false
conductor.system-task-workers.enabled=false
conductor.default-event-processor.enabled=false
conductor.workflow-repair-service.enabled=false

# API settings
conductor.indexing.enabled=false
conductor.metrics-prometheus.enabled=true
management.endpoints.web.exposure.include=health,prometheus
```

**Sweeper Service Config** (`config-sweeper-service.properties`):
```properties
# Database persistence
conductor.db.type=postgres
spring.datasource.url=jdbc:postgresql://conductor-postgres:5432/conductor
spring.datasource.username=conductor
spring.datasource.password=conductor

# Redis for locks and queues
conductor.redis-lock.serverAddress=redis://conductor-redis:6379
conductor.workflow-execution-lock.type=redis
conductor.app.workflowExecutionLockEnabled=true

# Enable ONLY workflow reconciler/sweeper
conductor.workflow-reconciler.enabled=true
conductor.system-task-workers.enabled=false
conductor.default-event-processor.enabled=false

# Optional: Enable repair service for Redis
conductor.workflow-repair-service.enabled=true

# Sweeper tuning
conductor.sweep-frequency.millis=500
conductor.app.sweeperThreadCount=16
conductor.app.workflowOffsetTimeout=30s

# Disable indexing
conductor.indexing.enabled=false

# Metrics
conductor.metrics-prometheus.enabled=true
management.endpoints.web.exposure.include=health,prometheus
```

#### Step 5: Start Conductor API Server

```bash
# Start API server container
podman run -d \
  --name conductor-api-server \
  --network conductor-network \
  -p 8080:8080 \
  -p 5000:5000 \
  -p 8090:8090 \
  -v /path/to/config-api-server.properties:/app/config/config.properties:ro \
  localhost/conductor:server-postgres

# Connect to postgres and redis networks
podman network connect postgres-network conductor-api-server
podman network connect redis-network conductor-api-server

# Restart to apply network changes
podman restart conductor-api-server
```

#### Step 6: Start Conductor Sweeper Service

```bash
# Start sweeper service container (no exposed ports)
podman run -d \
  --name conductor-sweeper \
  --network conductor-network \
  -v /path/to/config-sweeper-service.properties:/app/config/config.properties:ro \
  localhost/conductor:server-postgres

# Connect to postgres and redis networks
podman network connect postgres-network conductor-sweeper
podman network connect redis-network conductor-sweeper

# Restart to apply network changes
podman restart conductor-sweeper
```

#### Step 7: Verify Setup

```bash
# Check containers
podman ps | grep conductor

# Verify API server health
curl http://localhost:8080/health
# Expected: {"healthy":true}

# Check API server logs (should NOT see WorkflowReconciler)
podman logs conductor-api-server | grep -i reconciler
# Expected: No reconciler started

# Check sweeper logs (should see WorkflowReconciler)
podman logs conductor-sweeper | grep -i reconciler
# Expected: WorkflowReconciler initialized and running

# Verify networks
podman inspect conductor-api-server | grep -A 5 Networks
podman inspect conductor-sweeper | grep -A 5 Networks
```

### Complete Startup Script

Save as `start-distributed-conductor.sh`:

```bash
#!/bin/bash

set -e

echo "Starting Conductor with distributed services..."

# Configuration
CONDUCTOR_PATH="/path/to/conductor"
API_CONFIG="${CONDUCTOR_PATH}/docker/server/config/config-api-server.properties"
SWEEPER_CONFIG="${CONDUCTOR_PATH}/docker/server/config/config-sweeper-service.properties"

# Step 1: Create networks
echo "Creating networks..."
podman network create postgres-network 2>/dev/null || echo "postgres-network exists"
podman network create redis-network 2>/dev/null || echo "redis-network exists"
podman network create conductor-network 2>/dev/null || echo "conductor-network exists"

# Step 2: Start PostgreSQL
echo "Starting PostgreSQL..."
podman run -d \
  --name conductor-postgres \
  --network postgres-network \
  -e POSTGRES_USER=conductor \
  -e POSTGRES_PASSWORD=conductor \
  -e POSTGRES_DB=conductor \
  postgres:15

echo "Waiting for PostgreSQL..."
sleep 10

# Step 3: Start Redis
echo "Starting Redis..."
podman run -d \
  --name conductor-redis \
  --network redis-network \
  redis:6.2.3

echo "Waiting for Redis..."
sleep 5

# Step 4: Start Conductor API Server
echo "Starting Conductor API Server..."
podman run -d \
  --name conductor-api-server \
  --network conductor-network \
  -p 8080:8080 \
  -p 5000:5000 \
  -p 8090:8090 \
  -v ${API_CONFIG}:/app/config/config.properties:ro \
  localhost/conductor:server-postgres

# Connect API server to database networks
echo "Connecting API server to networks..."
podman network connect postgres-network conductor-api-server
podman network connect redis-network conductor-api-server
podman restart conductor-api-server

# Wait for API server
echo "Waiting for API server to start..."
sleep 30

# Step 5: Start Conductor Sweeper Service
echo "Starting Conductor Sweeper Service..."
podman run -d \
  --name conductor-sweeper \
  --network conductor-network \
  -v ${SWEEPER_CONFIG}:/app/config/config.properties:ro \
  localhost/conductor:server-postgres

# Connect sweeper to database networks
echo "Connecting sweeper to networks..."
podman network connect postgres-network conductor-sweeper
podman network connect redis-network conductor-sweeper
podman restart conductor-sweeper

# Wait for sweeper
echo "Waiting for sweeper to start..."
sleep 30

# Step 6: Verify
echo ""
echo "Verifying setup..."
curl -s http://localhost:8080/health | jq '.' || echo "API server not ready"

echo ""
echo "Container Status:"
podman ps | grep conductor

echo ""
echo "API Server Logs (last 20 lines):"
podman logs --tail 20 conductor-api-server

echo ""
echo "Sweeper Logs (last 20 lines):"
podman logs --tail 20 conductor-sweeper

echo ""
echo "Setup complete!"
echo "Access Conductor UI: http://localhost:5000"
echo "Access Conductor API: http://localhost:8080/api"
echo "Access gRPC: localhost:8090"
```

Make executable:
```bash
chmod +x start-distributed-conductor.sh
```

### Cleanup Script

Save as `stop-distributed-conductor.sh`:

```bash
#!/bin/bash

echo "Stopping distributed Conductor setup..."

# Stop containers
podman stop conductor-api-server conductor-sweeper conductor-postgres conductor-redis 2>/dev/null || true

# Remove containers
podman rm conductor-api-server conductor-sweeper conductor-postgres conductor-redis 2>/dev/null || true

# Remove networks
podman network rm postgres-network redis-network conductor-network 2>/dev/null || true

echo "Cleanup complete!"
```

---

## Configuration Reference

### Core Properties

#### Workflow Execution
```properties
# Workflow offset timeout (base sweep interval)
conductor.app.workflowOffsetTimeout=30s

# Max postpone duration (cap for sweep timeout)
conductor.app.maxPostponeDurationSeconds=3600

# Workflow execution lock
conductor.app.workflowExecutionLockEnabled=true
conductor.app.lockLeaseTime=60000
conductor.app.lockTimeToTry=500
conductor.workflow-execution-lock.type=redis  # or local_only, zookeeper
```

#### Sweeper Configuration
```properties
# Enable/disable workflow reconciler
conductor.workflow-reconciler.enabled=true

# Sweep frequency (polling interval)
conductor.sweep-frequency.millis=500

# Thread pool size for sweeping
conductor.app.sweeperThreadCount=8

# Queue poll timeout
conductor.app.sweeperWorkflowPollTimeout=2000
```

#### System Task Workers
```properties
# Enable/disable system task workers
conductor.system-task-workers.enabled=true

# Thread pool settings
conductor.app.systemTaskWorkerThreadCount=8
conductor.app.systemTaskMaxPollCount=8
conductor.app.systemTaskWorkerPollInterval=50
conductor.app.systemTaskWorkerCallbackDuration=30000

# Execution namespace (for isolation)
conductor.app.systemTaskWorkerExecutionNamespace=
```

#### Event Processing
```properties
# Enable/disable event processor
conductor.default-event-processor.enabled=true

# Event processing threads
conductor.app.eventProcessorThreadCount=4

# Event queue polling
conductor.app.eventQueuePollInterval=100
conductor.app.eventQueuePollCount=10
conductor.app.eventQueueLongPollTimeout=1000
```

#### Repair Service
```properties
# Enable/disable repair service
conductor.workflow-repair-service.enabled=false
```

#### Indexing
```properties
# Enable/disable indexing
conductor.indexing.enabled=false

# Async indexing
conductor.app.asyncIndexingEnabled=true
```

### Deployment Pattern Configurations

#### Pattern 1: API-Only Instance
```properties
conductor.workflow-reconciler.enabled=false
conductor.system-task-workers.enabled=false
conductor.default-event-processor.enabled=false
conductor.workflow-repair-service.enabled=false
```

#### Pattern 2: Sweeper-Only Instance
```properties
conductor.workflow-reconciler.enabled=true
conductor.system-task-workers.enabled=false
conductor.default-event-processor.enabled=false

# Tune sweeper performance
conductor.app.sweeperThreadCount=16
conductor.sweep-frequency.millis=250
```

#### Pattern 3: System Task Worker Instance
```properties
conductor.workflow-reconciler.enabled=false
conductor.system-task-workers.enabled=true
conductor.default-event-processor.enabled=false

# Tune worker performance
conductor.app.systemTaskWorkerThreadCount=20
conductor.app.systemTaskMaxPollCount=20
```

#### Pattern 4: Event Processor Instance
```properties
conductor.workflow-reconciler.enabled=false
conductor.system-task-workers.enabled=false
conductor.default-event-processor.enabled=true

# Tune event processing
conductor.app.eventProcessorThreadCount=8
conductor.app.eventQueuePollCount=20
```

#### Pattern 5: Monolithic (All Services - Default)
```properties
conductor.workflow-reconciler.enabled=true
conductor.system-task-workers.enabled=true
conductor.default-event-processor.enabled=true
```

---

## Monitoring and Troubleshooting

### Health Checks

```bash
# Check overall health
curl http://localhost:8080/health

# Check Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Key metrics to monitor:
# - conductor_server_workflow_running: Number of running workflows
# - conductor_server_task_poll: Task poll counts
# - conductor_server_workflow_start_error: Workflow start failures
# - conductor_server_task_execution_queue_full: Queue saturation
```

### Log Analysis

**API Server Logs**:
```bash
# Verify sweeper is disabled
podman logs conductor-api-server | grep "WorkflowReconciler"
# Should NOT appear

# Check workflow executions
podman logs conductor-api-server | grep "Started workflow"

# Check API requests
podman logs conductor-api-server | grep "POST /api/workflow"
```

**Sweeper Service Logs**:
```bash
# Verify sweeper is enabled
podman logs conductor-sweeper | grep "WorkflowReconciler"
# Should see: "WorkflowReconciler initialized"

# Check sweep activity
podman logs conductor-sweeper | grep "Sweeping workflow"

# Check lock acquisition
podman logs conductor-sweeper | grep "Acquired lock"
```

### Common Issues

#### Issue: Workflows Not Progressing
**Symptom**: Workflows stuck in running state, tasks not scheduling

**Diagnosis**:
```bash
# Check if sweeper is running
podman logs conductor-sweeper | tail -100

# Verify DECIDER_QUEUE has messages
# (Requires Redis CLI access)
podman exec conductor-redis redis-cli LLEN conductor_queues:_deciderQueue
```

**Solution**:
- Ensure `conductor.workflow-reconciler.enabled=true` on sweeper instance
- Check sweeper logs for errors
- Verify distributed locks are working (Redis connectivity)

#### Issue: Duplicate Sweeping
**Symptom**: Multiple sweeps happening simultaneously

**Diagnosis**:
```bash
# Check how many instances have sweeper enabled
podman ps | grep conductor

# Check logs for concurrent sweeps
podman logs conductor-api-server | grep "Sweeping"
podman logs conductor-sweeper | grep "Sweeping"
```

**Solution**:
- Ensure only ONE instance has `conductor.workflow-reconciler.enabled=true`
- Verify distributed locking is enabled
- Check lock configuration

#### Issue: Database Connection Errors
**Symptom**: `Connection refused` errors

**Diagnosis**:
```bash
# Check container networks
podman inspect conductor-sweeper | grep -A 10 Networks
podman inspect conductor-postgres | grep -A 10 Networks

# Verify connectivity
podman exec conductor-sweeper ping conductor-postgres
```

**Solution**:
- Ensure both containers are on the same network (or connected via bridge)
- Verify PostgreSQL is running: `podman ps | grep postgres`
- Check database URL in configuration

---

## Performance Tuning

### Sweeper Performance

**High Throughput** (many workflows):
```properties
conductor.app.sweeperThreadCount=32
conductor.sweep-frequency.millis=250
conductor.app.sweeperWorkflowPollTimeout=1000
```

**Low Latency** (few workflows, fast response):
```properties
conductor.app.sweeperThreadCount=8
conductor.sweep-frequency.millis=100
conductor.app.workflowOffsetTimeout=10s
```

**Resource Constrained**:
```properties
conductor.app.sweeperThreadCount=4
conductor.sweep-frequency.millis=1000
conductor.app.workflowOffsetTimeout=60s
```

### System Task Worker Performance

**CPU-Intensive Tasks**:
```properties
conductor.app.systemTaskWorkerThreadCount=16
conductor.app.systemTaskMaxPollCount=8
```

**I/O-Intensive Tasks** (HTTP calls):
```properties
conductor.app.systemTaskWorkerThreadCount=32
conductor.app.systemTaskMaxPollCount=16
```

### Lock Configuration

**Redis Lock** (Recommended for production):
```properties
conductor.workflow-execution-lock.type=redis
conductor.redis-lock.serverAddress=redis://conductor-redis:6379
conductor.app.lockLeaseTime=60000
conductor.app.lockTimeToTry=500
```

**Local Lock** (Development only):
```properties
conductor.workflow-execution-lock.type=local_only
```

---

## Summary

This guide covered:

1. **Conductor Core Architecture**: Key components and their interactions
2. **Workflow Execution Flow**: Detailed step-by-step process
3. **Key Components**: In-depth explanation of each service
4. **Distributed Deployment**: How to run services separately
5. **Sweeper Service Setup**: Complete guide to running sweeper independently
6. **Configuration Reference**: All relevant properties
7. **Monitoring**: Health checks and troubleshooting

### Key Takeaways

- **Conductor Core** = WorkflowExecutor + DeciderService + WorkflowSweeper
- **Sweeper** ensures workflow progression via background reconciliation
- **Services can be distributed** using enable/disable flags
- **Multi-network deployment** provides isolation and scalability
- **Proper locking** prevents concurrent workflow modifications
- **Configuration is flexible** for various deployment patterns

The distributed deployment pattern allows you to:
- Scale API servers independently for high request volume
- Scale sweeper services for workflow throughput
- Scale worker services for task execution
- Optimize resource allocation per workload type
