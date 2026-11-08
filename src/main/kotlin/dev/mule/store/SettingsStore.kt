package dev.mule.store

import dev.mule.config.AppConfig

interface SettingsStore {
    /** null on first run or an unreadable blob; config migration must never crash. */
    fun load(): AppConfig?
    fun save(cfg: AppConfig)
}
