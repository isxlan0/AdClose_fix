package com.close.hook.ads.hook

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class LibXposedEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        HookLogic.initializeModule(this, param.processName)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        HookLogic.loadPackage(param.packageName, param.isFirstPackage)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        HookLogic.loadPackage(param.packageName, param.isFirstPackage)
    }
}
