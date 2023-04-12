package org.koitharu.kotatsu.details.ui

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.base.domain.MangaDataRepository
import org.koitharu.kotatsu.base.domain.MangaIntent
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.toListItem
import org.koitharu.kotatsu.history.domain.HistoryRepository
import org.koitharu.kotatsu.local.domain.LocalMangaRepository
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.utils.ext.printStackTraceDebug
import org.koitharu.kotatsu.utils.ext.runCatchingCancellable
import javax.inject.Inject

@ViewModelScoped
class MangaDetailsDelegate @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaDataRepository: MangaDataRepository,
	private val historyRepository: HistoryRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {
	private val intent = MangaIntent(savedStateHandle)
	private val mangaData = MutableStateFlow(intent.manga)

	val selectedBranch = MutableStateFlow<String?>(null)

	// Remote manga for saved and saved for remote
	val relatedManga = MutableStateFlow<Manga?>(null)
	val manga: StateFlow<Manga?>
		get() = mangaData
	val mangaId = intent.manga?.id ?: intent.mangaId

	suspend fun doLoad() {
		var manga = mangaDataRepository.resolveIntent(intent) ?: throw NotFoundException("Cannot find manga", "")
		mangaData.value = manga
		manga = mangaRepositoryFactory.create(manga.source).getDetails(manga)
		// find default branch
		val hist = historyRepository.getOne(manga)
		selectedBranch.value = manga.getPreferredBranch(hist)
		mangaData.value = manga
		relatedManga.value = runCatchingCancellable {
			if (manga.source == MangaSource.LOCAL) {
				val m = localMangaRepository.getRemoteManga(manga) ?: return@runCatchingCancellable null
				mangaRepositoryFactory.create(m.source).getDetails(m)
			} else {
				localMangaRepository.findSavedManga(manga)?.manga
			}
		}.onFailure { error ->
			error.printStackTraceDebug()
		}.getOrNull()
	}

	fun mapChapters(
		manga: Manga?,
		related: Manga?,
		history: MangaHistory?,
		newCount: Int,
		branch: String?,
	): List<ChapterListItem> {
		val chapters = manga?.chapters ?: return emptyList()
		val relatedChapters = related?.chapters
		return if (related?.source != MangaSource.LOCAL && !relatedChapters.isNullOrEmpty()) {
			mapChaptersWithSource(chapters, relatedChapters, history?.chapterId, newCount, branch)
		} else {
			mapChapters(chapters, relatedChapters, history?.chapterId, newCount, branch)
		}
	}

	private fun mapChapters(
		chapters: List<MangaChapter>,
		downloadedChapters: List<MangaChapter>?,
		currentId: Long?,
		newCount: Int,
		branch: String?,
	): List<ChapterListItem> {
		val result = ArrayList<ChapterListItem>(chapters.size)
		val currentIndex = chapters.indexOfFirst { it.id == currentId }
		val firstNewIndex = chapters.size - newCount
		val downloadedIds = downloadedChapters?.mapTo(HashSet(downloadedChapters.size)) { it.id }
		for (i in chapters.indices) {
			val chapter = chapters[i]
			if (chapter.branch != branch) {
				continue
			}
			result += chapter.toListItem(
				isCurrent = i == currentIndex,
				isUnread = i > currentIndex,
				isNew = i >= firstNewIndex,
				isMissing = false,
				isDownloaded = downloadedIds?.contains(chapter.id) == true,
			)
		}
		if (result.size < chapters.size / 2) {
			result.trimToSize()
		}
		return result
	}

	private fun mapChaptersWithSource(
		chapters: List<MangaChapter>,
		sourceChapters: List<MangaChapter>,
		currentId: Long?,
		newCount: Int,
		branch: String?,
	): List<ChapterListItem> {
		val chaptersMap = chapters.associateByTo(HashMap(chapters.size)) { it.id }
		val result = ArrayList<ChapterListItem>(sourceChapters.size)
		val currentIndex = sourceChapters.indexOfFirst { it.id == currentId }
		val firstNewIndex = sourceChapters.size - newCount
		for (i in sourceChapters.indices) {
			val chapter = sourceChapters[i]
			val localChapter = chaptersMap.remove(chapter.id)
			if (chapter.branch != branch) {
				continue
			}
			result += localChapter?.toListItem(
				isCurrent = i == currentIndex,
				isUnread = i > currentIndex,
				isNew = i >= firstNewIndex,
				isMissing = false,
				isDownloaded = false,
			) ?: chapter.toListItem(
				isCurrent = i == currentIndex,
				isUnread = i > currentIndex,
				isNew = i >= firstNewIndex,
				isMissing = true,
				isDownloaded = false,
			)
		}
		if (chaptersMap.isNotEmpty()) { // some chapters on device but not online source
			result.ensureCapacity(result.size + chaptersMap.size)
			chaptersMap.values.mapNotNullTo(result) {
				if (it.branch == branch) {
					it.toListItem(
						isCurrent = false,
						isUnread = true,
						isNew = false,
						isMissing = false,
						isDownloaded = false,
					)
				} else {
					null
				}
			}
			result.sortBy { it.chapter.number }
		}
		if (result.size < sourceChapters.size / 2) {
			result.trimToSize()
		}
		return result
	}
}
