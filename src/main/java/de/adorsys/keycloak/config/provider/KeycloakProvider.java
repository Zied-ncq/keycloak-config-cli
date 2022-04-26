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

package de.adorsys.keycloak.config.provider;

import de.adorsys.keycloak.config.exception.KeycloakProviderException;
import de.adorsys.keycloak.config.properties.KeycloakConfigProperties;
import de.adorsys.keycloak.config.util.ResteasyUtil;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Form;
import java.text.MessageFormat;
import java.time.Duration;

/**
 * This class exists cause we need to create a single keycloak instance or to close the keycloak before using a new one
 * to avoid a deadlock.
 */
@Component
public class KeycloakProvider implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(KeycloakProvider.class);

    private final KeycloakConfigProperties properties;
    private final ResteasyClient resteasyClient;

    private Keycloak keycloak;

    private String version;

    @Autowired
    private KeycloakProvider(KeycloakConfigProperties properties) {
        this.properties = properties;
        this.resteasyClient = ResteasyUtil.getClient(
                !this.properties.isSslVerify(),
                this.properties.getHttpProxy()
        );
    }

    public Keycloak getInstance() {
        if (keycloak == null || keycloak.isClosed()) {
            keycloak = createKeycloak();

            checkServerVersion();
        }

        return keycloak;
    }

    public String getKeycloakVersion() {
        if (version == null) {
            version = getInstance().serverInfo().getInfo().getSystemInfo().getVersion();
        }

        return version;
    }

    public void refreshToken() {
        getInstance().tokenManager().refreshToken();
    }

    private Keycloak createKeycloak() {
        Keycloak result;
        if (properties.getAvailabilityCheck().isEnabled()) {
            result = getKeycloakWithRetry();
        } else {
            result = getKeycloak();
        }

        return result;
    }

    private Keycloak getKeycloakWithRetry() {
        Duration timeout = properties.getAvailabilityCheck().getTimeout();
        Duration retryDelay = properties.getAvailabilityCheck().getRetryDelay();

        RetryPolicy<Keycloak> retryPolicy = new RetryPolicy<Keycloak>()
                .withDelay(retryDelay)
                .withMaxDuration(timeout)
                .withMaxRetries(-1)
                .onRetry(e -> logger.debug("Attempt failure #{}: {}", e.getAttemptCount(), e.getLastFailure().getMessage()));

        logger.info("Wait {} seconds until {} is available ...", timeout.getSeconds(), properties.getUrl());

        try {
            return Failsafe.with(retryPolicy).get(() -> {
                Keycloak obj = getKeycloak();
                obj.serverInfo().getInfo();
                return obj;
            });
        } catch (Exception e) {
            String message = MessageFormat.format("Could not connect to keycloak in {0} seconds: {1}", timeout.getSeconds(), e.getMessage());
            throw new KeycloakProviderException(message);
        }
    }

    private Keycloak getKeycloak() {
        Keycloak keycloakInstance = getKeycloakInstance();
        keycloakInstance.tokenManager().getAccessToken();

        return keycloakInstance;
    }

    private Keycloak getKeycloakInstance() {
        return KeycloakBuilder.builder()
                .serverUrl(properties.getUrl().toString())
                .realm(properties.getLoginRealm())
                .clientId(properties.getClientId())
                .grantType(properties.getGrantType())
                .clientSecret(properties.getClientSecret())
                .username(properties.getUser())
                .password(properties.getPassword())
                .resteasyClient(resteasyClient)
                .build();
    }

    private void checkServerVersion() {
        if (properties.getVersion().equals("@keycloak.version@")) return;

        String kccKeycloakMajorVersion = properties.getVersion().split("\\.")[0];

        if (!getKeycloakVersion().startsWith(kccKeycloakMajorVersion)) {
            logger.warn(
                    "Local keycloak-config-cli ({}-{}) and remote Keycloak ({}) may not compatible.",
                    getClass().getPackage().getImplementationVersion(),
                    properties.getVersion(),
                    getKeycloakVersion()
            );
        }
    }

    @Override
    public void close() {
        if (!isClosed()) {
            logout();
            keycloak.close();
        }
    }

    private void logout() {
        ResteasyWebTarget target = resteasyClient
                .target(properties.getUrl().toString())
                .path("/realms/" + properties.getLoginRealm() + "/protocol/openid-connect/logout");


        Form form = new Form()
                .param("client_id", properties.getClientId())
                .param("refresh_token", this.keycloak.tokenManager().getAccessToken().getRefreshToken());

        target.request().post(Entity.form(form));
    }

    public boolean isClosed() {
        return keycloak == null || keycloak.isClosed();
    }
}
