package com.qwe7002.telegram_sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.sumimakito.codeauxlib.CodeauxLibPortable
import com.google.gson.Gson
import com.qwe7002.telegram_sms.config.proxy
import com.qwe7002.telegram_sms.data_structure.sendMessageBody
import com.qwe7002.telegram_sms.static_class.log
import com.qwe7002.telegram_sms.static_class.network
import com.qwe7002.telegram_sms.static_class.other
import com.qwe7002.telegram_sms.static_class.resend
import com.qwe7002.telegram_sms.static_class.service
import com.qwe7002.telegram_sms.static_class.sms
import com.qwe7002.telegram_sms.static_class.ussd
import com.qwe7002.telegram_sms.value.constValue
import io.paperdb.Paper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects

class SMSReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        Paper.init(context)
        val TAG = "sms_receiver"
        Log.d(TAG, "Receive action: " + intent.action)
        val extras = intent.extras!!
        val sharedPreferences = context.getSharedPreferences("data", Context.MODE_PRIVATE)
        if (!sharedPreferences.getBoolean("initialized", false)) {
            Log.i(TAG, "Uninitialized, SMS receiver is deactivated.")
            return
        }
        val isDefaultSmsApp = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED" && isDefaultSmsApp) {
            //When it is the default application, it will receive two broadcasts.
            Log.i(TAG, "reject: android.provider.Telephony.SMS_RECEIVED.")
            return
        }
        val botToken = sharedPreferences.getString("bot_token", "")
        val chatId = sharedPreferences.getString("chat_id", "")
        val messageThreadId = sharedPreferences.getString("message_thread_id", "")
        val requestUri = network.getUrl(botToken, "sendMessage")

        var intentSlot = extras.getInt("slot", -1)
        val subId = extras.getInt("subscription", -1)
        if (other.getActiveCard(context) >= 2 && intentSlot == -1) {
            val manager = SubscriptionManager.from(context)
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val info = manager.getActiveSubscriptionInfo(subId)
                intentSlot = info.simSlotIndex
            }
        }
        val slot = intentSlot
        val dualSim = other.getDualSimCardDisplay(
            context,
            intentSlot,
            sharedPreferences.getBoolean("display_dual_sim_display_name", false)
        )

        val pdus = (extras["pdus"] as Array<*>?)!!
        val messages = arrayOfNulls<SmsMessage>(
            pdus.size
        )
        for (i in pdus.indices) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                messages[i] =
                    SmsMessage.createFromPdu(pdus[i] as ByteArray, extras.getString("format"))
            } else {
                messages[i] = SmsMessage.createFromPdu(pdus[i] as ByteArray)
            }
        }
        if (messages.isEmpty()) {
            log.writeLog(context, "Message length is equal to 0.")
            return
        }

        val messageBodyBuilder = StringBuilder()
        for (item in messages) {
            messageBodyBuilder.append(item!!.messageBody)
        }
        val messageBody = messageBodyBuilder.toString()

        val messageAddress = messages[0]!!.originatingAddress!!
        val trustedPhoneNumber = sharedPreferences.getString("trusted_phone_number", null)
        var isTrustedPhone = false
        if (!trustedPhoneNumber.isNullOrEmpty()) {
            isTrustedPhone = messageAddress.contains(trustedPhoneNumber)
        }
        val requestBody = sendMessageBody()
        requestBody.chat_id = chatId
        requestBody.message_thread_id = messageThreadId

        var messageBodyHtml = messageBody
        val messageHead = """
            [$dualSim${context.getString(R.string.receive_sms_head)}]
            ${context.getString(R.string.from)}$messageAddress
            ${context.getString(R.string.content)}
            """.trimIndent()
        var rawRequestBodyText = messageHead + messageBody
        var isVerificationCode = false
        if (sharedPreferences.getBoolean("verification_code", false) && !isTrustedPhone) {
            if (messageBody.length <= 140) {
                val verification = CodeauxLibPortable.find(context,messageBody)
                if (verification != null) {
                    requestBody.parse_mode = "html"
                    messageBodyHtml = messageBody
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("&", "&amp;")
                        .replace(verification, "<code>$verification</code>")
                    isVerificationCode = true
                }
            } else {
                log.writeLog(
                    context,
                    "SMS exceeds 140 characters, no verification code is recognized."
                )
            }
        }
        requestBody.text = messageHead + messageBodyHtml
        if (isTrustedPhone) {
            log.writeLog(context, "SMS from trusted mobile phone detected")
            val message_command =
                messageBody.lowercase(Locale.getDefault()).replace("_", "").replace("-", "")
            val command_list =
                message_command.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (command_list.size > 0) {
                val message_list =
                    messageBody.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                when (command_list[0].trim { it <= ' ' }) {
                    "/restartservice" -> {
                        Thread {
                            service.stopAllService(context)
                            service.startService(
                                context,
                                sharedPreferences.getBoolean("battery_monitoring_switch", false),
                                sharedPreferences.getBoolean("chat_command", false)
                            )
                        }.start()
                        rawRequestBodyText = """
                        ${context.getString(R.string.system_message_head)}
                        ${context.getString(R.string.restart_service)}
                        """.trimIndent()
                        requestBody.text = rawRequestBodyText
                    }

                    "/sendsms", "/sendsms1", "/sendsms2" -> {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.SEND_SMS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.i(TAG, "No SMS permission.")
                            return
                        }
                        val messageInfo =
                            message_list[0].split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()
                        if (messageInfo.size == 2) {
                            val msgSendTo = other.getSendPhoneNumber(messageInfo[1])
                            if (other.isPhoneNumber(msgSendTo)) {
                                val msgSendContent = StringBuilder()
                                var i = 2
                                while (i < message_list.size) {
                                    if (i != 2) {
                                        msgSendContent.append("\n")
                                    }
                                    msgSendContent.append(message_list[i])
                                    ++i
                                }
                                var sendSlot = slot
                                if (other.getActiveCard(context) > 1) {
                                    when (command_list[0].trim { it <= ' ' }) {
                                        "/sendsms1" -> sendSlot = 0
                                        "/sendsms2" -> sendSlot = 1
                                    }
                                }
                                Thread {
                                    sms.sendSms(
                                        context,
                                        msgSendTo,
                                        msgSendContent.toString(),
                                        sendSlot,
                                        other.getSubId(context, sendSlot)
                                    )
                                }
                                    .start()
                                return
                            }
                        }
                    }

                    "/sendussd" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CALL_PHONE
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            if (message_list.size == 2) {
                                ussd.sendUssd(context, message_list[1], subId)
                                return
                            }
                        }
                    } else {
                        Log.i(TAG, "send_ussd: No permission.")
                        return
                    }
                }
            }
        }

        if (!isVerificationCode && !isTrustedPhone) {
            val blackListArray =
                Paper.book("system_config").read("block_keyword_list", ArrayList<String>())!!
            for (blackListItem in blackListArray) {
                if (blackListItem.isEmpty()) {
                    continue
                }

                var isSpam = false
                if(blackListItem.startsWith("regex:")) {
                    val blackListItemRegex = Regex(blackListItem.substring(6))
                    isSpam = blackListItemRegex.containsMatchIn(messageBody)
                }
                else {
                    isSpam = messageBody.contains(blackListItem)
                }

                if (isSpam) {
                    val simpleDateFormat =
                        SimpleDateFormat(context.getString(R.string.time_format), Locale.UK)
                    val writeMessage = """
                        ${requestBody.text}
                        ${context.getString(R.string.time)}${simpleDateFormat.format(Date(System.currentTimeMillis()))}
                        """.trimIndent()
                    Paper.init(context)
                    val spamSmsList = Paper.book().read("spam_sms_list", ArrayList<String>())!!
                    if (spamSmsList.size >= 5) {
                        spamSmsList.removeAt(0)
                    }
                    spamSmsList.add(writeMessage)
                    Paper.book().write("spam_sms_list", spamSmsList)
                    Log.i(TAG, "Detected message contains blacklist keywords, add spam list")
                    return
                }
            }
        }


        val body: RequestBody = RequestBody.create(constValue.JSON,Gson().toJson(requestBody) )
        val okhttpObj = network.getOkhttpObj(
            sharedPreferences.getBoolean("doh_switch", true),
            Paper.book("system_config").read("proxy_config", proxy())
        )
        val request: Request = Request.Builder().url(requestUri).method("POST", body).build()
        val call = okhttpObj.newCall(request)
        val errorHead = "Send SMS forward failed:"
        val requestBodyText = rawRequestBodyText
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                log.writeLog(context, errorHead + e.message)
                sms.fallbackSMS(context, requestBodyText, subId)
                resend.addResendLoop(context, requestBody.text)
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                val result = Objects.requireNonNull(response.body).string()
                if (response.code != 200) {
                    log.writeLog(context, errorHead + response.code + " " + result)
                    sms.fallbackSMS(context, requestBodyText, subId)
                    resend.addResendLoop(context, requestBody.text)
                } else {
                    if (!other.isPhoneNumber(messageAddress)) {
                        log.writeLog(context, "[$messageAddress] Not a regular phone number.")
                        return
                    }
                    other.addMessageList(other.getMessageId(result), messageAddress, slot)
                }
            }
        })
    }

}


