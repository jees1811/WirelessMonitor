package com.wirelessmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    companion object {
        private const val SCREEN_CAPTURE_REQUEST = 1001
        private const val AUDIO_PERMISSION_REQUEST = 2001
    }

    private enum class Quality(
        val label: String,
        val maxDimension: Int,
        val bitrate: Int
    ) {
        HIGH("High (1080p)", 1920, 16_000_000),
        MEDIUM("Medium (720p)", 1280, 8_000_000),
        LOW("Low (480p, least lag)", 854, 4_000_000)
    }

    private lateinit var ipInput: EditText
    private var selectedQuality = Quality.HIGH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

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
                
                Open START RECEIVER on the Pad 2 first.
                Then tap START SENDER here - it will try to
                find the Pad automatically. If it can't, type
                the Pad's IP address below.
            """.trimIndent()
            textSize = 18f
        }

        val qualityLabel = TextView(this).apply {
            text = "Quality"
            textSize = 18f
            setPadding(0, 30, 0, 0)
        }

        val qualityGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        Quality.entries.forEach { quality ->
            val button = RadioButton(this).apply {
                text = quality.label
                isChecked = quality == Quality.HIGH
                setOnClickListener {
                    selectedQuality = quality
                }
            }
            qualityGroup.addView(button)
        }

        val senderButton = Button(this).apply { text = "START SENDER" }

        ipInput = EditText(this).apply {
            hint = "Pad 2 IP address (optional - auto-detected if blank)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val receiverButton = Button(this).apply { text = "START RECEIVER" }

        root.addView(title)
        root.addView(description)
        root.addView(qualityLabel)
        root.addView(qualityGroup)
        root.addView(senderButton)
        root.addView(ipInput)
        root.addView(receiverButton)

        setContentView(root)

        senderButton.setOnClickListener { startSenderFlow() }

        receiverButton.setOnClickListener {
            try {
                startActivity(Intent(this, ReceiverActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "Receiver error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startSenderFlow() {

        val manualIp = ipInput.text.toString().trim()

        if (manualIp.isNotEmpty()) {
            proceedWithAudioPermission()
            return
        }

        Toast.makeText(this, "Looking for the Pad on Wi-Fi...", Toast.LENGTH_SHORT).show()

        NsdHelper.discoverService(
            context = this,
            onFound = { host, _ ->
                runOnUiThread {
                    ipInput.setText(host)
                    Toast.makeText(this, "Found Pad 2 at $host", Toast.LENGTH_SHORT).show()
                    proceedWithAudioPermission()
                }
            },
            onFailure = {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Couldn't auto-find the Pad. Make sure START RECEIVER is open on the Pad and both devices are on the same Wi-Fi, or type the Pad's IP manually.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun proceedWithAudioPermission() {

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST
            )
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "Screen capture error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Compatibility with older Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != SCREEN_CAPTURE_REQUEST) return

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_LONG).show()
            return
        }

        val ip = ipInput.text.toString().trim()

        if (ip.isEmpty()) {
            Toast.makeText(this, "Enter or auto-find the Pad 2 IP address first.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_RECEIVER_IP, ip)
                putExtra(ScreenCaptureService.EXTRA_MAX_DIMENSION, selectedQuality.maxDimension)
                putExtra(ScreenCaptureService.EXTRA_VIDEO_BITRATE, selectedQuality.bitrate)
            }

            startForegroundService(serviceIntent)

            Toast.makeText(this, "Sender started.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Sender error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}