/**
 * (C) Copyright IBM Corporation 2017, 2019.
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
package net.wasdev.wlp.gradle.plugins.tasks

import groovy.xml.StreamingMarkupBuilder
import net.wasdev.wlp.common.plugins.config.ApplicationXmlDocument
import net.wasdev.wlp.gradle.plugins.utils.ServerConfigDocument
import org.apache.commons.io.FileUtils
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.War
import org.gradle.plugins.ear.Ear

import java.nio.file.Files
import java.nio.file.StandardCopyOption

import org.apache.commons.io.FilenameUtils

import net.wasdev.wlp.ant.ServerTask

abstract class AbstractServerTask extends AbstractTask {

    private final String HEADER = "# Generated by liberty-gradle-plugin"

    def server
    def springBootBuildTask

    protected determineSpringBootBuildTask() {
        if (springBootVersion ?. startsWith('2')    ) {
            return project.bootJar
        }
        else if ( springBootVersion ?. startsWith('1') ) {
            return project.bootRepackage
        }
    }

    protected void executeServerCommand(Project project, String command, Map<String, String> params) {
        project.ant.taskdef(name: 'server',
                            classname: 'net.wasdev.wlp.ant.ServerTask',
                            classpath: project.buildscript.configurations.classpath.asPath)
        params.put('operation', command)
        project.ant.server(params)
    }

    protected Map<String, String> buildLibertyMap(Project project) {
        Map<String, String> result = new HashMap();
        result.put('serverName', server.name)

        def installDir = getInstallDir(project)
        result.put('installDir', installDir)

        def userDir = getUserDir(project, installDir)
        result.put('userDir', userDir)

        if (getServerOutputDir(project) != null) {
            result.put('outputDir', getServerOutputDir(project))
        }
        if (server.timeout != null && !server.timeout.isEmpty()) {
            result.put('timeout', server.timeout)
        }

        return result;
    }

    protected List<String> buildCommand (String operation) {
        List<String> command = new ArrayList<String>()
        String installDir = getInstallDir(project).toString()

        if (isWindows) {
            command.add(installDir + "\\bin\\server.bat")
        } else {
            command.add(installDir + "/bin/server")
        }
        command.add(operation)
        command.add(server.name)

        return command
    }

    protected File getServerDir(Project project){
        return new File(getUserDir(project).toString() + "/servers/" + server.name)
    }

    protected String getServerOutputDir(Project project) {
        if (server.outputDir != null) {
            return server.outputDir
        } else {
            return project.liberty.outputDir
        }
    }

    /**
     * @throws IOException
     * @throws FileNotFoundException
     */
    protected void copyConfigFiles() throws IOException {

        String serverDirectory = getServerDir(project).toString()
        String serverXMLPath = null
        String jvmOptionsPath = null
        String bootStrapPropertiesPath = null
        String serverEnvPath = null

        if (server.configDirectory == null) {
            server.configDirectory = new File(project.projectDir, "src/main/liberty/config")
        }

        if(server.configDirectory.exists()) {
            // copy configuration files from configuration directory to server directory if end-user set it
            FileUtils.copyDirectory(server.configDirectory, getServerDir(project))

            File configDirServerXML = new File(server.configDirectory, "server.xml")
            if (configDirServerXML.exists()) {
                serverXMLPath = configDirServerXML.getCanonicalPath()
            }

            File configDirJvmOptionsFile = new File(server.configDirectory, "jvm.options")
            if (configDirJvmOptionsFile.exists()) {
                jvmOptionsPath = configDirJvmOptionsFile.getCanonicalPath()
            }

            File configDirBootstrapFile = new File(server.configDirectory, "bootstrap.properties")
            if (configDirBootstrapFile.exists()) {
                bootStrapPropertiesPath = configDirBootstrapFile.getCanonicalPath()
            }

            File configDirServerEnv = new File(server.configDirectory, "server.env")
            if (configDirServerEnv.exists()) {
                serverEnvPath = configDirServerEnv.getCanonicalPath()
            }
        }

        // handle server.xml if not overwritten by server.xml from configDirectory
        if (serverXMLPath == null || serverXMLPath.isEmpty()) {
            // copy configuration file to server directory if end-user set it.
            if (server.configFile != null && server.configFile.exists()) {
                Files.copy(server.configFile.toPath(), new File(serverDirectory, "server.xml").toPath(), StandardCopyOption.REPLACE_EXISTING)
                serverXMLPath = server.configFile.getCanonicalPath()
            }
        }

        // handle jvm.options if not overwritten by jvm.options from configDirectory
        if (jvmOptionsPath == null || jvmOptionsPath.isEmpty()) {
            File optionsFile = new File(serverDirectory, "jvm.options")
            if(server.jvmOptions != null && !server.jvmOptions.isEmpty()){
                writeJvmOptions(optionsFile, server.jvmOptions)
                jvmOptionsPath = "inlined configuration"
            } else if (server.jvmOptionsFile != null && server.jvmOptionsFile.exists()) {
                Files.copy(server.jvmOptionsFile.toPath(), optionsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                jvmOptionsPath = server.jvmOptionsFile.getCanonicalPath()
            }
        }

        // handle bootstrap.properties if not overwritten by bootstrap.properties from configDirectory
        if (bootStrapPropertiesPath == null || bootStrapPropertiesPath.isEmpty()) {
            File bootstrapFile = new File(serverDirectory, "bootstrap.properties")
            if(server.bootstrapProperties != null && !server.bootstrapProperties.isEmpty()){
                writeBootstrapProperties(bootstrapFile, server.bootstrapProperties)
                bootStrapPropertiesPath = "inlined configuration"
            } else if (server.bootstrapPropertiesFile != null && server.bootstrapPropertiesFile.exists()) {
                Files.copy(server.bootstrapPropertiesFile.toPath(), bootstrapFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                bootStrapPropertiesPath = server.bootstrapPropertiesFile.getCanonicalPath()
            }
        }

        // handle server.env if not overwritten by server.env from configDirectory
        if (serverEnvPath == null || serverEnvPath.isEmpty()) {
            if (server.serverEnv != null && server.serverEnv.exists()) {
                Files.copy(server.serverEnv.toPath(), new File(serverDirectory, "server.env").toPath(), StandardCopyOption.REPLACE_EXISTING)
                serverEnvPath = server.serverEnv.getCanonicalPath()
            }
        }

        // log info on the configuration files that get used
        if (serverXMLPath != null && !serverXMLPath.isEmpty()) {
            logger.info("Update server configuration file server.xml from " + serverXMLPath)
        }
        if (jvmOptionsPath != null && !jvmOptionsPath.isEmpty()) {
            logger.info("Update server configuration file jvm.options from " + jvmOptionsPath)
        }
        if (bootStrapPropertiesPath != null && !bootStrapPropertiesPath.isEmpty()) {
            logger.info("Update server configuration file bootstrap.properties from " + bootStrapPropertiesPath)
        }
        if (serverEnvPath != null && !serverEnvPath.isEmpty()) {
            logger.info("Update server configuration file server.env from " + serverEnvPath)
        }
    }

    protected void setServerDirectoryNodes(Project project, Node serverNode) {
        serverNode.appendNode('userDirectory', getUserDir(project).toString())
        serverNode.appendNode('serverDirectory', getServerDir(project).toString())
        String serverOutputDir = getServerOutputDir(project)
        if (serverOutputDir != null && !serverOutputDir.isEmpty()) {
            serverNode.appendNode('serverOutputDirectory', serverOutputDir)
        } else {
            serverNode.appendNode('serverOutputDirectory', getServerDir(project).toString())
        }
    }

    protected void setServerPropertyNodes(Project project, Node serverNode) {
        serverNode.appendNode('serverName', server.name)
        if (server.configDirectory != null && server.configDirectory.exists()) {
            serverNode.appendNode('configDirectory', server.configDirectory.toString())
        }

        if (server.configFile != null && server.configFile.exists()) {
            serverNode.appendNode('configFile', server.configFile.toString())
        }

        if (server.bootstrapProperties != null && !server.bootstrapProperties.isEmpty()) {
            Node bootstrapProperties = new Node(null, 'bootstrapProperties')
            server.bootstrapProperties.each { k, v ->
                bootstrapProperties.appendNode(k, v.toString())
            }
            serverNode.append(bootstrapProperties)
        } else if (server.bootstrapPropertiesFile != null && server.bootstrapPropertiesFile.exists()) {
            serverNode.appendNode('bootstrapPropertiesFile', server.bootstrapPropertiesFile.toString())
        }

        if (server.jvmOptions != null && !server.jvmOptions.isEmpty()) {
            Node jvmOptions = new Node(null, 'jvmOptions')
            server.jvmOptions.each { v ->
                jvmOptions.appendNode('params', v.toString())
            }
            serverNode.append(jvmOptions)
        } else if (server.jvmOptionsFile != null && server.jvmOptionsFile.exists()) {
            serverNode.appendNode('jvmOptionsFile', server.jvmOptionsFile.toString())
        }

        if (server.serverEnv != null && server.serverEnv.exists()) {
            serverNode.appendNode('serverEnv', server.serverEnv.toString())
        }

        serverNode.appendNode('looseApplication', server.looseApplication)
        serverNode.appendNode('stripVersion', server.stripVersion)

        configureMultipleAppsConfigDropins(serverNode)
    }

    protected boolean isAppConfiguredInSourceServerXml(String fileName) {
        boolean configured = false;
        File serverConfigFile = new File(getServerDir(project), 'server.xml')
        if (serverConfigFile != null && serverConfigFile.exists()) {
            try {
                ServerConfigDocument scd = new ServerConfigDocument(serverConfigFile, server.configDirectory, server.bootstrapPropertiesFile, server.bootstrapProperties, server.serverEnv)
                if (scd != null && scd.getLocations().contains(fileName)) {
                    logger.debug("Application configuration is found in server.xml : " + fileName)
                    configured = true
                }
            }
            catch (Exception e) {
                logger.warn(e.getLocalizedMessage())
            }
        }
        return configured
    }
    
    protected String getArchiveName(Task task){
        if (springBootVersion?.startsWith('1')) {
            task = project.jar
        }
        if (server.stripVersion){
            return task.baseName + "." + task.extension
        }
        return task.archiveName;
    }

    protected void configureApps(Project project) {
        if ((server.apps == null || server.apps.isEmpty()) && (server.dropins == null || server.dropins.isEmpty())) {
            if (!project.configurations.libertyApp.isEmpty()) {
                server.apps = getApplicationFilesFromConfiguration().toArray()
            } else if (project.plugins.hasPlugin('war')) {
                server.apps = [project.war]
            } else if (project.plugins.hasPlugin('ear')) {
                server.apps = [project.ear]
            } else if (project.plugins.hasPlugin('org.springframework.boot')) {
                server.apps = [springBootBuildTask]
            }
        }
    }
    
    protected void configureMultipleAppsConfigDropins(Node serverNode) {
        if (server.apps != null && !server.apps.isEmpty()) {
            Tuple applications = splitAppList(server.apps)
            applications[0].each{ Task task ->
              isConfigDropinsRequired(task, 'apps', serverNode)
            }
        }
    }
    
    protected Tuple splitAppList(List<Object> allApps) {
        List<File> appFiles = new ArrayList<File>()
        List<Task> appTasks = new ArrayList<Task>()

        allApps.each { Object appObj ->
            if (appObj instanceof Task) {
                appTasks.add((Task)appObj)
            } else if (appObj instanceof File) {
                appFiles.add((File)appObj)
            } else {
                logger.warn('Application ' + appObj.getClass.name + ' is expressed as ' + appObj.toString() + ' which is not a supported input type. Define applications using Task or File objects.')
            }
        }

        return new Tuple(appTasks, appFiles)
    }
    
    private boolean isSupportedType(){
      switch (getPackagingType()) {
        case "ear":
        case "war":
            return true;
        default:
            return false;
        }
    }
    private String getLooseConfigFileName(Task task){
      return getArchiveName(task) + ".xml"
    }
    
    protected void isConfigDropinsRequired(Task task, String appsDir, Node serverNode) {
        File installAppsConfigDropinsFile = ApplicationXmlDocument.getApplicationXmlFile(getServerDir(project))
        if (isSupportedType()) {
          if (server.looseApplication){
            String looseConfigFileName = getLooseConfigFileName(task)
            String application = looseConfigFileName.substring(0, looseConfigFileName.length()-4)
            if (!isAppConfiguredInSourceServerXml(application)) {
                serverNode.appendNode('installAppsConfigDropins', installAppsConfigDropinsFile.toString())
            }
          } else {
                if (!isAppConfiguredInSourceServerXml(getArchiveName(task)) || hasConfiguredApp(ApplicationXmlDocument.getApplicationXmlFile(getServerDir(project)))) {
                    serverNode.appendNode('installAppsConfigDropins', installAppsConfigDropinsFile.toString())
                }
            }
        }
    }

    protected void createApplicationElements(Node applicationsNode, List<Objects> appList, String appDir) {
        springBootVersion=findSpringBootVersion(project)
        appList.each { Object appObj ->
            Node application = new Node(null, 'application')
            if (appObj instanceof Task) {
                if (springBootVersion?.startsWith('1')) {
                    appObj = project.jar
                }
                application.appendNode('appsDirectory', appDir)
                if (server.looseApplication) {
                    application.appendNode('applicationFilename', appObj.archiveName + '.xml')
                } else {
                    application.appendNode('applicationFilename', appObj.archiveName)
                }
                if (appObj instanceof War) {
                    application.appendNode('warSourceDirectory', project.webAppDirName)
                }
            } else if (appObj instanceof File) {
                application.appendNode('appsDirectory', appDir)
                if (server.looseApplication) {
                    application.appendNode('applicationFilename', appObj.name + '.xml')
                } else {
                    application.appendNode('applicationFilename', appObj.name)
                }
            }

            if(!application.children().isEmpty()) {
                if (project.plugins.hasPlugin("war")) {
                    application.appendNode('projectType', 'war')
                } else if (project.plugins.hasPlugin("ear")) {
                    application.appendNode('projectType', 'ear')
                }
                applicationsNode.append(application)
            }
        }
    }

    protected void setApplicationPropertyNodes(Project project, Node serverNode) {
        Node applicationsNode;
        if ((server.apps == null || server.apps.isEmpty()) && (server.dropins == null || server.dropins.isEmpty())) {
            if (project.plugins.hasPlugin('war')) {
                applicationsNode = new Node(null, 'applications')
                createApplicationElements(applicationsNode, [project.tasks.war], 'apps')
                serverNode.append(applicationsNode)
            }
        } else {
            applicationsNode = new Node(null, 'applications')
            if (server.apps != null && !server.apps.isEmpty()) {
                createApplicationElements(applicationsNode, server.apps, 'apps')
            }
            if (server.dropins != null && !server.dropins.isEmpty()) {
                createApplicationElements(applicationsNode, server.dropins, 'dropins')
            }
            serverNode.append(applicationsNode)
        }
    }

    protected void setDependencyNodes(Project project, Node serverNode) {
        Project parent = project.getParent()
        if (parent != null) {
            serverNode.appendNode('aggregatorParentId', parent.getName())
            serverNode.appendNode('aggregatorParentBasedir', parent.getProjectDir())
        }

        if (project.configurations.findByName('compile') && !project.configurations.compile.dependencies.isEmpty()) {
            project.configurations.compile.dependencies.each { dependency ->
                serverNode.appendNode('projectCompileDependency', dependency.group + ':' + dependency.name + ':' + dependency.version)
            }
        }
    }

    protected void writeServerPropertiesToXml(Project project) {
        XmlParser pluginXmlParser = new XmlParser()
        Node libertyPluginConfig = pluginXmlParser.parse(new File(project.buildDir, 'liberty-plugin-config.xml'))
        if (libertyPluginConfig.getAt('servers').isEmpty()) {
            libertyPluginConfig.appendNode('servers')
        } else {
            //removes the server nodes from the servers element
            libertyPluginConfig.getAt('servers')[0].value = ""
        }
        Node serverNode = new Node(null, 'server')

        setServerDirectoryNodes(project, serverNode)
        setServerPropertyNodes(project, serverNode)
        setApplicationPropertyNodes(project, serverNode)
        setDependencyNodes(project, serverNode)

        libertyPluginConfig.getAt('servers')[0].append(serverNode)

        new File( project.buildDir, 'liberty-plugin-config.xml' ).withWriter('UTF-8') { output ->
            output << new StreamingMarkupBuilder().bind { mkp.xmlDeclaration(encoding: 'UTF-8', version: '1.0' ) }
            XmlNodePrinter printer = new XmlNodePrinter( new PrintWriter(output) )
            printer.preserveWhitespace = true
            printer.print( libertyPluginConfig )
        }

        logger.info ("Adding Liberty plugin config info to ${project.buildDir}/liberty-plugin-config.xml.")
    }

    private void writeBootstrapProperties(File file, Map<String, Object> properties) throws IOException {
        makeParentDirectory(file)
        PrintWriter writer = null
        try {
            writer = new PrintWriter(file, "UTF-8")
            writer.println(HEADER)
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                writer.print(entry.getKey())
                writer.print("=")
                writer.println((entry.getValue() != null) ? entry.getValue().toString().replace("\\", "/") : "")
            }
        } finally {
            if (writer != null) {
                writer.close()
            }
        }
    }

    private void writeJvmOptions(File file, List<String> options) throws IOException {
        makeParentDirectory(file)
        PrintWriter writer = null
        try {
            writer = new PrintWriter(file, "UTF-8")
            writer.println(HEADER)
            for (String option : options) {
                writer.println(option)
            }
        } finally {
            if (writer != null) {
                writer.close()
            }
        }
    }

    private void makeParentDirectory(File file) {
        File parentDir = file.getParentFile()
        if (parentDir != null) {
            parentDir.mkdirs()
        }
    }

    protected String getPackagingType() throws Exception{
      if (project.plugins.hasPlugin("war") || !project.tasks.withType(War).isEmpty()) {
          if (project.plugins.hasPlugin("org.springframework.boot")) {
              return "springboot"
          }
          return "war"
      }
      else if (project.plugins.hasPlugin("ear") || !project.tasks.withType(Ear).isEmpty()) {
          return "ear"
      }
      else if (project.plugins.hasPlugin("org.springframework.boot") ) {
          return "springboot"
      }
      else {
          throw new GradleException("Archive path not found. Supported formats are jar, war, ear, and springboot jar.")
      }
    }

    //Checks if there is an app configured in an existing configDropins application xml file
    protected boolean hasConfiguredApp(File applicationXmlFile) {
      if (applicationXmlFile.exists()) {
          ApplicationXmlDocument appXml = new ApplicationXmlDocument()
          appXml.createDocument(applicationXmlFile)
          return appXml.hasChildElements()
      }
      return false
    }

    protected List<File> getApplicationFilesFromConfiguration() {
        List<File> appFiles = new ArrayList<File>()

        //This loops all the Dependency objects that get created by the configuration treating them as File objects
        //Should also include transitive dependencies
        //Can't use the resolved configuration unless we do a check separate from this one, not sure if there is an advantage since we want the applicaitons
        project.configurations.libertyApp.each {
            if (FilenameUtils.getExtension(it.name).equals('war') || FilenameUtils.getExtension(it.name).equals('ear')) {
                appFiles.add(it)
            }
        }

        return appFiles
    }

    protected ServerTask createServerTask(Project project, String operation) throws Exception {
        ServerTask serverTask =  new ServerTask()
        serverTask.setOperation(operation)
        serverTask.setServerName(server.name)

        def installDir = getInstallDir(project)

        serverTask.setInstallDir(installDir)
        serverTask.setUserDir(getUserDir(project, installDir))

        def serverOutputDir = getServerOutputDir(project)
        if (serverOutputDir != null) {
            serverTask.setOutputDir(new File(serverOutputDir))
        }  

        if (server.timeout != null && !server.timeout.isEmpty()) {
            serverTask.setTimeout(server.timeout)
        }

        return serverTask
    }


}
