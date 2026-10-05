# Public cross-plugin entry point bound reflectively by pam-native-background-transfer.
-keep class dev.pam.media.MediaTranscoding {
    public static java.lang.String transcode(android.content.Context, java.io.File, java.io.File, java.lang.String, java.util.function.BooleanSupplier, java.util.function.DoubleConsumer);
}
