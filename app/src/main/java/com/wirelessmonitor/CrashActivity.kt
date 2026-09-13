package com.wirelessmonitor

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CrashActivity : Activity() {

    companion object {
        const val EXTRA_STACK_TRACE = "stack_trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace =
            intent.getStringExtra(EXTRA_STACK_TRACE)
                ?: "Unknown error (no stack trace captured)"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val title = TextView(this).apply {
            text = "Wireless Monitor crashed - here's why:"
            textSize = 20f
            setPadding(0, 0, 0, 20)
        }

        val copyButton = Button(this).apply {
            text = "Copy error to clipboard"
        }

        val traceView = TextView(this).apply {
            text = trace
            textSize = 12f
            setPadding(0, 20, 0, 0)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }

        root.addView(title)
        root.addView(copyButton)
        root.addView(traceView)

        setContentView(root)

        copyButton.setOnClickListener {
            val clipboard =
                getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText("crash", trace)
            )

            Toast.makeText(
                this,
                "Copied - paste it in the chat.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
