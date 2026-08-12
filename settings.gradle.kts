/*
 * FMZlinkR
 * Derived from ShizuCallRecorder.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FMZlinkR"
include(":app")
