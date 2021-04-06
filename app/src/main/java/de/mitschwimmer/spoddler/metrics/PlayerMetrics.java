package de.mitschwimmer.spoddler.metrics;

import org.jetbrains.annotations.Nullable;

import de.mitschwimmer.spoddler.codecs.Codec;
import de.mitschwimmer.spoddler.codecs.Mp3Codec;
import de.mitschwimmer.spoddler.codecs.VorbisCodec;
import de.mitschwimmer.spoddler.crossfade.CrossfadeController;
import de.mitschwimmer.spoddler.soundadapter.AudioFormat;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;


/**
 * @author devgianlu
 */
public final class PlayerMetrics {
    public final PlayableContentFeeder.Metrics contentMetrics;
    public int decodedLength = 0;
    public int size = 0;
    public int bitrate = 0;
    public int duration = 0;
    public String encoding = null;
    public int fadeOverlap = 0;
    public String transition = "none";
    public int decryptTime = 0;

    public PlayerMetrics(@Nullable PlayableContentFeeder.Metrics contentMetrics, @Nullable CrossfadeController crossfade, @Nullable Codec codec) {
        this.contentMetrics = contentMetrics;

        if (codec != null) {
            size = codec.size();
            duration = codec.duration();
            decodedLength = codec.decodedLength();
            decryptTime = codec.decryptTimeMs();

            AudioFormat format = codec.getAudioFormat();
            bitrate = (int) (format.getFrameRate() * format.getFrameSize());

            if (codec instanceof VorbisCodec) encoding = "vorbis";
            else if (codec instanceof Mp3Codec) encoding = "mp3";
        }

        if (crossfade != null) {
            transition = "crossfade";
            fadeOverlap = crossfade.fadeOverlap();
        }
    }
}
