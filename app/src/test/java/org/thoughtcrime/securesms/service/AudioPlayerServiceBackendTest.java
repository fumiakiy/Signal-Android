package org.thoughtcrime.securesms.service;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import android.service.autofill.FieldClassification.Match;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Player;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.OngoingStubbing;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.thoughtcrime.securesms.service.AudioPlayerServiceBackend.AudioStateListener;
import org.thoughtcrime.securesms.service.AudioPlayerServiceBackend.Command;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class)
public class AudioPlayerServiceBackendTest {
  private AudioPlayerServiceBackend backend;
  private Player.EventListener playerEventListener;

  @Mock
  private AudioManager audioManager;
  @Mock
  private MediaPlayer mediaPlayer;
  @Mock
  private MediaPlayerFactory mediaPlayerFactory;
  @Mock
  private ProximitySensor proximitySensor;
  @Mock
  private ServiceInterface serviceInterface;
  @Mock
  private WakeLock wakeLock;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    when(mediaPlayerFactory.create(anyObject(), anyObject(), anyBoolean())).thenReturn(mediaPlayer);
    backend = new AudioPlayerServiceBackend(audioManager, mediaPlayerFactory, proximitySensor, serviceInterface, wakeLock);
  }

  private void setupBound(Uri uri, double progress, AudioStateListener listener) {
    ArgumentCaptor<Player.EventListener> captor = ArgumentCaptor.forClass(Player.EventListener.class);
    when(mediaPlayerFactory.create(anyObject(), captor.capture(), anyBoolean())).thenReturn(mediaPlayer);
    Intent play = playCommand(uri, progress);
    backend.onStartCommand(play);
    AudioPlayerServiceBackend.LocalBinder binder = (AudioPlayerServiceBackend.LocalBinder) backend.onBind(play);
    binder.setListener(listener);
    playerEventListener = captor.getValue();
  }

  @Test
  public void playCommandStartsServiceAndCreateMediaPlayer() {
    Uri uri = Uri.parse("content://1");
    backend.onStartCommand(playCommand(uri, 0));
    verify(serviceInterface).startForeground(Command.PLAY);
    verify(mediaPlayerFactory).create(eq(uri), any(), eq(false));
  }

  @Test
  public void pauseCommandStopsAndReleaseMediaPlayer() {
    Uri uri = Uri.parse("content://2");
    backend.onStartCommand(playCommand(uri, 0));

    backend.onStartCommand(pauseCommand());
    verify(mediaPlayer).getCurrentPosition();
    verify(mediaPlayer).stop();
    verify(mediaPlayer).release();
    verify(serviceInterface).updateNotification(Command.PAUSE);
  }

  @Test
  public void resumeCommandCreatesMediaPlayer() {
    Uri uri = Uri.parse("content://2");
    backend.onStartCommand(playCommand(uri, 0));
    verify(mediaPlayerFactory).create(eq(uri), any(), eq(false));
    backend.onStartCommand(pauseCommand());

    backend.onStartCommand(resumeCommand());
    verify(mediaPlayerFactory, times(2)).create(eq(uri), any(), eq(false));
    verify(serviceInterface).updateNotification(Command.RESUME);
  }

  @Test
  public void externalListenerGetsNotifiedWhenPlayerIsReady() {
    Uri uri = Uri.parse("content://2");
    AudioStateListener mockListener = mock(AudioStateListener.class);
    setupBound(uri, 0, mockListener);

    // Simulate player becomes ready
    playerEventListener.onPlayerStateChanged(true, Player.STATE_READY);

    verify(mockListener).onAudioStarted();
    verify(mockListener, never()).onAudioError(anyObject());
    verify(mockListener, never()).onAudioStopped();
  }

  @Test
  public void externalListenerGetsNotifiedWhenPlaybackReachesAtEnd() {
    Uri uri = Uri.parse("content://2");
    AudioStateListener mockListener = mock(AudioStateListener.class);
    setupBound(uri, 0, mockListener);

    // Simulate player becomes ready
    playerEventListener.onPlayerStateChanged(true, Player.STATE_READY);
    // Simulate playback ends
    playerEventListener.onPlayerStateChanged(true, Player.STATE_ENDED);

    verify(mockListener).onAudioStopped();
}

  @Test
  public void externalListenerGetsNotifiedWhenPlaybackFailed() {
    Uri uri = Uri.parse("content://2");
    AudioStateListener mockListener = mock(AudioStateListener.class);
    setupBound(uri, 0, mockListener);

    // Simulate playback error
    ExoPlaybackException e = ExoPlaybackException.createForSource(new IOException());
    playerEventListener.onPlayerError(e);

    verify(mockListener).onAudioStopped();
    verify(mockListener).onAudioError(e);
  }

  private Intent playCommand(Uri uri, double progress) {
    Intent intent = new Intent();
    intent.putExtra(AudioPlayerServiceBackend.MEDIA_URI_EXTRA, uri);
    intent.putExtra(AudioPlayerServiceBackend.PROGRESS_EXTRA, progress);
    intent.putExtra(AudioPlayerServiceBackend.COMMAND_EXTRA, AudioPlayerServiceBackend.Command.PLAY);
    return intent;
  }

  private Intent pauseCommand() {
    Intent intent = new Intent();
    intent.putExtra(AudioPlayerServiceBackend.COMMAND_EXTRA, AudioPlayerServiceBackend.Command.PAUSE);
    return intent;
  }

  private Intent resumeCommand() {
    Intent intent = new Intent();
    intent.putExtra(AudioPlayerServiceBackend.COMMAND_EXTRA, AudioPlayerServiceBackend.Command.RESUME);
    return intent;
  }
}
