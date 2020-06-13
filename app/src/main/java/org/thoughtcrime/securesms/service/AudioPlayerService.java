package org.thoughtcrime.securesms.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Pair;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import java.lang.ref.WeakReference;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.video.exo.AttachmentDataSourceFactory;

public class AudioPlayerService extends Service {
  private static final String TAG             = AudioPlayerService.class.getSimpleName();
  private static final int    FOREGROUND_ID   = 313499;
  private static final int    IDLE_STOP_MS    = 60 * 1000;
  public  static final String MEDIA_URI_EXTRA = "AudioPlayerService_media_uri_extra";
  public  static final String PROGRESS_EXTRA  = "AudioPlayerService_progress_extra";
  public  static final String EARPIECE_EXTRA  = "AudioPlayerService_earpiece_extra";

  private final     LocalBinder          binder               = new LocalBinder();
  private final     ProgressEventHandler progressEventHandler = new ProgressEventHandler(this);
  private final     Handler              stopTimerHandler     = new Handler();
  private final     Runnable             stopSelfRunnable     = new Runnable() {
    @Override public void run() {
      Log.d(TAG, "stopping");
      stopSelf();
    }
  };

  private @Nullable SimpleExoPlayer      mediaPlayer;

  private           Uri                  mediaUri;
  private           double               progress;
  private           boolean              earpiece;
  private final     Player.EventListener eventListener = new Player.EventListener() {

    boolean started = false;

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      Log.d(TAG, "onPlayerStateChanged(" + playWhenReady + ", " + playbackState + ")");
      switch (playbackState) {
        case Player.STATE_IDLE:
          startStopTimer();
          break;
        case Player.STATE_BUFFERING:
          stopStopTimer();
          break;
        case Player.STATE_READY:
          stopStopTimer();
          Log.i(TAG, "onPrepared() " + mediaPlayer.getBufferedPercentage() + "% buffered");
          synchronized (AudioPlayerService.this) {
            if (mediaPlayer == null) return;

            if (started) {
              Log.d(TAG, "Already started. Ignoring.");
              return;
            }

//            started = true;

            if (progress > 0) {
              mediaPlayer.seekTo((long) (mediaPlayer.getDuration() * progress));
            }

//              sensorManager.registerListener(AudioPlayerService.this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
          }

          binder.notifyOnStart();
          progressEventHandler.sendEmptyMessage(0);
          break;

        case Player.STATE_ENDED:
          startStopTimer();
          Log.i(TAG, "onComplete");
          synchronized (AudioPlayerService.this) {

//              sensorManager.unregisterListener(AudioPlayerService.this);
//
//              if (wakeLock != null && wakeLock.isHeld()) {
//                if (Build.VERSION.SDK_INT >= 21) {
//                  wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
//                }
//              }
          }

          binder.notifyOnStop();
          progressEventHandler.removeMessages(0);
      }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
      Log.w(TAG, "MediaPlayer Error: " + error);

//      Toast.makeText(context, R.string.AudioSlidePlayer_error_playing_audio, Toast.LENGTH_SHORT).show();

      synchronized (AudioPlayerService.this) {

//        sensorManager.unregisterListener(AudioPlayerService.this);
//
//        if (wakeLock != null && wakeLock.isHeld()) {
//          if (Build.VERSION.SDK_INT >= 21) {
//            wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
//          }
//        }
      }

      binder.notifyOnStop();
      binder.notifyOnError(error);
      progressEventHandler.removeMessages(0);
    }
  };

  @Override
  public void onDestroy() {
    super.onDestroy();
    if (mediaPlayer != null) {
      mediaPlayer.removeListener(eventListener);
      mediaPlayer.stop();
      mediaPlayer.release();
    }
    mediaPlayer = null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    mediaUri = intent.getParcelableExtra(MEDIA_URI_EXTRA);
    progress = intent.getDoubleExtra(PROGRESS_EXTRA, 0);
    earpiece = intent.getBooleanExtra(EARPIECE_EXTRA, false);
    Log.d(TAG, "onStartCommand" + mediaUri.toString());
    startForeground(FOREGROUND_ID, createNotification());
    play();
    return Service.START_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    // Clients can rebind to this service
    return true;
  }

  private Notification createNotification() {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NotificationChannels.OTHER);
    builder.setContentTitle(getApplicationContext().getString(R.string.AudioPlayerService_notification_title));
    builder.setContentText(getApplicationContext().getString(R.string.AudioPlayerService_notification_message));
    builder.setPriority(NotificationCompat.PRIORITY_MIN);
    builder.setWhen(0);
    builder.setSmallIcon(R.drawable.ic_signal_grey_24dp);
    return builder.build();
  }

  private void startStopTimer() {
    Log.d(TAG, "start stop timer");
    stopTimerHandler.postDelayed(stopSelfRunnable, IDLE_STOP_MS);
  }

  private void stopStopTimer() {
    Log.d(TAG, "stop stop timer");
    stopTimerHandler.removeCallbacks(stopSelfRunnable);
  }

  private void play() {
    if (mediaUri == null) return;
    LoadControl loadControl = new DefaultLoadControl
        .Builder()
        .setBufferDurationsMs(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
        .createDefaultLoadControl();
    mediaPlayer = ExoPlayerFactory
        .newSimpleInstance(this, new DefaultTrackSelector(), loadControl);
    mediaPlayer.addListener(eventListener);

    DefaultDataSourceFactory defaultDataSourceFactory =
        new DefaultDataSourceFactory(this, "GenericUserAgent", null);
    AttachmentDataSourceFactory attachmentDataSourceFactory =
        new AttachmentDataSourceFactory(this, defaultDataSourceFactory, null);
    ExtractorsFactory extractorsFactory =
        new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
    ExtractorMediaSource mediaSource = new ExtractorMediaSource.Factory(attachmentDataSourceFactory)
        .setExtractorsFactory(extractorsFactory)
        .createMediaSource(mediaUri);
    mediaPlayer.prepare(mediaSource);
    mediaPlayer.setPlayWhenReady(true);
    mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
        .setContentType(earpiece ? C.CONTENT_TYPE_SPEECH : C.CONTENT_TYPE_MUSIC)
        .setUsage(earpiece ? C.USAGE_VOICE_COMMUNICATION : C.USAGE_MEDIA)
        .build());
  }

  private void stop() {
    mediaUri = null;
    progress = 0;
    earpiece = false;

    if (mediaPlayer == null) return;
    mediaPlayer.stop();
    mediaPlayer.release();
    mediaPlayer = null;

//    sensorManager.unregisterListener(AudioPlayerService.this);
  }

  private Pair<Double, Integer> getProgress() {
    if (mediaPlayer == null || mediaPlayer.getCurrentPosition() <= 0 || mediaPlayer.getDuration() <= 0) {
      return new Pair<>(0D, 0);
    } else {
      return new Pair<>((double) mediaPlayer.getCurrentPosition() / (double) mediaPlayer.getDuration(),
          (int) mediaPlayer.getCurrentPosition());
    }
  }

  public class LocalBinder extends Binder {
    private AudioStateListener listener;

    public AudioPlayerService getService() {
      return AudioPlayerService.this;
    }

    public void stop() {
      AudioPlayerService.this.stop();
      notifyOnStop();
    }

    private void notifyOnStart() {
      if (listener == null) return;
      listener.onAudioStarted();
    }

    private void notifyOnStop() {
      if (listener == null) return;
      listener.onAudioStopped();
    }

    private void notifyOnError(ExoPlaybackException error) {
      if (listener == null) return;
      listener.onAudioError(error);
    }

    private void notifyOnProgress(final double progress, final long millis) {
      if (listener == null) return;
      listener.onAudioProgress(progress, millis);
    }

    public void setListener(AudioStateListener listener) {
      this.listener = listener;
    }
  }

  private static class ProgressEventHandler extends Handler {

    private final WeakReference<AudioPlayerService> playerReference;

    private ProgressEventHandler(@NonNull AudioPlayerService player) {
      this.playerReference = new WeakReference<>(player);
    }

    @Override
    public void handleMessage(Message msg) {
      AudioPlayerService player = playerReference.get();

      if (player == null || player.mediaPlayer == null || !isPlayerActive(player.mediaPlayer)) {
        return;
      }

      Pair<Double, Integer> progress = player.getProgress();
      player.binder.notifyOnProgress(progress.first, progress.second);
      sendEmptyMessageDelayed(0, 50);
    }

    private boolean isPlayerActive(@NonNull SimpleExoPlayer player) {
      return player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING;
    }
  }

  public interface AudioStateListener {
    void onAudioStarted();
    void onAudioStopped();
    void onAudioError(final ExoPlaybackException error);
    void onAudioProgress(final double progress, final long millis);
  }
}
