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

package de.adorsys.keycloak.config;

import de.adorsys.keycloak.config.model.RealmAttributesEnum;
import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.model.RealmScopeEnum;
import de.adorsys.keycloak.config.properties.RealmProperties;
import de.adorsys.keycloak.config.provider.KeycloakImportProvider;
import de.adorsys.keycloak.config.repository.RealmRepository;
import de.adorsys.keycloak.config.service.RealmImportService;
import org.keycloak.representations.idm.RealmRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import static java.util.Objects.nonNull;

@Component
public class KeycloakConfigRunner implements CommandLineRunner, ExitCodeGenerator {
    private static final Logger logger = LoggerFactory.getLogger(KeycloakConfigRunner.class);
    private static final long START_TIME = System.currentTimeMillis();

    private final KeycloakImportProvider keycloakImportProvider;
    private final RealmImportService realmImportService;
    private final RealmRepository realmRepository;
    private final RealmProperties realmProperties;

    private int exitCode = 0;

    @Autowired
    public KeycloakConfigRunner(
            KeycloakImportProvider keycloakImportProvider,
            RealmImportService realmImportService,
            RealmRepository realmRepository,
            RealmProperties realmProperties
    ) {
        this.keycloakImportProvider = keycloakImportProvider;
        this.realmImportService = realmImportService;
        this.realmRepository = realmRepository;
        this.realmProperties = realmProperties;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(String... args) {
        //import operator realms
        realmProperties.getOperators()
                .forEach(operatorRealmProperties -> upgradeRealm(RealmScopeEnum.OPERATOR,
                        operatorRealmProperties.getRealm(),
                        operatorRealmProperties.getResetVersion()));
    }

    private void upgradeRealm(RealmScopeEnum realmScope,
                              String realm,
                              Float resetVersion) {
        final float currentVersion = getRealmVersion(realm, resetVersion);
        final Map<String, RealmImport> realmImports = keycloakImportProvider.getAfterVersion(realmScope, realm, currentVersion).getRealmImports();
        try {
            for (Map.Entry<String, RealmImport> realmImport : realmImports.entrySet()) {
                logger.info("Importing file '{}'", realmImport.getKey());
                realmImportService.doImport(realmImport.getValue());
            }
        } catch (NullPointerException e) {
            throw e;
        } catch (Exception e) {
            logger.error(e.getMessage());

            exitCode = 1;

            if (logger.isDebugEnabled()) {
                throw e;
            }
        } finally {
            long totalTime = System.currentTimeMillis() - START_TIME;
            String formattedTime = new SimpleDateFormat("mm:ss.SSS").format(new Date(totalTime));
            logger.info("keycloak-config-cli running in {}.", formattedTime);
        }
    }

    private Float getRealmVersion(String realm,
                                  Float resetVersion) {
        final Float currentVersion = getCurrentRealmVersion(realm);
        if (nonNull(resetVersion) && currentVersion > resetVersion) {
            logger.info("Reset realm [{}] to version [{}]", realm, resetVersion);
            realmImportService.setupImportVersion(realm, resetVersion);
            realmImportService.setupImportChecksum(realm, "");
            return resetVersion;
        }
        return currentVersion;
    }

    private float getCurrentRealmVersion(String realm) {
        float currentVersion = KeycloakImportProvider.DEFAULT_REALM_VERSION;
        if (realmRepository.exists(realm)) {
            realmRepository.clearRealmCache(realm);
            final RealmRepresentation realmRepresentation = realmRepository.get(realm);
            final String versionAttribute = realmRepresentation.getAttributes()
                    .getOrDefault(RealmAttributesEnum.VERSION.getValue(), KeycloakImportProvider.DEFAULT_REALM_VERSION.toString());
            currentVersion = Float.parseFloat(versionAttribute);
            logger.info("Realm [{}] has version [{}] before importing updates", realm, currentVersion);
        } else {
            logger.info("Realm [{}] does not exists. It will be created", realm);
        }
        return currentVersion;
    }
}
