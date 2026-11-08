package dev.mule.session

object SessionIds {
    fun new(): String =
        "s-" + java.util.UUID.randomUUID().toString().substring(0, 8)
}
