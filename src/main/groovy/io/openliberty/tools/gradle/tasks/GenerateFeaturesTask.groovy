/**
 * (C) Copyright IBM Corporation 2021, 2022.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openliberty.tools.gradle.tasks


import io.openliberty.tools.common.plugins.config.ServerConfigXmlDocument
import io.openliberty.tools.common.plugins.config.XmlDocument
import io.openliberty.tools.common.plugins.util.BinaryScannerUtil
import static io.openliberty.tools.common.plugins.util.BinaryScannerUtil.*;
import io.openliberty.tools.common.plugins.util.PluginExecutionException
import io.openliberty.tools.common.plugins.util.ServerFeatureUtil
import io.openliberty.tools.gradle.utils.ArtifactDownloadUtil

import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.xml.sax.SAXException
import org.w3c.dom.Element;

import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.TransformerException

class GenerateFeaturesTask extends AbstractFeatureTask {

    protected static final String FEATURES_FILE_MESSAGE = "The Liberty Gradle Plugin has generated Liberty features necessary for your application in " + GENERATED_FEATURES_FILE_PATH;
    protected static final String HEADER = "This file was generated by the Liberty Gradle Plugin and will be overwritten on subsequent runs of the generateFeatures task." + "\n It is recommended that you do not edit this file and that you commit this file to your version control.";
    protected static final String GENERATED_FEATURES_COMMENT = "The following features were generated based on API usage detected in your application";
    protected static final String NO_NEW_FEATURES_COMMENT = "No additional features generated";

    private static final String BINARY_SCANNER_MAVEN_GROUP_ID = "com.ibm.websphere.appmod.tools";
    private static final String BINARY_SCANNER_MAVEN_ARTIFACT_ID = "binary-app-scanner";
    private static final String BINARY_SCANNER_MAVEN_TYPE = "jar";
    private static final String BINARY_SCANNER_MAVEN_VERSION = "[21.0.0.4-SNAPSHOT,)";

    private static final boolean DEFAULT_OPTIMIZE = true;

    private File binaryScanner;

    GenerateFeaturesTask() {
        configure({
            description 'Generate the features used by an application and add to the configuration of a Liberty server'
            group 'Liberty'
        })
    }

    private List<String> classFiles;

    @Option(option = 'classFile', description = 'If set and optimize is false, will generate features for the list of classes passed.')
    void setClassFiles(List<String> classFiles) {
        this.classFiles = classFiles;
    }

    private Boolean optimize = null;

    // Need to use a string value to allow the ability to specify a value for the parameter (ie. --optimize=false)
    @Option(option = 'optimize', description = 'Optimize generating features by passing in all classes and only user specified features.')
    void setOptimize(String optimize) {
        this.optimize = Boolean.parseBoolean(optimize);
    }

    @TaskAction
    void generateFeatures() {
        binaryScanner = getBinaryScannerJarFromRepository();
        BinaryScannerHandler binaryScannerHandler = new BinaryScannerHandler(binaryScanner);

        if (optimize == null) {
            optimize = DEFAULT_OPTIMIZE;
        }

        logger.debug("--- Generate Features values ---");
        logger.debug("optimize generate features: " + optimize);
        if (classFiles != null && !classFiles.isEmpty()) {
            logger.debug("Generate features for the following class files: " + classFiles);
        }

        initializeConfigDirectory();

        // TODO add support for env variables
        // commented out for now as the current logic depends on the server dir existing and specifying features with env variables is an edge case
        /* def serverDirectory = getServerDir(project);
        def libertyDirPropertyFiles;
        try {
            libertyDirPropertyFiles = getLibertyDirectoryPropertyFiles(getInstallDir(project), getUserDir(project), serverDirectory);
        } catch (IOException x) {
            logger.debug("Exception reading the server property files", e);
            logger.error("Error attempting to generate server feature list. Ensure your user account has read permission to the property files in the server installation directory.");
            return;
        } */

        // get existing server features from source directory
        ServerFeatureUtil servUtil = getServerFeatureUtil();

        Set<String> generatedFiles = new HashSet<String>();
        generatedFiles.add(GENERATED_FEATURES_FILE_NAME);

        Set<String> existingFeatures = getServerFeatures(servUtil, generatedFiles, optimize);
        logger.debug("Existing features:" + existingFeatures);

        Set<String> scannedFeatureList;
        try {
            Set<String> directories = getClassesDirectories();
            if (directories.isEmpty() && (classFiles == null || classFiles.isEmpty())) {
                // log as warning and continue to call binary scanner to detect conflicts in user specified features
                logger.warn("Could not find class files to generate features against. Liberty features will not be generated. "
                        + "Ensure your project has first been compiled.");
            }
            String eeVersion = getEEVersion(project);
            String mpVersion = getMPVersion(project);
            scannedFeatureList = binaryScannerHandler.runBinaryScanner(existingFeatures, classFiles, directories, eeVersion, mpVersion, optimize);
        } catch (BinaryScannerUtil.NoRecommendationException noRecommendation) {
            throw new GradleException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE3, noRecommendation.getConflicts()));
        } catch (BinaryScannerUtil.FeatureModifiedException featuresModified) {
            Set<String> userFeatures = (optimize) ? existingFeatures : 
                getServerFeatures(servUtil, generatedFiles, true); // user features excludes generatedFiles
            Set<String> modifiedSet = featuresModified.getFeatures(); // a set that works after being modified by the scanner

            if (modifiedSet.containsAll(userFeatures)) {
                // none of the user features were modified, only features which were generated earlier.
                logger.debug("FeatureModifiedException, modifiedSet containsAll userFeatures, pass modifiedSet on to generateFeatures");
                scannedFeatureList = modifiedSet;
            } else {
                Set<String> allAppFeatures = featuresModified.getSuggestions(); // suggestions are scanned from binaries
                allAppFeatures.addAll(userFeatures); // scanned plus configured features were detected to be in conflict
                logger.debug("FeatureModifiedException, combine suggestions from scanner with user features in error msg");
                throw new GradleException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE1, allAppFeatures, modifiedSet));
            }
        } catch (BinaryScannerUtil.RecommendationSetException showRecommendation) {
            if (showRecommendation.isExistingFeaturesConflict()) {
                throw new GradleException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE2, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
            }
            throw new GradleException(String.format(BinaryScannerUtil.BINARY_SCANNER_CONFLICT_MESSAGE1, showRecommendation.getConflicts(), showRecommendation.getSuggestions()));
        } catch (PluginExecutionException x) {
            // throw an error when there is a problem not caught in runBinaryScanner()
            Object o = x.getCause();
            if (o != null) {
                logger.debug("Caused by exception:" + x.getCause().getClass().getName());
                logger.debug("Caused by exception message:" + x.getCause().getMessage());
            }
            throw new GradleException("Failed to generate a working set of features. " + x.getMessage(), x);
        }

        def missingLibertyFeatures = new HashSet<String>();
        if (scannedFeatureList != null) {
            missingLibertyFeatures.addAll(scannedFeatureList);

            servUtil.setLowerCaseFeatures(false);
            // get set of user defined features so they can be omitted from the generated file that will be written
            Set<String> userDefinedFeatures = optimize ? existingFeatures : servUtil.getServerFeatures(server.configDirectory, server.serverXmlFile, new HashMap<String, File>(), generatedFiles);
            logger.debug("User defined features:" + userDefinedFeatures);
            servUtil.setLowerCaseFeatures(true);
            if (userDefinedFeatures != null) {
                missingLibertyFeatures.removeAll(userDefinedFeatures);
            }
        }
        logger.debug("Features detected by binary scanner which are not in server.xml : " + missingLibertyFeatures);

        def newServerXmlSrc = new File(server.configDirectory, GENERATED_FEATURES_FILE_PATH);
        try {
            if (missingLibertyFeatures.size() > 0) {
                // Create specialized server.xml
                ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                configDocument.createComment(HEADER);
                Element featureManagerElem = configDocument.createFeatureManager();
                configDocument.createComment(featureManagerElem, GENERATED_FEATURES_COMMENT);
                for (String missing : missingLibertyFeatures) {
                    logger.debug(String.format("Adding missing feature %s to %s.", missing, GENERATED_FEATURES_FILE_PATH));
                    configDocument.createFeature(missing);
                }
                configDocument.writeXMLDocument(newServerXmlSrc);
                logger.debug("Created file " + newServerXmlSrc);
                // Add a reference to this new file in existing server.xml.
                def serverXml = findConfigFile("server.xml", server.serverXmlFile);
                def doc = getServerXmlDocFromConfig(serverXml);
                logger.debug("Xml document we'll try to update after generate features doc=" + doc + " file=" + serverXml);
                addGenerationCommentToConfig(doc, serverXml);

                logger.lifecycle("Generated the following features: " + missingLibertyFeatures);
                // use logger.lifecycle so that message appears without --info tag on
            } else {
                logger.lifecycle("No additional features were generated.");
                if (newServerXmlSrc.exists()) {
                    // generated-features.xml exists but no additional features were generated
                    // create empty features list with comment
                    ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                    configDocument.createComment(HEADER);
                    Element featureManagerElem = configDocument.createFeatureManager();
                    configDocument.createComment(featureManagerElem, NO_NEW_FEATURES_COMMENT);
                    configDocument.writeXMLDocument(newServerXmlSrc);
                }
            }
        } catch (ParserConfigurationException | TransformerException | IOException e) {
            logger.debug("Exception creating the server features file", e);
            throw new GradleException("Automatic generation of features failed. Error attempting to create the " + GENERATED_FEATURES_FILE_NAME + ". Ensure your id has write permission to the server installation directory.", e);
        }
    }

    // Get the features from the server config and optionally exclude the specified config files from the search.
    private Set<String> getServerFeatures(ServerFeatureUtil servUtil, Set<String> generatedFiles, boolean excludeGenerated) {
        servUtil.setLowerCaseFeatures(false);
        // if optimizing, ignore generated files when passing in existing features to binary scanner
        Set<String> existingFeatures = servUtil.getServerFeatures(server.configDirectory, server.serverXmlFile, new HashMap<String, File>(), excludeGenerated ? generatedFiles : null); // pass generatedFiles to exclude them
        if (existingFeatures == null) {
            existingFeatures = new HashSet<String>();
        }
        servUtil.setLowerCaseFeatures(true);
        return existingFeatures;
    }
    /**
     * Gets the binary scanner jar file from the local cache.
     * Downloads it first from connected repositories such as Maven Central if a newer release is available than the cached version.
     * Note: Maven updates artifacts daily by default based on the last updated timestamp. Users should use 'mvn -U' to force updates if needed.
     *
     * @return The File object of the binary-app-scanner.jar in the local cache.
     * @throws PluginExecutionException indicates the binary-app-scanner.jar could not be found
     */
    private File getBinaryScannerJarFromRepository() throws PluginExecutionException {
        try {
            return ArtifactDownloadUtil.downloadBuildArtifact(project, BINARY_SCANNER_MAVEN_GROUP_ID, BINARY_SCANNER_MAVEN_ARTIFACT_ID, BINARY_SCANNER_MAVEN_TYPE, BINARY_SCANNER_MAVEN_VERSION);
        } catch (Exception e) {
            throw new PluginExecutionException("Could not retrieve the artifact " + BINARY_SCANNER_MAVEN_GROUP_ID + "."
                    + BINARY_SCANNER_MAVEN_ARTIFACT_ID
                    + " needed for generateFeatures. Ensure you have a connection to Maven Central or another repository that contains the "
                    + BINARY_SCANNER_MAVEN_GROUP_ID + "." + BINARY_SCANNER_MAVEN_ARTIFACT_ID
                    + ".jar configured in your build.gradle.",
                    e);
        }
    }

    /**
     * Return specificFile if it exists; otherwise check for a file with the requested name in the
     * configDirectory and return it if it exists. Null is returned if a file does not exist in 
     * either location.
     */
    private File findConfigFile(String fileName, File specificFile) {
        if (specificFile != null && specificFile.exists()) {
            return specificFile;
        }

        if (server.configDirectory == null) {
            return null;
        }
        File f = new File(server.configDirectory, fileName);
        if (f.exists()) {
            return f;
        } else {
            return null;
        }
    }

    // Convert a file into a document object
    private ServerConfigXmlDocument getServerXmlDocFromConfig(File serverXml) {
        if (serverXml == null || !serverXml.exists()) {
            return null;
        }
        try {
            return ServerConfigXmlDocument.newInstance(serverXml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            logger.debug("Exception creating server.xml object model", e);
        }
        return null;
    }

    /**
     * Add a comment to server.xml to warn them we created another file with features in it.
     */
    private void addGenerationCommentToConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            if (doc.createFMComment(FEATURES_FILE_MESSAGE)) {
                doc.writeXMLDocument(serverXml);
                XmlDocument.addNewlineBeforeFirstElement(serverXml);
            }
        } catch (IOException | TransformerException e) {
            log.debug("Exception adding comment to server.xml", e);
        }
        return;
    }

    private Set<String> getClassesDirectories() {
        Set<String> classesDirectories = new ArrayList<String>();
        project.sourceSets.main.getOutput().getClassesDirs().each {
            if (it.exists()) {
                classesDirectories.add(it.getAbsolutePath());
            }
        }
        return classesDirectories;
    }

    private getEEVersion(Object project) {
        String eeVersion = null
        project.configurations.compileClasspath.allDependencies.each {
            dependency ->
                if (dependency.group.equals("javax") && dependency.name.equals("javaee-api")) {
                    if (dependency.version.startsWith("8.")) {
                        eeVersion = BINARY_SCANNER_EEV8
                    } else if (dependency.version.startsWith("7.")) {
                        eeVersion = BINARY_SCANNER_EEV7
                    } else if (dependency.version.startsWith("6.")) {
                        eeVersion = BINARY_SCANNER_EEV6
                    }
                } else if (dependency.group.equals("jakarta.platform") &&
                        dependency.name.equals("jakarta.jakartaee-api") &&
                        dependency.version.startsWith("8.")) {
                    eeVersion = BINARY_SCANNER_EEV8
                }
        }
        return eeVersion;
    }

    private getMPVersion(Object project) {
        String mpVersion = null
        project.configurations.compileClasspath.allDependencies.each {
            if (it.group.equals("org.eclipse.microprofile") &&
                    it.name.equals("microprofile")) {
                if (it.version.startsWith("1")) {
                    mpVersion = BINARY_SCANNER_MPV1
                } else if (it.version.startsWith("2")) {
                    mpVersion = BINARY_SCANNER_MPV2
                } else if (it.version.startsWith("3")) {
                    mpVersion = BINARY_SCANNER_MPV3
                } else if (it.version.startsWith("4")) {
                    mpVersion = BINARY_SCANNER_MPV4
                }
            }
        }
        return mpVersion;
    }

    // Define the logging functions of the binary scanner handler and make it available in this plugin
    private class BinaryScannerHandler extends BinaryScannerUtil {
        BinaryScannerHandler(File scannerFile) {
            super(scannerFile);
        }

        @Override
        public void debug(String msg) {
            logger.debug(msg);
        }

        @Override
        public void debug(String msg, Throwable t) {
            logger.debug(msg, t);
        }

        @Override
        public void error(String msg) {
            logger.error(msg);
        }

        @Override
        public void warn(String msg) {
            logger.warn(msg);
        }

        @Override
        public void info(String msg) {
            logger.lifecycle(msg);
        }
    }
}
