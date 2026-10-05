package dev.deeplinks.devices
import dev.deeplinks.R
import dev.deeplinks.native.WorkspaceActivity
import dev.deeplinks.core.DeviceName
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.L
import dev.deeplinks.native.AppRoute
import dev.deeplinks.core.scanHint
import dev.deeplinks.core.scanTitle
import dev.deeplinks.core.LocaleManager
import dev.deeplinks.core.applyDshSecureWindow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowInsetsControllerCompat
import dev.deeplinks.core.enableDshEdgeToEdge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScanActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var pairingOverlay: View
    private lateinit var pairingStatus: TextView
    private lateinit var pairingProgress: ProgressBar
    private val handled = AtomicBoolean(false)

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
        if (!handled.compareAndSet(false, true)) return@registerForActivityResult
        stopScanner()
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
        previewView = findViewById(R.id.barcode_scanner)
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
        }
    }

    /** CameraX 走 Camera2。小米会拦截旧 Camera API 的 startPreview，预览起不来就解不出码。 */
    private fun startScanner() {
        if (handled.get() || isFinishing) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (handled.get() || isFinishing || isDestroyed) return@addListener
            val provider = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
            analysis.setAnalyzer(executor, ::analyzeFrame)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // 没有后置相机或被系统占用时保持页面，用户还能从相册识别。
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopScanner() {
        cameraProvider?.unbindAll()
    }

    private fun analyzeFrame(image: ImageProxy) {
        val text = try {
            if (handled.get()) null else decodeFrame(image)
        } finally {
            image.close()
        }
        if (text.isNullOrBlank() || !handled.compareAndSet(false, true)) return
        runOnUiThread {
            stopScanner()
            pairScanned(text)
        }
    }

    private fun decodeFrame(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return QrImageDecoder.decodeLuminance(
            bytes,
            rowStride = plane.rowStride,
            pixelStride = plane.pixelStride,
            width = image.width,
            height = image.height,
        )
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
    private fun pairAndFinish(text: String) {
        when (val outcome = pairFromQrText(text, DeviceName.of(this))) {
            is PairQrOutcome.Failed -> if (outcome.badQr) scanFailed(outcome.message) else openPairFailed(outcome)
            is PairQrOutcome.Paired -> saveAndFinish(outcome.host)
            is PairQrOutcome.Pending -> saveAndFinish(outcome.host, pending = true)
        }
    }

    private fun saveAndFinish(host: dev.deeplinks.core.Host, pending: Boolean = false) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            if (!HostStore.upsert(this, host)) {
                if (HostStore.isLocked(this)) {
                    HostStore.clearLockAndReplace(this, host)
                } else {
                    scanFailed(L.credentialsSaveFailedToast)
                    return@runOnUiThread
                }
            }
            if (pending) {
                // v4 1.5：回到应用内的「等电脑批准」页，由它轮询批准结果。
                startActivity(mainIntent(AppRoute.PAIR_WAITING))
                finish()
                return@runOnUiThread
            }
            startActivity(host.putInto(android.content.Intent(this@ScanActivity, WorkspaceActivity::class.java)))
            finish()
        }
    }

    /** v4 1.6：连不上 / 码过期等配对失败，回应用内失败页（码本身不对仍留在扫码页提示）。 */
    private fun openPairFailed(failure: PairQrOutcome.Failed) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            startActivity(
                mainIntent(AppRoute.PAIR_FAILED)
                    .putExtra(AppRoute.EXTRA_PAIR_MESSAGE, failure.message)
                    .putExtra(AppRoute.EXTRA_PAIR_NETWORK, failure.network),
            )
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
            handled.set(false)
            pairingProgress.visibility = View.GONE
            pairingStatus.text = L.pairFailedTapToRetry.format(message)
            pairingOverlay.contentDescription = L.pairFailedTapToRetry.format(message)
            pairingOverlay.visibility = View.VISIBLE
            pairingOverlay.setOnClickListener {
                hidePairingProgress()
                startScanner()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!handled.get() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            startScanner()
        }
    }

    override fun onPause() {
        stopScanner()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
