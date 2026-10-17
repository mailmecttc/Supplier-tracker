pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central. Listed before mavenCentral() because
        // repo.maven.apache.org rate-limits (HTTP 429) some networks.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "SupplierTracker"
include(":app")
