package de.mitschwimmer.spoddler;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import org.apache.logging.log4j.core.config.Configurator;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

import de.mitschwimmer.spoddler.events.EventsShell;
import xyz.gianlu.librespot.common.Log4JUncaughtExceptionHandler;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FileConfiguration conf = null;
        try {
            conf = new FileConfiguration((String) null);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Configurator.setRootLevel(conf.loggingLevel());
        Thread.setDefaultUncaughtExceptionHandler(new Log4JUncaughtExceptionHandler());


        Session session = null;
        try {
            session = conf.initSessionBuilder().create();
        } catch (IOException | GeneralSecurityException | Session.SpotifyAuthenticationException | MercuryClient.MercuryException e) {
            e.printStackTrace();
        }
        Player player = new Player(conf.toPlayer(), session);

        Session finalSession = session;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                player.close();
                finalSession.close();
            } catch (IOException ignored) {
            }
        }));

        EventsShell.Configuration eventsShellConf = conf.toEventsShell();
        if (eventsShellConf.enabled) {
            EventsShell eventsShell = new EventsShell(eventsShellConf);
            Objects.requireNonNull(session);
            session.addReconnectionListener(eventsShell);
            player.addEventsListener(eventsShell);
        }

    }
}