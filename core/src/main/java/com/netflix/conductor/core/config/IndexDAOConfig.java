/*
 * Copyright 2025 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.netflix.conductor.core.index.NoopIndexDAO;
import com.netflix.conductor.dao.IndexDAO;

/**
 * Fallback configuration that provides a no-op IndexDAO implementation when no other IndexDAO bean
 * is available and indexing is not explicitly disabled.
 */
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
