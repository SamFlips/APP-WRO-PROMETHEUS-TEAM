package com.example.wro_prometheus
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UsbPermissionReceiver : BroadcastReceiver() {

    interface UsbPermissionListener {
        fun onPermissionGranted()
        fun onPermissionDenied()
    }

    var listener: UsbPermissionListener? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.example.opencvserial.USB_PERMISSION") {
            val granted = intent.getBooleanExtra("permission", false)
            if (granted) {
                listener?.onPermissionGranted()
            } else {
                listener?.onPermissionDenied()
            }
        }
    }
}