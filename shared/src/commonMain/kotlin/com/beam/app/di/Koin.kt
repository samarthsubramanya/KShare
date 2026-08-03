package com.beam.app.di

import org.koin.core.context.startKoin
import org.koin.dsl.module

/** Empty for now — populated with discovery/transport/db modules in later phases. */
val coreModule = module {}

fun initKoin() {
    startKoin {
        modules(coreModule)
    }
}
