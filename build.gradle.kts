import org.gradle.kotlin.dsl.withType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        pluginVerifier()
        zipSigner()
    }
}


intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { file(it) }.orNull
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE").map { file(it) }.orNull
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Default channel: "default" (= stable on Marketplace). Override with the
        // `PUBLISH_CHANNEL` env var (e.g. "eap", "beta") if you want a side channel.
        channels = providers.environmentVariable("PUBLISH_CHANNEL")
            .map { listOf(it) }
            .orElse(listOf("default"))
    }
}
tasks.withType<RunIdeTask> {
    val sandboxProject = providers.environmentVariable("SANDBOX_PROJECT")
        .orNull ?: "/home/user/IdeaProjects/untitled"
    val sandboxProjectName = sandboxProject.substringAfterLast('/')
    args = listOf(sandboxProject)
    jvmArgs = listOf(
        "-Didea.trust.all.projects=true",
        "-Didea.config.path=${System.getProperty("user.home")}/.idea-run-$sandboxProjectName"
    )
}