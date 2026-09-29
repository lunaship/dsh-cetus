package dev.deeplinks.devices
import dev.deeplinks.R
import dev.deeplinks.native.WorkspaceActivity
import dev.deeplinks.core.DeviceName
import dev.deeplinks.core.EXTRA_AUTH_NOTICE
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.PairClient
import dev.deeplinks.core.PinnedSsl
import dev.deeplinks.core.applyDshSecureWindow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowInsetsControllerCompat
import dev.deeplinks.core.enableDshEdgeToEdge
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import java.util.concurrent.Executors
import java.util.UUID

class ScanActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var pairingOverlay: View
    private lateinit var pairingStatus: TextView
    private lateinit var pairingProgress: ProgressBar
    private var handled = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startScanner()
        } else {
            Toast.makeText(this, L.cameraPermissionRequired, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 扫码页展示一次性配对码与相机画面。
        applyDshSecureWindow()
        LocaleManager.init(this)
        enableDshEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(R.layout.activity_scan)
        barcodeView = findViewById(R.id.barcode_scanner)
        pairingOverlay = findViewById(R.id.pairing_overlay)
        pairingStatus = findViewById(R.id.pairing_status)
        pairingProgress = findViewById(R.id.pairing_progress)
        val scanClose = findViewById<ImageButton>(R.id.scan_close)
        scanClose.contentDescription = L.close
        scanClose.setOnClickListener { finish() }
        ViewCompat.setOnApplyWindowInsetsListener(scanClose) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (8 * resources.displayMetrics.density).toInt()
            view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                topMargin = bars.top + pad
                marginStart = bars.left + pad
            }
            insets
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            startScanner()
        }
    }

    private fun startScanner() {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                onScanned(result.result)
            }

            override fun possibleResultPoints(result: List<ResultPoint>) {}
        })
        barcodeView.resume()
    }

    private fun onScanned(result: Result) {
        if (handled) return
        handled = true
        barcodeView.pause()

        val text = result.text ?: ""
        when (val parsed = parsePairingQr(text)) {
            PairingQrResult.NotDsh -> {
                scanFailed(L.notDshQr)
                return
            }
            PairingQrResult.Invalid -> {
                scanFailed(L.qrIncomplete)
                return
            }
            is PairingQrResult.Ok -> {
                val qr = parsed.qr
                showPairingProgress()
                executor.execute {
                    try {
                        // 一次扫码 = 一次配对：局域网与远程两条路共用同一个幂等键（RFC §7.6）
                        val r = PairClient.pairWithQr(qr, DeviceName.of(this), UUID.randomUUID().toString())
                        saveAndFinish(qr.name, r)
                    } catch (e: Exception) {
                        val unwrapped = PinnedSsl.unwrap(e)
                        val msg = unwrapped.message?.takeIf { it.isNotBlank() } ?: L.allAddressesFailed
                        runOnUiThread { scanFailed(msg) }
                    }
                }
            }
        }
    }

    private fun saveAndFinish(fallbackName: String, r: PairClient.Result) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            val host = hostFromPair(fallbackName, r)
            if (!HostStore.upsert(this, host)) {
                if (HostStore.isLocked(this)) {
                    HostStore.clearLockAndReplace(this, host)
                } else {
                    scanFailed(L.credentialsSaveFailedToast)
                    return@runOnUiThread
                }
            }
            if (r.pending) {
                pairingProgress.visibility = View.GONE
                pairingStatus.text = L.pairPendingApprovalToast
                pairingOverlay.contentDescription = L.pairPendingApprovalToast
                pairingOverlay.visibility = View.VISIBLE
                pairingOverlay.setOnClickListener {
                    startActivity(
                        android.content.Intent(this@ScanActivity, DevicesActivity::class.java)
                            .putExtra(EXTRA_AUTH_NOTICE, L.pairPendingApprovalToast),
                    )
                    finish()
                }
                return@runOnUiThread
            }
            startActivity(host.putInto(android.content.Intent(this@ScanActivity, WorkspaceActivity::class.java)))
            finish()
        }
    }

    private fun showPairingProgress() {
        pairingProgress.visibility = View.VISIBLE
        pairingStatus.text = L.pairingInProgress
        pairingOverlay.contentDescription = L.pairingInProgress
        pairingOverlay.setOnClickListener(null)
        pairingOverlay.isClickable = true
        pairingOverlay.visibility = View.VISIBLE
    }

    private fun hidePairingProgress() {
        pairingOverlay.setOnClickListener(null)
        pairingOverlay.visibility = View.GONE
        pairingProgress.visibility = View.VISIBLE
    }

    private fun scanFailed(message: String) {
        runOnUiThread {
            handled = false
            pairingProgress.visibility = View.GONE
            pairingStatus.text = L.pairFailedTapToRetry.format(message)
            pairingOverlay.contentDescription = L.pairFailedTapToRetry.format(message)
            pairingOverlay.visibility = View.VISIBLE
            pairingOverlay.setOnClickListener {
                hidePairingProgress()
                barcodeView.resume()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::barcodeView.isInitialized && !handled) barcodeView.resume()
    }

    override fun onPause() {
        if (this::barcodeView.isInitialized) barcodeView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
