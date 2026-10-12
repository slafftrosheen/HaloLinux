package com.halo.sdk.runtime

interface RuntimeLogger {
    fun trace(category: String, message: String)
    fun debug(category: String, message: String)
    fun info(category: String, message: String)
    fun warn(category: String, message: String)
    fun error(category: String, message: String, throwable: Throwable? = null)
}
