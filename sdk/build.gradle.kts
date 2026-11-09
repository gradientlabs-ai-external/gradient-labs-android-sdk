plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.gradientlabs.chatsdk"
    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
        // Pure version-bump noise; dependency updates are not lint's job.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// JitPack builds a tag and serves it as
// com.github.gradientlabs-ai-external:gradient-labs-android-sdk:<tag>. It passes the
// tag in VERSION; local builds fall back to sdkVersion or a dev version.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.gradientlabs-ai-external"
            artifactId = "gradient-labs-android-sdk"
            version = System.getenv("VERSION") ?: providers.gradleProperty("sdkVersion").getOrElse("dev")
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Gradient Chat SDK")
                description.set("Android SDK for embedding Gradient Labs chat in host apps.")
                url.set("https://github.com/gradientlabs-ai-external/gradient-labs-android-sdk")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    // Android's org.json is a throwing stub in JVM unit tests; the real impl
    // lets WidgetBridge parse its JSON messages under test.
    testImplementation(libs.json)
}
