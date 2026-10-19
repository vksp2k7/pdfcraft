import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val copyPdfcraftWeb by tasks.registering(Sync::class) {
    val webOutput = rootProject.file("../dist/web")
    from(webOutput)
    into(layout.projectDirectory.dir("src/main/assets/pdfcraft-web"))
    doFirst {
        require(webOutput.resolve("index.html").isFile) {
            "Missing ${webOutput}/index.html. Run ./build-apk.sh from the android directory to build the WASM frontend first."
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(copyPdfcraftWeb)
}

android {
    namespace = "ai.storyteller.pdfcraft"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.storyteller.pdfcraft"
        minSdk = 29
        targetSdk = 35
        versionCode = 9000
        versionName = "0.9.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        noCompress += listOf("wasm")
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
