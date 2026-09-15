package to.curare.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import to.curare.compose.network.platformHttpClientEngineFactory
import to.curare.compose.ui.composable.navigation.CurareNavHost
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import fr.acinq.phoenix.PhoenixGlobal

/**
 * Coil's default ktor fetcher builds a bare `HttpClient()`, whose engine is whichever the
 * classpath happens to serve first. With CIO that means no TLS on ios, so images over
 * https fail exactly like the relay sockets did. Pin the singleton loader to the same
 * platform engine the sockets use.
 */
private val imageHttpClient by lazy { HttpClient(platformHttpClientEngineFactory()) }

@Composable
fun CurareApp(
    navController: NavHostController,
    curareGlobal: CurareGlobal,
    phoenixGlobal: PhoenixGlobal
) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { imageHttpClient }))
            }
            .build()
    }

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