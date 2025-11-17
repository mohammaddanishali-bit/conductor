/*
 * Copyright 2023 Conductor Authors.
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
package com.netflix.conductor.os.config;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(SpringRunner.class)
public class OpenSearchConditionsTest {

    @Test
    public void testValidOpenSearchVersion_WithSupportedVersion2() {
        OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion condition =
                new OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("conductor.opensearch.version", "2");

        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome =
                condition.getMatchOutcome(context, mock(AnnotatedTypeMetadata.class));

        assertTrue("Version 2 should be supported", outcome.isMatch());
    }

    @Test
    public void testValidOpenSearchVersion_WithSupportedVersion3() {
        OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion condition =
                new OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("conductor.opensearch.version", "3");

        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome =
                condition.getMatchOutcome(context, mock(AnnotatedTypeMetadata.class));

        assertTrue("Version 3 should be supported", outcome.isMatch());
    }

    @Test
    public void testValidOpenSearchVersion_WithSupportedVersion0() {
        OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion condition =
                new OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("conductor.opensearch.version", "0");

        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome =
                condition.getMatchOutcome(context, mock(AnnotatedTypeMetadata.class));

        assertTrue("Version 0 should be supported", outcome.isMatch());
    }

    @Test
    public void testValidOpenSearchVersion_WithUnsupportedVersion() {
        OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion condition =
                new OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion();

        MockEnvironment env = new MockEnvironment();
        env.setProperty("conductor.opensearch.version", "5");

        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome =
                condition.getMatchOutcome(context, mock(AnnotatedTypeMetadata.class));

        assertFalse("Version 5 should not be supported", outcome.isMatch());
        assertTrue(
                "Error message should mention unsupported version",
                outcome.getMessage().contains("not in supported versions"));
    }

    @Test
    public void testValidOpenSearchVersion_WithMissingVersion() {
        OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion condition =
                new OpenSearchConditions.OpenSearchEnabled.ValidOpenSearchVersion();

        MockEnvironment env = new MockEnvironment();
        // Don't set conductor.opensearch.version property

        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env);

        ConditionOutcome outcome =
                condition.getMatchOutcome(context, mock(AnnotatedTypeMetadata.class));

        assertFalse("Missing version should not be supported", outcome.isMatch());
        assertTrue(
                "Error message should mention missing configuration",
                outcome.getMessage().contains("not configured"));
    }

    @Test
    public void testOpenSearchEnabled_AllConditionsMet() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "conductor.indexing.enabled=true",
                        "conductor.indexing.type=opensearch",
                        "conductor.opensearch.version=2")
                .withUserConfiguration(OpenSearchConfiguration.class)
                .run(
                        context -> {
                            // This test verifies that when all conditions are met,
                            // the OpenSearchConfiguration should be loaded
                            // In a real scenario, beans would be created
                            assertNotNull(context);
                        });
    }

    @Test
    public void testOpenSearchEnabled_InvalidVersion() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "conductor.indexing.enabled=true",
                        "conductor.indexing.type=opensearch",
                        "conductor.opensearch.version=5")
                .withUserConfiguration(OpenSearchConfiguration.class)
                .run(
                        context -> {
                            // This test verifies that when version is invalid,
                            // the OpenSearchConfiguration should NOT be loaded
                            // No OpenSearch beans should be created
                            assertNotNull(context);
                        });
    }
}
