import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.toolchain.internal.tools.ToolSearchPath
import org.gradle.nativeplatform.toolchain.internal.ToolType
import org.gradle.nativeplatform.toolchain.internal.gcc.AbstractGccCompatibleToolChain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// Kotlin has issues if we try to get the working directory from inside a RuleSource, so put it in a static field.
class BuildContext {
    companion object {
        @JvmStatic
        lateinit var workingDirectory: File
    }
}

BuildContext.workingDirectory = file("..")

// For some reason, the compiler plugins (giving NativeToolChainRegistry the compiler factories) don't run
// until after the build script is evaluated, so we have to set up our toolchains as a part of a @Mutate rule.

open class ToolchainConfiguration : RuleSource() {
    @Mutate fun NativeToolChainRegistry.configureToolchains() {
        // On linux, compile using gcc, mingw, and osxcross
        if (OperatingSystem.current().isLinux) {
            register<Gcc>("gcc") {
                target("linux_x86-64")
                target("linux_x86")

                target("windows_x86-64") {
                    getcCompiler().executable = "x86_64-w64-mingw32-g++-faker"
                    cppCompiler.executable = "x86_64-w64-mingw32-g++-faker"
                    linker.executable = "x86_64-w64-mingw32-g++-faker"
                    staticLibArchiver.executable = "x86_64-w64-mingw32-ar"
                }

                target("windows_x86") {
                    getcCompiler().executable = "i686-w64-mingw32-g++-faker"
                    cppCompiler.executable = "i686-w64-mingw32-g++-faker"
                    linker.executable = "i686-w64-mingw32-g++-faker"
                    staticLibArchiver.executable = "i686-w64-mingw32-ar"
                }
            }

            val osxcrossBin = locateOsxCross()
            if (osxcrossBin != null) {
                register<Clang>("osxcross") { this as AbstractGccCompatibleToolChain
                    path(osxcrossBin, osxcrossBin.resolve("../binutils/bin"))

                    // Hack into the toolchain internals a bit to set a cached location for our SDK libraries, since
                    // gradle tries to run xcrun without using the path we set.
                    val sdkDirs = File(osxcrossBin.parent.resolve("SDK").toString()).listFiles()

                    if (sdkDirs != null && sdkDirs.isNotEmpty()) {
                        val sLD = AbstractGccCompatibleToolChain::class.java.getDeclaredField("standardLibraryDiscovery")
                        sLD.isAccessible = true
                        val pathLocator = org.gradle.nativeplatform.toolchain.internal.gcc.metadata.SystemLibraryDiscovery::class.java.getDeclaredField("macOSSdkPathLocator");
                        pathLocator.isAccessible = true
                        val cachedLocation = org.gradle.nativeplatform.toolchain.internal.xcode.AbstractLocator::class.java.getDeclaredField("cachedLocation");
                        cachedLocation.isAccessible = true
                        cachedLocation.set(pathLocator.get(sLD.get(this)), sdkDirs[0])
                    }

                    target("macos_x86-64") {
                        this as org.gradle.nativeplatform.toolchain.internal.gcc.DefaultGccPlatformToolChain
                        getcCompiler().executable = "o64-clang"
                        cppCompiler.executable = "o64-clang++"
                        linker.executable = "o64-clang++"
                        assembler.executable = "o64-clang"
                        symbolExtractor.executable = "x86_64-apple-darwin19-objcopy"
                        stripper.executable = "x86_64-apple-darwin19-strip"
                    }
                }
            }
        }

        // On windows, compile using visualcpp or gcc. Unfortunately, cross compile from windows isn't really possible
        if (OperatingSystem.current().isWindows) {
            register<VisualCpp>("visualCpp")

            register<Gcc>("gcc") {
                target("windows_x86-64")
                target("windows_x86")
            }
        }

        val androidNdk = locateAndroidNdk()
        if (androidNdk != null) {
            //val extraIncludes = androidNdk.parent.parent.parent.parent.parent.resolve("sysroot").resolve("usr").resolve("include")
            val extraIncludes = androidNdk.parent.resolve("sysroot").resolve("usr").resolve("include")

            register<Clang>("androidNdk") {
                target("android_armv7a") { this as org.gradle.nativeplatform.toolchain.internal.gcc.DefaultGccPlatformToolChain
                    path(androidNdk)

                    val configureArguments: Action<MutableList<String>> = org.gradle.api.Action {
                        add("-target")
                        add("armv7a-linux-androideabi21")
                        add(0, "-isystem")
                        add(1, extraIncludes.toAbsolutePath().toString())
                        add(2, "-isystem")
                        add(3, extraIncludes.resolve("arm-linux-androideabi").toAbsolutePath().toString())
                        add(4, "-isystem")
                        add(5, androidNdk.parent.resolve("sysroot").resolve("usr").resolve("include").resolve("c++").resolve("v1").toAbsolutePath().toString())

                        add("-fdeclspec")
                    }


                    getcCompiler().executable = "clang-14"
                    getcCompiler().withArguments(configureArguments)
                    cppCompiler.executable = "clang-14"
                    cppCompiler.withArguments(configureArguments)
                    linker.executable = "clang-14"
                    linker.withArguments(configureArguments)

                    symbolExtractor.executable = "llvm-objcopy"
                    staticLibArchiver.executable = "llvm-ar"
                    stripper.executable = "llvm-strip"
                }

                target("android_arm64-v8a") {
                    this as org.gradle.nativeplatform.toolchain.internal.gcc.DefaultGccPlatformToolChain
                    path(androidNdk)

                    val configureArguments: Action<MutableList<String>> = org.gradle.api.Action {
                        add("-target")
                        add("aarch64-linux-android21")
                        add(0, "-isystem")
                        add(1, extraIncludes.toAbsolutePath().toString())
                        add(2, "-isystem")
                        add(3, extraIncludes.resolve("aarch64-linux-android").toAbsolutePath().toString())
                        add(4, "-isystem")
                        add(5, androidNdk.parent.resolve("sysroot").resolve("usr").resolve("include").resolve("c++").resolve("v1").toAbsolutePath().toString())

                        add("-fdeclspec")
                        add("-fms-extensions")
                    }


                    getcCompiler().executable = "clang-14"
                    getcCompiler().withArguments(configureArguments)
                    cppCompiler.executable = "clang-14"
                    cppCompiler.withArguments(configureArguments)
                    linker.executable = "clang-14"
                    linker.withArguments(configureArguments)

                    symbolExtractor.executable = "llvm-objcopy"
                    staticLibArchiver.executable = "llvm-ar"
                    stripper.executable = "llvm-strip"
                }

                target("android_x86") {
                    this as org.gradle.nativeplatform.toolchain.internal.gcc.DefaultGccPlatformToolChain
                    path(androidNdk)

                    val configureArguments: Action<MutableList<String>> = org.gradle.api.Action {
                        add("-target")
                        add("i686-linux-android21")
                        add(0, "-isystem")
                        add(1, extraIncludes.toAbsolutePath().toString())
                        add(2, "-isystem")
                        add(3, extraIncludes.resolve("i686-linux-android").toAbsolutePath().toString())
                        add(4, "-isystem")
                        add(5, androidNdk.parent.resolve("sysroot").resolve("usr").resolve("include").resolve("c++").resolve("v1").toAbsolutePath().toString())

                        add("-fdeclspec")
                    }


                    getcCompiler().executable = "clang-14"
                    getcCompiler().withArguments(configureArguments)
                    cppCompiler.executable = "clang-14"
                    cppCompiler.withArguments(configureArguments)
                    linker.executable = "clang-14"
                    linker.withArguments(configureArguments)

                    symbolExtractor.executable = "llvm-objcopy"
                    staticLibArchiver.executable = "llvm-ar"
                    stripper.executable = "llvm-strip"
                }

                target("android_x86-64") {
                    this as org.gradle.nativeplatform.toolchain.internal.gcc.DefaultGccPlatformToolChain
                    path(androidNdk)

                    val configureArguments: Action<MutableList<String>> = org.gradle.api.Action {
                        add("-target")
                        add("x86_64-linux-android21")
                        add(0, "-isystem")
                        add(1, extraIncludes.toAbsolutePath().toString())
                        add(2, "-isystem")
                        add(3, extraIncludes.resolve("x86_64-linux-android").toAbsolutePath().toString())
                        add(4, "-isystem")
                        add(5, androidNdk.parent.resolve("sysroot").resolve("usr").resolve("include").resolve("c++").resolve("v1").toAbsolutePath().toString())

                        add("-fdeclspec")
                    }


                    getcCompiler().executable = "clang-14"
                    getcCompiler().withArguments(configureArguments)
                    cppCompiler.executable = "clang-14"
                    cppCompiler.withArguments(configureArguments)
                    linker.executable = "clang-14"
                    linker.withArguments(configureArguments)

                    symbolExtractor.executable = "llvm-objcopy"
                    staticLibArchiver.executable = "llvm-ar"
                    stripper.executable = "llvm-strip"
                }
            }
        }
    }

    private fun locateAndroidNdk(): Path? {
        // Check system property
        var androidNdk = if (System.getProperty("androidNdk") != null) Paths.get(System.getProperty("androidNdk")) else null

        // Check "ANDROID_NDK_ROOT" environment variable
        if (androidNdk == null && System.getenv("ANDROID_NDK_ROOT") != null) {
            androidNdk = Paths.get(System.getenv("ANDROID_NDK_ROOT"))
        }

        if (androidNdk == null && System.getenv("ANDROID_NDK_HOME") != null) {
            androidNdk = Paths.get(System.getenv("ANDROID_NDK_HOME"))
        }

        // Check the working directory
        if (androidNdk == null) {
            Files.list(BuildContext.workingDirectory.toPath()).forEach {
                if (it.fileName.startsWith("android-ndk")) {
                    androidNdk = it
                }
            }
        }

        if (androidNdk == null) {
            System.err.println("No Android NDK found, android builds will be unavailable. Please set the ANDROID_NDK_ROOT or ANDROID_NDK_HOME variables to the install location, run gradle with -DandroidNdk=<Install Path>, or symlink the install path to your \"devolay\" folder.")
        }

        val prebuilts = androidNdk?.resolve("toolchains")?.resolve("llvm")?.resolve("prebuilt")
        var bin: Path? = null

        if (prebuilts != null) {
            Files.list(prebuilts).findFirst().ifPresent {
                bin = it.resolve("bin")
            }
        }

        return bin
    }

    private fun locateOsxCross(): Path? {
        // Check system property
        var osxcross = if (System.getProperty("osxcrossBin") != null) Paths.get(System.getProperty("osxcrossBin")) else null

        // Check the working directory
        if (osxcross == null) {
            Files.list(BuildContext.workingDirectory.toPath()).forEach {
                if (it.fileName.startsWith("osxcross")) {
                    osxcross = it.resolve("target").resolve("bin")
                }
            }
        }

        // Check the system path
        val searchResult = ToolSearchPath(OperatingSystem.current()).locate(ToolType.C_COMPILER, "xcrun")
        if (osxcross == null && searchResult.isAvailable) {
            osxcross = searchResult.tool.parentFile.toPath();
        }


        if (osxcross == null) {
            System.err.println("No Osxcross found, Macos builds will be unavailable. Please add the osxcross/target/bin path to your PATH variable, run gradle with -Dosxcross=<Bin Path>, or symlink the install path to your \"devolay\" folder.")
        }

        return osxcross
    }
}
apply("plugin" to ToolchainConfiguration::class.java)
