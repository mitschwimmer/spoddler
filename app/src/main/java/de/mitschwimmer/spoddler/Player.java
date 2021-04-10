package de.mitschwimmer.spoddler;

import android.util.*;

import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.context.ContextTrackOuterClass.ContextTrack;
import com.spotify.metadata.Metadata;
import com.spotify.transfer.TransferStateOuterClass;

import de.mitschwimmer.spoddler.codecs.Codec;
import de.mitschwimmer.spoddler.contexts.AbsSpotifyContext;
import de.mitschwimmer.spoddler.events.EventsMetadataPipe;
import de.mitschwimmer.spoddler.metrics.NewPlaybackIdEvent;
import de.mitschwimmer.spoddler.metrics.NewSessionIdEvent;
import de.mitschwimmer.spoddler.metrics.PlaybackMetrics;
import de.mitschwimmer.spoddler.metrics.PlayerMetrics;
import de.mitschwimmer.spoddler.mixing.AudioSink;
import de.mitschwimmer.spoddler.mixing.LineHelper;
import de.mitschwimmer.spoddler.playback.PlayerSession;
import de.mitschwimmer.spoddler.soundadapter.LineUnavailableException;
import de.mitschwimmer.spoddler.state.DeviceStateHandler;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import xyz.gianlu.librespot.audio.AbsChunkedInputStream;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.metadata.ImageId;
import xyz.gianlu.librespot.metadata.PlayableId;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.String.format;

/**
 * @author Gianlu
 */
public class Player implements Closeable {
    public static final int VOLUME_MAX = 65536;
    private static final String TAG = "spoddler.Player";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NameThreadFactory((r) -> "release-line-scheduler-" + r.hashCode()));
    private final Session session;
    private final PlayerConfiguration conf;
    private final EventsDispatcher events;
    private final AudioSink sink;
    private final Map<String, PlaybackMetrics> metrics = new HashMap<>(5);
    private StateWrapper state;
    private PlayerSession playerSession;
    private ScheduledFuture<?> releaseLineFuture = null;
    private DeviceStateHandler.Listener deviceStateListener;

    public Player(@NotNull PlayerConfiguration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.events = new EventsDispatcher(conf);
        this.sink = new AudioSink(conf, ex -> {
            if (ex instanceof LineHelper.MixerException || ex instanceof LineUnavailableException)
                Log.e(TAG, "An error with the mixer occurred. This is likely a configuration issue, please consult the project repository.", ex);
            else
                Log.e(TAG,"Sink error!", ex);

            panicState(PlaybackMetrics.Reason.TRACK_ERROR);
        });

        initState();
    }

    public void addEventsListener(@NotNull EventsListener listener) {
        events.listeners.add(listener);
    }

    public void removeEventsListener(@NotNull EventsListener listener) {
        events.listeners.remove(listener);
    }

    private void initState() {
        this.state = new StateWrapper(session, this, conf);
        state.addListener(deviceStateListener = new DeviceStateHandler.Listener() {
            @Override
            public void ready() {
                events.volumeChanged(state.getVolume());
            }

            @Override
            public void command(DeviceStateHandler.@NotNull Endpoint endpoint, @NotNull DeviceStateHandler.CommandBody data) throws InvalidProtocolBufferException {
                Log.d(TAG, "Received command: " + endpoint);

                switch (endpoint) {
                    case Play:
                        handlePlay(data.obj());
                        break;
                    case Transfer:
                        handleTransferState(TransferStateOuterClass.TransferState.parseFrom(data.data()));
                        break;
                    case Resume:
                        handleResume();
                        break;
                    case Pause:
                        handlePause();
                        break;
                    case SeekTo:
                        handleSeek(data.valueInt());
                        break;
                    case SkipNext:
                        handleSkipNext(data.obj(), TransitionInfo.skippedNext(state));
                        break;
                    case SkipPrev:
                        handleSkipPrev();
                        break;
                    case SetRepeatingContext:
                        state.setRepeatingContext(data.valueBool());
                        state.updated();
                        break;
                    case SetRepeatingTrack:
                        state.setRepeatingTrack(data.valueBool());
                        state.updated();
                        break;
                    case SetShufflingContext:
                        state.setShufflingContext(data.valueBool());
                        state.updated();
                        break;
                    case AddToQueue:
                        handleAddToQueue(data.obj());
                        break;
                    case SetQueue:
                        handleSetQueue(data.obj());
                        break;
                    case UpdateContext:
                        state.updateContext(DeviceStateHandler.PlayCommandHelper.getContext(data.obj()));
                        state.updated();
                        break;
                    default:
                        Log.w(TAG, "Endpoint left unhandled: " + endpoint);
                        break;
                }
            }

            @Override
            public void volumeChanged() {
                int vol = state.getVolume();
                if (!conf.bypassSinkVolume) sink.setVolume(vol);
                events.volumeChanged(vol);
            }

            @Override
            public void notActive() {
                events.inactiveSession(false);
                sink.pause(true);
            }
        });
    }

    // ================================ //
    // =========== Commands =========== //
    // ================================ //

    public void volumeUp() {
        this.volumeUp(1);
    }

    public void volumeUp(int steps) {
        if (state == null) return;
        setVolume(Math.min(Player.VOLUME_MAX, state.getVolume() + steps * oneVolumeStep()));
    }

    public void volumeDown() {
        this.volumeDown(1);
    }

    public void volumeDown(int steps) {
        if (state == null) return;
        setVolume(Math.max(0, state.getVolume() - steps * oneVolumeStep()));
    }

    private int oneVolumeStep() {
        return Player.VOLUME_MAX / conf.volumeSteps;
    }

    public void setVolume(int val) {
        if (val < 0 || val > VOLUME_MAX)
            throw new IllegalArgumentException(String.valueOf(val));

        if (state == null) return;
        state.setVolume(val);
    }

    public void setShuffle(boolean val) {
        state.setShufflingContext(val);
        state.updated();
    }

    public void setRepeat(boolean track, boolean context) {
        if (track && context)
            throw new IllegalArgumentException("Cannot repeat track and context simultaneously.");

        if (track) {
            state.setRepeatingTrack(true);
        } else if (context) {
            state.setRepeatingContext(true);
        } else {
            state.setRepeatingContext(false);
            state.setRepeatingTrack(false);
        }

        state.updated();
    }

    public void play() {
        handleResume();
    }

    public void playPause() {
        if (state.isPaused()) handleResume();
        else handlePause();
    }

    public void pause() {
        handlePause();
    }

    public void next() {
        handleSkipNext(null, TransitionInfo.skippedNext(state));
    }

    public void previous() {
        handleSkipPrev();
    }

    public void seek(int pos) {
        handleSeek(pos);
    }

    public void load(@NotNull String uri, boolean play, boolean shuffle) {
        try {
            String sessionId = state.loadContext(uri);
            events.contextChanged();

            state.setShufflingContext(shuffle);

            loadSession(sessionId, play, true);
        } catch (IOException | MercuryClient.MercuryException ex) {
            Log.e(TAG, "Failed loading context!", ex);
            panicState(null);
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            Log.e(TAG, "Cannot play local tracks!", ex);
            panicState(null);
        }
    }

    public void addToQueue(@NotNull String uri) {
        state.addToQueue(ContextTrack.newBuilder().setUri(uri).build());
        state.updated();
    }

    public void removeFromQueue(@NotNull String uri) {
        state.removeFromQueue(uri);
        state.updated();
    }

    @NotNull
    public Future<Player> ready() {
        CompletableFuture<Player> future = new CompletableFuture<>();
        if (state.isReady()) {
            future.complete(this);
            return future;
        }

        state.addListener(new DeviceStateHandler.Listener() {
            @Override
            public void ready() {
                state.removeListener(this);
                future.complete(Player.this);
            }

            @Override
            public void command(@NotNull DeviceStateHandler.Endpoint endpoint, @NotNull DeviceStateHandler.CommandBody data) {
            }

            @Override
            public void volumeChanged() {
            }

            @Override
            public void notActive() {
            }
        });
        return future;
    }

    public void waitReady() throws InterruptedException {
        try {
            ready().get();
        } catch (ExecutionException ignored) {
        }
    }


    // ================================ //
    // ======== Internal state ======== //
    // ================================ //

    /**
     * Enter a "panic" state where everything is stopped.
     *
     * @param reason Why we entered this state
     */
    private void panicState(@Nullable PlaybackMetrics.Reason reason) {
        sink.pause(true);
        state.setState(false, false, false);
        state.updated();

        if (reason == null) {
            metrics.clear();
        } else if (playerSession != null) {
            endMetrics(playerSession.currentPlaybackId(), reason, playerSession.currentMetrics(), state.getPosition());
        }

        events.panicState();
    }

    /**
     * Loads a new session by creating a new {@link PlayerSession}. Will also trigger {@link Player#loadTrack(boolean, TransitionInfo)}.
     *
     * @param sessionId The new session ID
     * @param play      Whether the playback should start immediately
     */
    private void loadSession(@NotNull String sessionId, boolean play, boolean withSkip) {
        Log.d(TAG, format("Loading session, id: {%s}, play: {%s}", sessionId, play));

        TransitionInfo trans = TransitionInfo.contextChange(state, withSkip);

        if (playerSession != null) {
            endMetrics(playerSession.currentPlaybackId(), trans.endedReason, playerSession.currentMetrics(), trans.endedWhen);

            playerSession.close();
            playerSession = null;
        }

        playerSession = new PlayerSession(session, sink, conf, sessionId, new PlayerSession.Listener() {
            @Override
            public void startedLoading() {
                if (!state.isPaused()) {
                    state.setBuffering(true);
                    state.updated();
                }
            }

            @Override
            public void finishedLoading(@NotNull TrackOrEpisode metadata) {
                state.enrichWithMetadata(metadata);
                state.setBuffering(false);
                state.updated();

                events.metadataAvailable();
            }

            @Override
            public void loadingError(@NotNull Exception ex) {
                if (ex instanceof PlayableContentFeeder.ContentRestrictedException) {
                    Log.e(TAG, "Can't load track (content restricted).", ex);
                } else {
                    Log.e(TAG, "Failed loading track.", ex);
                    panicState(PlaybackMetrics.Reason.TRACK_ERROR);
                }
            }

            @Override
            public void playbackError(@NotNull Exception ex) {
                if (ex instanceof AbsChunkedInputStream.ChunkException)
                    Log.e(TAG, "Failed retrieving chunk, playback failed!", ex);
                else
                    Log.e(TAG, "Playback error!", ex);

                panicState(PlaybackMetrics.Reason.TRACK_ERROR);
            }

            @Override
            public void trackChanged(@NotNull String playbackId, @Nullable TrackOrEpisode metadata, int pos, @NotNull PlaybackMetrics.Reason startedReason) {
                if (metadata != null) state.enrichWithMetadata(metadata);
                state.setPlaybackId(playbackId);
                state.setPosition(pos);
                state.updated();

                events.trackChanged();
                events.metadataAvailable();

                session.eventService().sendEvent(new NewPlaybackIdEvent(state.getSessionId(), playbackId));
                startMetrics(playbackId, startedReason, pos);
            }

            @Override
            public void trackPlayed(@NotNull String playbackId, @NotNull PlaybackMetrics.Reason endReason, @NotNull PlayerMetrics playerMetrics, int when) {
                endMetrics(playbackId, endReason, playerMetrics, when);
                events.playbackEnded();
            }

            @Override
            public void playbackHalted(int chunk) {
                Log.d(TAG, format("Playback halted on retrieving chunk {%s}.", chunk));
                state.setBuffering(true);
                state.updated();

                events.playbackHaltStateChanged(true);
            }

            @Override
            public void playbackResumedFromHalt(int chunk, long diff) {
                Log.d(TAG, format("Playback resumed, chunk {%s} retrieved, took {%s}ms.", chunk, diff));
                state.setPosition(state.getPosition() - diff);
                state.setBuffering(false);
                state.updated();

                events.playbackHaltStateChanged(false);
            }

            @Override
            public @NotNull PlayableId currentPlayable() {
                return state.getCurrentPlayableOrThrow();
            }

            @Override
            public @Nullable PlayableId nextPlayable() {
                StateWrapper.NextPlayable next = state.nextPlayable(conf.autoplayEnabled);
                if (next == StateWrapper.NextPlayable.AUTOPLAY) {
                    loadAutoplay();
                    return null;
                }

                if (next.isOk()) {
                    if (next != StateWrapper.NextPlayable.OK_PLAY && next != StateWrapper.NextPlayable.OK_REPEAT)
                        sink.pause(false);

                    return state.getCurrentPlayableOrThrow();
                } else {
                    Log.e(TAG, "Failed loading next song: " + next);
                    panicState(PlaybackMetrics.Reason.END_PLAY);
                    return null;
                }
            }

            @Override
            public @Nullable PlayableId nextPlayableDoNotSet() {
                return state.nextPlayableDoNotSet();
            }

            @Override
            public @NotNull Optional<Map<String, String>> metadataFor(@NotNull PlayableId playable) {
                return state.metadataFor(playable);
            }
        });
        session.eventService().sendEvent(new NewSessionIdEvent(sessionId, state));

        loadTrack(play, trans);
    }

    /**
     * Loads a new track and pauses/resumes the sink accordingly.
     *
     * <b>This is called only to change track due to an external command (user interaction).</b>
     *
     * @param play  Whether the playback should start immediately
     * @param trans A {@link TransitionInfo} object containing information about this track change
     */
    private void loadTrack(boolean play, @NotNull TransitionInfo trans) {
        endMetrics(playerSession.currentPlaybackId(), trans.endedReason, playerSession.currentMetrics(), trans.endedWhen);

        Log.d(TAG, format("Loading track, id: {%s}, session: {%s}, playback: {%s}, play: {%s}", state.getCurrentPlayable(), playerSession.sessionId(), playerSession.currentPlaybackId(), play));
        String playbackId = playerSession.play(state.getCurrentPlayableOrThrow(), state.getPosition(), trans.startedReason);
        state.setPlaybackId(playbackId);
        session.eventService().sendEvent(new NewPlaybackIdEvent(state.getSessionId(), playbackId));

        if (play) sink.resume();
        else sink.pause(false);

        state.setState(true, !play, true);
        state.updated();

        events.trackChanged();
        if (play) events.playbackResumed();
        else events.playbackPaused();

        startMetrics(playbackId, trans.startedReason, state.getPosition());

        if (releaseLineFuture != null) {
            releaseLineFuture.cancel(true);
            releaseLineFuture = null;
        }
    }

    private void handlePlay(@NotNull JsonObject obj) {
        Log.d(TAG, format("Loading context (play), uri: {%s}", DeviceStateHandler.PlayCommandHelper.getContextUri(obj)));

        try {
            String sessionId = state.load(obj);
            events.contextChanged();

            Boolean paused = DeviceStateHandler.PlayCommandHelper.isInitiallyPaused(obj);
            if (paused == null) paused = false;
            loadSession(sessionId, !paused, DeviceStateHandler.PlayCommandHelper.willSkipToSomething(obj));
        } catch (IOException | MercuryClient.MercuryException ex) {
            Log.e(TAG, "Failed loading context!", ex);
            panicState(null);
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            Log.e(TAG, "Cannot play local tracks!", ex);
            panicState(null);
        }
    }

    private void handleTransferState(@NotNull TransferStateOuterClass.TransferState cmd) {
        Log.d(TAG, format("Loading context (transfer), uri: {%s}", cmd.getCurrentSession().getContext().getUri()));

        try {
            String sessionId = state.transfer(cmd);
            events.contextChanged();
            loadSession(sessionId, !cmd.getPlayback().getIsPaused(), true);
        } catch (IOException | MercuryClient.MercuryException ex) {
            Log.e(TAG, "Failed loading context!", ex);
            panicState(null);
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            Log.e(TAG, "Cannot play local tracks!", ex);
            panicState(null);
        }
    }

    private void handleSeek(int pos) {
        playerSession.seekCurrent(pos);
        state.setPosition(pos);
        events.seeked(pos);

        PlaybackMetrics pm = metrics.get(playerSession.currentPlaybackId());
        if (pm != null) {
            pm.endInterval(state.getPosition());
            pm.startInterval(pos);
        }
    }

    private void handleResume() {
        if (state.isPaused()) {
            state.setState(true, false, false);
            sink.resume();

            state.updated();
            events.playbackResumed();

            if (releaseLineFuture != null) {
                releaseLineFuture.cancel(true);
                releaseLineFuture = null;
            }
        }
    }

    private void handlePause() {
        if (!state.isPaused()) {
            state.setState(true, true, false);
            sink.pause(false);

            try {
                if (playerSession != null)
                    state.setPosition(playerSession.currentTime());
            } catch (Codec.CannotGetTimeException ex) {
                state.setPosition(state.getPosition());
            }

            state.updated();
            events.playbackPaused();

            if (releaseLineFuture != null) releaseLineFuture.cancel(true);
            releaseLineFuture = scheduler.schedule(() -> {
                if (!state.isPaused()) return;

                events.inactiveSession(true);
                sink.pause(true);
            }, conf.releaseLineDelay, TimeUnit.SECONDS);
        }
    }

    private void handleSetQueue(@NotNull JsonObject obj) {
        List<ContextTrack> prevTracks = DeviceStateHandler.PlayCommandHelper.getPrevTracks(obj);
        List<ContextTrack> nextTracks = DeviceStateHandler.PlayCommandHelper.getNextTracks(obj);
        if (prevTracks == null && nextTracks == null) throw new IllegalArgumentException();

        state.setQueue(prevTracks, nextTracks);
        state.updated();
    }

    private void handleAddToQueue(@NotNull JsonObject obj) {
        ContextTrack track = DeviceStateHandler.PlayCommandHelper.getTrack(obj);
        if (track == null) throw new IllegalArgumentException();

        state.addToQueue(track);
        state.updated();
    }

    private void handleSkipNext(@Nullable JsonObject obj, @NotNull TransitionInfo trans) {
        ContextTrack track = null;
        if (obj != null) track = DeviceStateHandler.PlayCommandHelper.getTrack(obj);

        if (track != null) {
            state.skipTo(track);
            loadTrack(true, TransitionInfo.skipTo(state));
            return;
        }

        StateWrapper.NextPlayable next = state.nextPlayable(conf.autoplayEnabled);
        if (next == StateWrapper.NextPlayable.AUTOPLAY) {
            loadAutoplay();
            return;
        }

        if (next.isOk()) {
            trans.endedWhen = state.getPosition();

            state.setPosition(0);
            loadTrack(next == StateWrapper.NextPlayable.OK_PLAY || next == StateWrapper.NextPlayable.OK_REPEAT, trans);
        } else {
            Log.e(TAG, "Failed loading next song: " + next);
            panicState(PlaybackMetrics.Reason.END_PLAY);
        }
    }

    private void handleSkipPrev() {
        if (state.getPosition() < 3000) {
            StateWrapper.PreviousPlayable prev = state.previousPlayable();
            if (prev.isOk()) {
                state.setPosition(0);
                loadTrack(true, TransitionInfo.skippedPrev(state));
            } else {
                Log.e(TAG, "Failed loading previous song: " + prev);
                panicState(null);
            }
        } else {
            playerSession.seekCurrent(0);
            state.setPosition(0);
            state.updated();
        }
    }

    /**
     * Tries to load some additional content to play and starts playing if successful.
     */
    private void loadAutoplay() {
        String context = state.getContextUri();
        if (context == null) {
            Log.e(TAG, "Cannot load autoplay with null context!");
            panicState(null);
            return;
        }


        String contextDesc = state.getContextMetadata("context_description");

        try {
            MercuryClient.Response resp = session.mercury().sendSync(MercuryRequests.autoplayQuery(context));
            if (resp.statusCode == 200) {
                String newContext = resp.payload.readIntoString(0);
                String sessionId = state.loadContext(newContext);
                state.setContextMetadata("context_description", contextDesc);

                events.contextChanged();
                loadSession(sessionId, true, false);

                Log.d(TAG, format("Loading context for autoplay, uri: {}", newContext));
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                String sessionId = state.loadContextWithTracks(station.uri(), station.tracks());
                state.setContextMetadata("context_description", contextDesc);

                events.contextChanged();
                loadSession(sessionId, true, false);

                Log.d(TAG, format("Loading context for autoplay (using radio-apollo), uri: {}", state.getContextUri()));
            } else {
                Log.e(TAG, "Failed retrieving autoplay context, code: " + resp.statusCode);

                state.setPosition(0);
                state.setState(true, false, false);
                state.updated();
            }
        } catch (IOException | MercuryClient.MercuryException ex) {
            if (ex instanceof MercuryClient.MercuryException && ((MercuryClient.MercuryException) ex).code == 400) {
                Log.i(TAG, "Cannot load autoplay for search context: " + context);

                state.setPosition(0);
                state.setState(true, true, false);
                state.updated();
            } else {
                Log.e(TAG, "Failed loading autoplay station!", ex);
                panicState(null);
            }
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            Log.e(TAG, "Cannot play local tracks!", ex);
            panicState(null);
        }
    }


    // ================================ //
    // =========== Metrics ============ //
    // ================================ //

    private void startMetrics(String playbackId, @NotNull PlaybackMetrics.Reason reason, int pos) {
        PlaybackMetrics pm = new PlaybackMetrics(state.getCurrentPlayableOrThrow(), playbackId, state);
        pm.startedHow(reason, state.getPlayOrigin().getFeatureIdentifier());
        pm.startInterval(pos);
        metrics.put(playbackId, pm);
    }

    private void endMetrics(String playbackId, @NotNull PlaybackMetrics.Reason reason, @Nullable PlayerMetrics playerMetrics, int when) {
        if (playbackId == null) return;

        PlaybackMetrics pm = metrics.remove(playbackId);
        if (pm == null) return;

        pm.endedHow(reason, state.getPlayOrigin().getFeatureIdentifier());
        pm.endInterval(when);
        pm.update(playerMetrics);
        pm.sendEvents(session, state.device());
    }


    // ================================ //
    // =========== Getters ============ //
    // ================================ //

    /**
     * @return The current {@link PlayableId} or {@code null}
     */
    public @Nullable PlayableId currentPlayable() {
        return state.getCurrentPlayable();
    }

    /**
     * @return Whether the player is active
     */
    public boolean isActive() {
        return state != null && state.isActive();
    }

    /**
     * @return A {@link Tracks} instance with the current player queue
     */
    @NotNull
    public Tracks tracks(boolean withQueue) {
        return new Tracks(state.getPrevTracks(), state.getCurrentTrack(), state.getNextTracks(withQueue));
    }

    /**
     * @return The metadata for the current entry or {@code null} if not available.
     */
    @Nullable
    public TrackOrEpisode currentMetadata() {
        return playerSession == null ? null : playerSession.currentMetadata();
    }

    /**
     * @return The cover image bytes for the current entry or {@code null} if not available.
     * @throws IOException If an error occurred while downloading the image
     */
    @Nullable
    public byte[] currentCoverImage() throws IOException {
        TrackOrEpisode metadata = currentMetadata();
        if (metadata == null) return null;

        ImageId image = null;
        Metadata.ImageGroup group = metadata.getCoverImage();
        if (group == null) {
            PlayableId id = state.getCurrentPlayable();
            if (id == null) return null;

            Map<String, String> map = state.metadataFor(id).orElse(null);
            if (map == null) return null;

            for (String key : ImageId.IMAGE_SIZES_URLS) {
                if (map.containsKey(key)) {
                    image = ImageId.fromUri(map.get(key));
                    break;
                }
            }
        } else {
            image = ImageId.biggestImage(group);
        }

        if (image == null)
            return null;

        try (Response resp = session.client().newCall(new Request.Builder()
                .url(session.getUserAttribute("image-url", "http://i.scdn.co/image/{file_id}").replace("{file_id}", image.hexId())).build())
                .execute()) {
            ResponseBody body;
            if (resp.code() == 200 && (body = resp.body()) != null)
                return body.bytes();
            else
                throw new IOException(format("Bad response code. {id: %s, code: %d}", image.hexId(), resp.code()));
        }
    }

    /**
     * @return The current position of the player or {@code -1} if unavailable (most likely if it's playing an episode).
     */
    public int time() {
        try {
            return playerSession == null ? -1 : playerSession.currentTime();
        } catch (Codec.CannotGetTimeException ex) {
            return -1;
        }
    }


    // ================================ //
    // ============ Close! ============ //
    // ================================ //

    @Override
    public void close() {
        if (playerSession != null) {
            endMetrics(playerSession.currentPlaybackId(), PlaybackMetrics.Reason.LOGOUT, playerSession.currentMetrics(), state.getPosition());
            playerSession.close();
        }

        state.close();

        sink.close();
        if (state != null && deviceStateListener != null)
            state.removeListener(deviceStateListener);

        scheduler.shutdown();
        events.close();

        Log.i(TAG, "Closed player.");
    }

    public interface EventsListener {
        void onContextChanged(@NotNull Player player, @NotNull String newUri);

        void onTrackChanged(@NotNull Player player, @NotNull PlayableId id, @Nullable TrackOrEpisode metadata);

        void onPlaybackEnded(@NotNull Player player);

        void onPlaybackPaused(@NotNull Player player, long trackTime);

        void onPlaybackResumed(@NotNull Player player, long trackTime);

        void onTrackSeeked(@NotNull Player player, long trackTime);

        void onMetadataAvailable(@NotNull Player player, @NotNull TrackOrEpisode metadata);

        void onPlaybackHaltStateChanged(@NotNull Player player, boolean halted, long trackTime);

        void onInactiveSession(@NotNull Player player, boolean timeout);

        void onVolumeChanged(@NotNull Player player, @Range(from = 0, to = 1) float volume);

        void onPanicState(@NotNull Player player);
    }

    /**
     * A simple object holding some {@link ContextTrack}s related to the current player state.
     */
    public static class Tracks {
        public final List<ContextTrack> previous;
        public final ContextTrack current;
        public final List<ContextTrack> next;

        Tracks(@NotNull List<ContextTrack> previous, @Nullable ContextTrack current, @NotNull List<ContextTrack> next) {
            this.previous = previous;
            this.current = current;
            this.next = next;
        }
    }

    /**
     * Stores information about the transition between two tracks.
     */
    private static class TransitionInfo {
        /**
         * How the <bold>next</bold> track started
         */
        final PlaybackMetrics.Reason startedReason;

        /**
         * How the <bold>previous</bold> track ended
         */
        final PlaybackMetrics.Reason endedReason;

        /**
         * When the <bold>previous</bold> track ended
         */
        int endedWhen = -1;

        private TransitionInfo(@NotNull PlaybackMetrics.Reason endedReason, @NotNull PlaybackMetrics.Reason startedReason) {
            this.startedReason = startedReason;
            this.endedReason = endedReason;
        }

        /**
         * Context changed.
         */
        @NotNull
        static TransitionInfo contextChange(@NotNull StateWrapper state, boolean withSkip) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.END_PLAY, withSkip ? PlaybackMetrics.Reason.CLICK_ROW : PlaybackMetrics.Reason.PLAY_BTN);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }

        /**
         * Skipping to another track in the same context.
         */
        @NotNull
        static TransitionInfo skipTo(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.END_PLAY, PlaybackMetrics.Reason.CLICK_ROW);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }

        /**
         * Skipping to previous track.
         */
        @NotNull
        static TransitionInfo skippedPrev(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.BACK_BTN, PlaybackMetrics.Reason.BACK_BTN);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }

        /**
         * Skipping to next track.
         */
        @NotNull
        static TransitionInfo skippedNext(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.FORWARD_BTN, PlaybackMetrics.Reason.FORWARD_BTN);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }
    }

    private class EventsDispatcher {
        private final ExecutorService executorService = Executors.newSingleThreadExecutor(new NameThreadFactory((r) -> "player-events-" + r.hashCode()));
        private final List<EventsListener> listeners = new ArrayList<>();

        EventsDispatcher(@NotNull PlayerConfiguration conf) {
            if (conf.metadataPipe != null) listeners.add(new EventsMetadataPipe(conf.metadataPipe));
        }

        void playbackEnded() {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackEnded(Player.this));
        }

        void playbackPaused() {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackPaused(Player.this, trackTime));
        }

        void playbackResumed() {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackResumed(Player.this, trackTime));
        }

        void contextChanged() {
            String uri = state.getContextUri();
            if (uri == null) return;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onContextChanged(Player.this, uri));
        }

        void trackChanged() {
            PlayableId id = state.getCurrentPlayable();
            if (id == null) return;

            TrackOrEpisode metadata = currentMetadata();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onTrackChanged(Player.this, id, metadata));
        }

        void seeked(int pos) {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onTrackSeeked(Player.this, pos));
        }

        void volumeChanged(@Range(from = 0, to = Player.VOLUME_MAX) int value) {
            float volume = (float) value / Player.VOLUME_MAX;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onVolumeChanged(Player.this, volume));
        }

        void metadataAvailable() {
            TrackOrEpisode metadata = currentMetadata();
            if (metadata == null) return;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onMetadataAvailable(Player.this, metadata));
        }

        void playbackHaltStateChanged(boolean halted) {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackHaltStateChanged(Player.this, halted, trackTime));
        }

        void inactiveSession(boolean timeout) {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onInactiveSession(Player.this, timeout));
        }

        private void panicState() {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPanicState(Player.this));
        }

        public void close() {
            executorService.shutdown();

            for (EventsListener l : listeners) {
                if (l instanceof Closeable) {
                    try {
                        ((Closeable) l).close();
                    } catch (IOException ignored) {
                    }
                }
            }

            listeners.clear();
        }
    }
}
