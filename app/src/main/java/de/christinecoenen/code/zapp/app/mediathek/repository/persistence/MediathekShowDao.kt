package de.christinecoenen.code.zapp.app.mediathek.repository.persistence

import androidx.room.*
import de.christinecoenen.code.zapp.app.mediathek.model.DownloadStatus
import de.christinecoenen.code.zapp.app.mediathek.model.MediathekShow
import de.christinecoenen.code.zapp.app.mediathek.model.PersistedMediathekShow
import io.reactivex.Completable
import io.reactivex.Flowable

@Dao
interface MediathekShowDao {

	@Query("SELECT * FROM PersistedMediathekShow")
	fun getAll(): Flowable<List<PersistedMediathekShow>>

	@Query("SELECT * FROM PersistedMediathekShow WHERE id=:id")
	fun getFromId(id: Int): Flowable<PersistedMediathekShow>

	@Query("SELECT * FROM PersistedMediathekShow WHERE apiId=:apiId")
	fun getFromApiId(apiId: String): Flowable<PersistedMediathekShow>

	@Query("SELECT * FROM PersistedMediathekShow WHERE apiId=:apiId")
	fun getFromApiIdSync(apiId: String): PersistedMediathekShow?

	@Query("SELECT * FROM PersistedMediathekShow WHERE downloadId=:downloadId")
	fun getFromDownloadId(downloadId: Int): Flowable<PersistedMediathekShow>

	@Query("SELECT downloadStatus FROM PersistedMediathekShow WHERE apiId=:apiId")
	fun getDownloadStatus(apiId: String): Flowable<DownloadStatus>

	@Query("SELECT downloadProgress FROM PersistedMediathekShow WHERE apiId=:apiId")
	fun getDownloadProgress(apiId: String): Flowable<Int>

	@Insert
	fun insert(vararg show: PersistedMediathekShow): Completable

	@Update
	fun update(vararg show: PersistedMediathekShow): Completable

	@Transaction
	fun insertOrUpdate(show: MediathekShow) {
		val existingPersistedShow = getFromApiIdSync(show.apiId)

		if (existingPersistedShow == null) {
			// insert new show
			val newPersistedShow = PersistedMediathekShow()
			newPersistedShow.mediathekShow = show
			insert(newPersistedShow).blockingAwait()
		} else {
			// update existing show
			existingPersistedShow.mediathekShow = show
			update(existingPersistedShow).blockingAwait()
		}
	}

	@Query("UPDATE PersistedMediathekShow SET downloadStatus=:downloadStatus WHERE downloadId=:downloadId")
	fun updateDownloadStatus(downloadId: Int, downloadStatus: DownloadStatus): Completable

	@Query("UPDATE PersistedMediathekShow SET downloadProgress=:progress WHERE downloadId=:downloadId")
	fun updateDownloadProgress(downloadId: Int, progress: Int): Completable

	@Query("UPDATE PersistedMediathekShow SET downloadedVideoPath=:videoPath WHERE downloadId=:downloadId")
	fun updateDownloadedVideoPath(downloadId: Int, videoPath: String): Completable

	@Delete
	fun delete(show: PersistedMediathekShow): Completable
}