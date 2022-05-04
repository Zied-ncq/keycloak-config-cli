#!/bin/bash

ENV=""
ENV_ARRAY=("local" "staging")
usage() {
    echo "usage: [-e | -h]. Create or update realms in keycloak."
    echo " -e, --env    targeted env [local, staging]"
    echo " -h, --help   display this help and exit"
}

while [[ "$1" != "" ]]; do
    case $1 in
        -e | --env )            shift
                                ENV=$1
                                ;;
        -h | --help )           usage
                                exit
                                ;;
        * )                     usage
                                exit 1
    esac
    shift
done

notContainsElement () {
  local e match="$1"
  shift
  for e; do [[ "$e" == "$match" ]] && return 1; done
  return 0
}

validateParams() {
    if notContainsElement "${ENV}" "${ENV_ARRAY[@]}"; then
        printf '\e[1m\e[31m [Environment] should be a valid value [Current value: %s] [Accepted values: %s]\e[0m\n' "${ENV}" "${ENV_ARRAY[*]}"
        usage
        exit 1
    fi
}

validateParams

mvn package -DskipTests
java -jar ./target/keycloak-config-cli.jar --spring.profiles.active=$ENV
