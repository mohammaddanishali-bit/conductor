# Start Workflow Flow - From REST to Database

This document traces the complete flow of starting a workflow in Netflix Conductor, from the REST API call all the way down to database persistence.

## Table of Contents
1. [Flow Overview](#flow-overview)
2. [Layer 1: REST Controller](#layer-1-rest-controller)
3. [Layer 2: Service Layer](#layer-2-service-layer)
4. [Layer 3: Execution Layer](#layer-3-execution-layer)
5. [Layer 4: DAO Facade Layer](#layer-4-dao-facade-layer)
6. [Layer 5: Database Layer](#layer-5-database-layer)
7. [Complete Flow Diagram](#complete-flow-diagram)

---

## Flow Overview

When a client wants to start a workflow, the request flows through these layers:

```
REST API → WorkflowService → WorkflowExecutor → ExecutionDAOFacade → ExecutionDAO → Database
  (HTTP)      (Business)         (Orchestration)      (Abstraction)      (Persistence)    (Storage)
```

**Key Operations at Each Layer:**
- **REST**: Receive HTTP request, validate, deserialize JSON
- **Service**: Business logic wrapper, prepare StartWorkflowInput
- **Executor**: Create workflow model, validate, acquire lock
- **Facade**: Externalize large payloads, coordinate DAO calls
- **DAO**: Persist to database (Redis/Postgres/etc.)

---

## Layer 1: REST Controller

**File**: `rest/src/main/java/com/netflix/conductor/rest/controllers/WorkflowResource.java`

### Endpoint Definition

The REST controller exposes two main endpoints for starting workflows:

```java
@RestController
@RequestMapping(WORKFLOW)  // Maps to /api/workflow
public class WorkflowResource {

    private final WorkflowService workflowService;

    // Method 1: Start with StartWorkflowRequest object
    @PostMapping(produces = TEXT_PLAIN_VALUE)
    @Operation(summary = "Start a new workflow with StartWorkflowRequest")
    public String startWorkflow(@RequestBody StartWorkflowRequest request) {
        return workflowService.startWorkflow(request);
    }

    // Method 2: Start with individual parameters
    @PostMapping(value = "/{name}", produces = TEXT_PLAIN_VALUE)
    @Operation(summary = "Start a new workflow. Returns the ID of the workflow instance")
    public String startWorkflow(
            @PathVariable("name") String name,
            @RequestParam(value = "version", required = false) Integer version,
            @RequestParam(value = "correlationId", required = false) String correlationId,
            @RequestParam(value = "priority", defaultValue = "0") int priority,
            @RequestBody Map<String, Object> input) {
        return workflowService.startWorkflow(name, version, correlationId, priority, input);
    }
}
```

### What Happens Here

**Purpose**: These endpoints receive HTTP POST requests to start a new workflow.

**Endpoint 1 - Using StartWorkflowRequest**:
- **URL**: `POST /api/workflow`
- **Body**: Complete StartWorkflowRequest JSON object
- **Example**:
  ```json
  {
    "name": "order_processing",
    "version": 1,
    "correlationId": "order-12345",
    "priority": 5,
    "input": {
      "orderId": "12345",
      "customerId": "customer-001",
      "items": ["item1", "item2"]
    },
    "taskToDomain": {
      "payment_task": "payment-domain"
    }
  }
  ```

**Endpoint 2 - Using Path and Query Parameters**:
- **URL**: `POST /api/workflow/{name}?version=1&correlationId=order-123&priority=5`
- **Body**: Just the workflow input data
- **Example**:
  ```json
  {
    "orderId": "12345",
    "customerId": "customer-001"
  }
  ```

**Key Points:**
- Both methods delegate immediately to `WorkflowService`
- The controller is thin - no business logic, just routing
- Returns the workflow ID as a plain text string
- Spring automatically deserializes JSON to Java objects

---

## Layer 2: Service Layer

**File**: `core/src/main/java/com/netflix/conductor/service/WorkflowServiceImpl.java`

### Service Implementation

```java
@Service
public class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowExecutor workflowExecutor;
    private final MetadataService metadataService;

    // Method 1: With StartWorkflowRequest
    @Override
    public String startWorkflow(StartWorkflowRequest startWorkflowRequest) {
        // Convert request to StartWorkflowInput and delegate to executor
        return workflowExecutor.startWorkflow(new StartWorkflowInput(startWorkflowRequest));
    }

    // Method 2: With individual parameters
    @Override
    public String startWorkflow(
            String name,
            Integer version,
            String correlationId,
            Integer priority,
            Map<String, Object> input) {

        // Step 1: Fetch the workflow definition from metadata store
        WorkflowDef workflowDef = metadataService.getWorkflowDef(name, version);
        if (workflowDef == null) {
            throw new NotFoundException(
                "No such workflow found by name: %s, version: %d", name, version);
        }

        // Step 2: Build StartWorkflowInput object
        StartWorkflowInput startWorkflowInput = new StartWorkflowInput();
        startWorkflowInput.setName(workflowDef.getName());
        startWorkflowInput.setVersion(workflowDef.getVersion());
        startWorkflowInput.setCorrelationId(correlationId);
        startWorkflowInput.setPriority(priority);
        startWorkflowInput.setWorkflowInput(input);

        // Step 3: Delegate to executor
        return workflowExecutor.startWorkflow(startWorkflowInput);
    }
}
```

### What Happens Here

**Purpose**: The service layer acts as a facade for business logic and prepares data for the execution layer.

**Key Responsibilities:**
1. **Fetch Workflow Definition**: If not provided, look up the workflow definition from the metadata store
   - Workflow definitions contain the blueprint (tasks, sequence, configuration)
   - Stored in `meta_workflow_def` table or Redis metadata structures

2. **Build StartWorkflowInput**: Create a standardized input object
   - Combines all parameters needed to start a workflow
   - Ensures consistency regardless of which REST endpoint was called

3. **Validation**: Check that the workflow definition exists
   - Throws `NotFoundException` if workflow definition is missing

**StartWorkflowInput Object Structure:**
```java
public class StartWorkflowInput {
    private String name;                              // Workflow name
    private Integer version;                           // Workflow version
    private WorkflowDef workflowDefinition;           // Complete workflow definition (optional)
    private Map<String, Object> workflowInput;        // Input data for the workflow
    private String externalInputPayloadStoragePath;   // Path if input is in external storage
    private String correlationId;                      // For tracking related workflows
    private Integer priority;                          // Execution priority (0-99)
    private String parentWorkflowId;                   // If this is a sub-workflow
    private String parentWorkflowTaskId;               // Parent task that spawned this
    private String event;                              // Event that triggered this workflow
    private Map<String, String> taskToDomain;         // Task-to-domain mapping
    private String workflowId;                         // Pre-assigned ID (usually null)
    private String triggeringWorkflowId;               // Workflow that triggered this one
}
```

---

## Layer 3: Execution Layer

**File**: `core/src/main/java/com/netflix/conductor/core/execution/WorkflowExecutorOps.java`

### Workflow Executor Implementation

```java
@Component
public class WorkflowExecutorOps implements WorkflowExecutor {

    private final MetadataMapperService metadataMapperService;
    private final ExecutionDAOFacade executionDAOFacade;
    private final ParametersUtils parametersUtils;
    private final IDGenerator idGenerator;
    private final ExecutionLockService executionLockService;

    @Override
    public String startWorkflow(StartWorkflowInput input) {
        // STEP 1: Get or validate workflow definition
        WorkflowDef workflowDefinition;
        if (input.getWorkflowDefinition() == null) {
            // Look up workflow definition by name and version
            workflowDefinition = metadataMapperService.lookupForWorkflowDefinition(
                input.getName(),
                input.getVersion()
            );
        } else {
            workflowDefinition = input.getWorkflowDefinition();
        }

        // STEP 2: Populate task definitions in the workflow
        // This resolves task references and loads task definitions
        workflowDefinition = metadataMapperService.populateTaskDefinitions(workflowDefinition);

        // STEP 3: Validate workflow input
        Map<String, Object> workflowInput = input.getWorkflowInput();
        String externalInputPayloadStoragePath = input.getExternalInputPayloadStoragePath();
        validateWorkflow(workflowDefinition, workflowInput, externalInputPayloadStoragePath);

        // STEP 4: Generate workflow ID if not provided
        String workflowId = Optional.ofNullable(input.getWorkflowId())
                                    .orElseGet(idGenerator::generate);

        // STEP 5: Create WorkflowModel object (the runtime instance)
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId(workflowId);
        workflow.setCorrelationId(input.getCorrelationId());
        workflow.setPriority(input.getPriority() == null ? 0 : input.getPriority());
        workflow.setWorkflowDefinition(workflowDefinition);
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setParentWorkflowId(input.getParentWorkflowId());
        workflow.setParentWorkflowTaskId(input.getParentWorkflowTaskId());
        workflow.setOwnerApp(WorkflowContext.get().getClientApp());
        workflow.setCreateTime(System.currentTimeMillis());
        workflow.setEvent(input.getEvent());
        workflow.setTaskToDomain(input.getTaskToDomain());
        workflow.setVariables(workflowDefinition.getVariables());

        // STEP 6: Process and set workflow input
        if (workflowInput != null && !workflowInput.isEmpty()) {
            // Parse input parameters using the workflow definition
            Map<String, Object> parsedInput =
                parametersUtils.getWorkflowInput(workflowDefinition, workflowInput);
            workflow.setInput(parsedInput);
        } else {
            // Input is stored externally (S3, Azure Blob, etc.)
            workflow.setExternalInputPayloadStoragePath(externalInputPayloadStoragePath);
        }

        // STEP 7: Create and evaluate the workflow
        try {
            createAndEvaluate(workflow);

            // Record success metrics
            Monitors.recordWorkflowStartSuccess(
                workflow.getWorkflowName(),
                String.valueOf(workflow.getWorkflowVersion()),
                workflow.getOwnerApp()
            );

            return workflowId;
        } catch (Exception e) {
            // Record failure metrics
            Monitors.recordWorkflowStartError(
                workflowDefinition.getName(),
                WorkflowContext.get().getClientApp()
            );

            LOGGER.error("Unable to start workflow: {}", workflowDefinition.getName(), e);

            // Clean up: remove the workflow if creation failed
            try {
                executionDAOFacade.removeWorkflow(workflowId, false);
            } catch (Exception rwe) {
                LOGGER.error("Could not remove the workflowId: " + workflowId, rwe);
            }

            throw e;
        }
    }

    // Helper method: Create workflow and run decider
    private void createAndEvaluate(WorkflowModel workflow) {
        // STEP 1: Acquire distributed lock
        // This prevents concurrent modifications to the same workflow
        if (!executionLockService.acquireLock(workflow.getWorkflowId())) {
            throw new TransientException("Error acquiring lock when creating workflow");
        }

        try {
            // STEP 2: Persist workflow to database
            executionDAOFacade.createWorkflow(workflow);

            LOGGER.debug(
                "A new instance of workflow: {} created with id: {}",
                workflow.getWorkflowName(),
                workflow.getWorkflowId()
            );

            // STEP 3: Load external payload data if needed
            executionDAOFacade.populateWorkflowAndTaskPayloadData(workflow);

            // STEP 4: Notify listeners that workflow started
            notifyWorkflowStatusListener(workflow, WorkflowEventType.STARTED);

            // STEP 5: Run the decider to schedule initial tasks
            decide(workflow);

        } finally {
            // STEP 6: Always release the lock
            executionLockService.releaseLock(workflow.getWorkflowId());
        }
    }

    // Validation method
    private void validateWorkflow(
            WorkflowDef workflowDef,
            Map<String, Object> workflowInput,
            String externalStoragePath) {

        // Check if either input or external storage path is provided
        if (workflowInput == null && StringUtils.isBlank(externalStoragePath)) {
            LOGGER.error("The input for the workflow '{}' cannot be NULL", workflowDef.getName());

            Monitors.recordWorkflowStartError(
                workflowDef.getName(),
                WorkflowContext.get().getClientApp()
            );

            throw new IllegalArgumentException("NULL input passed when starting workflow");
        }
    }
}
```

### What Happens Here

**Purpose**: The executor orchestrates the workflow creation process and manages the workflow lifecycle.

**Key Steps Explained:**

1. **Workflow Definition Lookup**:
   - Gets the workflow blueprint that defines what tasks to run
   - Example: "order_processing" workflow has tasks like "validate_order", "charge_payment", "ship_order"

2. **Task Definition Population**:
   - Resolves all task references in the workflow
   - Loads task-specific configurations (timeouts, retry policies, etc.)

3. **Input Validation**:
   - Ensures workflow has either inline input or external storage path
   - Prevents starting a workflow without any input data

4. **ID Generation**:
   - Generates a unique UUID for this workflow instance
   - Example: `"3fa85f64-5717-4562-b3fc-2c963f66afa6"`

5. **WorkflowModel Creation**:
   - **WorkflowModel** = Runtime instance of a workflow
   - **WorkflowDef** = Blueprint/definition
   - Sets initial status to `RUNNING`
   - Records creation timestamp, owner app, correlation ID

6. **Distributed Locking**:
   - Acquires a lock on the workflow ID
   - Prevents race conditions if multiple requests try to start the same workflow
   - Uses Redis-based locking mechanism

7. **Decider Invocation**:
   - The "decide" process evaluates the workflow state
   - Determines which tasks should be scheduled next
   - Adds tasks to queues for workers to pick up

**Important Objects:**

**WorkflowModel vs WorkflowDef:**
```java
// WorkflowDef - The blueprint (stored in metadata)
{
    "name": "order_processing",
    "version": 1,
    "tasks": [
        {"name": "validate_order", "type": "SIMPLE"},
        {"name": "charge_payment", "type": "SIMPLE"},
        {"name": "ship_order", "type": "SIMPLE"}
    ]
}

// WorkflowModel - The runtime instance (stored in execution store)
{
    "workflowId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "workflowName": "order_processing",
    "workflowVersion": 1,
    "status": "RUNNING",
    "createTime": 1699564800000,
    "input": {"orderId": "12345"},
    "tasks": [
        // Task instances will be added as they're scheduled
    ]
}
```

---

## Layer 4: DAO Facade Layer

**File**: `core/src/main/java/com/netflix/conductor/core/dal/ExecutionDAOFacade.java`

### ExecutionDAOFacade Implementation

```java
@Component
public class ExecutionDAOFacade {

    private final ExecutionDAO executionDAO;
    private final QueueDAO queueDAO;
    private final IndexDAO indexDAO;
    private final ObjectMapper objectMapper;
    private final ConductorProperties properties;
    private final ExternalPayloadStorageUtils externalPayloadStorageUtils;

    /**
     * Creates a new workflow in the data store
     * @param workflowModel the workflow to be created
     * @return the id of the created workflow
     */
    public String createWorkflow(WorkflowModel workflowModel) {
        // STEP 1: Externalize large payloads if needed
        externalizeWorkflowData(workflowModel);

        // STEP 2: Persist workflow to primary datastore (Redis/Postgres/etc.)
        executionDAO.createWorkflow(workflowModel);

        // STEP 3: Add workflow to decider queue
        // The decider queue is processed asynchronously to schedule tasks
        queueDAO.push(
            DECIDER_QUEUE,                                          // Queue name
            workflowModel.getWorkflowId(),                         // Workflow ID (message)
            workflowModel.getPriority(),                            // Priority (0-99)
            properties.getWorkflowOffsetTimeout().getSeconds()      // Delay (usually 0)
        );

        // STEP 4: Index workflow for searching
        if (properties.isAsyncIndexingEnabled()) {
            // Asynchronous indexing (faster, eventual consistency)
            indexDAO.asyncIndexWorkflow(new WorkflowSummary(workflowModel.toWorkflow()));
        } else {
            // Synchronous indexing (slower, immediate consistency)
            indexDAO.indexWorkflow(new WorkflowSummary(workflowModel.toWorkflow()));
        }

        return workflowModel.getWorkflowId();
    }

    // Helper method: Check if payload is too large and externalize if needed
    private void externalizeWorkflowData(WorkflowModel workflowModel) {
        // Check and upload workflow input if it exceeds threshold
        externalPayloadStorageUtils.verifyAndUpload(
            workflowModel,
            ExternalPayloadStorage.PayloadType.WORKFLOW_INPUT
        );

        // Check and upload workflow output if it exceeds threshold
        externalPayloadStorageUtils.verifyAndUpload(
            workflowModel,
            ExternalPayloadStorage.PayloadType.WORKFLOW_OUTPUT
        );
    }
}
```

### What Happens Here

**Purpose**: The DAO Facade coordinates multiple data access operations and provides a unified interface.

**Key Responsibilities:**

1. **External Payload Storage**:
   - **Problem**: Workflow input/output can be very large (MB or GB)
   - **Solution**: Store large payloads in external storage (S3, Azure Blob)
   - **Threshold**: Configurable (default: 5KB for PostgreSQL, 10KB for Redis)
   - **Example**:
     ```java
     // If input is > 5KB
     Original: workflow.input = {"data": "... 50KB of data ..."}
     After:    workflow.input = null
               workflow.externalInputPayloadStoragePath = "s3://bucket/workflow/123/input.json"
     ```

2. **Primary Datastore Persistence**:
   - Calls the appropriate ExecutionDAO implementation
   - Could be Redis, PostgreSQL, MySQL, Cassandra, or SQLite
   - Stores the workflow model as JSON

3. **Decider Queue**:
   - **What**: A queue that holds workflow IDs that need to be evaluated
   - **Why**: Decouples workflow creation from task scheduling
   - **How**: Async workers poll this queue and run the decider
   - **Priority**: Higher priority workflows are processed first
   - **Example**:
     ```
     DECIDER_QUEUE:
     - Priority 10: workflow-urgent-123
     - Priority 5:  workflow-normal-456
     - Priority 0:  workflow-low-789
     ```

4. **Indexing for Search**:
   - Stores workflow summary in ElasticSearch/OpenSearch
   - Enables searching workflows by:
     - Workflow name
     - Status
     - Correlation ID
     - Start time
     - Custom fields in input/output
   - **Async vs Sync**:
     - **Async**: Fast workflow creation, search may lag slightly
     - **Sync**: Slower workflow creation, search immediately available

---

## Layer 5: Database Layer

### 5.1 Redis Implementation

**File**: `redis-persistence/src/main/java/com/netflix/conductor/redis/dao/RedisExecutionDAO.java`

```java
@Component
@Conditional(RedisConditions.RedisEnabled.class)
public class RedisExecutionDAO implements ExecutionDAO {

    private final JedisProxy jedisProxy;
    private final ObjectMapper objectMapper;

    // Key prefixes used in Redis
    private static final String WORKFLOW = "WORKFLOW";
    private static final String WORKFLOW_DEF_TO_WORKFLOWS = "WORKFLOW_DEF_TO_WORKFLOWS";
    private static final String CORR_ID_TO_WORKFLOWS = "CORR_ID_TO_WORKFLOWS";
    private static final String PENDING_WORKFLOWS = "PENDING_WORKFLOWS";

    @Override
    public String createWorkflow(WorkflowModel workflow) {
        // Delegate to common insert/update method
        return insertOrUpdateWorkflow(workflow, false);
    }

    private String insertOrUpdateWorkflow(WorkflowModel workflow, boolean update) {
        Preconditions.checkNotNull(workflow, "workflow object cannot be null");

        // STEP 1: Separate tasks from workflow for storage
        // Tasks are stored separately to optimize queries
        List<TaskModel> tasks = workflow.getTasks();
        workflow.setTasks(new LinkedList<>());

        // STEP 2: Serialize workflow to JSON
        String payload = toJson(workflow);

        // STEP 3: Store workflow object
        // Key: conductor:WORKFLOW:{workflowId}
        // Value: JSON of workflow
        jedisProxy.set(nsKey(WORKFLOW, workflow.getWorkflowId()), payload);

        recordRedisDaoRequests("storeWorkflow", "n/a", workflow.getWorkflowName());
        recordRedisDaoPayloadSize("storeWorkflow", payload.length(), "n/a", workflow.getWorkflowName());

        if (!update) {
            // STEP 4: Add to workflow definition mapping
            // Allows querying: "Show me all instances of order_processing workflow from today"
            // Key: conductor:WORKFLOW_DEF_TO_WORKFLOWS:order_processing:2024-11-11
            // Type: SET
            // Value: {workflowId1, workflowId2, workflowId3}
            String key = nsKey(
                WORKFLOW_DEF_TO_WORKFLOWS,
                workflow.getWorkflowName(),
                dateStr(workflow.getCreateTime())  // YYYY-MM-DD format
            );
            jedisProxy.sadd(key, workflow.getWorkflowId());

            // STEP 5: Add to correlation ID mapping (if correlation ID exists)
            // Allows querying: "Show me all workflows for order-12345"
            // Key: conductor:CORR_ID_TO_WORKFLOWS:order-12345
            // Type: SET
            // Value: {workflowId1, workflowId2}
            if (workflow.getCorrelationId() != null) {
                jedisProxy.sadd(
                    nsKey(CORR_ID_TO_WORKFLOWS, workflow.getCorrelationId()),
                    workflow.getWorkflowId()
                );
            }
        }

        // STEP 6: Manage pending workflows set
        // Tracks non-terminal workflows for each workflow type
        // Key: conductor:PENDING_WORKFLOWS:order_processing
        // Type: SET
        // Value: {runningWorkflowId1, runningWorkflowId2}
        if (workflow.getStatus().isTerminal()) {
            // Remove from pending if workflow completed
            jedisProxy.srem(
                nsKey(PENDING_WORKFLOWS, workflow.getWorkflowName()),
                workflow.getWorkflowId()
            );
        } else {
            // Add to pending if workflow is still running
            jedisProxy.sadd(
                nsKey(PENDING_WORKFLOWS, workflow.getWorkflowName()),
                workflow.getWorkflowId()
            );
        }

        // Restore tasks list
        workflow.setTasks(tasks);
        return workflow.getWorkflowId();
    }

    // Namespace key helper
    private String nsKey(String... parts) {
        return "conductor:" + String.join(":", parts);
    }
}
```

### What Gets Stored in Redis

**1. Main Workflow Object**:
```
Key:   conductor:WORKFLOW:3fa85f64-5717-4562-b3fc-2c963f66afa6
Type:  STRING
Value: {
  "workflowId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "workflowName": "order_processing",
  "workflowVersion": 1,
  "status": "RUNNING",
  "createTime": 1699564800000,
  "correlationId": "order-12345",
  "input": {"orderId": "12345"},
  "tasks": []  // Empty, tasks stored separately
}
```

**2. Workflow Definition to Workflows Mapping**:
```
Key:   conductor:WORKFLOW_DEF_TO_WORKFLOWS:order_processing:2024-11-11
Type:  SET
Value: [
  "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "7bc91a32-8945-4d73-a2ef-1b945c77def1",
  "8cd02b43-9a56-5e84-b3a0-2c856d88eba2"
]
```

**3. Correlation ID Mapping**:
```
Key:   conductor:CORR_ID_TO_WORKFLOWS:order-12345
Type:  SET
Value: [
  "3fa85f64-5717-4562-b3fc-2c963f66afa6"
]
```

**4. Pending Workflows**:
```
Key:   conductor:PENDING_WORKFLOWS:order_processing
Type:  SET
Value: [
  "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "7bc91a32-8945-4d73-a2ef-1b945c77def1"
]
```

---

### 5.2 PostgreSQL Implementation

**File**: `postgres-persistence/src/main/java/com/netflix/conductor/postgres/dao/PostgresExecutionDAO.java`

```java
@Conditional(PostgresConditions.PostgresEnabled.class)
@Component
public class PostgresExecutionDAO extends BasePostgresDAO implements ExecutionDAO {

    @Override
    public String createWorkflow(WorkflowModel workflow) {
        // Delegate to common insert/update method
        return insertOrUpdateWorkflow(workflow, false);
    }

    private String insertOrUpdateWorkflow(WorkflowModel workflow, boolean update) {
        Preconditions.checkNotNull(workflow, "workflow object cannot be null");

        boolean terminal = workflow.getStatus().isTerminal();

        // STEP 1: Separate tasks from workflow
        List<TaskModel> tasks = workflow.getTasks();
        workflow.setTasks(Lists.newLinkedList());

        // STEP 2: Execute database operations in a transaction
        withTransaction(tx -> {
            if (!update) {
                // CREATE operations
                addWorkflow(tx, workflow);
                addWorkflowDefToWorkflowMapping(tx, workflow);
            } else {
                // UPDATE operations
                updateWorkflow(tx, workflow);
            }

            // STEP 3: Manage pending workflow tracking
            if (terminal) {
                // Workflow finished, remove from pending
                removePendingWorkflow(tx, workflow.getWorkflowName(), workflow.getWorkflowId());
            } else {
                // Workflow running, add to pending
                addPendingWorkflow(tx, workflow.getWorkflowName(), workflow.getWorkflowId());
            }
        });

        // Restore tasks
        workflow.setTasks(tasks);
        return workflow.getWorkflowId();
    }

    // Insert workflow into main table
    private void addWorkflow(Connection connection, WorkflowModel workflow) {
        String INSERT_WORKFLOW =
            "INSERT INTO workflow (workflow_id, correlation_id, json_data, date_str) " +
            "VALUES (?, ?, ?::jsonb, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(INSERT_WORKFLOW)) {
            stmt.setString(1, workflow.getWorkflowId());
            stmt.setString(2, workflow.getCorrelationId());
            stmt.setString(3, toJson(workflow));  // Store complete workflow as JSONB
            stmt.setString(4, dateStr(workflow.getCreateTime()));  // Date for partitioning
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new ApplicationException(ApplicationException.Code.BACKEND_ERROR,
                "Error creating workflow: " + workflow.getWorkflowId(), e);
        }
    }

    // Create mapping from workflow definition to workflow instances
    private void addWorkflowDefToWorkflowMapping(Connection connection, WorkflowModel workflow) {
        String INSERT_MAPPING =
            "INSERT INTO workflow_def_to_workflow (workflow_def, workflow_id, date_str) " +
            "VALUES (?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(INSERT_MAPPING)) {
            // This enables queries like: "Get all order_processing workflows from today"
            stmt.setString(1, workflow.getWorkflowName());
            stmt.setString(2, workflow.getWorkflowId());
            stmt.setString(3, dateStr(workflow.getCreateTime()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new ApplicationException(ApplicationException.Code.BACKEND_ERROR,
                "Error creating workflow mapping", e);
        }
    }

    // Track pending (non-terminal) workflows
    private void addPendingWorkflow(Connection connection, String workflowName, String workflowId) {
        String INSERT_PENDING =
            "INSERT INTO workflow_pending (workflow_type, workflow_id) " +
            "VALUES (?, ?) " +
            "ON CONFLICT DO NOTHING";  // Ignore if already exists

        try (PreparedStatement stmt = connection.prepareStatement(INSERT_PENDING)) {
            stmt.setString(1, workflowName);
            stmt.setString(2, workflowId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new ApplicationException(ApplicationException.Code.BACKEND_ERROR,
                "Error adding pending workflow", e);
        }
    }
}
```

### What Gets Stored in PostgreSQL

**1. Main Workflow Table**:
```sql
-- Table: workflow
INSERT INTO workflow (workflow_id, correlation_id, json_data, date_str)
VALUES (
  '3fa85f64-5717-4562-b3fc-2c963f66afa6',
  'order-12345',
  '{
    "workflowId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "workflowName": "order_processing",
    "workflowVersion": 1,
    "status": "RUNNING",
    "createTime": 1699564800000,
    "correlationId": "order-12345",
    "input": {"orderId": "12345"},
    "tasks": []
  }'::jsonb,
  '2024-11-11'
);
```

**2. Workflow Definition Mapping Table**:
```sql
-- Table: workflow_def_to_workflow
INSERT INTO workflow_def_to_workflow (workflow_def, workflow_id, date_str)
VALUES ('order_processing', '3fa85f64-5717-4562-b3fc-2c963f66afa6', '2024-11-11');

-- Enables query:
SELECT workflow_id
FROM workflow_def_to_workflow
WHERE workflow_def = 'order_processing'
  AND date_str = '2024-11-11';
```

**3. Pending Workflows Table**:
```sql
-- Table: workflow_pending
INSERT INTO workflow_pending (workflow_type, workflow_id)
VALUES ('order_processing', '3fa85f64-5717-4562-b3fc-2c963f66afa6');

-- Enables query:
SELECT COUNT(*)
FROM workflow_pending
WHERE workflow_type = 'order_processing';
```

---

## Complete Flow Diagram

### Visual Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENT APPLICATION                           │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 │ POST /api/workflow/order_processing
                 │ Body: {"orderId": "12345"}
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         LAYER 1: REST                                │
│  File: WorkflowResource.java                                         │
├─────────────────────────────────────────────────────────────────────┤
│  @PostMapping("/{name}")                                             │
│  • Receives HTTP request                                             │
│  • Extracts path parameters (name, version)                          │
│  • Extracts query parameters (correlationId, priority)               │
│  • Deserializes JSON body to Map                                     │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 │ workflowService.startWorkflow(name, version, ...)
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      LAYER 2: SERVICE                                │
│  File: WorkflowServiceImpl.java                                      │
├─────────────────────────────────────────────────────────────────────┤
│  • Fetches WorkflowDef from metadata store                           │
│  • Validates workflow definition exists                              │
│  • Creates StartWorkflowInput object                                 │
│  • Sets all input parameters                                         │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 │ workflowExecutor.startWorkflow(startWorkflowInput)
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    LAYER 3: EXECUTION                                │
│  File: WorkflowExecutorOps.java                                      │
├─────────────────────────────────────────────────────────────────────┤
│  1. Lookup/Validate WorkflowDef                                      │
│  2. Populate task definitions                                        │
│  3. Validate workflow input                                          │
│  4. Generate unique workflow ID                                      │
│  5. Create WorkflowModel object:                                     │
│     • workflowId: 3fa85f64-5717-4562-b3fc-2c963f66afa6               │
│     • status: RUNNING                                                │
│     • createTime: 1699564800000                                      │
│     • input: {"orderId": "12345"}                                    │
│  6. Acquire distributed lock                                         │
│  7. Call createAndEvaluate()                                         │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 │ executionDAOFacade.createWorkflow(workflow)
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     LAYER 4: DAO FACADE                              │
│  File: ExecutionDAOFacade.java                                       │
├─────────────────────────────────────────────────────────────────────┤
│  1. Externalize large payloads (if input > 5KB)                      │
│  2. executionDAO.createWorkflow(workflowModel)                       │
│  3. queueDAO.push(DECIDER_QUEUE, workflowId, priority)               │
│  4. indexDAO.asyncIndexWorkflow(workflowSummary)                     │
└────────────────┬────────────────────────────────────────────────────┘
                 │
                 │ executionDAO.createWorkflow(workflowModel)
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    LAYER 5: DATABASE                                 │
│  Files: RedisExecutionDAO.java / PostgresExecutionDAO.java           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌────────────────────┐      ┌──────────────────────┐               │
│  │   REDIS STORAGE    │      │  POSTGRES STORAGE    │               │
│  ├────────────────────┤      ├──────────────────────┤               │
│  │ 1. Store workflow  │      │ 1. Insert workflow   │               │
│  │    SET workflow:id │      │    INTO workflow     │               │
│  │                    │      │                      │               │
│  │ 2. Add to def map  │      │ 2. Insert mapping    │               │
│  │    SADD def:name   │      │    INTO workflow_    │               │
│  │                    │      │    def_to_workflow   │               │
│  │ 3. Add to corr map │      │                      │               │
│  │    SADD corr:id    │      │ 3. Insert pending    │               │
│  │                    │      │    INTO workflow_    │               │
│  │ 4. Add to pending  │      │    pending           │               │
│  │    SADD pending    │      │                      │               │
│  └────────────────────┘      └──────────────────────┘               │
└────────────────┬────────────────┬───────────────────────────────────┘
                 │                │
                 ▼                ▼
     ┌────────────────┐  ┌──────────────────┐
     │  Redis Store   │  │  PostgreSQL DB   │
     │  Key-Value     │  │  Relational      │
     └────────────────┘  └──────────────────┘
```

### Sequence Diagram

```
Client          REST          Service        Executor        Facade          DAO           Database
  │              │              │              │              │              │              │
  │─POST────────>│              │              │              │              │              │
  │ /workflow/   │              │              │              │              │              │
  │ order_proc   │              │              │              │              │              │
  │              │              │              │              │              │              │
  │              │─startWork──>│              │              │              │              │
  │              │ flow()       │              │              │              │              │
  │              │              │              │              │              │              │
  │              │              │─getWorkflow─>│              │              │              │
  │              │              │ Def()        │              │              │              │
  │              │              │<─WorkflowDef─│              │              │              │
  │              │              │              │              │              │              │
  │              │              │─startWorkflow────────────>│              │              │
  │              │              │ (input)      │              │              │              │
  │              │              │              │              │              │              │
  │              │              │              │─validate────>│              │              │
  │              │              │              │ Input()      │              │              │
  │              │              │              │              │              │              │
  │              │              │              │─generate────>│              │              │
  │              │              │              │ WorkflowId() │              │              │
  │              │              │              │              │              │              │
  │              │              │              │─create──────>│              │              │
  │              │              │              │ WorkflowModel│              │              │
  │              │              │              │              │              │              │
  │              │              │              │─acquire─────>│              │              │
  │              │              │              │ Lock()       │              │              │
  │              │              │              │              │              │              │
  │              │              │              │─createWork──────────────>│              │
  │              │              │              │ flow()       │              │              │
  │              │              │              │              │              │              │
  │              │              │              │              │─externalize─>│              │
  │              │              │              │              │ Payload()    │              │
  │              │              │              │              │              │              │
  │              │              │              │              │─create──────────────────>│
  │              │              │              │              │ Workflow()   │              │
  │              │              │              │              │              │              │
  │              │              │              │              │              │─INSERT ─────>│
  │              │              │              │              │              │ workflow     │
  │              │              │              │              │              │              │
  │              │              │              │              │              │─INSERT ─────>│
  │              │              │              │              │              │ mapping      │
  │              │              │              │              │              │              │
  │              │              │              │              │              │─INSERT ─────>│
  │              │              │              │              │              │ pending      │
  │              │              │              │              │              │              │
  │              │              │              │              │              │<─Success─────│
  │              │              │              │              │<─workflowId──│              │
  │              │              │              │              │              │              │
  │              │              │              │<─workflowId──│              │              │
  │              │              │<─workflowId──│              │              │              │
  │              │<─workflowId──│              │              │              │              │
  │<─workflowId──│              │              │              │              │              │
  │ (200 OK)     │              │              │              │              │              │
```

---

## Summary: Complete Flow in Steps

### Step-by-Step Breakdown

1. **Client sends HTTP POST request**
   - Endpoint: `POST /api/workflow/order_processing?correlationId=order-123`
   - Body: `{"orderId": "12345"}`

2. **REST Controller receives request**
   - Class: `WorkflowResource.startWorkflow()`
   - Extracts parameters: name, version, correlationId, priority, input
   - Calls service layer

3. **Service Layer processes request**
   - Class: `WorkflowServiceImpl.startWorkflow()`
   - Fetches workflow definition from metadata store
   - Creates `StartWorkflowInput` object
   - Calls executor

4. **Executor creates workflow instance**
   - Class: `WorkflowExecutorOps.startWorkflow()`
   - Validates input
   - Generates unique workflow ID: `3fa85f64-5717-4562-b3fc-2c963f66afa6`
   - Creates `WorkflowModel` object with status `RUNNING`
   - Acquires distributed lock
   - Calls DAO facade

5. **DAO Facade coordinates persistence**
   - Class: `ExecutionDAOFacade.createWorkflow()`
   - Checks if payload is too large, externalizes if needed
   - Calls execution DAO to persist
   - Adds workflow ID to decider queue
   - Indexes workflow for search

6. **Database Layer stores workflow**
   - **Redis**: Stores JSON in key-value format, adds to multiple indices
   - **PostgreSQL**: Inserts into `workflow` table, creates mappings
   - Returns workflow ID

7. **Response flows back**
   - DAO → Facade → Executor → Service → Controller → Client
   - Client receives: `3fa85f64-5717-4562-b3fc-2c963f66afa6`

8. **Async processing begins**
   - Decider polls `DECIDER_QUEUE`
   - Finds new workflow ID
   - Evaluates workflow state
   - Schedules initial tasks
   - Workers poll task queues and execute tasks

---

## Key Concepts

### Workflow vs Workflow Instance
- **WorkflowDef**: Blueprint/template stored in metadata
- **WorkflowModel**: Runtime instance with specific input and state

### Synchronous vs Asynchronous Operations
- **Synchronous**: `createWorkflow()` - blocks until DB write completes
- **Asynchronous**: `indexWorkflow()` - returns immediately, indexes in background

### Distributed Locking
- Prevents concurrent modifications
- Uses Redis-based locks
- Always released in `finally` block

### External Payload Storage
- Large inputs/outputs stored in S3/Azure Blob
- Only reference path stored in database
- Configurable size threshold

### Decider Pattern
- Workflows added to decider queue after creation
- Decider evaluates state and schedules tasks
- Runs asynchronously in background workers

---

## Example: Complete Workflow Creation

### HTTP Request
```bash
curl -X POST "http://localhost:8080/api/workflow/order_processing?correlationId=order-123&priority=5" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "12345",
    "customerId": "customer-001",
    "items": ["item1", "item2"],
    "totalAmount": 99.99
  }'
```

### HTTP Response
```
3fa85f64-5717-4562-b3fc-2c963f66afa6
```

### What's Stored

**Redis**:
```
# Main workflow
SET conductor:WORKFLOW:3fa85f64-5717-4562-b3fc-2c963f66afa6
{
  "workflowId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "workflowName": "order_processing",
  "status": "RUNNING",
  "correlationId": "order-123",
  "priority": 5,
  "input": {
    "orderId": "12345",
    "customerId": "customer-001",
    "items": ["item1", "item2"],
    "totalAmount": 99.99
  }
}

# Workflow definition mapping
SADD conductor:WORKFLOW_DEF_TO_WORKFLOWS:order_processing:2024-11-11
  → 3fa85f64-5717-4562-b3fc-2c963f66afa6

# Correlation ID mapping
SADD conductor:CORR_ID_TO_WORKFLOWS:order-123
  → 3fa85f64-5717-4562-b3fc-2c963f66afa6

# Pending workflows
SADD conductor:PENDING_WORKFLOWS:order_processing
  → 3fa85f64-5717-4562-b3fc-2c963f66afa6

# Decider queue
ZADD conductor:DECIDER_QUEUE 5
  → 3fa85f64-5717-4562-b3fc-2c963f66afa6
```

**PostgreSQL**:
```sql
-- Workflow table
INSERT INTO workflow (workflow_id, correlation_id, json_data, date_str)
VALUES (
  '3fa85f64-5717-4562-b3fc-2c963f66afa6',
  'order-123',
  '{"workflowId": "3fa85f64-...", "workflowName": "order_processing", ...}'::jsonb,
  '2024-11-11'
);

-- Mapping table
INSERT INTO workflow_def_to_workflow (workflow_def, workflow_id, date_str)
VALUES ('order_processing', '3fa85f64-5717-4562-b3fc-2c963f66afa6', '2024-11-11');

-- Pending table
INSERT INTO workflow_pending (workflow_type, workflow_id)
VALUES ('order_processing', '3fa85f64-5717-4562-b3fc-2c963f66afa6');
```

**ElasticSearch/OpenSearch**:
```json
POST /conductor/_doc/3fa85f64-5717-4562-b3fc-2c963f66afa6
{
  "workflowId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "workflowType": "order_processing",
  "version": 1,
  "status": "RUNNING",
  "startTime": "2024-11-11T10:30:00Z",
  "correlationId": "order-123",
  "priority": 5
}
```

---

## Files Reference

### Core Files
1. `rest/src/main/java/com/netflix/conductor/rest/controllers/WorkflowResource.java:58-77`
2. `core/src/main/java/com/netflix/conductor/service/WorkflowServiceImpl.java:61-131`
3. `core/src/main/java/com/netflix/conductor/core/execution/WorkflowExecutorOps.java:1873-1960`
4. `core/src/main/java/com/netflix/conductor/core/dal/ExecutionDAOFacade.java:249-264`
5. `redis-persistence/src/main/java/com/netflix/conductor/redis/dao/RedisExecutionDAO.java:415-631`
6. `postgres-persistence/src/main/java/com/netflix/conductor/postgres/dao/PostgresExecutionDAO.java:305-605`

### Database Schema Files
1. `redis-persistence/src/main/java/com/netflix/conductor/redis/dao/RedisExecutionDAO.java:48-60` (Key prefixes)
2. `postgres-persistence/src/main/resources/db/migration_postgres/V1__initial_schema.sql:104-173` (Tables)

---

## Next Steps After Workflow Creation

After the workflow is created and stored:

1. **Decider Evaluation**:
   - Worker polls `DECIDER_QUEUE`
   - Runs `DeciderService.decide(workflowId)`
   - Determines which tasks to schedule

2. **Task Scheduling**:
   - Creates `TaskModel` instances
   - Adds tasks to task-specific queues
   - Example: `order_processing.validate_order` queue

3. **Worker Execution**:
   - Workers poll task queues
   - Execute tasks
   - Update task status
   - Trigger next decider run

4. **Workflow Completion**:
   - When all tasks complete, workflow status → `COMPLETED`
   - Removed from pending workflows
   - Triggers workflow completion listeners
   - Can be archived based on retention policy

---

## Conclusion

The startWorkflow flow demonstrates Conductor's layered architecture:

- **Separation of Concerns**: Each layer has a specific responsibility
- **Pluggable Storage**: Easy to swap Redis for PostgreSQL or other stores
- **Async Processing**: Decoupling creation from execution for scalability
- **Distributed Systems**: Locking, queuing, and external storage for scale
- **Observability**: Metrics, logging, and indexing at every layer

This architecture allows Conductor to handle millions of workflows reliably and at scale.
