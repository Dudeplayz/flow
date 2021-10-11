/*
 * Copyright 2000-2021 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.experimental;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import com.vaadin.flow.server.Constants;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.startup.ApplicationConfiguration;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks available feature flags and their status.
 * 
 * Enabled feature flags are stored in
 * <code>vaadin-featureflags.properties</code> inside the resources folder
 * (<code>src/main/resources</code>).
 */
public class FeatureFlags implements Serializable {

    public static final String PROPERTIES_FILENAME = "vaadin-featureflags.properties";

    private static final Feature EXAMPLE = new Feature(
            "Example feature. Will be removed once the first real feature flag is added",
            "exampleFeatureFlag", "https://github.com/vaadin/flow/pull/12004");
    private static List<Feature> features = new ArrayList<>();

    static {
        features.add(EXAMPLE);
        loadProperties();
    }

    /**
     * Set by the Maven / Gradle plugin when running through that so the feature
     * flags will be correctly detected.
     */
    public static void setPropertiesLocation(File propertiesFolder) {
        File featureProperties = new File(propertiesFolder,
                PROPERTIES_FILENAME);
        if (featureProperties != null && featureProperties.exists()) {
            try (FileInputStream stream = new FileInputStream(
                    featureProperties)) {
                loadProperties(stream);
            } catch (IOException e) {
                getLogger().error("Unable to read properties from file "
                        + featureProperties.getAbsolutePath(), e);
            }
        }
    }

    private static void loadProperties() {
        File featureFlagFile = getFeatureFlagFile();
        if (!featureFlagFile.exists()) {
            return;
        }

        try (FileInputStream propertiesStream = new FileInputStream(
                featureFlagFile)) {
            loadProperties(propertiesStream);
        } catch (IOException e) {
            getLogger().error("Unable to read properties using classloader", e);
        }
    }

    private static void loadProperties(InputStream propertiesStream) {
        try {
            Properties props = new Properties();

            if (propertiesStream != null) {
                props.load(propertiesStream);
            }
            for (Feature f : features) {
                if ("true".equals(props.getProperty(getPropertyName(f.getId()),
                        "false"))) {
                    f.setEnabled(true);
                }
            }

        } catch (IOException e) {
            getLogger().error("Unable to read feature flags", e);
        }
    }

    private static void saveProperties() {
        File featureFlagFile = getFeatureFlagFile();
        StringBuilder properties = new StringBuilder();
        for (Feature feature : features) {
            if (!feature.isEnabled()) {
                continue;
            }
            properties.append("# ").append(feature.getTitle()).append("\n");
            properties.append(getPropertyName(feature.getId()))
                    .append("=true\n");
        }
        if (!featureFlagFile.getParentFile().exists()) {
            featureFlagFile.getParentFile().mkdirs();
        }
        try (FileWriter writer = new FileWriter(featureFlagFile)) {
            IOUtils.write(properties.toString(), writer);
        } catch (IOException e) {
            getLogger().error("Unable to store feature flags", e);
        }
    }

    /**
     * Get a list of all available features and their status.
     * 
     * @return a list of all features
     */
    public static List<Feature> getFeatures() {
        return features;
    }

    /**
     * Checks if the given feature is enabled.
     * 
     * @param feature
     *            the feature to check
     * @return <code>true</code> if enabled, <code>false</code> otherwise
     */
    public static boolean isEnabled(Feature feature) {
        return feature.isEnabled();
    }

    /**
     * Checks if the given feature is enabled.
     * 
     * @param featureId
     *            the feature to check
     * @return <code>true</code> if enabled, <code>false</code> otherwise
     */
    public static boolean isEnabled(String featureId) {
        return getFeature(featureId).map(f -> f.isEnabled()).orElse(false);
    }

    private static Optional<Feature> getFeature(String featureId) {
        return features.stream()
                .filter(feature -> feature.getId().equals(featureId))
                .findFirst();
    }

    private static String getPropertyName(String featureId) {
        return "com.vaadin.experimental." + featureId;
    }

    /**
     * Enables or disables the given feature.
     * 
     * @param featureId
     *            the feature id
     * @param enabled
     *            <code>true</code> to enable, <code>false</code> to disable
     */
    public static void setEnabled(String featureId, boolean enabled) {
        Optional<Feature> maybeFeature = getFeature(featureId);
        if (!maybeFeature.isPresent()) {
            throw new IllegalArgumentException("Unknown feature " + featureId);
        }
        Feature feature = maybeFeature.get();
        if (feature.isEnabled() == enabled) {
            return;
        }

        maybeFeature.get().setEnabled(enabled);
        // Update the feature flag file
        saveProperties();
        getLogger().info("Set feature {} to {}", featureId, enabled);
    }

    private static File getFeatureFlagFile() {
        ApplicationConfiguration config = ApplicationConfiguration
                .get(VaadinService.getCurrent().getContext());
        String resourceFolder = config.getStringProperty(
                Constants.JAVA_RESOURCE_FOLDER_TOKEN, "src/main/resources");
        return new File(resourceFolder, PROPERTIES_FILENAME);
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(FeatureFlags.class);
    }
}
