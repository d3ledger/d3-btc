/*
 * Copyright D3 Ledger, Inc. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.d3.btc.monitoring

import com.github.kittinunf.result.Result
import com.google.gson.GsonBuilder
import org.springframework.jmx.export.annotation.ManagedAttribute
import org.springframework.jmx.export.annotation.ManagedResource


private val gson = GsonBuilder().setPrettyPrinting().create()

/*
 *  Class that was developed to help you monitor things
 */
@ManagedResource
abstract class Monitoring {

    /*
     * Returns an object that must monitored
     */
    protected abstract fun monitor(): Any

    /*
     * Takes monitored object and turns it into JSON.
     * This function is used by JMX
     */
    @ManagedAttribute
    fun getMonitoring(): String {
        val objectToMonitor = monitor()
        if (objectToMonitor !is Result<Any, Exception>) {
            return gson.toJson(objectToMonitor)
        }
        return objectToMonitor.fold(
            { data -> gson.toJson(data) },
            { ex -> throw ex })
    }
}
