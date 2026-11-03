package com.puli.islanddrop

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var root: LinearLayout

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) sendFilesToMac(uris)
    }

    private val requestNotifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        rebuildSetupCard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.start(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        rebuildSetupCard()
        updateStatus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#1c1c1e"))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        layoutParams = lp
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#98989f"))
        textSize = 13f
        setPadding(dp(4), dp(20), 0, dp(6))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layoutParams = lp
    }

    private fun row(title: String, subtitle: String, checked: Boolean): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            this.text = title
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        texts.addView(TextView(this).apply {
            this.text = subtitle
            setTextColor(Color.parseColor("#98989f"))
            textSize = 13f
        })
        r.addView(texts)
        r.addView(TextView(this).apply {
            this.text = if (checked) "✓" else ""
            setTextColor(Color.parseColor("#30d158"))
            textSize = 18f
        })
        return r
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#2c2c2e"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.BLACK)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Island Drop"
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Files from your Mac, straight to your phone."
            setTextColor(Color.parseColor("#98989f"))
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        })

        // Status card
        val statusCard = card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.topMargin = dp(20)
        }
        val radar = RadarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140))
        }
        statusCard.addView(radar)
        statusTitle = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        statusCard.addView(statusTitle)
        statusSub = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#98989f"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(statusSub)
        root.addView(statusCard)

        root.addView(sectionHeader("SETUP"))
        root.addView(card().apply { tag = "setup_card" })

        root.addView(sectionHeader("OPTIONS"))
        val optionsCard = card()
        val autoAcceptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val autoTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        autoTexts.addView(TextView(this).apply {
            text = "Auto-accept from my Mac"
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        autoTexts.addView(TextView(this).apply {
            text = "Files save immediately, the card still pops up"
            setTextColor(Color.parseColor("#98989f"))
            textSize = 13f
        })
        autoAcceptRow.addView(autoTexts)
        val autoSwitch = Switch(this).apply {
            isChecked = Prefs.autoAccept(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutoAccept(this@MainActivity, checked) }
        }
        autoAcceptRow.addView(autoSwitch)
        optionsCard.addView(autoAcceptRow)
        root.addView(optionsCard)

        root.addView(button("Send Files to Mac", filled = true).apply {
            setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        })
        root.addView(button("Connect iPhone", filled = true).apply {
            setOnClickListener { showConnectIphone() }
        })
        root.addView(TextView(this).apply {
            text = "Preview Pop-up"
            setTextColor(Color.parseColor("#0A84FF"))
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { previewPopup() }
        })

        rebuildSetupCard()
    }

    private fun button(label: String, filled: Boolean): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 17f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = dp(15).toFloat()
            setColor(Color.parseColor("#0A84FF"))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        lp.topMargin = dp(14)
        layoutParams = lp
    }

    private fun rebuildSetupCard() {
        val setupCard = root.findViewWithTag<LinearLayout>("setup_card") ?: return
        setupCard.removeAllViews()

        val notifsGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val overlayGranted = Settings.canDrawOverlays(this)
        val batteryIgnored = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        setupCard.addView(row("Notifications", "Get alerts when files arrive", notifsGranted).apply {
            setOnClickListener {
                if (!notifsGranted && Build.VERSION.SDK_INT >= 33) {
                    requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        })
        setupCard.addView(divider())
        setupCard.addView(row("Pop up over apps", "Slide-up card like AirPods", overlayGranted).apply {
            setOnClickListener {
                if (!overlayGranted) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        setupCard.addView(divider())
        setupCard.addView(row("Wake on lock screen", "Turn the screen on for incoming files", true))
        setupCard.addView(divider())
        setupCard.addView(row("Always ready", "Don't let the battery saver stop the receiver", batteryIgnored).apply {
            setOnClickListener {
                if (!batteryIgnored) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }
        })
    }

    private fun updateStatus() {
        val name = ReceiverService.visibleAs ?: deviceName(this)
        statusTitle.text = ReceiverService.status.ifBlank { "Ready to receive" }
        statusSub.text = "Visible to your Mac as “$name”"
    }

    private fun sendFilesToMac(uris: List<Uri>) {
        lateinit var finder: MacFinder
        finder = MacFinder(this) { services ->
            val first = services.firstOrNull() ?: return@MacFinder
            finderResolveAndSend(finder, first, uris)
        }
        finder.start()
        Toast.makeText(this, "Looking for your Mac…", Toast.LENGTH_SHORT).show()
        // Stop scanning after a few seconds regardless.
        root.postDelayed({ finder.stop() }, 8000)
    }

    private fun finderResolveAndSend(finder: MacFinder, service: android.net.nsd.NsdServiceInfo, uris: List<Uri>) {
        finder.resolve(service) { host, port ->
            finder.stop()
            if (host == null) {
                Toast.makeText(this, "Couldn't reach the Mac", Toast.LENGTH_SHORT).show()
                return@resolve
            }
            val infos = uris.map { Outgoing.describe(this, it) }
            Thread {
                MacSender.send(this, host, port, Outgoing.Files(uris, infos)) { event ->
                    when (event) {
                        is MacSender.Event.Done -> Toast.makeText(this, "Sent to Mac", Toast.LENGTH_SHORT).show()
                        is MacSender.Event.Declined -> Toast.makeText(this, "Declined on the Mac", Toast.LENGTH_SHORT).show()
                        is MacSender.Event.Failed -> Toast.makeText(this, event.msg, Toast.LENGTH_SHORT).show()
                        else -> {}
                    }
                }
            }.start()
        }
    }

    private fun showConnectIphone() {
        val url = WebBridge.url(this)
        val message = if (url != null) {
            "Open this address in Safari on the iPhone (same Wi-Fi network):\n\n$url"
        } else {
            "Connect to Wi-Fi first, then try again."
        }
        AlertDialog.Builder(this)
            .setTitle("Connect iPhone")
            .setMessage(message)
            .setPositiveButton("Copy link") { _, _ ->
                if (url != null) {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Island Drop", url))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun previewPopup() {
        val demo = Transfer(
            Transfer.nextId(), "Pulidevan's MacBook Pro", Transfer.Kind.FILES,
            listOf(FileInfo("Quarterly Report.pdf", 1_932_000, "application/pdf")),
            1_932_000, demo = true
        )
        Presenter.present(this, demo)
        Toast.makeText(this, "Sent a preview notification", Toast.LENGTH_SHORT).show()
    }
}
