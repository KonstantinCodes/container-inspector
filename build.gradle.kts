plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "codes.konstantin"
version = "1.0.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Eclipse Layout Kernel for graph layout
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.11.0")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0")

    // YAML parsing for config files
    implementation("org.yaml:snakeyaml:2.2")

    // Test dependencies
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    intellijPlatform {
        phpstorm("2025.3.2")
        bundledPlugin("com.jetbrains.php")

        pluginVerifier()
        zipSigner()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildPlugin {
        from("LICENSE")
        from("THIRD_PARTY_LICENSES.md")
        from("licenses_of_third_party_libraries") {
            into("licenses_of_third_party_libraries")
        }
        from("notices_for_third_party_libraries") {
            into("notices_for_third_party_libraries")
        }
    }

    patchPluginXml {
        sinceBuild.set("253")
        untilBuild.set("253.*")

        changeNotes.set("""
            <h3>1.0.0</h3>
            <ul>
                <li>Initial release</li>
                <li>Container XML parsing and visualization</li>
                <li>Service search and filtering</li>
                <li>Dependency graph exploration</li>
                <li>Navigation to PHP classes</li>
            </ul>
        """.trimIndent())
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
