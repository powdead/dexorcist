package com.powdead.dexorcist.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class DexorcistExtension {
    abstract val enableArmeabi: Property<Boolean>
    abstract val dexorcistNdkPath: Property<String>
    abstract val downgradeResources: Property<Boolean>
    abstract val splitApk: Property<Boolean>
    abstract val splitAbis: ListProperty<String>

    init {
        enableArmeabi.convention(false)
        dexorcistNdkPath.convention("")
        downgradeResources.convention(true)
        splitApk.convention(false)
        splitAbis.convention(emptyList())
    }
}
