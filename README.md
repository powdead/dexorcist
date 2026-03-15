# dexorcist

Gradle plugin that makes Android apps built with modern NDK/SDK run on Android 2.3+ (API 9).

## What it does

Injects native shims for ~50 libc/libm symbols missing before API 21, switches to c++_static and strips System.loadLibrary("c++_shared"), disables _FORTIFY_SOURCE to avoid __*_chk symbol issues, and uses -mstack-protector-guard=global on x86 since the TLS canary doesn't work before API 18. On the Java side it rewrites bytecode to redirect missing framework calls (API 11-16) to compat shims, generates an AutoCloseable stub for desugared streams, strips incompatible resources.arsc chunks from post-Lollipop, and handles multidex, core library desugaring, and manifest overrideLibrary.

## Setup

```groovy
// settings.gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

```groovy
// build.gradle (root)
plugins {
    id 'io.github.powdead.dexorcist' version '0.2.1' apply false
}
```

```groovy
// build.gradle (app)
plugins {
    id 'com.android.application'
    id 'io.github.powdead.dexorcist'
}

dexorcist {
    enableArmeabi = false       // add armeabi ABI support
    downgradeResources = true   // strip post-Lollipop resource chunks
}
```

Then build with assembleStockRelease or assembleShimmedRelease.

## Requires

AGP 8.x
NDK 28+
dexorcist-shims (added automatically)

## License

Apache 2.0
