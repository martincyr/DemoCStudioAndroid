plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val webChatDir = rootProject.layout.projectDirectory.dir("webchat")
val webChatAssetsDir = layout.projectDirectory.dir("src/main/assets/webchat")
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val npmExecutable = if (isWindows) "npm.cmd" else "npm"

val installWebChat = tasks.register<Exec>("installWebChat") {
    description = "Installs the Copilot Studio WebChat npm dependencies."
    workingDir = webChatDir.asFile
    commandLine(npmExecutable, "install", "--no-audit", "--no-fund")
    inputs.file(webChatDir.file("package.json"))
    outputs.dir(webChatDir.dir("node_modules"))
}

val buildWebChat = tasks.register<Exec>("buildWebChat") {
    description = "Bundles the Copilot Studio WebChat assets into src/main/assets/webchat."
    dependsOn(installWebChat)
    workingDir = webChatDir.asFile
    commandLine(npmExecutable, "run", "build")
    inputs.dir(webChatDir.dir("src"))
    inputs.file(webChatDir.file("build.mjs"))
    inputs.file(webChatDir.file("package.json"))
    outputs.dir(webChatAssetsDir)
}

tasks.named("preBuild") {
    dependsOn(buildWebChat)
}

android {
    namespace = "com.martincyr.demoagentsdk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.martincyr.demoagentsdk"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Not applied while optimization is disabled, but keeps the Adaptive Cards JNI/
            // reflection rules attached for whenever minification is turned on.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    buildToolsVersion = "36.0.0"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.compose.markdown)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(files("libs/AgentsClientSDK.jar"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.msal)
    implementation(libs.speech.sdk)
    implementation(libs.gson)
    implementation(libs.adaptivecards.android)
}