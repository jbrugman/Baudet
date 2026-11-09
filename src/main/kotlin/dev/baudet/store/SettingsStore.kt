package dev.baudet.store

import dev.baudet.config.AppConfig

interface SettingsStore {
    /** null on first run or an unreadable blob; config migration must never crash. */
    fun load(): AppConfig?
    fun save(cfg: AppConfig)
}
