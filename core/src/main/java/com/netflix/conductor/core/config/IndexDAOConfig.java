package com.netflix.conductor.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.netflix.conductor.core.index.NoopIndexDAO;
import com.netflix.conductor.dao.IndexDAO;

/**
 * Fallback configuration that provides a no-op IndexDAO implementation when no other IndexDAO bean
 * is available (e.g., when indexing is disabled or no specific indexing backend like
 * Elasticsearch/OpenSearch is configured).
 */
@Configuration
public class IndexDAOConfig {

    @Bean
    @ConditionalOnMissingBean(IndexDAO.class)
    public IndexDAO indexDAO() {
        return new NoopIndexDAO();
    }
}
