package com.wirelessmonitor

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val SCREEN_CAPTURE_REQUEST = 1001
    }

    private lateinit var ipInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 40, 40, 40)

        val title = TextView(this)
        title.text = "Wireless Monitor"
        title.textSize = 28f

        val description = TextView(this)
        description.text =
            "\nOnePlus 13 → OnePlus Pad 2\n\n" +
            "Choose Sender on the OnePlus 13.\n" +
            "Choose Receiver on the Pad 2."

        val senderButton = Button(this)
        senderButton.text = "START SENDER (OnePlus 13)"

        ipInput = EditText(this)
        ipInput.hint = "Pad 2 IP address"
        ipInput.inputType =
            android.text.InputType.TYPE_CLASS_PHONE

        val receiverButton = Button(this)
        receiverButton.text = "START RECEIVER (Pad 2)"

        root.addView(title)
        root.addView(description)
        root.addView(senderButton)
        root.addView(ipInput)
        root.addView(receiverButton)

        setContentView(root)

        senderButton.setOnClickListener {
            requestScreenCapture()
        }

        receiverButton.setOnClickListener {
            startReceiver()
        }
    }

    private fun requestScreenCapture() {
        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        val intent = manager.createScreenCaptureIntent()

        startActivityForResult(
            intent,
            SCREEN_CAPTURE_REQUEST
        )
    }

    @Deprecated("Deprecated Android API used for broad compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode == SCREEN_CAPTURE_REQUEST) {

            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(
                    this,
                    "Screen capture permission was not granted.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val serviceIntent =
                Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    putExtra(
                        ScreenCaptureService.EXTRA_RESULT_CODE,
                        resultCode
                    )

                    putExtra(
                        ScreenCaptureService.EXTRA_DATA,
                        data
                    )

                    putExtra(
                        ScreenCaptureService.EXTRA_RECEIVER_IP,
                        ipInput.text.toString().trim()
                    )
                }

            startForegroundService(serviceIntent)

            Toast.makeText(
                this,
                "Sender started.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startReceiver() {
        val intent =
            Intent(
                this,
                ReceiverActivity::class.java
            )

        startActivity(intent)
    }
}
