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
import java.util.Collection;
import java.util.HashMap;
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
import com.mirth.connect.util.PropertiesConfigurationUtil;

public class ExtensionLoader{
    @Inject
    private static ExtensionLoader instance = new ExtensionLoader();

    public static ExtensionLoader getInstance() {
        return instance;
    }
    
    private Map<String, ConnectorMetaData> connectorMetaDataMap = new HashMap<String, ConnectorMetaData>();
    private Map<String, PluginMetaData> pluginMetaDataMap = new HashMap<String, PluginMetaData>();
    private Map<String, ConnectorMetaData> connectorProtocolsMap = new HashMap<String, ConnectorMetaData>();
    private Map<String, MetaData> invalidMetaDataMap = new HashMap<String, MetaData>();
    private Map<String, String> coreLibraryVersionPropertiesFilenamesMap = new HashMap<String, String>();
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
            try {
                connectCoreVersions = getCoreVersions();
            } catch (Exception e) {
                logger.error("An error occurred while attempting to determine the current core versions.", e);
                return false;
            }
            
            logger.debug("checking extension \"" + metaData.getName() + "\" version compatability: pluginCoreVersions=" + StringUtils.join(metaData.getCoreVersions()) + ", connectCoreVersions=" + StringUtils.join(connectCoreVersions));
            
            // Compare the coreVersions map between an extension and the Connect core libraries.
            // The coreVersions from an extension is the minimum version of the Connect core libraries that are required.
            // If any Connect core library version is lower than any respective coreVersions entry from an extension,
            // then the extension is not compatible.
            for (Map.Entry<String, String> extensionCoreVersionEntry : metaData.getCoreVersions().entrySet()) {
                if (connectCoreVersions.containsKey(extensionCoreVersionEntry.getKey())) {
                    Semver connectCoreVersion = new Semver(connectCoreVersions.get(extensionCoreVersionEntry.getKey()));
                    if (connectCoreVersion.isLowerThan(extensionCoreVersionEntry.getValue())) {
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
     * Get core library versions from each of the core library's *.version.properties files.
     * @return Map<String, String> == Map<coreLibaryName, coreLibraryVersion>
     * @throws FileNotFoundException
     * @throws ConfigurationException
     */
    private Map<String, String> getCoreVersions() throws FileNotFoundException, ConfigurationException {
        String coreLibraryVersionProperty = "library.version";
        Map<String, String> coreVersions = new HashMap<String, String>();
        
        initializeCoreLibraryVersionPropertiesFilenamesMap();
        
        InputStream versionPropertiesStream = null;
        try {
            for (Map.Entry<String, String> coreLibraryVersionProperties : coreLibraryVersionPropertiesFilenamesMap.entrySet()) {
                versionPropertiesStream = ResourceUtil.getResourceStream(ExtensionLoader.class, coreLibraryVersionProperties.getValue());
                PropertiesConfiguration versionConfig = PropertiesConfigurationUtil.create(versionPropertiesStream);
                coreVersions.put(coreLibraryVersionProperties.getKey(), versionConfig.getString(coreLibraryVersionProperty));
            }
        } finally {
            ResourceUtil.closeResourceQuietly(versionPropertiesStream);
        }
        
        return coreVersions;
    }
    
    private void initializeCoreLibraryVersionPropertiesFilenamesMap() {
        coreLibraryVersionPropertiesFilenamesMap.put("client", "mirth-core-client.version.properties");
        coreLibraryVersionPropertiesFilenamesMap.put("client-api", "mirth-core-client-api.version.properties");
        coreLibraryVersionPropertiesFilenamesMap.put("client-base", "mirth-core-client-base.version.properties");
        coreLibraryVersionPropertiesFilenamesMap.put("client-plugins", "mirth-core-client-plugins.version.properties");
        coreLibraryVersionPropertiesFilenamesMap.put("models", "mirth-core-models.version.properties");
        coreLibraryVersionPropertiesFilenamesMap.put("server-plugins", "mirth-core-server-plugins.version.properties");
        coreLibraryVersionPropertiesFilenamesMap.put("ui", "mirth-core-ui.version.properties");
        coreLibraryVersionPropertiesFilenamesMap.put("util", "mirth-core-util.version.properties");
    }
}
