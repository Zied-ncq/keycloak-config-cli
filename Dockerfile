FROM openjdk:17-slim


ENV KEYCLOAK_URL=keycloak:8080/auth

COPY ./target/keycloak-config-cli.jar ./keycloak-config-cli.jar
COPY commands.sh ./commands.sh
RUN ["chmod", "+x", "./commands.sh"]
ENTRYPOINT ["./commands.sh"]