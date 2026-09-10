package to.curare.compose

import androidx.compose.ui.window.ComposeUIViewController
import androidx.navigation.compose.rememberNavController
import fr.acinq.phoenix.PhoenixGlobal

fun MainViewController() = ComposeUIViewController {
    val navController = rememberNavController()

    CurareApp(
        navController = navController,
        curareGlobal = CurareGlobal(
            platformContext = PlatformContext()
        ),
        phoenixGlobal = PhoenixGlobal(
            ctx = fr.acinq.phoenix.utils.PlatformContext()
        )
    )
}