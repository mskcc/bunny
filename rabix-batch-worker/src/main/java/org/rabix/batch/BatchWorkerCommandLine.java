package org.rabix.batch;

import com.google.inject.*;
import org.apache.commons.cli.*;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;
import org.rabix.awsbatch.AmazonS3Service;
import org.rabix.awsbatch.mapper.S3ToLocalPathMapper;
import org.rabix.backend.api.BackendModule;
import org.rabix.backend.api.callback.WorkerStatusCallback;
import org.rabix.backend.api.callback.impl.NoOpWorkerStatusCallback;

import org.rabix.batch.status.BatchWorkerEngineStatusCallback;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.Bindings;
import org.rabix.bindings.BindingsFactory;
import org.rabix.bindings.ProtocolType;
import org.rabix.bindings.helper.FileValueHelper;
import org.rabix.bindings.helper.URIHelper;
import org.rabix.bindings.mapper.FileMappingException;
import org.rabix.bindings.model.*;
import org.rabix.common.config.ConfigModule;
import org.rabix.common.helper.JSONHelper;
import org.rabix.common.json.BeanSerializer;
import org.rabix.common.jvm.ClasspathScanner;
import org.rabix.common.logging.VerboseLogger;
import org.rabix.common.service.download.DownloadService;
import org.rabix.common.service.download.DownloadServiceException;
import org.rabix.common.service.upload.UploadService;
import org.rabix.common.service.upload.impl.NoOpUploadServiceImpl;
import org.rabix.engine.EngineModule;
import org.rabix.engine.service.*;
import org.rabix.engine.service.impl.*;
import org.rabix.engine.status.EngineStatusCallback;
import org.rabix.engine.stub.BackendStubFactory;
import org.rabix.engine.stub.impl.BackendStubFactoryImpl;
import org.rabix.transport.mechanism.TransportPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class BatchWorkerCommandLine {

    private static final Logger logger = LoggerFactory.getLogger(BatchWorkerCommandLine.class);
    private static String configDir = "/.bunny/config";

    @SuppressWarnings("unchecked")
    public static void main(String[] commandLineArguments) {
        final CommandLineParser commandLineParser = new DefaultParser();
        final Options posixOptions = createOptions();

        CommandLine commandLine;
        List<String> commandLineArray = Arrays.asList(commandLineArguments);

        try {
            commandLine = commandLineParser.parse(posixOptions, commandLineArguments);
            if (commandLine.hasOption("h")) {
                printUsageAndExit(posixOptions);
            }
            if (commandLine.hasOption("version")) {
                printVersionAndExit(posixOptions);
            }
            if (!checkCommandLine(commandLine)) {
                printUsageAndExit(posixOptions);
            }

            final String appPath = commandLine.getArgList().get(0);

//            Path filePath = null;
//            URI appUri = URI.create(app.replace(" ", "%20"));
//            if (appUri.getScheme() == null) {
//                appUri = new URI("file", Paths.get(".").toAbsolutePath().resolve(appUri.getSchemeSpecificPart()).normalize().toString(), appUri.getFragment());
//            }
//            filePath = Paths.get(appUri.getPath());
//            if (!Files.exists(filePath)) {
//                VerboseLogger.log(String.format("Application file %s does not exist.", appUri.toString()));
//                printUsageAndExit(posixOptions);
//            }

//
//            String fullUri = appUri.toString();
//            if (commandLine.hasOption("resolve-app")) {
//                printResolvedAppAndExit(fullUri);
//            }

            String inputsPath = null;
            Path inputsFile = null;
            if (commandLine.getArgList().size() > 1) {
                inputsPath = commandLine.getArgList().get(1);
            }

            String directoryName;
            if (commandLine.hasOption("directory-name")) {
                directoryName = commandLine.getOptionValue("directory-name");
            } else {
                directoryName = "working_dir";
            }

            File configDir = getConfigDir(commandLine, posixOptions);

            if (!configDir.exists() || !configDir.isDirectory()) {
                VerboseLogger.log(String.format("Config directory %s doesn't exist or is not a directory.", configDir.getCanonicalPath()));
                printUsageAndExit(posixOptions);
            }

            Map<String, Object> configOverrides = new HashMap<>();
            configOverrides.put("cleaner.backend.period", 5000L);

            //String directoryName = generateDirectoryName(app);
            configOverrides.put("backend.execution.directory.name", directoryName);

            final ConfigModule configModule = new ConfigModule(configDir, configOverrides);
            Configuration configuration = configModule.provideConfig();
            Injector injector = Guice.createInjector(
                    new EngineModule(configModule),
                    new AbstractModule() {
                        @Override
                        protected void configure() {
                            install(configModule);

                            if (configuration.getBoolean("engine.delete_intermediary_files", false)) {
                                bind(IntermediaryFilesHandler.class).to(IntermediaryFilesLocalHandler.class).in(Scopes.SINGLETON);
                            } else {
                                bind(IntermediaryFilesHandler.class).to(NoOpIntermediaryFilesServiceHandler.class).in(Scopes.SINGLETON);
                            }
                            bind(JobService.class).to(JobServiceImpl.class).in(Scopes.SINGLETON);
                            bind(BackendService.class).to(BackendServiceImpl.class).in(Scopes.SINGLETON);
                            bind(EngineStatusCallback.class).to(BatchWorkerEngineStatusCallback.class).in(Scopes.SINGLETON);
//                            bind(DownloadService.class).to(AmazonS3Service.class).in(Scopes.SINGLETON);
//                            bind(UploadService.class).to(AmazonS3Service.class).in(Scopes.SINGLETON);
                            bind(WorkerStatusCallback.class).to(NoOpWorkerStatusCallback.class).in(Scopes.SINGLETON);

                            bind(BackendStubFactory.class).to(BackendStubFactoryImpl.class).in(Scopes.SINGLETON);
                            bind(new TypeLiteral<TransportPlugin.ReceiveCallback<Job>>(){}).to(JobReceiverImpl.class).in(Scopes.SINGLETON);

                            Set<Class<BackendModule>> backendModuleClasses = ClasspathScanner.<BackendModule>scanSubclasses(BackendModule.class);
                            for (Class<BackendModule> backendModuleClass : backendModuleClasses) {
                                try {
                                    install(backendModuleClass.getConstructor(ConfigModule.class).newInstance(configModule));
                                } catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
                                    logger.error("Failed to instantiate BackendModule " + backendModuleClass, e);
                                    System.exit(33);
                                }
                            }
                            bind(BootstrapService.class).to(BootstrapServiceImpl.class).in(Scopes.SINGLETON);
                        }
                    });

            AmazonS3Service s3Service = injector.getInstance(AmazonS3Service.class);

            Map config = new HashMap<>();
            config.put("APP", true);
            try {
                String appName = appPath.split("/")[appPath.split("/").length - 1];
                s3Service.download(new File(directoryName), new DownloadService.DownloadResource(appPath, appPath, appName, false),
                        config);
            } catch (DownloadServiceException e2) {
                // TODO Auto-generated catch block
                e2.printStackTrace();
            }

            S3ToLocalPathMapper s3ToLocalPathMapper = injector.getInstance(S3ToLocalPathMapper.class);

            String s = null;
            try {
                s = s3ToLocalPathMapper.map(appPath, config);
            } catch (FileMappingException e2) {
                e2.printStackTrace();
            }

            String appUrl = URIHelper.createURI(URIHelper.FILE_URI_SCHEME, s);

            try {
                String inputName = inputsPath.split("/")[inputsPath.split("/").length - 1];
                s3Service.download(new File(inputsPath), new DownloadService.DownloadResource(inputsPath, inputsPath, inputName, false),
                        config);
            } catch (DownloadServiceException e3) {
                // TODO Auto-generated catch block
                VerboseLogger.log(String.format("Can't download input file: %s.", inputsPath));
            }

            try {
                inputsPath = s3ToLocalPathMapper.map(inputsPath, config);
            } catch (FileMappingException e2) {
                VerboseLogger.log(String.format("Inputs file %s does not exist.", inputsPath));
            }

            // injector.getInstance(GarbageCollectionService.class).disable();

            // Load app from JSON
            Bindings bindings = null;
            Application application = null;

            try {
                bindings = BindingsFactory.create(appUrl);
                application = bindings.loadAppObject(appUrl);
            } catch (NotImplementedException e) {
                logger.error("Not implemented feature");
                System.exit(33);
            } catch (BindingException e) {
                logger.error("Error: " + appUrl + " is not a valid app! {}", e.getMessage());
                System.exit(10);
            }
            if (application == null) {
                VerboseLogger.log("Error reading the app file");
                System.exit(10);
            }
            if (application.getRaw().containsKey("$schemas")) {
                LoggerFactory.getLogger(BatchWorkerCommandLine.class).error("Unsupported feature: $schemas.");
            }
            Options appInputOptions = new Options();

            // Create appInputOptions for parser
            for (ApplicationPort schemaInput : application.getInputs()) {
                boolean hasArg = !schemaInput.getDataType().isType(DataType.Type.BOOLEAN);
                appInputOptions.addOption(null, schemaInput.getId().replaceFirst("^#", ""), hasArg, schemaInput.getDescription());
            }

            Map<String, Object> inputs;
            if (inputsFile != null) {
                String inputsText = readFile(inputsFile.toAbsolutePath().toString(), Charset.defaultCharset());
                inputs = (Map<String, Object>) JSONHelper.transform(JSONHelper.readJsonNode(inputsText), false);
            } else {
                inputs = new HashMap<>();
                // No inputs file. If we didn't provide -- at the end, just print app help and exit
                if (!commandLineArray.contains("--"))
                    printAppUsageAndExit(appInputOptions);
            }

            // Check for required inputs
            List<String> missingRequiredFields = new ArrayList<>();
            for (ApplicationPort schemaInput : application.getInputs()) {
                String id = schemaInput.getId().replaceFirst("^#", "");

                if (schemaInput.isRequired() && schemaInput.getDefaultValue() == null && (!inputs.containsKey(id) || inputs.get(id) == null)) {
                    missingRequiredFields.add(id);
                }
            }
            if (!missingRequiredFields.isEmpty()) {
                String message = "Required inputs missing: " + StringUtils.join(missingRequiredFields, ", ");
                VerboseLogger.log(message);
                if (configuration.getBoolean("composer.logs.enabled", false))
                    logger.info("Composer: {\"status\": \"FAILED\",  \"stepId\": \"root\", \"message\": \"" + message + "\"}");
                printAppUsageAndExit(appInputOptions);
            }

            Resources resources = null;
            Map<String, Object> contextConfig = null;

            resources = extractResources(inputs, bindings.getProtocolType());
            if (resources != null) {
                contextConfig = new HashMap<String, Object>();
                if (resources.getCpu() != null) {
                    contextConfig.put("allocatedResources.cpu", resources.getCpu().toString());
                }
                if (resources.getMemMB() != null) {
                    contextConfig.put("allocatedResources.mem", resources.getMemMB().toString());
                }
            }

            final BootstrapService bootstrapService = injector.getInstance(BootstrapService.class);

            final JobService jobService = injector.getInstance(JobService.class);

            bootstrapService.start();
            Object commonInputs = null;
            try {
                commonInputs = bindings.translateToCommon(inputs);
                if (inputsFile != null) {
                    final Path finalInputs = inputsFile;
                    FileValueHelper.updateFileValues(commonInputs, (FileValue f) -> {
                        fixPaths(finalInputs, f);
                        if (f.getSecondaryFiles() != null)
                            for (FileValue sec : f.getSecondaryFiles()) {
                                fixPaths(finalInputs, sec);
                            }
                        return f;
                    });
                }
            } catch (BindingException e1) {
                VerboseLogger.log("Failed to translate inputs to the common Rabix format");
                System.exit(10);
            }

            jobService.start(new Job(appUrl, (Map<String, Object>) commonInputs), contextConfig);
        } catch (ParseException e) {
            logger.error("Encountered an error while parsing using Posix parser.", e);
            System.exit(10);
        } catch (IOException e) {
            logger.error("Encountered an error while reading a file.", e);
            System.exit(10);
        } catch (JobServiceException | BootstrapServiceException | URISyntaxException e) {
            logger.error("Encountered an error while starting local backend.", e);
            System.exit(10);
        }
    }

    /**
     * Prints command line usage
     */
    private static void printUsageAndExit(Options options) {
        HelpFormatter hf =new HelpFormatter();
        hf.setWidth(87);
        hf.setSyntaxPrefix("Usage: \n");
        final String usage = "    rabix [OPTIONS]... <app> <inputs> [-- input_parameters...]\n" +
                "    rabix [OPTIONS]... <app> -- input_parameters...\n\n" +
                "where:\n" +
                " <app>               is the path to a CWL document that describes the app.\n" +
                " <inputs>            is the JSON or YAML file that provides the values of app inputs.\n" +
                " input_parameters... are the app input values specified directly from the command line\n\n";
        final String header = "Executes CWL application with provided inputs.\n\nOptions:\n";
        final String footer = "\nInput parameters are specified at the end of the command, after the -- delimiter. You can specify values for each input, using the following format:\n" +
                "  --<input_port_id> <value>\n\n" +
                "Rabix suite homepage: http://rabix.io\n" +
                "Source and issue tracker: https://github.com/rabix/bunny.";
        hf.printHelp(usage, header, options, footer);
        System.exit(10);
    }

    /**
     * Create command line options
     */
    private static Options createOptions() {
        Options options = new Options();
        options.addOption("v", "verbose", false, "print more information on the standard output");
        options.addOption("b", "basedir", true, "execution directory");
        options.addOption("d", "directory-name", true, "base directory name");
        options.addOption(null, "aws-output-location", true, "location on the S3 bucket where to upload outputs of the job");
        options.addOption("c", "configuration-dir", true, "configuration directory");
        options.addOption("r", "resolve-app", false, "resolve all referenced fragments and print application as a single JSON document");
        options.addOption(null, "cache-dir", true, "basic tool result caching (experimental)");
        options.addOption(null, "no-container", false, "don't use containers");
        options.addOption(null, "tmp-outdir-prefix", true, "legacy compatibility parameter, doesn't do anything");
        options.addOption(null, "tmpdir-prefix", true, "legacy compatibility parameter, doesn't do anything");
        options.addOption(null, "outdir", true, "legacy compatibility parameter, doesn't do anything");
        options.addOption(null, "quiet", false, "don't print anything except final result on standard output");
        options.addOption(null, "tes-url", true, "url of the ga4gh task execution server instance (experimental)");
        options.addOption(null, "tes-storage", true, "path to the storage used by the ga4gh tes server (currently supports locall dirs and google storage cloud paths)");
        options.addOption(null, "enable-composer-logs", false, "enable additional logging required by Composer");
        // TODO: implement useful cli overrides for config options
        options.addOption(null, "version", false, "print program version and exit");
        options.addOption("h", "help", false, "print this help message and exit");
        return options;
    }

    private static void printVersionAndExit(Options posixOptions) {
        System.out.println("Rabix 1.0.4");
        System.exit(0);
    }

    private static boolean checkCommandLine(CommandLine commandLine) {
        if (commandLine.getArgList().size() == 1 || commandLine.getArgList().size() == 2) {
            return true;
        }
        logger.info("Invalid number of arguments\n");
        return false;
    }

    private static void printResolvedAppAndExit(String appUrl) {
        Bindings bindings = null;
        Application application = null;
        try {
            bindings = BindingsFactory.create(appUrl);
            application = bindings.loadAppObject(appUrl);

            System.out.println(BeanSerializer.serializePartial(application));
            System.exit(0);
        } catch (NotImplementedException e) {
            logger.error("Not implemented feature");
            System.exit(33);
        } catch (BindingException e) {
            logger.error("Error: " + appUrl + " is not a valid app!");
            System.exit(10);
        }
    }

    private static File getConfigDir(CommandLine commandLine, Options options) throws IOException, URISyntaxException {
        String configPath = commandLine.getOptionValue("configuration-dir");
        if (configPath != null) {
            File config = new File(configPath);
            if (config.exists() && config.isDirectory()) {
                return config;
            } else {
                logger.debug("Configuration directory {} doesn't exist or is not a directory.", configPath);
            }
        }

        File config = Paths.get(BatchWorkerCommandLine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent().toAbsolutePath().normalize().resolve("config").toFile();

        logger.debug("Config path: {}", config.getCanonicalPath());
        if (config.exists() && config.isDirectory()) {
            logger.debug("Configuration directory found localy.");
            return config;
        }
        String homeDir = System.getProperty("user.home");

        config = new File(homeDir, configDir);
        if (!config.exists() || !config.isDirectory()) {
            logger.info("Config directory doesn't exist or is not a directory");
            printUsageAndExit(options);
        }
        return config;
    }

    private static String generateDirectoryName(String path) {
        String name = FilenameUtils.getBaseName(path);
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd-HHmmss.S");
        return name + "-" + df.format(new Date());
    }

    /**
     * Reads content from a file
     */
    static String readFile(String path, Charset encoding) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    private static void printAppUsageAndExit(Options options) {
        HelpFormatter h = new HelpFormatter();
        h.setSyntaxPrefix("");
        h.printHelp("Inputs for selected tool are: ", options);
        System.exit(10);
    }

    private static void printAppInvalidUsageAndExit(Options options) {
        HelpFormatter h = new HelpFormatter();
        h.setSyntaxPrefix("");
        h.printHelp("You have invalid inputs for the tool you provided. Valid inputs are: ", options);
        System.exit(10);
    }

    private static Object createInputValue(String[] value, DataType inputType) {
        if (inputType.isArray()) {
            if (inputType.getSubtype().isFile()) {
                List<FileValue> ret = new ArrayList<>();
                for (String s : value) {
                    ret.add(new FileValue(null, s, null, null, null, null, null));
                }
                return ret;
            } else {
                return Arrays.asList(value);
            }
        }

        if (inputType.isFile()) {
            return new FileValue(null, value[0], null, null, null, null, null);
        } else {
            return value[0];
        }
    }

    private static Resources extractResources(Map<String, Object> inputs, ProtocolType protocol) {
        switch (protocol) {
            case DRAFT2: {
                if (inputs.containsKey("allocatedResources")) {
                    Map<String, Object> allocatedResources = (Map<String, Object>) inputs.get("allocatedResources");
                    Long cpu = ((Integer) allocatedResources.get("cpu")).longValue();
                    Long mem = ((Integer) allocatedResources.get("mem")).longValue();
                    return new Resources(cpu, mem, null, false, null, null, null, null);
                }
            }
            case DRAFT3:
                return null;
            default:
                return null;
        }
    }

    private static void fixPaths(final Path finalInputs, FileValue f) {
        String path = f.getPath();
        if (path != null && !Paths.get(path).isAbsolute()) {
            f.setPath(finalInputs.resolveSibling(path).normalize().toString());
        }
        String location = f.getLocation();
        if (location != null && URI.create(location).getScheme() == null) {
            f.setLocation(finalInputs.resolveSibling(location).normalize().toString());
        }
    }

}
