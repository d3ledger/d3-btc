package com.d3.btc.helper.format

import com.google.gson.Gson

/**
 * Gson object holder
 */
object GsonInstance {
    private val gson = Gson()

    fun get() = gson
}