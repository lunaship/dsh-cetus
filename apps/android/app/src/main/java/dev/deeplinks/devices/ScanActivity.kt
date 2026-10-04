package dev.deeplinks.devices
import dev.deeplinks.R
import dev.deeplinks.native.WorkspaceActivity
import dev.deeplinks.core.DeviceName
import dev.deeplinks.core.L
import dev.deeplinks.core.PendingPairStore
import dev.deeplinks.core.PairingSession
import dev.deeplinks.core.PairFailureCode
import dev.deeplinks.native.AppRoute
import dev.deeplinks.core.scanHint
import dev.deeplinks.core.scanTitle
import dev.deeplinks.core.LocaleManager
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

    /** 从相册识别（M1）：Photo Picker 选图，无需存储权限。 */
    private val albumPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (handled) return@registerForActivityResult
        handled = true
        barcodeView.pause()
        pairAlbum(uri)
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
        barcodeView.setStatusText("")
        pairingOverlay = findViewById(R.id.pairing_overlay)
        pairingStatus = findViewById(R.id.pairing_status)
        pairingProgress = findViewById(R.id.pairing_progress)
        findViewById<android.widget.TextView>(R.id.scan_title).text = L.scanTitle
        findViewById<android.widget.TextView>(R.id.scan_hint).text = L.scanHint
        val scanClose = findViewById<ImageButton>(R.id.scan_close)
        scanClose.contentDescription = L.close
        scanClose.setOnClickListener { finish() }
        val scanAlbum = findViewById<ImageButton>(R.id.scan_album)
        scanAlbum.contentDescription = L.methodAlbum
        scanAlbum.setOnClickListener {
            albumPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
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
        pairScanned(result.text ?: "")
    }

    /** 扫码配对：识别到的文本走共用配对流程（M1）。 */
    private fun pairScanned(text: String) {
        showPairingProgress()
        executor.execute { pairAndFinish(text) }
    }

    /** 相册配对：先在 IO 线程解码图片，识别不到二维码就提示。 */
    private fun pairAlbum(uri: android.net.Uri) {
        showPairingProgress()
        executor.execute {
            val text = QrImageDecoder.decodeUri(this, uri)
            if (text.isNullOrBlank()) {
                scanFailed(L.qrImageNotFound)
                return@execute
            }
            pairAndFinish(text)
        }
    }

    /** 扫码与相册共用的落点：解析 + 配对 + 保存/导航。运行在 [executor] 线程上。 */
    @Volatile private var activeAttempt: String? = null

    private fun pairAndFinish(text: String) {
        if (isFinishing || isDestroyed) return
        val attempt = PendingPairStore.begin(this) ?: run { scanFailed(L.credentialsSaveFailedToast); return }
        activeAttempt = attempt
        if (isFinishing || isDestroyed) { PendingPairStore.abort(this, attempt); return }
        val outcome = pairFromQrText(text, DeviceName.of(this), attempt)
        if (isFinishing || isDestroyed || !PendingPairStore.isCurrent(this, attempt)) return
        when (outcome) {
            is PairQrOutcome.Failed -> {
                PendingPairStore.abort(this, attempt)
                activeAttempt = null
                if (outcome.failure.code == PairFailureCode.PAIR_CODE_INVALID || outcome.failure.code == PairFailureCode.QR_REMOTE_INVALID) scanFailed(outcome.failure.message) else openPairFailed(outcome)
            }
            is PairQrOutcome.Paired -> saveAndFinish(outcome.host, attempt)
            is PairQrOutcome.Pending -> savePendingAndFinish(outcome.session)
        }
    }

    private fun saveAndFinish(host: dev.deeplinks.core.Host, attempt: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed || !PendingPairStore.isCurrent(this, attempt)) return@runOnUiThread
            if (!PendingPairStore.complete(this, attempt, host)) {
                scanFailed(L.credentialsSaveFailedToast)
                return@runOnUiThread
            }
            activeAttempt = null
            startActivity(host.putInto(android.content.Intent(this@ScanActivity, WorkspaceActivity::class.java)))
            finish()
        }
    }

    private fun savePendingAndFinish(session: PairingSession) {
        if (!PendingPairStore.save(this, session)) {
            scanFailed(L.credentialsSaveFailedToast)
            return
        }
        runOnUiThread {
            if (isFinishing || isDestroyed || !PendingPairStore.isCurrent(this, session.attemptId)) return@runOnUiThread
            activeAttempt = null
            startActivity(mainIntent(AppRoute.PAIR_WAITING))
            finish()
        }
    }

    /** v4 1.6：连不上 / 码过期等配对失败，回应用内失败页（码本身不对仍留在扫码页提示）。 */
    private fun openPairFailed(outcome: PairQrOutcome.Failed) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            val intent = mainIntent(AppRoute.PAIR_FAILED)
                .putExtra(AppRoute.EXTRA_PAIR_MESSAGE, outcome.failure.message)
                .putExtra(AppRoute.EXTRA_PAIR_CODE, outcome.failure.code.name)
                .putExtra(AppRoute.EXTRA_PAIR_RECOVERY, outcome.failure.recovery.name)
            outcome.failure.suggestion?.let { intent.putExtra(AppRoute.EXTRA_PAIR_SUGGESTION, it) }
            startActivity(intent)
            finish()
        }
    }

    private fun mainIntent(route: String) =
        android.content.Intent(this, dev.deeplinks.native.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(dev.deeplinks.native.MainActivity.EXTRA_START_ROUTE, route)

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
        activeAttempt?.let { PendingPairStore.abort(this, it) }
        executor.shutdownNow()
        super.onDestroy()
    }
}
