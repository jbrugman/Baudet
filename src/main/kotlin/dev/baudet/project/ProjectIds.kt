package dev.baudet.project

object ProjectIds {
    fun new(): String =
        "p-" + java.util.UUID.randomUUID().toString().substring(0, 8)
}
