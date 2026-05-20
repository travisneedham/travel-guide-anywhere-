package com.travelguide.anywhere

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.commit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.travelguide.anywhere.databinding.ActivityMainBinding
import com.travelguide.anywhere.ui.main.MainFragment
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val locationRequested = permissions.containsKey(Manifest.permission.ACCESS_FINE_LOCATION)
        if (locationRequested && !fineGranted && !coarseGranted) {
            Toast.makeText(this, "Location permission is required for the tour guide", Toast.LENGTH_LONG).show()
        }
    }

    private var pendingCrashFile: File? = null
    private val saveCrashLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write((pendingCrashFile?.readText() ?: "").toByteArray())
                }
                Toast.makeText(this, "Crash report saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingCrashFile?.delete()
        pendingCrashFile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        requestPermissionsIfNeeded()

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, MainFragment())
            }
            val crashFile = CrashReporter.crashFile(this)
            if (crashFile.exists()) {
                showCrashDialog(crashFile)
            }
        }
    }

    private fun showCrashDialog(crashFile: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Crash report found")
            .setMessage("The app crashed last session. Save or share the report?")
            .setPositiveButton("Save to Downloads") { _, _ ->
                pendingCrashFile = crashFile
                saveCrashLauncher.launch("travel_guide_crash.txt")
            }
            .setNeutralButton("Share") { _, _ ->
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", crashFile)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share crash report"))
                crashFile.delete()
            }
            .setNegativeButton("Dismiss") { _, _ -> crashFile.delete() }
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}
