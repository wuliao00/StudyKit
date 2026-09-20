package com.studykit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.studykit.ui.nav.AppNav
import com.studykit.ui.theme.StudyKitTheme
import com.studykit.util.ShareIntake
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * 分享进来的图片，进程内一次性交接给 `AppNav`。
     * 不走导航参数：图片是本地文件路径，塞进 navArgument 会把它的生命周期绑到返回栈上，
     * 用户按返回后临时文件还在，容易被二次导入。
     */
    private val sharedImageFlow = MutableStateFlow<File?>(null)

    /** POST_NOTIFICATIONS 按需申请（Android 13+；本机 Android 11 无此门槛，代码兼容写） */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不阻塞主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        // 只在冷启动读一次：转屏/主题切换也会走 onCreate，而 intent 还挂着 SEND，
        // 不判 savedInstanceState 就会在用户已经离开录入页后，再把他弹回去一次。
        if (savedInstanceState == null) {
            intake(intent)
        }
        setContent {
            StudyKitTheme {
                AppNav(
                    sharedImage = sharedImageFlow,
                    onSharedConsumed = { sharedImageFlow.value = null },
                )
            }
        }
    }

    /** singleTop 下重复分享走这里（`setIntent` 必须调，否则后续读到的还是首次那个 intent） */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intake(intent)
    }

    /**
     * 收件 + 反馈。冷启动与 `onNewIntent` 两条路共用，差别只在 intent 来源。
     *
     * 刻意不静默失败：用户点了「分享到 StudyKit」却什么都没发生，只会以为应用没装上或图丢了。
     * 这句提示同时也是排查入口 —— `ShareIntake` 内部是 `runCatching`，读不到图时不会留任何痕迹。
     */
    private fun intake(intent: Intent) {
        val file = ShareIntake.extract(this, intent)
        if (file != null) {
            sharedImageFlow.value = file
        } else if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            Toast.makeText(this, "收到了分享，但读不到里面的图片", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
