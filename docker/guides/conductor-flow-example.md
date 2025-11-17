# Conductor Complete Flow Example: Order Processing Workflow

## Overview
This document shows a complete end-to-end example of how Conductor processes a workflow, with exact class names, database operations, and API calls at each step.

## The Big Picture: How Conductor Works

**Conductor is like a traffic controller for your microservices.** Here's the simple version:

### The Players:
1. **Conductor Server** - The orchestration engine (runs Java/Spring Boot)
2. **Your Workers** - Your microservices that do the actual work
3. **Database** - Stores workflow state (Redis, Postgres, etc.)
4. **Queue** - Holds tasks waiting to be executed

### The Flow in 30 Seconds:
```
1. You register a WORKFLOW DEFINITION (the blueprint)
   ↓
2. You start a WORKFLOW INSTANCE (actual execution)
   ↓
3. Conductor schedules the FIRST TASK
   ↓
4. Your WORKER polls "Do you have work for me?"
   ↓
5. Conductor gives the task to your worker
   ↓
6. Your worker EXECUTES business logic
   ↓
7. Your worker reports "I'm done, here's the result"
   ↓
8. Conductor DECIDES what task runs next
   ↓
9. Repeat steps 4-8 until workflow completes
```

### Key Concepts to Understand:

**Workflow Definition (the Recipe)**
- A JSON blueprint that defines the steps and their order
- Stored as metadata - used to create many workflow instances
- Like a recipe you use to cook many meals

**Workflow Instance (the Execution)**
- A specific execution of a workflow with its own unique ID
- Has its own input data and current state
- Like actually cooking one meal using the recipe

**Task**
- A unit of work to be executed (e.g., "validate_order", "charge_payment")
- Tasks have types - each type is handled by specific workers
- Tasks go through states: SCHEDULED → IN_PROGRESS → COMPLETED/FAILED

**Worker (Your Microservice)**
- Continuously polls Conductor: "Do you have tasks of type X for me?"
- Executes the actual business logic (database calls, API calls, etc.)
- Reports results back to Conductor
- **Pull model**: Workers ask for work, not pushed to them

**Decider (The Brain)**
- Looks at the workflow state and decides what should happen next
- Runs after every task completion
- Schedules the next tasks based on the workflow definition

**Queue**
- Tasks waiting to be picked up by workers
- One queue per task type (e.g., "validate_order" queue)
- Workers poll specific queues they can handle

### The Communication Model:

```
┌─────────────────┐                    ┌──────────────────┐
│  Conductor      │                    │  Your Worker     │
│  Server         │                    │  (Microservice)  │
└─────────────────┘                    └──────────────────┘
         │                                      │
         │  1. Creates task                     │
         │  Adds to queue                       │
         │                                       │
         │         2. Poll for task             │
         │  <───────────────────────────────────│
         │                                       │
         │         3. Here's a task             │
         │  ─────────────────────────────────>  │
         │     (status: IN_PROGRESS)            │
         │                                       │
         │                                4. Execute
         │                                   business logic
         │                                       │
         │     5. Task complete, here's result  │
         │  <───────────────────────────────────│
         │                                       │
         │  6. Decide next task                 │
         │  Add to queue                        │
         │                                       │
```

Now let's see this in action with a real example!

## Workflow Definition

### Step 1: Register Workflow Definition

**API Call:**
```http
POST http://localhost:8080/api/metadata/workflow
Content-Type: application/json

{
  "name": "order_processing",
  "version": 1,
  "description": "Process customer orders",
  "tasks": [
  
    {
      "name": "validate_order",
      "taskReferenceName": "validate_ref",
      "type": "SIMPLE",
      "inputParameters": {
        "orderId": "${workflow.input.orderId}",
        "items": "${workflow.input.items}"
      }
    },
    {
      "name": "check_inventory",
      "taskReferenceName": "inventory_ref",
      "type": "SIMPLE",
      "inputParameters": {
        "items": "${validate_ref.output.validatedItems}"
      }
    },
    {
      "name": "payment_tasks",
      "taskReferenceName": "payment_fork",
      "type": "FORK_JOIN",
      "forkTasks": [
        [
          {
            "name": "charge_payment",
            "taskReferenceName": "charge_ref",
            "type": "SIMPLE",
            "inputParameters": {
              "amount": "${workflow.input.amount}",
              "customerId": "${workflow.input.customerId}"
            }
          }
        ],
        [
          {
            "name": "send_invoice",
            "taskReferenceName": "invoice_ref",
            "type": "SIMPLE",
            "inputParameters": {
              "orderId": "${workflow.input.orderId}",
              "email": "${workflow.input.email}"
            }
          }
        ]
      ]
    },
    {
      "name": "payment_join",
      "taskReferenceName": "payment_join_ref",
      "type": "JOIN",
      "joinOn": ["charge_ref", "invoice_ref"]
    },
    {
      "name": "ship_order",
      "taskReferenceName": "ship_ref",
      "type": "SIMPLE",
      "inputParameters": {
        "orderId": "${workflow.input.orderId}",
        "address": "${workflow.input.shippingAddress}"
      }
    }
  ],
  "outputParameters": {
    "orderId": "${workflow.input.orderId}",
    "trackingNumber": "${ship_ref.output.trackingNumber}",
    "transactionId": "${charge_ref.output.transactionId}"
  },
  "timeoutSeconds": 3600,
  "timeoutPolicy": "TIME_OUT_WF"
}
```

### What Happens When You Register a Workflow

When you register a workflow definition, Conductor stores it as metadata (the blueprint) that can be used to start multiple workflow instances.

**The Registration Flow:**
1. **REST API receives the request** - The `WorkflowResource` controller handles the HTTP POST
2. **Service layer validates** - Checks if the workflow definition is valid (required fields, task references, etc.)
3. **DAO stores the definition** - Persists the workflow blueprint to the database
4. **Multiple versions supported** - Each workflow name can have multiple versions (v1, v2, etc.)

### Classes Involved:

**REST Layer:**
```
MetadataResource.registerWorkflowDef()
  Location: rest/src/main/java/com/netflix/conductor/rest/controllers/MetadataResource.java:156
  Purpose: Receives HTTP POST request and extracts WorkflowDef from JSON body
```

**Service Layer:**
```
MetadataServiceImpl.registerWorkflowDef()
  Location: core/src/main/java/com/netflix/conductor/service/MetadataServiceImpl.java:89
  Purpose: Validates the workflow definition and delegates to DAO for storage
```

**DAO Layer:**
```
MetadataDAOFacade.createWorkflowDef(WorkflowDef)
  Location: core/src/main/java/com/netflix/conductor/dao/MetadataDAO.java:45
  Purpose: Abstraction layer that delegates to specific implementation (Redis/Postgres)
  ↓
RedisMetadataDAO.createWorkflowDef(WorkflowDef)
  Location: redis-persistence/src/main/java/com/netflix/conductor/redis/dao/RedisMetadataDAO.java:201
  Purpose: Stores workflow definition in Redis with versioning support
```

**Database Operations:**
```redis
# Redis Keys Created:
# 1. Store the complete workflow definition as a HASH field
HSET conductor:WORKFLOW_DEF:order_processing "1" '{...WorkflowDef JSON...}'

# 2. Add workflow name to the set of all workflow names
SADD conductor:WORKFLOW_DEF_NAMES "order_processing"
```

**What gets stored:**
- Complete workflow definition including all tasks, their sequence, and configuration
- Version number for this workflow
- Input/output parameters
- Timeout settings
- Failure handling policies

---

## Workflow Execution Flow

### Step 2: Start Workflow

**What This Does:**
Starting a workflow creates a new execution instance from the workflow definition. Think of the workflow definition as a recipe, and the workflow instance as actually cooking a meal using that recipe.

**Key Concepts:**
- **Workflow Instance** - A unique execution with its own ID, input data, and state
- **Input Parameters** - The data you pass to start the workflow (order details in this example)
- **Workflow ID** - Unique identifier returned immediately (UUID format)

**API Call:**
```http
POST http://localhost:8080/api/workflow/order_processing
Content-Type: application/json

{
  "orderId": "ORD-12345",
  "customerId": "CUST-001",
  "items": [{"sku": "ITEM-001", "qty": 2}],
  "amount": 99.99,
  "email": "customer@example.com",
  "shippingAddress": "123 Main St, City, State 12345"
}
```

**Response:**
```json
"wf_a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```
This workflow ID is returned immediately. The workflow executes asynchronously.

### Classes & Flow:

#### 2.1 REST Controller

```java
// File: rest/src/main/java/com/netflix/conductor/rest/controllers/WorkflowResource.java:180

@PostMapping(value = "/{name}")
public String startWorkflow(
    @PathVariable("name") String name,
    @RequestParam(value = "version", required = false) Integer version,
    @RequestParam(value = "correlationId", required = false) String correlationId,
    @RequestParam(value = "priority", defaultValue = "0") int priority,
    @RequestBody Map<String, Object> input) {

    return workflowService.startWorkflow(name, version, correlationId, priority, input);
}
```

#### 2.2 Service Layer

```java
// File: core/src/main/java/com/netflix/conductor/service/WorkflowServiceImpl.java:145

public String startWorkflow(String workflowName, Integer version,
                           String correlationId, Integer priority,
                           Map<String, Object> input) {
    StartWorkflowRequest request = new StartWorkflowRequest();
    request.setName(workflowName);
    request.setVersion(version);
    request.setCorrelationId(correlationId);
    request.setPriority(priority);
    request.setInput(input);

    return workflowExecutor.startWorkflow(new StartWorkflowInput(request));
}
```

#### 2.3 Workflow Executor

**This is the heart of workflow orchestration.** The WorkflowExecutor is responsible for:
1. Creating the workflow instance from the definition
2. Determining which tasks should run first
3. Storing everything in the database
4. Queueing tasks for workers to pick up

**Step-by-Step Breakdown:**

```java
// File: core/src/main/java/com/netflix/conductor/core/execution/WorkflowExecutorOps.java:250

public String startWorkflow(StartWorkflowInput input) {
    // STEP 1: Generate a unique ID for this workflow instance
    String workflowId = idGenerator.generate();  // e.g., "wf_a1b2c3d4..."

    // STEP 2: Load the workflow blueprint from metadata storage
    // This is the definition you registered earlier
    WorkflowDef workflowDef = metadataDAO.getWorkflowDef(
        input.getName(),        // "order_processing"
        input.getVersion()      // 1
    );

    // STEP 3: Create a new workflow instance (execution state)
    // This is a RUNNING instance of the workflow definition
    WorkflowModel workflow = new WorkflowModel();
    workflow.setWorkflowId(workflowId);
    workflow.setWorkflowDefinition(workflowDef);
    workflow.setStatus(WorkflowStatus.RUNNING);              // Initial status
    workflow.setInput(input.getInput());                      // Order details
    workflow.setCreateTime(System.currentTimeMillis());
    workflow.setCorrelationId(input.getCorrelationId());     // Optional: link related workflows
    workflow.setPriority(input.getPriority());               // Task priority

    // STEP 4: Ask the Decider which tasks should run first
    // The Decider analyzes the workflow definition and determines initial tasks
    DeciderOutcome outcome = deciderService.decide(workflow);
    // Returns: List of tasks to schedule (e.g., "validate_order" task)

    // STEP 5: Store the workflow instance in the database
    // Stores workflow WITHOUT tasks (tasks stored separately)
    executionDAOFacade.createWorkflow(workflow);

    // STEP 6: Create task instances and add them to queues
    if (!outcome.tasksToBeScheduled.isEmpty()) {
        List<TaskModel> tasks = outcome.tasksToBeScheduled;

        // Store task instances in database
        executionDAOFacade.createTasks(tasks);

        // Push task IDs to appropriate queues for workers to poll
        for (TaskModel task : tasks) {
            String queueName = QueueUtils.getQueueName(task);  // e.g., "validate_order"
            queueDAO.push(queueName, task.getTaskId(), task.getCallbackAfterSeconds());
        }
    }

    return workflowId;  // Return immediately; execution continues asynchronously
}
```

**Important Notes:**
- The workflow starts in `RUNNING` status
- The first task ("validate_order" in our example) is immediately scheduled
- The API returns the workflow ID immediately - execution happens asynchronously
- Workers will poll the task queue to pick up the task

#### 2.4 Decider Service - Initial Scheduling

**The Decider is the "brain" of Conductor.** It evaluates the workflow state and decides:
- Which tasks are ready to execute
- Which tasks completed and what should run next
- When the workflow is complete or failed

**For a NEW workflow (no tasks yet):**
- Looks at the workflow definition
- Schedules the first task in the sequence
- Uses TaskMappers to convert workflow task definitions into executable task instances

```java
// File: core/src/main/java/com/netflix/conductor/core/execution/DeciderService.java:89

public DeciderOutcome decide(WorkflowModel workflow) throws TerminateWorkflowException {
    List<TaskModel> tasksToBeScheduled = new ArrayList<>();

    // Check if this is a new workflow (no tasks created yet)
    if (workflow.getTasks().isEmpty()) {
        // New workflow - start first tasks
        WorkflowDef workflowDef = workflow.getWorkflowDefinition();
        tasksToBeScheduled = startWorkflow(workflow);
    }
    // Note: If workflow has tasks, it evaluates which ones completed
    // and schedules next tasks (covered in Step 5)

    return new DeciderOutcome(tasksToBeScheduled, ...);
}

private List<TaskModel> startWorkflow(WorkflowModel workflow) {
    WorkflowDef def = workflow.getWorkflowDefinition();

    // Get first task from workflow definition
    // In our example: "validate_order" task
    WorkflowTask workflowTask = def.getTasks().get(0);  // First task

    // Map the workflow task definition to an executable task instance
    // TaskMapper knows how to convert each task type (SIMPLE, FORK_JOIN, etc.)
    TaskMapper taskMapper = taskMapperRegistry.get(workflowTask.getType());
    List<TaskModel> tasks = taskMapper.getMappedTasks(workflowTask, workflow);

    return tasks;  // Returns list with one task: validate_order
}
```

**What happens:**
1. Decider checks if workflow has any tasks yet → No, it's brand new
2. Gets the first task from the workflow definition → "validate_order"
3. Uses SimpleTaskMapper to create a TaskModel instance
4. Returns this task to be scheduled

#### 2.5 Task Mapper

**TaskMappers transform workflow task definitions into executable task instances.**

Each task type (SIMPLE, FORK_JOIN, DECISION, etc.) has its own mapper that knows how to:
- Create the appropriate TaskModel instances
- Evaluate input parameter expressions (like `${workflow.input.orderId}`)
- Set initial task state and metadata

**For SIMPLE tasks (user-defined tasks executed by workers):**

```java
// File: core/src/main/java/com/netflix/conductor/core/execution/mapper/SimpleTaskMapper.java:45

public List<TaskModel> getMappedTasks(TaskMapperContext taskMapperContext) {
    WorkflowTask workflowTask = taskMapperContext.getWorkflowTask();  // From definition
    WorkflowModel workflow = taskMapperContext.getWorkflowModel();    // Current execution

    // Create a new task instance
    TaskModel task = new TaskModel();
    task.setTaskId(idGenerator.generate());                   // Generate unique ID: "task_validate_001"
    task.setTaskType(workflowTask.getName());                 // "validate_order"
    task.setReferenceTaskName(workflowTask.getTaskReferenceName());  // "validate_ref"
    task.setWorkflowInstanceId(workflow.getWorkflowId());     // Link to workflow
    task.setStatus(TaskModel.Status.SCHEDULED);               // Initial status: ready to be polled
    task.setScheduledTime(System.currentTimeMillis());

    // Evaluate input parameter expressions using JSONPath
    // Example: "${workflow.input.orderId}" becomes "ORD-12345"
    Map<String, Object> input = parametersUtils.getTaskInput(
        workflowTask.getInputParameters(),  // From workflow definition
        workflow,                           // Current workflow execution (has input data)
        null,
        null
    );
    task.setInputData(input);  // Resolved input: {orderId: "ORD-12345", items: [...]}

    return Collections.singletonList(task);
}
```

**Result:**
- A TaskModel with status `SCHEDULED`
- Input parameters evaluated with actual workflow data
- Ready to be stored in database and added to queue

### Database Operations After Start:

**Redis Operations:**

```redis
# 1. Store Workflow
SET conductor:workflow:wf_a1b2c3d4... = {
  "workflowId": "wf_a1b2c3d4...",
  "status": "RUNNING",
  "workflowType": "order_processing",
  "version": 1,
  "input": {
    "orderId": "ORD-12345",
    "customerId": "CUST-001",
    "items": [{"sku": "ITEM-001", "qty": 2}],
    "amount": 99.99,
    "email": "customer@example.com",
    "shippingAddress": "123 Main St..."
  },
  "tasks": [],
  "createTime": 1699999999999,
  "status": "RUNNING"
}

# 2. Add to running workflows index
SADD conductor:workflow:running = wf_a1b2c3d4...
ZADD conductor:workflow:running:order_processing = <timestamp> wf_a1b2c3d4...

# 3. Store Task
SET conductor:task:task_validate_001 = {
  "taskId": "task_validate_001",
  "taskType": "validate_order",
  "referenceTaskName": "validate_ref",
  "workflowInstanceId": "wf_a1b2c3d4...",
  "status": "SCHEDULED",
  "inputData": {
    "orderId": "ORD-12345",
    "items": [{"sku": "ITEM-001", "qty": 2}]
  },
  "scheduledTime": 1699999999999
}

# 4. Add task to workflow's task list
LPUSH conductor:workflow:wf_a1b2c3d4...:tasks = task_validate_001

# 5. Push to task queue
LPUSH conductor:queue:validate_order = task_validate_001
```

**OpenSearch Operations:**

```json
// Index workflow for searching
POST /conductor/_doc/wf_a1b2c3d4...
{
  "workflowId": "wf_a1b2c3d4...",
  "workflowType": "order_processing",
  "version": 1,
  "status": "RUNNING",
  "startTime": 1699999999999,
  "input": {...},
  "correlationId": null,
  "priority": 0
}

// Index task for searching
POST /conductor/_doc/task_validate_001
{
  "taskId": "task_validate_001",
  "taskType": "validate_order",
  "status": "SCHEDULED",
  "workflowId": "wf_a1b2c3d4...",
  "scheduledTime": 1699999999999
}
```

---

### Step 3: Worker Polls for Task

**What This Step Does:**
Workers are your microservices that execute the actual business logic. They continuously poll Conductor asking "Do you have any tasks for me?" This is a **PULL model** - workers actively ask for work rather than Conductor pushing work to them.

**Key Concepts:**
- **Task Type** - Workers poll for specific task types they can handle (e.g., "validate_order")
- **Worker ID** - Unique identifier for the worker instance (for tracking and monitoring)
- **Blocking Poll** - The poll request can wait (block) for a short time if no tasks are available
- **Long Polling** - Reduces network traffic by waiting up to 100ms before returning empty

**Worker Code (External Service):**
This code runs in YOUR microservice, not in Conductor:

```java
// This runs in your microservice/worker application

public class OrderValidationWorker {

    private final TaskClient taskClient;  // Conductor client library

    public void pollAndExecute() {
        while (true) {
            // CONTINUOUSLY poll Conductor for tasks of type "validate_order"
            // This is a BLOCKING call that waits up to 100ms for tasks
            List<Task> tasks = taskClient.batchPollTasksInDomain(
                "validate_order",  // taskType - what kind of tasks I can handle
                "worker-001",      // workerId - my unique identifier
                1,                 // count - how many tasks I want (usually 1)
                100                // timeout in ms - how long to wait if no tasks available
            );

            // Process each task we received (usually 1 or 0)
            for (Task task : tasks) {
                processTask(task);
            }
        }
    }

    private void processTask(Task task) {
        try {
            // STEP 1: Extract input data from the task
            String orderId = (String) task.getInputData().get("orderId");
            List items = (List) task.getInputData().get("items");

            // STEP 2: Execute YOUR business logic
            // This could be calling a database, external API, validation service, etc.
            ValidationResult result = orderValidationService.validate(orderId, items);

            // STEP 3: Update task with SUCCESS result
            TaskResult taskResult = new TaskResult(task);
            taskResult.setStatus(TaskResult.Status.COMPLETED);
            taskResult.getOutputData().put("validatedItems", result.getValidatedItems());
            taskResult.getOutputData().put("totalAmount", result.getTotalAmount());

            // Send result back to Conductor
            taskClient.updateTask(taskResult);

        } catch (Exception e) {
            // STEP 3 (Alternative): Update task with FAILURE
            TaskResult taskResult = new TaskResult(task);
            taskResult.setStatus(TaskResult.Status.FAILED);
            taskResult.setReasonForIncompletion(e.getMessage());

            // Conductor will retry based on task definition settings
            taskClient.updateTask(taskResult);
        }
    }
}
```

**API Call (Made by TaskClient):**
```http
GET http://localhost:8080/api/tasks/poll/validate_order?workerid=worker-001&count=1&timeout=100
```

**What the poll returns:**
- If tasks available: List of Task objects with all details (input data, task ID, etc.)
- If no tasks available: Empty list (after waiting up to 100ms)

### Classes Involved in Polling:

#### 3.1 REST Controller

```java
// File: rest/src/main/java/com/netflix/conductor/rest/controllers/TaskResource.java:98

@GetMapping(value = "/poll/{tasktype}")
public List<Task> poll(
    @PathVariable("tasktype") String taskType,
    @RequestParam(value = "workerid") String workerId,
    @RequestParam(value = "domain", required = false) String domain) {

    return taskService.poll(taskType, workerId, domain, 1, 100);
}
```

#### 3.2 Service Layer

```java
// File: core/src/main/java/com/netflix/conductor/service/TaskServiceImpl.java:156

public List<Task> poll(String taskType, String workerId, String domain,
                      int count, int timeout) {
    return executionService.poll(taskType, workerId, domain, count, timeout);
}
```

#### 3.3 Execution Service

**This is where the magic happens on Conductor's side.** When a worker polls:
1. Conductor pops task IDs from the queue (FIFO - first in, first out)
2. Loads the full task details from the database
3. Updates task status from `SCHEDULED` to `IN_PROGRESS`
4. Assigns the task to the worker
5. Returns the task to the worker

**IMPORTANT:** This is a **BLOCKING operation** - if no tasks are available, the call waits up to the timeout period before returning empty.

```java
// File: core/src/main/java/com/netflix/conductor/service/ExecutionService.java:289

public List<Task> poll(String taskType, String workerId, String domain,
                      int count, int timeoutInMilliSecond) {

    // STEP 1: Construct queue name based on task type and optional domain
    // Queue name examples: "validate_order" or "validate_order:domain1"
    String queueName = QueueUtils.getQueueName(taskType, domain, null, null);

    // STEP 2: Record poll statistics (for monitoring)
    // Tracks which workers are polling and when
    pollDataDAO.updatePollData(taskType, domain, workerId);

    // STEP 3: Pop task IDs from the queue (BLOCKING operation)
    // In Redis: BRPOP conductor:queue:validate_order 0.1
    // This blocks for up to timeoutInMilliSecond waiting for tasks
    List<String> taskIds = queueDAO.pop(queueName, count, timeoutInMilliSecond);

    if (taskIds.isEmpty()) {
        // No tasks available - return empty list
        return Collections.emptyList();
    }

    // STEP 4: Load full task objects from storage
    List<TaskModel> tasks = taskIds.stream()
        .map(taskId -> {
            // Load task details from database
            // In Redis: GET conductor:TASK:{taskId}
            TaskModel task = executionDAO.getTask(taskId);

            // Update task status to IN_PROGRESS
            if (task.getStatus() == TaskModel.Status.SCHEDULED) {
                task.setStatus(TaskModel.Status.IN_PROGRESS);  // Mark as being worked on
                task.setWorkerId(workerId);                     // Assign to this worker
                task.setStartTime(System.currentTimeMillis()); // Record start time
                task.setPollCount(task.getPollCount() + 1);    // Track poll attempts

                // Persist the updated task
                executionDAO.updateTask(task);  // Update in Redis/Postgres
                indexDAO.indexTask(task);       // Update search index
            }

            return task;
        })
        .collect(Collectors.toList());

    // STEP 5: Convert internal TaskModel to API Task model
    // Removes internal fields and formats for external consumption
    return tasks.stream()
        .map(this::toTask)
        .collect(Collectors.toList());
}
```

**Key Points:**
- **Atomic operation**: Task is removed from queue and marked IN_PROGRESS in one logical operation
- **Worker assignment**: Task is now "owned" by this worker until completion
- **Poll count**: Tracks how many times this task has been polled (useful for debugging)

### Database Operations During Poll:

**Redis Operations:**

```redis
# 1. Pop from queue (BLOCKING with timeout)
BRPOP conductor:queue:validate_order 0.1
# Returns: task_validate_001

# 2. Load task
GET conductor:task:task_validate_001
# Returns: {full task JSON}

# 3. Update task status
SET conductor:task:task_validate_001 = {
  ...previous fields...
  "status": "IN_PROGRESS",
  "workerId": "worker-001",
  "startTime": 1700000000000,
  "pollCount": 1
}

# 4. Update poll data
ZADD conductor:poll_data:validate_order = <timestamp> worker-001
```

**OpenSearch Operations:**

```json
// Update task index
POST /conductor/_update/task_validate_001
{
  "doc": {
    "status": "IN_PROGRESS",
    "workerId": "worker-001",
    "startTime": 1700000000000,
    "pollCount": 1
  }
}
```

---

### Step 4: Worker Updates Task Result

**API Call (Made by Worker):**
```http
PUT http://localhost:8080/api/tasks/task_validate_001
Content-Type: application/json

{
  "taskId": "task_validate_001",
  "workflowInstanceId": "wf_a1b2c3d4...",
  "status": "COMPLETED",
  "outputData": {
    "validatedItems": [{"sku": "ITEM-001", "qty": 2, "price": 49.99}],
    "totalAmount": 99.98,
    "valid": true
  },
  "workerId": "worker-001"
}
```

### Classes Involved in Update:

#### 4.1 REST Controller

```java
// File: rest/src/main/java/com/netflix/conductor/rest/controllers/TaskResource.java:156

@PutMapping
public String updateTask(@RequestBody TaskResult taskResult) {
    taskService.updateTask(taskResult);
    return taskResult.getTaskId();
}
```

#### 4.2 Service Layer

```java
// File: core/src/main/java/com/netflix/conductor/service/TaskServiceImpl.java:201

public void updateTask(TaskResult taskResult) {
    executionService.updateTask(taskResult);
}
```

#### 4.3 Execution Service

```java
// File: core/src/main/java/com/netflix/conductor/service/ExecutionService.java:445

public void updateTask(TaskResult taskResult) {
    // 1. Load task from storage
    TaskModel task = executionDAO.getTask(taskResult.getTaskId());

    // 2. Update task with result
    task.setStatus(mapToTaskStatus(taskResult.getStatus()));
    task.setOutputData(taskResult.getOutputData());
    task.setReasonForIncompletion(taskResult.getReasonForIncompletion());
    task.setWorkerId(taskResult.getWorkerId());
    task.setEndTime(System.currentTimeMillis());
    task.setExecuted(true);

    // 3. Persist updated task
    executionDAO.updateTask(task);

    // 4. Update search index
    indexDAO.updateTask(task);

    // 5. Fire task status listener
    taskStatusListener.onTaskUpdated(task);

    // 6. Queue workflow for re-evaluation
    String workflowId = task.getWorkflowInstanceId();
    queueDAO.push(DECIDER_QUEUE, workflowId, 0);

    log.info("Task {} updated with status {}", task.getTaskId(), task.getStatus());
}
```

### Database Operations After Update:

**Redis Operations:**

```redis
# 1. Update task
SET conductor:task:task_validate_001 = {
  "taskId": "task_validate_001",
  "status": "COMPLETED",
  "outputData": {
    "validatedItems": [{"sku": "ITEM-001", "qty": 2, "price": 49.99}],
    "totalAmount": 99.98,
    "valid": true
  },
  "workerId": "worker-001",
  "endTime": 1700000001000,
  "executed": true
}

# 2. Push workflow to decider queue for re-evaluation
LPUSH conductor:queue:_deciderQueue = wf_a1b2c3d4...
```

**OpenSearch Operations:**

```json
// Update task in index
POST /conductor/_update/task_validate_001
{
  "doc": {
    "status": "COMPLETED",
    "endTime": 1700000001000,
    "outputData": {...}
  }
}
```

---

### Step 5: Workflow Re-evaluation (Decider Decides Next Tasks)

The decider runs continuously, polling the `_deciderQueue` for workflows that need evaluation.

#### 5.1 Decider Queue Processor

```java
// File: core/src/main/java/com/netflix/conductor/core/execution/WorkflowSweeper.java:89

@Scheduled(fixedDelayString = "${conductor.workflow.sweeper.frequency:500}")
public void sweep() {
    // Pop workflows from decider queue
    List<String> workflowIds = queueDAO.pop(DECIDER_QUEUE, batchSize, timeout);

    for (String workflowId : workflowIds) {
        try {
            decide(workflowId);
        } catch (Exception e) {
            log.error("Error deciding workflow {}", workflowId, e);
        }
    }
}

private void decide(String workflowId) {
    // 1. Load workflow
    WorkflowModel workflow = executionDAO.getWorkflow(workflowId, true);

    // 2. Call decider
    DeciderOutcome outcome = deciderService.decide(workflow);

    // 3. Schedule new tasks
    if (!outcome.tasksToBeScheduled.isEmpty()) {
        executionDAOFacade.createTasks(outcome.tasksToBeScheduled);

        for (TaskModel task : outcome.tasksToBeScheduled) {
            String queueName = QueueUtils.getQueueName(task);
            queueDAO.push(queueName, task.getTaskId(), 0);
        }
    }

    // 4. Update workflow
    workflow.setStatus(outcome.workflowStatus);
    workflow.setLastRetriedTime(System.currentTimeMillis());
    executionDAO.updateWorkflow(workflow);
    indexDAO.updateWorkflow(workflow);
}
```

#### 5.2 Decider Logic for Next Task

```java
// File: core/src/main/java/com/netflix/conductor/core/execution/DeciderService.java:156

public DeciderOutcome decide(WorkflowModel workflow) {
    List<TaskModel> tasksToBeScheduled = new ArrayList<>();

    // 1. Get all pending tasks
    List<TaskModel> pendingTasks = workflow.getTasks().stream()
        .filter(t -> !t.getStatus().isTerminal() && !t.isExecuted())
        .collect(Collectors.toList());

    // 2. Process completed tasks and schedule next
    for (TaskModel task : pendingTasks) {
        if (task.getStatus() == TaskModel.Status.COMPLETED) {
            // Mark as executed
            task.setExecuted(true);

            // Get next task from workflow definition
            List<WorkflowTask> nextWorkflowTasks = getNextTasks(workflow, task);

            for (WorkflowTask nextWorkflowTask : nextWorkflowTasks) {
                TaskMapper mapper = taskMapperRegistry.get(nextWorkflowTask.getType());
                List<TaskModel> mappedTasks = mapper.getMappedTasks(
                    new TaskMapperContext(nextWorkflowTask, workflow, task, null)
                );
                tasksToBeScheduled.addAll(mappedTasks);
            }
        }
    }

    // 3. Check if workflow is complete
    boolean allDone = workflow.getTasks().stream()
        .allMatch(t -> t.getStatus().isTerminal());

    WorkflowModel.Status workflowStatus = WorkflowModel.Status.RUNNING;
    if (allDone) {
        boolean anyFailed = workflow.getTasks().stream()
            .anyMatch(t -> t.getStatus() == TaskModel.Status.FAILED);

        workflowStatus = anyFailed ?
            WorkflowModel.Status.FAILED :
            WorkflowModel.Status.COMPLETED;
    }

    return new DeciderOutcome(tasksToBeScheduled, workflowStatus, ...);
}

private List<WorkflowTask> getNextTasks(WorkflowModel workflow, TaskModel completedTask) {
    WorkflowDef def = workflow.getWorkflowDefinition();

    // Find the completed task in the definition
    int currentIndex = -1;
    for (int i = 0; i < def.getTasks().size(); i++) {
        if (def.getTasks().get(i).getTaskReferenceName()
                .equals(completedTask.getReferenceTaskName())) {
            currentIndex = i;
            break;
        }
    }

    // Get next task (if exists)
    if (currentIndex >= 0 && currentIndex < def.getTasks().size() - 1) {
        WorkflowTask nextTask = def.getTasks().get(currentIndex + 1);
        return Collections.singletonList(nextTask);
    }

    return Collections.emptyList();
}
```

### After First Task Completion, Next Task is Scheduled:

The decider sees that `validate_order` completed successfully, and schedules the next task: `check_inventory`

**Database Operations:**

```redis
# 1. Update validate task as executed
SET conductor:task:task_validate_001 = {
  ...
  "executed": true
}

# 2. Create new inventory task
SET conductor:task:task_inventory_001 = {
  "taskId": "task_inventory_001",
  "taskType": "check_inventory",
  "referenceTaskName": "inventory_ref",
  "workflowInstanceId": "wf_a1b2c3d4...",
  "status": "SCHEDULED",
  "inputData": {
    "items": [{"sku": "ITEM-001", "qty": 2, "price": 49.99}]
  },
  "scheduledTime": 1700000002000
}

# 3. Add to workflow's task list
LPUSH conductor:workflow:wf_a1b2c3d4...:tasks = task_inventory_001

# 4. Push to queue
LPUSH conductor:queue:check_inventory = task_inventory_001
```

---

### Step 6: Fork-Join Parallel Execution

When the inventory task completes, the next task is a FORK_JOIN which executes two tasks in parallel:
- `charge_payment`
- `send_invoice`

#### 6.1 Fork Join Task Mapper

```java
// File: core/src/main/java/com/netflix/conductor/core/execution/mapper/ForkJoinTaskMapper.java:78

public List<TaskModel> getMappedTasks(TaskMapperContext context) {
    List<TaskModel> tasksToBeScheduled = new ArrayList<>();
    WorkflowTask forkJoinTask = context.getWorkflowTask();

    // 1. Create FORK task (system task)
    TaskModel forkTask = new TaskModel();
    forkTask.setTaskType(TaskType.FORK.name());
    forkTask.setTaskId(idGenerator.generate());
    forkTask.setReferenceTaskName(forkJoinTask.getTaskReferenceName());
    forkTask.setStatus(TaskModel.Status.COMPLETED);  // Fork completes immediately
    tasksToBeScheduled.add(forkTask);

    // 2. Create tasks for each parallel branch
    List<List<WorkflowTask>> forkTasks = forkJoinTask.getForkTasks();

    for (List<WorkflowTask> branch : forkTasks) {
        for (WorkflowTask workflowTask : branch) {
            TaskMapper mapper = taskMapperRegistry.get(workflowTask.getType());
            List<TaskModel> branchTasks = mapper.getMappedTasks(
                new TaskMapperContext(workflowTask, workflow, null, null)
            );
            tasksToBeScheduled.addAll(branchTasks);
        }
    }

    return tasksToBeScheduled;
}
```

**Tasks Created:**

```redis
# 1. FORK task (completes immediately)
SET conductor:task:task_fork_001 = {
  "taskId": "task_fork_001",
  "taskType": "FORK",
  "status": "COMPLETED",
  "executed": true
}

# 2. Parallel task 1: charge_payment
SET conductor:task:task_charge_001 = {
  "taskId": "task_charge_001",
  "taskType": "charge_payment",
  "status": "SCHEDULED",
  "inputData": {
    "amount": 99.99,
    "customerId": "CUST-001"
  }
}
LPUSH conductor:queue:charge_payment = task_charge_001

# 3. Parallel task 2: send_invoice
SET conductor:task:task_invoice_001 = {
  "taskId": "task_invoice_001",
  "taskType": "send_invoice",
  "status": "SCHEDULED",
  "inputData": {
    "orderId": "ORD-12345",
    "email": "customer@example.com"
  }
}
LPUSH conductor:queue:send_invoice = task_invoice_001
```

Both tasks are now available for different workers to poll and execute **in parallel**.

---

### Step 7: JOIN Task (Wait for Parallel Tasks)

After both parallel tasks complete, a JOIN task waits for them before proceeding.

#### 7.1 Join Task Mapper

```java
// File: core/src/main/java/com/netflix/conductor/core/execution/mapper/JoinTaskMapper.java:67

public List<TaskModel> getMappedTasks(TaskMapperContext context) {
    WorkflowTask joinWorkflowTask = context.getWorkflowTask();
    WorkflowModel workflow = context.getWorkflowModel();

    // Check if all tasks being joined are complete
    List<String> joinOn = joinWorkflowTask.getJoinOn();  // ["charge_ref", "invoice_ref"]

    boolean allComplete = joinOn.stream()
        .allMatch(refName -> {
            TaskModel task = workflow.getTaskByRefName(refName);
            return task != null && task.getStatus() == TaskModel.Status.COMPLETED;
        });

    // Create JOIN task
    TaskModel joinTask = new TaskModel();
    joinTask.setTaskType(TaskType.JOIN.name());
    joinTask.setTaskId(idGenerator.generate());
    joinTask.setReferenceTaskName(joinWorkflowTask.getTaskReferenceName());

    if (allComplete) {
        // All joined tasks complete - JOIN succeeds
        joinTask.setStatus(TaskModel.Status.COMPLETED);
        joinTask.setExecuted(true);
    } else {
        // Still waiting - JOIN is IN_PROGRESS
        joinTask.setStatus(TaskModel.Status.IN_PROGRESS);
    }

    return Collections.singletonList(joinTask);
}
```

The decider will keep re-evaluating the workflow. When both `charge_payment` and `send_invoice` complete:
1. JOIN task status changes to COMPLETED
2. Next task (`ship_order`) is scheduled

---

### Step 8: Workflow Completion

When the final task (`ship_order`) completes:

```java
// DeciderService determines workflow is complete

DeciderOutcome outcome = decide(workflow);

// All tasks executed and successful
workflow.setStatus(WorkflowModel.Status.COMPLETED);
workflow.setEndTime(System.currentTimeMillis());

// Calculate output
Map<String, Object> output = parametersUtils.getWorkflowOutput(
    workflow.getWorkflowDefinition(),
    workflow
);
workflow.setOutput(output);

// Persist
executionDAO.updateWorkflow(workflow);
indexDAO.updateWorkflow(workflow);

// Fire completion event
workflowStatusListener.onWorkflowCompleted(workflow);
```

**Final Database State:**

```redis
# Updated workflow
SET conductor:workflow:wf_a1b2c3d4... = {
  "workflowId": "wf_a1b2c3d4...",
  "status": "COMPLETED",
  "tasks": [
    {"taskId": "task_validate_001", "status": "COMPLETED"},
    {"taskId": "task_inventory_001", "status": "COMPLETED"},
    {"taskId": "task_fork_001", "status": "COMPLETED"},
    {"taskId": "task_charge_001", "status": "COMPLETED"},
    {"taskId": "task_invoice_001", "status": "COMPLETED"},
    {"taskId": "task_join_001", "status": "COMPLETED"},
    {"taskId": "task_ship_001", "status": "COMPLETED"}
  ],
  "output": {
    "orderId": "ORD-12345",
    "trackingNumber": "TRACK-9876",
    "transactionId": "TXN-5555"
  },
  "endTime": 1700000010000
}

# Remove from running index
SREM conductor:workflow:running = wf_a1b2c3d4...
ZREM conductor:workflow:running:order_processing = wf_a1b2c3d4...
```

---

## Summary: Complete Communication Flow

```
User API Call
    ↓
WorkflowResource (REST)
    ↓
WorkflowServiceImpl
    ↓
WorkflowExecutorOps
    ↓
DeciderService → TaskMappers → TaskModel creation
    ↓
ExecutionDAOFacade
    ↓
RedisExecutionDAO (persist) + OpenSearchIndexDAO (index)
    ↓
QueueDAO (push to queue)
    ↓
Worker polls → ExecutionService
    ↓
QueueDAO.pop() → ExecutionDAO.getTask()
    ↓
Worker executes business logic
    ↓
Worker updates → TaskResource
    ↓
TaskServiceImpl → ExecutionService
    ↓
ExecutionDAO.updateTask() + IndexDAO.updateTask()
    ↓
QueueDAO.push(DECIDER_QUEUE, workflowId)
    ↓
WorkflowSweeper polls decider queue
    ↓
DeciderService.decide() → Schedule next tasks
    ↓
Repeat until workflow complete
```

## Key Takeaways

1. **Separation of Concerns**: REST → Service → Core → DAO layers
2. **Queue-Based Distribution**: Tasks pushed to queues by type
3. **Pull Model**: Workers poll queues (not pushed to workers)
4. **Event-Driven Re-evaluation**: Task updates trigger workflow re-evaluation
5. **Decider is the Brain**: All scheduling logic centralized in DeciderService
6. **Pluggable Persistence**: DAO abstraction allows different backends
7. **Search for Visibility**: Everything indexed in OpenSearch for queries
8. **Stateless Execution**: All state in persistence layer, servers can restart

This is the complete end-to-end flow of how Conductor orchestrates workflows!

---

## APPENDIX: Complete Database Schemas

### Redis Schema (RedisExecutionDAO & RedisMetadataDAO)

**Implementation Files:**
- `redis-persistence/src/main/java/com/netflix/conductor/redis/dao/RedisExecutionDAO.java`
- `redis-persistence/src/main/java/com/netflix/conductor/redis/dao/RedisMetadataDAO.java`

#### Redis Key Patterns

Redis uses namespaced keys with the format: `{namespace}:{keyFamily}:{identifier}`

**Default namespace:** `conductor` (configurable via `conductor.redis.workflowNamespacePrefix`)

#### 1. Workflow Execution Keys

```redis
# Store workflow instance (JSON serialized)
conductor:WORKFLOW:{workflowId}
Type: STRING
Value: {WorkflowModel JSON without tasks array}
Example: conductor:WORKFLOW:wf_a1b2c3d4-e5f6-7890-abcd-ef1234567890

# Map workflow to its tasks
conductor:WORKFLOW_TO_TASKS:{workflowId}
Type: SET
Members: [taskId1, taskId2, taskId3, ...]
Example: conductor:WORKFLOW_TO_TASKS:wf_a1b2c3d4
Contains: {task_validate_001, task_inventory_001, task_charge_001, ...}

# Pending (running) workflows by type
conductor:PENDING_WORKFLOWS:{workflowName}
Type: SET
Members: [workflowId1, workflowId2, ...]
Example: conductor:PENDING_WORKFLOWS:order_processing
Contains: {wf_a1b2c3d4, wf_xyz123, ...}

# Workflows by definition and date
conductor:WORKFLOW_DEF_TO_WORKFLOWS:{workflowName}:{dateStr}
Type: SET
Members: [workflowId1, workflowId2, ...]
dateStr format: yyyyMMdd
Example: conductor:WORKFLOW_DEF_TO_WORKFLOWS:order_processing:20251107
Contains: {wf_a1b2c3d4, wf_xyz123}

# Workflows by correlation ID
conductor:CORR_ID_TO_WORKFLOWS:{correlationId}
Type: SET
Members: [workflowId1, workflowId2, ...]
Example: conductor:CORR_ID_TO_WORKFLOWS:ORDER-12345
Contains: {wf_a1b2c3d4}
```

#### 2. Task Execution Keys

```redis
# Store task instance (JSON serialized)
conductor:TASK:{taskId}
Type: STRING
Value: {TaskModel JSON}
Example: conductor:TASK:task_validate_001

# Scheduled tasks for a workflow (track which tasks are scheduled to prevent duplicates)
conductor:SCHEDULED_TASKS:{workflowId}
Type: HASH
Fields: {taskReferenceTaskName}{retryCount} -> taskId
Example: conductor:SCHEDULED_TASKS:wf_a1b2c3d4
  validate_ref0 -> task_validate_001
  inventory_ref0 -> task_inventory_001
  charge_ref0 -> task_charge_001
  invoice_ref0 -> task_invoice_001

# In-progress tasks by task type
conductor:IN_PROGRESS_TASKS:{taskDefName}
Type: SET
Members: [taskId1, taskId2, ...]
Example: conductor:IN_PROGRESS_TASKS:validate_order
Contains: {task_validate_001, task_validate_002}

# Tasks actually in IN_PROGRESS status (for concurrency limiting)
conductor:TASKS_IN_PROGRESS_STATUS:{taskDefName}
Type: SET
Members: [taskId1, taskId2, ...]
Example: conductor:TASKS_IN_PROGRESS_STATUS:charge_payment
Contains: {task_charge_001}

# Task rate limiting bucket (scored set by timestamp)
conductor:TASK_LIMIT_BUCKET:{taskDefName}
Type: SORTED SET
Members: taskId, Score: timestamp
Example: conductor:TASK_LIMIT_BUCKET:charge_payment
  task_charge_001 -> 1699999999999
  task_charge_002 -> 1700000000000
```

#### 3. Metadata Keys

```redis
# All task definitions
conductor:TASK_DEFS
Type: HASH
Fields: taskDefName -> {TaskDef JSON}
Example: conductor:TASK_DEFS
  validate_order -> {"name":"validate_order","retryCount":3,"timeoutSeconds":300,...}
  charge_payment -> {"name":"charge_payment","retryCount":2,"timeoutSeconds":60,...}

# Workflow definitions by name and version
conductor:WORKFLOW_DEF:{workflowName}
Type: HASH
Fields: version -> {WorkflowDef JSON}
Example: conductor:WORKFLOW_DEF:order_processing
  1 -> {WorkflowDef JSON for version 1}
  2 -> {WorkflowDef JSON for version 2}
  latest -> 2

# All workflow definition names
conductor:WORKFLOW_DEF_NAMES
Type: SET
Members: [workflowName1, workflowName2, ...]
Example: conductor:WORKFLOW_DEF_NAMES
Contains: {order_processing, customer_onboarding, payment_processing}
```

#### 4. Event Execution Keys

```redis
# Event executions
conductor:EVENT_EXECUTION:{eventHandlerName}:{eventName}:{messageId}
Type: HASH
Fields: executionId -> {EventExecution JSON}
Example: conductor:EVENT_EXECUTION:order_handler:order.placed:msg-123
  msg-123_0 -> {EventExecution JSON}
TTL: Configurable (default: no expiration)
```

#### 5. Queue Keys (External Queue Implementation - Orkes Queues)

```redis
# Task queue (stores task IDs to be polled)
conductor:queue:{taskType}
Type: LIST
Operations: LPUSH (add), BRPOP (poll with blocking)
Example: conductor:queue:validate_order
Contains: [task_validate_003, task_validate_002, task_validate_001]

# Decider queue (workflows needing re-evaluation)
conductor:queue:_deciderQueue
Type: LIST
Contains: [workflowId1, workflowId2, ...]
```

### Complete Redis Operations Example

```redis
# 1. Create Workflow
SET conductor:WORKFLOW:wf_abc123 '{"workflowId":"wf_abc123","status":"RUNNING",...}'
SADD conductor:PENDING_WORKFLOWS:order_processing wf_abc123
SADD conductor:WORKFLOW_DEF_TO_WORKFLOWS:order_processing:20251107 wf_abc123

# 2. Create Task
SET conductor:TASK:task_001 '{"taskId":"task_001","status":"SCHEDULED",...}'
HSET conductor:SCHEDULED_TASKS:wf_abc123 validate_ref0 task_001
SADD conductor:WORKFLOW_TO_TASKS:wf_abc123 task_001
SADD conductor:IN_PROGRESS_TASKS:validate_order task_001
LPUSH conductor:queue:validate_order task_001

# 3. Worker Polls Task
BRPOP conductor:queue:validate_order 0.1
# Returns: task_001
GET conductor:TASK:task_001
# Update task status to IN_PROGRESS
SET conductor:TASK:task_001 '{"taskId":"task_001","status":"IN_PROGRESS",...}'
SADD conductor:TASKS_IN_PROGRESS_STATUS:validate_order task_001

# 4. Worker Completes Task
SET conductor:TASK:task_001 '{"taskId":"task_001","status":"COMPLETED",...}'
SREM conductor:IN_PROGRESS_TASKS:validate_order task_001
SREM conductor:TASKS_IN_PROGRESS_STATUS:validate_order task_001
LPUSH conductor:queue:_deciderQueue wf_abc123

# 5. Workflow Complete
SET conductor:WORKFLOW:wf_abc123 '{"workflowId":"wf_abc123","status":"COMPLETED",...}'
SREM conductor:PENDING_WORKFLOWS:order_processing wf_abc123
```

---

### PostgreSQL Schema

**Implementation Files:**
- `postgres-persistence/src/main/resources/db/migration_postgres/V1__initial_schema.sql`
- `postgres-persistence/src/main/java/com/netflix/conductor/postgres/dao/PostgresExecutionDAO.java`
- `postgres-persistence/src/main/java/com/netflix/conductor/postgres/dao/PostgresMetadataDAO.java`

#### 1. Metadata Tables

##### meta_workflow_def
Stores workflow definitions.

```sql
CREATE TABLE meta_workflow_def (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  name VARCHAR(255) NOT NULL,
  version INT NOT NULL,
  latest_version INT NOT NULL DEFAULT 0,
  json_data TEXT NOT NULL,

  CONSTRAINT unique_name_version UNIQUE (name, version)
);
CREATE INDEX workflow_def_name_index ON meta_workflow_def (name);
```

**Columns:**
- `id` - Auto-incrementing primary key
- `name` - Workflow name (e.g., "order_processing")
- `version` - Workflow version number
- `latest_version` - Flag indicating if this is the latest version
- `json_data` - Complete WorkflowDef JSON
- `created_on`, `modified_on` - Audit timestamps

**Example Row:**
```
id: 1
name: order_processing
version: 1
latest_version: 1
json_data: {"name":"order_processing","version":1,"tasks":[...],...}
```

##### meta_task_def
Stores task definitions.

```sql
CREATE TABLE meta_task_def (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  name VARCHAR(255) NOT NULL,
  json_data TEXT NOT NULL,

  CONSTRAINT unique_task_def_name UNIQUE (name)
);
```

**Columns:**
- `id` - Auto-incrementing primary key
- `name` - Task definition name (e.g., "validate_order")
- `json_data` - Complete TaskDef JSON

**Example Row:**
```
id: 1
name: validate_order
json_data: {"name":"validate_order","retryCount":3,"timeoutSeconds":300,...}
```

##### meta_event_handler
Stores event handler configurations.

```sql
CREATE TABLE meta_event_handler (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  name VARCHAR(255) NOT NULL,
  event VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL,
  json_data TEXT NOT NULL
);
CREATE INDEX event_handler_name_index ON meta_event_handler (name);
CREATE INDEX event_handler_event_index ON meta_event_handler (event);
```

**Columns:**
- `name` - Event handler name
- `event` - Event pattern (e.g., "order:placed")
- `active` - Whether handler is active
- `json_data` - Complete EventHandler JSON

#### 2. Execution Tables

##### workflow
Stores workflow execution instances.

```sql
CREATE TABLE workflow (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  workflow_id VARCHAR(255) NOT NULL,
  correlation_id VARCHAR(255),
  json_data TEXT NOT NULL,

  CONSTRAINT unique_workflow_id UNIQUE (workflow_id)
);
```

**Columns:**
- `workflow_id` - UUID of workflow instance
- `correlation_id` - User-provided correlation ID
- `json_data` - Complete WorkflowModel JSON (without tasks array)

**Example Row:**
```
id: 1001
workflow_id: wf_a1b2c3d4-e5f6-7890-abcd-ef1234567890
correlation_id: ORDER-12345
json_data: {"workflowId":"wf_a1b2...","status":"RUNNING","input":{...},...}
```

##### task
Stores task execution instances.

```sql
CREATE TABLE task (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  task_id VARCHAR(255) NOT NULL,
  json_data TEXT NOT NULL,

  CONSTRAINT unique_task_id UNIQUE (task_id)
);
```

**Columns:**
- `task_id` - UUID of task instance
- `json_data` - Complete TaskModel JSON

**Example Row:**
```
id: 5001
task_id: task_validate_001
json_data: {"taskId":"task_validate_001","status":"COMPLETED","output":{...},...}
```

##### workflow_to_task
Maps workflows to their tasks (relationship table).

```sql
CREATE TABLE workflow_to_task (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  workflow_id VARCHAR(255) NOT NULL,
  task_id VARCHAR(255) NOT NULL,

  CONSTRAINT unique_workflow_to_task_id UNIQUE (workflow_id, task_id)
);
CREATE INDEX workflow_id_index ON workflow_to_task (workflow_id);
```

**Example Rows:**
```
workflow_id: wf_a1b2c3d4, task_id: task_validate_001
workflow_id: wf_a1b2c3d4, task_id: task_inventory_001
workflow_id: wf_a1b2c3d4, task_id: task_charge_001
```

##### workflow_pending
Tracks workflows in non-terminal states.

```sql
CREATE TABLE workflow_pending (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  workflow_type VARCHAR(255) NOT NULL,
  workflow_id VARCHAR(255) NOT NULL,

  CONSTRAINT unique_workflow_type_workflow_id UNIQUE (workflow_type, workflow_id)
);
CREATE INDEX workflow_type_index ON workflow_pending (workflow_type);
```

**Example Rows:**
```
workflow_type: order_processing, workflow_id: wf_a1b2c3d4
workflow_type: order_processing, workflow_id: wf_xyz123
```

**Purpose:** Quick lookup of running workflows by type.

##### workflow_def_to_workflow
Maps workflow definitions to instances by date.

```sql
CREATE TABLE workflow_def_to_workflow (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  workflow_def VARCHAR(255) NOT NULL,
  date_str VARCHAR(60),
  workflow_id VARCHAR(255) NOT NULL,

  CONSTRAINT unique_workflow_def_date_str
    UNIQUE (workflow_def, date_str, workflow_id)
);
```

**Example Rows:**
```
workflow_def: order_processing, date_str: 20251107, workflow_id: wf_a1b2c3d4
workflow_def: order_processing, date_str: 20251107, workflow_id: wf_xyz123
```

**Purpose:** Query workflows by type within date ranges.

##### task_scheduled
Tracks scheduled tasks to prevent duplicates.

```sql
CREATE TABLE task_scheduled (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  workflow_id VARCHAR(255) NOT NULL,
  task_key VARCHAR(255) NOT NULL,
  task_id VARCHAR(255) NOT NULL,

  CONSTRAINT unique_workflow_id_task_key UNIQUE (workflow_id, task_key)
);
```

**Columns:**
- `task_key` - Combination of `referenceTaskName + retryCount`
- `task_id` - The actual task ID

**Example Rows:**
```
workflow_id: wf_a1b2c3d4, task_key: validate_ref0, task_id: task_validate_001
workflow_id: wf_a1b2c3d4, task_key: inventory_ref0, task_id: task_inventory_001
```

##### task_in_progress
Tracks in-progress tasks for concurrency limits.

```sql
CREATE TABLE task_in_progress (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  task_def_name VARCHAR(255) NOT NULL,
  task_id VARCHAR(255) NOT NULL,
  workflow_id VARCHAR(255) NOT NULL,
  in_progress_status BOOLEAN NOT NULL DEFAULT false,

  CONSTRAINT unique_task_def_task_id1 UNIQUE (task_def_name, task_id)
);
```

**Example Rows:**
```
task_def_name: validate_order, task_id: task_validate_001, in_progress_status: true
task_def_name: charge_payment, task_id: task_charge_001, in_progress_status: true
```

#### 3. Queue Tables

##### queue
Stores queue names.

```sql
CREATE TABLE queue (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  queue_name VARCHAR(255) NOT NULL,

  CONSTRAINT unique_queue_name UNIQUE (queue_name)
);
```

**Example Rows:**
```
queue_name: validate_order
queue_name: charge_payment
queue_name: _deciderQueue
```

##### queue_message
Stores messages (task IDs) in queues.

```sql
CREATE TABLE queue_message (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deliver_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  queue_name VARCHAR(255) NOT NULL,
  message_id VARCHAR(255) NOT NULL,
  priority INTEGER DEFAULT 0,
  popped BOOLEAN DEFAULT false,
  offset_time_seconds BIGINT,
  payload TEXT,

  CONSTRAINT unique_queue_name_message_id UNIQUE (queue_name, message_id)
);
CREATE INDEX combo_queue_message
  ON queue_message (queue_name, popped, deliver_on, created_on);
```

**Columns:**
- `queue_name` - Queue identifier (usually task type)
- `message_id` - Usually the task ID
- `priority` - Task priority (higher = more urgent)
- `popped` - Whether message has been polled
- `deliver_on` - When message should be available (for delayed tasks)
- `offset_time_seconds` - Delay in seconds
- `payload` - Additional data (optional)

**Example Rows:**
```
queue_name: validate_order, message_id: task_validate_001, priority: 0, popped: false
queue_name: charge_payment, message_id: task_charge_001, priority: 5, popped: false
queue_name: _deciderQueue, message_id: wf_a1b2c3d4, priority: 0, popped: false
```

**Polling Query:**
```sql
SELECT message_id FROM queue_message
WHERE queue_name = 'validate_order'
  AND popped = false
  AND deliver_on <= NOW()
ORDER BY priority DESC, created_on ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;

-- Mark as popped
UPDATE queue_message SET popped = true WHERE id = ?;
```

#### 4. Other Tables

##### event_execution
Tracks event handler executions.

```sql
CREATE TABLE event_execution (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  event_handler_name VARCHAR(255) NOT NULL,
  event_name VARCHAR(255) NOT NULL,
  message_id VARCHAR(255) NOT NULL,
  execution_id VARCHAR(255) NOT NULL,
  json_data TEXT NOT NULL,

  CONSTRAINT unique_event_execution
    UNIQUE (event_handler_name, event_name, message_id)
);
```

##### poll_data
Tracks worker polling statistics.

```sql
CREATE TABLE poll_data (
  id SERIAL PRIMARY KEY,
  created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  modified_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  queue_name VARCHAR(255) NOT NULL,
  domain VARCHAR(255) NOT NULL,
  json_data TEXT NOT NULL,

  CONSTRAINT unique_poll_data UNIQUE (queue_name, domain)
);
CREATE INDEX ON poll_data (queue_name);
```

### Complete PostgreSQL Operations Example

```sql
-- 1. Create Workflow
INSERT INTO workflow (workflow_id, correlation_id, json_data)
VALUES ('wf_abc123', 'ORDER-12345', '{"workflowId":"wf_abc123",...}');

INSERT INTO workflow_pending (workflow_type, workflow_id)
VALUES ('order_processing', 'wf_abc123');

INSERT INTO workflow_def_to_workflow (workflow_def, date_str, workflow_id)
VALUES ('order_processing', '20251107', 'wf_abc123');

-- 2. Create Task
INSERT INTO task (task_id, json_data)
VALUES ('task_001', '{"taskId":"task_001","status":"SCHEDULED",...}');

INSERT INTO workflow_to_task (workflow_id, task_id)
VALUES ('wf_abc123', 'task_001');

INSERT INTO task_scheduled (workflow_id, task_key, task_id)
VALUES ('wf_abc123', 'validate_ref0', 'task_001');

INSERT INTO task_in_progress (task_def_name, task_id, workflow_id)
VALUES ('validate_order', 'task_001', 'wf_abc123');

-- Add to queue
INSERT INTO queue_message (queue_name, message_id, priority)
VALUES ('validate_order', 'task_001', 0);

-- 3. Worker Polls Task
SELECT message_id FROM queue_message
WHERE queue_name = 'validate_order' AND popped = false
ORDER BY priority DESC, created_on ASC
LIMIT 1 FOR UPDATE SKIP LOCKED;

-- Mark as popped
UPDATE queue_message SET popped = true
WHERE queue_name = 'validate_order' AND message_id = 'task_001';

-- Get task details
SELECT json_data FROM task WHERE task_id = 'task_001';

-- Update task status
UPDATE task SET json_data = '{"taskId":"task_001","status":"IN_PROGRESS",...}'
WHERE task_id = 'task_001';

UPDATE task_in_progress SET in_progress_status = true
WHERE task_id = 'task_001';

-- 4. Worker Completes Task
UPDATE task SET json_data = '{"taskId":"task_001","status":"COMPLETED",...}'
WHERE task_id = 'task_001';

DELETE FROM task_in_progress WHERE task_id = 'task_001';

-- Trigger workflow re-evaluation
INSERT INTO queue_message (queue_name, message_id, priority)
VALUES ('_deciderQueue', 'wf_abc123', 0);

-- 5. Workflow Complete
UPDATE workflow SET json_data = '{"workflowId":"wf_abc123","status":"COMPLETED",...}'
WHERE workflow_id = 'wf_abc123';

DELETE FROM workflow_pending WHERE workflow_id = 'wf_abc123';
```

### Schema Comparison: Redis vs PostgreSQL

| Feature | Redis | PostgreSQL |
|---------|-------|------------|
| **Workflow Storage** | STRING key-value | Row in `workflow` table |
| **Task Storage** | STRING key-value | Row in `task` table |
| **Workflow-Task Mapping** | SET | `workflow_to_task` table |
| **Queues** | LIST (LPUSH/BRPOP) | `queue_message` table |
| **Pending Workflows** | SET | `workflow_pending` table |
| **Scheduled Tasks** | HASH | `task_scheduled` table |
| **In-Progress Tasks** | SET | `task_in_progress` table |
| **Metadata** | HASH | Separate tables per type |
| **Transactions** | Limited (Lua scripts) | Full ACID transactions |
| **Queries** | Limited (by key) | Full SQL support |
| **Performance** | Very fast (in-memory) | Fast (disk + indexes) |
| **Scalability** | Horizontal (sharding) | Vertical + replication |
| **Durability** | Optional (AOF/RDB) | Always durable |

### Key Design Patterns

#### 1. Dual Storage Pattern
- **Primary Data**: Workflow/task JSON stored as blob (STRING in Redis, TEXT in Postgres)
- **Index Data**: Separate keys/tables for querying (pending workflows, scheduled tasks)
- **Benefit**: Fast reads without JSON parsing, flexible schema changes

#### 2. Deduplication Pattern
- **Redis**: `HSET` with `SETNX` for scheduled tasks
- **Postgres**: UNIQUE constraints
- **Purpose**: Prevent duplicate task scheduling on retries

#### 3. Queue Pattern
- **Redis**: LIST with BRPOP for blocking polls
- **Postgres**: `SELECT FOR UPDATE SKIP LOCKED` for atomic polling
- **Benefit**: Multiple workers can poll concurrently without conflicts

#### 4. Denormalization Pattern
- Workflow JSON doesn't include tasks array
- Tasks stored separately and loaded on demand
- **Benefit**: Faster workflow updates, don't need to rewrite entire task list

---

## Database Operation Tracing

### Example: Creating a Task

**Redis (RedisExecutionDAO.java:137-190)**
```java
public List<TaskModel> createTasks(List<TaskModel> tasks) {
    for (TaskModel task : tasks) {
        // 1. Store task key in scheduled tasks hash (deduplication)
        Long added = jedisProxy.hset(
            nsKey(SCHEDULED_TASKS, task.getWorkflowInstanceId()),  // conductor:SCHEDULED_TASKS:wf_abc
            taskKey,                                                // validate_ref0
            task.getTaskId()                                        // task_001
        );

        // 2. Link task to workflow
        jedisProxy.sadd(
            nsKey(WORKFLOW_TO_TASKS, task.getWorkflowInstanceId()), // conductor:WORKFLOW_TO_TASKS:wf_abc
            task.getTaskId()                                         // task_001
        );

        // 3. Add to in-progress tracking
        jedisProxy.sadd(
            nsKey(IN_PROGRESS_TASKS, task.getTaskDefName()),        // conductor:IN_PROGRESS_TASKS:validate_order
            task.getTaskId()                                         // task_001
        );

        // 4. Store task JSON
        updateTask(task);  // Stores to conductor:TASK:task_001
    }
}
```

**PostgreSQL (PostgresExecutionDAO.java)**
```java
public List<TaskModel> createTasks(List<TaskModel> tasks) {
    for (TaskModel task : tasks) {
        // 1. Insert task
        String INSERT_TASK = "INSERT INTO task (task_id, json_data) VALUES (?, ?)";
        execute(INSERT_TASK, task.getTaskId(), toJson(task));

        // 2. Link to workflow
        String INSERT_MAPPING = "INSERT INTO workflow_to_task (workflow_id, task_id) VALUES (?, ?)";
        execute(INSERT_MAPPING, task.getWorkflowInstanceId(), task.getTaskId());

        // 3. Mark as scheduled
        String INSERT_SCHEDULED =
            "INSERT INTO task_scheduled (workflow_id, task_key, task_id) VALUES (?, ?, ?)";
        String taskKey = task.getReferenceTaskName() + task.getRetryCount();
        execute(INSERT_SCHEDULED, task.getWorkflowInstanceId(), taskKey, task.getTaskId());

        // 4. Track in-progress
        String INSERT_IN_PROGRESS =
            "INSERT INTO task_in_progress (task_def_name, task_id, workflow_id) VALUES (?, ?, ?)";
        execute(INSERT_IN_PROGRESS, task.getTaskDefName(), task.getTaskId(),
                task.getWorkflowInstanceId());
    }
}
```

This completes the comprehensive schema documentation for both Redis and PostgreSQL!
