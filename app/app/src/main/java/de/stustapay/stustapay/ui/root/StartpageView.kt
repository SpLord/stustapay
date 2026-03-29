package de.stustapay.stustapay.ui.root

import android.graphics.BitmapFactory
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.stustapay.stustapay.BuildConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.stustapay.libssp.update.UpdateBanner
import de.stustapay.libssp.update.VersionLabel
import de.stustapay.libssp.util.restartApp
import de.stustapay.stustapay.R
import de.stustapay.stustapay.model.Access
import de.stustapay.stustapay.ui.nav.NavDest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL


@Composable
fun StartpageView(
    navigateTo: (NavDest) -> Unit = {},
    viewModel: StartpageViewModel = hiltViewModel()
) {
    val loginState by viewModel.uiState.collectAsStateWithLifecycle()
    val configLoading by viewModel.configLoading.collectAsStateWithLifecycle()
    val gradientColors = listOf(MaterialTheme.colors.background, MaterialTheme.colors.onSecondary)

    val logoUrl = loginState.appLogoUrl()
    var logoBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(logoUrl) {
        logoBitmap = null
        if (logoUrl != null) {
            withContext(Dispatchers.IO) {
                try {
                    val connection = URL(logoUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.doInput = true
                    connection.connect()
                    val inputStream = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    connection.disconnect()
                    logoBitmap = bitmap
                } catch (_: Exception) {
                    // failed to load logo, keep null
                }
            }
        }
    }

    val navigateToHook = fun(dest: NavDest) {
        // only allow navigation if we have a config
        // but always allow entering settings!
        if (!configLoading || dest == RootNavDests.settings) {
            navigateTo(dest)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = gradientColors)),
    ) {

        // Version tag bottom right
        VersionLabel(
            currentVersion = BuildConfig.VERSION_NAME,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            logoBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Event Logo",
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .padding(top = 10.dp, bottom = 5.dp),
                )
            }

            TerminalConfig()

            Column(verticalArrangement = Arrangement.Bottom) {

                if (startpageItems.isNotEmpty()) {
                    Divider()
                }

                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1.0f)
                        .verticalScroll(state = scroll)
                ) {
                    for (item in startpageItems) {
                        if (loginState.checkAccess(item.canAccess)) {
                            StartpageEntry(item = item, navigateTo = navigateToHook)
                        }
                    }
                }

                Divider()

                if (loginState.hasConfig()) {
                    StartpageEntry(
                        item = StartpageItem(
                            icon = Icons.Filled.Person,
                            navDestination = RootNavDests.user,
                            label = R.string.user_title,
                        ),
                        navigateTo = navigateToHook
                    )
                }

                if (loginState.checkAccess { u, _ -> Access.canChangeConfig(u) } || !loginState.hasConfig()) {
                    StartpageEntry(
                        item = StartpageItem(
                            icon = Icons.Filled.Settings,
                            label = R.string.root_item_settings,
                            navDestination = RootNavDests.settings,
                        ),
                        navigateTo = navigateToHook
                    )
                }

                if (loginState.checkAccess { u, _ -> Access.canHackTheSystem(u) }) {
                    StartpageEntry(
                        item = StartpageItem(
                            icon = Icons.AutoMirrored.Filled.Send,
                            label = R.string.root_item_development,
                            navDestination = RootNavDests.development,
                        ),
                        navigateTo = navigateToHook
                    )
                }

                StartpageEntry(
                    item = StartpageItem(
                        icon = Icons.Filled.QuestionMark,
                        label = R.string.root_item_guide,
                        navDestination = RootNavDests.guide,
                    ),
                    navigateTo = navigateToHook
                )

                UpdateBanner(
                    currentVersion = BuildConfig.VERSION_NAME,
                    apkName = "app-release.apk",
                )

                val activity = LocalActivity.current
                StartpageEntry(
                    item = StartpageItem(
                        icon = Icons.Filled.Refresh,
                        label = R.string.root_item_restart_app,
                        navDestination = RootNavDests.startpage,
                    ),
                    navigateTo = {
                        if (activity != null) {
                            restartApp(activity)
                        }
                    }
                )
            }
        }
    }
}