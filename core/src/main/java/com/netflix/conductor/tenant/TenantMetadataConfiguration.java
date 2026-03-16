package com.netflix.conductor.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class TenantMetadataConfiguration {

    @Bean
    public TenantMetadataDAO tenantMetadataDAO(DataSource dataSource, ObjectMapper objectMapper) {
        return new TenantMetadataDAO(dataSource, objectMapper);
    }
}
