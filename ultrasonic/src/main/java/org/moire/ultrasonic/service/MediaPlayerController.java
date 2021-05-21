/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.service;

import android.content.Context;
import android.content.Intent;
import timber.log.Timber;

import org.koin.java.KoinJavaComponent;
import org.moire.ultrasonic.data.ActiveServerProvider;
import org.moire.ultrasonic.domain.MusicDirectory;
import org.moire.ultrasonic.domain.MusicDirectory.Entry;
import org.moire.ultrasonic.domain.PlayerState;
import org.moire.ultrasonic.domain.RepeatMode;
import org.moire.ultrasonic.domain.UserInfo;
import org.moire.ultrasonic.featureflags.Feature;
import org.moire.ultrasonic.featureflags.FeatureStorage;
import org.moire.ultrasonic.util.ShufflePlayBuffer;
import org.moire.ultrasonic.util.Util;

import java.util.Iterator;
import java.util.List;

import kotlin.Lazy;

import static org.koin.java.KoinJavaComponent.inject;

/**
 * The implementation of the Media Player Controller.
 * This class contains everything that is necessary for the Application UI
 * to control the Media Player implementation.
 *
 * @author Sindre Mehus, Joshua Bahnsen
 * @version $Id$
 */
public class MediaPlayerController
{
	private boolean created = false;
	private String suggestedPlaylistName;
	private boolean keepScreenOn;

	private boolean showVisualization;
	private boolean autoPlayStart;

	private final Context context;
	private final Lazy<JukeboxMediaPlayer> jukeboxMediaPlayer = inject(JukeboxMediaPlayer.class);
	private final Lazy<ActiveServerProvider> activeServerProvider = inject(ActiveServerProvider.class);

	private final DownloadQueueSerializer downloadQueueSerializer;
	private final ExternalStorageMonitor externalStorageMonitor;
	private final Downloader downloader;
	private final ShufflePlayBuffer shufflePlayBuffer;
	private final LocalMediaPlayer localMediaPlayer;

	public MediaPlayerController(Context context, DownloadQueueSerializer downloadQueueSerializer,
								 ExternalStorageMonitor externalStorageMonitor, Downloader downloader,
								 ShufflePlayBuffer shufflePlayBuffer, LocalMediaPlayer localMediaPlayer)
	{
		this.context = context;
		this.downloadQueueSerializer = downloadQueueSerializer;
		this.externalStorageMonitor = externalStorageMonitor;
		this.downloader = downloader;
		this.shufflePlayBuffer = shufflePlayBuffer;
		this.localMediaPlayer = localMediaPlayer;

		Timber.i("MediaPlayerController constructed");
	}

	public void onCreate()
	{
		if (created) return;
		this.externalStorageMonitor.onCreate(this::reset);

		setJukeboxEnabled(activeServerProvider.getValue().getActiveServer().getJukeboxByDefault());
		created = true;

		Timber.i("MediaPlayerController created");
	}

	public void onDestroy()
	{
		if (!created) return;
		externalStorageMonitor.onDestroy();
		context.stopService(new Intent(context, MediaPlayerService.class));
		downloader.onDestroy();
		created = false;

		Timber.i("MediaPlayerController destroyed");
	}
	public synchronized void restore(List<MusicDirectory.Entry> songs, final int currentPlayingIndex, final int currentPlayingPosition, final boolean autoPlay, boolean newPlaylist)
	{
		download(songs, false, false, false, false, newPlaylist);

		if (currentPlayingIndex != -1)
		{
			MediaPlayerService.executeOnStartedMediaPlayerService(context, (mediaPlayerService) ->
				 {
					mediaPlayerService.play(currentPlayingIndex, autoPlayStart);

					if (localMediaPlayer.currentPlaying != null)
					{
						if (autoPlay && jukeboxMediaPlayer.getValue().isEnabled())
						{
							jukeboxMediaPlayer.getValue().skip(downloader.getCurrentPlayingIndex(), currentPlayingPosition / 1000);
						}
						else
						{
							if (localMediaPlayer.currentPlaying.isCompleteFileAvailable())
							{
								localMediaPlayer.play(localMediaPlayer.currentPlaying, currentPlayingPosition, autoPlay);
							}
						}
					}
					autoPlayStart = false;
					return null;
				 }
			);
		}
	}

	public synchronized void preload()
	{
		MediaPlayerService.getInstance(context);
	}

	public synchronized void play(final int index)
	{
		MediaPlayerService.executeOnStartedMediaPlayerService(context, (mediaPlayerService) -> {
				mediaPlayerService.play(index, true);
					return null;
				}
		);
	}

	public synchronized void play()
	{
		MediaPlayerService.executeOnStartedMediaPlayerService(context, (mediaPlayerService) -> {

				mediaPlayerService.play();
					return null;
				}
		);
	}

	public synchronized void resumeOrPlay()
	{
		MediaPlayerService.executeOnStartedMediaPlayerService(context, (mediaPlayerService) -> {
				mediaPlayerService.resumeOrPlay();
					return null;
				}
		);
	}

	public synchronized void togglePlayPause()
	{
		if (localMediaPlayer.playerState == PlayerState.IDLE) autoPlayStart = true;
		MediaPlayerService.executeOnStartedMediaPlayerService(context, (mediaPlayerService) -> {
				mediaPlayerService.togglePlayPause();
					return null;
				}
		);
	}

	public synchronized void start()
	{
		MediaPlayerService.executeOnStartedMediaPlayerService(context, (mediaPlayerService) -> {
				mediaPlayerService.start();
					return null;
				}
		);
	}

	public synchronized void seekTo(final int position)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.seekTo(position);
	}

	public synchronized void pause()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.pause();
	}

	public synchronized void stop()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.stop();
	}

	public synchronized void download(List<MusicDirectory.Entry> songs, boolean save, boolean autoPlay, boolean playNext, boolean shuffle, boolean newPlaylist)
	{
		downloader.download(songs, save, autoPlay, playNext, newPlaylist);
		jukeboxMediaPlayer.getValue().updatePlaylist();

		if (shuffle) shuffle();

		if (!playNext && !autoPlay && (downloader.downloadList.size() - 1) == downloader.getCurrentPlayingIndex())
		{
			MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
			if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
		}

		if (autoPlay)
		{
			play(0);
		}
		else
		{
			if (localMediaPlayer.currentPlaying == null && downloader.downloadList.size() > 0)
			{
				localMediaPlayer.currentPlaying = downloader.downloadList.get(0);
				localMediaPlayer.currentPlaying.setPlaying(true);
			}

			downloader.checkDownloads();
		}

		downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList, downloader.getCurrentPlayingIndex(), getPlayerPosition());
	}

	public synchronized void downloadBackground(List<MusicDirectory.Entry> songs, boolean save)
	{
		downloader.downloadBackground(songs, save);
		downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList, downloader.getCurrentPlayingIndex(), getPlayerPosition());
	}

	public synchronized void setCurrentPlaying(DownloadFile currentPlaying)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) localMediaPlayer.setCurrentPlaying(currentPlaying);
	}

	public synchronized void setCurrentPlaying(int index)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setCurrentPlaying(index);
	}

	public synchronized void setPlayerState(PlayerState state)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) localMediaPlayer.setPlayerState(state);
	}

	public void stopJukeboxService()
	{
		jukeboxMediaPlayer.getValue().stopJukeboxService();
	}

	public synchronized void setShufflePlayEnabled(boolean enabled)
	{
		shufflePlayBuffer.isEnabled = enabled;
		if (enabled)
		{
			clear();
			downloader.checkDownloads();
		}
	}

	public boolean isShufflePlayEnabled()
	{
		return shufflePlayBuffer.isEnabled;
	}

	public synchronized void shuffle()
	{
		downloader.shuffle();

		downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList, downloader.getCurrentPlayingIndex(), getPlayerPosition());
		jukeboxMediaPlayer.getValue().updatePlaylist();

		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
	}

	public RepeatMode getRepeatMode()
	{
		return Util.getRepeatMode();
	}

	public synchronized void setRepeatMode(RepeatMode repeatMode)
	{
		Util.setRepeatMode(repeatMode);
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
	}

	public boolean getKeepScreenOn()
	{
		return keepScreenOn;
	}

	public void setKeepScreenOn(boolean keepScreenOn)
	{
		this.keepScreenOn = keepScreenOn;
	}

	public boolean getShowVisualization()
	{
		return showVisualization;
	}

	public void setShowVisualization(boolean showVisualization)
	{
		this.showVisualization = showVisualization;
	}

	public synchronized void clear()
	{
		clear(true);
	}

	public synchronized void clear(boolean serialize)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) {
			mediaPlayerService.clear(serialize);
		} else {
			// If no MediaPlayerService is available, just empty the playlist
			downloader.clear();
			if (serialize) {
				downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList,
					downloader.getCurrentPlayingIndex(), getPlayerPosition());
			}
		}

		jukeboxMediaPlayer.getValue().updatePlaylist();
	}

	public synchronized void clearIncomplete()
	{
		reset();
		Iterator<DownloadFile> iterator = downloader.downloadList.iterator();

		while (iterator.hasNext())
		{
			DownloadFile downloadFile = iterator.next();
			if (!downloadFile.isCompleteFileAvailable())
			{
				iterator.remove();
			}
		}

		downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList, downloader.getCurrentPlayingIndex(), getPlayerPosition());
		jukeboxMediaPlayer.getValue().updatePlaylist();
	}

	public synchronized void remove(DownloadFile downloadFile)
	{
		if (downloadFile == localMediaPlayer.currentPlaying)
		{
			reset();
			setCurrentPlaying(null);
		}

		downloader.removeDownloadFile(downloadFile);

		downloadQueueSerializer.serializeDownloadQueue(downloader.downloadList, downloader.getCurrentPlayingIndex(), getPlayerPosition());
		jukeboxMediaPlayer.getValue().updatePlaylist();

		if (downloadFile == localMediaPlayer.nextPlaying)
		{
			MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
			if (mediaPlayerService != null) mediaPlayerService.setNextPlaying();
		}
	}

	public synchronized void delete(List<MusicDirectory.Entry> songs)
	{
		for (MusicDirectory.Entry song : songs)
		{
			downloader.getDownloadFileForSong(song).delete();
		}
	}

	public synchronized void unpin(List<MusicDirectory.Entry> songs)
	{
		for (MusicDirectory.Entry song : songs)
		{
			downloader.getDownloadFileForSong(song).unpin();
		}
	}

	public synchronized void previous()
	{
		int index = downloader.getCurrentPlayingIndex();
		if (index == -1)
		{
			return;
		}

		// Restart song if played more than five seconds.
		if (getPlayerPosition() > 5000 || index == 0)
		{
			play(index);
		}
		else
		{
			play(index - 1);
		}
	}

	public synchronized void next()
	{
		int index = downloader.getCurrentPlayingIndex();
		if (index != -1)
		{
			switch (getRepeatMode())
			{
				case SINGLE:
				case OFF:
					if (index + 1 >= 0 && index + 1 < downloader.downloadList.size()) {
						play(index + 1);
					}
					break;
				case ALL:
					play((index + 1) % downloader.downloadList.size());
					break;
				default:
					break;
			}
		}
	}

	public synchronized void reset()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) localMediaPlayer.reset();
	}

	public synchronized int getPlayerPosition()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService == null) return 0;
		return mediaPlayerService.getPlayerPosition();
	}

	public synchronized int getPlayerDuration()
	{
		if (localMediaPlayer.currentPlaying != null)
		{
			Integer duration = localMediaPlayer.currentPlaying.getSong().getDuration();
			if (duration != null)
			{
				return duration * 1000;
			}
		}

		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService == null) return 0;
		return mediaPlayerService.getPlayerDuration();
	}

	public PlayerState getPlayerState()	{ return localMediaPlayer.playerState; }

	public void setSuggestedPlaylistName(String name)
	{
		this.suggestedPlaylistName = name;
	}

	public String getSuggestedPlaylistName()
	{
		return suggestedPlaylistName;
	}

	public boolean isJukeboxEnabled()
	{
		return jukeboxMediaPlayer.getValue().isEnabled();
	}

	public boolean isJukeboxAvailable()
	{
		try
		{
			String username = activeServerProvider.getValue().getActiveServer().getUserName();
			UserInfo user = MusicServiceFactory.getMusicService().getUser(username);
			return user.getJukeboxRole();
		}
		catch (Exception e)
		{
			Timber.w(e, "Error getting user information");
		}

		return false;
	}

	public void setJukeboxEnabled(boolean jukeboxEnabled)
	{
		jukeboxMediaPlayer.getValue().setEnabled(jukeboxEnabled);
		setPlayerState(PlayerState.IDLE);

		if (jukeboxEnabled)
		{
			jukeboxMediaPlayer.getValue().startJukeboxService();

			reset();

			// Cancel current download, if necessary.
			if (downloader.currentDownloading != null)
			{
				downloader.currentDownloading.cancelDownload();
			}
		}
		else
		{
			jukeboxMediaPlayer.getValue().stopJukeboxService();
		}
	}

	public void adjustJukeboxVolume(boolean up)
	{
		jukeboxMediaPlayer.getValue().adjustVolume(up);
	}

	public void setVolume(float volume)
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) localMediaPlayer.setVolume(volume);
	}

	public void updateNotification()
	{
		MediaPlayerService mediaPlayerService = MediaPlayerService.getRunningInstance();
		if (mediaPlayerService != null) mediaPlayerService.updateNotification(localMediaPlayer.playerState, localMediaPlayer.currentPlaying);
	}

	public void toggleSongStarred() {
		if (localMediaPlayer.currentPlaying == null)
			return;

		final Entry song = localMediaPlayer.currentPlaying.getSong();

		// Trigger an update
		localMediaPlayer.setCurrentPlaying(localMediaPlayer.currentPlaying);

		song.setStarred(!song.getStarred());
	}

    public void setSongRating(final int rating)
	{
		if (!KoinJavaComponent.get(FeatureStorage.class).isFeatureEnabled(Feature.FIVE_STAR_RATING))
			return;

		if (localMediaPlayer.currentPlaying == null)
			return;

		final Entry song = localMediaPlayer.currentPlaying.getSong();
		song.setUserRating(rating);

		new Thread(() -> {
			try
			{
				MusicServiceFactory.getMusicService().setRating(song.getId(), rating);
			}
			catch (Exception e)
			{
				Timber.e(e);
			}
		}).start();

		updateNotification();
	}

	public DownloadFile getCurrentPlaying() {
		return localMediaPlayer.currentPlaying;
	}

	public int getPlaylistSize() {
		return downloader.downloadList.size();
	}

	public int getCurrentPlayingNumberOnPlaylist() {
		return downloader.getCurrentPlayingIndex();
	}

	public DownloadFile getCurrentDownloading() {
		return downloader.currentDownloading;
	}

	public List<DownloadFile> getPlayList() {
		return downloader.downloadList;
	}

	public long getPlayListUpdateRevision() {
		return downloader.getDownloadListUpdateRevision();
	}

	public long getPlayListDuration() {
		return downloader.getDownloadListDuration();
	}

	public DownloadFile getDownloadFileForSong(Entry song) {
		return downloader.getDownloadFileForSong(song);
	}
}