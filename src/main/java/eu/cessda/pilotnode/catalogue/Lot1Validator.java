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

package eu.cessda.pilotnode.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.NonValidationKeyword;
import com.networknt.schema.SpecVersion;

/**
 * Validates service bundles against the EOSC-Lot-1 service catalogue model
 * (https://github.com/EOSC-Lot-1/eosc-service-catalogues-specs). The OpenAPI document of each release is bundled
 * in {@code src/main/resources/schemas}.
 */
public final class Lot1Validator {

    /** Releases of the model that can be read. */
    public enum Version {
        V1_0_0("v1.0.0"),
        V2_0_0("v2.0.0");

        private final String label;

        Version(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Map<Version, JsonSchema> bundleSchemas = new EnumMap<>(Version.class);

    public Lot1Validator() {
        ObjectMapper mapper = new ObjectMapper();
        for (Version version : Version.values()) {
            bundleSchemas.put(version, bundleSchema(mapper, version));
        }
    }

    private static JsonSchema bundleSchema(ObjectMapper mapper, Version version) {
        String path = "/schemas/eosc-lot1-" + version.label() + ".json";
        try (InputStream in = Lot1Validator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Bundled model not found: " + path);
            }
            JsonNode openApi = mapper.readTree(in);
            // A schema that points into the OpenAPI document's components, which are carried along.
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("$ref", "#/components/schemas/ServiceBundle");
            wrapper.set("components", openApi.get("components"));
            JsonMetaSchema meta = JsonMetaSchema.builder(JsonMetaSchema.getV202012())
                    .keyword(new NonValidationKeyword("components"))
                    .build();
            return JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
                    .metaSchema(meta)
                    .build()
                    .getSchema(wrapper);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }

    /** Validation messages for one {@code {id, service}} bundle; empty if it conforms. */
    public List<String> validate(Version version, JsonNode bundle) {
        return bundleSchemas.get(version).validate(bundle).stream().map(m -> m.getMessage()).sorted().toList();
    }
}
