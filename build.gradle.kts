plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        pluginVerifier()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "242"
            // Intentionally left open. The plugin only uses stable public IntelliJ Platform
            // APIs (TextEditor, FileEditorProvider, EditorNotificationProvider, Configurable,
            // AnAction). Compatibility with future builds is enforced by the JetBrains Plugin
            // Verifier in CI rather than by an artificial upper bound.
            untilBuild = provider { null }
        }

        vendor {
            name = "Lukas Ott"
            url = "https://github.com/0skater0/sops-editor-plugin"
        }
    }

    pluginVerification {
        ides {
            // Explicit compatibility matrix. Each entry triggers a download (~500 MB) and
            // a full Plugin Verifier run when `./gradlew verifyPlugin` is invoked. Keep this
            // list in sync with what the README claims as "Verified compatible with".
            //
            // IntelliJ IDEA Community (IC) is only distributed via the JetBrains Maven repo
            // for builds *before* 2025.3 (253). From 2025.3 onward there is only the
            // unified IntelliJ IDEA Ultimate (IU) distribution — see
            // https://blog.jetbrains.com/platform/2025/11/intellij-platform-2025-3-what-plugin-developers-should-know/
            create("IC", "2024.2")
            create("IC", "2024.3")
            create("IC", "2025.1")
            create("IC", "2025.2")
            create("IU", "2025.3")
            create("IU", "2026.1")

            // PhpStorm explicitly — primary daily-driver target for this plugin.
            create("PS", "2024.2")
            create("PS", "2025.3")
            create("PS", "2026.1")
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    wrapper {
        gradleVersion = "8.13"
    }
}
