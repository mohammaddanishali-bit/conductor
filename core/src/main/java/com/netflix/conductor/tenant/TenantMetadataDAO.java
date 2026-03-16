package com.netflix.conductor.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDefSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class TenantMetadataDAO {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantMetadataDAO.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public TenantMetadataDAO(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    /** Get all workflow definitions belonging to the given tenant. */
    public List<WorkflowDef> getWorkflowDefsByTenant(String tenantId) {
        String sql = "SELECT json_data FROM meta_workflow_def "
                + "WHERE json_data::jsonb->>'ownerApp' = ? "
                + "ORDER BY name, version";
        return queryList(sql, tenantId, WorkflowDef.class);
    }

    /** Get latest version of each workflow definition for the given tenant. */
    public List<WorkflowDef> getWorkflowDefsLatestVersionsByTenant(String tenantId) {
        String sql = "SELECT json_data FROM meta_workflow_def "
                + "WHERE json_data::jsonb->>'ownerApp' = ? "
                + "AND version = latest_version ORDER BY name";
        return queryList(sql, tenantId, WorkflowDef.class);
    }

    /** Get workflow names and versions for the given tenant. */
    public Map<String, List<WorkflowDefSummary>> getWorkflowNamesAndVersionsByTenant(String tenantId) {
        String sql = "SELECT json_data FROM meta_workflow_def "
                + "WHERE json_data::jsonb->>'ownerApp' = ? "
                + "ORDER BY name, version";
        List<WorkflowDef> defs = queryList(sql, tenantId, WorkflowDef.class);

        Map<String, List<WorkflowDefSummary>> result = new LinkedHashMap<>();
        for (WorkflowDef def : defs) {
            WorkflowDefSummary summary = new WorkflowDefSummary();
            summary.setName(def.getName());
            summary.setVersion(def.getVersion());
            summary.setCreateTime(def.getCreateTime());
            result.computeIfAbsent(def.getName(), k -> new ArrayList<>()).add(summary);
        }
        return result;
    }

    /** Get a specific workflow definition by name for the given tenant. */
    public Optional<WorkflowDef> getWorkflowDef(String tenantId, String name, Integer version) {
        if (version != null) {
            String sql = "SELECT json_data FROM meta_workflow_def "
                    + "WHERE json_data::jsonb->>'ownerApp' = ? "
                    + "AND name = ? AND version = ?";
            return querySingleTenantWithVersion(sql, tenantId, name, version, WorkflowDef.class);
        } else {
            String sql = "SELECT json_data FROM meta_workflow_def "
                    + "WHERE json_data::jsonb->>'ownerApp' = ? "
                    + "AND name = ? AND version = latest_version";
            return querySingleTenant(sql, tenantId, name, WorkflowDef.class);
        }
    }

    /** Get all task definitions belonging to the given tenant. */
    public List<TaskDef> getTaskDefsByTenant(String tenantId) {
        String sql = "SELECT json_data FROM meta_task_def "
                + "WHERE json_data::jsonb->>'ownerApp' = ? "
                + "ORDER BY name";
        return queryList(sql, tenantId, TaskDef.class);
    }

    /** Get a specific task definition by name for the given tenant. */
    public Optional<TaskDef> getTaskDef(String tenantId, String name) {
        String sql = "SELECT json_data FROM meta_task_def "
                + "WHERE json_data::jsonb->>'ownerApp' = ? "
                + "AND name = ?";
        return querySingleTenant(sql, tenantId, name, TaskDef.class);
    }

    // ---- internal helpers ----

    private <T> List<T> queryList(String sql, String param, Class<T> type) {
        List<T> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(objectMapper.readValue(rs.getString(1), type));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Database query failed: {}", sql, e);
            throw new RuntimeException("Database query failed", e);
        } catch (Exception e) {
            LOGGER.error("JSON deserialization failed", e);
            throw new RuntimeException("JSON deserialization failed", e);
        }
        return results;
    }

    private <T> Optional<T> querySingleTenant(String sql, String tenantId, String name, Class<T> type) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(objectMapper.readValue(rs.getString(1), type));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Database query failed: {}", sql, e);
            throw new RuntimeException("Database query failed", e);
        } catch (Exception e) {
            LOGGER.error("JSON deserialization failed", e);
            throw new RuntimeException("JSON deserialization failed", e);
        }
        return Optional.empty();
    }

    private <T> Optional<T> querySingleTenantWithVersion(
            String sql, String tenantId, String name, int version, Class<T> type) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, name);
            ps.setInt(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(objectMapper.readValue(rs.getString(1), type));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Database query failed: {}", sql, e);
            throw new RuntimeException("Database query failed", e);
        } catch (Exception e) {
            LOGGER.error("JSON deserialization failed", e);
            throw new RuntimeException("JSON deserialization failed", e);
        }
        return Optional.empty();
    }
}
