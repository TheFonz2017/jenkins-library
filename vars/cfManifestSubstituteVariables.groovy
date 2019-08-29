import com.sap.piper.ConfigurationHelper
import com.sap.piper.GenerateDocumentation
import com.sap.piper.variablesubstitution.ExecutionContext
import com.sap.piper.variablesubstitution.DebugHelper
import com.sap.piper.variablesubstitution.YamlUtils
import groovy.transform.Field

import static com.sap.piper.Prerequisites.checkScript

@Field String STEP_NAME = getClass().getName()
@Field Set GENERAL_CONFIG_KEYS = []
@Field Set STEP_CONFIG_KEYS = GENERAL_CONFIG_KEYS + [
    /**
     * The `String` path of the Yaml file to replace variables in.
     * Defaults to "manifest.yml" if not specified otherwise.
     */
    'manifestFile',
    /**
     * The `String` path of the Yaml file to produce as output.
     * If not specified this will default to `manifestFile` and overwrite it.
     */
    'outputManifestFile',
    /**
     * The `String` path of the Yaml file containing the variables' values to use as a replacement in the manifest file.
     * Defaults to `manifest-variables.yml` if not specified otherwise.
     */
    'variablesFile'
]

@Field Set PARAMETER_KEYS = STEP_CONFIG_KEYS

/**
 * Step that substitutes variables in a given YAML file with those specified in a another. The format to reference a variable
 * in the YAML file is to use double parentheses `((` and `))`, e.g. `((variableName))`.
 * A declaration of a variable and assignment of its value is simply done as a property in the variables YAML file.
 *
 * The format follows [Cloud Foundry standards](https://docs.cloudfoundry.org/devguide/deploy-apps/manifest-attributes.html#variable-substitution)
 *
 * The step is activated by the presence of both a `manifest.yml` and a variables file. Names of both files are configurable.
 */
@GenerateDocumentation
void call(Map<String, String> arguments) {
    handlePipelineStepErrors (stepName: STEP_NAME, stepParameters: arguments) {
        def script = checkScript(this, arguments)  ?: this

        // load default & individual configuration
        Map config = ConfigurationHelper.newInstance(this)
                                        .loadStepDefaults()
                                        .mixinStepConfig(script.commonPipelineEnvironment, STEP_CONFIG_KEYS)
                                        .mixinStageConfig(script.commonPipelineEnvironment, arguments.stageName ?: env.STAGE_NAME, STEP_CONFIG_KEYS)
                                        .mixin(arguments, PARAMETER_KEYS)
                                        .use()

        String manifestFilePath = config.manifestFile ?: "manifest.yml"
        String variablesFilePath = config.variablesFile ?: "manifest-variables.yml"
        String outputFilePath = config.outputManifestFile ?: manifestFilePath

        DebugHelper debugHelper = new DebugHelper(script, config)
        YamlUtils yamlUtils = new YamlUtils(script, debugHelper)

        Boolean manifestExists = fileExists manifestFilePath
        Boolean variablesFileExists = fileExists variablesFilePath

        if (!manifestExists) {
            echo "[CFManifestSubstituteVariables] Could not find YAML file at ${manifestFilePath}. Skipping variable substitution."
            return
        }

        if (!variablesFileExists) {
            echo "[CFManifestSubstituteVariables] Could not find variable substitution file at ${variablesFilePath}. Skipping variable substitution."
            return
        }

        def manifestData = null;
        try {
            // may return a List<Object>  (in case more YAML segments are in the file)
            // or a Map<String, Object> in case there is just one segment.
            manifestData = readYaml file: manifestFilePath
            echo "[CFManifestSubstituteVariables] Loaded manifest at ${manifestFilePath}!"
        }
        catch(Exception ex) {
            debugHelper.debug("Exception: ${ex}")
            echo "[CFManifestSubstituteVariables] Could not load manifest file at ${manifestFilePath}. Exception was: ${ex}"
            throw ex
        }

        def variablesData = null
        try {
            // may return a List<Object>  (in case more YAML segments are in the file)
            // or a Map<String, Object> in case there is just one segment.
            variablesData = readYaml file: variablesFilePath
            echo "[CFManifestSubstituteVariables] Loaded variables file at ${variablesFilePath}!"
        }
        catch(Exception ex) {
            debugHelper.debug("Exception: ${ex}")
            echo "[CFManifestSubstituteVariables] Could not load manifest variables file at ${variablesFilePath}. Exception was: ${ex}"
            throw ex
        }

        // substitute all variables.
        ExecutionContext context = new ExecutionContext()
        def result = yamlUtils.substituteVariables(manifestData, variablesData, context)

        if (context.noVariablesReplaced) {
            echo "[CFManifestSubstituteVariables] No variables were found or could be replaced in ${manifestFilePath}. Skipping variable substitution."
            return
        }

        // writeYaml won't overwrite the file. You need to delete it first.
        deleteFile(outputFilePath)

        writeYaml file: outputFilePath, data: result

        echo "[CFManifestSubstituteVariables] Replaced variables in ${manifestFilePath} with variables from ${variablesFilePath}."
        echo "[CFManifestSubstituteVariables] Wrote output file (with variables replaced) at ${outputFilePath}."

        debugHelper.debug("Loaded Manifest: ${manifestData}")
        debugHelper.debug("Loaded Variables: ${variablesData}")
        debugHelper.debug("Result: ${result}")
    }
}

/*
 * Removes the given file, if it exists.
 * @param filePath the path to the file to remove.
 */
private void deleteFile(String filePath) {

    Boolean fileExists = fileExists file: filePath
    if(fileExists) {
        Boolean failure = sh script: "rm '${filePath}'", returnStatus: true
        if(!failure) {
            echo "[CFManifestSubstituteVariables] Successfully deleted file '${filePath}'."
        }
        else {
            error "[CFManifestSubstituteVariables] Could not delete file '${filePath}'. Check file permissions."
        }
    }
}
