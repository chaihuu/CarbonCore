/*
 * This file is part of CarbonCore.
 *
 * Copyright (C) 2026 Chaihu
 *
 * CarbonCore is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * CarbonCore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "xyz.chaihu"
version = "1.1.0"
description = "CarbonCore - Bank / RedPacket / Wish / Quest with dual Java-GUI & Bedrock-Form UI"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
    // GeyserMC official form API (Cumulus) + Floodgate, provided at runtime by the server
    compileOnly("org.geysermc.geyser:api:2.12.0-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("org.geysermc.cumulus:cumulus:1.1.2")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("me.clip:placeholderapi:2.12.3")

    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.zaxxer:HikariCP:7.1.0")
}

java {
    // Paper 26.2 runs on Java 25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        manifest {
            attributes(
                "Implementation-Title" to "CarbonCore",
                "Implementation-Version" to project.version,
                "License" to "GPL-3.0-or-later"
            )
        }
        // Bundle sqlite + HikariCP into the single jar.
        // xerial natives live under /org/xerial/sqlite/native and are loaded by
        // absolute resource path, so we must NOT relocate those class packages.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
    }
    jar {
        enabled = false
    }
    build {
        dependsOn(shadowJar)
    }
}
