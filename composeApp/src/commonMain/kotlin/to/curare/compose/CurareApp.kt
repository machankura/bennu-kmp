package to.curare.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import to.curare.compose.ui.composable.navigation.CurareNavHost
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import fr.acinq.phoenix.PhoenixGlobal

@Composable
fun CurareApp(
    navController: NavHostController,
    curareGlobal: CurareGlobal,
    phoenixGlobal: PhoenixGlobal
) {
    CurareTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            // One host state for the whole app rather than one per screen. Only one
            // Scaffold is composed at a time under a NavHost, so the message renders in
            // whichever screen is on top -- and a view model coroutine reporting an
            // outcome does not have to be handed a state through the parameter list of
            // every composable between it and the Scaffold.
            ProvideSnackbarHost {
                CurareNavHost(
                    curareGlobal = curareGlobal,
                    phoenixGlobal = phoenixGlobal,
                    navController = navController
                )
            }
        }
    }
}