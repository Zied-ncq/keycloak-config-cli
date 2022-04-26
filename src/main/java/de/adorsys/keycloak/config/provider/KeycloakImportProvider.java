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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import de.adorsys.keycloak.config.exception.InvalidImportException;
import de.adorsys.keycloak.config.model.KeycloakImport;
import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.model.RealmScopeEnum;
import de.adorsys.keycloak.config.properties.ImportConfigProperties;
import de.adorsys.keycloak.config.properties.RealmProperties;
import de.adorsys.keycloak.config.service.checksum.ChecksumService;
import de.adorsys.keycloak.config.util.ChecksumUtil;
import de.adorsys.keycloak.config.util.RegexUtils;
import de.adorsys.keycloak.config.util.TextReplacer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.core.JsonEncoding.UTF8;
import static de.adorsys.keycloak.config.model.RealmScopeEnum.OPERATOR;
import static java.lang.String.format;
import static java.util.Objects.nonNull;

@Component
public class KeycloakImportProvider {
    public static final Float DEFAULT_REALM_VERSION = 0F;
    public static final Float DEFAULT_FILE_VERSION = 1.0F;
    private static final Logger logger = LoggerFactory.getLogger(KeycloakImportProvider.class);
    private static final ObjectMapper OBJECT_MAPPER_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ObjectMapper OBJECT_MAPPER_YAML = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ResourceLoader resourceLoader;
    private final Collection<ResourceExtractor> resourceExtractors;
    private final ImportConfigProperties importConfigProperties;
    private final ObjectMapper objectMapper;
    private final ChecksumService checksumService;
    private final TextReplacer textReplacer;
    private final RegexUtils regexUtils;
    private final RealmProperties realmProperties;
    private final String operatorImportDirectoryPath;
    private StringSubstitutor interpolator = null;

    @Autowired
    public KeycloakImportProvider(ResourceLoader resourceLoader,
                                  Collection<ResourceExtractor> resourceExtractors,
                                  ImportConfigProperties importConfigProperties,
                                  @Qualifier("json") ObjectMapper objectMapper,
                                  ChecksumService checksumService,
                                  TextReplacer textReplacer,
                                  RegexUtils regexUtils,
                                  RealmProperties realmProperties,
                                  @Value("${import.path.operator:#{null}}") String operatorImportDirectoryPath) {
        this.resourceLoader = resourceLoader;
        this.resourceExtractors = resourceExtractors;
        this.importConfigProperties = importConfigProperties;

        this.objectMapper = objectMapper;
        this.checksumService = checksumService;
        this.textReplacer = textReplacer;
        this.regexUtils = regexUtils;
        this.realmProperties = realmProperties;
        this.operatorImportDirectoryPath = operatorImportDirectoryPath;

        if (importConfigProperties.isVarSubstitution()) {
            String prefix = importConfigProperties.getVarSubstitutionPrefix();
            String suffix = importConfigProperties.getVarSubstitutionSuffix();

            this.interpolator = StringSubstitutor.createInterpolator()
                    .setVariablePrefix(prefix)
                    .setVariableSuffix(suffix)
                    .setEnableSubstitutionInVariables(importConfigProperties.isVarSubstitutionInVariables())
                    .setEnableUndefinedVariableException(importConfigProperties.isVarSubstitutionUndefinedThrowsExceptions());
        }
    }

    public KeycloakImport readFromPath(String path) {
        // backward compatibility to correct a possible missing prefix "file:" in path
        if (!ResourceUtils.isUrl(path)) {
            path = "file:" + path;
        }

        Resource resource = resourceLoader.getResource(path);
        Optional<ResourceExtractor> maybeMatchingExtractor = resourceExtractors.stream().filter(r -> {
            try {
                return r.canHandleResource(resource);
            } catch (IOException e) {
                return false;
            }
        }).findFirst();

        if (!maybeMatchingExtractor.isPresent()) {
            throw new InvalidImportException(format("No resource extractor found to handle config property import.path=%s! Check your settings.",
                    path));
        }

        try {
            return readRealmImportsFromResource(maybeMatchingExtractor.get().extract(resource));
        } catch (IOException e) {
            throw new InvalidImportException("import.path does not exists: " + path, e);
        }
    }

    private KeycloakImport readRealmImportsFromResource(Collection<File> importResources) {
        Map<String, RealmImport> realmImports = importResources.stream()
                // https://stackoverflow.com/a/52130074/8087167
                .collect(Collectors.toMap(File::getAbsolutePath, this::readRealmImport, (u, v) -> {
                    throw new IllegalStateException(format("Duplicate key %s", u));
                }, TreeMap::new));
        return new KeycloakImport(realmImports);
    }

    public KeycloakImport readRealmImportFromFile(File importFile) {
        Map<String, RealmImport> realmImports = new HashMap<>();

        RealmImport realmImport = readRealmImport(importFile);
        realmImports.put(importFile.getAbsolutePath(), realmImport);

        return new KeycloakImport(realmImports);
    }

    private RealmImport readRealmImport(File importFile) {
        ImportConfigProperties.ImportFileType fileType = importConfigProperties.getFileType();

        ObjectMapper objectMapper;

        switch (fileType) {
            case YAML:
                objectMapper = OBJECT_MAPPER_YAML;
                break;
            case JSON:
                objectMapper = OBJECT_MAPPER_JSON;
                break;
            case AUTO:
                String fileExt = FilenameUtils.getExtension(importFile.getName());
                switch (fileExt) {
                    case "yaml":
                    case "yml":
                        objectMapper = OBJECT_MAPPER_YAML;
                        break;
                    case "json":
                        objectMapper = OBJECT_MAPPER_JSON;
                        break;
                    default:
                        throw new InvalidImportException("Unknown file extension: " + fileExt);
                }
                break;
            default:
                throw new InvalidImportException("Unknown import file type: " + fileType);
        }
        String importConfig;

        try {
            importConfig = FileUtils.readFileToString(importFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }

        if (importConfigProperties.isVarSubstitution()) {
            importConfig = interpolator.replace(importConfig);
        }

        String checksum = ChecksumUtil.checksum(importConfig.getBytes(StandardCharsets.UTF_8));

        try {
            RealmImport realmImport = objectMapper.readValue(importConfig, RealmImport.class);
            realmImport.setChecksum(checksum);

            return realmImport;
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }
    }

    private RealmImport readRealmImport(RealmScopeEnum scopeEnum, String realm, File importFile) {
        return convertToRealmImport(scopeEnum, realm, importFile)
                .setChecksum(calculateChecksum(importFile))
                .setVersion(getFileVersion(importFile.getName()));
    }

    public KeycloakImport getAfterVersion(RealmScopeEnum scopeEnum, String realm, float currentVersion) {
        final String importDirectoryPath;
        if (OPERATOR.equals(scopeEnum)) {
            importDirectoryPath = operatorImportDirectoryPath;
        } else {
            importDirectoryPath = "undefined_path";
        }
        return readFromDirectory(scopeEnum, importDirectoryPath, realm, currentVersion);
    }

    private KeycloakImport readFromDirectory(RealmScopeEnum scopeEnum,
                                             String filename,
                                             String realm,
                                             float currentVersion) {
        File configDirectory = new File(filename);

        if (!configDirectory.exists()) {
            throw new InvalidImportException(format("[import.path.%s=%s] does not exists",
                    scopeEnum.toString().toLowerCase(),
                    filename));
        }
        if (!configDirectory.isDirectory()) {
            throw new InvalidImportException(format("[import.path.%s=%s] is not a directory: %s",
                    filename,
                    scopeEnum.toString().toLowerCase()));
        }

        return readRealmImportsFromDirectory(scopeEnum, configDirectory, realm, currentVersion);
    }

    public KeycloakImport readRealmImportsFromDirectory(RealmScopeEnum scopeEnum, File importFilesDirectory, String realm, float currentVersion) {

        final float maxVersion = getMaxVersion(scopeEnum);
        final Map<String, RealmImport> realmImports = Optional.ofNullable(importFilesDirectory.listFiles())
                .map(Arrays::asList)
                .orElse(Collections.emptyList())
                .stream()
                .filter(this::isJsonFile)
                .filter(file -> isStrictlyAfterVersion(file.getName(), currentVersion))
                .filter(file -> isBeforeVersion(file.getName(), maxVersion))
                .sorted(this::sortImportFilesByName)
                .collect(Collectors.toMap(File::getName, file -> readRealmImport(scopeEnum, realm, file), (e1, e2) -> e2, LinkedHashMap::new));

        return new KeycloakImport(realmImports);
    }

    private float getMaxVersion(RealmScopeEnum scopeEnum) {
        if (OPERATOR.equals(scopeEnum) && nonNull(realmProperties.getAdvancedSettings().getOperatorMaxVersion())) {
            logger.info("Max [{}] Version Limitation detected [max-version: {}]",
                    scopeEnum,
                    realmProperties.getAdvancedSettings().getOperatorMaxVersion());
            return realmProperties.getAdvancedSettings().getOperatorMaxVersion();
        }
        logger.info("No [{}] Version Limitation detected", scopeEnum);
        return Float.MAX_VALUE;
    }

    private boolean isJsonFile(File file) {
        return file.isFile() && file.getName().endsWith(".json");
    }

    private int sortImportFilesByName(File file1, File file2) {
        final float fileVersion1 = getFileVersion(file1.getName());
        final float fileVersion2 = getFileVersion(file2.getName());
        return Float.compare(fileVersion1, fileVersion2);
    }

    private boolean isStrictlyAfterVersion(String fileName, float currentVersion) {
        float fileVersion = getFileVersion(fileName);
        return fileVersion > currentVersion;
    }

    private boolean isBeforeVersion(String fileName, float maxVersion) {
        float fileVersion = getFileVersion(fileName);
        return maxVersion >= fileVersion;
    }


    private float getFileVersion(String fileName) {
        final String[] split = fileName.substring(0, fileName.indexOf(".json")).split("-");
        return split.length > 1 ? Float.parseFloat(split[1]) : DEFAULT_FILE_VERSION;
    }

    private RealmImport convertToRealmImport(RealmScopeEnum scopeEnum, String realm, File importFile) {
        RealmImport realmImport;

        try {
            final String templateContent = FileUtils.readFileToString(importFile, UTF8.getJavaName());
            final String realmImportContent = replaceParametersInTemplate(scopeEnum, realm, templateContent);
            realmImport = objectMapper.readValue(realmImportContent, RealmImport.class);
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }

        return realmImport;
    }

    private String replaceParametersInTemplate(RealmScopeEnum scopeEnum, String realm, String templateContent) {
        final Set<String> ids = regexUtils.extractUniqueUuids(templateContent);
        final Map<String, String> replacements;
        if (OPERATOR.equals(scopeEnum)) {
            replacements = realmProperties.getOperatorPropertiesAsMap(realm);
        } else {
            replacements = new HashMap<>();
        }
        ids.forEach(uuidInFile -> replacements.put(uuidInFile, UUID.randomUUID().toString()));
        return textReplacer.replaceAll(templateContent, replacements);
    }

    private String calculateChecksum(File importFile) {
        byte[] importFileInBytes = readRealmImportToBytes(importFile);
        return checksumService.checksum(importFileInBytes);
    }

    private byte[] readRealmImportToBytes(File importFile) {
        byte[] importFileInBytes;

        try {
            importFileInBytes = Files.readAllBytes(importFile.toPath());
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }

        return importFileInBytes;
    }
}
