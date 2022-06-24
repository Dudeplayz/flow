/*
 * Copyright 2000-2022 Vaadin Ltd.
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
package com.vaadin.flow.server.frontend;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.vaadin.experimental.FeatureFlags;

import static com.vaadin.flow.server.frontend.FrontendUtils.FEATURE_FLAGS_FILE_NAME;

/**
 * A task for generating the feature flags file
 * {@link FrontendUtils#FEATURE_FLAGS_FILE_NAME} during `package` Maven goal.
 * <p>
 * For internal use only. May be renamed or removed in a future release.
 *
 * @author Vaadin Ltd
 */
public class TaskGenerateFeatureFlags extends AbstractTaskClientGenerator {

    private final File frontendGeneratedDirectory;
    private final FeatureFlags featureFlags;

    TaskGenerateFeatureFlags(File frontendGeneratedDirectory,
            FeatureFlags featureFlags) {
        this.frontendGeneratedDirectory = frontendGeneratedDirectory;
        this.featureFlags = featureFlags;
    }

    @Override
    protected String getFileContent() {
        List<String> lines = new ArrayList<>();
        lines.add("// @ts-nocheck");
        lines.add("window.Vaadin = window.Vaadin || {};");
        lines.add(
                "window.Vaadin.featureFlags = window.Vaadin.featureFlags || {};");

        featureFlags.getFeatures().forEach(feature -> {
            lines.add(String.format("window.Vaadin.featureFlags.%s = %s;",
                    feature.getId(), featureFlags.isEnabled(feature)));
        });

        return String.join(System.lineSeparator(), lines);
    }

    @Override
    protected File getGeneratedFile() {
        return new File(frontendGeneratedDirectory, FEATURE_FLAGS_FILE_NAME);
    }

    @Override
    protected boolean shouldGenerate() {
        return true;
    }
}
