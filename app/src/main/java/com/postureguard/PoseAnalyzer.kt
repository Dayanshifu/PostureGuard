package com.postureguard



import android.content.Context

import android.graphics.Bitmap

import android.graphics.Matrix

import android.media.MediaPlayer

import android.os.SystemClock

import androidx.camera.core.ImageAnalysis

import androidx.camera.core.ImageProxy

import com.google.mediapipe.framework.image.BitmapImageBuilder

import com.google.mediapipe.tasks.components.containers.Landmark

import com.google.mediapipe.tasks.core.BaseOptions

import com.google.mediapipe.tasks.vision.core.RunningMode

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

import kotlin.math.*



class PoseAnalyzer(

    private val context: Context,

    private val onResult: (status: String, isBad: Boolean, debugInfo: String) -> Unit

) : ImageAnalysis.Analyzer {



    var isFrontMode: Boolean = true // 对应 MainActivity 的默认选择

    private var poseLandmarker: PoseLandmarker? = null



// --- 算法阈值 (比例/角度) ---

    private val THRESHOLD_SHOULDER_TILT = 0.12f // 肩膀高度差/肩宽

    private val THRESHOLD_HEAD_TILT = 0.15f // 头部高度差/肩宽

    private val THRESHOLD_SIDE_ANGLE = 142f // 驼背角度(越小越驼)

    private val THRESHOLD_TORSO_TILT = 140f // 躯干前倾角度



// --- 滤波平滑 (EMA) ---

    private var emaAngle: Float? = null

    private var emaTorso: Float? = null



// --- 报警与状态锁 ---

    private var lastState = "good"

    private var lastStateTime = 0L

    private var badPostureStartTime = 0L

    private var hasAlerted = false

    private val mediaPlayer: MediaPlayer by lazy {

        MediaPlayer.create(context, R.raw.sound).apply { isLooping = false }

    }



    init {

        setupPoseLandmarker()

    }



    private fun setupPoseLandmarker() {

        val baseOptions = BaseOptions.builder()

            .setModelAssetPath("pose_landmarker_lite.task")

            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()

            .setBaseOptions(baseOptions)

            .setRunningMode(RunningMode.LIVE_STREAM)

            .setResultListener { result, _ -> processPose(result) }

            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(context, options)

    }



    override fun analyze(image: ImageProxy) {

        val rotationDegrees = image.imageInfo.rotationDegrees

        val bitmap = image.toBitmap()



// 关键：处理 Bitmap 旋转，确保 MediaPipe 看到的是正向的人

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }

        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)



        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        poseLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())

        image.close()

    }



    private fun processPose(result: PoseLandmarkerResult) {

        val landmarks = result.landmarks() // 归一化坐标 (用于计算比例)

        val worldLandmarks = result.worldLandmarks() // 3D 世界坐标 (用于计算角度)



        if (landmarks.isNullOrEmpty() || worldLandmarks.isNullOrEmpty()) {

            onResult("未检测到人", false, "")

            return

        }



        val lm = landmarks[0]

        val wlm = worldLandmarks[0]

        val issues = mutableListOf<String>()

        var debugStr = ""



        if (!isFrontMode) {

// ================= 侧面模式 (3D 向量法) =================

            val useLeft = lm[11].visibility().orElse(0f) > lm[12].visibility().orElse(0f)

            val ear = if (useLeft) wlm[7] else wlm[8]

            val shoulder = if (useLeft) wlm[11] else wlm[12]

            val hip = if (useLeft) wlm[23] else wlm[24]



// 1. 计算 3D 骨骼夹角 (耳-肩-胯)

            val angle = calculate3DAngle(ear, shoulder, hip)

            emaAngle = if (emaAngle == null) angle else emaAngle!! * 0.7f + angle * 0.3f



// 2. 躯干垂直倾斜度

            val torsoTilt = abs(Math.toDegrees(atan2(

                (shoulder.x() - hip.x()).toDouble(),

                (shoulder.y() - hip.y()).toDouble()

            ))).toFloat()

            emaTorso = if (emaTorso == null) torsoTilt else emaTorso!! * 0.7f + torsoTilt * 0.3f



            if (emaAngle!! < THRESHOLD_SIDE_ANGLE) issues.add("驼背")

            if (emaTorso!! < THRESHOLD_TORSO_TILT) issues.add("前倾")



            debugStr = "角度:${emaAngle!!.toInt()}° 倾斜:${emaTorso!!.toInt()}°"



        } else {

// ================= 正面模式 (比例归一化法) =================

// 计算肩宽作为基准单位 (解决远近误差)

            val shoulderWidth = hypot(lm[11].x() - lm[12].x(), lm[11].y() - lm[12].y())



// 歪肩：y轴差 / 肩宽

            val sDiffRatio = abs(lm[11].y() - lm[12].y()) / shoulderWidth

// 歪头：耳朵y轴差 / 肩宽

            val eDiffRatio = abs(lm[7].y() - lm[8].y()) / shoulderWidth



            if (sDiffRatio > THRESHOLD_SHOULDER_TILT) issues.add("歪肩")

            if (eDiffRatio > THRESHOLD_HEAD_TILT) issues.add("歪头")



            debugStr = "肩偏:${String.format("%.2f", sDiffRatio)} 头偏:${String.format("%.2f", eDiffRatio)}"

        }



// --- 状态判定逻辑 ---

        val now = SystemClock.elapsedRealtime()

        val currentState = if (issues.isEmpty()) "good" else "bad"



// 状态抖动保护：只有持续 1 秒的状态改变才生效

        if (currentState != lastState && (now - lastStateTime) > 1000) {

            lastState = currentState

            lastStateTime = now

        }



// --- 报警逻辑 ---

        if (lastState == "bad") {

            if (badPostureStartTime == 0L) badPostureStartTime = now

            if ((now - badPostureStartTime) > 15000 && !hasAlerted) { // 15秒提醒

                mediaPlayer.start()

                hasAlerted = true

            }

        } else {

            badPostureStartTime = 0L

            hasAlerted = false

        }



        val statusText = if (lastState == "good") "姿态良好 ✨" else "${issues.joinToString("/")} ⚠️"

        onResult(statusText, lastState == "bad", debugStr)

    }



    private fun calculate3DAngle(a: Landmark, b: Landmark, c: Landmark): Float {

        val v1 = floatArrayOf(a.x() - b.x(), a.y() - b.y(), a.z() - b.z())

        val v2 = floatArrayOf(c.x() - b.x(), c.y() - b.y(), c.z() - b.z())

        val dot = v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2]

        val mag1 = sqrt(v1[0] * v1[0] + v1[1] * v1[1] + v1[2] * v1[2])

        val mag2 = sqrt(v2[0] * v2[0] + v2[1] * v2[1] + v2[2] * v2[2])

        return Math.toDegrees(acos(dot / (mag1 * mag2)).toDouble()).toFloat()

    }



    fun close() {

        poseLandmarker?.close()

        if (mediaPlayer.isPlaying) mediaPlayer.stop()

        mediaPlayer.release()

    }

}