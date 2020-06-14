package org.thoughtcrime.securesms.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Pair;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

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
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.video.exo.AttachmentDataSourceFactory;

public class AudioPlayerService extends Service {
  private static final String TAG             = AudioPlayerService.class.getSimpleName();
  private static final int    FOREGROUND_ID   = 313499;
  private static final int    IDLE_STOP_MS    = 60 * 1000;
  public  static final String MEDIA_URI_EXTRA = "AudioPlayerService_media_uri_extra";
  public  static final String PROGRESS_EXTRA  = "AudioPlayerService_progress_extra";
  public  static final String COMMAND_EXTRA   = "AudioPlayerService_command_extra";

  public enum Command {
    UNKNOWN, PLAY, PAUSE, RESUME, CLOSE
  }

  private final     LocalBinder          binder               = new LocalBinder();
  private final     ProgressEventHandler progressEventHandler = new ProgressEventHandler(this);
  private final     Handler              stopTimerHandler     = new Handler();
  private final     Runnable             stopSelfRunnable     = new Runnable() {
    @Override public void run() {
      stopSelf();
    }
  };

  private           AudioManager         audioManager;
  private           SensorManager        sensorManager;
  private           Sensor               proximitySensor;
  private @Nullable WakeLock             wakeLock;
  private @Nullable SimpleExoPlayer      mediaPlayer;

  private           Uri                  mediaUri;
  private           double               progress;
  private           boolean              earpiece;
  private           long                 startTime;

  private final SensorEventListener sensorEventListener = new SensorEventListener() {
    @Override
    public void onSensorChanged(SensorEvent event) {
      if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) return;
      if (mediaPlayer == null || mediaPlayer.getPlaybackState() != Player.STATE_READY) return;

      int streamType;

      if (event.values[0] < 5f && event.values[0] != proximitySensor.getMaximumRange()) {
        streamType = AudioManager.STREAM_VOICE_CALL;
        earpiece = true;
      } else {
        streamType = AudioManager.STREAM_MUSIC;
        earpiece = false;
      }

      if (streamType == AudioManager.STREAM_VOICE_CALL &&
          mediaPlayer.getAudioStreamType() != streamType &&
          !audioManager.isWiredHeadsetOn()) {

        if (wakeLock != null) wakeLock.acquire();
        changeStreamType();
      } else if (streamType == AudioManager.STREAM_MUSIC &&
          mediaPlayer.getAudioStreamType() != streamType &&
          System.currentTimeMillis() - startTime > 500) {
        if (wakeLock != null) wakeLock.release();
        changeStreamType();
      }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
  };

  private final Player.EventListener playerEventListener = new Player.EventListener() {
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

            if (progress > 0) {
              mediaPlayer.seekTo((long) (mediaPlayer.getDuration() * progress));
            }
          }

          binder.notifyOnStart();
          progressEventHandler.sendEmptyMessage(0);
          break;

        case Player.STATE_ENDED:
          Log.i(TAG, "onComplete");
          synchronized (AudioPlayerService.this) {
            if (wakeLock != null && wakeLock.isHeld()) {
              if (Build.VERSION.SDK_INT >= 21) {
                wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
              }
            }
          }

          binder.notifyOnStop();
          progressEventHandler.removeMessages(0);
          stopSelf();
      }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
      Log.w(TAG, "MediaPlayer Error: " + error);

      synchronized (AudioPlayerService.this) {
        if (wakeLock != null && wakeLock.isHeld()) {
          if (Build.VERSION.SDK_INT >= 21) {
            wakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
          }
        }
      }

      binder.notifyOnStop();
      binder.notifyOnError(error);
      progressEventHandler.removeMessages(0);
    }
  };

  @Override
  public void onCreate() {
    super.onCreate();
    audioManager    = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
    sensorManager   = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

    sensorManager.registerListener(sensorEventListener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);

    if (Build.VERSION.SDK_INT >= 21) {
      this.wakeLock = ServiceUtil.getPowerManager(this).newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG);
    } else {
      this.wakeLock = null;
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    sensorManager.unregisterListener(sensorEventListener);
    if (mediaPlayer != null) {
      mediaPlayer.removeListener(playerEventListener);
      mediaPlayer.stop();
      mediaPlayer.release();
    }
    mediaPlayer = null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Command command = (Command) intent.getSerializableExtra(COMMAND_EXTRA);
    switch (command) {
      case PLAY:
        mediaUri = intent.getParcelableExtra(MEDIA_URI_EXTRA);
        progress = intent.getDoubleExtra(PROGRESS_EXTRA, 0);
        startForeground(FOREGROUND_ID, createNotification(command));
        play();
        break;
      case PAUSE:
        pause();
        break;
      case RESUME:
        resume();
        break;
      case CLOSE:
        stopService();
        break;
      default:
        break;
    }
    return Service.START_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    // returns true because clients can rebind to this service
    return true;
  }

  private Notification createNotification(Command command) {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NotificationChannels.OTHER);
    builder.setPriority(NotificationCompat.PRIORITY_MIN);
    builder.setWhen(0);
    builder.setSmallIcon(R.drawable.ic_signal_grey_24dp);

    addActionsTo(builder, command);

    return builder.build();
  }

  private void addActionsTo(NotificationCompat.Builder builder, Command command) {
    Intent closeIntent = new Intent(this, AudioPlayerService.class);
    closeIntent.putExtra(COMMAND_EXTRA, Command.CLOSE);
    PendingIntent piClose = PendingIntent.getService(this, Command.CLOSE.ordinal(), closeIntent, 0);
    switch (command) {
      case PLAY:
      case RESUME:
        builder.setContentTitle(getApplicationContext().getString(R.string.AudioPlayerService_notification_title));
        builder.setContentText(getApplicationContext().getString(R.string.AudioPlayerService_notification_message));
        Intent pauseIntent = new Intent(this, AudioPlayerService.class);
        pauseIntent.putExtra(COMMAND_EXTRA, Command.PAUSE);
        PendingIntent piPause = PendingIntent.getService(this, Command.PAUSE.ordinal(), pauseIntent, 0);
        builder.addAction(0, getApplicationContext().getString(R.string.AudioPlayerService_action_pause), piPause);
        builder.addAction(0, getApplicationContext().getString(R.string.AudioPlayerService_action_close), piClose);
        break;
      case PAUSE:
        builder.setContentTitle(getApplicationContext().getString(R.string.AudioPlayerService_notification_title));
        builder.setContentText(getApplicationContext().getString(R.string.AudioPlayerService_notification_message));
        Intent resumeIntent = new Intent(this, AudioPlayerService.class);
        resumeIntent.putExtra(COMMAND_EXTRA, Command.RESUME);
        PendingIntent piResume = PendingIntent.getService(this, Command.RESUME.ordinal(), resumeIntent, 0);
        builder.addAction(0, getApplicationContext().getString(R.string.AudioPlayerService_action_resume), piResume);
        builder.addAction(0, getApplicationContext().getString(R.string.AudioPlayerService_action_close), piClose);
        break;
      case CLOSE:
        builder.setContentTitle(getApplicationContext().getString(R.string.AudioPlayerService_notification_title_finished));
        builder.setContentText(getApplicationContext().getString(R.string.AudioPlayerService_notification_message_finished));
      default:
        break;
    }
  }

  private void startStopTimer() {
    stopTimerHandler.postDelayed(stopSelfRunnable, IDLE_STOP_MS);
  }

  private void stopStopTimer() {
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
    mediaPlayer.addListener(playerEventListener);

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
    startTime = System.currentTimeMillis();
  }

  private void resume() {
    play();
    NotificationManagerCompat.from(this).notify(FOREGROUND_ID, createNotification(Command.RESUME));
  }

  private void pause() {
    if (mediaPlayer == null) return;
    progress = getProgress().first;
    mediaPlayer.stop();
    mediaPlayer.release();
    mediaPlayer = null;
    binder.notifyOnStop();
    NotificationManagerCompat.from(this).notify(FOREGROUND_ID, createNotification(Command.PAUSE));
  }

  /** Call when the mediaPlayer must change the stream type i.e. where to output audio. */
  private void changeStreamType() {
    if (mediaPlayer == null) return;
    progress = getProgress().first;
    mediaPlayer.stop();
    mediaPlayer.release();
    play();
  }

  private void stopService() {
    pause();
    mediaUri = null;
    progress = 0;
    earpiece = false;
    stopSelf();
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
      AudioPlayerService.this.pause();
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
