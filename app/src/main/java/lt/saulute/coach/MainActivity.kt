package lt.saulute.coach

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var allowed by remember {
                mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            }
            val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
            LaunchedEffect(Unit) { if (!allowed) request.launch(Manifest.permission.CAMERA) }
            SauluteApp(cameraAllowed = allowed, requestCamera = { request.launch(Manifest.permission.CAMERA) })
        }
    }
}
