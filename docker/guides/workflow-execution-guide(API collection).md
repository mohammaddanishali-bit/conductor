# Conductor Workflow Execution Guide

Complete step-by-step guide to create and execute a workflow using curl commands.

## Prerequisites

- Conductor server running on `http://localhost:8080`
- Redis running on port 6379
- OpenSearch running on port 9201

## Step 1: Register Task Definition

```bash
curl -X POST "http://localhost:8080/api/metadata/taskdefs" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "name": "simple_task",
      "description": "A simple test task",
      "ownerEmail": "test@example.com",
      "retryCount": 3,
      "timeoutSeconds": 3600,
      "inputKeys": ["message"],
      "outputKeys": ["result"],
      "timeoutPolicy": "TIME_OUT_WF",
      "retryLogic": "FIXED",
      "retryDelaySeconds": 60,
      "responseTimeoutSeconds": 600,
      "concurrentExecLimit": 100,
      "rateLimitFrequencyInSeconds": 60,
      "rateLimitPerFrequency": 50
    }
  ]'
```

**Expected Response:** HTTP 204 (No Content) or HTTP 200

## Step 2: Register Workflow Definition

```bash
curl -X POST "http://localhost:8080/api/metadata/workflow" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "simple_test_workflow",
    "description": "A simple workflow for testing",
    "version": 1,
    "ownerEmail": "test@example.com",
    "tasks": [
      {
        "name": "simple_task",
        "taskReferenceName": "simple_task_ref",
        "type": "SIMPLE",
        "inputParameters": {
          "message": "${workflow.input.message}"
        }
      }
    ],
    "schemaVersion": 2,
    "restartable": true,
    "workflowStatusListenerEnabled": true
  }'
```

**Expected Response:** HTTP 204 (No Content)

## Step 3: Verify Task Definition

```bash
curl -X GET "http://localhost:8080/api/metadata/taskdefs/simple_task" | jq
```

**Expected Response:**
```json
{
  "name": "simple_task",
  "description": "A simple test task",
  "ownerEmail": "test@example.com",
  "retryCount": 3,
  ...
}
```

## Step 4: Verify Workflow Definition

```bash
curl -X GET "http://localhost:8080/api/metadata/workflow/simple_test_workflow?version=1" | jq
```

**Expected Response:**
```json
{
  "name": "simple_test_workflow",
  "description": "A simple workflow for testing",
  "version": 1,
  "tasks": [...]
}
```

## Step 5: Start Workflow Execution

```bash
curl -X POST "http://localhost:8080/api/workflow" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "simple_test_workflow",
    "version": 1,
    "input": {
      "message": "Hello from traced workflow"
    }
  }'
```

**Expected Response:** Workflow ID (string)
```
908c428e-3b92-40c8-9f43-fd1ffbf49cac
```

**Save the workflow ID for next steps!**

## Step 6: Check Workflow Status

```bash
# Replace WORKFLOW_ID with the ID from Step 5
export WORKFLOW_ID="908c428e-3b92-40c8-9f43-fd1ffbf49cac"

curl -X GET "http://localhost:8080/api/workflow/${WORKFLOW_ID}?includeTasks=true" | jq '{
  workflowId: .workflowId,
  status: .status,
  input: .input,
  tasks: [.tasks[] | {
    taskId: .taskId,
    referenceTaskName: .referenceTaskName,
    status: .status,
    taskType: .taskType
  }]
}'
```

**Expected Response:**
```json
{
  "workflowId": "908c428e-3b92-40c8-9f43-fd1ffbf49cac",
  "status": "RUNNING",
  "input": {
    "message": "Hello from traced workflow"
  },
  "tasks": [
    {
      "taskId": "e2a867a3-fdf4-46b7-b01b-8f54644afa46",
      "referenceTaskName": "simple_task_ref",
      "status": "SCHEDULED",
      "taskType": "simple_task"
    }
  ]
}
```

## Step 7: Check Task Queue

```bash
curl -X GET "http://localhost:8080/api/tasks/queue/all" | jq '{
  simple_task: .simple_task,
  _deciderQueue: ._deciderQueue
}'
```

**Expected Response:**
```json
{
  "simple_task": 1,
  "_deciderQueue": 4
}
```

## Step 8: Poll for Task (Worker Simulation)

```bash
curl -X GET "http://localhost:8080/api/tasks/poll/simple_task" | jq
```

**Expected Response:** Task object with details
```json
{
  "taskType": "simple_task",
  "status": "IN_PROGRESS",
  "inputData": {
    "message": "Hello from traced workflow"
  },
  "taskId": "e2a867a3-fdf4-46b7-b01b-8f54644afa46",
  "workflowInstanceId": "908c428e-3b92-40c8-9f43-fd1ffbf49cac",
  ...
}
```

**Save the taskId for the next step!**

## Step 9: Complete the Task

```bash
# Replace WORKFLOW_ID and TASK_ID with actual values
export WORKFLOW_ID="908c428e-3b92-40c8-9f43-fd1ffbf49cac"
export TASK_ID="e2a867a3-fdf4-46b7-b01b-8f54644afa46"

curl -X POST "http://localhost:8080/api/tasks" \
  -H "Content-Type: application/json" \
  -d "{
    \"workflowInstanceId\": \"${WORKFLOW_ID}\",
    \"taskId\": \"${TASK_ID}\",
    \"status\": \"COMPLETED\",
    \"outputData\": {
      \"result\": \"Task completed successfully\",
      \"message\": \"Processed: Hello from traced workflow\"
    }
  }"
```

**Expected Response:** Task ID
```
e2a867a3-fdf4-46b7-b01b-8f54644afa46
```

## Step 10: Verify Workflow Completion

```bash
curl -X GET "http://localhost:8080/api/workflow/${WORKFLOW_ID}" | jq '{
  workflowName: .workflowName,
  version: .workflowVersion,
  status: .status,
  input: .input,
  output: .output,
  startTime: .startTime,
  endTime: .endTime
}'
```

**Expected Response:**
```json
{
  "workflowName": "simple_test_workflow",
  "version": 1,
  "status": "COMPLETED",
  "input": {
    "message": "Hello from traced workflow"
  },
  "output": {
    "result": "Task completed successfully",
    "message": "Processed: Hello from traced workflow"
  },
  "startTime": 1762842622937,
  "endTime": 1762842940029
}
```

## Step 11: Query OpenSearch Index

```bash
curl -X GET "http://localhost:9201/conductor_workflow/_search" \
  -H "Content-Type: application/json" \
  -d "{
    \"query\": {
      \"match\": {
        \"workflowId\": \"${WORKFLOW_ID}\"
      }
    }
  }" | jq '.hits.hits[0]._source | {
    workflowId,
    workflowType,
    status,
    input,
    output,
    startTime,
    endTime
  }'
```

**Expected Response:**
```json
{
  "workflowId": "908c428e-3b92-40c8-9f43-fd1ffbf49cac",
  "workflowType": "simple_test_workflow",
  "status": "COMPLETED",
  "input": "{message=Hello from traced workflow}",
  "output": "{result=Task completed successfully, message=Processed: Hello from traced workflow}",
  "startTime": "2025-11-11T06:30:22.937Z",
  "endTime": "2025-11-11T06:35:40.029Z"
}
```

## Additional Useful Commands

### List All Workflow Definitions
```bash
curl -X GET "http://localhost:8080/api/metadata/workflow" | jq
```

### List All Task Definitions
```bash
curl -X GET "http://localhost:8080/api/metadata/taskdefs" | jq
```

### Search Workflows by Status
```bash
curl -X GET "http://localhost:8080/api/workflow/search?query=status:COMPLETED" | jq
```

### Get Workflow Execution Path
```bash
curl -X GET "http://localhost:8080/api/workflow/${WORKFLOW_ID}/executions" | jq
```

### Terminate a Running Workflow
```bash
curl -X DELETE "http://localhost:8080/api/workflow/${WORKFLOW_ID}?reason=Manual termination"
```

### Restart a Workflow
```bash
curl -X POST "http://localhost:8080/api/workflow/${WORKFLOW_ID}/restart"
```

### Retry a Failed Workflow
```bash
curl -X POST "http://localhost:8080/api/workflow/${WORKFLOW_ID}/retry"
```

## Troubleshooting

### Check Conductor Server Health
```bash
curl -X GET "http://localhost:8080/health" | jq
```

### Check Queue Sizes
```bash
curl -X GET "http://localhost:8080/api/tasks/queue/sizes" | jq
```

### View Task Execution Logs
```bash
curl -X GET "http://localhost:8080/api/tasks/${TASK_ID}/log" | jq
```

### Check OpenSearch Indices
```bash
curl -X GET "http://localhost:9201/_cat/indices?v"
```

### Check OpenSearch Cluster Health
```bash
curl -X GET "http://localhost:9201/_cluster/health?pretty"
```

## Notes

1. **ownerEmail is required** for both task and workflow definitions (Jakarta validation)
2. **Task must be registered before workflow** that uses it
3. **Workflow stays RUNNING** until a worker polls and completes the task
4. **Status transitions**: SCHEDULED → IN_PROGRESS (on poll) → COMPLETED (on update)
5. **Output data** from task automatically becomes workflow output for single-task workflows
6. **OpenSearch indexing** happens asynchronously after workflow state changes

## Complete Example Script

```bash
#!/bin/bash

# Set base URL
BASE_URL="http://localhost:8080"

# Step 1: Register task
echo "Step 1: Registering task definition..."
curl -X POST "${BASE_URL}/api/metadata/taskdefs" \
  -H "Content-Type: application/json" \
  -d '[{"name":"simple_task","description":"A simple test task","ownerEmail":"test@example.com","retryCount":3,"timeoutSeconds":3600,"inputKeys":["message"],"outputKeys":["result"],"timeoutPolicy":"TIME_OUT_WF","retryLogic":"FIXED","retryDelaySeconds":60,"responseTimeoutSeconds":600}]'

echo -e "\n\nStep 2: Registering workflow definition..."
curl -X POST "${BASE_URL}/api/metadata/workflow" \
  -H "Content-Type: application/json" \
  -d '{"name":"simple_test_workflow","description":"A simple workflow for testing","version":1,"ownerEmail":"test@example.com","tasks":[{"name":"simple_task","taskReferenceName":"simple_task_ref","type":"SIMPLE","inputParameters":{"message":"${workflow.input.message}"}}],"schemaVersion":2,"restartable":true}'

echo -e "\n\nStep 3: Starting workflow..."
WORKFLOW_ID=$(curl -s -X POST "${BASE_URL}/api/workflow" \
  -H "Content-Type: application/json" \
  -d '{"name":"simple_test_workflow","version":1,"input":{"message":"Hello from traced workflow"}}')

echo "Workflow ID: ${WORKFLOW_ID}"

echo -e "\n\nStep 4: Checking workflow status..."
curl -s -X GET "${BASE_URL}/api/workflow/${WORKFLOW_ID}?includeTasks=true" | jq '{status: .status, tasks: [.tasks[] | {taskId: .taskId, status: .status}]}'

echo -e "\n\nStep 5: Polling for task..."
TASK_DATA=$(curl -s -X GET "${BASE_URL}/api/tasks/poll/simple_task")
TASK_ID=$(echo $TASK_DATA | jq -r '.taskId')

echo "Task ID: ${TASK_ID}"

echo -e "\n\nStep 6: Completing task..."
curl -s -X POST "${BASE_URL}/api/tasks" \
  -H "Content-Type: application/json" \
  -d "{\"workflowInstanceId\":\"${WORKFLOW_ID}\",\"taskId\":\"${TASK_ID}\",\"status\":\"COMPLETED\",\"outputData\":{\"result\":\"Task completed successfully\"}}"

echo -e "\n\nStep 7: Verifying workflow completion..."
sleep 2
curl -s -X GET "${BASE_URL}/api/workflow/${WORKFLOW_ID}" | jq '{status: .status, output: .output}'

echo -e "\n\nWorkflow execution completed!"
```

Save this script as `run_workflow.sh`, make it executable with `chmod +x run_workflow.sh`, and run it with `./run_workflow.sh`.
