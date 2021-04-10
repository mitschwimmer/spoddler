package de.mitschwimmer.spoddler.codecs;

import android.util.*;

import com.spotify.metadata.Metadata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import xyz.gianlu.librespot.audio.format.AudioQualityPicker;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;
import xyz.gianlu.librespot.common.Utils;

import java.util.List;

/**
 * @author Gianlu
 */
public final class VorbisOnlyAudioQuality implements AudioQualityPicker {
    private static final String TAG = "spoddler.VorbisOnlyAudioQuality";
    private final AudioQuality preferred;

    public VorbisOnlyAudioQuality(@NotNull AudioQuality preferred) {
        this.preferred = preferred;
    }

    @Nullable
    public static Metadata.AudioFile getVorbisFile(@NotNull List<Metadata.AudioFile> files) {
        for (Metadata.AudioFile file : files) {
            if (file.hasFormat() && SuperAudioFormat.get(file.getFormat()) == SuperAudioFormat.VORBIS)
                return file;
        }

        return null;
    }

    @Override
    public @Nullable Metadata.AudioFile getFile(@NotNull List<Metadata.AudioFile> files) {
        List<Metadata.AudioFile> matches = preferred.getMatches(files);
        Metadata.AudioFile vorbis = getVorbisFile(matches);
        if (vorbis == null) {
            vorbis = getVorbisFile(files);
            if (vorbis != null)
                Log.w(TAG, String.format("Using {%s} because preferred {%s} couldn't be found.", vorbis.getFormat(), preferred));
            else
                Log.e(TAG, String.format("Couldn't find any Vorbis file, available: {%s}", Utils.formatsToString(files)));
        }

        return vorbis;
    }
}
