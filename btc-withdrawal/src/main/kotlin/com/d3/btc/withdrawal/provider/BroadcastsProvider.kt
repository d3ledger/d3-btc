package com.d3.btc.withdrawal.provider

import com.d3.commons.util.GsonInstance
import com.d3.btc.withdrawal.transaction.WithdrawalDetails
import com.d3.commons.sidechain.iroha.consumer.IrohaConsumer
import com.d3.commons.sidechain.iroha.util.IrohaQueryHelper
import com.d3.commons.sidechain.iroha.util.ModelUtil
import com.d3.commons.util.irohaEscape
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.map
import com.google.gson.Gson
import mu.KLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Provider that is used to mark withdrawals as 'broadcasted'
 */
@Component
class BroadcastsProvider(
    @Qualifier("broadcastsIrohaConsumer")
    private val broadcastsIrohaConsumer: IrohaConsumer,
    @Qualifier("withdrawalQueryHelper")
    private val withdrawalQueryHelper: IrohaQueryHelper
) {

    private val gson = GsonInstance.get()
    /**
     * Checks if given withdrawal has been broadcasted before
     * @param withdrawalDetails - details of withdrawal
     * @return true if given withdrawal has been broadcasted before
     */
    fun hasBeenBroadcasted(withdrawalDetails: WithdrawalDetails): Result<Boolean, Exception> {
        logger.info("Check if withdrawal $withdrawalDetails (hash ${withdrawalDetails.irohaFriendlyHashCode()}) has been broadcasted")
        return hasBeenBroadcasted(withdrawalDetails.irohaFriendlyHashCode())
    }

    /**
     * Checks if given withdrawal has been broadcasted before
     * @param withdrawalHash - hash code of withdrawal
     * @return true if given withdrawal has been broadcasted before
     */
    fun hasBeenBroadcasted(withdrawalHash: String): Result<Boolean, Exception> {
        return withdrawalQueryHelper.getAccountDetails(
            broadcastsIrohaConsumer.creator,
            broadcastsIrohaConsumer.creator,
            withdrawalHash
        ).map { value ->
            val broadcasted = value.isPresent
            if (broadcasted) {
                logger.info("Withdrawal with hash $withdrawalHash has been broadcasted already")
            } else {
                logger.info("Withdrawal with hash $withdrawalHash hasn't been broadcasted yet")
            }
            broadcasted
        }
    }

    /**
     * Marks given withdrawal as 'broadcasted'
     * @param withdrawalDetails - withdrawal to mark
     * @return result of operation
     */
    fun markAsBroadcasted(withdrawalDetails: WithdrawalDetails): Result<Unit, Exception> {
        logger.info("Mark withdrawal $withdrawalDetails as 'broadcasted'")
        return ModelUtil.setAccountDetail(
            broadcastsIrohaConsumer,
            broadcastsIrohaConsumer.creator,
            withdrawalDetails.irohaFriendlyHashCode(),
            gson.toJson(withdrawalDetails).irohaEscape()
        ).map { Unit }
    }

    companion object : KLogging()
}