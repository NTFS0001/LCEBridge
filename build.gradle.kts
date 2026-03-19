plugins {
    java
}

allprojects {
    group = "dev.banditvault.lcebridge"
    version = "0.2.0"

    repositories {
        mavenCentral()
        maven {
            name = "opencollab-releases"
            url = uri("https://repo.opencollab.dev/maven-releases/")
        }
        maven {
            name = "opencollab-snapshots"
            url = uri("https://repo.opencollab.dev/maven-snapshots/")
        }
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
