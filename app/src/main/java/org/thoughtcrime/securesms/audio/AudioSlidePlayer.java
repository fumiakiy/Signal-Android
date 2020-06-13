package org.thoughtcrime.securesms.audio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlaybackException;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.AudioSlide;
import org.thoughtcrime.securesms.service.AudioPlayerService;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.lang.ref.WeakReference;

public class AudioSlidePlayer implements AudioPlayerService.AudioStateListener {

  private static final String TAG = AudioSlidePlayer.class.getSimpleName();

  private static @NonNull Optional<AudioSlidePlayer> playing = Optional.absent();

  private final @NonNull  Context           context;
  private final @NonNull  AudioSlide        slide;
  private final @NonNull  AudioManager      audioManager;
  private final @NonNull  SensorManager     sensorManager;
  private final @NonNull  Sensor            proximitySensor;
  private final @Nullable WakeLock          wakeLock;
  private final @NonNull  Intent            serviceIntent;
  private final @NonNull  ServiceConnection serviceConnection;

  private @NonNull  WeakReference<Listener>        listener;
  private @Nullable AudioPlayerService.LocalBinder binder;

  public synchronized static AudioSlidePlayer createFor(@NonNull Context context,
                                                        @NonNull AudioSlide slide,
                                                        @NonNull Listener listener)
  {
    if (playing.isPresent() && playing.get().getAudioSlide().equals(slide)) {
      playing.get().setListener(listener);
      return playing.get();
    } else {
      return new AudioSlidePlayer(context, slide, listener);
    }
  }

  private AudioSlidePlayer(@NonNull Context context,
                           @NonNull AudioSlide slide,
                           @NonNull Listener listener)
  {
    this.context              = context;
    this.slide                = slide;
    this.listener             = new WeakReference<>(listener);
    this.audioManager         = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    this.sensorManager        = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
    this.proximitySensor      = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

    if (Build.VERSION.SDK_INT >= 21) {
      this.wakeLock = ServiceUtil.getPowerManager(context).newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG);
    } else {
      this.wakeLock = null;
    }

    this.serviceIntent     = new Intent(context, AudioPlayerService.class);
    this.serviceConnection = new ServiceConnection() {
      @Override public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        binder = (AudioPlayerService.LocalBinder) iBinder;
        binder.setListener(AudioSlidePlayer.this);
      }

      @Override public void onServiceDisconnected(ComponentName componentName) {
        binder = null;
      }
    };
  }

  private void startService(final double progress, final boolean earpiece) {
    serviceIntent.putExtra(AudioPlayerService.MEDIA_URI_EXTRA, slide.getUri());
    serviceIntent.putExtra(AudioPlayerService.PROGRESS_EXTRA, progress);
    serviceIntent.putExtra(AudioPlayerService.EARPIECE_EXTRA, earpiece);
    Log.d(TAG, slide.getUri().toString());
    context.startService(serviceIntent);
    bindService();
  }

  private void bindService() {
    context.bindService(serviceIntent, serviceConnection, 0);
  }

  private void unbindService() {
    if (binder != null) {
      context.unbindService(serviceConnection);
      binder = null;
    }
  }

  public void play(final double progress) throws IOException {
    play(progress, false);
  }

  private void play(final double progress, boolean earpiece) {
    setPlaying(AudioSlidePlayer.this);
    startService(progress, earpiece);
  }

  public synchronized void stop() {
    Log.i(TAG, "Stop called!");

    removePlaying(this);
    if (binder != null) {
      binder.stop();
    }
  }

  public static void onResume() {
    if (!playing.isPresent()) return;
    AudioSlidePlayer player = playing.get();
    player.bindService();
  }

  public static void onPause() {
    if (!playing.isPresent()) return;
    AudioSlidePlayer player = playing.get();
    player.unbindService();
  }

  @Override
  public void onAudioStarted() {
    notifyOnStart();
  }

  @Override
  public void onAudioStopped() {
    unbindService();
    notifyOnStop();
  }

  @Override
  public void onAudioError(ExoPlaybackException error) {
    Toast.makeText(context, R.string.AudioSlidePlayer_error_playing_audio, Toast.LENGTH_SHORT).show();
  }

  @Override
  public void onAudioProgress(double progress, long millis) {
    notifyOnProgress(progress, millis);
  }

  public void setListener(@NonNull Listener listener) {
    this.listener = new WeakReference<>(listener);

//    if (this.mediaPlayer != null && this.mediaPlayer.getPlaybackState() == Player.STATE_READY) {
//      notifyOnStart();
//    }
  }

  public @NonNull AudioSlide getAudioSlide() {
    return slide;
  }

  private void notifyOnStart() {
    Util.runOnMain(new Runnable() {
      @Override
      public void run() {
        getListener().onStart();
      }
    });
  }

  private void notifyOnStop() {
    Util.runOnMain(new Runnable() {
      @Override
      public void run() {
        getListener().onStop();
      }
    });
  }

  private void notifyOnProgress(final double progress, final long millis) {
    Util.runOnMain(new Runnable() {
      @Override
      public void run() {
        getListener().onProgress(progress, millis);
      }
    });
  }

  private @NonNull Listener getListener() {
    Listener listener = this.listener.get();

    if (listener != null) return listener;
    else                  return new Listener() {
      @Override
      public void onStart() {}
      @Override
      public void onStop() {}
      @Override
      public void onProgress(double progress, long millis) {}
    };
  }

  private synchronized static void setPlaying(@NonNull AudioSlidePlayer player) {
    if (playing.isPresent() && playing.get() != player) {
      playing.get().notifyOnStop();
      playing.get().stop();
    }

    playing = Optional.of(player);
  }

  private synchronized static void removePlaying(@NonNull AudioSlidePlayer player) {
    if (playing.isPresent() && playing.get() == player) {
      playing = Optional.absent();
    }
  }
//
//  @Override
//  public void onSensorChanged(SensorEvent event) {
//    if (event.sensor.getType() != Sensor.TYPE_PROXIMITY) return;
//    if (mediaPlayer == null || mediaPlayer.getPlaybackState() != Player.STATE_READY) return;
//
//    int streamType;
//
//    if (event.values[0] < 5f && event.values[0] != proximitySensor.getMaximumRange()) {
//      streamType = AudioManager.STREAM_VOICE_CALL;
//    } else {
//      streamType = AudioManager.STREAM_MUSIC;
//    }
//
//    if (streamType == AudioManager.STREAM_VOICE_CALL &&
//        mediaPlayer.getAudioStreamType() != streamType &&
//        !audioManager.isWiredHeadsetOn())
//    {
//      double position = mediaPlayer.getCurrentPosition();
//      double duration = mediaPlayer.getDuration();
//      double progress = position / duration;
//
//      if (wakeLock != null) wakeLock.acquire();
//      stop();
//      try {
//        play(progress, true);
//      } catch (IOException e) {
//        Log.w(TAG, e);
//      }
//    } else if (streamType == AudioManager.STREAM_MUSIC &&
//               mediaPlayer.getAudioStreamType() != streamType &&
//               System.currentTimeMillis() - startTime > 500)
//    {
//      if (wakeLock != null) wakeLock.release();
//      stop();
//      notifyOnStop();
//    }
//  }
//
//  @Override
//  public void onAccuracyChanged(Sensor sensor, int accuracy) {
//
//  }

  public interface Listener {
    void onStart();
    void onStop();
    void onProgress(double progress, long millis);
  }
}
