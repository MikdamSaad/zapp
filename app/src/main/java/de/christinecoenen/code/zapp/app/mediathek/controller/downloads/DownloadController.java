package de.christinecoenen.code.zapp.app.mediathek.controller.downloads;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;

import androidx.annotation.NonNull;

import com.tonyodev.fetch2.Download;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2.Fetch;
import com.tonyodev.fetch2.FetchConfiguration;
import com.tonyodev.fetch2.FetchListener;
import com.tonyodev.fetch2.NetworkType;
import com.tonyodev.fetch2.Request;
import com.tonyodev.fetch2.Status;
import com.tonyodev.fetch2.database.DownloadInfo;
import com.tonyodev.fetch2core.DownloadBlock;
import com.tonyodev.fetch2core.Downloader;
import com.tonyodev.fetch2okhttp.OkHttpDownloader;

import org.joda.time.DateTime;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.List;
import java.util.concurrent.TimeUnit;

import de.christinecoenen.code.zapp.app.mediathek.controller.downloads.exceptions.DownloadException;
import de.christinecoenen.code.zapp.app.mediathek.controller.downloads.exceptions.NoNetworkException;
import de.christinecoenen.code.zapp.app.mediathek.controller.downloads.exceptions.WrongNetworkConditionException;
import de.christinecoenen.code.zapp.app.mediathek.model.DownloadStatus;
import de.christinecoenen.code.zapp.app.mediathek.model.PersistedMediathekShow;
import de.christinecoenen.code.zapp.app.mediathek.model.Quality;
import de.christinecoenen.code.zapp.app.mediathek.repository.MediathekRepository;
import de.christinecoenen.code.zapp.app.settings.repository.SettingsRepository;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

public class DownloadController implements FetchListener {

	private final Fetch fetch;

	private final ConnectivityManager connectivityManager;
	private final SettingsRepository settingsRepository;
	private final DownloadFileInfoManager downloadFileInfoManager;
	private final MediathekRepository mediathekRepository;

	public DownloadController(Context applicationContext, MediathekRepository mediathekRepository) {
		this.mediathekRepository = mediathekRepository;

		settingsRepository = new SettingsRepository(applicationContext);
		downloadFileInfoManager = new DownloadFileInfoManager(applicationContext, settingsRepository);

		connectivityManager = (ConnectivityManager) applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);

		CookieManager cookieManager = new CookieManager();
		cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

		OkHttpClient client = new OkHttpClient.Builder()
			.readTimeout(40, TimeUnit.SECONDS) // fetch default: 20 seconds
			.connectTimeout(30, TimeUnit.SECONDS) // fetch default: 15 seconds
			.cache(null)
			.followRedirects(true)
			.followSslRedirects(true)
			.retryOnConnectionFailure(false)
			.cookieJar(new JavaNetCookieJar(cookieManager))
			.build();

		FetchConfiguration fetchConfiguration = new FetchConfiguration.Builder(applicationContext)
			.setNotificationManager(new ZappNotificationManager(applicationContext, mediathekRepository) {
				@NonNull
				@Override
				public Fetch getFetchInstanceForNamespace(@NonNull String namespace) {
					return fetch;
				}
			})
			.enableRetryOnNetworkGain(false)
			.setAutoRetryMaxAttempts(0)
			.setDownloadConcurrentLimit(1)
			.preAllocateFileOnCreation(false) // true causes downloads to sd card to hang
			.setHttpDownloader(new OkHttpDownloader(client, Downloader.FileDownloaderType.SEQUENTIAL))
			.enableLogging(true)
			.build();

		fetch = Fetch.Impl.getInstance(fetchConfiguration);
		fetch.addListener(this);
	}

	public Completable startDownload(PersistedMediathekShow show, Quality quality) {

		String downloadUrl = show.getMediathekShow().getVideoUrl(quality);

		return getDownload(show.getDownloadId())
			.flatMapCompletable(download -> {
				if (download.getId() != 0 && download.getUrl().equals(downloadUrl)) {
					// same quality as existing download
					// save new settings to request
					applySettingsToRequest(download.getRequest());
					fetch.updateRequest(download.getId(), download.getRequest(), false, null, null);

					// update show properties
					show.setDownloadedAt(DateTime.now());
					show.setDownloadProgress(0);
					mediathekRepository.updateShow(show);

					// retry
					fetch.retry(show.getDownloadId());
				} else {
					// delete old file with wrong quality
					fetch.delete(show.getDownloadId());

					String filePath = downloadFileInfoManager.getDownloadFilePath(show.getMediathekShow(), quality);

					Request request;
					try {
						request = new Request(downloadUrl, filePath);
						request.setIdentifier(show.getId());
					} catch (Exception e) {
						throw new DownloadException("Constructing download request failed.", e);
					}

					// update show properties
					show.setDownloadId(request.getId());
					show.setDownloadedAt(DateTime.now());
					show.setDownloadProgress(0);
					mediathekRepository.updateShow(show);

					enqueueDownload(request);
				}

				return Completable.complete();
			});
	}

	public void stopDownload(int id) {
		fetch.getDownloadsByRequestIdentifier(id, downloadList -> {
			for (Download download : downloadList) {
				fetch.cancel(download.getId());
			}
		});
	}

	public void deleteDownload(int id) {
		fetch.getDownloadsByRequestIdentifier(id, downloadList -> {
			for (Download download : downloadList) {
				fetch.delete(download.getId());
			}
		});
	}

	public Flowable<DownloadStatus> getDownloadStatus(String apiId) {
		return mediathekRepository.getDownloadStatus(apiId);
	}

	public Flowable<Integer> getDownloadProgress(String apiId) {
		return mediathekRepository.getDownloadProgress(apiId);
	}

	public void deleteDownloadsWithDeletedFiles() {
		fetch.getDownloadsWithStatus(Status.COMPLETED, downloads -> {
			for (Download download : downloads) {
				if (downloadFileInfoManager.shouldDeleteDownload(download)) {
					fetch.remove(download.getId());
				}
			}
		});
	}

	/**
	 * @return download with the given id or empty download with id of 0
	 */
	private Single<Download> getDownload(int downloadId) {
		return Single.create(emitter -> fetch.getDownload(downloadId, download -> {
			if (download == null) {
				emitter.onSuccess(new DownloadInfo());
			} else {
				emitter.onSuccess(download);
			}
		}));
	}

	private void enqueueDownload(Request request) {
		applySettingsToRequest(request);
		fetch.enqueue(request, null, null);
	}

	private void applySettingsToRequest(Request request) {
		NetworkType networkType = settingsRepository.getDownloadOverUnmeteredNetworkOnly() ?
			NetworkType.UNMETERED : NetworkType.ALL;
		request.setNetworkType(networkType);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager.getActiveNetwork() == null) {
			throw new NoNetworkException("No active network available.");
		}

		if (settingsRepository.getDownloadOverUnmeteredNetworkOnly() && connectivityManager.isActiveNetworkMetered()) {
			throw new WrongNetworkConditionException("Download over metered networks prohibited.");
		}
	}

	private void updateDownloadStatus(@NonNull Download download) {
		DownloadStatus downloadStatus = DownloadStatus.values()[download.getStatus().getValue()];
		mediathekRepository.updateDownloadStatus(download.getId(), downloadStatus);
	}

	private void updateDownloadProgress(@NonNull Download download, int progress) {
		mediathekRepository.updateDownloadProgress(download.getId(), progress);
	}

	@Override
	public void onAdded(@NonNull Download download) {
		updateDownloadStatus(download);
	}

	@Override
	public void onCancelled(@NonNull Download download) {
		fetch.delete(download.getId());
		updateDownloadStatus(download);
		updateDownloadProgress(download, 0);
	}

	@Override
	public void onCompleted(@NonNull Download download) {
		updateDownloadStatus(download);
		mediathekRepository.updateDownloadedVideoPath(download.getId(), download.getFile());
		downloadFileInfoManager.updateDownloadFileInMediaCollection(download);
	}

	@Override
	public void onDeleted(@NonNull Download download) {
		updateDownloadStatus(download);
		updateDownloadProgress(download, 0);
		downloadFileInfoManager.updateDownloadFileInMediaCollection(download);
	}

	@Override
	public void onDownloadBlockUpdated(@NonNull Download download, @NonNull DownloadBlock downloadBlock, int i) {

	}

	@Override
	public void onError(@NonNull Download download, @NonNull Error error, Throwable throwable) {
		downloadFileInfoManager.deleteDownloadFile(download);
		updateDownloadStatus(download);
	}

	@Override
	public void onPaused(@NonNull Download download) {
		updateDownloadStatus(download);
	}

	@Override
	public void onProgress(@NonNull Download download, long l, long l1) {
		updateDownloadProgress(download, download.getProgress());
	}

	@Override
	public void onQueued(@NonNull Download download, boolean b) {
		updateDownloadStatus(download);
	}

	@Override
	public void onRemoved(@NonNull Download download) {
		updateDownloadStatus(download);
	}

	@Override
	public void onResumed(@NonNull Download download) {
		updateDownloadStatus(download);
	}

	@Override
	public void onStarted(@NonNull Download download, @NonNull List<? extends DownloadBlock> list, int i) {
		updateDownloadStatus(download);
	}

	@Override
	public void onWaitingNetwork(@NonNull Download download) {
		updateDownloadStatus(download);
	}
}
