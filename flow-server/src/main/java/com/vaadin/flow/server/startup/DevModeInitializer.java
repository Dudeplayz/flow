/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.server.startup;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.servlet.annotation.WebListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.WebComponentExporter;
import com.vaadin.flow.component.WebComponentExporterFactory;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.router.HasErrorParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.Constants;
import com.vaadin.flow.server.DevModeHandler;
import com.vaadin.flow.server.ExecutionFailedException;
import com.vaadin.flow.server.InitParameters;
import com.vaadin.flow.server.PWA;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.frontend.EndpointGeneratorTaskFactory;
import com.vaadin.flow.server.frontend.FallbackChunk;
import com.vaadin.flow.server.frontend.FrontendUtils;
import com.vaadin.flow.server.frontend.NodeTasks;
import com.vaadin.flow.server.frontend.NodeTasks.Builder;
import com.vaadin.flow.server.frontend.scanner.ClassFinder;
import com.vaadin.flow.server.frontend.scanner.ClassFinder.DefaultClassFinder;
import com.vaadin.flow.theme.NoTheme;
import com.vaadin.flow.theme.Theme;

import elemental.json.Json;
import elemental.json.JsonObject;
import static com.vaadin.flow.server.Constants.CONNECT_APPLICATION_PROPERTIES_TOKEN;
import static com.vaadin.flow.server.Constants.CONNECT_JAVA_SOURCE_FOLDER_TOKEN;
import static com.vaadin.flow.server.Constants.CONNECT_OPEN_API_FILE_TOKEN;
import static com.vaadin.flow.server.Constants.PACKAGE_JSON;
import static com.vaadin.flow.server.Constants.PROJECT_FRONTEND_GENERATED_DIR_TOKEN;
import static com.vaadin.flow.server.Constants.VAADIN_SERVLET_RESOURCES;
import static com.vaadin.flow.server.Constants.VAADIN_WEBAPP_RESOURCES;
import static com.vaadin.flow.server.InitParameters.SERVLET_PARAMETER_DEVMODE_OPTIMIZE_BUNDLE;
import static com.vaadin.flow.server.frontend.FrontendUtils.DEFAULT_CONNECT_APPLICATION_PROPERTIES;
import static com.vaadin.flow.server.frontend.FrontendUtils.DEFAULT_CONNECT_JAVA_SOURCE_FOLDER;
import static com.vaadin.flow.server.frontend.FrontendUtils.DEFAULT_CONNECT_OPENAPI_JSON_FILE;
import static com.vaadin.flow.server.frontend.FrontendUtils.DEFAULT_FLOW_RESOURCES_FOLDER;
import static com.vaadin.flow.server.frontend.FrontendUtils.DEFAULT_FRONTEND_DIR;
import static com.vaadin.flow.server.frontend.FrontendUtils.DEFAULT_GENERATED_DIR;
import static com.vaadin.flow.server.frontend.FrontendUtils.DEFAULT_PROJECT_FRONTEND_GENERATED_DIR;
import static com.vaadin.flow.server.frontend.FrontendUtils.PARAM_FRONTEND_DIR;
import static com.vaadin.flow.server.frontend.FrontendUtils.PARAM_GENERATED_DIR;
import static com.vaadin.flow.server.frontend.FrontendUtils.TARGET;

/**
 * Servlet initializer starting node updaters as well as the webpack-dev-mode
 * server.
 *
 * @since 2.0
 */
@HandlesTypes({ Route.class, UIInitListener.class,
        VaadinServiceInitListener.class, WebComponentExporter.class,
        WebComponentExporterFactory.class, NpmPackage.class,
        NpmPackage.Container.class, JsModule.class, JsModule.Container.class,
        CssImport.class, CssImport.Container.class, JavaScript.class,
        JavaScript.Container.class, Theme.class, NoTheme.class,
        HasErrorParameter.class, PWA.class, AppShellConfigurator.class })
@WebListener
public class DevModeInitializer
        implements VaadinServletContextStartupInitializer, Serializable,
        ServletContextListener {

    static class DevModeClassFinder extends DefaultClassFinder {

        private static final Set<String> APPLICABLE_CLASS_NAMES = Collections
                .unmodifiableSet(calculateApplicableClassNames());

        public DevModeClassFinder(Set<Class<?>> classes) {
            super(classes);
        }

        @Override
        public Set<Class<?>> getAnnotatedClasses(
                Class<? extends Annotation> annotation) {
            ensureImplementation(annotation);
            return super.getAnnotatedClasses(annotation);
        }

        @Override
        public <T> Set<Class<? extends T>> getSubTypesOf(Class<T> type) {
            ensureImplementation(type);
            return super.getSubTypesOf(type);
        }

        private void ensureImplementation(Class<?> clazz) {
            if (!APPLICABLE_CLASS_NAMES.contains(clazz.getName())) {
                throw new IllegalArgumentException("Unexpected class name "
                        + clazz + ". Implementation error: the class finder "
                        + "instance is not aware of this class. "
                        + "Fix @HandlesTypes annotation value for "
                        + DevModeInitializer.class.getName());
            }
        }

        private static Set<String> calculateApplicableClassNames() {
            HandlesTypes handlesTypes = DevModeInitializer.class
                    .getAnnotation(HandlesTypes.class);
            return Stream.of(handlesTypes.value()).map(Class::getName)
                    .collect(Collectors.toSet());
        }
    }

    private static final Pattern JAR_FILE_REGEX = Pattern
            .compile(".*file:(.+\\.jar).*");

    // Path of jar files in a URL with zip protocol doesn't start with "zip:"
    // nor "file:". It contains only the path of the file.
    // Weblogic uses zip protocol.
    private static final Pattern ZIP_PROTOCOL_JAR_FILE_REGEX = Pattern
            .compile("(.+\\.jar).*");

    private static final Pattern VFS_FILE_REGEX = Pattern
            .compile("(vfs:/.+\\.jar).*");

    private static final Pattern VFS_DIRECTORY_REGEX = Pattern
            .compile("vfs:/.+");

    // allow trailing slash
    private static final Pattern DIR_REGEX_FRONTEND_DEFAULT = Pattern.compile(
            "^(?:file:0)?(.+)" + Constants.RESOURCES_FRONTEND_DEFAULT + "/?$");

    // allow trailing slash
    private static final Pattern DIR_REGEX_RESOURCES_JAR_DEFAULT = Pattern
            .compile("^(?:file:0)?(.+)" + Constants.RESOURCES_THEME_JAR_DEFAULT
                    + "/?$");

    // allow trailing slash
    private static final Pattern DIR_REGEX_COMPATIBILITY_FRONTEND_DEFAULT = Pattern
            .compile("^(?:file:)?(.+)"
                    + Constants.COMPATIBILITY_RESOURCES_FRONTEND_DEFAULT
                    + "/?$");

    // Attribute key for storing Dev Mode Handler startup flag.
    // If presented in Servlet Context, shows the Dev Mode Handler already
    // started / become starting.
    // This attribute helps to avoid Dev Mode running twice.
    //
    // Addresses the issue https://github.com/vaadin/spring/issues/502
    private static final class DevModeHandlerAlreadyStartedAttribute
            implements Serializable {
    }

    @Override
    public void initialize(Set<Class<?>> classes, VaadinContext context)
            throws VaadinInitializerException {
        initDevModeHandler(classes, context);

        setDevModeStarted(context);
    }

    private void setDevModeStarted(VaadinContext context) {
        context.setAttribute(DevModeHandlerAlreadyStartedAttribute.class,
                new DevModeHandlerAlreadyStartedAttribute());
    }

    /**
     * Initialize the devmode server if not in production mode or compatibility
     * mode.
     *
     * @deprecated Use {@link #initDevModeHandler(Set, VaadinContext)} instead
     *             by wrapping {@link ServletContext} with
     *             {@link VaadinServletContext}.
     *
     * @param classes
     *            classes to check for npm- and js modules
     * @param context
     *            servlet context we are running in
     *
     * @throws ServletException
     *             if dev mode can't be initialized
     */
    @Deprecated
    public static void initDevModeHandler(Set<Class<?>> classes,
            ServletContext context) throws ServletException {
        try {
            initDevModeHandler(classes, new VaadinServletContext(context));
        } catch (VaadinInitializerException e) {
            throw new ServletException(e);
        }
    }

    /**
     * Initialize the devmode server if not in production mode or compatibility
     * mode.
     *
     * @param classes
     *            classes to check for npm- and js modules
     * @param context
     *            VaadinContext we are running in
     *
     * @throws VaadinInitializerException
     *             if dev mode can't be initialized
     */
    public static void initDevModeHandler(Set<Class<?>> classes,
            VaadinContext context) throws VaadinInitializerException {

        ApplicationConfiguration config = ApplicationConfiguration.get(context);
        if (config.isProductionMode()) {
            log().debug("Skipping DEV MODE because PRODUCTION MODE is set.");
            return;
        }
        if (!config.enableDevServer()) {
            log().debug(
                    "Skipping DEV MODE because dev server shouldn't be enabled.");
            return;
        }

        String baseDir = config.getStringProperty(FrontendUtils.PROJECT_BASEDIR,
                null);
        if (baseDir == null) {
            baseDir = getBaseDirectoryFallback();
        }

        String generatedDir = System.getProperty(PARAM_GENERATED_DIR,
                DEFAULT_GENERATED_DIR);
        String frontendFolder = config.getStringProperty(PARAM_FRONTEND_DIR,
                System.getProperty(PARAM_FRONTEND_DIR, DEFAULT_FRONTEND_DIR));

        File flowResourcesFolder = new File(baseDir,
                DEFAULT_FLOW_RESOURCES_FOLDER);

        Lookup lookupFromContext = context.getAttribute(Lookup.class);
        Lookup lookupForClassFinder = Lookup.of(new DevModeClassFinder(classes),
                ClassFinder.class);
        Lookup lookup = Lookup.compose(lookupForClassFinder, lookupFromContext);
        Builder builder = new NodeTasks.Builder(lookup, new File(baseDir),
                new File(generatedDir), new File(frontendFolder));

        log().info("Starting dev-mode updaters in {} folder.",
                builder.npmFolder);

        if (!builder.generatedFolder.exists()) {
            try {
                FileUtils.forceMkdir(builder.generatedFolder);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        String.format("Failed to create directory '%s'",
                                builder.generatedFolder),
                        e);
            }
        }

        File generatedPackages = new File(builder.generatedFolder,
                PACKAGE_JSON);

        // Regenerate webpack configuration, as it may be necessary to update it
        // TODO: make sure target directories are aligned with build config,
        // see https://github.com/vaadin/flow/issues/9082
        File target = new File(baseDir, TARGET);
        builder.withWebpack(new File(target, VAADIN_WEBAPP_RESOURCES),
                new File(target, VAADIN_SERVLET_RESOURCES),
                FrontendUtils.WEBPACK_CONFIG, FrontendUtils.WEBPACK_GENERATED);

        builder.useV14Bootstrap(config.useV14Bootstrap());

        if (!config.useV14Bootstrap() && isEndpointServiceAvailable(lookup)) {
            String connectJavaSourceFolder = config.getStringProperty(
                    CONNECT_JAVA_SOURCE_FOLDER_TOKEN,
                    Paths.get(baseDir, DEFAULT_CONNECT_JAVA_SOURCE_FOLDER)
                            .toString());
            String connectApplicationProperties = config.getStringProperty(
                    CONNECT_APPLICATION_PROPERTIES_TOKEN,
                    Paths.get(baseDir, DEFAULT_CONNECT_APPLICATION_PROPERTIES)
                            .toString());
            String connectOpenApiJsonFile = config.getStringProperty(
                    CONNECT_OPEN_API_FILE_TOKEN,
                    Paths.get(baseDir, DEFAULT_CONNECT_OPENAPI_JSON_FILE)
                            .toString());

            builder.withConnectJavaSourceFolder(
                    new File(connectJavaSourceFolder))
                    .withConnectApplicationProperties(
                            new File(connectApplicationProperties))
                    .withConnectGeneratedOpenApiJson(
                            new File(connectOpenApiJsonFile));
        }

        // If we are missing either the base or generated package json files
        // generate those
        if (!new File(builder.npmFolder, PACKAGE_JSON).exists()
                || !generatedPackages.exists()) {
            builder.createMissingPackageJson(true);
        }

        Set<File> frontendLocations = getFrontendLocationsFromClassloader(
                DevModeInitializer.class.getClassLoader());

        boolean useByteCodeScanner = config.getBooleanProperty(
                SERVLET_PARAMETER_DEVMODE_OPTIMIZE_BUNDLE,
                Boolean.parseBoolean(System.getProperty(
                        SERVLET_PARAMETER_DEVMODE_OPTIMIZE_BUNDLE,
                        Boolean.FALSE.toString())));

        boolean enablePnpm = config.isPnpmEnabled();

        boolean useHomeNodeExec = config.getBooleanProperty(
                InitParameters.REQUIRE_HOME_NODE_EXECUTABLE, false);

        String connectTsFolder = config.getStringProperty(
                PROJECT_FRONTEND_GENERATED_DIR_TOKEN,
                Paths.get(baseDir, DEFAULT_PROJECT_FRONTEND_GENERATED_DIR)
                        .toString());

        JsonObject tokenFileData = Json.createObject();
        NodeTasks tasks = builder.enablePackagesUpdate(true)
                .useByteCodeScanner(useByteCodeScanner)
                .withFlowResourcesFolder(flowResourcesFolder)
                .withConnectClientTsApiFolder(new File(connectTsFolder))
                .copyResources(frontendLocations)
                .copyLocalResources(new File(baseDir,
                        Constants.LOCAL_FRONTEND_RESOURCES_PATH))
                .enableImportsUpdate(true).runNpmInstall(true)
                .populateTokenFileData(tokenFileData)
                .withEmbeddableWebComponents(true).enablePnpm(enablePnpm)
                .withHomeNodeExecRequired(useHomeNodeExec).build();

        Runnable runnable = () -> runNodeTasks(context, tokenFileData, tasks);

        CompletableFuture<Void> nodeTasksFuture = CompletableFuture
                .runAsync(runnable);

        DevModeHandler.start(
                Lookup.compose(lookup,
                        Lookup.of(config, ApplicationConfiguration.class)),
                builder.npmFolder, nodeTasksFuture);
    }

    private static boolean isEndpointServiceAvailable(Lookup lookup) {
        if (lookup == null) {
            return false;
        }
        return lookup.lookup(EndpointGeneratorTaskFactory.class) != null;
    }

    /**
     * Shows whether {@link DevModeHandler} has been already started or not.
     *
     * @deprecated Use {@link #isDevModeAlreadyStarted(VaadinContext)} instead
     *             by wrapping {@link ServletContext} with
     *             {@link VaadinServletContext}.
     *
     * @param servletContext
     *            The servlet context, not <code>null</code>
     * @return <code>true</code> if {@link DevModeHandler} has already been
     *         started, <code>false</code> - otherwise
     */
    @Deprecated
    public static boolean isDevModeAlreadyStarted(
            ServletContext servletContext) {
        return isDevModeAlreadyStarted(
                new VaadinServletContext(servletContext));
    }

    /**
     * Shows whether {@link DevModeHandler} has been already started or not.
     *
     * @param context
     *            The {@link VaadinContext}, not <code>null</code>
     * @return <code>true</code> if {@link DevModeHandler} has already been
     *         started, <code>false</code> - otherwise
     */
    public static boolean isDevModeAlreadyStarted(VaadinContext context) {
        assert context != null;
        return context.getAttribute(
                DevModeHandlerAlreadyStartedAttribute.class) != null;
    }

    private static Logger log() {
        return LoggerFactory.getLogger(DevModeInitializer.class);
    }

    @Override
    public void contextInitialized(ServletContextEvent ctx) {
        // No need to do anything on init
    }

    @Override
    public void contextDestroyed(ServletContextEvent ctx) {
        DevModeHandler handler = DevModeHandler.getDevModeHandler();
        if (handler != null && !handler.reuseDevServer()) {
            handler.stop();
        }
    }

    /*
     * Accept user.dir or cwd as a fallback only if the directory seems to be a
     * Maven or Gradle project. Check to avoid cluttering server directories
     * (see tickets #8249, #8403).
     */
    private static String getBaseDirectoryFallback() {
        String baseDirCandidate = System.getProperty("user.dir", ".");
        Path path = Paths.get(baseDirCandidate);
        if (path.toFile().isDirectory()
                && (path.resolve("pom.xml").toFile().exists()
                        || path.resolve("build.gradle").toFile().exists())) {
            return path.toString();
        } else {
            throw new IllegalStateException(String.format(
                    "Failed to determine project directory for dev mode. "
                            + "Directory '%s' does not look like a Maven or "
                            + "Gradle project. Ensure that you have run the "
                            + "prepare-frontend Maven goal, which generates "
                            + "'flow-build-info.json', prior to deploying your "
                            + "application",
                    path.toString()));
        }
    }

    /*
     * This method returns all folders of jar files having files in the
     * META-INF/resources/frontend and META-INF/resources/themes folder. We
     * don't use URLClassLoader because will fail in Java 9+
     */
    static Set<File> getFrontendLocationsFromClassloader(
            ClassLoader classLoader) throws VaadinInitializerException {
        Set<File> frontendFiles = new HashSet<>();
        frontendFiles.addAll(getFrontendLocationsFromClassloader(classLoader,
                Constants.RESOURCES_FRONTEND_DEFAULT));
        frontendFiles.addAll(getFrontendLocationsFromClassloader(classLoader,
                Constants.COMPATIBILITY_RESOURCES_FRONTEND_DEFAULT));
        frontendFiles.addAll(getFrontendLocationsFromClassloader(classLoader,
                Constants.RESOURCES_THEME_JAR_DEFAULT));
        return frontendFiles;
    }

    private static void runNodeTasks(VaadinContext vaadinContext,
            JsonObject tokenFileData, NodeTasks tasks) {
        try {
            tasks.execute();

            FallbackChunk chunk = FrontendUtils
                    .readFallbackChunk(tokenFileData);
            if (chunk != null) {
                vaadinContext.setAttribute(chunk);
            }
        } catch (ExecutionFailedException exception) {
            log().debug(
                    "Could not initialize dev mode handler. One of the node tasks failed",
                    exception);
            throw new CompletionException(exception);
        }
    }

    private static Set<File> getFrontendLocationsFromClassloader(
            ClassLoader classLoader, String resourcesFolder)
            throws VaadinInitializerException {
        Set<File> frontendFiles = new HashSet<>();
        try {
            Enumeration<URL> en = classLoader.getResources(resourcesFolder);
            if (en == null) {
                return frontendFiles;
            }
            Set<String> vfsJars = new HashSet<>();
            while (en.hasMoreElements()) {
                URL url = en.nextElement();
                String urlString = url.toString();

                String path = URLDecoder.decode(url.getPath(),
                        StandardCharsets.UTF_8.name());
                Matcher jarMatcher = JAR_FILE_REGEX.matcher(path);
                Matcher zipProtocolJarMatcher = ZIP_PROTOCOL_JAR_FILE_REGEX
                        .matcher(path);
                Matcher dirMatcher = DIR_REGEX_FRONTEND_DEFAULT.matcher(path);
                Matcher dirResourcesMatcher = DIR_REGEX_RESOURCES_JAR_DEFAULT
                        .matcher(path);
                Matcher dirCompatibilityMatcher = DIR_REGEX_COMPATIBILITY_FRONTEND_DEFAULT
                        .matcher(path);
                Matcher jarVfsMatcher = VFS_FILE_REGEX.matcher(urlString);
                Matcher dirVfsMatcher = VFS_DIRECTORY_REGEX.matcher(urlString);
                if (jarVfsMatcher.find()) {
                    String vfsJar = jarVfsMatcher.group(1);
                    if (vfsJars.add(vfsJar))
                        frontendFiles.add(
                                getPhysicalFileOfJBossVfsJar(new URL(vfsJar)));
                } else if (dirVfsMatcher.find()) {
                    URL vfsDirUrl = new URL(urlString.substring(0,
                            urlString.lastIndexOf(resourcesFolder)));
                    frontendFiles
                            .add(getPhysicalFileOfJBossVfsDirectory(vfsDirUrl));
                } else if (jarMatcher.find()) {
                    frontendFiles.add(new File(jarMatcher.group(1)));
                } else if ("zip".equalsIgnoreCase(url.getProtocol())
                        && zipProtocolJarMatcher.find()) {
                    frontendFiles.add(new File(zipProtocolJarMatcher.group(1)));
                } else if (dirMatcher.find()) {
                    frontendFiles.add(new File(dirMatcher.group(1)));
                } else if (dirResourcesMatcher.find()) {
                    frontendFiles.add(new File(dirResourcesMatcher.group(1)));
                } else if (dirCompatibilityMatcher.find()) {
                    frontendFiles
                            .add(new File(dirCompatibilityMatcher.group(1)));
                } else {
                    log().warn(
                            "Resource {} not visited because does not meet supported formats.",
                            url.getPath());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return frontendFiles;
    }

    private static File getPhysicalFileOfJBossVfsDirectory(URL url)
            throws IOException, VaadinInitializerException {
        try {
            Object virtualFile = url.openConnection().getContent();
            Class virtualFileClass = virtualFile.getClass();

            // Reflection as we cannot afford a dependency to WildFly or JBoss
            Method getChildrenRecursivelyMethod = virtualFileClass
                    .getMethod("getChildrenRecursively");
            Method getPhysicalFileMethod = virtualFileClass
                    .getMethod("getPhysicalFile");

            // By calling getPhysicalFile, we make sure that the corresponding
            // physical files/directories of the root directory and its children
            // are created. Later, these physical files are scanned to collect
            // their resources.
            List virtualFiles = (List) getChildrenRecursivelyMethod
                    .invoke(virtualFile);
            File rootDirectory = (File) getPhysicalFileMethod
                    .invoke(virtualFile);
            for (Object child : virtualFiles) {
                // side effect: create real-world files
                getPhysicalFileMethod.invoke(child);
            }
            return rootDirectory;
        } catch (NoSuchMethodException | IllegalAccessException
                | InvocationTargetException exc) {
            throw new VaadinInitializerException(
                    "Failed to invoke JBoss VFS API.", exc);
        }
    }

    private static File getPhysicalFileOfJBossVfsJar(URL url)
            throws IOException, VaadinInitializerException {
        try {
            Object jarVirtualFile = url.openConnection().getContent();

            // Creating a temporary jar file out of the vfs files
            String vfsJarPath = url.toString();
            String fileNamePrefix = vfsJarPath.substring(
                    vfsJarPath.lastIndexOf('/') + 1,
                    vfsJarPath.lastIndexOf(".jar"));
            Path tempJar = Files.createTempFile(fileNamePrefix, ".jar");

            generateJarFromJBossVfsFolder(jarVirtualFile, tempJar);

            File tempJarFile = tempJar.toFile();
            tempJarFile.deleteOnExit();
            return tempJarFile;
        } catch (NoSuchMethodException | IllegalAccessException
                | InvocationTargetException exc) {
            throw new VaadinInitializerException(
                    "Failed to invoke JBoss VFS API.", exc);
        }
    }

    private static void generateJarFromJBossVfsFolder(Object jarVirtualFile,
            Path tempJar) throws IOException, IllegalAccessException,
            InvocationTargetException, NoSuchMethodException {
        // We should use reflection to use JBoss VFS API as we cannot afford a
        // dependency to WildFly or JBoss
        Class virtualFileClass = jarVirtualFile.getClass();
        Method getChildrenRecursivelyMethod = virtualFileClass
                .getMethod("getChildrenRecursively");
        Method openStreamMethod = virtualFileClass.getMethod("openStream");
        Method isFileMethod = virtualFileClass.getMethod("isFile");
        Method getPathNameRelativeToMethod = virtualFileClass
                .getMethod("getPathNameRelativeTo", virtualFileClass);

        List jarVirtualChildren = (List) getChildrenRecursivelyMethod
                .invoke(jarVirtualFile);
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(
                Files.newOutputStream(tempJar))) {
            for (Object child : jarVirtualChildren) {
                if (!(Boolean) isFileMethod.invoke(child))
                    continue;

                String relativePath = (String) getPathNameRelativeToMethod
                        .invoke(child, jarVirtualFile);
                InputStream inputStream = (InputStream) openStreamMethod
                        .invoke(child);
                ZipEntry zipEntry = new ZipEntry(relativePath);
                zipOutputStream.putNextEntry(zipEntry);
                IOUtils.copy(inputStream, zipOutputStream);
                zipOutputStream.closeEntry();
            }
        }
    }
}
