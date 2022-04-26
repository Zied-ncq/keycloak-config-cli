/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2021 adorsys GmbH & Co. KG @ https://adorsys.com
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package de.adorsys.keycloak.config.properties;

import de.adorsys.keycloak.config.model.EnvironmentEnum;
import de.adorsys.keycloak.config.model.TemplateParameterEnum;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;

/**
 * @author hazem
 */
public class OperatorRealmProperties {

    @NotNull
    @Size(min = 1)
    private String realm;

    private Float resetVersion;


    public Map<String, String> asMap(String realm,
                                     EnvironmentEnum env) {
        final Map<String, String> params = new HashMap<>();
        params.put("[" + TemplateParameterEnum.REALM + "]", realm);
        return params;
    }

    public String getRealm() {
        return realm;
    }

    public OperatorRealmProperties setRealm(String realm) {
        this.realm = realm;
        return this;
    }

    public Float getResetVersion() {
        return resetVersion;
    }

    public OperatorRealmProperties setResetVersion(Float resetVersion) {
        this.resetVersion = resetVersion;
        return this;
    }
}
