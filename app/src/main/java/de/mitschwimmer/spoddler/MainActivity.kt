package de.mitschwimmer.spoddler

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.spotify.connectstate.Connect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration

class MainActivity : AppCompatActivity() {
    private val TAG: String = "spoddler.Main"
    private lateinit var player: Player

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val coroutineScope = CoroutineScope(Dispatchers.IO)

        coroutineScope.launch(Dispatchers.IO) {
            val session = Session.Builder(Session.Configuration.Builder().setStoreCredentials(false).setCacheEnabled(false).build())
                .setPreferredLocale("en")
                .setDeviceType(Connect.DeviceType.SMARTPHONE)
                .setDeviceName("librespot-java")
                .userPass("mail@example.com", "rC3H8p6Wt8kQsw")
                .setDeviceId(null).create()

            onSessionCreated(session)
        }
    }

    private fun onSessionCreated(session: Session) {
        player = Player(
            PlayerConfiguration.Builder()
                .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
                .setOutputClass("xyz.gianlu.librespot.android.sink.AndroidSinkOutput")
                .build(),
            session
        )
        Log.i(TAG, "Logged in as: " + session.apWelcome().canonicalUsername)
    }
}
