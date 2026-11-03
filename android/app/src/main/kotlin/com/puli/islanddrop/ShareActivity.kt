package com.puli.islanddrop

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Handles Android's system share sheet ("Share → Island Drop") — the "AirDrop to Mac" screen. */
class ShareActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var finder: MacFinder
    private var uris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uris = when (intent?.action) {
            android.content.Intent.ACTION_SEND ->
                (intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM))?.let { listOf(it) } ?: emptyList()
            android.content.Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }

        if (uris.isEmpty()) {
            finish()
            return
        }

        buildUi()
        finder = MacFinder(this) { services -> runOnUiThread { showDevices(services) } }
        finder.start()
    }

    override fun onDestroy() {
        if (::finder.isInitialized) finder.stop()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            gravity = Gravity.BOTTOM
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#1c1c1e"))
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        sheet.addView(TextView(this).apply {
            text = if (uris.size == 1) "1 item" else "${uris.size} items"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        sheet.addView(TextView(this).apply {
            text = "AIRDROP TO MAC"
            setTextColor(Color.parseColor("#98989f"))
            textSize = 12f
            setPadding(0, dp(16), 0, dp(8))
        })
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sheet.addView(listContainer)
        listContainer.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER }
        })

        sheet.addView(TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#0A84FF"))
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, 0)
            setOnClickListener { finish() }
        })

        root.addView(sheet)
        setContentView(root)
    }

    private fun showDevices(services: List<NsdServiceInfo>) {
        listContainer.removeAllViews()
        if (services.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Looking for your Mac…"
                setTextColor(Color.parseColor("#98989f"))
                gravity = Gravity.CENTER_HORIZONTAL
            })
            return
        }
        services.forEach { service ->
            val row = TextView(this).apply {
                text = service.serviceName
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { sendTo(service) }
            }
            listContainer.addView(row)
        }
    }

    private fun sendTo(service: NsdServiceInfo) {
        finder.resolve(service) { host, port ->
            if (host == null) {
                Toast.makeText(this, "Couldn't reach ${service.serviceName}", Toast.LENGTH_SHORT).show()
                return@resolve
            }
            val infos = uris.map { Outgoing.describe(this, it) }
            Thread {
                MacSender.send(this, host, port, Outgoing.Files(uris, infos)) { event ->
                    when (event) {
                        is MacSender.Event.Done -> {
                            Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        is MacSender.Event.Declined -> {
                            Toast.makeText(this, "Declined on the Mac", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        is MacSender.Event.Failed -> {
                            Toast.makeText(this, event.msg, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        else -> {}
                    }
                }
            }.start()
        }
    }
}
