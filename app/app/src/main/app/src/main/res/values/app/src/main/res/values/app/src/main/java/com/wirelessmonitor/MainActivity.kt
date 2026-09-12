package com.wirelessmonitor

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
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
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Wireless Monitor"
            textSize = 28f
        }

        val description = TextView(this).apply {
            text = """
                
                OnePlus 13 → OnePlus Pad 2
                
                Use START RECEIVER on the Pad 2.
                Use START SENDER on the OnePlus 13.
            """.trimIndent()
            textSize = 18f
        }

        val senderButton = Button(this).apply {
            text = "START SENDER"
        }

        ipInput = EditText(this).apply {
            hint = "Pad 2 IP address"
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT
        }

        val receiverButton = Button(this).apply {
            text = "START RECEIVER"
        }

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
            try {
                startActivity(
                    Intent(
                        this,
                        ReceiverActivity::class.java
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Receiver error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestScreenCapture() {

        try {

            val manager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            startActivityForResult(
                manager.createScreenCaptureIntent(),
                SCREEN_CAPTURE_REQUEST
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Screen capture error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Compatibility with older Android versions")
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

        if (requestCode != SCREEN_CAPTURE_REQUEST) {
            return
        }

        if (
            resultCode != RESULT_OK ||
            data == null
        ) {
            Toast.makeText(
                this,
                "Screen capture permission denied.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val ip =
            ipInput.text.toString().trim()

        if (ip.isEmpty()) {

            Toast.makeText(
                this,
                "Enter the Pad 2 IP address first.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        try {

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
                        ip
                    )
                }

            startForegroundService(
                serviceIntent
            )

            Toast.makeText(
                this,
                "Sender started.",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Sender error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
