/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.cli.commands;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.cli.Arguments;
import software.amazon.smithy.cli.CliError;
import software.amazon.smithy.cli.CliPrinter;
import software.amazon.smithy.cli.ColorFormatter;
import software.amazon.smithy.cli.Command;
import software.amazon.smithy.cli.EnvironmentVariable;
import software.amazon.smithy.cli.StandardOptions;
import software.amazon.smithy.cli.Style;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.validation.ContextualValidationEventFormatter;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;

final class CommandUtils {

    private static final Logger LOGGER = Logger.getLogger(CommandUtils.class.getName());

    private CommandUtils() {}

    static Model buildModel(
            Arguments arguments,
            List<String> models,
            Command.Env env,
            CliPrinter printer,
            boolean quietValidation,
            SmithyBuildConfig config
    ) {
        ClassLoader classLoader = env.classLoader();
        ModelAssembler assembler = CommandUtils.createModelAssembler(classLoader);
        ContextualValidationEventFormatter formatter = new ContextualValidationEventFormatter();
        StandardOptions standardOptions = arguments.getReceiver(StandardOptions.class);
        BuildOptions buildOptions = arguments.getReceiver(BuildOptions.class);
        Severity minSeverity = buildOptions.severity(standardOptions);
        ColorFormatter colors = env.colors();

        assembler.validationEventListener(event -> {
            // Only log events that are >= --severity. Note that setting --quiet inherently
            // configures events to need to be >= DANGER.
            if (event.getSeverity().ordinal() >= minSeverity.ordinal()) {
                if (event.getSeverity() == Severity.WARNING) {
                    // Only log warnings when not quiet
                    colors.println(printer, formatter.format(event), Style.YELLOW);
                } else if (event.getSeverity() == Severity.DANGER || event.getSeverity() == Severity.ERROR) {
                    // Always output error and danger events, even when quiet.
                    colors.println(printer, formatter.format(event), Style.RED);
                } else {
                    printer.println(formatter.format(event));
                }
            }
        });

        CommandUtils.handleModelDiscovery(buildOptions, assembler, classLoader, config);
        CommandUtils.handleUnknownTraitsOption(buildOptions, assembler);
        config.getSources().forEach(assembler::addImport);
        models.forEach(assembler::addImport);
        config.getImports().forEach(assembler::addImport);

        ValidatedResult<Model> result = assembler.assemble();
        Validator.validate(quietValidation, colors, env.stderr(), result);
        return result.getResult().orElseThrow(() -> new RuntimeException("Expected Validator to throw"));
    }

    static ModelAssembler createModelAssembler(ClassLoader classLoader) {
        return Model.assembler(classLoader).putProperty(ModelAssembler.DISABLE_JAR_CACHE, true);
    }

    private static void handleUnknownTraitsOption(BuildOptions options, ModelAssembler assembler) {
        if (options.allowUnknownTraits()) {
            LOGGER.fine("Ignoring unknown traits");
            assembler.putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
        }
    }

    private static void handleModelDiscovery(
            BuildOptions options,
            ModelAssembler assembler,
            ClassLoader baseLoader,
            SmithyBuildConfig config
    ) {
        if (options.discoverClasspath() != null) {
            discoverModelsWithClasspath(options.discoverClasspath(), assembler);
        } else if (shouldDiscoverDependencies(options, config)) {
            assembler.discoverModels(baseLoader);
        }
    }

    private static boolean shouldDiscoverDependencies(BuildOptions options, SmithyBuildConfig config) {
        if (options.discover()) {
            return true;
        } else {
            return config.getMaven().isPresent()
                   && EnvironmentVariable.SMITHY_DEPENDENCY_MODE.get().equals("standard");
        }
    }

    private static void discoverModelsWithClasspath(String rawClasspath, ModelAssembler assembler) {
        LOGGER.finer("Discovering models with classpath: " + rawClasspath);

        // Use System.getProperty here each time since it allows the value to be changed.
        String[] classpath = rawClasspath.split(System.getProperty("path.separator"));
        URL[] urls = new URL[classpath.length];

        for (int i = 0; i < classpath.length; i++) {
            try {
                urls[i] = Paths.get(classpath[i]).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new CliError("Error parsing model discovery URL: " + classpath[i]);
            }
        }

        URLClassLoader urlClassLoader = new URLClassLoader(urls);
        assembler.discoverModels(urlClassLoader);
    }
}
