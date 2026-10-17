import com.android.build.api.artifact.SingleArtifact
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
}

// versionCode = number of commits, so every CI build installs over the previous one.
fun commitCount(): Int = try {
    val out = ByteArrayOutputStream()
    val p = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    p.inputStream.copyTo(out)
    if (p.waitFor() == 0) out.toString().trim().toInt() else 1
} catch (e: Exception) { 1 }

android {
    namespace = "io.github.rushivangala.folio"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.rushivangala.folio"
        minSdk = 33
        targetSdk = 36
        versionCode = commitCount()
        versionName = providers.gradleProperty("appVersionName").get()
    }

    signingConfigs {
        create("release") {
            // CI passes these from repository secrets (see README → Signing).
            val ks = System.getenv("SIGNING_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                // No secrets: sign with the repository's shared CI key so builds still install and update.
                storeFile = rootProject.file("ci/ci-signing.jks")
                storePassword = "android"
                keyAlias = "ci"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }

    buildFeatures {
        buildConfig = true
    }
}

base {
    archivesName.set("Folio-${providers.gradleProperty("appVersionName").get()}")
}

// ---------------------------------------------------------------- no internet, ever
// Folio must never hold android.permission.INTERNET. Libraries can add it through manifest merging,
// so this checks the merged release manifest (what actually ships) and fails the build if it is there.
abstract class CheckNoInternet : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val f = manifest.get().asFile
        val text = f.readText()
        if (!text.contains("<manifest")) throw GradleException("No-internet check: ${f.path} is not a manifest")
        val found = Regex("""android\.permission\.INTERNET["'\s/>]""").containsMatchIn(text)
        if (found) {
            throw GradleException(
                "The merged release manifest requests android.permission.INTERNET.\n" +
                    "Folio must work without it. Find the library that adds it (see ${f.path}) and remove it, " +
                    "or strip it with <uses-permission android:name=\"android.permission.INTERNET\" tools:node=\"remove\" />."
            )
        }
        report.get().asFile.writeText("OK: no android.permission.INTERNET in ${f.name}\n")
        // On GitHub Actions this becomes a visible notice on the run, proof that the check ran.
        if (System.getenv("GITHUB_ACTIONS") == "true") println("::notice title=No internet::Merged release manifest checked: no android.permission.INTERNET")
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val checkTask = tasks.register<CheckNoInternet>("check${name}NoInternet") {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest requests the INTERNET permission."
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/no-internet/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "bundle$name" }.configureEach { dependsOn(checkTask) }
    }
}
