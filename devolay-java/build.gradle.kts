import org.gradle.internal.jvm.Jvm

plugins {
    base
    `java-library`
    `maven-publish`
    signing
}

base.archivesBaseName = "devolay"
java.sourceCompatibility = JavaVersion.VERSION_1_8

sourceSets {
    create("integrated") {
        java {
            srcDir("src/main/java")
        }
    }
}

java {
    registerFeature("integrated") {
        usingSourceSet(sourceSets["main"])
        usingSourceSet(sourceSets["integrated"])
    }
}

val sourceJar by tasks.creating(Jar::class) {
    from(sourceSets.main.get().allJava)
    this.archiveClassifier.set("sources")
}

val javadocJar by tasks.creating(Jar::class) {
    dependsOn("javadoc")
    from(tasks.javadoc.get().destinationDir)
    this.archiveClassifier.set("javadoc")
}

val classesJar by tasks.registering(Jar::class) {
    dependsOn(tasks.classes)
    from(sourceSets["main"].java.classesDirectory)
}
val generateAndroidManifest by tasks.registering {
    outputs.file(temporaryDir.resolve( "AndroidManifest.xml"))

    doLast {
        temporaryDir.resolve( "AndroidManifest.xml").delete()
        temporaryDir.resolve( "AndroidManifest.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="me.walkerknapp.devolay"
                android:versionCode="${(version as String).replace(".", "")}"
                android:versionName="$version" >
                <uses-sdk android:minSdkVersion="16" android:targetSdkVersion="16" />
                <uses-permission android:name="android.permission.INTERNET" />
            </manifest>
        """.trimIndent())
    }
}

// Depend on the artifacts generated by devolay-natives

val nativeDesktopDependency: Configuration by configurations.creating
val ndiDesktopDependency: Configuration by configurations.creating
val nativeAndroidDependency: Configuration by configurations.creating

dependencies {
    nativeDesktopDependency(project(":devolay-natives", "nativeArtifacts"))
    ndiDesktopDependency(project(":devolay-natives", "integratedNdiArtifacts"))
    nativeAndroidDependency(project(":devolay-natives", "androidArtifacts"))
}

tasks.jar {
    dependsOn(nativeDesktopDependency)
    from(nativeDesktopDependency.map { zipTree(it) })
}

tasks.named<Jar>("integratedJar") {
    dependsOn(ndiDesktopDependency)
    dependsOn(nativeDesktopDependency)
    from(ndiDesktopDependency.map { zipTree(it) })
    from(nativeDesktopDependency.map { zipTree(it) })
}

val androidAar by tasks.registering(Zip::class) {
    from(nativeAndroidDependency)
    from(classesJar) {
        rename { "classes.jar" }
    }
    from(generateAndroidManifest)
}

// Define our publications

publishing {
    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = System.getenv("OSSRH_USERNAME")
                password = System.getenv("OSSRH_PASSWORD")
            }
        }
    }
    publications {
        create<MavenPublication>("devolay") {
            from(components["java"])
            artifact(sourceJar)
            artifact(javadocJar)
            artifact(androidAar) {
                extension = "aar"
            }

            groupId = project.group as String
            artifactId = "devolay"
            version = project.version as String?

            pom {
                name.set("Devolay")
                description.set("Devolay is a library for sending and receiving video over the network using the Newtek NDI(tm) SDK.")
                url.set("https://github.com/WalkerKnapp/devolay")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("WalkerKnapp")
                        name.set("Walker Knapp")
                        email.set("walker@walkerknapp.me")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/WalkerKnapp/devolay.git")
                    developerConnection.set("scm:git:git@github.com:WalkerKnapp/devolay.git")
                    url.set("https://github.com/WalkerKnapp/devolay")
                }
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(System.getenv("PGP_KEY_ID"), System.getenv("PGP_KEY"), System.getenv("PGP_PASSWORD"))
    sign(publishing.publications["devolay"])
}

// Generate an artifact of the JNI headers created by this project, for devolay-natives to consume.

val generateJniHeaders by tasks.registering(Exec::class) {
    description = "Generates C headers for JNI by running javah"
    group = "build"

    inputs.files(sourceSets.main.get().allJava)
    outputs.dir(temporaryDir.resolve("headers").absolutePath)

    //val nativeIncludes = "../devolay-natives/src/main/headers"

    doFirst {
        temporaryDir.resolve("classes").mkdirs()
        temporaryDir.resolve("headers").mkdirs()

        val javacCommand = mutableListOf(Jvm.current().javacExecutable.toString(),
                "-d",
                temporaryDir.resolve("classes").absolutePath,
                "-h",
                temporaryDir.resolve("headers").absolutePath)

        inputs.files.forEach { file ->
            javacCommand.add(file.toPath().toAbsolutePath().toString())
        }

        commandLine(javacCommand)
    }
}

val jniIncludes: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, namedAttribute(Usage.C_PLUS_PLUS_API))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, namedAttribute(LibraryElements.HEADERS_CPLUSPLUS))
        attribute(Attribute.of("artifactType", String::class.java), "directory")
    }
}
inline fun <reified T: Named> Project.namedAttribute(value: String) = objects.named(T::class.java, value)

artifacts {
    add("jniIncludes", generateJniHeaders)
}
