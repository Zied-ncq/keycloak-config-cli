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

package de.adorsys.keycloak.config.service;

import de.adorsys.keycloak.config.AbstractImportTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@TestPropertySource(properties = {
        "import.sync-user-federation=true"
})
public class SyncUserFederationIT extends AbstractImportTest {

    public static final ToStringConsumer LDAP_CONTAINER_LOGS = new ToStringConsumer();
    @Container
    public static final GenericContainer<?> LDAP_CONTAINER;
    private static final String REALM_NAME = "realmWithLdap";

    static {
        LDAP_CONTAINER = new GenericContainer<>(DockerImageName.parse("osixia/openldap" + ":" + "1.5.0"))
                .withExposedPorts(389, 636)
                .withEnv("LDAP_ORGANISATION", "test-suit")
                .withEnv("LDAP_ADMIN_PASSWORD", "admin123")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("import-files/user-federation/ldap-openldap-docker-init.ldif"),
                        "/container/service/slapd/assets/config/bootstrap/ldif/custom/ldap-openldap-docker-init.ldif"
                )

                .withNetwork(NETWORK)
                .withNetworkAliases("ldap")

                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(300));

        if (System.getProperties().getOrDefault("skipContainerStart", "false").equals("false")) {
            LDAP_CONTAINER.start();
            LDAP_CONTAINER.followOutput(LDAP_CONTAINER_LOGS);
        }
    }

    public SyncUserFederationIT() {
        this.resourcePath = "import-files/user-federation";
    }

    @Test
    @Order(0)
    void shouldCreateRealmWithUser() throws IOException {
        doImport("00_create_realm_with_federation.json");

        RealmRepresentation createdRealm = keycloakProvider.getInstance().realm(REALM_NAME).toRepresentation();

        assertThat(createdRealm.getRealm(), is(REALM_NAME));
        assertThat(createdRealm.isEnabled(), is(true));

        UserRepresentation createdUser = keycloakRepository.getUser(REALM_NAME, "jbrown");
        assertThat(createdUser.getUsername(), is("jbrown"));
        assertThat(createdUser.getEmail(), is("jbrown@keycloak.org"));
        assertThat(createdUser.isEnabled(), is(true));
        assertThat(createdUser.getFirstName(), is("James"));
        assertThat(createdUser.getLastName(), is("Brown"));
    }
}
