package com.example.mobilicisindiaassignment

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.scottyab.rootbeer.RootBeer
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private val database = FirebaseDatabase.getInstance()
    private val healthChecksRef = database.getReference("health_checks")
    private lateinit var orientationListener: OrientationEventListener

    private val REQUEST_CODE_PERMISSIONS = 101

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startHealthChecksButton = findViewById<Button>(R.id.startHealthChecksButton)

        // Handle button click to start health checks
        startHealthChecksButton.setOnClickListener {
            checkPermissions()
        }
        setupOrientationChangeListener()

    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val notGrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGrantedPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            performHealthChecks()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                performHealthChecks()
            } else {
                Toast.makeText(this, "Permissions denied. Cannot perform health checks.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performHealthChecks() {
        val healthCheckResults = mutableMapOf<String, Boolean>()

        healthCheckResults["Camera"] = isCameraAvailable(this)
        healthCheckResults["Microphone"] = testMicrophones(this)
        healthCheckResults["Rooted Status"] = checkDeviceOrientation()
        healthCheckResults["Bluetooth"] = isBluetoothEnabled()
        healthCheckResults["GPS, Sensors"] = areSensorsAvailable(this)

        showResultsDialog(healthCheckResults)
    }

    private fun showResultsDialog(results: Map<String, Boolean>) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Health Check Results")

        val message = results.entries.joinToString("\n") { (test, passed) ->
            "$test: ${if (passed) "Passed" else "Failed"}"
        }

        builder.setMessage(message)
        builder.setPositiveButton("Send to Firebase") { _, _ ->
            sendResultsToFirebase(results)
        }
        builder.setNegativeButton("Generate PDF") { _, _ ->
            generatePdfReport(results)
        }

        builder.show()
    }

    private fun sendResultsToFirebase(results: Map<String, Boolean>) {
        val resultId = healthChecksRef.push().key

        if (resultId == null) {
            Toast.makeText(this, "Failed to send results to Firebase", Toast.LENGTH_SHORT).show()
            return
        }

        healthChecksRef.child(resultId).setValue(results)
            .addOnSuccessListener {
                Toast.makeText(this, "Results sent to Firebase", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send results to Firebase", Toast.LENGTH_SHORT).show()
            }
    }

    private fun generatePdfReport(results: Map<String, Boolean>) {
        val pdfFileName = "HealthCheckReport.pdf"
        val pdfFilePath: File

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Use scoped storage for Android 10 and above
            val downloadsDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            pdfFilePath = File(downloadsDirectory, pdfFileName)
        } else {
            // Use external storage for Android 9 and below
            pdfFilePath = File(Environment.getExternalStorageDirectory(), pdfFileName)
        }

        try {
            val pdfWriter = PdfWriter(pdfFilePath.absolutePath)
            val pdf = PdfDocument(pdfWriter)
            val document = Document(pdf)

            for ((test, passed) in results) {
                val status = if (passed) "Passed" else "Failed"
                document.add(Paragraph("$test: $status"))
            }

            document.close()

            Toast.makeText(
                this,
                "PDF report generated and saved at: ${pdfFilePath.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to generate or save the PDF report", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun isCameraAvailable(context: Context): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        return cameraPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun testMicrophones(context: Context): Boolean {
        val microphonePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        return microphonePermission == PackageManager.PERMISSION_GRANTED
    }




    private fun checkDeviceOrientation(): Boolean {
        val isRotated = isDeviceRotated()
      /*  val orientationText = if (isRotated) "Landscape" else "Portrait"
        Toast.makeText(this, "Device is in $orientationText mode", Toast.LENGTH_SHORT).show()*/
        return isRotated
    }
    private fun isDeviceRotated(): Boolean {
        val orientation = windowManager.defaultDisplay.rotation
        return orientation == Surface.ROTATION_90 || orientation == Surface.ROTATION_270
    }
    private fun setupOrientationChangeListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val newOrientation = when {
                    orientation >= 315 || orientation < 45 -> Configuration.ORIENTATION_PORTRAIT
                    orientation in 225..314 -> Configuration.ORIENTATION_LANDSCAPE
                    orientation in 135..224 -> 9 // Reverse Portrait (android.view.Surface.ROTATION_270)
                    else -> 8 // Reverse Landscape (android.view.Surface.ROTATION_90)
                }

                requestedOrientation = when (newOrientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    9 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT // Reverse Portrait
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT // Portrait and Reverse Landscape
                }
            }
        }

        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
    }


    private fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }

    private fun areSensorsAvailable(context: Context): Boolean {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val sensors = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)

        for (sensorType in sensors) {
            val sensor = sensorManager.getDefaultSensor(sensorType)
            if (sensor == null) {
                return false
            }
        }

        val isGpsAvailable = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        return isGpsAvailable
    }

    companion object {
        private const val SAMPLE_RATE = 44100
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationListener.disable()
    }

}
