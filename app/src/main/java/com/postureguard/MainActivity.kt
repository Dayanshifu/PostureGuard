package com.postureguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvDebug: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSwitch: ImageButton
    private lateinit var rgMode: RadioGroup

    private var isRunning = false
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: PoseAnalyzer? = null

    // 权限申请
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera() else Toast.makeText(this, "未获得相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 绑定 UI
        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tvStatus)
        tvDebug = findViewById(R.id.tvDebug)
        btnToggle = findViewById(R.id.btnToggle)
        btnSwitch = findViewById(R.id.btnSwitch)
        rgMode = findViewById(R.id.rgMode)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 2. 初始化分析器 (带 Debug 回调)
        try {
            analyzer = PoseAnalyzer(this) { status, isBad, debugInfo ->
                runOnUiThread {
                    tvStatus.text = status
                    tvStatus.setTextColor(if (isBad) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
                    tvDebug.text = debugInfo
                }
            }
        } catch (e: Exception) {
            Log.e("PostureGuard", "Analyzer init failed", e)
            Toast.makeText(this, "模型初始化失败，请检查 assets 文件夹", Toast.LENGTH_LONG).show()
        }

        // 3. 模式选择 (正面/侧面)
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            analyzer?.isFrontMode = (checkedId == R.id.rbFront)
        }

        // 4. 开始/停止监控
        btnToggle.setOnClickListener {
            if (!isRunning) {
                checkPermissionAndStart()
            } else {
                stopCamera()
            }
        }

        // 5. 切换摄像头
        btnSwitch.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
            if (isRunning) startCamera()
        }
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 预览配置
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // 图像分析配置 (核心：确保旋转角度正确)
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(viewFinder.display.rotation)
                .build()
                .also {
                    analyzer?.let { a -> it.setAnalyzer(cameraExecutor, a) }
                }

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                isRunning = true
                btnToggle.text = "停止监控"
                btnToggle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
            } catch (exc: Exception) {
                Log.e("PostureGuard", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        ProcessCameraProvider.getInstance(this).get().unbindAll()
        isRunning = false
        btnToggle.text = "开始监控"
        btnToggle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
        tvStatus.text = "等待启动..."
        tvDebug.text = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        analyzer?.close()
    }
}