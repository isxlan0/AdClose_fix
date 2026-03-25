package com.close.hook.ads.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class LibXposedEntry(
    base: XposedInterface,
    param: XposedModuleInterface.ModuleLoadedParam
) : XposedModule(base, param) {

    init {
        HookLogic.initializeModule(base, param.processName)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        HookLogic.loadPackage(param.packageName, param.isFirstPackage)
    }
}
