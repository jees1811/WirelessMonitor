package com.wirelessmonitor

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

class WirelessMonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->

            try {

                val trace = Log.getStackTraceString(throwable)

                val intent =
                    Intent(this, CrashActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                        )
                        putExtra(
                            CrashActivity.EXTRA_STACK_TRACE,
                            trace
                        )
                    }

                startActivity(intent)

            } catch (_: Exception) {
            }

            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}
