plugins {
    `maven-publish`
    signing
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

group = "io.github.geraldwambui"
version = "1.0.0"


android {
    namespace = "com.nachothegardener.flowforge.flowforge"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "io.github.geraldwambui"
                artifactId = "flowforge"
                version = "1.0.0"

                pom {
                    name.set("FlowForge")
                    description.set("A lightweight Kotlin library to simplify reactive flows in Jetpack Compose apps")
                    url.set("https://github.com/geraldwambui/FlowForge")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("geraldwambui")
                            name.set("Gerald Wambui")
                            email.set("gw746071@gmail.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:https://github.com/geraldwambui/FlowForge.git")
                        developerConnection.set("scm:git:ssh://git@github.com/geraldwambui/FlowForge.git")
                        url.set("https://github.com/geraldwambui/FlowForge")
                    }
                }
            }
        }
    }

    signing {
        sign(publishing.publications["release"])
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.android)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}