#!/bin/bash
mvn package -DskipTests
java -jar ./target/keycloak-config-cli.jar --spring.profiles.active=local
