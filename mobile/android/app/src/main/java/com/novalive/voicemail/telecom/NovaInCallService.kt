package com.novalive.voicemail.telecom

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * NovaInCallService
 * 
 * Bound when Nova is the active Phone / Dialer service.
 * Automatically handles ringing calls on the last ring:
 * 1. Counts down ring timeout via NovaAutoAnswerService.
 * 2. Answers call via call.answer(0).
 * 3. Speaks AI busy message.
 * 4. Hangs up via call.disconnect().
 */
class NovaInCallService : InCallService() {

    companion object {
        private const val TAG = "NovaInCallService"
        var activeCall: Call? = null
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.d(TAG, "InCallService Call state changed to $state")
            if (state == Call.STATE_DISCONNECTED) {
                activeCall = null
                CallStateTracker.onCallEnded()
                NovaAutoAnswerService.notifyCallEnded(applicationContext)
            } else if (state == Call.STATE_ACTIVE) {
                CallStateTracker.onCallAnswered()
                NovaAutoAnswerService.notifyCallAnsweredManually(applicationContext)
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall = call
        call.registerCallback(callCallback)

        val handle = call.details.handle
        val phoneNumber = handle?.schemeSpecificPart ?: "Unknown"
        val displayName = call.details.callerDisplayName ?: phoneNumber

        CallStateTracker.onCallReceived(phoneNumber, displayName)

        if (call.state == Call.STATE_RINGING) {
            Log.i(TAG, "InCallService: call ringing for $phoneNumber, starting NovaAutoAnswerService")
            NovaAutoAnswerService.startForRingingCall(applicationContext, phoneNumber, displayName)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (activeCall == call) {
            activeCall = null
        }
        CallStateTracker.onCallEnded()
        NovaAutoAnswerService.notifyCallEnded(applicationContext)
    }

    fun answerCall() {
        activeCall?.answer(0)
    }

    fun declineCall() {
        activeCall?.reject(Call.REJECT_REASON_DECLINED)
    }
}
