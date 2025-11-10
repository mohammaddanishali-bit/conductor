package com.netflix.conductor.os.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;

/**
 * Configuration to support both old and new property prefixes:
 *
 * <ul>
 *   <li><b>New:</b> conductor.opensearch.*
 *   <li><b>Old (deprecated):</b> conductor.elasticsearch.*
 * </ul>
 *
 * This allows backward compatibility for existing users migrating from Elasticsearch to OpenSearch.
 */
@Configuration
public class OpenSearchPropertiesConfiguration {

    private static final Logger logger =
            LoggerFactory.getLogger(OpenSearchPropertiesConfiguration.class);

    private final Environment environment;

    public OpenSearchPropertiesConfiguration(Environment environment) {
        this.environment = environment;
    }

    /** Primary bean bound to the new prefix: conductor.opensearch.* */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "conductor.opensearch")
    public OpenSearchProperties openSearchProperties() {
        return new OpenSearchProperties();
    }

    /**
     * Secondary bean bound to the old prefix: conductor.elasticsearch.* This ensures backward
     * compatibility with older configurations.
     *
     * Note: removed @ConfigurationProperties to avoid duplicate definition errors in Spring Boot 3.x
     * The deprecated prefix warning in warnIfUsingDeprecatedPrefix() will still alert users.
     */
    /** Logs a warning if the deprecated prefix is detected in the configuration. */
    @PostConstruct
    public void warnIfUsingDeprecatedPrefix() {
        if (environment.containsProperty("conductor.elasticsearch.url")) {
            logger.warn(
                    "\n⚠️  The property prefix 'conductor.elasticsearch.*' is DEPRECATED and will be removed in a future version.\n"
                            + "    Please migrate your configuration to 'conductor.opensearch.*'\n");
        }
    }

    /**
     * Validates the configured OpenSearch version. Only version 0 (default/auto-detect) and version
     * 2 (OpenSearch 2.x) are officially supported. Other versions will trigger a warning.
     */
    @PostConstruct
    public void validateOpenSearchVersion() {
        // Only validate if OpenSearch is enabled
        if (!isOpenSearchEnabled()) {
            return;
        }

        String configuredVersion = environment.getProperty("conductor.opensearch.version", "0");

        logger.info("OpenSearch configured version: {}", configuredVersion);

        // Version 0 is the default auto-detect mode
        if ("0".equals(configuredVersion)) {
            logger.info(
                    "OpenSearch version is set to '0' (default/auto-detect mode). This provides generic compatibility.");
            return;
        }

        // Version 2 is the officially supported version (OpenSearch 2.x)
        if ("2".equals(configuredVersion)) {
            logger.info(
                    "OpenSearch version is set to '2'. This is the officially supported version for OpenSearch 2.x (recommended: 2.18).");
            return;
        }

        // All other versions get a warning
        logger.warn(
                "\n⚠️  OpenSearch version '{}' is not officially supported!\n"
                        + "    Supported versions:\n"
                        + "      - '0' (default/auto-detect mode)\n"
                        + "      - '2' (OpenSearch 2.x - recommended)\n"
                        + "    Current configuration may cause compatibility issues.\n"
                        + "    Please update 'conductor.opensearch.version' to '2' for OpenSearch 2.x or '0' for auto-detect.\n",
                configuredVersion);
    }

    /**
     * Checks if OpenSearch indexing is enabled in the configuration.
     *
     * @return true if OpenSearch is enabled
     */
    private boolean isOpenSearchEnabled() {
        boolean indexingEnabled =
                Boolean.parseBoolean(environment.getProperty("conductor.indexing.enabled", "true"));
        String indexingType = environment.getProperty("conductor.indexing.type", "");

        return indexingEnabled && "opensearch".equalsIgnoreCase(indexingType);
    }
}
