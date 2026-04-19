pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    val localProps = java.util.Properties().apply {
        val f = rootDir.resolve("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val githubToken: String = System.getenv("GITHUB_TOKEN")
        ?: localProps.getProperty("github_token")
        ?: ""

    repositories {
        google()
        mavenCentral()

        // Meta Wearables Device Access Toolkit — hosted on GitHub Packages.
        // Requires a GitHub PAT (classic) with read:packages scope.
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "" // GitHub Packages accepts empty username with a PAT
                password = githubToken
            }
        }
    }
}

rootProject.name = "pov-streamer"
include(":app")
