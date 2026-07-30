package com.github.megatronking.stringfog.plugin

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import groovy.xml.XmlParser
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.FileInputStream
import java.io.InputStreamReader

class StringFogPlugin : Plugin<Project> {

    companion object {
        private const val PLUGIN_NAME = "stringfog"
    }

    override fun apply(project: Project) {
        project.extensions.create(PLUGIN_NAME, StringFogExtension::class.java)

        // AGP 9.x removed the old DSL implementation types (BaseExtension / AppExtension /
        // LibraryExtension, applicationVariants / libraryVariants, registerJavaGeneratingTask).
        // Everything below now goes exclusively through the public AndroidComponentsExtension /
        // Variant API so the plugin keeps working on AGP 9.x + Gradle 9.x.
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.onVariants { variant ->
            // Check stringfog extension
            val stringfog = project.extensions.getByType(StringFogExtension::class.java)
            if (stringfog.implementation.isNullOrEmpty()) {
                throw IllegalArgumentException("Missing stringfog implementation config")
            }
            if (!stringfog.enable) {
                return@onVariants
            }

            // We must get the package name to generate <package name>.StringFog.java
            // Priority: AndroidManifest -> variant applicationId/namespace -> stringfog.packageName
            var applicationId: String? = null
            val manifestFile = project.file("src/main/AndroidManifest.xml")
            if (manifestFile.exists()) {
                val parsedManifest = XmlParser().parse(
                    InputStreamReader(FileInputStream(manifestFile), "utf-8")
                )
                applicationId = parsedManifest.attribute("package")?.toString()
            }
            if (applicationId.isNullOrEmpty()) {
                // ApplicationVariant exposes the real applicationId; other variant types
                // (library, etc.) only expose namespace.
                applicationId = (variant as? ApplicationVariant)?.applicationId?.orNull
                    ?: variant.namespace.orNull
            }
            if (applicationId.isNullOrEmpty()) {
                applicationId = stringfog.packageName
            }
            if (applicationId.isNullOrEmpty()) {
                throw IllegalArgumentException("Unable to resolve applicationId")
            }

            val logs = mutableListOf<String>()
            variant.instrumentation.transformClassesWith(
                StringFogTransform::class.java,
                InstrumentationScope.PROJECT
            ) { params ->
                params.setParameters(
                    applicationId,
                    stringfog,
                    logs,
                    "$applicationId.${SourceGeneratingTask.FOG_CLASS_NAME}"
                )
            }
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
            )

            // Register one generating task per variant (variant.name is already unique per
            // variant, so there's no cross-variant collision to guard against here) and wire
            // its output directory through the new Sources API instead of
            // BaseVariant.registerJavaGeneratingTask. AGP now manages/allocates the generated
            // source directory itself, so we no longer need to build the path from
            // project.buildDir by hand.
            val generateTaskName = "generateStringFog${variant.name.replaceFirstChar { it.uppercase() }}"
            val provider = project.tasks.register(generateTaskName, SourceGeneratingTask::class.java) { task ->
                task.applicationId.set(applicationId)
                task.implementation.set(stringfog.implementation)
                task.mode.set(stringfog.mode)
            }
            variant.sources.java?.addGeneratedSourceDirectory(provider, SourceGeneratingTask::genDir)

            // TODO Need a final task to write logs to file
//            val printFile = project.layout.buildDirectory.file("outputs/mapping/${variant.name.lowercase()}/stringfog.txt")
//            printFile.get().asFile.writeText(logs.joinToString("\n"))
        }
    }

}
