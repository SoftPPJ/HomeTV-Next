pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral(); maven("https://maven.aliyun.com/repository/jcenter") { content { includeGroup("tv.danmaku.ijk.media") } } } }
rootProject.name = "HomeTV"
include(":app")
