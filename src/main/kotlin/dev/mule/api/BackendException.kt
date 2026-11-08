package dev.mule.api

import dev.mule.i18n.Key
import dev.mule.i18n.Texts

class BackendException(status: Int, message: String) :
    RuntimeException(Texts.fmt(Key.HttpFromBackend, status, message))
