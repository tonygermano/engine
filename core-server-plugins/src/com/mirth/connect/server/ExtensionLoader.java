/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.server;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.AndFileFilter;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.semver4j.Semver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.mirth.connect.donkey.util.ResourceUtil;
import com.mirth.connect.model.ConnectorMetaData;
import com.mirth.connect.model.MetaData;
import com.mirth.connect.model.PluginClass;
import com.mirth.connect.model.PluginClassCondition;
import com.mirth.connect.model.PluginMetaData;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.server.extprops.ExtensionStatuses;
import com.mirth.connect.server.tools.ClassPathResource;
import com.mirth.connect.util.HttpUtil;
import com.mirth.connect.util.MirthSSLUtil;
import com.mirth.connect.util.PropertiesConfigurationUtil;

public class ExtensionLoader{
    @Inject
    private static ExtensionLoader instance = new ExtensionLoader();

    public static ExtensionLoader getInstance() {
        return instance;
    }
    
    private static final List<String> CORE_LIBRARY_VERSION_PROPERTIES_FILENAMES = Arrays.asList(
            "mirth-core-client.version.properties",
            "mirth-core-client-api.version.properties",
            "mirth-core-client-base.version.properties",
            "mirth-core-client-plugins.version.properties",
            "mirth-core-models.version.properties",
            "mirth-core-server-plugins.version.properties",
            "mirth-core-ui.version.properties",
            "mirth-core-util.version.properties");    
    private static final String EXTENSIONS_CORE_VERSIONS_S3_FILE_URL = "https://s3.amazonaws.com/downloads.mirthcorp.com/connect/extensions-core-versions/extensionsCoreVersions.json";
    private static int TIMEOUT = 30000;
    private static boolean HOSTNAME_VERIFICATION = true;
    
    private Map<String, ConnectorMetaData> connectorMetaDataMap = new HashMap<String, ConnectorMetaData>();
    private Map<String, PluginMetaData> pluginMetaDataMap = new HashMap<String, PluginMetaData>();
    private Map<String, ConnectorMetaData> connectorProtocolsMap = new HashMap<String, ConnectorMetaData>();
    private Map<String, MetaData> invalidMetaDataMap = new HashMap<String, MetaData>();
    private String extensionsCoreVersionsS3File;
    private boolean loadedExtensions = false;
    private ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
    private static Logger logger = LogManager.getLogger(ExtensionLoader.class);

    private ExtensionLoader() {}
    
    public Map<String, ConnectorMetaData> getConnectorMetaData() {
        loadExtensions();
        return connectorMetaDataMap;
    }

    public Map<String, PluginMetaData> getPluginMetaData() {
        loadExtensions();
        return pluginMetaDataMap;
    }

    public Map<String, ConnectorMetaData> getConnectorProtocols() {
        loadExtensions();
        return connectorProtocolsMap;
    }

    public Map<String, MetaData> getInvalidMetaData() {
        loadExtensions();
        return invalidMetaDataMap;
    }

    @SuppressWarnings("unchecked")
    public <T> Class<T> getControllerClass(Class<T> abstractClass) {
        Class<T> overrideClass = null;
        PluginClass highestPluginClassModel = null;

        ExtensionStatuses extensionStatuses = ExtensionStatuses.getInstance();

        for (PluginMetaData pluginMetaData : getPluginMetaData().values()) {
            if (extensionStatuses.isEnabled(pluginMetaData.getName())) {
                List<PluginClass> controllerClasses = pluginMetaData.getControllerClasses();

                if (controllerClasses != null) {
                    for (PluginClass controllerClassModel : controllerClasses) {
                        boolean accept = true;
                        String conditionClass = controllerClassModel.getConditionClass();
                        if (StringUtils.isNotBlank(conditionClass)) {
                            try {
                                accept = ((PluginClassCondition) Class.forName(conditionClass).newInstance()).accept(controllerClassModel);
                            } catch (Exception e) {
                                logger.warn("Error instantiating plugin condition class \"" + conditionClass + "\".");
                            }
                        }

                        if (accept) {
                            try {
                                Class<?> pluginClass = Class.forName(controllerClassModel.getName());

                                if (abstractClass.isAssignableFrom(pluginClass) && (highestPluginClassModel == null || highestPluginClassModel.getWeight() < controllerClassModel.getWeight())) {
                                    highestPluginClassModel = controllerClassModel;
                                    overrideClass = (Class<T>) pluginClass;
                                }
                            } catch (Exception e) {
                                logger.error("An error occurred while attempting to load \"" + controllerClassModel.getName() + "\" from plugin: " + pluginMetaData.getName(), e);
                            }
                        }
                    }
                }
            }
        }

        return overrideClass;
    }

    public <T> T getControllerInstance(Class<T> abstractClass) {
        Class<T> overrideClass = getControllerClass(abstractClass);

        if (overrideClass != null) {
            try {
                T instance = overrideClass.newInstance();
                logger.debug("Using custom " + abstractClass.getSimpleName() + ": " + overrideClass.getName());
                return instance;
            } catch (Exception e) {
                logger.error("An error occurred while attempting to instantiate " + abstractClass.getSimpleName() + " implementation: " + overrideClass.getName(), e);
            }
        }

        logger.debug("Using default " + abstractClass.getSimpleName());
        return null;
    }

    public boolean isExtensionCompatible(MetaData metaData) {
        if (metaData.getCoreVersions() != null) {
            // validate extension the new way for commercial extensions
            Map<String, String> connectCoreVersions = new HashMap<String, String>();
            Map<String, String> extensionMinCoreVersions = new HashMap<String, String>();
            Map<String, String> extensionMaxCoreVersions = new HashMap<String, String>();
            try {
                connectCoreVersions = getCoreVersions();
                extensionMinCoreVersions = metaData.getCoreVersions();
                extensionMaxCoreVersions = getExtensionMaxCoreVersions(metaData.getPath(), metaData.getPluginVersion());
            } catch (Exception e) {
                logger.error("An error occurred while attempting to determine the core versions.", e);
                return false;
            }
            
            logger.debug("checking extension \"" + metaData.getName() + "\" version compatability: extensionMinCoreVersions=" + StringUtils.join(extensionMinCoreVersions) + " extensionMaxCoreVersions=" + StringUtils.join(extensionMaxCoreVersions) + ", connectCoreVersions=" + StringUtils.join(connectCoreVersions));
            
            // connectCoreVersions      Connect Core library versions
            // extensionMinCoreVersions minimum coreVersions for an extension
            // extensionMaxCoreVersions maximum coreVersions for an extension
            // For an extension to be valid, extensionMinCoreVersions <= connectCoreVersions <= extensionMaxCoreVersions
            // must be true for all map entries.
            for (Map.Entry<String, String> connectCoreVersionEntry : connectCoreVersions.entrySet()) {
                if (extensionMinCoreVersions.containsKey(connectCoreVersionEntry.getKey()) && extensionMaxCoreVersions.containsKey(connectCoreVersionEntry.getKey())) {
                    Semver connectCoreVersion = new Semver(connectCoreVersionEntry.getValue());
                    if (connectCoreVersion.isLowerThan(extensionMinCoreVersions.get(connectCoreVersionEntry.getKey()))) {
                        return false;
                    }
                    
                    if (!extensionMaxCoreVersions.get(connectCoreVersionEntry.getKey()).isEmpty() && connectCoreVersion.isGreaterThan(extensionMaxCoreVersions.get(connectCoreVersionEntry.getKey()))) {
                        return false;
                    }
                }
            }
            
            return true;
        } else {
            // validate extension the old way for non-commercial extensions
            String serverMirthVersion;
            try {
                serverMirthVersion = getServerVersion();
            } catch (Exception e) {
                logger.error("An error occurred while attempting to determine the current server version.", e);
                return false;
            }
            
            String[] extensionMirthVersions = metaData.getMirthVersion().split(",");

            logger.debug("checking extension \"" + metaData.getName() + "\" version compatability: versions=" + ArrayUtils.toString(extensionMirthVersions) + ", server=" + serverMirthVersion);

            // if there is no build version, just use the patch version
            if (serverMirthVersion.split("\\.").length == 4) {
                serverMirthVersion = serverMirthVersion.substring(0, serverMirthVersion.lastIndexOf('.'));
            }

            for (int i = 0; i < extensionMirthVersions.length; i++) {
                if (extensionMirthVersions[i].trim().equals(serverMirthVersion)) {
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Loads the metadata files (plugin.xml, source.xml, destination.xml) for all extensions of the
     * specified type. If this function fails to parse the metadata file for an extension, it will
     * skip it and continue.
     */
    private synchronized void loadExtensions() {
        if (!loadedExtensions) {
            try {
                // match all of the file names for the extension
                IOFileFilter nameFileFilter = new NameFileFilter(new String[] { "plugin.xml",
                        "source.xml", "destination.xml" });
                // this is probably not needed, but we dont want to pick up directories,
                // so we AND the two filters
                IOFileFilter andFileFilter = new AndFileFilter(nameFileFilter, FileFilterUtils.fileFileFilter());
                // this is directory where extensions are located
                File extensionPath = new File(getExtensionsPath());
                // do a recursive scan for extension files
                Collection<File> extensionFiles = FileUtils.listFiles(extensionPath, andFileFilter, FileFilterUtils.trueFileFilter());
                
                // get extension Core versions JSON from S3
                extensionsCoreVersionsS3File = getExtensionsCoreVersionsFileFromS3();

                for (File extensionFile : extensionFiles) {
                    try {
                        MetaData metaData = (MetaData) serializer.deserialize(FileUtils.readFileToString(extensionFile), MetaData.class);

                        if (isExtensionCompatible(metaData)) {
                            if (metaData instanceof ConnectorMetaData) {
                                ConnectorMetaData connectorMetaData = (ConnectorMetaData) metaData;
                                connectorMetaDataMap.put(connectorMetaData.getName(), connectorMetaData);

                                if (StringUtils.contains(connectorMetaData.getProtocol(), ":")) {
                                    for (String protocol : connectorMetaData.getProtocol().split(":")) {
                                        connectorProtocolsMap.put(protocol, connectorMetaData);
                                    }
                                } else {
                                    connectorProtocolsMap.put(connectorMetaData.getProtocol(), connectorMetaData);
                                }
                            } else if (metaData instanceof PluginMetaData) {
                                pluginMetaDataMap.put(metaData.getName(), (PluginMetaData) metaData);
                            }
                        } else {
                            logger.error("Extension \"" + metaData.getName() + "\" is not compatible with this version of Mirth Connect and was not loaded. Please install a compatible version.");
                            invalidMetaDataMap.put(metaData.getName(), metaData);
                        }
                    } catch (Exception e) {
                        logger.error("Error reading or parsing extension metadata file: " + extensionFile.getName(), e);
                    }
                }
            } catch (Exception e) {
                logger.error("Error loading extension metadata.", e);
            } finally {
                loadedExtensions = true;
            }
        }
    }

    /**
     * If in an IDE, extensions will be on the classpath as a resource. If that's the case, use that
     * directory. Otherwise, use the mirth home directory and append extensions.
     * 
     * @return
     */
    private String getExtensionsPath() {
        if (ClassPathResource.getResourceURI("extensions") != null) {
            return ClassPathResource.getResourceURI("extensions").getPath() + File.separator;
        } else {
            return new File(ClassPathResource.getResourceURI("mirth.properties")).getParentFile().getParent() + File.separator + "extensions" + File.separator;
        }
    }

    private String getServerVersion() throws FileNotFoundException, ConfigurationException {
        PropertiesConfiguration versionConfig = PropertiesConfigurationUtil.create();
        
        InputStream versionPropertiesStream = null;
        try {
            versionPropertiesStream = ResourceUtil.getResourceStream(ExtensionLoader.class, "version.properties");
            versionConfig = PropertiesConfigurationUtil.create(versionPropertiesStream);
        } finally {
            ResourceUtil.closeResourceQuietly(versionPropertiesStream);
        }
        
        return versionConfig.getString("mirth.version");
    }
    
    /**
     * Get Core library versions from each of the Core library's *.version.properties files.
     * 
     * @return Map<String, String> == Map<coreLibaryName, coreLibraryVersion>
     * @throws FileNotFoundException
     * @throws ConfigurationException
     */
    private Map<String, String> getCoreVersions() throws FileNotFoundException, ConfigurationException {
        String coreLibraryVersionProperty = "library.version";
        Map<String, String> coreVersions = new HashMap<String, String>();
        
        InputStream versionPropertiesStream = null;
        try {
            for (String coreLibraryVersionPropertiesFilename : CORE_LIBRARY_VERSION_PROPERTIES_FILENAMES) {
                versionPropertiesStream = ResourceUtil.getResourceStream(ExtensionLoader.class, coreLibraryVersionPropertiesFilename);
                PropertiesConfiguration versionConfig = PropertiesConfigurationUtil.create(versionPropertiesStream);
                
                String coreLibraryName = coreLibraryVersionPropertiesFilename.substring(0, coreLibraryVersionPropertiesFilename.indexOf("."));
                String coreLibraryVersion = versionConfig.getString(coreLibraryVersionProperty);
                coreVersions.put(coreLibraryName, coreLibraryVersion);
            }
        } finally {
            ResourceUtil.closeResourceQuietly(versionPropertiesStream);
        }
        
        return coreVersions;
    }
    
    /**
     * Get the max Core library versions for a plugin.
     * 
     * @param pluginPath    Plugin name
     * @param pluginVersion Plugin version
     * @return Map<String, String> == Map<coreLibaryName, pluginMaxCoreLibraryVersion>
     * @throws FileNotFoundException
     * @throws ConfigurationException
     * @throws JsonProcessingException 
     * @throws JsonMappingException 
     */
    private Map<String, String> getExtensionMaxCoreVersions(String pluginPath, String pluginVersion) throws FileNotFoundException, ConfigurationException, JsonMappingException, JsonProcessingException {
        Map<String, String> pluginMaxCoreVersions = new HashMap<String, String>();        
        ObjectMapper mapper = new ObjectMapper();

        // Map.Entry<String, JsonNode> == Map.Entry<coreLibraryName, JsonNode(min, max)>
        Iterator<Map.Entry<String, JsonNode>> pluginCoreVersions = mapper.readTree(extensionsCoreVersionsS3File).get("extensionsData").get(pluginPath).get(pluginVersion).get("coreVersions").fields();        
        while(pluginCoreVersions.hasNext()) {
            Map.Entry<String, JsonNode> me = pluginCoreVersions.next();
            pluginMaxCoreVersions.put(me.getKey(), me.getValue().has("max") ? me.getValue().get("max").textValue() : "");
        }
        
        return pluginMaxCoreVersions;
    }
    
    /**
     * Get the JSON manifest file that contains all extensions' Core libraries min and max versions.
     * 
     * @return
     */
    private String getExtensionsCoreVersionsFileFromS3() {
        return HttpUtil.executeGetRequest(EXTENSIONS_CORE_VERSIONS_S3_FILE_URL, TIMEOUT, HOSTNAME_VERIFICATION, MirthSSLUtil.DEFAULT_HTTPS_CLIENT_PROTOCOLS, MirthSSLUtil.DEFAULT_HTTPS_CIPHER_SUITES);
    }
}
