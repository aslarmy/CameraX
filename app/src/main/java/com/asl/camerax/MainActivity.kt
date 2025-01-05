package com.asl.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.asl.camerax.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "MainActivity_img"

class MainActivity : AppCompatActivity() {

    private val mainBinding: ActivityMainBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val multiplePermissionId = 14
    private val multiplePermissionNameList = if (Build.VERSION.SDK_INT >= 33) {
        arrayListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    } else {
        arrayListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var recording: Recording? = null
    private var isPhoto = true


    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var camera: Camera
    private lateinit var cameraSelector: CameraSelector
    private var orientationEventListener: OrientationEventListener? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var aspectRatio = AspectRatio.RATIO_16_9

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(mainBinding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (checkMultiplePermission()) {
            startCamera()
        }

        mainBinding.flipCameraIB.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            bindCameraUserCases()
            mainBinding.flashToggleIB.isEnabled = true
        }

        mainBinding.changeCameraToVideoIB.setOnClickListener {
            isPhoto = !isPhoto
            val cameraIcon = if (isPhoto) R.drawable.ic_photo else R.drawable.ic_videocam
            val captureIB = if (isPhoto) R.drawable.camera else R.drawable.ic_start
            mainBinding.changeCameraToVideoIB.setImageResource(cameraIcon)
            mainBinding.captureIB.setImageResource(captureIB)
        }

        mainBinding.captureIB.setOnClickListener {
            if (isPhoto) {
                takePhoto()
            } else {
                captureVideo()
            }

        }
        mainBinding.flashToggleIB.setOnClickListener {
            setFlashIcon(camera)
        }
        mainBinding.aspectRatioText.setOnClickListener {
            if (aspectRatio == AspectRatio.RATIO_16_9) {
                aspectRatio = AspectRatio.RATIO_4_3
                setAspectRatio("H,4:3")
                mainBinding.aspectRatioText.text = "4:3"
            } else {
                aspectRatio = AspectRatio.RATIO_16_9
                setAspectRatio("H,0:0")
                mainBinding.aspectRatioText.text = "16:9"
            }
            bindCameraUserCases()
        }
    }


    private fun checkMultiplePermission(): Boolean {
        val listPermissionNeeded = arrayListOf<String>()
        for (permission in multiplePermissionNameList) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                listPermissionNeeded.add(permission)
            }
        }
        if (listPermissionNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                listPermissionNeeded.toTypedArray(),
                multiplePermissionId
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == multiplePermissionId) {
            if (grantResults.isNotEmpty()) {
                var isGrant = true
                for (element in grantResults) {
                    if (element == PackageManager.PERMISSION_DENIED) {
                        isGrant = false
                    }
                }
                if (isGrant) {
                    // here all permission granted successfully
                    startCamera()
                } else {
                    var someDenied = false
                    for (permission in permissions) {
                        if (!ActivityCompat.shouldShowRequestPermissionRationale(
                                this,
                                permission
                            )
                        ) {
                            if (ActivityCompat.checkSelfPermission(
                                    this,
                                    permission
                                ) == PackageManager.PERMISSION_DENIED
                            ) {
                                someDenied = true
                            }
                        }
                    }
                    if (someDenied) {
                        // here app Setting open because all permission is not granted
                        // and permanent denied
                        appSettingOpen(this)
                    } else {
                        // here warning permission show
                        warningPermissionDialog(this) { _: DialogInterface, which: Int ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE ->
                                    checkMultiplePermission()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUserCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUserCases() {
        val rotation = mainBinding.previewView.display.rotation

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    aspectRatio,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            ).build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.surfaceProvider = mainBinding.previewView.surfaceProvider
            }

        // Video Capture
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
            )
            .setAspectRatio(aspectRatio)
            .build()
        videoCapture = VideoCapture.withOutput(recorder).apply {
            targetRotation = rotation
        }


        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .build()

        cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()


        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val myRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture.targetRotation = myRotation
                videoCapture.targetRotation = myRotation
            }
        }
        orientationEventListener?.enable()

        try {
            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, videoCapture
            )

            setupZoomAndTapToFocus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun setupZoomAndTapToFocus() {
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera.cameraControl.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        }

        val scaleGestureDetector = ScaleGestureDetector(this, listener)

        mainBinding.previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_DOWN) {
                val factor = mainBinding.previewView.meteringPointFactory
                val point = factor.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(2, TimeUnit.SECONDS)
                    .build()


                val x = event.x
                val y = event.y

                val focusCircle = RectF(x - 50, y - 50, x + 50, y + 50)
                mainBinding.focusCircleView.focusCircle = focusCircle
                mainBinding.focusCircleView.invalidate()

                camera.cameraControl.startFocusAndMetering(action)
                view.performClick()
            }
            true
        }
    }


    private fun setFlashIcon(camera: Camera) {
        if (camera.cameraInfo.hasFlashUnit()) {
            if (camera.cameraInfo.torchState.value == 0) {
                camera.cameraControl.enableTorch(true)
                mainBinding.flashToggleIB.setImageResource(R.drawable.flash_off)
            } else {
                camera.cameraControl.enableTorch(false)
                mainBinding.flashToggleIB.setImageResource(R.drawable.flash_on)
            }
        } else {
            Toast.makeText(
                this,
                "Flash is not available",
                Toast.LENGTH_SHORT
            ).show()
            mainBinding.flashToggleIB.isEnabled = false
        }
    }

    private fun takePhoto() {
        val imageFolder = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            ),
            "Images"
        )

        if (!imageFolder.exists()) {
            imageFolder.mkdir()
        }

        val fileName = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.getDefault())
            .format(System.currentTimeMillis()) + ".jpg"


        val contentValue = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Images")
            }
        }

        // Avoid mirror effects
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (lensFacing == CameraSelector.LENS_FACING_FRONT)
        }

        val uri = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val outputOption =
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                OutputFileOptions.Builder(
                    contentResolver,
                    uri,
                    contentValue
                )
                    .setMetadata(metadata)
                    .build()
            } else {
                val imageFile = File(imageFolder, fileName)
                OutputFileOptions.Builder(imageFile)
                    .setMetadata(metadata)
                    .build()
            }

        imageCapture.takePicture(
            outputOption,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val message = "Photo capture succeeded: ${outputFileResults.savedUri}"
                    Toast.makeText(
                        this@MainActivity,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d(TAG, message)
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        exception.toString(),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun captureVideo() {
        mainBinding.captureIB.isEnabled = false

        // Hide ui element
        with(mainBinding) {
            flashToggleIB.gone()
            flipCameraIB.gone()
            aspectRatioText.gone()
            changeCameraToVideoIB.gone()
        }

        if (recording != null) {
            recording?.stop()
            stopRecording()
            recording = null
            return
        }

        startRecording()

        val fileName = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.getDefault())
            .format(System.currentTimeMillis()) + ".jpg"


        val contentValue = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }
        }

        val mediaStoreOutputOption = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValue)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOption)
            .apply {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        with(mainBinding) {
                            captureIB.setImageResource(R.drawable.ic_stop)
                            captureIB.isEnabled = true
                        }
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val message =
                                "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, message)
                        } else {
                            recording?.let {
                                it.close()
                                recording = null
                            }
                            Log.d(TAG, recordEvent.error.toString())
                        }

                        with(mainBinding) {
                            captureIB.setImageResource(R.drawable.ic_start)
                            captureIB.isEnabled = true
                            flashToggleIB.visible()
                            flipCameraIB.visible()
                            aspectRatioText.visible()
                            changeCameraToVideoIB.visible()
                        }
                    }
                }
            }

    }

    private fun setAspectRatio(ratio: String) {
        mainBinding.previewView.layoutParams = mainBinding.previewView.layoutParams.apply {
            if (this is ConstraintLayout.LayoutParams) {
                dimensionRatio = ratio
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener?.disable()
        if (recording != null) {
            recording?.stop()
            captureVideo()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updateTimer = object : Runnable {
        override fun run() {
            val currentTime = SystemClock.elapsedRealtime() - mainBinding.recordingTimerC.base
            val timeString = currentTime.toFormattedTime()
            mainBinding.recordingTimerC.text = timeString
            handler.postDelayed(this, 1000)
        }
    }

    fun Long.toFormattedTime(): String {
        val seconds = ((this / 1000) % 60).toInt()
        val minutes = ((this / (1000 * 60)) % 60).toInt()
        val hours = ((this / (1000 * 60 * 60)) % 24).toInt()

        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun stopRecording() {
        with(mainBinding) {
            recordingTimerC.gone()
            recordingTimerC.stop()
        }
        handler.removeCallbacks(updateTimer)
    }

    private fun startRecording() {
        with(mainBinding) {
            recordingTimerC.visible()
            recordingTimerC.base = SystemClock.elapsedRealtime()
            recordingTimerC.start()
        }
        handler.post(updateTimer)
    }
}