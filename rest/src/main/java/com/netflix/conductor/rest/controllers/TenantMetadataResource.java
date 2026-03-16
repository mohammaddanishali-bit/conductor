package com.netflix.conductor.rest.controllers;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDefSummary;
import com.netflix.conductor.tenant.TenantMetadataDAO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tenant/metadata")
public class TenantMetadataResource {

    private final TenantMetadataDAO tenantMetadataDAO;

    public TenantMetadataResource(TenantMetadataDAO tenantMetadataDAO) {
        this.tenantMetadataDAO = tenantMetadataDAO;
    }

    // ---- Workflow Definition Endpoints ----

    @GetMapping("/workflow")
    public List<WorkflowDef> getWorkflowDefs(
            @RequestHeader("X-Tenant-ID") String tenantId) {
        return tenantMetadataDAO.getWorkflowDefsByTenant(tenantId);
    }

    @GetMapping("/workflow/latest-versions")
    public List<WorkflowDef> getWorkflowDefsLatestVersions(
            @RequestHeader("X-Tenant-ID") String tenantId) {
        return tenantMetadataDAO.getWorkflowDefsLatestVersionsByTenant(tenantId);
    }

    @GetMapping("/workflow/names-and-versions")
    public Map<String, List<WorkflowDefSummary>> getWorkflowNamesAndVersions(
            @RequestHeader("X-Tenant-ID") String tenantId) {
        return tenantMetadataDAO.getWorkflowNamesAndVersionsByTenant(tenantId);
    }

    @GetMapping("/workflow/{name}")
    public ResponseEntity<WorkflowDef> getWorkflowDef(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable("name") String name,
            @RequestParam(value = "version", required = false) Integer version) {
        return tenantMetadataDAO.getWorkflowDef(tenantId, name, version)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- Task Definition Endpoints ----

    @GetMapping("/taskdefs")
    public List<TaskDef> getTaskDefs(
            @RequestHeader("X-Tenant-ID") String tenantId) {
        return tenantMetadataDAO.getTaskDefsByTenant(tenantId);
    }

    @GetMapping("/taskdefs/{tasktype}")
    public ResponseEntity<TaskDef> getTaskDef(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable("tasktype") String taskType) {
        return tenantMetadataDAO.getTaskDef(tenantId, taskType)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
