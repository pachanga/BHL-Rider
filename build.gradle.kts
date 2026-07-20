fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(properties("platformLocalPath"))

        bundledPlugins(properties("platformBundledPlugins").map { it.split(',').filter(String::isNotBlank) })
        plugins(properties("platformPlugins").map { it.split(',').filter(String::isNotBlank) })

        // DAP client support (com.intellij.platform.dap.*) for the BHL debugger.
        bundledModule("intellij.platform.dap")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = properties("pluginGroup")
        name = properties("pluginName")
        version = properties("pluginVersion")

        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
        }
    }

    signing {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = environment("PUBLISH_TOKEN")
    }
}

tasks {
    wrapper {
        gradleVersion = "9.0.0"
    }
}
