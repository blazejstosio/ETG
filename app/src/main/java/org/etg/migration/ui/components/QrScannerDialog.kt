package org.etg.migration.ui.components

import android.Manifest
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.etg.migration.R
import org.etg.migration.data.PermissionHelper
import org.etg.migration.ui.theme.EtgButton
import org.etg.migration.ui.theme.EtgButtonStyle
import org.etg.migration.ui.theme.EtgCardHeader
import org.etg.migration.ui.theme.EtgTone
import org.etg.migration.ui.theme.LocalGrapheneColors
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerDialog(
    title: String,
    subtitle: String,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val g = LocalGrapheneColors.current
    var hasCamPerm by remember { mutableStateOf(PermissionHelper.hasCamera(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCamPerm = granted
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = g.surface,
            border = BorderStroke(1.dp, if (g.isDark) g.borderStrong else g.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                EtgCardHeader(
                    iconRes = R.drawable.ic_qr_scan,
                    tint = g.accentCyan,
                    title = title,
                    subtitle = subtitle
                )

                if (!hasCamPerm) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .background(g.surfaceContainer, RoundedCornerShape(18.dp))
                            .border(1.dp, g.border, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        EtgButton(
                            text = stringResource(R.string.action_grant_permission),
                            onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                            iconRes = R.drawable.ic_qr_scan
                        )
                    }
                } else {
                    val decodedOnce = remember { AtomicBoolean(false) }
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

                    DisposableEffect(Unit) {
                        onDispose {
                            runCatching { boundProvider?.unbindAll() }
                            executor.shutdownNow()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, g.accentCyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                                providerFuture.addListener({
                                    val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
                                    boundProvider = provider
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val reader = MultiFormatReader()
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    analysis.setAnalyzer(executor) { image ->
                                        if (decodedOnce.get()) {
                                            image.close()
                                            return@setAnalyzer
                                        }
                                        try {
                                            val plane = image.planes[0]
                                            val buffer = plane.buffer
                                            val rowStride = plane.rowStride
                                            val w = image.width
                                            val h = image.height
                                            val data = ByteArray(w * h)
                                            if (rowStride == w) {
                                                buffer.get(data, 0, w * h)
                                            } else {
                                                val rowBuf = ByteArray(rowStride)
                                                for (row in 0 until h) {
                                                    buffer.position(row * rowStride)
                                                    val toRead = minOf(rowStride, buffer.remaining())
                                                    buffer.get(rowBuf, 0, toRead)
                                                    System.arraycopy(rowBuf, 0, data, row * w, w)
                                                }
                                            }
                                            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
                                            val bitmap = BinaryBitmap(HybridBinarizer(source))
                                            val rawText = reader.decodeWithState(bitmap)?.text
                                            if (!rawText.isNullOrBlank() && decodedOnce.compareAndSet(false, true)) {
                                                ContextCompat.getMainExecutor(ctx).execute {
                                                    onResult(rawText)
                                                }
                                            }
                                        } catch (_: Throwable) {
                                        } finally {
                                            reader.reset()
                                            image.close()
                                        }
                                    }
                                    runCatching {
                                        provider.unbindAll()
                                        provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.matchParentSize()
                        )

                        val reticleColor = g.accentCyan
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val boxSize = 180.dp.toPx()
                            val left = (size.width - boxSize) / 2f
                            val top = (size.height - boxSize) / 2f
                            val right = left + boxSize
                            val bottom = top + boxSize
                            val corner = 24.dp.toPx()
                            val strokeW = 3.dp.toPx()

                            val scrim = Color.Black.copy(alpha = 0.42f)
                            drawRect(scrim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, top))
                            drawRect(scrim, Offset(0f, bottom), androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
                            drawRect(scrim, Offset(0f, top), androidx.compose.ui.geometry.Size(left, boxSize))
                            drawRect(scrim, Offset(right, top), androidx.compose.ui.geometry.Size(size.width - right, boxSize))

                            val brackets = Path().apply {
                                moveTo(left, top + corner)
                                lineTo(left, top)
                                lineTo(left + corner, top)

                                moveTo(right - corner, top)
                                lineTo(right, top)
                                lineTo(right, top + corner)

                                moveTo(right, bottom - corner)
                                lineTo(right, bottom)
                                lineTo(right - corner, bottom)

                                moveTo(left + corner, bottom)
                                lineTo(left, bottom)
                                lineTo(left, bottom - corner)
                            }
                            drawPath(
                                path = brackets,
                                color = reticleColor,
                                style = Stroke(
                                    width = strokeW,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                EtgButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    tone = EtgTone.NEUTRAL,
                    style = EtgButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
