package de.mitschwimmer.spoddler

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.spotify.connectstate.Connect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler
import xyz.gianlu.librespot.core.Session

class MainActivity : AppCompatActivity() {
    private lateinit var player: Player

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Configurator.setRootLevel(Level.DEBUG)
        Thread.setDefaultUncaughtExceptionHandler(Log4JUncaughtExceptionHandler())

        // TODO do not use global scope and handle exceptions
        // TODO fix "W/System: A resource failed to call close."
        GlobalScope.launch(Dispatchers.IO) {
            val session = Session.Builder(Session.Configuration.Builder().setStoreCredentials(false).setCacheEnabled(false).build())
                .setPreferredLocale("en")
                .setDeviceType(Connect.DeviceType.SMARTPHONE)
                .setDeviceName("librespot-java")
                .userPass("karl@mitschwimmer.de", "rC3H8p6Wt8kQsw")
                .setDeviceId(null).create()

            onSessionCreated(session)
        }
    }

    private fun onSessionCreated(session: Session) {
        player = Player(PlayerConfiguration.Builder().build(), session)
        println("I am fine")
    }
}
