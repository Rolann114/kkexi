pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Maven Central 放前面：国内直连它比 dl.google.com 稳。
        // 实测教训：dl.google.com 会偶发 `Connection timed out: getsockopt`，
        // 而 org.json:json 这类非 AndroidX 的包在 Central 上同样有，没必要去 Google 兜一圈。
        mavenCentral()
        google()
    }
}

rootProject.name = "课隙"
include(":app")
