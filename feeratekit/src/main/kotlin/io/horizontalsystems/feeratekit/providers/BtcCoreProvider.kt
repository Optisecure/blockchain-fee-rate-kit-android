package io.horizontalsystems.feeratekit.providers

import android.util.Base64
import android.util.Log
import com.eclipsesource.json.JsonArray
import com.eclipsesource.json.JsonObject
import io.horizontalsystems.feeratekit.model.Coin
import io.horizontalsystems.feeratekit.model.FeeProviderConfig
import io.horizontalsystems.feeratekit.model.FeeRate
import io.horizontalsystems.feeratekit.utils.HttpUtils
import io.reactivex.Single
import io.reactivex.functions.Function3
import java.util.*
import java.util.logging.Logger

/* Smart Fee RPC Response
         {
            "error": null,
            "id": "curltest",
            "result": {
                "blocks": 10,
                "feerate": 0.00019212
            }
        }
    */

/**
 * Bitcoin-Core RPC Fee provider
 */
class BtcCoreProvider(private val providerConfig: FeeProviderConfig) : IFeeRateProvider {

    private val logger = Logger.getLogger("BtcCoreProvider")

    private val LOW_PRIORITY_BLOCKS = 100
    private val MEDIUM_PRIORITY_BLOCKS = 10
    private val HIGH_PRIORITY_BLOCKS = 1

    private fun getEstimatedSmartFee(priorityInNumberOfBlocks: Int): Single<Float> {
        return Single.create { subscriber ->
            try {
                val jsonArray = JsonArray()
                var basicAuth: String? = null

                jsonArray.add(priorityInNumberOfBlocks)

                val requestData = JsonObject().apply {
                    this["jsonrpc"] = "2.0"
                    this["method"] = "estimatesmartfee"
                    this["params"] = jsonArray
                    this["id"] = 1
                }

                logger.info("Request feeRate for Bitcoin $requestData")


                providerConfig.btcCoreRpcUSer?.let {
                    val userCredentials = "${providerConfig.btcCoreRpcUSer}:${providerConfig.btcCoreRpcPassword}"
                    basicAuth = "Basic " + String(Base64.encode(userCredentials.toByteArray(), Base64.DEFAULT))
                }

                providerConfig.btcCoreRpcUrl?.let {
                    val response = HttpUtils.post(providerConfig.btcCoreRpcUrl, requestData.toString(), basicAuth)
                    val responseObject = response.asObject()
                    var fee: Float

                    if (responseObject["result"].asObject()["feerate"] != null)
                        fee = responseObject["result"].asObject()["feerate"].asFloat()
                    else
                        fee = responseObject["result"].asObject()["fee"].asFloat()

                    subscriber.onSuccess(fee)
                }

            } catch (e: Exception) {
                Log.e("Bitcoin-Core", "exception", e)
                subscriber.onError(e)
            }
        }
    }

    override fun getFeeRates(): Single<FeeRate> {

        return Single.zip(getEstimatedSmartFee(LOW_PRIORITY_BLOCKS),
                          getEstimatedSmartFee(MEDIUM_PRIORITY_BLOCKS),
                          getEstimatedSmartFee(HIGH_PRIORITY_BLOCKS),
                          Function3<Float, Float, Float, Triple<Float, Float, Float>> { t1, t2, t3 ->
                              Triple(
                                      t1,
                                      t2,
                                      t3
                              )
                          })
                .map {
                    val coin = Coin.BITCOIN
                    val defaultRate = coin.defaultRate()
                    FeeRate(
                            coin = coin,
                            lowPriority = feeInSatoshiPerByte(it.first),
                            lowPriorityDuration = defaultRate.lowPriorityDuration,
                            mediumPriority = feeInSatoshiPerByte(it.second),
                            mediumPriorityDuration = defaultRate.mediumPriorityDuration,
                            highPriority = feeInSatoshiPerByte(it.third),
                            highPriorityDuration = defaultRate.highPriorityDuration,
                            date = Date().time / 1000
                    )
                }
    }

    private fun feeInSatoshiPerByte(btcPerKbyte: Float): Long {
        (btcPerKbyte * 100_000_000 / 1024).toLong().let {
            if (it < 1)
                return 1
            else
                return it
        }
    }
}
