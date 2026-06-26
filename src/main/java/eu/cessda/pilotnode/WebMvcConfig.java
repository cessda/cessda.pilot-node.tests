/*
 * SPDX-FileCopyrightText: 2026 CESSDA ERIC (support@cessda.eu)
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package eu.cessda.pilotnode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Ensures static resources are served correctly alongside the /api/data REST
 * controller.
 *
 * <p>Without this, Spring MVC's handler mapping order can cause the {@code /**}
 * mapping in {@link DashboardDataController} to shadow the default static
 * resource handler, resulting in 404 responses for {@code index.html} and
 * {@code node.html}.</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String dataDir;

    public WebMvcConfig(@Value("${dashboard.data-dir}") String dataDirPath) {
        this.dataDir = dataDirPath;
    }

    /**
     * Explicitly registers the classpath static resource handler at the same
     * locations Spring Boot uses by default, but with a defined order that
     * ensures it is checked before the catch-all controller mapping.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Static paths
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true);

        // /api/data paths
        registry.addResourceHandler("/api/data/**")
                .addResourceLocations(dataDir, "classpath:/dashboard/data/");
    }
}