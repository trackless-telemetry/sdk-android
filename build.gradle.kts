import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "com.tracklesstelemetry.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ZERO runtime dependencies — Android framework only
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.mockk:mockk:1.13.9")
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates("com.tracklesstelemetry", "sdk-android", "0.2.2")

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set("Trackless Telemetry Android SDK")
        description.set("Privacy-first analytics SDK for Android — zero dependencies, no persistent identifiers, aggregate counts only.")
        url.set("https://github.com/trackless-telemetry/sdk-android")
        inceptionYear.set("2025")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("trackless-telemetry")
                name.set("Trackless Telemetry")
                email.set("support@tracklesstelemetry.com")
                url.set("https://tracklesstelemetry.com")
            }
        }

        scm {
            url.set("https://github.com/trackless-telemetry/sdk-android")
            connection.set("scm:git:git://github.com/trackless-telemetry/sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/trackless-telemetry/sdk-android.git")
        }
    }
}
