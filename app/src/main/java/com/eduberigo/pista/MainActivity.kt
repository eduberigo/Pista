package com.eduberigo.pista

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val hasPerm = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hasPerm.value = Environment.isExternalStorageManager()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            PistaTheme {
                Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
                    if (hasPerm.value) {
                        LaunchedEffect(Unit) { Pista.ready() }
                        TickDriver()
                        Root()
                    } else {
                        PermissionScreen { requestFiles() }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPerm.value = Environment.isExternalStorageManager()
    }

    private fun requestFiles() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }
}

@Composable
fun PermissionScreen(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pista", color = C.txt, fontSize = 28.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Para ler sua biblioteca de músicas e guardar as análises e faixas separadas, o app precisa de acesso aos arquivos.",
            color = C.dim, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAsk) { Text("Permitir acesso aos arquivos") }
    }
}

/** Telas: 0 = decks, 1 = biblioteca, 2 = ajustes, 3 = escolher pasta. */
@Composable
fun Root() {
    var screen by remember { mutableIntStateOf(0) }
    var libDeck by remember { mutableIntStateOf(0) }
    var withStems by remember { mutableStateOf(true) }
    BackHandler(enabled = screen != 0) { screen = if (screen >= 3) 2 else 0 }
    when (screen) {
        1 -> LibraryScreen(libDeck, onClose = { screen = 0 }, onSettings = { screen = 2 })
        2 -> SettingsScreen(
            onClose = { screen = 0 },
            onPickFolder = { screen = 3 },
            onBackup = { withStems = it; screen = 4 },
            onRestore = { screen = 5 },
        )
        3 -> FolderPicker(
            "Pasta das músicas", "USAR ESTA PASTA", Pista.library.root,
            onPick = { Pista.library.setRoot(it.path); screen = 2 }, onCancel = { screen = 2 },
        )
        4 -> FolderPicker(
            "Onde guardar o backup (cria a pasta Pista-backup)", "FAZER BACKUP AQUI", "",
            onPick = { Backup.backupTo(it, withStems); screen = 2 }, onCancel = { screen = 2 },
        )
        5 -> FolderPicker(
            "Pasta do backup (Pista-backup ou a pasta Pista copiada)", "RESTAURAR DESTA PASTA", "",
            onPick = { Backup.restoreFrom(it) { screen = 3 }; screen = 2 }, onCancel = { screen = 2 },
        )
        else -> MainScreen(
            openLib = { libDeck = it; screen = 1 },
            openSettings = { screen = 2 },
        )
    }
}
