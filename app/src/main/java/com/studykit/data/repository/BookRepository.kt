package com.studykit.data.repository

import com.studykit.data.dao.BookDao
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.Excerpt
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class BookRepository(private val bookDao: BookDao) {

    fun observeAll(): Flow<List<Book>> = bookDao.observeAll()

    fun observeById(id: Long): Flow<Book?> = bookDao.observeById(id)

    suspend fun getById(id: Long): Book? = bookDao.getById(id)

    suspend fun add(title: String, author: String, totalPages: Int): Long =
        bookDao.insert(
            Book(uuid = UUID.randomUUID().toString(), title = title, author = author, totalPages = totalPages),
        )

    suspend fun update(book: Book) = bookDao.update(book)

    /** 更新阅读进度（当前页码） */
    suspend fun updateProgress(id: Long, page: Int) = bookDao.updateProgress(id, page)

    /** 标记书籍为已读完 */
    suspend fun markFinished(id: Long) = bookDao.markFinished(id, System.currentTimeMillis())

    fun observeExcerpts(bookId: Long): Flow<List<Excerpt>> = bookDao.observeExcerpts(bookId)

    fun observeExcerptCount(): Flow<Int> = bookDao.observeExcerptCount()

    fun observeExcerpt(id: Long): Flow<Excerpt?> = bookDao.observeExcerpt(id)

    suspend fun getExcerpt(id: Long): Excerpt? = bookDao.getExcerpt(id)

    suspend fun addExcerpt(bookId: Long, content: String, pageNo: Int?): Long =
        bookDao.insertExcerpt(
            Excerpt(uuid = UUID.randomUUID().toString(), bookId = bookId, content = content, pageNo = pageNo),
        )

    suspend fun updateExcerpt(excerpt: Excerpt) = bookDao.updateExcerpt(excerpt)

    suspend fun deleteExcerpt(excerpt: Excerpt) = bookDao.deleteExcerpt(excerpt)

    fun observeReviews(bookId: Long): Flow<List<BookReview>> = bookDao.observeReviews(bookId)

    fun observeReview(id: Long): Flow<BookReview?> = bookDao.observeReview(id)

    suspend fun getReview(id: Long): BookReview? = bookDao.getReview(id)

    suspend fun addReview(bookId: Long, rating: Int, content: String): Long =
        bookDao.insertReview(
            BookReview(
                uuid = UUID.randomUUID().toString(),
                bookId = bookId,
                rating = rating.coerceIn(1, 5),
                content = content,
            ),
        )

    suspend fun updateReview(review: BookReview) =
        bookDao.updateReview(review.copy(rating = review.rating.coerceIn(1, 5)))
}
