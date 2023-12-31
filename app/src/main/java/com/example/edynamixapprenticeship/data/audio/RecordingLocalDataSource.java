package com.example.edynamixapprenticeship.data.audio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.edynamixapprenticeship.model.audio.Recording;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.RealmResults;

public class RecordingLocalDataSource {
    private static final String LOG_TAG = RecordingLocalDataSource.class.getSimpleName();
    private final Context context;
    private final Realm realm;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private String currentRecordingLocation;
    private final MutableLiveData<Recording> currentlyPlayingRecording;

    @Inject
    public RecordingLocalDataSource(@ApplicationContext Context context) {
        this.context = context;
        this.currentlyPlayingRecording = new MutableLiveData<>(null);
        Realm.init(context);
        RealmConfiguration config = new RealmConfiguration.Builder()
                .deleteRealmIfMigrationNeeded()
                .build();
        realm = Realm.getInstance(config);
    }

    public void startRecording() {
        String fileName = "recording_" + System.currentTimeMillis() + ".3gp";

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri audioUri;
            try {
                audioUri = createAudioFileUri(context, fileName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(audioUri, "w");
                recorder.setOutputFile(pfd.getFileDescriptor());
                this.currentRecordingLocation = audioUri.toString();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        } else {
            File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
            File file = new File(directory, fileName);
            recorder.setOutputFile(currentRecordingLocation);
            this.currentRecordingLocation = file.getAbsolutePath();
        }

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            Log.e(LOG_TAG, "MediaRecorder prepare() failed");
            Log.e(LOG_TAG, e.getMessage());
        }
    }

    public void stopRecording() {
        recorder.stop();
        recorder.reset();
        recorder.release();
        recorder = null;

        String newRecordingLocation = currentRecordingLocation;
        Recording newRecording = new Recording(newRecordingLocation, getDurationOfRecording(newRecordingLocation));

        realm.executeTransactionAsync(bgRealm -> bgRealm.copyToRealmOrUpdate(newRecording));
    }

    public MutableLiveData<List<Recording>> getRecordings() {
        RealmResults<Recording> results = realm.where(Recording.class).findAllAsync();
        MutableLiveData<List<Recording>> data = new MutableLiveData<>();
        results.addChangeListener((recordings, changeSet) -> data.postValue(realm.copyFromRealm(recordings)));
        return data;
    }


    public void startPlaying(Recording recording) {
        if (currentlyPlayingRecording.getValue() != null) stopPlaying();

        player = new MediaPlayer();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                player.setDataSource(context, Uri.parse(recording.getLocation()));
            } else {
                player.setDataSource(recording.getLocation());
            }
            this.currentlyPlayingRecording.setValue(recording);
            player.setOnCompletionListener(mediaPlayer -> currentlyPlayingRecording.setValue(null));
            player.prepare();
            player.start();
        } catch (IOException e) {
            Log.e(LOG_TAG, "MediaPlayer prepare() failed");
            Log.e(LOG_TAG, e.getMessage());
            Log.e(LOG_TAG, recording.getLocation());
        }
    }

    public void stopPlaying() {
        player.release();
        player = null;
        this.currentlyPlayingRecording.setValue(null);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public Uri createAudioFileUri(Context context, String fileName) throws IOException {
        ContentResolver contentResolver = context.getContentResolver();
        Uri audioCollection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues newAudioDetails = new ContentValues();
        newAudioDetails.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        newAudioDetails.put(MediaStore.Audio.Media.MIME_TYPE, "audio/3gpp");
        newAudioDetails.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri audioUri = contentResolver.insert(audioCollection, newAudioDetails);

        if (audioUri == null) {
            throw new IOException("Failed to create new MediaStore record.");
        }

        return audioUri;
    }

    private long getDurationOfRecording(String location) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                retriever.setDataSource(context, Uri.parse(location));
            } else {
                retriever.setDataSource(context, Uri.parse(location));
            }

            retriever.setDataSource(context, Uri.parse(location));
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                retriever.close();
            }

            long durationMs = Long.parseLong(durationStr);
            return TimeUnit.MILLISECONDS.toSeconds(durationMs);

        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void close() {
        if (!realm.isClosed()) realm.close();
    }

    public LiveData<Recording> getCurrentlyPlayingRecording() {
        return currentlyPlayingRecording;
    }
}
