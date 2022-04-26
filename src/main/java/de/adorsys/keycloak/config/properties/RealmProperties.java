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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Component
@ConfigurationProperties(prefix = "configuration")
@Validated
public class RealmProperties {

    @NotNull
    private EnvironmentEnum env;

    private AdvancedSettingsProperties advancedSettings = new AdvancedSettingsProperties();

    @NotNull
    @Size(min = 1)
    private List<OperatorRealmProperties> operators = new ArrayList<>();

    public Map<String, String> getOperatorPropertiesAsMap(String realm) {
        return operators.stream()
                .filter(realmProperties -> realmProperties.getRealm().equals(realm))
                .map(realmProperties -> realmProperties.asMap(realm, env))
                .findFirst()
                .orElseThrow(() -> new RealmPropertiesNotFound("Realm properties not found for [realm: %s]", realm));
    }

    public EnvironmentEnum getEnv() {
        return env;
    }

    public RealmProperties setEnv(EnvironmentEnum env) {
        this.env = env;
        return this;
    }

    public AdvancedSettingsProperties getAdvancedSettings() {
        return advancedSettings;
    }

    public RealmProperties setAdvancedSettings(AdvancedSettingsProperties advancedSettings) {
        this.advancedSettings = advancedSettings;
        return this;
    }

    public List<OperatorRealmProperties> getOperators() {
        return operators;
    }

    public RealmProperties setOperators(List<OperatorRealmProperties> operators) {
        this.operators = operators;
        return this;
    }

}
