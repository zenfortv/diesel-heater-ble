package io.github.zenfortv.dieselheater

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup.LayoutParams
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The app's screen: the same web page, loaded from the service on 127.0.0.1, plus permissions
 *  and a native heater picker (window.HeaterApp.pickHeater() from the page). */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("heater", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = 0xff120f0d.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "HeaterApp")
        web.webViewClient = object : WebViewClient() {
            // the service may still be starting: retry until its page answers
            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) v.postDelayed({ v.reload() }, 700)
            }
        }
        setContentView(web)
        if (prefs.getInt("terms", 0) >= TERMS_VERSION) proceed() else showTerms()
    }

    private fun proceed() {
        val missing = needed()
        if (missing.isEmpty()) ready() else requestPermissions(missing, 1)
    }

    // nothing connects to the heater until the user has accepted web/DISCLAIMER.md
    private fun showTerms() {
        stopService(Intent(this, HeaterService::class.java))
        val text = assets.open("DISCLAIMER.md").bufferedReader().readText().lines()
            .filter { it.trim() != "---" }
            .joinToString("\n") { it.replace(Regex("^#+ "), "").replace("**", "") }
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val scroll = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply { this.text = text; textSize = 14f; setPadding(pad, pad / 2, pad, pad) })
        }
        val check = CheckBox(this).apply {
            this.text = "I have read and accept these terms. I use this app entirely at my own risk."
            setPadding(pad / 2, pad / 2, pad, pad / 2)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt()))
            addView(check)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Terms of use and safety")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Accept", null)
            .setNegativeButton("Decline") { _, _ ->
                prefs.edit().putBoolean("quit", true).apply()
                toast("Terms declined: the app will not run.")
                finishAndRemoveTask()
            }
            .create()
        dlg.setOnShowListener {
            val yes = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            yes.isEnabled = false
            check.setOnCheckedChangeListener { _, c -> yes.isEnabled = c }
            yes.setOnClickListener {
                prefs.edit().putInt("terms", TERMS_VERSION).apply()
                dlg.dismiss()
                proceed()
            }
        }
        dlg.show()
    }

    private fun needed(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_SCAN; p += Manifest.permission.BLUETOOTH_CONNECT }
        else p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = ready()

    private fun btAllowed() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ready() {
        if (!btAllowed()) {
            web.loadData("""<body style="background:#120f0d;color:#f5eee8;font:17px system-ui;padding:24px">
                <h2>Bluetooth permission needed</h2><p>The app needs "Nearby devices" to talk to the heater.
                Allow it in Settings &gt; Apps &gt; Diesel Heater &gt; Permissions, then reopen the app.</p></body>""", "text/html", "utf-8")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        HeaterService.start(this)
        web.loadUrl("http://127.0.0.1:${HeaterService.PORT}/")
        askBatteryOnce()
        if (prefs.getString("address", "").isNullOrEmpty()) web.postDelayed({ pickHeater() }, 1500)
    }

    // Doze would otherwise pause the service at night
    @SuppressLint("BatteryLife")
    private fun askBatteryOnce() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun pickHeater() {
        val ad = getSystemService(BluetoothManager::class.java)?.adapter
        if (ad == null) { toast("This phone has no Bluetooth"); return }
        if (!ad.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        val scanner = ad.bluetoothLeScanner ?: return
        val addrs = mutableListOf<String>()
        val names = mutableListOf<String>()
        val items = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        fun heaterLike(n: String) = n.startsWith("BYD", true) || n.contains("heater", true)
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) = runOnUiThread {
                val n = r.scanRecord?.deviceName ?: r.device.name ?: return@runOnUiThread
                val a = r.device.address
                if (a in addrs) return@runOnUiThread
                // heaters first
                val at = if (heaterLike(n)) names.count { heaterLike(it) } else addrs.size
                addrs.add(at, a); names.add(at, n)
                items.insert((if (heaterLike(n)) "🔥 " else "") + "$n\n$a", at)
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Choose your heater (name like BYD-…)")
            .setAdapter(items) { _, i ->
                val name = names[i]
                val svc = HeaterService.instance
                if (svc != null) svc.heater.setDevice(addrs[i], name)
                else { prefs.edit().putString("address", addrs[i]).putString("name", name).apply(); HeaterService.start(this) }
                toast("Connecting to $name…")
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { try { scanner.stopScan(cb) } catch (_: Exception) {} }
            .create()
        dlg.show()
        try { scanner.startScan(cb) } catch (e: Exception) { toast("Scan failed: ${e.message}") }
        web.postDelayed({ try { scanner.stopScan(cb) } catch (_: Exception) {} }, 20000)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    @Deprecated("system back")
    override fun onBackPressed() { moveTaskToBack(true) }  // keep the page; the service runs anyway

    companion object { const val TERMS_VERSION = 1 }  // raise when web/DISCLAIMER.md changes

    inner class Bridge {
        @JavascriptInterface fun pickHeater() = runOnUiThread { this@MainActivity.pickHeater() }
    }
}
